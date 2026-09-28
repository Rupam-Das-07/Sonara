#!/usr/bin/env node
'use strict';

/**
 * verify-production-smoke.js — Sonara Production Gateway Smoke & Deployment Verification Gate.
 *
 * PURPOSE:
 *   Validates the production integration of Sonara (Cloudflare -> Fly.io -> sonara-backend)
 *   after deployment, without requiring an Android device.
 *
 * SAFETY RULES:
 *   - Strictly READ-ONLY: never mutates server state or identity store.
 *   - Strictly BOUNDED: exactly 9 requests total (budget <= 10).
 *   - NO audio downloads: verifies stream resolution schema only, never downloads audio bytes.
 *   - Respects rate limits (stream resolve is 20 req/min; we make exactly 1 call).
 *
 * CRITICAL PATHS COVERED:
 *   P1: Gateway health (/health) & deep subsystem health (/health/deep)
 *   P2: Catalog search (/api/v1/search?q=coldplay)
 *   P3: Ephemeral stream resolution (/api/v1/stream/resolve?video_id=9qnqYL0eNNI)
 *   P4: Track-seeded recommendations (/api/v1/recommendations/related/9qnqYL0eNNI)
 *   P5: Discovery catalog (/api/v1/playlists and /api/v1/artists/featured)
 *   P6: Artwork CDN reachability & content-type verification
 *   P7: Error contract (/api/v1/search?q= -> 400 Bad Request with structured JSON)
 *
 * EXIT CODES:
 *   0 = PASS (all critical production paths healthy)
 *   1 = FAIL (regression detected, schema violation, or service degraded)
 */

const https = require('https');
const http = require('http');
const { URL } = require('url');

const DEFAULT_TARGET = 'https://sonara.antideploy.com';
const DEFAULT_TIMEOUT_MS = 10000;

function parseArgs(argv) {
  const options = {
    target: DEFAULT_TARGET,
    timeoutMs: DEFAULT_TIMEOUT_MS,
    json: false,
    verbose: false,
  };

  for (const arg of argv) {
    if (arg === '--json') options.json = true;
    else if (arg === '--verbose') options.verbose = true;
    else if (arg.startsWith('--target=')) options.target = arg.slice('--target='.length).replace(/\/+$/, '');
    else if (arg.startsWith('--timeout=')) options.timeoutMs = parseInt(arg.slice('--timeout='.length), 10);
    else if (arg === '--help' || arg === '-h') {
      console.log(`Sonara Production Smoke Verification Gate
Usage: node verify-production-smoke.js [--target=https://...] [--timeout=ms] [--json] [--verbose]`);
      process.exit(0);
    }
  }
  return options;
}

function request(targetUrl, options = {}) {
  const method = options.method || 'GET';
  const timeoutMs = options.timeoutMs || DEFAULT_TIMEOUT_MS;
  const parsed = new URL(targetUrl);
  const client = parsed.protocol === 'https:' ? https : http;

  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const req = client.request(
      parsed,
      {
        method,
        headers: {
          'Accept': 'application/json, image/*, */*',
          'User-Agent': 'SonaraSmokeVerifier/1.0 (ProductionVerificationGate)',
        },
        timeout: timeoutMs,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const latencyMs = Date.now() - t0;
          const bodyBuffer = Buffer.concat(chunks);
          const bodyText = bodyBuffer.toString('utf8');
          resolve({
            statusCode: res.statusCode,
            headers: res.headers,
            bodyText,
            bodyBuffer,
            latencyMs,
          });
        });
      }
    );

    req.on('timeout', () => {
      req.destroy();
      reject(new Error(`Request timed out after ${timeoutMs}ms for ${targetUrl}`));
    });

    req.on('error', (err) => {
      reject(new Error(`Network error (${err.message}) for ${targetUrl}`));
    });

    req.end();
  });
}

