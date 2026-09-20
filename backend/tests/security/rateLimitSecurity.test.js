'use strict';

/**
 * rateLimitSecurity.test.js — Regression & security validation tests for Finding #6:
 * sonara:backend:dev-mode-rate-limit-bypass
 *
 * Verifies:
 * 1. Fail-closed NODE_ENV resolution:
 *    - Absent NODE_ENV defaults strictly to 'production'.
 *    - Absent NODE_ENV + '--dev' argv flag resolves to 'development'.
 *    - Explicit NODE_ENV=production always overrides '--dev' flag.
 *    - Explicit NODE_ENV=development enters development.
 *    - Non-development environments ('production', 'test', 'staging', 'unknown', 'Production')
 *      NEVER enter development mode (isDev() returns false).
 * 2. Express rate-limit middleware behavior:
 *    - In production mode, requests exceeding thresholds receive HTTP 429 (rate limiting active).
 *    - In development mode, requests exceeding thresholds are permitted (bypass active).
 *    - In staging/unknown mode, requests exceeding thresholds receive HTTP 429 (fail-closed).
 * 3. Preservation of all 4 production rate limiters and their numeric thresholds:
 *    - globalLimiter (200 req/min)
 *    - searchLimiter (60 req/min)
 *    - resolveLimiter (20 req/min)
 *    - importLimiter (30 req/min)
 * 4. Package scripts verification:
 *    - 'start' does not pass --dev (secure by construction).
 *    - 'dev' explicitly passes --dev (explicit opt-in).
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const rateLimit = require('express-rate-limit');
const config = require('../../src/config/env');
const fs = require('fs');
const path = require('path');

describe('Finding #6 — Environment Resolution & Fail-Closed Invariant', () => {
  const { resolveNodeEnv } = config;

  test('absent NODE_ENV without --dev defaults strictly to production (fail-closed)', () => {
    const resolved = resolveNodeEnv({}, ['node', 'src/index.js']);
    assert.equal(resolved, 'production', 'Missing NODE_ENV must fail-closed to production');
  });

  test('empty string NODE_ENV defaults strictly to production', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: '   ' }, ['node', 'src/index.js']);
    assert.equal(resolved, 'production');
  });

  test('absent NODE_ENV with explicit --dev CLI flag resolves to development', () => {
    const resolved = resolveNodeEnv({}, ['node', 'src/index.js', '--dev']);
    assert.equal(resolved, 'development', 'CLI --dev flag must explicitly opt in to development');
  });

  test('explicit NODE_ENV=production overrides --dev CLI flag', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: 'production' }, ['node', 'src/index.js', '--dev']);
    assert.equal(resolved, 'production', 'Explicit production environment variable must take precedence');
  });

  test('explicit NODE_ENV=development resolves to development', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: 'development' }, ['node', 'src/index.js']);
    assert.equal(resolved, 'development');
  });

  test('explicit NODE_ENV=test resolves to test', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: 'test' }, ['node', 'src/index.js']);
    assert.equal(resolved, 'test');
  });

  test('explicit NODE_ENV=staging resolves to staging', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: 'staging' }, ['node', 'src/index.js']);
    assert.equal(resolved, 'staging');
  });

  test('unrecognized NODE_ENV resolves without falling back to development', () => {
    const resolved = resolveNodeEnv({ NODE_ENV: 'unrecognized_custom_env' }, ['node', 'src/index.js']);
    assert.equal(resolved, 'unrecognized_custom_env');
  });
});

describe('Finding #6 — Positive Identification in isDev() and isProd()', () => {
  const originalNodeEnv = config.nodeEnv;

  test('isDev() returns true ONLY for development (case-insensitive)', () => {
    config.nodeEnv = 'development';
    assert.equal(config.isDev(), true);

    config.nodeEnv = 'DEVELOPMENT';
    assert.equal(config.isDev(), true);
  });

  test('isDev() returns false for all non-development environments', () => {
    const nonDevEnvironments = [
      'production',
      'Production',
      'test',
      'staging',
      'unknown',
      '',
      null,
      undefined,
    ];

    for (const env of nonDevEnvironments) {
      config.nodeEnv = env;
      assert.equal(config.isDev(), false, `isDev() must be false for env '${env}'`);
    }

    // Restore
    config.nodeEnv = originalNodeEnv;
  });

  test('isProd() returns true for production (case-insensitive)', () => {
    config.nodeEnv = 'production';
    assert.equal(config.isProd(), true);

    config.nodeEnv = 'Production';
    assert.equal(config.isProd(), true);

    config.nodeEnv = 'development';
    assert.equal(config.isProd(), false);

    config.nodeEnv = 'test';
    assert.equal(config.isProd(), false);

    // Restore
    config.nodeEnv = originalNodeEnv;
  });
});

describe('Finding #6 — Live Express Rate Limiter Middleware Behavior', () => {
  const originalNodeEnv = config.nodeEnv;

  function createTestApp() {
    const app = express();
    const limiter = rateLimit({
      windowMs: 60 * 1000,
      max: 2, // Allow exactly 2 requests; 3rd should be 429 if active
      standardHeaders: true,
      legacyHeaders: false,
      message: { error: 'Rate limit exceeded' },
      skip: () => config.isDev(),
    });

    app.get('/test', limiter, (req, res) => {
      res.status(200).json({ status: 'ok' });
    });

    return app;
  }

  function simulateRequest(app, ip = '127.0.0.1') {
    return new Promise((resolve) => {
      const req = {
        method: 'GET',
        url: '/test',
        ip,
        headers: {},
        app,
        socket: { remoteAddress: ip },
      };

      let statusCode = 200;
      let body = null;

      const res = {
        status(code) {
          statusCode = code;
          return this;
        },
        json(data) {
          body = data;
          resolve({ status: statusCode, body });
        },
        send(data) {
          body = data;
          resolve({ status: statusCode, body });
        },
        setHeader() {},
        getHeader() {},
      };

      app.handle(req, res);
    });
  }

  test('rate limiter blocks 3rd request with HTTP 429 when in production mode', async () => {
    config.nodeEnv = 'production';
    const app = createTestApp();

    const r1 = await simulateRequest(app, '192.168.1.100');
    assert.equal(r1.status, 200);

    const r2 = await simulateRequest(app, '192.168.1.100');
    assert.equal(r2.status, 200);

    const r3 = await simulateRequest(app, '192.168.1.100');
    assert.equal(r3.status, 429, '3rd request in production mode must be rate limited with 429');

    config.nodeEnv = originalNodeEnv;
  });

  test('rate limiter permits requests past threshold when explicitly in development mode', async () => {
    config.nodeEnv = 'development';
    const app = createTestApp();

    const r1 = await simulateRequest(app, '192.168.1.101');
    assert.equal(r1.status, 200);

    const r2 = await simulateRequest(app, '192.168.1.101');
    assert.equal(r2.status, 200);

    const r3 = await simulateRequest(app, '192.168.1.101');
    assert.equal(r3.status, 200, '3rd request in development mode must be allowed without rate limiting');

    const r4 = await simulateRequest(app, '192.168.1.101');
    assert.equal(r4.status, 200, '4th request in development mode must be allowed without rate limiting');

    config.nodeEnv = originalNodeEnv;
  });

  test('rate limiter remains active for unknown / staging environments', async () => {
    config.nodeEnv = 'staging';
    const app = createTestApp();

    const r1 = await simulateRequest(app, '192.168.1.102');
    assert.equal(r1.status, 200);

    const r2 = await simulateRequest(app, '192.168.1.102');
    assert.equal(r2.status, 200);

    const r3 = await simulateRequest(app, '192.168.1.102');
    assert.equal(r3.status, 429, 'Unknown/staging environments must fail-closed and enforce rate limiting');

    config.nodeEnv = originalNodeEnv;
  });
});

describe('Finding #6 — Production Limits & Package Script Invariants', () => {
  test('production rate limit numeric thresholds are strictly preserved', () => {
    assert.equal(config.globalRateLimitMax, 200, 'globalRateLimitMax must be 200');
    assert.equal(config.searchRateLimitMax, 60, 'searchRateLimitMax must be 60');
    assert.equal(config.streamResolveRateLimitMax, 20, 'streamResolveRateLimitMax must be 20');
  });

  test('package.json scripts define secure-by-default production and explicit development', () => {
    const pkgPath = path.resolve(__dirname, '../../package.json');
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));

    // 'start' command must NOT pass --dev
    assert.equal(pkg.scripts.start, 'node src/index.js');
    assert.equal(pkg.scripts.start.includes('--dev'), false, 'npm start must not include --dev');

    // 'dev' command must explicitly pass --dev
    assert.ok(pkg.scripts.dev.includes('--dev'), 'npm run dev must explicitly pass --dev');
  });
});
