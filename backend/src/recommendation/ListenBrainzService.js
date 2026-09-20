'use strict';

/**
 * ListenBrainzService.js — LB API Client (Phase 1, updated for correct endpoints)
 *
 * The ONLY module in the backend that communicates directly with ListenBrainz.
 * All outbound calls are serialized through lbRateLimiter (~3 req/sec max).
 * All responses are normalized through ListenBrainzNormalizer before caching.
 *
 * Endpoints used (verified against production LB API):
 *
 *  - Similar Recordings via LB Radio:
 *    GET /1/lb-radio/artist/:artistMbid
 *    Returns recordings from artists similar to the given artist MBID.
 *    This is the primary production-ready similarity endpoint available
 *    without user authentication.
 *
 *  - Recording metadata lookup:
 *    GET /1/metadata/recording/?recording_mbids=:mbid
 *    Used to enrich raw candidate MBIDs with title/artist info for
 *    TrackResolver scoring.
 *
 * NOTE ON LB SIMILARITY API:
 *   The TROI/Dataset Hoster similar-recordings endpoint
 *   (/1/mbid/recording/:mbid/similar-recordings) is not part of the
 *   stable production API and returns 404. The LB Radio endpoint
 *   (/1/lb-radio/artist/:artistMbid) is the verified, stable alternative
 *   that returns recording candidates from similar artists with community
 *   listen counts. This is confirmed production-ready as of 2024.
 *
 * Caching strategy:
 *  - In-memory Map with 48-hour TTL.
 *  - Collaborative similarity data is slow-changing; 48h is appropriate.
 *  - Key: `radio:artist:{artistMbid}` or `meta:{mbid}`.
 *  - Max 2000 entries before LRU eviction.
 */


const BoundedCache = require('../utils/BoundedCache');
const { lbRateLimiter } = require('./lbRateLimiter');
const { getJson } = require('../utils/httpJson');
const {
  normalizeLbRadioResponse,
  normalizeRecordingMetadata,
  emptyPayload,
} = require('./ListenBrainzNormalizer');

const LB_BASE_URL    = 'https://api.listenbrainz.org';
const LB_USER_AGENT  = 'Sonara/1.0 (music streaming application; contact@sonara.app)';
const LB_TIMEOUT_MS  = 12000; // 12s — LB radio can be slow to generate
const CACHE_TTL_MS   = 48 * 60 * 60 * 1000; // 48 hours
const CACHE_MAX_SIZE = 2000;

// LB Radio parameters — tuned for Sonara's recommendation use case:
//  - max_similar_artists:     5  → explore 5 similar artists per query
//  - max_recordings_per_artist: 5 → 5 tracks per artist = max 25 candidates
//  - pop_begin/pop_end:       0–100 → full popularity spectrum (no elitism)
//  - mode:                  'easy' → broader similarity matches
const LB_RADIO_PARAMS = {
  mode:                     'easy',
  count:                    25,
  max_similar_artists:      5,
  max_recordings_per_artist: 5,
  pop_begin:                0,
  pop_end:                  100,
};

// In-process response cache
const _cache = new BoundedCache({
  maxSize: CACHE_MAX_SIZE,
  ttlMs: CACHE_TTL_MS,
});

// ─── Internal Helpers ─────────────────────────────────────────────────────────

/**
 * Core HTTP helper — all LB requests go through this, serialized by lbRateLimiter.
 *
 * Previously called `axios.get` without requiring axios, which is not a declared
 * dependency; every call threw `ReferenceError: axios is not defined`. Because
 * the recommendation engine wraps these calls in try/catch, the failure appeared
 * as an empty result rather than an error. Now uses native fetch via getJson.
 */
