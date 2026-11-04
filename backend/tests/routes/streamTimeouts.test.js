'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const config = require('../../src/config/env');
const router = require('../../src/routes/stream');
const handler = router.stack.find((layer) => layer.route?.path === '/play').route.stack[0].handle;
const TIMEOUT = 100;

// Advance timers and promise continuations together. Mock AbortSignal.timeout as
// well, so the original total-lifetime deadline is exercised without wall time.
async function flush() {
  for (let i = 0; i < 30; i++) await Promise.resolve();
}

function clockFor(t) {
  let now = 0;
  let id = 0;
  const timers = new Map();
  t.mock.method(globalThis, 'setTimeout', (fn, delay) => {
    const handle = ++id;
    timers.set(handle, { at: now + delay, fn });
    return handle;
  });
  t.mock.method(globalThis, 'clearTimeout', (handle) => timers.delete(handle));
  t.mock.method(AbortSignal, 'timeout', (delay) => {
    const controller = new AbortController();
    setTimeout(() => controller.abort(new DOMException('Timed out', 'TimeoutError')), delay);
    return controller.signal;
  });
  return {
    timers,
    async advance(ms) {
      const target = now + ms;
      await flush();
      while (true) {
        const next = [...timers].filter(([, timer]) => timer.at <= target)
          .sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at;
        timers.delete(next[0]);
        next[1].fn();
        await flush();
      }
      now = target;
      await flush();
    },
  };
}

function setup(t, { steps = [], headerDelay = 0, headerError, status = 200,
  responseHeaders = {}, backpressure = false, cancelRejects = false, genericBodyAbort = false } = {}) {
  const clock = clockFor(t);
  const oldTimeout = config.streamResolveTimeoutMs;
  config.streamResolveTimeoutMs = TIMEOUT;
  const stats = { reads: 0, cancels: 0, releases: 0, aborts: 0, pending: 0 };
  const errors = [];
  const written = [];
  const res = new EventEmitter();
  Object.assign(res, {
    headersSent: false, writableEnded: false, destroyed: false, destroyCount: 0,
    headers: {},
    status(code) { this.statusCode = code; return this; },
    setHeader(name, value) { this.headers[name] = value; },
    write(chunk) { this.headersSent = true; written.push(chunk); return !backpressure; },
    end() { this.headersSent = true; this.writableEnded = true; this.emit('close'); },
    destroy() { this.destroyCount++; this.destroyed = true; this.emit('close'); },
  });
  const req = {
    query: { video_id: 'dQw4w9WgXcQ', audio_url: 'https://rr1.googlevideo.com/videoplayback?id=x' },
    headers: { range: 'bytes=10-19' },
  };
  let signal;
  let fetchOptions;
  let pendingCancel;
  function wait(delay, result, error) {
    return new Promise((resolve, reject) => {
      let timer;
      const finish = (err) => {
        clearTimeout(timer);
        signal.removeEventListener('abort', onAbort);
        pendingCancel = null;
        stats.pending--;
        if (err) reject(err); else resolve(result);
      };
      const onAbort = () => finish(genericBodyAbort && stats.reads > 0
        ? new DOMException('Body aborted', 'AbortError') : signal.reason);
      stats.pending++;
      if (signal.aborted) { finish(signal.reason); return; }
      signal.addEventListener('abort', onAbort, { once: true });
      pendingCancel = () => finish(new DOMException('Cancelled', 'AbortError'));
      if (delay !== Infinity) timer = setTimeout(() => finish(error), delay);
    });
  }
  const cancel = async () => {
    stats.cancels++;
    pendingCancel?.();
    if (cancelRejects) throw new Error('cancel failed');
  };
  const reader = {
    read() {
      const step = steps[stats.reads++] || { done: true };
      return wait(step.delay || 0, {
        done: !!step.done, value: step.value === undefined ? new Uint8Array([stats.reads]) : step.value,
      }, step.error);
    },
    cancel,
    releaseLock() { assert.equal(stats.pending, 0); stats.releases++; },
  };
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    signal = options.signal;
    fetchOptions = options;
    signal.addEventListener('abort', () => { stats.aborts++; }, { once: true });
    return wait(headerDelay, {
      ok: status >= 200 && status < 300, status,
      headers: { get: (name) => responseHeaders[name] || null },
      body: { getReader: () => reader, cancel },
    }, headerError);
  });
  let settled = false;
  const running = handler(req, res, (error) => errors.push(error)).then(() => { settled = true; });
  t.after(async () => {
    // Always stop the route, including when an assertion fails on the old code.
    if (!settled) res.emit('close');
    await clock.advance(1000);
    await running;
    config.streamResolveTimeoutMs = oldTimeout;
  });
  return {
    clock, stats, errors, res, written,
    get signal() { return signal; },
    get fetchOptions() { return fetchOptions; },
    get settled() { return settled; },
    assertClean() {
      assert.equal(clock.timers.size, 0, 'all request timers must be cleared');
      assert.equal(stats.pending, 0, 'no pending upstream operation');
      assert.equal(res.listenerCount('close'), 0, 'close listeners must be removed');
      assert.equal(res.listenerCount('drain'), 0, 'drain listeners must be removed');
    },
  };
}

