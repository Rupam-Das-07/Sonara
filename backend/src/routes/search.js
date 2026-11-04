'use strict';

/**
 * search.js — Android-facing search route for sonara-backend.
 *
 * GET /api/v1/search?q={query}
 *
 * Flow:
 *   Android → /api/v1/search
 *           → ytmusicProvider (Python :5000 via native fetch)
 *           → SearchQualityEngine (4-stage deterministic ranking)
 *           → Android-facing TrackDTO[]
 *
 * The Android client receives only sanitized, ranked results.
 * Provider credentials and internal Python URLs are never exposed.
 */

const express = require('express');
const rateLimit = require('express-rate-limit');
const { searchSongs, searchMetrics } = require('../search/ytmusicProvider');
const SearchQualityEngine = require('../search/SearchQualityEngine');
const BoundedCache = require('../utils/BoundedCache');
const { Errors } = require('../errors/errors');
const logger = require('../utils/logger');
const config = require('../config/env');
const asyncHandler = require('../middleware/asyncHandler');

const router = express.Router();

// ---------------------------------------------------------------------------
// Stage C-3F — /search/videos cache + in-flight deduplication.
//
// WHY: /search/videos was the only Android-facing read path with NO protection
// at ANY layer. Node held no cache and no in-flight map, and on the Python side
// youtube_service.search_youtube() calls _extract_with_retry() DIRECTLY — the
// `_in_flight_extractions` map and the stream URL cache both live in
// get_audio_info(), not in _extract_with_retry() — so nothing deduplicated it
// either. Every request therefore ran a fresh yt-dlp `ytsearchN:` extraction
// (15s timeout, up to 2 retries, pooled YoutubeDL). SearchViewModel fires this
// concurrently with Mode 1 on every submitted search, so it is a live hot path.
//
// Caching is safe here because the upstream payload carries NO ephemeral data:
// search_youtube returns only videoId, title, channelTitle, a thumbnail derived
// from the videoId, and duration. There is no expiring stream URL to go stale
// (unlike the stream descriptors, which need the bespoke expiry handling in
// AudioSourceResolver).
//
// TTL matches the sibling convention in ytmusicProvider.searchSongs (15 min).
// The bound is deliberately half of searchSongs' 1000 because an entry here can
// hold up to `limit` (max 50) items versus a fixed 20, keeping worst-case
// footprint comparable.
// ---------------------------------------------------------------------------
const VIDEO_SEARCH_TTL_MS = 15 * 60 * 1000;
const VIDEO_SEARCH_MAX_ENTRIES = 500;
const _videoSearchCache = new BoundedCache({ maxSize: VIDEO_SEARCH_MAX_ENTRIES, ttlMs: VIDEO_SEARCH_TTL_MS });
const _inFlightVideoSearches = new Map();

/** Same normalization as ytmusicProvider.normalizeQuery, so cache behaviour is consistent. */
function normalizeVideoQuery(q) {
  if (!q) return '';
  return q.trim().toLowerCase().replace(/\s+/g, ' ');
}

/**
 * Maps one raw Python search item to the Android-facing video DTO.
 * Extracted verbatim from the former inline mapper — shape and field precedence
 * are unchanged.
 *
 * @param {object} item
 * @returns {object}
 */
function mapVideoItem(item) {
  const vid = item.videoId || item.id || '';
  const thumb = item.thumbnail || (vid ? `https://i.ytimg.com/vi/${vid}/maxresdefault.jpg` : '');
  return {
    id: vid,
    videoId: vid,
    title: item.title || 'Unknown Title',
    artist: item.artist || item.channelTitle || item.channel || 'Unknown Artist',
    album: item.album || 'YouTube',
    duration: item.duration || 0,
    artworkUrl: thumb,
    thumbnails: [{ url: thumb }],
    resultType: 'video'
  };
}

