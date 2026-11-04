'use strict';

/**
 * ytmusicProvider.js — YTMusic search provider for sonara-backend.
 *
 * Communicates with the shared Python YTMusic service (Port 5000) via native
 * Node.js fetch(). No axios dependency.
 *
 * Python YTMusic service is SHARED INFRASTRUCTURE:
 *   - It runs independently on its own port.
 *   - This file communicates with it over HTTP.
 *   - Its address MUST NOT be exposed to Android clients.
 *
 * Features:
 *   - 15-minute search result cache (BoundedCache)
 *   - In-flight stampede protection
 *   - Search metrics tracking
 */

const config = require('../config/env');
const BoundedCache = require('../utils/BoundedCache');
const { createTrack } = require('./trackModel');
const logger = require('../utils/logger');

const searchCache = new BoundedCache({ maxSize: 1000, ttlMs: 15 * 60 * 1000 });
const _inFlightSearches = new Map();

function sourceFailureResult() {
  const result = [];
  Object.defineProperty(result, 'sourceFailed', { value: true, enumerable: false });
  return result;
}

const searchMetrics = {
  cacheHits:       0,
  cacheMisses:     0,
  upstreamSearches:0,
  totalTimeMs:     0,
  lastTimeMs:      0,
  get inFlight()   { return _inFlightSearches.size; },
  get cacheSize()  { return searchCache.size; },
  get hitRate()    {
    const total = this.cacheHits + this.cacheMisses;
    return total > 0 ? ((this.cacheHits / total) * 100).toFixed(1) : '0.0';
  },
};

function normalizeQuery(q) {
  if (!q) return '';
  return q.trim().toLowerCase().replace(/\s+/g, ' ');
}

/**
 * Search the Python YTMusic service for songs.
 *
 * @param {string} query
 * @returns {Promise<object[]>} Array of CanonicalTrack objects
 */
async function searchSongs(query) {
  const normalized = normalizeQuery(query);
  if (!normalized) return [];

  const cacheKey = `search:${normalized}`;

  const cached = searchCache.get(cacheKey);
  if (cached) {
    searchMetrics.cacheHits++;
    return cached;
  }

  // Stampede protection
  if (_inFlightSearches.has(normalized)) {
    try {
      searchMetrics.cacheHits++;
      return await _inFlightSearches.get(normalized);
    } catch {
      // In-flight request failed — fall through to retry
    }
  }

  searchMetrics.cacheMisses++;

  const fetchPromise = (async () => {
    const t0 = Date.now();
    try {
      const url = `${config.pythonYtmusicUrl}/api/search?q=${encodeURIComponent(query)}`;
      const res = await fetch(url, { signal: AbortSignal.timeout(config.ytmusicTimeoutMs) });

      if (!res.ok) {
        logger.warn('[YTMUSIC] Python search returned non-OK', { status: res.status });
        return [];
      }

      const data = await res.json();
      const tracks = Array.isArray(data) ? data.map(createTrack).filter(Boolean) : [];

      searchMetrics.upstreamSearches++;
      searchMetrics.lastTimeMs = Date.now() - t0;
      searchMetrics.totalTimeMs += searchMetrics.lastTimeMs;

      logger.debug('[YTMUSIC] Search completed', {
        query: normalized,
        results: tracks.length,
        latencyMs: searchMetrics.lastTimeMs,
      });

      searchCache.set(cacheKey, tracks);
      return tracks;
    } catch (err) {
      logger.warn('[YTMUSIC] Search failed', { query: normalized, error: err.message });
      return [];
    } finally {
      _inFlightSearches.delete(normalized);
    }
  })();

  _inFlightSearches.set(normalized, fetchPromise);
  return fetchPromise;
}