test('F4: byte progress continues beyond the former total lifetime deadline', async (t) => {
  const h = setup(t, { steps: Array.from({ length: 6 }, () => ({ delay: 40 })) });
  await h.clock.advance(250);
  assert.equal(h.res.writableEnded, true, 'active stream must complete past the deadline');
  assert.equal(h.written.length, 6);
  assert.equal(h.res.destroyCount, 0);
  assert.deepEqual(h.errors, []);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
});

test('F4: headers that never arrive still have a bounded timeout', async (t) => {
  const h = setup(t, { headerDelay: Infinity });
  await h.clock.advance(TIMEOUT - 1);
  assert.equal(h.settled, false);
  await h.clock.advance(1);
  assert.equal(h.settled, true);
  assert.equal(h.signal.aborted, true);
  assert.equal(h.errors.length, 1);
  assert.match(h.errors[0].message, /timed out/i);
  assert.equal(h.res.headersSent, false);
  h.assertClean();
});

test('F4: first-byte inactivity starts after headers, not at fetch start', async (t) => {
  const h = setup(t, { headerDelay: 80, steps: [{ delay: Infinity }] });
  await h.clock.advance(179);
  assert.equal(h.settled, false, 'body gets its own inactivity window');
  await h.clock.advance(1);
  assert.equal(h.settled, true);
  assert.equal(h.errors.length, 1);
  assert.match(h.errors[0].message, /timed out/i);
  assert.equal(h.stats.cancels, 1);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
});

test('F4: empty chunks do not reset the byte-progress deadline', async (t) => {
  const h = setup(t, { steps: [
    { delay: 80 },
    ...Array.from({ length: 5 }, () => ({ delay: 30, value: new Uint8Array(0) })),
  ] });
  await h.clock.advance(179);
  assert.equal(h.settled, false, 'nonempty chunk resets inactivity');
  await h.clock.advance(1);
  assert.equal(h.settled, true, 'empty chunks cannot keep a stalled stream alive');
  assert.equal(h.written.length, 1, 'empty chunks are not downstream progress');
  assert.equal(h.res.destroyCount, 1);
  assert.deepEqual(h.errors, []);
  assert.equal(h.stats.cancels, 1);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
});

test('F4: first-byte timeout remains an HTTP error when the body reports AbortError', async (t) => {
  const h = setup(t, { steps: [{ delay: Infinity }], genericBodyAbort: true });
  await h.clock.advance(TIMEOUT);
  assert.equal(h.settled, true);
  assert.equal(h.errors.length, 1, 'timeout must not be mistaken for client disconnect');
  assert.match(h.errors[0].message, /timed out/i);
  assert.equal(h.res.headersSent, false);
  h.assertClean();
});

test('F4: stalled read after bytes destroys once without next()', async (t) => {
  const h = setup(t, { steps: [{ delay: 60 }, { delay: Infinity }], cancelRejects: true });
  await h.clock.advance(159);
  assert.equal(h.settled, false);
  await h.clock.advance(1);
  assert.equal(h.settled, true);
  assert.equal(h.signal.aborted, true);
  assert.equal(h.res.destroyCount, 1);
  assert.deepEqual(h.errors, []);
  assert.equal(h.stats.cancels, 1);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
});

