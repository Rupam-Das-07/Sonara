'use strict';

/**
 * healthChecker.js — Sonara backend API Health & Status Checker (operational tooling).
 *
 * ROLE
 * ----
 * A THIN, external, read-only prober. It answers one operational question:
 * "From where this process runs, is the Sonara backend stack reachable and
 * behaving?" It performs NO business logic, mutates NO state, and reuses the
 * canonical configuration (../config/env) rather than re-deriving anything.
 *
 * PROCESS-BOUNDARY REALITY (verified against source)
 * --------------------------------------------------
 * A standalone CLI is a *separate OS process* from the running Express server.
 * It therefore CANNOT observe the server's in-memory caches, JioSaavn circuit
 * breaker, or rate-limiter queue depths — those live only inside the server
 * process. This checker is consequently an EXTERNAL prober:
 *   - Tier 1: local host / runtime / config / filesystem (in-process, cheap).
 *   - Tier 2: loopback HTTP probes to the running stack (gateway + Python svcs).
 *   - Tier 3: OPT-IN (--external) reachability of third-party upstreams, plus
 *             the *configured* rate-limiter policy (static constants, NOT live
 *             server queue state — explicitly labelled as such).
 *
 * IMPORTANT: This module never calls process.exit and never prints. It returns
 * a plain report object. The CLI layer (scripts/check-api-health.js) owns
 * process.exit, argument parsing, and stdout formatting.
 *
 * STATUS MODEL
 * ------------
 *   HEALTHY        — check passed.
 *   DEGRADED       — non-fatal problem; core feature reachable but impaired.
 *   DOWN           — the backend gateway itself is unreachable / not serving.
 *   NOT_CONFIGURED — a check could not run because its target config is absent.
 *
 * OVERALL ROLLUP (worst-wins), with two hard rules from the spec:
 *   1. Only the backend GATEWAY being unreachable yields overall DOWN.
 *   2. Optional third-party upstreams (JioSaavn / ListenBrainz / trending /
 *      Google Suggest) MUST NOT turn the backend DOWN — their contribution to
 *      the overall verdict is capped at DEGRADED. (Trending is served by the
 *      Python ytmusic service, whose failure is already only DEGRADED.)
 *
 * SECURITY
 * --------
 * Never emits API keys, tokens, cookies, credentials, stream/signed URLs, full
 * environment variables, or file contents. Probe targets are known non-secret
 * constants (loopback URLs / public upstream hosts) with query strings and any
 * userinfo stripped before they appear in a report.
 */

const fs = require('fs');
const path = require('path');
const config = require('../config/env');

/** Canonical status vocabulary. */
const STATUS = Object.freeze({
  HEALTHY: 'HEALTHY',
  DEGRADED: 'DEGRADED',
  DOWN: 'DOWN',
  NOT_CONFIGURED: 'NOT_CONFIGURED',
});

/** Severity ranking for worst-wins rollups (higher = worse). */
const RANK = Object.freeze({
  [STATUS.HEALTHY]: 0,
  [STATUS.NOT_CONFIGURED]: 1,
  [STATUS.DEGRADED]: 2,
  [STATUS.DOWN]: 3,
});

const SCHEMA_VERSION = 1;
const DEFAULT_TIMEOUT_MS = 5000;
const MIN_NODE_MAJOR = 20; // package.json engines.node >= 20

// Third-party upstream endpoints, grounded in the real source:
//   - Google Suggest: src/routes/search.js
//   - JioSaavn search API: src/stream/providers/jiosaavnStreamProvider.js
// Health probing hits ONLY these lightweight JSON endpoints. No media download.
const GOOGLE_SUGGEST_PROBE_URL =
  'https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=health';
const JIOSAAVN_PROBE_URL =
  'https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&cc=in&p=1&n=1&q=health';

// ─────────────────────────────────────────────────────────────────────────────
// Small pure helpers (independently unit-testable)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the worse of two statuses per RANK.
 * @param {string} a
 * @param {string} b
 * @returns {string}
 */
function worseOf(a, b) {
  return RANK[a] >= RANK[b] ? a : b;
}

/**
 * Reduces a list of statuses to the single worst one.
 * @param {string[]} statuses
 * @param {string} [floor=STATUS.HEALTHY]
 * @returns {string}
 */
function worstStatus(statuses, floor = STATUS.HEALTHY) {
  return statuses.reduce((acc, s) => worseOf(acc, s), floor);
}

/**
 * Rolls up core (Tier 1 + Tier 2) checks. A core check reporting
 * NOT_CONFIGURED is treated as DEGRADED (a missing core target is a real
 * operational problem, not a benign skip).
 * @param {{status:string}[]} coreChecks
 * @returns {string}
 */
