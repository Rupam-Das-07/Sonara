'use strict';

/**
 * IdentityStore.js — Backend Canonical Identity Store
 *
 * Persists canonical videoId → MBID mappings as a JSON file on disk.
 * Shared across all users — one user's successful resolution benefits everyone.
 *
 * Architecture:
 *   In-memory Map (primary, instant lookup)
 *     ↓ backed by
 *   data/identity_store.json (durable, survives server restart)
 *
 * Design follows the established pattern in:
 *   features/artist/artistArtworkService.js (artist_cache.json, Map + periodic flush)
 *   features/recommendation/affinityEngine.js (affinity.json, Map + dirty-flag flush)
 *
 * Entry shape:
 * {
 *   videoId:       string,
 *   mbid:          string | null,   — null = confirmed no-match (prevents re-lookup)
 *   confidence:    number,
 *   resolvedAt:    ISO timestamp,
 *   matchedFields: Object,
 *   source:        'musicbrainz'
 * }
 */

const fs   = require('fs').promises;
const path = require('path');

const STORE_PATH   = path.join(__dirname, '../../data/identity_store.json');
const FLUSH_MS     = 60_000; // Flush to disk every 60 seconds (matches affinityEngine pattern)

class IdentityStore {
  constructor() {
    this._store       = new Map();  // videoId → entry
    this._reverseIdx  = new Map();  // mbid    → videoId (secondary index for TrackResolver)
    this._dirty       = false;
    this._flushTimer  = null;
    this._metrics     = { hits: 0, misses: 0, writes: 0, invalidations: 0 };
    this._ready       = this._load();
  }

  // ─── Startup ──────────────────────────────────────────────────────────────

  /**
   * Loads the JSON store from disk into the in-memory Map.
   * Called once at construction. Server routes should await `store.ready()`.
   */
  async _load() {
    try {
      const raw = await fs.readFile(STORE_PATH, 'utf8');
      const entries = JSON.parse(raw);
      if (typeof entries === 'object' && entries !== null) {
        for (const [videoId, entry] of Object.entries(entries)) {
          this._store.set(videoId, entry);
          // Rebuild reverse index from persisted data
          if (entry.mbid) this._reverseIdx.set(entry.mbid.toLowerCase(), videoId);
        }
        console.log(`[IdentityStore] Loaded ${this._store.size} identity mappings from disk.`);
      }
    } catch (err) {
      if (err.code === 'ENOENT') {
        console.log('[IdentityStore] No existing identity_store.json — starting fresh.');
      } else {
        console.error('[IdentityStore] Failed to load from disk:', err.message);
      }
    }
    // Start periodic flush
    this._scheduleFlush();
  }

  /**
   * Resolves when the store has finished loading from disk.
   * @returns {Promise<void>}
   */
  ready() {
    return this._ready;
  }

  // ─── Public API ───────────────────────────────────────────────────────────

  /**
   * Looks up a videoId in the store.
   * @param {string} videoId
   * @returns {Object | null}  entry or null if not found
   */
  get(videoId) {
    const entry = this._store.get(videoId) || null;
    if (entry) this._metrics.hits++;
    else       this._metrics.misses++;
    return entry;
  }

  /**
   * Returns true if the store has any entry (including confirmed null) for this videoId.
   * @param {string} videoId
   */
  has(videoId) {
    return this._store.has(videoId);
  }

  /**
   * Stores a resolved identity mapping.
   * @param {string} videoId
   * @param {{ resolved, mbid, confidence, matchedFields, rejectedCandidates }} resolutionResult
   */
  set(videoId, resolutionResult) {
    if (!videoId) return;

    const entry = {
      videoId,
      mbid:          resolutionResult.mbid,
      confidence:    resolutionResult.confidence,
      resolved:      resolutionResult.resolved,
      resolvedAt:    new Date().toISOString(),
      matchedFields: resolutionResult.matchedFields || {},
      source:        'musicbrainz',
    };

    this._store.set(videoId, entry);
    this._metrics.writes++;
    this._dirty = true;

    // Keep reverse index in sync
    if (entry.mbid) {
      this._reverseIdx.set(entry.mbid.toLowerCase(), videoId);
    }

    console.log(`[IdentityStore] Stored: videoId=${videoId} → mbid=${entry.mbid} (confidence=${entry.confidence})`);
  }

