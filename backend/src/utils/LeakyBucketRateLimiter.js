'use strict';

/**
 * LeakyBucketRateLimiter — Generic single-flight serializing rate limiter.
 *
 * Implements a leaky-bucket queue that:
 *  - Serializes execution of asynchronous tasks through a single drain interval.
 *  - Paces execution by enforcing a configurable delay (drainIntervalMs) between
 *    the completion of one task and the start of the next (chained setTimeout).
 *  - Caps queued tasks at maxQueueDepth, immediately failing fast with an HTTP 503
 *    status error when the limit is exceeded.
 *  - Safely handles synchronous and asynchronous task exceptions without stalling
 *    the queue or leaking memory.
 *  - Provides a point-in-time diagnostic metrics snapshot.
 */
class LeakyBucketRateLimiter {
  /**
   * @param {object} options
   * @param {number} options.drainIntervalMs — Pacing interval (ms) between task completions and next task dispatch.
   * @param {number} options.maxQueueDepth   — Maximum number of queued tasks before 503 rejection.
   * @param {string} [options.name]          — Label used in error prefix (default: 'RateLimiter').
   */
  constructor({ drainIntervalMs, maxQueueDepth, name = 'RateLimiter' } = {}) {
    if (typeof drainIntervalMs !== 'number' || drainIntervalMs <= 0) {
      throw new TypeError('drainIntervalMs must be a positive number');
    }
    if (typeof maxQueueDepth !== 'number' || maxQueueDepth <= 0) {
      throw new TypeError('maxQueueDepth must be a positive number');
    }

    this._drainIntervalMs = drainIntervalMs;
    this._maxQueueDepth   = maxQueueDepth;
    this._name            = name;

    this._queue   = [];
    this._running = false;
    this._metrics = {
      totalScheduled: 0,
      totalExecuted:  0,
      totalRejected:  0,
      totalSucceeded: 0,
      totalFailed:    0,
      currentDepth:   0,
      lastDrainMs:    null,
    };
  }

  /**
   * Schedules an asynchronous task function for serialized execution.
   *
   * @param {Function} fn — Async function returning a Promise or value.
   * @returns {Promise<any>} Resolves/rejects with the result of fn().
   * @throws {Error} If queue depth exceeds maxQueueDepth (with { status: 503 }).
   */
  schedule(fn) {
    this._metrics.totalScheduled++;

    if (this._queue.length >= this._maxQueueDepth) {
      this._metrics.totalRejected++;
      return Promise.reject(
        Object.assign(
          new Error(`[${this._name}] Queue full (depth=${this._maxQueueDepth}). Request rejected.`),
          { status: 503 }
        )
      );
    }

    return new Promise((resolve, reject) => {
      this._queue.push({ fn, resolve, reject });
      this._metrics.currentDepth = this._queue.length;
      this._startDrain();
    });
  }

  /**
   * Starts the drain sequence if not already running.
   * Guarded by this._running flag.
   */
  _startDrain() {
    if (this._running) return;
    this._running = true;
    this._drainNext();
  }

  /**
   * Dispatches the next task in the queue and schedules subsequent drain
   * via setTimeout in .finally().
   */
  _drainNext() {
    if (this._queue.length === 0) {
      this._running = false;
      this._metrics.currentDepth = 0;
      return;
    }

    const { fn, resolve, reject } = this._queue.shift();
    this._metrics.currentDepth = this._queue.length;
    this._metrics.totalExecuted++;
    this._metrics.lastDrainMs = Date.now();

    // Wrapping fn() in Promise.resolve().then() catches synchronous exceptions
    // and converts them to rejected promises without breaking the drain loop.
    Promise.resolve()
      .then(() => fn())
      .then(result => {
        this._metrics.totalSucceeded++;
        resolve(result);
      })
      .catch(err => {
        this._metrics.totalFailed++;
        reject(err);
      })
      .finally(() => {
        setTimeout(() => this._drainNext(), this._drainIntervalMs);
      });
  }

  /**
   * Returns a point-in-time diagnostic metrics snapshot.
   *
   * @returns {object}
   */
  getMetrics() {
    return {
      drainIntervalMs: this._drainIntervalMs,
      maxQueueDepth:   this._maxQueueDepth,
      currentDepth:    this._metrics.currentDepth,
      totalScheduled:  this._metrics.totalScheduled,
      totalExecuted:   this._metrics.totalExecuted,
      totalRejected:   this._metrics.totalRejected,
      totalSucceeded:  this._metrics.totalSucceeded,
      totalFailed:     this._metrics.totalFailed,
      lastDrainMs:     this._metrics.lastDrainMs,
    };
  }
}

module.exports = { LeakyBucketRateLimiter };
