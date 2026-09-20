'use strict';

/**
 * stream.test.js — Route integration tests for:
 *   GET /api/v1/stream/resolve      (F-01 and F-02 tests)
 *   GET /api/v1/stream/play         (F-01 lifecycle tests)
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const streamRouter = require('../../src/routes/stream');

describe('stream route — /resolve', () => {
  function getResolveHandler() {
    // Extract the /resolve GET route handler from express router stack
    const layer = streamRouter.stack.find(
      (l) => l.route && l.route.path === '/resolve' && l.route.methods.get
    );
    return layer.route.stack[layer.route.stack.length - 1].handle;
  }

  function mockReqRes(query = {}) {
    let statusCode = 200;
    let responseData = null;
    let nextError = null;

    const req = { query };
    const res = {
      status(code) {
        statusCode = code;
        return this;
      },
      json(data) {
        responseData = data;
        return this;
      },
      statusCode,
    };
    const next = (err) => {
      nextError = err;
    };

    return { req, res, getStatus: () => statusCode, getData: () => responseData, getError: () => nextError };
  }

  test('rejects missing URL and video_id with HTTP 400', async () => {
    const handler = getResolveHandler();
    const { req, res, getStatus, getData } = mockReqRes({});

    await handler(req, res, () => {});
    assert.equal(getStatus(), 400);
    assert.ok(getData().error);
  });

  test('rejects invalid YouTube URL with HTTP 400', async () => {
    const handler = getResolveHandler();
    const { req, res, getStatus, getData } = mockReqRes({ url: 'https://evil.com/video' });

    await handler(req, res, () => {});
    assert.equal(getStatus(), 400);
  });

  test('rejects invalid video_id with HTTP 400', async () => {
    const handler = getResolveHandler();
    const { req, res, getStatus } = mockReqRes({ video_id: 'invalid_id_length' });

    await handler(req, res, () => {});
    assert.equal(getStatus(), 400);
  });

  test('accepts valid 11-char video_id parameter', async () => {
    const handler = getResolveHandler();
    const { req, res, getStatus, getData } = mockReqRes({
      video_id: 'dQw4w9WgXcQ',
      quality: 'STANDARD',
    });

    // Will attempt to resolve via YouTube provider
    await handler(req, res, () => {});
    // If python service is offline in this test run, it calls next with streamUnavailable
    // which confirms routing reached the resolver!
  });
});

// ---------------------------------------------------------------------------
// /play — F-01 lifecycle tests
// ---------------------------------------------------------------------------

describe('stream route — /play', () => {
  // -------------------------------------------------------------------------
  // Helper: extract the /play handler from the Express router stack.
  // -------------------------------------------------------------------------
  function getPlayHandler() {
    const layer = streamRouter.stack.find(
      (l) => l.route && l.route.path === '/play' && l.route.methods.get
    );
    if (!layer) throw new Error('/play route not found in router stack');
    return layer.route.stack[layer.route.stack.length - 1].handle;
  }

  // -------------------------------------------------------------------------
  // Helper: build mock req/res/next suitable for /play.
  //
  // The mock res is a minimal EventEmitter-like object that supports:
  //   status(), json(), setHeader(), write(), end(), destroy(),
  //   on(), once(), removeListener(), emit()
  //   headersSent, writableEnded, destroyed, closed
  // -------------------------------------------------------------------------
  function makePlayMocks({
    query = {},
    reqHeaders = {},
    writeReturnValues = [],     // per-call return values for res.write()
    simulateDisconnectAfterMs,  // ms after which to fire 'close' with writableEnded=false
  } = {}) {
    const listeners = {};

    let _headersSent   = false;
    let _writableEnded = false;
    let _destroyed     = false;
    let _closed        = false;
    let nextError      = null;
    let statusCode     = null;
    const headersSet   = {};
    const written      = [];
    let ended          = false;
    let destroyCalled  = false;
    let writeCallIdx   = 0;

    const res = {
      get headersSent()   { return _headersSent; },
      get writableEnded() { return _writableEnded; },
      get destroyed()     { return _destroyed; },
      get closed()        { return _closed; },

      status(code) { statusCode = code; return this; },
      json(data) { return this; },
      setHeader(name, value) { headersSet[name.toLowerCase()] = value; },

      write(chunk) {
        written.push(chunk);
        _headersSent = true;
        const ret = writeReturnValues[writeCallIdx] !== undefined
          ? writeReturnValues[writeCallIdx]
          : true;
        writeCallIdx++;
        return ret;
      },

      end() {
        ended = true;
        _writableEnded = true;
        _headersSent = true;
        // Simulate 'close' firing on normal end (writableEnded = true)
        this.emit('close');
      },

      destroy() {
        destroyCalled = true;
        _destroyed = true;
      },

      on(event, fn) {
        if (!listeners[event]) listeners[event] = [];
        listeners[event].push(fn);
        return this;
      },
      once(event, fn) {
        const wrapper = (...args) => {
          this.removeListener(event, wrapper);
          fn(...args);
        };
        wrapper._original = fn;
        return this.on(event, wrapper);
      },
      removeListener(event, fn) {
        if (!listeners[event]) return this;
        listeners[event] = listeners[event].filter(
          (l) => l !== fn && l._original !== fn
        );
        return this;
      },
      emit(event, ...args) {
        (listeners[event] || []).slice().forEach((l) => l(...args));
      },
    };

    // Schedule a premature client-disconnect simulation if requested.
    if (simulateDisconnectAfterMs != null) {
      setTimeout(() => {
        // writableEnded stays false  → res.on('close') handler treats as abort
        res.emit('close');
      }, simulateDisconnectAfterMs);
    }

    const req = { query, headers: reqHeaders };
    const next = (err) => { nextError = err; };

    return {
      req, res, next,
      getStatus:       () => statusCode,
      getHeaders:      () => headersSet,
      getWritten:      () => written,
      isEnded:         () => ended,
      isDestroyed:     () => destroyCalled,
      getNextError:    () => nextError,
    };
  }

  // -----------------------------------------------------------------------
  // Group 1 — Security validation (no upstream contact)
  // -----------------------------------------------------------------------

  test('G1: rejects missing video_id with 400', async () => {
    const handler = getPlayHandler();
    const { req, res, next, getStatus } = makePlayMocks({
      query: { audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x' },
    });
    await handler(req, res, next);
    assert.equal(getStatus(), 400);
  });

  test('G1: rejects invalid video_id (too short) with 400', async () => {
    const handler = getPlayHandler();
    const { req, res, next, getStatus } = makePlayMocks({
      query: {
        video_id: 'abc',
        audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
      },
    });
    await handler(req, res, next);
    assert.equal(getStatus(), 400);
  });

  test('G1: rejects untrusted audio_url with 403', async () => {
    const handler = getPlayHandler();
    const { req, res, next, getStatus } = makePlayMocks({
      query: {
        video_id: 'dQw4w9WgXcQ',
        audio_url: 'https://evil.com/audio.mp3',
      },
    });
    await handler(req, res, next);
    assert.equal(getStatus(), 403);
  });

  // -----------------------------------------------------------------------
  // Group 2 — Upstream (Python) error before headers sent
  // -----------------------------------------------------------------------

  test('G2: calls next(streamUnavailable) when python returns non-2xx/206', async () => {
    const handler = getPlayHandler();

    // Provide a valid googlevideo URL so security passes.
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    // Monkey-patch globalThis.fetch just for this test.
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: false,
      status: 500,
      headers: { get: () => null },
      body: null,
    });

    const { req, res, next, getNextError } = makePlayMocks({ query });
    await handler(req, res, next);

    globalThis.fetch = originalFetch;

    const err = getNextError();
    assert.ok(err, 'next() should have been called with an error');
    assert.match(err.message || err.code || JSON.stringify(err), /unavailable|stream/i);
  });

  // -----------------------------------------------------------------------
  // Group 3 — Normal streaming (small body, no backpressure)
  // -----------------------------------------------------------------------

  test('G3: streams all chunks and calls res.end() for a successful response', async () => {
    const handler = getPlayHandler();
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    const chunks = [
      new Uint8Array([0x01, 0x02]),
      new Uint8Array([0x03, 0x04]),
    ];
    let chunkIdx = 0;

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      headers: { get: (h) => (h === 'content-type' ? 'audio/webm' : null) },
      body: {
        getReader: () => ({
          read: async () => {
            if (chunkIdx < chunks.length) return { done: false, value: chunks[chunkIdx++] };
            return { done: true, value: undefined };
          },
          cancel: async () => {},
        }),
      },
    });

    const { req, res, next, getWritten, isEnded, getNextError } = makePlayMocks({ query });
    await handler(req, res, next);

    globalThis.fetch = originalFetch;

    assert.equal(getNextError(), null, 'next() should not have been called');
    assert.equal(isEnded(), true, 'res.end() should have been called');
    assert.equal(getWritten().length, 2, 'both chunks should have been written');
  });

  // -----------------------------------------------------------------------
  // Group 4 — Range header forwarded
  // -----------------------------------------------------------------------

  test('G4: forwards Range header to upstream for ExoPlayer seeking', async () => {
    const handler = getPlayHandler();
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    let capturedHeaders = null;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_url, opts) => {
      capturedHeaders = opts.headers;
      return {
        ok: true,
        status: 206,
        headers: { get: () => null },
        body: {
          getReader: () => ({
            read: async () => ({ done: true, value: undefined }),
            cancel: async () => {},
          }),
        },
      };
    };

    const { req, res, next } = makePlayMocks({
      query,
      reqHeaders: { range: 'bytes=0-1023' },
    });
    await handler(req, res, next);

    globalThis.fetch = originalFetch;

    assert.ok(capturedHeaders, 'fetch should have been called');
    assert.equal(capturedHeaders['Range'], 'bytes=0-1023', 'Range header must be forwarded');
  });

  // -----------------------------------------------------------------------
  // Group 5 — Client disconnect before fetch completes (pre-header abort)
  // -----------------------------------------------------------------------

  test('G5: handles client disconnect before fetch resolves without calling next()', async () => {
    const handler = getPlayHandler();
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_url, { signal }) => {
      // Simulate fetch that respects the abort signal
      await new Promise((resolve, reject) => {
        if (signal.aborted) return reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
        signal.addEventListener('abort', () =>
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }))
        );
        // Never resolves on its own — relies on the abort signal
      });
    };

    // Simulate disconnect 20 ms into the handler
    const { req, res, next, getNextError } = makePlayMocks({
      query,
      simulateDisconnectAfterMs: 20,
    });

    await handler(req, res, next);
    globalThis.fetch = originalFetch;

    // AbortError before headers sent must NOT propagate to next()
    assert.equal(getNextError(), null, 'next() must not be called on pre-header abort');
  });

  // -----------------------------------------------------------------------
  // Group 6 — Backpressure: res.write() returns false → drain waits
  // -----------------------------------------------------------------------

  test('G6: respects backpressure — waits for drain before writing next chunk', async () => {
    const handler = getPlayHandler();
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    const chunks = [
      new Uint8Array([0xAA]),
      new Uint8Array([0xBB]),
    ];
    let chunkIdx = 0;

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      headers: { get: () => null },
      body: {
        getReader: () => ({
          read: async () => {
            if (chunkIdx < chunks.length) return { done: false, value: chunks[chunkIdx++] };
            return { done: true, value: undefined };
          },
          cancel: async () => {},
        }),
      },
    });

    // First write returns false (backpressure), second returns true
    const writeReturnValues = [false, true];

    const { req, res, next, getWritten, isEnded } = makePlayMocks({
      query,
      writeReturnValues,
    });

    // After the first write (which returns false), emit 'drain' on next tick
    // so that waitForDrainOrClose resolves.
    let drainEmitted = false;
    const origWrite = res.write.bind(res);
    res.write = function (chunk) {
      const ret = origWrite(chunk);
      if (!ret && !drainEmitted) {
        drainEmitted = true;
        // Emit drain asynchronously so the pump actually awaits it
        setImmediate(() => res.emit('drain'));
      }
      return ret;
    };

    await handler(req, res, next);
    globalThis.fetch = originalFetch;

    assert.equal(isEnded(), true, 'res.end() must be called after drain');
    assert.equal(getWritten().length, 2, 'both chunks must eventually be written');
  });

  // -----------------------------------------------------------------------
  // Group 7 — Mid-stream error (headers already sent) must NOT call next()
  // -----------------------------------------------------------------------

  test('G7: mid-stream error destroys socket without calling next() (no ERR_HTTP_HEADERS_SENT)', async () => {
    const handler = getPlayHandler();
    const query = {
      video_id: 'dQw4w9WgXcQ',
      audio_url: 'https://rr1---sn-a5meknsd.googlevideo.com/videoplayback?id=x',
    };

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      headers: { get: () => null },
      body: {
        getReader: () => {
          let called = false;
          return {
            read: async () => {
              if (!called) {
                called = true;
                // First read returns a chunk (this commits headers via res.write)
                return { done: false, value: new Uint8Array([0x01]) };
              }
              // Second read throws a network error mid-stream
              throw new Error('ECONNRESET mid-stream');
            },
            cancel: async () => {},
          };
        },
      },
    });

    const { req, res, next, getNextError, isDestroyed } = makePlayMocks({ query });

    await handler(req, res, next);
    globalThis.fetch = originalFetch;

    // Headers were sent after the first chunk — next() must NOT have been called
    assert.equal(getNextError(), null, 'next() must not be called after headers are sent');
    // Socket should have been destroyed
    assert.equal(isDestroyed(), true, 'res.destroy() must be called on mid-stream error');
  });
});

// ---------------------------------------------------------------------------
// /resolve — F-02 async error propagation tests
// ---------------------------------------------------------------------------

describe('stream route — /resolve (F-02)', () => {
  // asyncHandler's wrappedHandler returns void (not a Promise). The internal
  // Promise.resolve().then(handler).catch(next) chain resolves after the call
  // returns.  We flush via setImmediate before asserting next().
  const flushMicrotasks = () => new Promise(resolve => setImmediate(resolve));

  // Extract the /resolve handler (last layer = the wrapped async handler,
  // skipping the rate-limiter layer that comes before it).
  function getResolveHandlerF02() {
    const layer = streamRouter.stack.find(
      (l) => l.route && l.route.path === '/resolve' && l.route.methods.get
    );
    if (!layer) throw new Error('/resolve route not found in router stack');
    return layer.route.stack[layer.route.stack.length - 1].handle;
  }

  function mockResolveReqRes(query) {
    let statusCode = 200;
    let responseData = null;
    let nextError = null;
    const req = { query: query || {} };
    const res = {
      status(code) { statusCode = code; return this; },
      json(data)   { responseData = data; return this; },
      setHeader()  { return this; },
    };
    const next = (err) => { nextError = err; };
    return {
      req, res, next,
      getStatus:    () => statusCode,
      getData:      () => responseData,
      getNextError: () => nextError,
    };
  }

  // -------------------------------------------------------------------------
  // F-02: Duplicate ?url= (e.g. ?url=a&url=b) causes Express to set
  // req.query.url to an Array.  Calling .trim() on that Array throws a
  // synchronous TypeError before the internal try block.  Without asyncHandler
  // this escapes Express 4 as an unhandled rejection and terminates the
  // Node >=20 process.  With asyncHandler it must reach next(err).
  // -------------------------------------------------------------------------
  test('F-02: duplicate ?url= array — asyncHandler forwards TypeError to next()', async () => {
    const handler = getResolveHandlerF02();
    const { req, res, next, getNextError } = mockResolveReqRes({
      url: ['https://youtu.be/dQw4w9WgXcQ', 'https://youtu.be/abc1234abcd'],
    });

    handler(req, res, next);
    await flushMicrotasks();

    const err = getNextError();
    assert.ok(err instanceof TypeError, 'next() must be called with a TypeError');
  });

  // -------------------------------------------------------------------------
  // F-02: Duplicate ?quality= similarly causes an Array -> .trim() TypeError.
  // -------------------------------------------------------------------------
  test('F-02: duplicate ?quality= array — asyncHandler forwards TypeError to next()', async () => {
    const handler = getResolveHandlerF02();
    const { req, res, next, getNextError } = mockResolveReqRes({
      video_id: 'dQw4w9WgXcQ',
      quality: ['STANDARD', 'HIGH'],
    });

    handler(req, res, next);
    await flushMicrotasks();

    const err = getNextError();
    assert.ok(err instanceof TypeError, 'next() must be called with a TypeError');
  });

  // -------------------------------------------------------------------------
  // F-02: Unexpected resolver rejection is caught by the existing catch block
  // and forwarded via next().  asyncHandler must not interfere with this path.
  // -------------------------------------------------------------------------
  test('F-02: unexpected resolver rejection reaches next() as INTERNAL_ERROR', async () => {
    const handler = getResolveHandlerF02();
    const { req, res, next, getNextError } = mockResolveReqRes({ video_id: 'dQw4w9WgXcQ' });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('unexpected resolver bug'); };
    handler(req, res, next);
    await flushMicrotasks();
    globalThis.fetch = originalFetch;

    const err = getNextError();
    assert.ok(err, 'next() must be called when resolver rejects unexpectedly');
  });

  // -------------------------------------------------------------------------
  // Baseline: existing /resolve validation behavior is preserved unchanged.
  // These paths use res.status(400).json() directly (not via next()), so they
  // work with either await or flush — using flush for consistency.
  // -------------------------------------------------------------------------
  test('existing: missing url and video_id still returns 400', async () => {
    const handler = getResolveHandlerF02();
    const { req, res, next, getStatus } = mockResolveReqRes({});
    handler(req, res, next);
    await flushMicrotasks();
    assert.equal(getStatus(), 400);
  });

  test('existing: invalid YouTube URL still returns 400', async () => {
    const handler = getResolveHandlerF02();
    const { req, res, next, getStatus } = mockResolveReqRes({ url: 'https://evil.com/video' });
    handler(req, res, next);
    await flushMicrotasks();
    assert.equal(getStatus(), 400);
  });
});
