'use strict';

/**
 * healthChecker.test.js — unit tests for the operational Health & Status Checker.
 *
 * Hermetic: every external boundary (fetch, config, fs, rate-limiter modules,
 * clock) is injected. No real network, no real filesystem, no upstream traffic.
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const hc = require('../../src/operations/healthChecker');
const { STATUS } = hc;

// ── Test doubles ─────────────────────────────────────────────────────────────

/**
 * Builds a mock fetch that routes by URL substring.
 * @param {Array<{match:string, ok?:boolean, status?:number, throws?:any, body?:string}>} routes
 */
function makeFetch(routes) {
  return async function mockFetch(url) {
    for (const r of routes) {
      if (String(url).includes(r.match)) {
        if (r.throws) throw r.throws;
        return {
          ok: r.ok !== undefined ? r.ok : true,
          status: r.status !== undefined ? r.status : 200,
          async text() { return r.body || ''; },
        };
      }
    }
    // Default: unreachable
    const err = new Error('no route');
    err.code = 'ECONNREFUSED';
    throw err;
  };
}

const CONFIG = Object.freeze({
  port: 3002,
  nodeEnv: 'test',
  pythonYtmusicUrl: 'http://127.0.0.1:5000',
  pythonAudioUrl: 'http://127.0.0.1:5001',
});

// fs double that returns a valid identity store.
function healthyFsDeps() {
  return {
    fs: {
      statSync: () => ({ size: 88510 }),
      readFileSync: () => JSON.stringify({ a: 1, b: 2, c: 3 }),
    },
    storePath: '/fake/data/identity_store.json',
    memoryUsage: () => ({ rss: 42 * 1024 * 1024 }),
    // Rate-limiter loaders (only used with --external).
    loadMb: () => ({ getMetrics: () => ({ drainIntervalMs: 1100, maxQueueDepth: 20 }) }),
    loadLb: () => ({ getMetrics: () => ({ drainIntervalMs: 350, maxQueueDepth: 30 }) }),
  };
}

const NOW = () => 1_000; // deterministic clock

// ── Pure helpers ─────────────────────────────────────────────────────────────

describe('status rollup helpers', () => {
  it('worseOf returns the higher-ranked status', () => {
    assert.equal(hc.worseOf(STATUS.HEALTHY, STATUS.DEGRADED), STATUS.DEGRADED);
    assert.equal(hc.worseOf(STATUS.DOWN, STATUS.DEGRADED), STATUS.DOWN);
    assert.equal(hc.worseOf(STATUS.NOT_CONFIGURED, STATUS.HEALTHY), STATUS.NOT_CONFIGURED);
  });

  it('worstStatus reduces a list', () => {
    assert.equal(hc.worstStatus([STATUS.HEALTHY, STATUS.HEALTHY]), STATUS.HEALTHY);
    assert.equal(hc.worstStatus([STATUS.HEALTHY, STATUS.DOWN, STATUS.DEGRADED]), STATUS.DOWN);
    assert.equal(hc.worstStatus([]), STATUS.HEALTHY);
  });

  it('rollupCore maps NOT_CONFIGURED up to DEGRADED', () => {
    assert.equal(
      hc.rollupCore([{ status: STATUS.HEALTHY }, { status: STATUS.NOT_CONFIGURED }]),
      STATUS.DEGRADED
    );
    assert.equal(
      hc.rollupCore([{ status: STATUS.HEALTHY }, { status: STATUS.DOWN }]),
      STATUS.DOWN
    );
  });

  it('rollupExternalContribution caps DOWN at DEGRADED and ignores advisory', () => {
    assert.equal(
      hc.rollupExternalContribution([{ status: STATUS.DOWN, impact: 'degrade' }]),
      STATUS.DEGRADED
    );
    assert.equal(
      hc.rollupExternalContribution([{ status: STATUS.DOWN, impact: 'advisory' }]),
      STATUS.HEALTHY
    );
    assert.equal(
      hc.rollupExternalContribution([{ status: STATUS.NOT_CONFIGURED, impact: 'degrade' }]),
      STATUS.HEALTHY
    );
  });

  it('statusToExitCode maps per spec (0/1/2)', () => {
    assert.equal(hc.statusToExitCode(STATUS.HEALTHY), 0);
    assert.equal(hc.statusToExitCode(STATUS.DEGRADED), 1);
    assert.equal(hc.statusToExitCode(STATUS.NOT_CONFIGURED), 1);
    assert.equal(hc.statusToExitCode(STATUS.DOWN), 2);
  });
});