// ─── Recommendation / detail endpoints ───────────────────────────────────────
//
// recommendationService.js calls ytmusic.getWatchPlaylist, ytmusic.getRelatedSongs
// and ytmusic.getSongDetails, but this module previously exported only
// { searchSongs, searchMetrics }. All three were therefore `undefined`, and since
// the recommendation engine wraps its calls in try/catch, the resulting TypeError
// surfaced as HTTP 200 with `{ success: false, tracks: [] }` instead of an error.
// The Python service already implements every endpoint needed; only the Node-side
// bindings were missing.
//
// searchSongs above keeps its own bespoke cache/stampede path deliberately: it is
// the live production search path, and this repair should not risk changing it.

const _detailCache = new BoundedCache({ maxSize: 500, ttlMs: 30 * 60 * 1000 }); // 30 min
const _inFlightDetails = new Map();

/**
 * Shared GET against the Python YTMusic service with caching and in-flight
 * stampede protection.
 *
 * @param {string} cacheKey
 * @param {string} path - Path on the Python service, beginning with '/'.
 * @param {string} label - For logging only.
 * @param {any} notFoundValue - Returned when Python answers 404, which these
 *        endpoints use to mean "nothing available" rather than "failure".
 * @returns {Promise<any>} Raw parsed JSON, or notFoundValue.
 */
function _detailGet(cacheKey, path, label, notFoundValue) {
  const cached = _detailCache.get(cacheKey);
  if (cached !== undefined && cached !== null) return Promise.resolve(cached);

  if (_inFlightDetails.has(cacheKey)) {
    return _inFlightDetails.get(cacheKey);
  }

  const promise = (async () => {
    const t0 = Date.now();
    try {
      const res = await fetch(`${config.pythonYtmusicUrl}${path}`, {
        signal: AbortSignal.timeout(config.ytmusicTimeoutMs),
        headers: { Accept: 'application/json' },
      });

      if (res.status === 404) {
        logger.debug(`[YTMUSIC] ${label} returned 404 (treated as empty)`, { path });
        return notFoundValue;
      }

      if (!res.ok) {
        logger.warn(`[YTMUSIC] ${label} returned non-OK`, { status: res.status });
        return notFoundValue;
      }

      const data = await res.json();
      logger.debug(`[YTMUSIC] ${label} completed`, { latencyMs: Date.now() - t0 });
      _detailCache.set(cacheKey, data);
      return data;
    } catch (err) {
      logger.warn(`[YTMUSIC] ${label} failed`, { error: err.message });
      return notFoundValue;
    } finally {
      _inFlightDetails.delete(cacheKey);
    }
  })();

  _inFlightDetails.set(cacheKey, promise);
  return promise;
}

/**
 * Radio continuation for a seed track.
 * Python: GET /api/watch-playlist/<videoId> → { playlistId, tracks: [...] }
 *
 * @param {string} videoId
 * @returns {Promise<object[]>} CanonicalTrack[] (empty if unavailable)
 */
async function getWatchPlaylist(videoId) {
  const data = await _detailGet(
    `watch:${videoId}`,
    `/api/watch-playlist/${encodeURIComponent(videoId)}`,
    'getWatchPlaylist',
    null
  );
  const tracks = Array.isArray(data?.tracks) ? data.tracks : [];
  return tracks.map(createTrack).filter(Boolean);
}

/**
 * YouTube Music "related songs" for a seed track.
 * Python: GET /api/related/<videoId> → [ ... ] (bare array)
 *
 * @param {string} videoId
 * @returns {Promise<object[]>} CanonicalTrack[] (empty if unavailable)
 */
async function getRelatedSongs(videoId) {
  const data = await _detailGet(
    `related:${videoId}`,
    `/api/related/${encodeURIComponent(videoId)}`,
    'getRelatedSongs',
    null
  );
  const items = Array.isArray(data) ? data : [];
  return items.map(createTrack).filter(Boolean);
}

/**
 * Detailed metadata for a single track, used to seed identity resolution.
 * Python: GET /api/song/<videoId> → normalized track object
 *
 * @param {string} videoId
 * @returns {Promise<object|null>} CanonicalTrack, or null if unavailable.
 */