  /**
   * Removes a mapping from the store, forcing re-resolution on next lookup.
   * Used for manual correction of incorrect MBID mappings.
   * @param {string} videoId
   * @returns {boolean} true if an entry was removed
   */
  invalidate(videoId) {
    if (!videoId) return false;
    const entry = this._store.get(videoId);
    const existed = this._store.delete(videoId);
    if (existed) {
      // Keep reverse index in sync
      if (entry && entry.mbid) this._reverseIdx.delete(entry.mbid.toLowerCase());
      this._metrics.invalidations++;
      this._dirty = true;
      console.log(`[IdentityStore] Invalidated: videoId=${videoId}`);
    }
    return existed;
  }

  /**
   * Reverse lookup: given an MBID, returns the videoId previously mapped to it.
   * Used by TrackResolver to check if a candidate MBID already has a known playable video.
   *
   * @param {string} mbid  — MusicBrainz Recording MBID
   * @returns {string | null}  videoId or null if no reverse mapping exists
   */
  reverseGet(mbid) {
    if (!mbid) return null;
    return this._reverseIdx.get(mbid.toLowerCase()) || null;
  }

  /**
   * Returns true if the given MBID has a known reverse mapping to a videoId.
   * @param {string} mbid
   * @returns {boolean}
   */
  reverseHas(mbid) {
    if (!mbid) return false;
    return this._reverseIdx.has(mbid.toLowerCase());
  }

  /**
   * Returns a summary snapshot for the debug endpoint.
   */
  getSnapshot() {
    const entries = [...this._store.values()];
    const resolved = entries.filter(e => e.resolved && e.mbid).length;
    const noMatch  = entries.filter(e => !e.mbid).length;

    return {
      totalEntries:       this._store.size,
      resolvedCount:      resolved,
      noMatchCount:       noMatch,
      reverseIndexSize:   this._reverseIdx.size,
      metrics: { ...this._metrics, hitRate: this._metrics.hits + this._metrics.misses > 0
        ? ((this._metrics.hits / (this._metrics.hits + this._metrics.misses)) * 100).toFixed(1) + '%'
        : 'N/A' },
    };
  }

  /**
   * Returns all entries (for the admin debug endpoint).
   */
  getAllEntries() {
    return [...this._store.values()];
  }

  // ─── Persistence ──────────────────────────────────────────────────────────

  _scheduleFlush() {
    this._flushTimer = setInterval(() => this._flush(), FLUSH_MS);
    // Allow process to exit even if timer is pending
    if (this._flushTimer.unref) this._flushTimer.unref();
  }

  async _flush() {
    if (!this._dirty) return;
    this._dirty = false;

    const payload = {};
    for (const [videoId, entry] of this._store.entries()) {
      payload[videoId] = entry;
    }

    try {
      // Atomic write: write to .tmp then rename (matches artistArtworkService pattern)
      const tmpPath = STORE_PATH + '.tmp';
      await fs.writeFile(tmpPath, JSON.stringify(payload, null, 2), 'utf8');
      await fs.rename(tmpPath, STORE_PATH);
      console.log(`[IdentityStore] Flushed ${this._store.size} entries to disk.`);
    } catch (err) {
      console.error('[IdentityStore] Flush failed:', err.message);
      this._dirty = true; // Retry next interval
    }
  }

  /**
   * Force an immediate flush (called on server shutdown if needed).
   */
  async forceFlush() {
    this._dirty = true;
    await this._flush();
  }
}

// Singleton — one store per process, shared across all routes
const identityStore = new IdentityStore();

module.exports = { identityStore };