/**
 * Resolves a video search, serving from cache and collapsing concurrent
 * identical requests into one upstream yt-dlp search.
 *
 * Error semantics are deliberately identical to the previous inline code:
 *  - a non-OK upstream status resolves to null, which the route renders as an
 *    empty 200 result, and is NEVER cached (a transient provider outage must not
 *    pin an empty Videos tab for 15 minutes);
 *  - a network/timeout failure REJECTS, so the route's catch still produces 502.
 *    The in-flight entry is released either way.
 *
 * A caller that joins an in-flight search inherits the remaining timeout budget
 * of the original rather than starting a fresh one; that can only ever be
 * shorter than today's budget, never longer.
 *
 * @param {string} query - Raw (untrimmed-case) query, sent upstream as-is.
 * @param {number} limit
 * @returns {Promise<object[]|null>} Mapped items, or null when upstream was not OK.
 */
function resolveVideoSearch(query, limit) {
  // The limit is part of the identity: a cached 10-item answer must never be
  // served to a request that asked for 50.
  const cacheKey = `videos:${normalizeVideoQuery(query)}:${limit}`;

  const cached = _videoSearchCache.get(cacheKey);
  if (cached) return Promise.resolve(cached);

  const inFlight = _inFlightVideoSearches.get(cacheKey);
  if (inFlight) return inFlight;

  // Registered synchronously (before the first await) so two callers in the same
  // tick cannot both miss and both start an upstream search.
  const pending = (async () => {
    const pythonUrl = `${config.pythonAudioUrl}/search-youtube?q=${encodeURIComponent(query)}&limit=${limit}`;
    const pythonRes = await fetch(pythonUrl, {
      signal: AbortSignal.timeout(config.searchTimeoutMs || 12000),
    });

    if (!pythonRes.ok) {
      logger.warn('[SEARCH/VIDEOS] Python service error', { status: pythonRes.status });
      return null;
    }

    const data = await pythonRes.json();
    const rawItems = data.items || (Array.isArray(data) ? data : []);
    const mappedItems = rawItems.map(mapVideoItem);

    // Only a genuinely non-empty success is cached, matching the established
    // rule in artistCatalogService and quickPicksService: never let an emptiness
    // that might be a failure become sticky.
    if (mappedItems.length > 0) {
      _videoSearchCache.set(cacheKey, mappedItems);
    }
    return mappedItems;
  })().finally(() => {
    _inFlightVideoSearches.delete(cacheKey);
  });

  _inFlightVideoSearches.set(cacheKey, pending);
  return pending;
}

const searchLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: config.searchRateLimitMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: Errors.rateLimited().toJSON(),
  skip: () => config.isDev(), // No rate-limiting in dev
});

/**
 * Map a CanonicalTrack to an Android-facing TrackDTO.
 * Strips internal SQE fields (rawTitle, originalRank, etc.) from the response.
 * Returns only stable, client-safe fields.
 *
 * @param {object} track
 * @returns {object} TrackDTO
 */
function toTrackDTO(track) {
  // Prefer the last (largest) thumbnail for artwork
  const thumbnails = track.thumbnails || [];
  const artworkUrl = thumbnails.length > 0
    ? thumbnails[thumbnails.length - 1].url
    : '';

  // Structured per-artist credits, forwarded so the client can attribute listening
  // affinity to a VERIFIED artist identity instead of parsing the flat `artist`
  // string. Only entries carrying a usable name survive; `id` is upstream's channel
  // id when present and '' otherwise, and an absent/!array value yields []. The flat
  // `artist` field is unchanged, so existing clients are unaffected.
  const artists = Array.isArray(track.artists)
    ? track.artists
      .filter((a) => a && typeof a.name === 'string' && a.name.trim())
      .map((a) => ({
        name: a.name.trim(),
        id: typeof a.id === 'string' ? a.id.trim() : '',
      }))
    : [];

  return {
    id:         track.id,
    videoId:    track.videoId,
    title:      track.title,
    artist:     track.artist,
    artists:    artists,
    album:      track.album || '',
    duration:   track.duration,   // seconds (number | null)
    artworkUrl: artworkUrl,
    thumbnails: thumbnails,
    resultType: track.resultType,
    // qualityScore intentionally omitted from client response
  };
}

