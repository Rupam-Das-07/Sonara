'use strict';

/**
 * health.test.js — Route integration tests for:
 *   GET /health         (Lightweight liveness probe)
 *   GET /health/deep    (Deep dependency / readiness probe — F-15)
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const app = require('../../src/index');
const hc = require('../../src/operations/healthChecker');
const { STATUS } = hc;

function getRouteHandler(path) {
  const layer = app._router.stack.find(
    (l) => l.route && l.route.path === path && l.route.methods.get
  );
  if (!layer) throw new Error(`Route not found on app: ${path}`);
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

function mockReqRes(query = {}) {
  let statusCode = 200;
  let responseData = null;
  let nextError = null;

  const req = { query, method: 'GET' };
  const res = {
    status(code) {
      statusCode = code;
      return this;
    },
    json(data) {
      responseData = data;
      return this;
    },
    get statusCode() {
      return statusCode;
    },
  };
  const next = (err) => {
    nextError = err;
  };

  return { req, res, getStatus: () => statusCode, getData: () => responseData, getError: () => nextError };
}

describe('GET /health (liveness probe)', () => {
  it('returns static HTTP 200 ok synchronously without probing downstreams', () => {
    const handler = getRouteHandler('/health');
    const { req, res, getStatus, getData } = mockReqRes();

    handler(req, res, () => {});

    assert.equal(getStatus(), 200);
    const data = getData();
    assert.ok(data);
    assert.equal(data.status, 'ok');
    assert.equal(data.service, 'sonara-backend');
    assert.equal(data.version, '1.0.0');
    assert.ok(data.ts);
  });
});

describe('GET /health/deep (readiness / deep health probe)', () => {
  const origFetch = globalThis.fetch;

  it('returns HTTP 200 with canonical HEALTHY report when upstreams respond', async () => {
    let gatewayProbed = false;
    globalThis.fetch = async (url) => {
      if (String(url).includes('3002/health')) {
        gatewayProbed = true;
        throw new Error('Self-probe deadlock: gateway /health was called from /health/deep');
      }
      return {
        ok: true,
        status: 200,
        async text() { return ''; },
      };
    };

    try {
      const handler = getRouteHandler('/health/deep');
      const { req, res, getStatus, getData, getError } = mockReqRes();

      await handler(req, res, () => {});

      assert.equal(getError(), null);
      assert.equal(gatewayProbed, false, 'GET /health/deep must never probe the gateway itself');
      assert.equal(getStatus(), 200);

      const report = getData();
      assert.ok(report);
      assert.equal(report.schemaVersion, 1);
      assert.equal(report.status, STATUS.HEALTHY);
      assert.equal(report.includeExternal, false);

      const gw = report.checks.find((c) => c.id === 'gateway');
      assert.ok(gw);
      assert.equal(gw.status, STATUS.HEALTHY);
      assert.equal(gw.latencyMs, 0);
      assert.match(gw.detail, /in-process/);

      const yt = report.checks.find((c) => c.id === 'python_ytmusic');
      assert.ok(yt);
      assert.equal(yt.status, STATUS.HEALTHY);

      const audio = report.checks.find((c) => c.id === 'python_audio');
      assert.ok(audio);
      assert.equal(audio.status, STATUS.HEALTHY);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('returns HTTP 200 with DEGRADED status when a Python upstream fails', async () => {
    globalThis.fetch = async (url) => {
      if (String(url).includes('5000/health')) {
        return {
          ok: false,
          status: 503,
          async text() { return ''; },
        };
      }
      return {
        ok: true,
        status: 200,
        async text() { return ''; },
      };
    };

    try {
      const handler = getRouteHandler('/health/deep');
      const { req, res, getStatus, getData, getError } = mockReqRes();

      await handler(req, res, () => {});

      assert.equal(getError(), null);
      assert.equal(getStatus(), 200); // DEGRADED still returns 200 (gateway is operational)

      const report = getData();
      assert.ok(report);
      assert.equal(report.status, STATUS.DEGRADED);

      const yt = report.checks.find((c) => c.id === 'python_ytmusic');
      assert.equal(yt.status, STATUS.DEGRADED);
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('produces a leak-free report (no tokens, passwords, or credentials exposed)', async () => {
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      async text() { return ''; },
    });

    try {
      const handler = getRouteHandler('/health/deep');
      const { req, res, getData } = mockReqRes();

      await handler(req, res, () => {});

      const serialized = JSON.stringify(getData());
      assert.ok(!/token|password|secret|apikey|cookie|authorization/i.test(serialized));
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});
