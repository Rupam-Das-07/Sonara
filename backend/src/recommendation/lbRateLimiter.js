'use strict';

/**
 * lbRateLimiter.js — ListenBrainz Leaky-Bucket Rate Limiter Adapter
 *
 * ListenBrainz is more permissive than MusicBrainz but still requires
 * responsible usage. This adapter configures a limiter that keeps Sonara
 * comfortably within LB's fair-use expectations:
 *
 *  - 3 requests/second maximum (conservative — LB allows more but we stay safe)
 *  - Queue depth capped at MAX_QUEUE_DEPTH (30) before fail-fast (503) kicks in
 *  - Drain interval is set to 350ms to provide headroom (~3 req/sec with margin)
 *
 * Note on LB API:
 *   LB similarity endpoints are slow (200-800ms). The concurrency cap of
 *   3 req/s means real-world throughput is naturally bounded by response time.
 *
 * Singleton requirement:
 *   All ListenBrainz calls across the entire process must share this instance.
 *
 * Usage:
 *   const { lbRateLimiter } = require('./lbRateLimiter');
 *   const result = await lbRateLimiter.schedule(() => getJson(LB_BASE_URL, path));
 */

const { LeakyBucketRateLimiter } = require('../utils/LeakyBucketRateLimiter');

const DRAIN_INTERVAL_MS = 350; // ~3 req/sec with margin
const MAX_QUEUE_DEPTH   = 30;

class LbRateLimiter extends LeakyBucketRateLimiter {
  constructor(options = {}) {
    super({
      drainIntervalMs: options.drainIntervalMs ?? DRAIN_INTERVAL_MS,
      maxQueueDepth:   options.maxQueueDepth ?? MAX_QUEUE_DEPTH,
      name:            options.name ?? 'LbRateLimiter',
    });
  }
}

// Singleton — all LB calls share a single rate limiter instance.
const lbRateLimiter = new LbRateLimiter();

module.exports = {
  lbRateLimiter,
  LbRateLimiter,
};
