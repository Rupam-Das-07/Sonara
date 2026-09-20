'use strict';

/**
 * BoundedCache.js — Independently owned copy for sonara-backend.
 *
 * Lightweight in-memory bounded cache with FIFO eviction and TTL support.
 * Originally from the Sonara Web backend utils/. Independently owned here
 * and must not import from the Web project at runtime.
 *
 * Future improvements to sonara-backend's cache should be made here.
 */
class BoundedCache {
  /**
   * @param {Object} options
   * @param {number} options.maxSize - Max items before oldest is evicted.
   * @param {number} [options.ttlMs] - Optional TTL in ms. Expired items are lazily evicted.
   */
  constructor({ maxSize = 1000, ttlMs = null } = {}) {
    this.maxSize = maxSize;
    this.ttlMs = ttlMs;
    this.cache = new Map();
  }

  get(key) {
    const item = this.cache.get(key);
    if (!item) return null;
    if (this.ttlMs && Date.now() - item.ts > this.ttlMs) {
      this.cache.delete(key);
      return null;
    }
    return item.data;
  }

  set(key, data) {
    if (this.cache.size >= this.maxSize && !this.cache.has(key)) {
      this.cache.delete(this.cache.keys().next().value);
    }
    this.cache.set(key, { data, ts: Date.now() });
  }

  has(key) { return this.get(key) !== null; }

  delete(key) { return this.cache.delete(key); }

  clear() { this.cache.clear(); }

  get size() { return this.cache.size; }
}

module.exports = BoundedCache;