describe('sanitizeUrl / safeErrorCode', () => {
  it('strips query strings and userinfo', () => {
    assert.equal(hc.sanitizeUrl('https://user:pass@host.com/path?token=secret'), 'https://host.com/path');
    assert.equal(hc.sanitizeUrl('http://127.0.0.1:5000/health'), 'http://127.0.0.1:5000/health');
    assert.equal(hc.sanitizeUrl('http://127.0.0.1:3002/'), 'http://127.0.0.1:3002');
  });

  it('never throws on malformed input', () => {
    assert.equal(hc.sanitizeUrl(''), '');
    assert.equal(hc.sanitizeUrl(null), '');
    assert.equal(hc.sanitizeUrl('not a url?x=1'), 'not a url');
  });

  it('safeErrorCode maps timeouts and prefers code/name', () => {
    assert.equal(hc.safeErrorCode({ name: 'TimeoutError' }), 'TIMEOUT');
    assert.equal(hc.safeErrorCode({ name: 'AbortError' }), 'TIMEOUT');
    assert.equal(hc.safeErrorCode({ code: 'ECONNREFUSED' }), 'ECONNREFUSED');
    assert.equal(hc.safeErrorCode(null), 'ERROR');
  });

  it('safeErrorCode unwraps a fetch TypeError cause to the OS-level code', () => {
    // Mirrors Node global fetch: new TypeError('fetch failed', { cause: { code } }).
    const err = new TypeError('fetch failed');
    err.cause = { code: 'ECONNREFUSED' };
    assert.equal(hc.safeErrorCode(err), 'ECONNREFUSED');
  });
});

// ── Tier 1 checks ────────────────────────────────────────────────────────────

describe('checkRuntime', () => {
  it('is HEALTHY on a supported Node major', () => {
    const c = hc.checkRuntime({ nodeVersion: 'v22.0.0', platform: 'linux', arch: 'x64', memoryUsage: () => ({ rss: 1 }) });
    assert.equal(c.status, STATUS.HEALTHY);
    assert.equal(c.tier, 1);
  });

  it('is DEGRADED below the Node floor', () => {
    const c = hc.checkRuntime({ nodeVersion: 'v18.0.0', platform: 'linux', arch: 'x64', memoryUsage: () => ({ rss: 1 }) });
    assert.equal(c.status, STATUS.DEGRADED);
    assert.match(c.note, /below the supported floor/);
  });
});

describe('checkConfig', () => {
  it('is HEALTHY when all targets present and emits no secrets', () => {
    const c = hc.checkConfig(CONFIG);
    assert.equal(c.status, STATUS.HEALTHY);
    assert.ok(!/token|password|secret|apikey/i.test(JSON.stringify(c)));
  });

  it('is NOT_CONFIGURED when a target is missing', () => {
    const c = hc.checkConfig({ ...CONFIG, pythonAudioUrl: '' });
    assert.equal(c.status, STATUS.NOT_CONFIGURED);
    assert.match(c.note, /PYTHON_AUDIO_URL/);
  });
});

describe('checkIdentityStore', () => {
  it('is HEALTHY for a valid JSON store and never leaks contents', () => {
    const c = hc.checkIdentityStore(healthyFsDeps());
    assert.equal(c.status, STATUS.HEALTHY);
    assert.match(c.detail, /88510 bytes/);
    assert.match(c.detail, /3 entries/);
    // Content values (1,2,3 / a,b,c) must not appear as raw JSON content.
    assert.ok(!/"a":1|"b":2|"c":3/.test(JSON.stringify(c)));
  });

  it('is DEGRADED (never DOWN) when the file is missing', () => {
    const deps = healthyFsDeps();
    deps.fs.statSync = () => { throw new Error('ENOENT'); };
    const c = hc.checkIdentityStore(deps);
    assert.equal(c.status, STATUS.DEGRADED);
    assert.match(c.detail, /not found/);
  });

  it('is DEGRADED when the file is not valid JSON', () => {
    const deps = healthyFsDeps();
    deps.fs.readFileSync = () => 'this is not json{';
    const c = hc.checkIdentityStore(deps);
    assert.equal(c.status, STATUS.DEGRADED);
    assert.match(c.detail, /not valid JSON/);
  });
});

// ── Probe + classifiers ──────────────────────────────────────────────────────

describe('probeHttp', () => {
  it('reports ok + latency on a 200', async () => {
    const r = await hc.probeHttp({ url: 'http://x/health', timeoutMs: 100, fetchImpl: makeFetch([{ match: 'x/health', ok: true, status: 200 }]), now: NOW });
    assert.equal(r.ok, true);
    assert.equal(r.httpStatus, 200);
  });

  it('reports an error code when fetch throws', async () => {
    const err = new Error('boom'); err.code = 'ECONNREFUSED';
    const r = await hc.probeHttp({ url: 'http://x', timeoutMs: 100, fetchImpl: makeFetch([{ match: 'x', throws: err }]), now: NOW });
    assert.equal(r.ok, false);
    assert.equal(r.httpStatus, null);
    assert.equal(r.errorCode, 'ECONNREFUSED');
  });
});

