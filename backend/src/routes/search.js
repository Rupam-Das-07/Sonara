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
const { Errors } = require('../errors/errors');
const logger = require('../utils/logger');
const config = require('../config/env');
const asyncHandler = require('../middleware/asyncHandler');

const router = express.Router();

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

  return {
    id:         track.id,
    videoId:    track.videoId,
    title:      track.title,
    artist:     track.artist,
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
    const pythonUrl = `${config.pythonAudioUrl}/search-youtube?q=${encodeURIComponent(query)}&limit=${limit}`;
    const pythonRes = await fetch(pythonUrl, {
      signal: AbortSignal.timeout(config.searchTimeoutMs || 12000),
    });

    if (!pythonRes.ok) {
      logger.warn('[SEARCH/VIDEOS] Python service error', { status: pythonRes.status });
      return res.json({ items: [] });
    }

    const data = await pythonRes.json();
    const rawItems = data.items || (Array.isArray(data) ? data : []);

    const mappedItems = rawItems.map(item => {
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
    });

    logger.info('[SEARCH/VIDEOS] Completed', {
      query,
      results: mappedItems.length,
      latencyMs: Date.now() - t0,
    });

    res.json({ items: mappedItems });
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