test('F4: backpressure longer than the timeout suspends upstream inactivity', async (t) => {
  const h = setup(t, { steps: [{ delay: 10 }, { delay: 50 }], backpressure: true });
  await h.clock.advance(250);
  assert.equal(h.signal.aborted, false, 'slow downstream is not an upstream stall');
  assert.equal(h.stats.reads, 1, 'do not read ahead while waiting for drain');
  assert.equal(h.clock.timers.size, 0, 'no upstream deadline while downstream is blocked');
  h.res.emit('drain');
  await h.clock.advance(50);
  assert.equal(h.written.length, 2);
  h.res.emit('drain');
  await h.clock.advance(0);
  assert.equal(h.res.writableEnded, true);
  assert.deepEqual(h.errors, []);
  h.assertClean();
});

test('F4: inactivity protection resumes after drain', async (t) => {
  const h = setup(t, { steps: [{}, { delay: Infinity }], backpressure: true });
  await h.clock.advance(250);
  h.res.emit('drain');
  await h.clock.advance(99);
  assert.equal(h.settled, false);
  await h.clock.advance(1);
  assert.equal(h.settled, true);
  assert.equal(h.res.destroyCount, 1);
  assert.deepEqual(h.errors, []);
  h.assertClean();
});

for (const phase of ['headers', 'read', 'backpressure']) {
  test(`F4: disconnect during ${phase} aborts and releases resources`, async (t) => {
    const h = setup(t, {
      headerDelay: phase === 'headers' ? Infinity : 0,
      steps: phase === 'read' ? [{}, { delay: Infinity }] : [{}],
      backpressure: phase === 'backpressure', cancelRejects: true,
    });
    await h.clock.advance(10);
    h.res.emit('close');
    await h.clock.advance(0);
    assert.equal(h.settled, true);
    assert.equal(h.signal.aborted, true);
    assert.equal(h.stats.aborts, 1);
    assert.equal(h.stats.cancels, phase === 'headers' ? 0 : 1);
    assert.equal(h.stats.releases, phase === 'headers' ? 0 : 1);
    assert.equal(h.res.writableEnded, false, 'disconnect is not normal completion');
    assert.equal(h.res.destroyCount, 0, 'do not destroy an already disconnected response');
    assert.deepEqual(h.errors, []);
    h.assertClean();
  });
}

test('F4: Range, status and audio headers are preserved and completion cleans timers', async (t) => {
  const headers = {
    'content-type': 'audio/webm', 'content-length': '10',
    'content-range': 'bytes 10-19/100', 'accept-ranges': 'bytes',
  };
  const h = setup(t, { status: 206, responseHeaders: headers, steps: [{ value: new Uint8Array(10) }] });
  await h.clock.advance(0);
  assert.equal(h.fetchOptions.headers.Range, 'bytes=10-19');
  assert.equal(h.res.statusCode, 206);
  assert.deepEqual(h.res.headers, headers);
  assert.equal(h.res.writableEnded, true);
  assert.equal(h.written[0].length, 10);
  assert.deepEqual(h.errors, []);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
  await h.clock.advance(500);
  assert.equal(h.res.destroyCount, 0, 'no delayed timeout after completion');
});

test('F4: non-OK upstream response retains streamUnavailable and cancels its body', async (t) => {
  const h = setup(t, { status: 503, cancelRejects: true });
  await h.clock.advance(0);
  assert.equal(h.errors.length, 1);
  assert.match(h.errors[0].message, /unavailable/i);
  assert.equal(h.res.headersSent, false);
  assert.equal(h.stats.reads, 0);
  assert.equal(h.stats.cancels, 1);
  assert.equal(h.signal.aborted, true);
  h.assertClean();
});

test('F4: preheader network failure retains internal error and cleans timers', async (t) => {
  const h = setup(t, { headerError: new Error('network failure') });
  await h.clock.advance(0);
  assert.equal(h.errors.length, 1);
  assert.equal(h.errors[0].code, 'INTERNAL_ERROR');
  assert.equal(h.res.headersSent, false);
  assert.equal(h.signal.aborted, true);
  h.assertClean();
});

test('F4: body failure destroys once and releases reader even when cancel rejects', async (t) => {
  const h = setup(t, { steps: [{}, { error: new Error('broken body') }], cancelRejects: true });
  await h.clock.advance(0);
  assert.equal(h.res.destroyCount, 1);
  assert.equal(h.res.writableEnded, false);
  assert.deepEqual(h.errors, []);
  assert.equal(h.signal.aborted, true);
  assert.equal(h.stats.cancels, 1);
  assert.equal(h.stats.releases, 1);
  h.assertClean();
});