function rollupCore(coreChecks) {
  const mapped = coreChecks.map((c) =>
    c.status === STATUS.NOT_CONFIGURED ? STATUS.DEGRADED : c.status
  );
  return worstStatus(mapped, STATUS.HEALTHY);
}

/**
 * Rolls up the OPTIONAL external (Tier 3) contribution to the overall verdict.
 * Rules: 'advisory' checks never change the verdict; any external DOWN is
 * capped to DEGRADED; external NOT_CONFIGURED is a benign skip (neutral).
 * @param {{status:string, impact:string}[]} externalChecks
 * @returns {string}
 */
function rollupExternalContribution(externalChecks) {
  let worst = STATUS.HEALTHY;
  for (const c of externalChecks) {
    if (c.impact === 'advisory') continue;
    if (c.status === STATUS.NOT_CONFIGURED) continue;
    const capped = c.status === STATUS.DOWN ? STATUS.DEGRADED : c.status;
    worst = worseOf(worst, capped);
  }
  return worst;
}

/**
 * Maps an overall status to a process exit code (used by the CLI layer only).
 *   HEALTHY → 0 ; DEGRADED/NOT_CONFIGURED → 1 ; DOWN → 2.
 * @param {string} status
 * @returns {0|1|2}
 */
function statusToExitCode(status) {
  if (status === STATUS.HEALTHY) return 0;
  if (status === STATUS.DOWN) return 2;
  return 1; // DEGRADED or NOT_CONFIGURED
}

/**
 * Sanitizes a URL for safe display: keeps scheme + host + path, strips the
 * query string and any embedded userinfo. Returns a best-effort string even
 * for malformed input (never throws).
 * @param {string} raw
 * @returns {string}
 */
function sanitizeUrl(raw) {
  if (!raw || typeof raw !== 'string') return '';
  try {
    const u = new URL(raw);
    return `${u.protocol}//${u.host}${u.pathname === '/' ? '' : u.pathname}`;
  } catch {
    // Fall back to stripping a query string manually; never echo credentials.
    return String(raw).split('?')[0];
  }
}

/**
 * Produces a short, non-sensitive error descriptor from a caught error.
 * Deliberately avoids echoing err.message verbatim (which for fetch can embed
 * the full target URL). Emits a stable code/name only.
 * @param {any} err
 * @returns {string}
 */
function safeErrorCode(err) {
  if (!err) return 'ERROR';
  if (err.name === 'TimeoutError') return 'TIMEOUT';
  if (err.name === 'AbortError') return 'TIMEOUT';
  // Node's global fetch wraps transport failures in a TypeError whose real
  // OS-level code lives on err.cause (e.g. ECONNREFUSED, ENOTFOUND). Prefer it.
  if (err.cause && (err.cause.code || err.cause.name)) {
    return String(err.cause.code || err.cause.name);
  }
  return String(err.code || err.name || 'ERROR');
}

// ─────────────────────────────────────────────────────────────────────────────
// Probe primitive
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Performs a single HTTP probe with a hard timeout. Never throws.
 * @param {object} args
 * @param {string} args.url
 * @param {number} args.timeoutMs
 * @param {function} args.fetchImpl
 * @param {string} [args.method='GET']
 * @param {function} [args.now=Date.now]
 * @returns {Promise<{ok:boolean, httpStatus:(number|null), latencyMs:number, errorCode?:string, bodyText?:string}>}
 */
