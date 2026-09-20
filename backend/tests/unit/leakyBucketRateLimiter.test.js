'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { LeakyBucketRateLimiter } = require('../../src/utils/LeakyBucketRateLimiter');
const { mbRateLimiter, MbRateLimiter } = require('../../src/identity/mbRateLimiter');
const { lbRateLimiter, LbRateLimiter } = require('../../src/recommendation/lbRateLimiter');

describe('LeakyBucketRateLimiter (shared engine)', () => {
  it('validates constructor options', () => {
    assert.throws(() => new LeakyBucketRateLimiter({ drainIntervalMs: 0, maxQueueDepth: 10 }), TypeError);
    assert.throws(() => new LeakyBucketRateLimiter({ drainIntervalMs: -100, maxQueueDepth: 10 }), TypeError);
    assert.throws(() => new LeakyBucketRateLimiter({ drainIntervalMs: 100, maxQueueDepth: 0 }), TypeError);
    assert.throws(() => new LeakyBucketRateLimiter({ drainIntervalMs: 100, maxQueueDepth: -5 }), TypeError);
  });

  it('exposes exact 9 metrics fields with correct initial values', () => {
    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 500, maxQueueDepth: 15, name: 'TestLimiter' });
    const metrics = limiter.getMetrics();

    const expectedKeys = [
      'drainIntervalMs',
      'maxQueueDepth',
      'currentDepth',
      'totalScheduled',
      'totalExecuted',
      'totalRejected',
      'totalSucceeded',
      'totalFailed',
      'lastDrainMs',
    ];

    assert.deepEqual(Object.keys(metrics).sort(), expectedKeys.sort());
    assert.equal(metrics.drainIntervalMs, 500);
    assert.equal(metrics.maxQueueDepth, 15);
    assert.equal(metrics.currentDepth, 0);
    assert.equal(metrics.totalScheduled, 0);
    assert.equal(metrics.totalExecuted, 0);
    assert.equal(metrics.totalRejected, 0);
    assert.equal(metrics.totalSucceeded, 0);
    assert.equal(metrics.totalFailed, 0);
    assert.equal(metrics.lastDrainMs, null);
  });

  it('executes tasks in strict FIFO order', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 100, maxQueueDepth: 10 });
    const order = [];

    const p1 = limiter.schedule(async () => { order.push(1); return 'first'; });
    const p2 = limiter.schedule(async () => { order.push(2); return 'second'; });
    const p3 = limiter.schedule(async () => { order.push(3); return 'third'; });

    const r1 = await p1;
    assert.deepEqual(order, [1]);

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(100);
    const r2 = await p2;
    assert.deepEqual(order, [1, 2]);

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(100);
    const r3 = await p3;
    assert.deepEqual(order, [1, 2, 3]);

    assert.equal(r1, 'first');
    assert.equal(r2, 'second');
    assert.equal(r3, 'third');

    // drain trailing idle timer
    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(100);
    t.mock.timers.reset();
  });

  it('enforces single-flight sequential execution (no overlapping)', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 50, maxQueueDepth: 10 });
    let concurrent = 0;
    let maxConcurrent = 0;

    const makeTask = (resolveDelay) => async () => {
      concurrent++;
      if (concurrent > maxConcurrent) maxConcurrent = concurrent;
      await new Promise(r => setTimeout(r, resolveDelay));
      concurrent--;
    };

    const p1 = limiter.schedule(makeTask(20));
    const p2 = limiter.schedule(makeTask(20));

    await new Promise(r => setImmediate(r));
    assert.equal(concurrent, 1);

    t.mock.timers.tick(20);
    await new Promise(r => setImmediate(r));
    assert.equal(concurrent, 0);

    t.mock.timers.tick(49);
    await new Promise(r => setImmediate(r));
    assert.equal(concurrent, 0);

    t.mock.timers.tick(1);
    await new Promise(r => setImmediate(r));
    assert.equal(concurrent, 1);

    t.mock.timers.tick(20);
    await new Promise(r => setImmediate(r));
    assert.equal(concurrent, 0);

    await Promise.all([p1, p2]);
    assert.equal(maxConcurrent, 1);

    t.mock.timers.tick(50);
    await new Promise(r => setImmediate(r));
    t.mock.timers.reset();
  });

  it('paces inter-task delay by configured drainIntervalMs', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 250, maxQueueDepth: 5 });
    const timestamps = [];

    const p1 = limiter.schedule(async () => { timestamps.push(Date.now()); });
    const p2 = limiter.schedule(async () => { timestamps.push(Date.now()); });

    await p1;
    assert.equal(timestamps.length, 1);
    const t1 = timestamps[0];

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(249);
    await new Promise(r => setImmediate(r));
    assert.equal(timestamps.length, 1);

    t.mock.timers.tick(1);
    await p2;
    assert.equal(timestamps.length, 2);
    const t2 = timestamps[1];

    assert.equal(t2 - t1, 250);

    t.mock.timers.tick(250);
    await new Promise(r => setImmediate(r));
    t.mock.timers.reset();
  });

  it('rejects with 503 on queue overflow and does not execute rejected tasks', async () => {
    const limiter = new LeakyBucketRateLimiter({
      drainIntervalMs: 1000,
      maxQueueDepth: 2,
      name: 'TestMb',
    });

    const slowTask = () => new Promise(() => {}); // never resolves to keep queue filled

    // First task shifts immediately into execution (queue becomes empty, depth=0)
    limiter.schedule(slowTask);
    assert.equal(limiter.getMetrics().currentDepth, 0);

    // Queue 2 tasks to fill queue up to maxQueueDepth = 2
    limiter.schedule(slowTask);
    assert.equal(limiter.getMetrics().currentDepth, 1);
    limiter.schedule(slowTask);
    assert.equal(limiter.getMetrics().currentDepth, 2);

    // Third queued task should be rejected immediately because queue.length >= 2
    let rejectedError = null;
    let rejectedTaskRan = false;

    try {
      await limiter.schedule(async () => {
        rejectedTaskRan = true;
      });
    } catch (err) {
      rejectedError = err;
    }

    assert.ok(rejectedError);
    assert.equal(rejectedError.status, 503);
    assert.equal(rejectedError.message, '[TestMb] Queue full (depth=2). Request rejected.');
    assert.equal(rejectedTaskRan, false);

    const m = limiter.getMetrics();
    assert.equal(m.totalScheduled, 4);
    assert.equal(m.totalRejected, 1);
    assert.equal(m.currentDepth, 2);
  });

  it('tracks metrics accurately on successful executions', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 100, maxQueueDepth: 5 });

    const p1 = limiter.schedule(async () => 'ok1');
    const p2 = limiter.schedule(async () => 'ok2');

    await p1;
    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(100);
    await p2;

    const m = limiter.getMetrics();
    assert.equal(m.totalScheduled, 2);
    assert.equal(m.totalExecuted, 2);
    assert.equal(m.totalSucceeded, 2);
    assert.equal(m.totalFailed, 0);
    assert.equal(m.totalRejected, 0);
    assert.equal(m.currentDepth, 0);
    assert.ok(typeof m.lastDrainMs === 'number');

    t.mock.timers.tick(100);
    await new Promise(r => setImmediate(r));
    t.mock.timers.reset();
  });

  it('tracks failed tasks, propagates errors, and continues draining subsequent tasks', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 100, maxQueueDepth: 5 });
    const executed = [];

    const p1 = limiter.schedule(async () => {
      executed.push(1);
      throw new Error('upstream failed');
    });

    const p2 = limiter.schedule(async () => {
      executed.push(2);
      return 'recovered';
    });

    await assert.rejects(p1, { message: 'upstream failed' });

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(100);

    const res2 = await p2;
    assert.equal(res2, 'recovered');
    assert.deepEqual(executed, [1, 2]);

    const m = limiter.getMetrics();
    assert.equal(m.totalScheduled, 2);
    assert.equal(m.totalExecuted, 2);
    assert.equal(m.totalSucceeded, 1);
    assert.equal(m.totalFailed, 1);

    t.mock.timers.tick(100);
    await new Promise(r => setImmediate(r));
    t.mock.timers.reset();
  });

  it('converts synchronous throws from fn() into rejected promises without stalling queue', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 50, maxQueueDepth: 5 });

    const p1 = limiter.schedule(() => {
      throw new TypeError('synchronous crash');
    });

    const p2 = limiter.schedule(() => 'after sync crash');

    await assert.rejects(p1, { name: 'TypeError', message: 'synchronous crash' });

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(50);

    const res2 = await p2;
    assert.equal(res2, 'after sync crash');

    const m = limiter.getMetrics();
    assert.equal(m.totalFailed, 1);
    assert.equal(m.totalSucceeded, 1);

    t.mock.timers.tick(50);
    await new Promise(r => setImmediate(r));
    t.mock.timers.reset();
  });

  it('transitions cleanly to idle and restarts on new schedule', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });

    const limiter = new LeakyBucketRateLimiter({ drainIntervalMs: 50, maxQueueDepth: 5 });

    await limiter.schedule(async () => 'first batch');
    assert.equal(limiter._running, true);

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(50);
    await new Promise(r => setImmediate(r));

    assert.equal(limiter._running, false);
    assert.equal(limiter.getMetrics().currentDepth, 0);

    const res = await limiter.schedule(async () => 'second batch');
    assert.equal(res, 'second batch');
    assert.equal(limiter._running, true);

    await new Promise(r => setImmediate(r));
    t.mock.timers.tick(50);
    await new Promise(r => setImmediate(r));

    assert.equal(limiter._running, false);
    t.mock.timers.reset();
  });
});