describe('classifyLoopback / classifyExternal', () => {
  it('gateway unreachable classifies DOWN', () => {
    const c = hc.classifyLoopback({ id: 'gateway', label: 'g', target: 'http://127.0.0.1:3002', probe: { ok: false, httpStatus: null, latencyMs: 3, errorCode: 'ECONNREFUSED' }, downStatus: STATUS.DOWN });
    assert.equal(c.status, STATUS.DOWN);
  });

  it('python service unreachable classifies DEGRADED', () => {
    const c = hc.classifyLoopback({ id: 'python_ytmusic', label: 'y', target: 't', probe: { ok: false, httpStatus: 500, latencyMs: 3 }, downStatus: STATUS.DEGRADED });
    assert.equal(c.status, STATUS.DEGRADED);
    assert.match(c.detail, /HTTP 500/);
  });

  it('external failure is capped at DEGRADED and marked degrade impact', () => {
    const c = hc.classifyExternal({ id: 'jiosaavn', label: 'j', target: 't', probe: { ok: false, httpStatus: null, latencyMs: 3, errorCode: 'TIMEOUT' } });
    assert.equal(c.status, STATUS.DEGRADED);
    assert.equal(c.impact, 'degrade');
  });
});

describe('checkRateLimiterPolicy', () => {
  it('reports configured policy as advisory HEALTHY', () => {
    const checks = hc.checkRateLimiterPolicy(healthyFsDeps());
    assert.equal(checks.length, 2);
    for (const c of checks) {
      assert.equal(c.impact, 'advisory');
      assert.equal(c.status, STATUS.HEALTHY);
      assert.match(c.detail, /configured policy/);
    }
  });

  it('is advisory NOT_CONFIGURED when a limiter cannot be loaded', () => {
    const deps = healthyFsDeps();
    deps.loadMb = () => { throw new Error('module missing'); };
    const checks = hc.checkRateLimiterPolicy(deps);
    const mb = checks.find((c) => c.id === 'ratelimit_musicbrainz');
    assert.equal(mb.status, STATUS.NOT_CONFIGURED);
    assert.equal(mb.impact, 'advisory');
  });
});

// ── Orchestrator ─────────────────────────────────────────────────────────────