async function runSmokeSuite(options) {
  const results = {
    target: options.target,
    timestamp: new Date().toISOString(),
    verdict: 'PASS',
    requestBudget: { allowed: 10, used: 0 },
    checks: [],
  };

  function recordCheck(id, name, path, pass, detail, latencyMs, extra = {}) {
    results.checks.push({
      id,
      name,
      path,
      pass,
      latencyMs: latencyMs || 0,
      detail,
      ...extra,
    });
    if (!pass) {
      results.verdict = 'FAIL';
    }
  }

  let resolvedArtworkUrl = null;

  // -------------------------------------------------------------------------
  // 1. P1: Gateway Health Check (/health)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/health`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P1.1', 'Gateway Health Ping', '/health', false, `HTTP ${res.statusCode} (expected 200)`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const valid = data.status === 'ok' && data.service === 'sonara-backend';
      recordCheck('P1.1', 'Gateway Health Ping', '/health', valid, valid ? `OK: ${data.service} v${data.version}` : 'Invalid body', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P1.1', 'Gateway Health Ping', '/health', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 2. P1: Deep Subsystem Health Check (/health/deep)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/health/deep`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P1.2', 'Deep Stack Health', '/health/deep', false, `HTTP ${res.statusCode} (expected 200)`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const isHealthy = data.status === 'HEALTHY';
      const downCount = data.counts?.down || 0;
      const valid = isHealthy && downCount === 0;
      const summary = `Status: ${data.status} (healthy: ${data.counts?.healthy}, degraded: ${data.counts?.degraded}, down: ${downCount})`;
      recordCheck('P1.2', 'Deep Stack Health', '/health/deep', valid, summary, res.latencyMs, { subsystemChecks: data.checks });
    }
  } catch (err) {
    recordCheck('P1.2', 'Deep Stack Health', '/health/deep', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 3. P2: Search Quality Engine Contract (/api/v1/search?q=coldplay)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/search?q=coldplay`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P2', 'Search Catalog', '/api/v1/search?q=coldplay', false, `HTTP ${res.statusCode}`, res.latencyMs);
    } else {
      const items = JSON.parse(res.bodyText);
      if (!Array.isArray(items) || items.length === 0) {
        recordCheck('P2', 'Search Catalog', '/api/v1/search?q=coldplay', false, 'Empty or non-array search results', res.latencyMs);
      } else {
        const first = items[0];
        const hasRequired = first.id && first.videoId && first.title && first.artist;
        if (first.artworkUrl) resolvedArtworkUrl = first.artworkUrl;
        recordCheck('P2', 'Search Catalog', '/api/v1/search?q=coldplay', Boolean(hasRequired), `Returned ${items.length} tracks. First: "${first.title}" by ${first.artist}`, res.latencyMs);
      }
    }
  } catch (err) {
    recordCheck('P2', 'Search Catalog', '/api/v1/search?q=coldplay', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 4. P3: Stream Resolution Contract (/api/v1/stream/resolve?video_id=9qnqYL0eNNI)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/stream/resolve?video_id=9qnqYL0eNNI`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P3', 'Stream Resolution', '/api/v1/stream/resolve?video_id=9qnqYL0eNNI', false, `HTTP ${res.statusCode}`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const valid = Boolean(data.audio_url && data.trackId === '9qnqYL0eNNI' && data.format && data.codec);
      recordCheck('P3', 'Stream Resolution', '/api/v1/stream/resolve?video_id=9qnqYL0eNNI', valid, valid ? `Resolved ephemeral proxy stream (${data.codec}, ${data.bitrate}kbps, ${data.qualityTier})` : 'Missing audio_url/trackId', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P3', 'Stream Resolution', '/api/v1/stream/resolve?video_id=9qnqYL0eNNI', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 5. P4: Seed Recommendations (/api/v1/recommendations/related/9qnqYL0eNNI)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/recommendations/related/9qnqYL0eNNI`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P4', 'Seed Recommendations', '/api/v1/recommendations/related/9qnqYL0eNNI', false, `HTTP ${res.statusCode}`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const valid = data.success === true && Array.isArray(data.tracks) && data.tracks.length > 0;
      recordCheck('P4', 'Seed Recommendations', '/api/v1/recommendations/related/9qnqYL0eNNI', valid, valid ? `Returned ${data.tracks.length} related tracks` : 'Empty recommendations', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P4', 'Seed Recommendations', '/api/v1/recommendations/related/9qnqYL0eNNI', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 6. P5.1: Curated Playlists Catalog (/api/v1/playlists)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/playlists`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P5.1', 'Curated Playlists', '/api/v1/playlists', false, `HTTP ${res.statusCode}`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const valid = Array.isArray(data.playlists) && data.playlists.length > 0;
      const first = valid ? data.playlists[0] : null;
      const coversValid = valid && !first.coverImage?.includes('/assets/');
      recordCheck('P5.1', 'Curated Playlists', '/api/v1/playlists', valid && coversValid, valid ? `Returned ${data.playlists.length} curated playlists (covers routable: ${coversValid})` : 'Invalid playlists schema', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P5.1', 'Curated Playlists', '/api/v1/playlists', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 7. P5.2: Editorial Featured Artists (/api/v1/artists/featured)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/artists/featured`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 200) {
      recordCheck('P5.2', 'Featured Artists', '/api/v1/artists/featured', false, `HTTP ${res.statusCode}`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const valid = Array.isArray(data.featured) && data.featured.length > 0;
      recordCheck('P5.2', 'Featured Artists', '/api/v1/artists/featured', valid, valid ? `Returned ${data.featured.length} featured artists` : 'Empty featured artists', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P5.2', 'Featured Artists', '/api/v1/artists/featured', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 8. P6: Artwork CDN Reachability (HEAD probe)
  // -------------------------------------------------------------------------
  const artworkTarget = resolvedArtworkUrl || 'https://yt3.googleusercontent.com/7RK5kFu8RjjulsxsPCJ55wmJLchKaaIRAYih0ldj--RGbnOOdA0U2vmLWyVfjPW82nR-Dwm2r8PhEi61=w544-h544-l90-rj';
  try {
    results.requestBudget.used++;
    const res = await request(artworkTarget, { method: 'HEAD', timeoutMs: options.timeoutMs });
    const contentType = res.headers['content-type'] || '';
    const isImage = contentType.startsWith('image/');
    const valid = res.statusCode === 200 && isImage;
    recordCheck('P6', 'Artwork CDN Resolution', artworkTarget.slice(0, 60) + '...', valid, valid ? `HTTP 200, Content-Type: ${contentType}` : `HTTP ${res.statusCode}, Content-Type: ${contentType}`, res.latencyMs);
  } catch (err) {
    recordCheck('P6', 'Artwork CDN Resolution', artworkTarget.slice(0, 60) + '...', false, err.message, 0);
  }

  // -------------------------------------------------------------------------
  // 9. P7: Error Contract Validation (/api/v1/search?q= -> 400 Bad Request)
  // -------------------------------------------------------------------------
  try {
    results.requestBudget.used++;
    const res = await request(`${options.target}/api/v1/search?q=`, { timeoutMs: options.timeoutMs });
    if (res.statusCode !== 400) {
      recordCheck('P7', 'Error Contract', '/api/v1/search?q=', false, `HTTP ${res.statusCode} (expected 400)`, res.latencyMs);
    } else {
      const data = JSON.parse(res.bodyText);
      const hasCode = data.error && data.error.code === 'INVALID_REQUEST';
      recordCheck('P7', 'Error Contract', '/api/v1/search?q=', Boolean(hasCode), hasCode ? `Correct structured error: ${data.error.code}` : 'Malformed error payload', res.latencyMs);
    }
  } catch (err) {
    recordCheck('P7', 'Error Contract', '/api/v1/search?q=', false, err.message, 0);
  }

  return results;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));

  try {
    const report = await runSmokeSuite(options);

    if (options.json) {
      process.stdout.write(JSON.stringify(report, null, 2) + '\n');
    } else {
      console.log('================================================================');
      console.log(`SONARA PRODUCTION DEPLOYMENT SMOKE GATE`);
      console.log(`Target: ${report.target}`);
      console.log(`Time:   ${report.timestamp}`);
      console.log(`Budget: ${report.requestBudget.used} / ${report.requestBudget.allowed} requests used`);
      console.log(`Result: ${report.verdict}`);
      console.log('================================================================');
      for (const c of report.checks) {
        const mark = c.pass ? '[PASS]' : '[FAIL]';
        console.log(`${mark} ${c.id.padEnd(5)} ${c.name.padEnd(25)} (${c.latencyMs}ms) - ${c.detail}`);
        if (options.verbose && c.subsystemChecks) {
          for (const s of c.subsystemChecks) {
            console.log(`         -> [${s.status}] ${s.label}: ${s.detail}`);
          }
        }
      }
      console.log('================================================================');
    }

    process.exit(report.verdict === 'PASS' ? 0 : 1);
  } catch (fatal) {
    console.error(`FATAL SMOKE ERROR: ${fatal.message}`);
    process.exit(1);
  }
}

main();