describe('Rate Limiter Domain Adapters', () => {
  describe('MusicBrainz Adapter', () => {
    it('exports singleton mbRateLimiter and MbRateLimiter class', () => {
      assert.ok(mbRateLimiter);
      assert.ok(MbRateLimiter);
      assert.equal(typeof mbRateLimiter.schedule, 'function');
      assert.equal(typeof mbRateLimiter.getMetrics, 'function');
      assert.ok(mbRateLimiter instanceof LeakyBucketRateLimiter);
      assert.ok(mbRateLimiter instanceof MbRateLimiter);
    });

    it('enforces MusicBrainz domain configuration (1100ms, queue depth 20)', () => {
      const m = mbRateLimiter.getMetrics();
      assert.equal(m.drainIntervalMs, 1100);
      assert.equal(m.maxQueueDepth, 20);
    });

    it('instantiates new MbRateLimiter with MusicBrainz defaults and error prefix', async () => {
      const mb = new MbRateLimiter();
      const m = mb.getMetrics();
      assert.equal(m.drainIntervalMs, 1100);
      assert.equal(m.maxQueueDepth, 20);

      // Fill queue to test error prefix
      const hang = () => new Promise(() => {});
      mb.schedule(hang); // executes immediately, depth = 0
      for (let i = 0; i < 20; i++) {
        mb.schedule(hang);
      }
      assert.equal(mb.getMetrics().currentDepth, 20);

      await assert.rejects(
        () => mb.schedule(hang),
        (err) => {
          assert.equal(err.status, 503);
          assert.match(err.message, /^\[MbRateLimiter\] Queue full/);
          return true;
        }
      );
    });
  });

  describe('ListenBrainz Adapter', () => {
    it('exports singleton lbRateLimiter and LbRateLimiter class', () => {
      assert.ok(lbRateLimiter);
      assert.ok(LbRateLimiter);
      assert.equal(typeof lbRateLimiter.schedule, 'function');
      assert.equal(typeof lbRateLimiter.getMetrics, 'function');
      assert.ok(lbRateLimiter instanceof LeakyBucketRateLimiter);
      assert.ok(lbRateLimiter instanceof LbRateLimiter);
    });

    it('enforces ListenBrainz domain configuration (350ms, queue depth 30)', () => {
      const m = lbRateLimiter.getMetrics();
      assert.equal(m.drainIntervalMs, 350);
      assert.equal(m.maxQueueDepth, 30);
    });

    it('instantiates new LbRateLimiter with ListenBrainz defaults and error prefix', async () => {
      const lb = new LbRateLimiter();
      const m = lb.getMetrics();
      assert.equal(m.drainIntervalMs, 350);
      assert.equal(m.maxQueueDepth, 30);

      // Fill queue to test error prefix
      const hang = () => new Promise(() => {});
      lb.schedule(hang); // executes immediately, depth = 0
      for (let i = 0; i < 30; i++) {
        lb.schedule(hang);
      }
      assert.equal(lb.getMetrics().currentDepth, 30);

      await assert.rejects(
        () => lb.schedule(hang),
        (err) => {
          assert.equal(err.status, 503);
          assert.match(err.message, /^\[LbRateLimiter\] Queue full/);
          return true;
        }
      );
    });
  });
});
