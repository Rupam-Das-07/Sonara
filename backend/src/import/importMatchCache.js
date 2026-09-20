'use strict';

/**
 * importMatchCache.js — Dedicated in-memory cache for import match decisions.
 *
 * Memoizes tiered match outcomes (confident, review, unmatched) for identical
 * track metadata across chunks and import sessions.
 *
 * Isolated from playback, identity, and artwork caches.
 */

const BoundedCache = require('../utils/BoundedCache');
const { normalize } = require('../identity/IdentityMatcher');

const DEFAULT_MAX_SIZE = 2000;
const DEFAULT_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

class ImportMatchCache {
  constructor({ maxSize = DEFAULT_MAX_SIZE, ttlMs = DEFAULT_TTL_MS } = {}) {
    this._cache = new BoundedCache({ maxSize, ttlMs });
    this.hits = 0;
    this.misses = 0;
  }

  /**
   * Generates a deterministic cache key for a track.
   *
   * @param {string} title
   * @param {string} artist
   * @param {number|null} durationMs
   * @returns {string}
   */
  generateKey(title, artist, durationMs) {
    const normTitle = normalize(title || '');
    const normArtist = normalize(artist || '');
    // 5-second bucket to tolerate minor duration variances across services
    const durationBucket = durationMs && durationMs > 0
      ? Math.round(durationMs / 5000) * 5
      : 0;
    return `${normTitle}::${normArtist}::${durationBucket}`;
  }

  get(title, artist, durationMs) {
    const key = this.generateKey(title, artist, durationMs);
    const cached = this._cache.get(key);
    if (cached) {
      this.hits++;
      return cached;
    }
    this.misses++;
    return null;
  }

  set(title, artist, durationMs, matchDecision) {
    const key = this.generateKey(title, artist, durationMs);
    this._cache.set(key, matchDecision);
  }

  clear() {
    this._cache.clear();
    this.hits = 0;
    this.misses = 0;
  }

  get size() {
    return this._cache.size;
  }

  get hitRate() {
    const total = this.hits + this.misses;
    return total > 0 ? ((this.hits / total) * 100).toFixed(1) : '0.0';
  }
}

// Global shared singleton for the import subsystem
const importMatchCache = new ImportMatchCache();

module.exports = { ImportMatchCache, importMatchCache };
