'use strict';

/**
 * MusicBrainzService.js — MB API Client
 *
 * The ONLY module in the backend that communicates directly with MusicBrainz.
 * All outbound calls are serialized through mbRateLimiter (1 req/sec max).
 *
 * Responsibilities:
 *  - Identity Resolution: search MB for a Recording MBID given title + artist.
 *  - Immediate Metadata: fetch genres + tags for a resolved MBID.
 *  - Deferred Metadata: fetch artist relationships, recording relationships,
 *    release groups (lazy, background only).
 *
 * This module does NOT:
 *  - Persist anything (that is IdentityStore's responsibility).
 *  - Cache anything (callers own caching).
 *  - Make decisions about recommendations (that is the engine's responsibility).
 *
 * MusicBrainz API rules:
 *  - Rate limit: 1 request/second.
 *  - User-Agent: MUST be set to identify the application.
 *  - Base URL: https://musicbrainz.org/ws/2/
 *  - Format: JSON (?fmt=json)
 */


const { mbRateLimiter } = require('./mbRateLimiter');
const { findBestMatch } = require('./IdentityMatcher');
const { getJson } = require('../utils/httpJson');
const logger = require('../utils/logger');

const MB_BASE_URL   = 'https://musicbrainz.org/ws/2';
const MB_USER_AGENT = 'Sonara/1.0 (music streaming application; contact@sonara.app)';
const MB_TIMEOUT_MS = 8000; // 8 seconds — generous for MB's occasionally slow responses

const BoundedCache = require('../utils/BoundedCache');

// Per-process in-memory cache for raw MB responses.
// Prevents re-fetching the same MBID within a server session.
// Key: `search:${normalizedKey}` or `meta:${mbid}:${stage}`
const _responseCache = new BoundedCache({ maxSize: 1000, ttlMs: 7 * 24 * 60 * 60 * 1000 }); // 7 days — MB data is highly stable

/**
 * Single point of egress to MusicBrainz. NOT exported — all calls go through
 * the public methods below, and all are serialized by mbRateLimiter to honour
 * MB's 1 req/sec rule.
 *
 * Previously called `axios.get` without requiring axios, which is not a declared
 * dependency; every call threw `ReferenceError: axios is not defined`. Now uses
 * native fetch via the shared getJson helper.
 */