async function getSongDetails(videoId) {
  const data = await _detailGet(
    `song:${videoId}`,
    `/api/song/${encodeURIComponent(videoId)}`,
    'getSongDetails',
    null
  );
  if (!data || !(data.videoId || data.id)) return null;
  return createTrack(data);
}

/**
 * Fetches tracks from a YouTube Music chart playlist via the Python service.
 *
 * @param {object} [options={}]
 * @param {string} [options.country='IN']
 * @param {string} [options.language='Hindi']
 * @param {string} [options.playlistId]
 * @param {number} [options.limit=100]
 * @returns {Promise<object[]>} Array of CanonicalTrack objects
 */
async function getChartTracks(options = {}) {
  const country = (options.country || 'IN').toUpperCase();
  const language = options.language || 'Hindi';
  const playlistId = options.playlistId || '';
  const limit = options.limit || 100;

  const queryParams = new URLSearchParams({
    country,
    language,
    limit: String(limit),
  });
  if (playlistId) {
    queryParams.set('playlistId', playlistId);
  }

  const url = `${config.pythonYtmusicUrl}/api/charts/tracks?${queryParams.toString()}`;
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(config.ytmusicTimeoutMs || 10000),
      headers: { Accept: 'application/json' },
    });

    if (!res.ok) {
      logger.warn('[YTMUSIC] Chart tracks returned non-OK', { status: res.status });
      return [];
    }

    const data = await res.json();
    const rawTracks = Array.isArray(data?.tracks) ? data.tracks : [];
    return rawTracks.map(createTrack).filter(Boolean);
  } catch (err) {
    logger.warn('[YTMUSIC] getChartTracks failed', { error: err.message });
    return [];
  }
}

/**
 * Fetches YouTube Music's native New Releases shelf.
 *
 * Returns album CONTAINERS, not tracks — the shelf exposes no track-level videoId,
 * so each container must be resolved through getAlbumDetail() to obtain playable
 * tracks. Freshness here is upstream's own editorial shelf membership plus the
 * container type; it is NOT a verified release date.
 *
 * @returns {Promise<object[]>} Raw container objects ([] on any failure)
 */
async function getNewReleaseShelf() {
  const url = `${config.pythonYtmusicUrl}/api/explore/new-releases`;
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(config.ytmusicTimeoutMs || 10000),
      headers: { Accept: 'application/json' },
    });

    if (!res.ok) {
      logger.warn('[YTMUSIC] New releases shelf returned non-OK', { status: res.status });
      return sourceFailureResult();
    }

    const data = await res.json();
    return Array.isArray(data?.containers) ? data.containers : sourceFailureResult();
  } catch (err) {
    logger.warn('[YTMUSIC] getNewReleaseShelf failed', { error: err.message });
    return sourceFailureResult();
  }
}

/**
 * Fetches one album's detail: album-level title/year/artwork plus its raw tracks.
 *
 * Album tracks arrive with `thumbnails: null` and carry no year of their own, which
 * is why the caller inherits artwork and year from the album level.
 *
 * @param {string} browseId - Album browseId (upstream form: "MPREb_...")
 * @returns {Promise<object|null>} Album detail, or null on any failure
 */
async function getAlbumDetail(browseId) {
  if (!browseId || typeof browseId !== 'string') return null;

  const url = `${config.pythonYtmusicUrl}/api/album/${encodeURIComponent(browseId)}`;
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(config.ytmusicTimeoutMs || 10000),
      headers: { Accept: 'application/json' },
    });

    if (!res.ok) {
      logger.warn('[YTMUSIC] Album detail returned non-OK', { status: res.status, browseId });
      return null;
    }

    const data = await res.json();
    return data && typeof data === 'object' ? data : null;
  } catch (err) {
    logger.warn('[YTMUSIC] getAlbumDetail failed', { browseId, error: err.message });
    return null;
  }
}

module.exports = {
  searchSongs,
  searchMetrics,
  getWatchPlaylist,
  getRelatedSongs,
  getSongDetails,
  getChartTracks,
  getNewReleaseShelf,
  getAlbumDetail,
};
