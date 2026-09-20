'use strict';

/**
 * mbRateLimiter.js — MusicBrainz Leaky-Bucket Rate Limiter Adapter
 *
 * MusicBrainz enforces a strict 1 request/second per IP limit.
 * Violating this results in temporary IP bans.
 *
 * This adapter configures a leaky-bucket queue that:
 *  - Serializes ALL outbound MB API calls through a single drain interval.
 *  - Maintains 1 request per DRAIN_INTERVAL_MS (1.1s — 10% safety margin over
 *    MB's 1 req/sec limit).
 *  - Caps the queue at MAX_QUEUE_DEPTH (20) to reject requests when the system
 *    is under sustained load — fail fast (503) is better than indefinite queuing.
 *  - Provides a metrics snapshot for the /api/v1/identity/store/snapshot endpoint
 *    and healthChecker policy inspection.
 *
 * Singleton requirement:
 *   One rate limiter instance for the entire process. All MusicBrainz calls
 *   must go through this instance to ensure the external IP rate ceiling is honored.
 *
 * Usage:
 *   const { mbRateLimiter } = require('./mbRateLimiter');
 *   const result = await mbRateLimiter.schedule(() => getJson(MB_BASE_URL, path));
 */

const { LeakyBucketRateLimiter } = require('../utils/LeakyBucketRateLimiter');

const DRAIN_INTERVAL_MS = 1100; // 1.1s — 10% margin over MB's 1 req/sec limit
const MAX_QUEUE_DEPTH   = 20;   // Reject new requests beyond this depth

class MbRateLimiter extends LeakyBucketRateLimiter {
  constructor(options = {}) {
    super({
      drainIntervalMs: options.drainIntervalMs ?? DRAIN_INTERVAL_MS,
      maxQueueDepth:   options.maxQueueDepth ?? MAX_QUEUE_DEPTH,
      name:            options.name ?? 'MbRateLimiter',
    });
  }
}

// Singleton — one rate limiter for the entire process.
// All MusicBrainz calls must go through this instance.
const mbRateLimiter = new MbRateLimiter();

module.exports = {
  mbRateLimiter,
  MbRateLimiter,
};