async function probeHttp({ url, timeoutMs, fetchImpl, method = 'GET', now = Date.now, readBody = false }) {
  const t0 = now();
  try {
    const res = await fetchImpl(url, {
      method,
      signal: AbortSignal.timeout(timeoutMs),
      headers: { Accept: 'application/json' },
    });
    const latencyMs = now() - t0;
    let bodyText;
    if (readBody) {
      try {
        bodyText = await res.text();
      } catch {
        bodyText = undefined;
      }
    }
    return { ok: res.ok, httpStatus: res.status, latencyMs, bodyText };
  } catch (err) {
    return { ok: false, httpStatus: null, latencyMs: now() - t0, errorCode: safeErrorCode(err) };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tier 1 — local host / runtime / config / filesystem
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Runtime + host check. Reports Node version, platform, arch, RSS. HEALTHY
 * unless the Node major version is below the supported floor (then DEGRADED).
 * @param {object} deps
 * @returns {object} check
 */
function checkRuntime(deps = {}) {
  const version = deps.nodeVersion || process.version; // e.g. 'v22.23.2'
  const platform = deps.platform || process.platform;
  const arch = deps.arch || process.arch;
  const rssBytes = (deps.memoryUsage || process.memoryUsage)().rss;
  const major = parseInt(String(version).replace(/^v/, '').split('.')[0], 10);

  const belowFloor = Number.isFinite(major) && major < MIN_NODE_MAJOR;
  return {
    id: 'runtime',
    label: 'Node.js runtime',
    tier: 1,
    status: belowFloor ? STATUS.DEGRADED : STATUS.HEALTHY,
    detail: `node ${version} on ${platform}/${arch}, rss ${Math.round(rssBytes / 1024 / 1024)}MB`,
    ...(belowFloor ? { note: `Node ${major} is below the supported floor (>= ${MIN_NODE_MAJOR}).` } : {}),
  };
}

/**
 * Configuration check. Confirms the canonical config object loaded and that the
 * probe targets are present. Emits only allow-listed, non-secret fields.
 * @param {object} cfg — the resolved config (defaults to ../config/env)
 * @returns {object} check
 */
function checkConfig(cfg) {
  const hasYt = typeof cfg.pythonYtmusicUrl === 'string' && cfg.pythonYtmusicUrl.length > 0;
  const hasAudio = typeof cfg.pythonAudioUrl === 'string' && cfg.pythonAudioUrl.length > 0;
  const hasPort = Number.isInteger(cfg.port) && cfg.port > 0;

  const missing = [];
  if (!hasPort) missing.push('PORT');
  if (!hasYt) missing.push('PYTHON_YTMUSIC_URL');
  if (!hasAudio) missing.push('PYTHON_AUDIO_URL');

  return {
    id: 'config',
    label: 'Runtime configuration',
    tier: 1,
    status: missing.length === 0 ? STATUS.HEALTHY : STATUS.NOT_CONFIGURED,
    detail:
      `env=${cfg.nodeEnv}, port=${hasPort ? cfg.port : 'unset'}, ` +
      `ytmusic=${hasYt ? sanitizeUrl(cfg.pythonYtmusicUrl) : 'unset'}, ` +
      `audio=${hasAudio ? sanitizeUrl(cfg.pythonAudioUrl) : 'unset'}`,
    ...(missing.length ? { note: `Missing config: ${missing.join(', ')}.` } : {}),
  };
}

/**
 * Filesystem check for the persistent identity store. Reports existence,
 * readability, size, and JSON-parseability — never the file CONTENTS.
 * A missing/unreadable/corrupt store is DEGRADED (backend can still run and
 * rebuild identity data), never DOWN.
 * @param {object} deps
 * @returns {object} check
 */
function checkIdentityStore(deps = {}) {
  const fsImpl = deps.fs || fs;
  // Backend root is two levels up from src/operations/.
  const storePath =
    deps.storePath || path.join(__dirname, '..', '..', 'data', 'identity_store.json');
  const relLabel = 'data/identity_store.json';

  let stat;
  try {
    stat = fsImpl.statSync(storePath);
  } catch {
    return {
      id: 'identity_store',
      label: 'Identity store file',
      tier: 1,
      status: STATUS.DEGRADED,
      detail: `${relLabel} not found`,
      note: 'Backend can rebuild identity data on demand; treated as non-fatal.',
    };
  }

  let raw;
  try {
    raw = fsImpl.readFileSync(storePath, 'utf8');
  } catch {
    return {
      id: 'identity_store',
      label: 'Identity store file',
      tier: 1,
      status: STATUS.DEGRADED,
      detail: `${relLabel} present (${stat.size} bytes) but not readable`,
    };
  }

  let entryCount = null;
  let parseable = true;
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) entryCount = parsed.length;
    else if (parsed && typeof parsed === 'object') entryCount = Object.keys(parsed).length;
  } catch {
    parseable = false;
  }

  if (!parseable) {
    return {
      id: 'identity_store',
      label: 'Identity store file',
      tier: 1,
      status: STATUS.DEGRADED,
      detail: `${relLabel} present (${stat.size} bytes) but not valid JSON`,
    };
  }

  return {
    id: 'identity_store',
    label: 'Identity store file',
    tier: 1,
    status: STATUS.HEALTHY,
    detail: `${relLabel} ok (${stat.size} bytes${entryCount != null ? `, ${entryCount} entries` : ''})`,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Tier 2 — loopback HTTP probes to the running stack
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a Tier 2 loopback check from a probe result.
 * @param {object} args
 * @returns {object} check
 */
function classifyLoopback({ id, label, target, probe, downStatus }) {
  if (probe.ok) {
    return {
      id,
      label,
      tier: 2,
      status: STATUS.HEALTHY,
      target,
      latencyMs: probe.latencyMs,
      detail: `HTTP ${probe.httpStatus} in ${probe.latencyMs}ms`,
    };
  }
  const reason = probe.httpStatus != null ? `HTTP ${probe.httpStatus}` : `unreachable (${probe.errorCode})`;
  return {
    id,
    label,
    tier: 2,
    status: downStatus,
    target,
    latencyMs: probe.latencyMs,
    detail: reason,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Tier 3 — opt-in external upstream reachability + configured rate-limit policy
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a Tier 3 external reachability check. External failures are reported
 * honestly but are capped (via rollupExternalContribution) so they can never
 * force the backend DOWN.
 * @param {object} args
 * @returns {object} check
 */
function classifyExternal({ id, label, target, probe }) {
  if (probe.ok) {
    return {
      id,
      label,
      tier: 3,
      impact: 'degrade',
      status: STATUS.HEALTHY,
      target,
      latencyMs: probe.latencyMs,
      detail: `HTTP ${probe.httpStatus} in ${probe.latencyMs}ms`,
    };
  }
  const reason = probe.httpStatus != null ? `HTTP ${probe.httpStatus}` : `unreachable (${probe.errorCode})`;
  return {
    id,
    label,
    tier: 3,
    impact: 'degrade',
    status: STATUS.DEGRADED, // optional upstream: never DOWN
    target,
    latencyMs: probe.latencyMs,
    detail: reason,
    note: 'Optional upstream with a graceful fallback; cannot force backend DOWN.',
  };
}

/**
 * Reports the CONFIGURED rate-limiter policy (static module constants) as an
 * advisory/informational check. This is NOT a live reachability probe and does
 * NOT reflect the running server's queue state (separate process). Only
 * drainIntervalMs and maxQueueDepth are surfaced; live counters are omitted
 * because in this CLI process they are always zero and would be misleading.
 * @param {object} deps
 * @returns {object[]} checks
 */
function checkRateLimiterPolicy(deps = {}) {
  const results = [];
  const sources = [
    { id: 'ratelimit_musicbrainz', label: 'MusicBrainz rate-limit policy', load: deps.loadMb || (() => require('../identity/mbRateLimiter').mbRateLimiter) },
    { id: 'ratelimit_listenbrainz', label: 'ListenBrainz rate-limit policy', load: deps.loadLb || (() => require('../recommendation/lbRateLimiter').lbRateLimiter) },
  ];
  for (const src of sources) {
    try {
      const limiter = src.load();
      const m = limiter.getMetrics();
      results.push({
        id: src.id,
        label: src.label,
        tier: 3,
        impact: 'advisory',
        status: STATUS.HEALTHY,
        detail: `configured policy: 1 req / ${m.drainIntervalMs}ms, max queue depth ${m.maxQueueDepth}`,
        note: 'Configured policy from source constants; not a live probe and not live server queue state.',
      });
    } catch (err) {
      results.push({
        id: src.id,
        label: src.label,
        tier: 3,
        impact: 'advisory',
        status: STATUS.NOT_CONFIGURED,
        detail: `policy unavailable (${safeErrorCode(err)})`,
      });
    }
  }
  return results;
}

// ─────────────────────────────────────────────────────────────────────────────
// Orchestrator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Runs the full health check and returns a machine-readable report. Never
 * throws; never prints; never calls process.exit.
 *
 * @param {object} [options]
 * @param {boolean} [options.includeExternal=false] — run Tier 3 checks.
 * @param {boolean} [options.skipGateway=false]     — skip gateway loopback probe (in-process calls).
 * @param {number}  [options.timeoutMs=5000]        — per-probe timeout.
 * @param {function}[options.fetchImpl=globalThis.fetch] — injectable fetch.
 * @param {object}  [options.configOverride]        — injectable config (tests).
 * @param {function}[options.now=Date.now]          — injectable clock (tests).
 * @param {object}  [options.deps]                  — injectable Tier1/Tier3 deps.
 * @returns {Promise<object>} HealthReport
 */
async function checkHealth(options = {}) {
  const {
    includeExternal = false,
    skipGateway = false,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    fetchImpl = globalThis.fetch,
    configOverride,
    now = Date.now,
    deps = {},
  } = options;

  const cfg = configOverride || config;
  const startedAt = now();

  // ── Tier 1 (synchronous, in-process) ──────────────────────────────────────
  const tier1 = [checkRuntime(deps), checkConfig(cfg), checkIdentityStore(deps)];

  // ── Tier 2 (loopback HTTP, parallel) ──────────────────────────────────────
  const gatewayUrl = `http://127.0.0.1:${cfg.port}/health`;
  const ytmusicUrl = `${cfg.pythonYtmusicUrl}/health`;
  const audioUrl = `${cfg.pythonAudioUrl}/`; // audio service exposes root, not /health

  const [gatewayProbe, ytmusicProbe, audioProbe] = await Promise.all([
    skipGateway
      ? Promise.resolve(null)
      : probeHttp({ url: gatewayUrl, timeoutMs, fetchImpl, now }),
    probeHttp({ url: ytmusicUrl, timeoutMs, fetchImpl, now }),
    probeHttp({ url: audioUrl, timeoutMs, fetchImpl, now }),
  ]);

  const gatewayCheck = skipGateway
    ? {
        id: 'gateway',
        label: 'Backend gateway (/health)',
        tier: 2,
        status: STATUS.HEALTHY,
        target: sanitizeUrl(gatewayUrl),
        latencyMs: 0,
        detail: 'in-process (gateway serving this request)',
      }
    : classifyLoopback({
        id: 'gateway',
        label: 'Backend gateway (/health)',
        target: sanitizeUrl(gatewayUrl),
        probe: gatewayProbe,
        downStatus: STATUS.DOWN, // ONLY the gateway can drive overall DOWN
      });

  const tier2 = [
    gatewayCheck,
    classifyLoopback({
      id: 'python_ytmusic',
      label: 'Python ytmusic service (/health)',
      target: sanitizeUrl(ytmusicUrl),
      probe: ytmusicProbe,
      downStatus: STATUS.DEGRADED, // core dep, but gateway may still serve
    }),
    classifyLoopback({
      id: 'python_audio',
      label: 'Python audio service (/)',
      target: sanitizeUrl(audioUrl),
      probe: audioProbe,
      downStatus: STATUS.DEGRADED,
    }),
  ];

  const coreChecks = [...tier1, ...tier2];

  // ── Tier 3 (opt-in) ───────────────────────────────────────────────────────
  let externalChecks = [];
  if (includeExternal) {
    const [suggestProbe, jiosaavnProbe] = await Promise.all([
      probeHttp({ url: GOOGLE_SUGGEST_PROBE_URL, timeoutMs, fetchImpl, now }),
      probeHttp({ url: JIOSAAVN_PROBE_URL, timeoutMs, fetchImpl, now }),
    ]);
    externalChecks = [
      classifyExternal({
        id: 'google_suggest',
        label: 'Google Suggest (search autocomplete)',
        target: sanitizeUrl(GOOGLE_SUGGEST_PROBE_URL),
        probe: suggestProbe,
      }),
      classifyExternal({
        id: 'jiosaavn',
        label: 'JioSaavn search API (HQ stream provider)',
        target: sanitizeUrl(JIOSAAVN_PROBE_URL),
        probe: jiosaavnProbe,
      }),
      ...checkRateLimiterPolicy(deps),
    ];
  }

  const allChecks = [...coreChecks, ...externalChecks];

  // ── Overall rollup ────────────────────────────────────────────────────────
  const overall = worseOf(
    rollupCore(coreChecks),
    includeExternal ? rollupExternalContribution(externalChecks) : STATUS.HEALTHY
  );

  const counts = { healthy: 0, degraded: 0, down: 0, notConfigured: 0 };
  for (const c of allChecks) {
    if (c.status === STATUS.HEALTHY) counts.healthy++;
    else if (c.status === STATUS.DEGRADED) counts.degraded++;
    else if (c.status === STATUS.DOWN) counts.down++;
    else if (c.status === STATUS.NOT_CONFIGURED) counts.notConfigured++;
  }

  return {
    schemaVersion: SCHEMA_VERSION,
    status: overall,
    generatedAt: new Date(startedAt).toISOString(),
    durationMs: now() - startedAt,
    includeExternal,
    counts,
    checks: allChecks,
  };
}

module.exports = {
  checkHealth,
  STATUS,
  RANK,
  SCHEMA_VERSION,
  // Exposed for unit testing / reuse:
  worseOf,
  worstStatus,
  rollupCore,
  rollupExternalContribution,
  statusToExitCode,
  sanitizeUrl,
  safeErrorCode,
  probeHttp,
  checkRuntime,
  checkConfig,
  checkIdentityStore,
  classifyLoopback,
  classifyExternal,
  checkRateLimiterPolicy,
};