function _mbGet(path, params = {}) {
  return mbRateLimiter.schedule(() =>
    getJson(MB_BASE_URL, path, {
      params:    { ...params, fmt: 'json' },
      headers:   { 'User-Agent': MB_USER_AGENT },
      timeoutMs: MB_TIMEOUT_MS,
    })
  );
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Resolves a Sonara track to a MusicBrainz Recording MBID.
 *
 * @param {{ title: string, artist: string, album?: string, durationSeconds?: number }} input
 * @returns {Promise<{
 *   resolved:           boolean,
 *   mbid:               string | null,
 *   confidence:         number,
 *   matchedFields:      Object,
 *   rejectedCandidates: Array
 * }>}
 */
async function resolveIdentity(input) {
  const { title, artist } = input;
  if (!title || !artist) {
    return { resolved: false, mbid: null, confidence: 0, matchedFields: {}, rejectedCandidates: [] };
  }

  // Build a deduplicated cache key from the most significant inputs
  const cacheKey = `search:${title.toLowerCase().trim()}::${artist.toLowerCase().trim()}`;
  const cached = _responseCache.get(cacheKey);
  if (cached !== null) {
    logger.debug(`[MusicBrainzService] Cache hit for "${title}" by "${artist}"`);
    return cached;
  }

  try {
    // MusicBrainz lucene query format
    const query = `recording:"${title.replace(/"/g, '')}" AND artist:"${artist.replace(/"/g, '')}"`;
    logger.debug(`[MusicBrainzService] Searching MB: ${query}`);

    const data = await _mbGet('/recording', { query, limit: 10 });
    const results = data?.recordings || [];

    const match = findBestMatch(input, results);
    _responseCache.set(cacheKey, match);

    if (match.resolved) {
      logger.info(`[MusicBrainzService] Resolved "${title}" → ${match.mbid} (confidence=${match.confidence})`);
    } else {
      logger.info(`[MusicBrainzService] No match for "${title}" by "${artist}" (best rejected candidates: ${match.rejectedCandidates.length})`);
    }

    return match;
  } catch (err) {
    logger.error(`[MusicBrainzService] resolveIdentity failed for "${title}": ${err.message}`);
    throw err; // Propagate so IdentityResolver can apply retry backoff
  }
}

/**
 * Fetches immediate metadata for a resolved MBID.
 * Returns: genres, tags, artistMbid.
 *
 * @param {string} mbid - MusicBrainz Recording MBID
 * @returns {Promise<{ mbid, genres, tags, artistMbid } | null>}
 */
async function fetchImmediateMetadata(mbid) {
  if (!mbid) return null;

  const cacheKey = `meta:${mbid}:immediate`;
  const cached = _responseCache.get(cacheKey);
  if (cached !== null) return cached;

  try {
    logger.debug(`[MusicBrainzService] Fetching immediate metadata for MBID: ${mbid}`);
    const data = await _mbGet(`/recording/${mbid}`, {
      inc: 'genres+tags+artist-credits',
    });

    const genres  = (data.genres  || []).map(g => g.name).filter(Boolean);
    const tags    = (data.tags    || []).map(t => t.name).filter(Boolean);
    const artistMbid = data['artist-credit']?.[0]?.artist?.id || null;

    const result = { mbid, genres, tags, artistMbid };
    _responseCache.set(cacheKey, result);
    return result;
  } catch (err) {
    logger.error(`[MusicBrainzService] fetchImmediateMetadata failed for ${mbid}: ${err.message}`);
    return null;
  }
}

/**
 * Fetches deferred metadata for a resolved MBID.
 * Returns: artistRelationships, recordingRelationships, releaseGroups.
 * Called lazily in the background — never during ranking.
 *
 * @param {string} mbid        - MusicBrainz Recording MBID
 * @param {string} artistMbid  - MusicBrainz Artist MBID (from immediate metadata)
 * @returns {Promise<{ artistRelationships, recordingRelationships, releaseGroups } | null>}
 */
async function fetchDeferredMetadata(mbid, artistMbid) {
  if (!mbid) return null;

  const cacheKey = `meta:${mbid}:deferred`;
  const cached = _responseCache.get(cacheKey);
  if (cached !== null) return cached;

  try {
    logger.debug(`[MusicBrainzService] Fetching deferred metadata for MBID: ${mbid}`);

    // Fetch recording relationships and release groups
    const recordingData = await _mbGet(`/recording/${mbid}`, {
      inc: 'recording-rels+release-groups',
    });

    const recordingRelationships = (recordingData.relations || []).map(r => ({
      type:    r.type,
      direction: r.direction,
      targetMbid: r.recording?.id || null,
      targetTitle: r.recording?.title || null,
    })).filter(r => r.targetMbid);

    const releaseGroups = (recordingData['release-groups'] || []).map(rg => ({
      mbid:            rg.id,
      title:           rg.title,
      primaryType:     rg['primary-type'],
      firstReleaseDate: rg['first-release-date'],
    }));

    // Fetch artist relationships separately (requires artistMbid)
    let artistRelationships = [];
    if (artistMbid) {
      const artistData = await _mbGet(`/artist/${artistMbid}`, { inc: 'artist-rels' });
      artistRelationships = (artistData.relations || []).map(r => ({
        type:          r.type,
        direction:     r.direction,
        targetArtistMbid:  r.artist?.id || null,
        targetArtistName:  r.artist?.name || null,
      })).filter(r => r.targetArtistMbid);
    }

    const result = { artistRelationships, recordingRelationships, releaseGroups };
    _responseCache.set(cacheKey, result);
    return result;
  } catch (err) {
    logger.error(`[MusicBrainzService] fetchDeferredMetadata failed for ${mbid}: ${err.message}`);
    return null;
  }
}

/**
 * Returns a diagnostic snapshot of the internal response cache.
 */
function getCacheMetrics() {
  return {
    entriesCount: _responseCache.size,
    rateLimiter:  mbRateLimiter.getMetrics(),
  };
}

module.exports = {
  resolveIdentity,
  fetchImmediateMetadata,
  fetchDeferredMetadata,
  getCacheMetrics,
};
