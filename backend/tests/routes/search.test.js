'use strict';

/**
 * search.test.js - Route integration tests for /api/v1/search
 *
 * F-02 regression: verify that async route handlers wrapped with asyncHandler
 * forward all rejections (including pre-try synchronous throws from duplicate
 * query parameters) to Express error middleware instead of crashing the process.
 *
 * Implementation note on microtask flushing:
 *   asyncHandler's wrappedHandler returns void, not a Promise.  It launches a
 *   Promise.resolve().then(handler).catch(next) chain that resolves on the next
 *   microtask tick.  Tests that need to observe next() must await that flush via
 *   'await new Promise(resolve => setImmediate(resolve))'.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const searchRouter = require('../../src/routes/search');
const config = require('../../src/config/env');

// Flush the microtask queue so asyncHandler's .catch(next) fires.
const flushMicrotasks = () => new Promise(resolve => setImmediate(resolve));

// ---------------------------------------------------------------------------
// Helper: extract a named GET route handler from the Express router stack.
// The last element in route.stack is always the actual (wrapped) route handler;
// any earlier elements are middleware layers (e.g. rate limiters).
// ---------------------------------------------------------------------------
function getSearchHandler(routePath) {
  const layer = searchRouter.stack.find(
    (l) => l.route && l.route.path === routePath && l.route.methods.get
  );
  if (!layer) throw new Error('GET ' + routePath + ' route not found in search router stack');
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

// ---------------------------------------------------------------------------
// Helper: build a minimal mock req / res / next for search routes.
// ---------------------------------------------------------------------------
function makeSearchMocks(query) {
  let statusCode = 200;
  let responseData = null;
  let nextError = null;

  const req = { query: query || {} };
  const res = {
    status(code) { statusCode = code; return this; },
    json(data) { responseData = data; return this; },
  };
  const next = (err) => { nextError = err; };

  return {
    req, res, next,
    getStatus:    () => statusCode,
    getData:      () => responseData,
    getNextError: () => nextError,
  };
}

// ===========================================================================
// GET /
// ===========================================================================

describe('search route - GET /', () => {
  // -------------------------------------------------------------------------
  // F-02: Duplicate query parameters (e.g. ?q=a&q=b) cause Express to place
  // an Array in req.query.q.  Calling .trim() on an Array throws a synchronous
  // TypeError.  Without asyncHandler this escapes Express 4 as an unhandled
  // rejection (process crash on Node >= 20).  With asyncHandler it must reach
  // next(err).
  //
  // Note: asyncHandler's wrappedHandler returns void.  We call the handler
  // synchronously and then flush the microtask queue before asserting.
  // -------------------------------------------------------------------------
  test('F-02: duplicate ?q= array causes asyncHandler to forward TypeError to next()', async () => {
    const handler = getSearchHandler('/');
    const { req, res, next, getNextError } = makeSearchMocks({ q: ['kesariya', 'arijit'] });

    handler(req, res, next);      // returns void — asyncHandler launches microtask
    await flushMicrotasks();       // wait for .catch(next) to fire

    const err = getNextError();
    assert.ok(err instanceof Error, 'next() must be called with a TypeError');
    assert.ok(err instanceof TypeError, 'error must be a TypeError from .trim() on Array');
  });

  // -------------------------------------------------------------------------
  // F-02: ytmusicProvider.searchSongs() catches all internal errors and
  // returns [] — so fetch errors do NOT propagate to next().  Instead, verify
  // that when fetch fails, the route gracefully returns an empty array
  // (existing degradation behavior is preserved under asyncHandler).
  // -------------------------------------------------------------------------
  test('existing: fetch failure causes searchSongs to degrade to empty result', async () => {
    const handler = getSearchHandler('/');
    const { req, res, next, getData, getNextError } = makeSearchMocks({ q: 'kesariya' });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('ECONNREFUSED'); };

    handler(req, res, next);
    await flushMicrotasks();

    globalThis.fetch = originalFetch;

    // ytmusicProvider catches internally → returns [] → route calls res.json([])
    assert.equal(getNextError(), null, 'next() must NOT be called when ytmusicProvider degrades');
    const data = getData();
    assert.ok(Array.isArray(data), 'route must return an array (empty results)');
  });

  // -------------------------------------------------------------------------
  // Baseline: missing ?q still returns HTTP 400 (existing behavior preserved).
  // This path uses res.status(400).json() directly (not next()), so it works
  // without microtask flushing.
  // -------------------------------------------------------------------------
  test('existing: missing ?q returns 400', async () => {
    const handler = getSearchHandler('/');
    const { req, res, next, getStatus } = makeSearchMocks({});

    handler(req, res, next);
    await flushMicrotasks();

    assert.equal(getStatus(), 400);
  });
});

// ===========================================================================
// GET /videos
// ===========================================================================

describe('search route - GET /videos', () => {
  test('F-02: duplicate ?q= array causes asyncHandler to forward TypeError to next()', async () => {
    const handler = getSearchHandler('/videos');
    const { req, res, next, getNextError } = makeSearchMocks({ q: ['a', 'b'] });

    handler(req, res, next);
    await flushMicrotasks();

    const err = getNextError();
    assert.ok(err instanceof TypeError, 'next() must be called with a TypeError');
  });

  test('F-02: Python 500 gracefully degrades to { items: [] }', async () => {
    const handler = getSearchHandler('/videos');
    const { req, res, next, getData } = makeSearchMocks({ q: 'song' });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({ ok: false, status: 500 });

    handler(req, res, next);
    await flushMicrotasks();

    globalThis.fetch = originalFetch;

    const data = getData();
    assert.ok(data && Array.isArray(data.items) && data.items.length === 0,
      'Python 500 should degrade to { items: [] }');
  });

  test('existing: missing ?q returns 400', async () => {
    const handler = getSearchHandler('/videos');
    const { req, res, next, getStatus } = makeSearchMocks({});

    handler(req, res, next);
    await flushMicrotasks();

    assert.equal(getStatus(), 400);
  });

  test('F-13: limit query param is bounded between 1 and 50 (HYGIENE-05)', async () => {
    const handler = getSearchHandler('/videos');
    const cases = [
      { input: '1000', expected: '50' },
      { input: '51',   expected: '50' },
      { input: '50',   expected: '50' },
      { input: '20',   expected: '20' },
      { input: '1',    expected: '1' },
      { input: '0',    expected: '10' },
      { input: '-10',  expected: '1' },
      { input: 'abc',  expected: '10' },
      { input: undefined, expected: '10' },
    ];

    for (const { input, expected } of cases) {
      let capturedUrl = null;
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (url) => {
        capturedUrl = url;
        return {
          ok: true,
          json: async () => ({ items: [] }),
        };
      };

      try {
        const query = { q: 'test' };
        if (input !== undefined) query.limit = input;
        const { req, res, next } = makeSearchMocks(query);
        handler(req, res, next);
        await flushMicrotasks();

        assert.ok(capturedUrl, `fetch should be called for limit=${input}`);
        const parsed = new URL(capturedUrl);
        assert.equal(
          parsed.searchParams.get('limit'),
          expected,
          `limit=${input} should clamp to upstream limit=${expected}`
        );
      } finally {
        globalThis.fetch = originalFetch;
      }
    }
  });
});

// ===========================================================================
// GET /suggestions
// ===========================================================================

describe('search route - GET /suggestions', () => {
  // -------------------------------------------------------------------------
  // F-02: Duplicate ?q= -> Array -> .trim() throws before internal try/catch.
  // asyncHandler + the existing internal catch together guarantee no crash.
  // Because /suggestions catches its own errors and responds (never calls
  // next(err)), the test asserts that the handler completes without throwing
  // at the test-runner level and that a response is returned.
  // -------------------------------------------------------------------------
  test('F-02: duplicate ?q= array must not produce an unhandled rejection', async () => {
    const handler = getSearchHandler('/suggestions');
    const { req, res, next, getData, getNextError } = makeSearchMocks({ q: ['song1', 'song2'] });

    // Must complete without throwing at the test-runner level.
    handler(req, res, next);
    await flushMicrotasks();

    // asyncHandler forwards the TypeError to next(err); the /suggestions route
    // also has its own catch that returns { query, suggestions:[] }.
    // Either behavior is acceptable — the invariant is NO unhandled rejection.
    const data = getData();
    const err = getNextError();
    assert.ok(data !== null || err instanceof Error,
      'handler must either return data or forward error — must not crash');
  });

  test('existing: empty ?q returns { query: "", suggestions: [] }', async () => {
    const handler = getSearchHandler('/suggestions');
    const { req, res, next, getData } = makeSearchMocks({ q: '' });

    handler(req, res, next);
    await flushMicrotasks();

    const data = getData();
    assert.equal(data.query, '');
    assert.deepEqual(data.suggestions, []);
  });

  test('existing: missing ?q returns empty suggestions', async () => {
    const handler = getSearchHandler('/suggestions');
    const { req, res, next, getData } = makeSearchMocks({});

    handler(req, res, next);
    await flushMicrotasks();

    assert.deepEqual(getData().suggestions, []);
  });

  test('existing: fetch timeout falls back to { query, suggestions: [] }', async () => {
    const handler = getSearchHandler('/suggestions');
    const { req, res, next, getData } = makeSearchMocks({ q: 'kesariya' });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
      throw Object.assign(new Error('timeout'), { name: 'TimeoutError' });
    };

    handler(req, res, next);
    await flushMicrotasks();

    globalThis.fetch = originalFetch;

    const data = getData();
    assert.ok(data, 'should have fallback response');
    assert.equal(data.query, 'kesariya');
    assert.deepEqual(data.suggestions, []);
  });
});

// ===========================================================================
// F-03: Configuration — searchTimeoutMs
// ===========================================================================

describe('search configuration — F-03', () => {
  test('config.searchTimeoutMs is exported and is a positive number', () => {
    assert.equal(typeof config.searchTimeoutMs, 'number');
    assert.ok(config.searchTimeoutMs > 0, 'searchTimeoutMs must be positive');
  });

  test('config.searchTimeoutMs resolves to 12000 by default', () => {
    assert.equal(config.searchTimeoutMs, 12000);
  });

  test('SEARCH_TIMEOUT_MS environment variable overrides default when re-evaluated', () => {
    const originalEnv = process.env.SEARCH_TIMEOUT_MS;
    try {
      process.env.SEARCH_TIMEOUT_MS = '20000';
      delete require.cache[require.resolve('../../src/config/env')];
      const reloadedConfig = require('../../src/config/env');
      assert.equal(reloadedConfig.searchTimeoutMs, 20000);
    } finally {
      if (originalEnv !== undefined) {
        process.env.SEARCH_TIMEOUT_MS = originalEnv;
      } else {
        delete process.env.SEARCH_TIMEOUT_MS;
      }
      delete require.cache[require.resolve('../../src/config/env')];
      require('../../src/config/env');
    }
  });
});