describe('checkHealth (orchestration)', () => {
  const allUp = [
    { match: '127.0.0.1:3002/health', ok: true, status: 200 },
    { match: '127.0.0.1:5000/health', ok: true, status: 200 },
    { match: '127.0.0.1:5001/', ok: true, status: 200 },
  ];

  it('is HEALTHY (exit 0) when everything is up', async () => {
    const report = await hc.checkHealth({
      fetchImpl: makeFetch(allUp),
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(report.status, STATUS.HEALTHY);
    assert.equal(hc.statusToExitCode(report.status), 0);
    assert.equal(report.includeExternal, false);
    // Only Tier 1 + Tier 2 checks when external is off.
    assert.ok(report.checks.every((c) => c.tier <= 2));
  });

  it('is DOWN (exit 2) when the gateway is unreachable', async () => {
    const routes = [
      { match: '127.0.0.1:3002/health', throws: Object.assign(new Error('x'), { code: 'ECONNREFUSED' }) },
      { match: '127.0.0.1:5000/health', ok: true, status: 200 },
      { match: '127.0.0.1:5001/', ok: true, status: 200 },
    ];
    const report = await hc.checkHealth({ fetchImpl: makeFetch(routes), configOverride: CONFIG, deps: healthyFsDeps(), now: NOW });
    assert.equal(report.status, STATUS.DOWN);
    assert.equal(hc.statusToExitCode(report.status), 2);
  });

  it('is DEGRADED (exit 1) when a Python service is down but the gateway is up', async () => {
    const routes = [
      { match: '127.0.0.1:3002/health', ok: true, status: 200 },
      { match: '127.0.0.1:5000/health', ok: false, status: 503 },
      { match: '127.0.0.1:5001/', ok: true, status: 200 },
    ];
    const report = await hc.checkHealth({ fetchImpl: makeFetch(routes), configOverride: CONFIG, deps: healthyFsDeps(), now: NOW });
    assert.equal(report.status, STATUS.DEGRADED);
    assert.equal(hc.statusToExitCode(report.status), 1);
  });

  it('external DOWN cannot force overall DOWN (capped at DEGRADED)', async () => {
    const routes = [
      ...allUp,
      { match: 'suggestqueries.google.com', throws: Object.assign(new Error('x'), { code: 'ENOTFOUND' }) },
      { match: 'jiosaavn.com', throws: Object.assign(new Error('x'), { code: 'ENOTFOUND' }) },
    ];
    const report = await hc.checkHealth({
      includeExternal: true,
      fetchImpl: makeFetch(routes),
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(report.status, STATUS.DEGRADED); // NOT DOWN
    assert.ok(report.checks.some((c) => c.tier === 3));
    assert.ok(report.includeExternal);
  });

  it('produces a leak-free report (no secrets, no query strings, no userinfo in targets)', async () => {
    const report = await hc.checkHealth({
      includeExternal: true,
      fetchImpl: makeFetch([...allUp, { match: 'suggestqueries.google.com', ok: true, status: 200 }, { match: 'jiosaavn.com', ok: true, status: 200 }]),
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    const serialized = JSON.stringify(report);
    assert.ok(!/token|password|secret|apikey|cookie|authorization/i.test(serialized));
    for (const c of report.checks) {
      if (c.target) {
        assert.ok(!c.target.includes('?'), `target must not contain a query string: ${c.target}`);
        assert.ok(!c.target.includes('@'), `target must not contain userinfo: ${c.target}`);
      }
    }
  });

  it('never throws and always returns a valid status', async () => {
    // Even if fetch always rejects, the orchestrator resolves with a report.
    const report = await hc.checkHealth({
      fetchImpl: async () => { throw new Error('total network failure'); },
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.ok(Object.values(STATUS).includes(report.status));
    assert.equal(report.status, STATUS.DOWN); // gateway unreachable
  });
});

describe('checkHealth ({ skipGateway: true })', () => {
  it('skips gateway probe and marks gateway in-process HEALTHY', async () => {
    let gatewayProbed = false;
    const mockFetch = async (url) => {
      if (String(url).includes('3002/health')) {
        gatewayProbed = true;
        throw new Error('Gateway should not have been probed');
      }
      if (String(url).includes('5000/health')) return { ok: true, status: 200, async text() { return ''; } };
      if (String(url).includes('5001/')) return { ok: true, status: 200, async text() { return ''; } };
      throw new Error(`Unexpected URL: ${url}`);
    };

    const report = await hc.checkHealth({
      skipGateway: true,
      fetchImpl: mockFetch,
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });

    assert.equal(gatewayProbed, false);
    assert.equal(report.status, STATUS.HEALTHY);
    const gw = report.checks.find((c) => c.id === 'gateway');
    assert.ok(gw, 'gateway check must be present');
    assert.equal(gw.status, STATUS.HEALTHY);
    assert.equal(gw.latencyMs, 0);
    assert.match(gw.detail, /in-process/);
  });

  it('rolls up to DEGRADED when a Python service fails with skipGateway: true', async () => {
    const routes = [
      { match: '127.0.0.1:5000/health', ok: false, status: 503 },
      { match: '127.0.0.1:5001/', ok: true, status: 200 },
    ];
    const report = await hc.checkHealth({
      skipGateway: true,
      fetchImpl: makeFetch(routes),
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(report.status, STATUS.DEGRADED);
    const gw = report.checks.find((c) => c.id === 'gateway');
    assert.equal(gw.status, STATUS.HEALTHY);
    const yt = report.checks.find((c) => c.id === 'python_ytmusic');
    assert.equal(yt.status, STATUS.DEGRADED);
  });

  it('rolls up to DEGRADED when both Python services fail with skipGateway: true', async () => {
    const routes = [
      { match: '127.0.0.1:5000/health', ok: false, status: 503 },
      { match: '127.0.0.1:5001/', throws: Object.assign(new Error('unreachable'), { code: 'ECONNREFUSED' }) },
    ];
    const report = await hc.checkHealth({
      skipGateway: true,
      fetchImpl: makeFetch(routes),
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(report.status, STATUS.DEGRADED);
    const gw = report.checks.find((c) => c.id === 'gateway');
    assert.equal(gw.status, STATUS.HEALTHY);
    const audio = report.checks.find((c) => c.id === 'python_audio');
    assert.equal(audio.status, STATUS.DEGRADED);
  });

  it('preserves existing default gateway probe when skipGateway is false or omitted', async () => {
    let gatewayProbed = false;
    const mockFetch = async (url) => {
      if (String(url).includes('3002/health')) {
        gatewayProbed = true;
        return { ok: true, status: 200, async text() { return ''; } };
      }
      return { ok: true, status: 200, async text() { return ''; } };
    };

    const reportDefault = await hc.checkHealth({
      fetchImpl: mockFetch,
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(gatewayProbed, true);
    assert.equal(reportDefault.status, STATUS.HEALTHY);

    gatewayProbed = false;
    const reportExplicitFalse = await hc.checkHealth({
      skipGateway: false,
      fetchImpl: mockFetch,
      configOverride: CONFIG,
      deps: healthyFsDeps(),
      now: NOW,
    });
    assert.equal(gatewayProbed, true);
    assert.equal(reportExplicitFalse.status, STATUS.HEALTHY);
  });
});