// GET /api/v1/search?q=<query>
router.get('/', searchLimiter, asyncHandler(async (req, res, next) => {
  const t0 = Date.now();
  const query = (req.query.q || '').trim();

  if (!query) {
    return res.status(400).json(Errors.invalidRequest('Query parameter "q" is required and must not be blank').toJSON());
  }

  if (query.length > 200) {
    return res.status(400).json(Errors.invalidRequest('Query is too long (max 200 characters)').toJSON());
  }

  try {
    const rawResults = await searchSongs(query);
    const rankedResults = SearchQualityEngine.process(rawResults, query);
    const dtos = rankedResults.map(toTrackDTO);

    logger.info('[SEARCH] Completed', {
      query,
      results: dtos.length,
      latencyMs: Date.now() - t0,
    });

    res.json(dtos);
  } catch (err) {
    logger.error('[SEARCH] Unexpected failure', { query, error: err.message });
    next(Errors.providerFailure('Search service unavailable'));
  }
}));

// GET /api/v1/search/videos?q=<query>&limit=10
router.get('/videos', searchLimiter, asyncHandler(async (req, res, next) => {
  const t0 = Date.now();
  const query = (req.query.q || '').trim();
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 10, 1), 50);

  if (!query) {
    return res.status(400).json(Errors.invalidRequest('Query parameter "q" is required and must not be blank').toJSON());
  }

  if (query.length > 200) {
    return res.status(400).json(Errors.invalidRequest('Query is too long (max 200 characters)').toJSON());
  }

  try {
    const items = await resolveVideoSearch(query, limit);

    // Upstream answered non-OK: same contract as before — an empty 200 result,
    // no "Completed" log, nothing cached.
    if (items === null) {
      return res.json({ items: [] });
    }

    logger.info('[SEARCH/VIDEOS] Completed', {
      query,
      results: items.length,
      latencyMs: Date.now() - t0,
    });

    res.json({ items });
  } catch (err) {
    logger.error('[SEARCH/VIDEOS] Unexpected failure', { query, error: err.message });
    next(Errors.providerFailure('YouTube video search service unavailable'));
  }
}));

// GET /api/v1/search/suggestions?q=<query>
router.get('/suggestions', asyncHandler(async (req, res) => {
  const query = (req.query.q || '').trim();
  if (!query) {
    return res.json({ query: '', suggestions: [] });
  }

  try {
    const url = `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(query)}`;
    const response = await fetch(url, { signal: AbortSignal.timeout(4000) });
    if (!response.ok) {
      return res.json({ query, suggestions: [] });
    }
    const data = await response.json();
    const suggestions = Array.isArray(data) && Array.isArray(data[1]) ? data[1] : [];
    res.json({ query, suggestions });
  } catch (err) {
    logger.warn('[SEARCH/SUGGESTIONS] Failed', { query, error: err.message });
    res.json({ query, suggestions: [] });
  }
}));

// GET /api/v1/search/metrics — debug endpoint (dev only)
router.get('/metrics', (req, res) => {
  if (config.isProd()) {
    return res.status(404).json(Errors.notFound().toJSON());
  }
  res.json({
    cacheHits:        searchMetrics.cacheHits,
    cacheMisses:      searchMetrics.cacheMisses,
    upstreamSearches: searchMetrics.upstreamSearches,
    hitRate:          searchMetrics.hitRate,
    lastTimeMs:       searchMetrics.lastTimeMs,
    inFlight:         searchMetrics.inFlight,
    cacheSize:        searchMetrics.cacheSize,
  });
});

module.exports = router;

// Exposed for test inspection only (not for production use) — mirrors
// RecommendationService._radioCache / AudioSourceResolver.standardCache.
module.exports._videoSearchCache = _videoSearchCache;
module.exports._inFlightVideoSearches = _inFlightVideoSearches;
module.exports.VIDEO_SEARCH_TTL_MS = VIDEO_SEARCH_TTL_MS;