function _lbGet(path, params = {}) {
  return lbRateLimiter.schedule(() =>
    getJson(LB_BASE_URL, path, {
      params,
      headers: {
        'User-Agent': LB_USER_AGENT,
        'Accept':     'application/json',
      },
      timeoutMs: LB_TIMEOUT_MS,
    })
  );
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Fetches similar recording candidates using the LB Radio artist endpoint.
 * Returns recording MBIDs from artists similar to the given artist MBID.
 *
 * This is the production-verified endpoint for MBID-based similarity without
 * requiring a ListenBrainz user account.
 *
 * On any error, returns an empty normalized payload for graceful degradation.
 *
 * @param {string} sourceMbid   — MusicBrainz Artist MBID
 * @returns {Promise<NormalizedLbPayload>}
 */
async function getSimilarRecordingsForArtist(sourceMbid) {
  if (!sourceMbid) return emptyPayload('', 'artist');

  const cacheKey = `radio:artist:${sourceMbid.toLowerCase()}`;
  const cached   = _cache.get(cacheKey);
  if (cached) {
    console.log(`[ListenBrainzService] Cache hit: similar recordings for artist ${sourceMbid}`);
    return cached;
  }

  try {
    console.log(`[ListenBrainzService] Fetching LB Radio candidates for artist MBID: ${sourceMbid}`);
    const raw        = await _lbGet(`/1/lb-radio/artist/${encodeURIComponent(sourceMbid)}`, LB_RADIO_PARAMS);
    const normalized = normalizeLbRadioResponse(sourceMbid, raw);
    _cache.set(cacheKey, normalized);
    console.log(`[ListenBrainzService] LB Radio for ${sourceMbid}: ${normalized.candidates.length} candidates`);
    return normalized;
  } catch (err) {
    const status = err?.response?.status || err?.status || 'network';
    console.warn(`[ListenBrainzService] getSimilarRecordingsForArtist failed for ${sourceMbid} (${status}): ${err.message}`);
    return emptyPayload(sourceMbid, 'artist');
  }
}

/**
 * Fetches recording metadata from LB's metadata cache for a list of MBIDs.
 * Used by TrackResolver to enrich candidate MBIDs with title/artist name
 * without hitting MusicBrainz.
 *
 * LB maintains an internal MB metadata cache that's fast and respects rate limits.
 *
 * @param {string[]} mbids  — Array of Recording MBIDs (max 50)
 * @returns {Promise<Object>} — Map of mbid → { title, artistName }
 */
async function getRecordingMetadataBatch(mbids) {
  if (!mbids || mbids.length === 0) return {};

  const uniqueMbids = [...new Set(mbids.filter(Boolean))].slice(0, 50);
  const cacheKey    = `meta:batch:${uniqueMbids.slice(0, 3).join(',')}:${uniqueMbids.length}`;
  const cached      = _cache.get(cacheKey);
  if (cached) return cached;

  try {
    const raw = await _lbGet('/1/metadata/recording/', {
      recording_mbids: uniqueMbids.join(','),
      inc: 'artist',
    });
    const result = normalizeRecordingMetadata(raw);
    _cache.set(cacheKey, result);
    return result;
  } catch (err) {
    console.warn('[ListenBrainzService] getRecordingMetadataBatch failed:', err.message);
    return {};
  }
}

/**
 * Fetches similar recording candidates. Encapsulates endpoint selection logic.
 * Currently uses the LB Radio (artist-based) endpoint as it is the only stable
 * unauthenticated similarity endpoint available in production.
 *
 * @param {{ recordingMbid?: string, artistMbid?: string }} options
 * @returns {Promise<NormalizedLbPayload>}
 */
async function getSimilarRecordings({ recordingMbid, artistMbid }) {
  if (artistMbid) {
    return getSimilarRecordingsForArtist(artistMbid);
  }
  return emptyPayload(recordingMbid || '', 'recording');
}

/**
 * Returns a diagnostic snapshot.
 */
function getCacheMetrics() {
  return {
    cacheSize:      _cache.size,
    cacheTtlHours:  CACHE_TTL_MS / (60 * 60 * 1000),
    lbRadioParams:  LB_RADIO_PARAMS,
    rateLimiter:    lbRateLimiter.getMetrics(),
  };
}

module.exports = {
  getSimilarRecordingsForArtist,
  getSimilarRecordings,
  getRecordingMetadataBatch,
  getCacheMetrics,
};
