'use strict';

/**
 * import.js — Route handler for Spotify Playlist Import batch matching.
 *
 * POST /api/v1/import/match
 *
 * Receives structured metadata chunks only (no raw files, no Spotify URIs, no OAuth).
 * Validates request payload, applies local 256KB body parsing, rate-limits chunk requests,
 * and delegates to importMatchService.
 */

const express = require('express');
const rateLimit = require('express-rate-limit');
const { Errors } = require('../errors/errors');
const config = require('../config/env');
const logger = require('../utils/logger');
const asyncHandler = require('../middleware/asyncHandler');
const { matchTracksChunk } = require('../import/importMatchService');
const { importMatchCache } = require('../import/importMatchCache');

const router = express.Router();

// Local 256KB body parser middleware for this router
const localJsonParser = express.json({ limit: '256kb' });

// Dedicated batch-aware import rate limiter (30 requests/min, skipped in dev)
const importLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: Errors.rateLimited('Import rate limit exceeded. Please wait a moment.').toJSON(),
  skip: () => config.isDev(),
});

const MAX_TRACKS_PER_CHUNK = 50;
const MAX_STRING_LENGTH = 300;
const MAX_DURATION_MS = 24 * 60 * 60 * 1000; // 24 hours

/**
 * Validates an incoming track item.
 *
 * @param {any} track
 * @param {number} index
 * @returns {string|null} Error message or null if valid
 */
function validateTrack(track, index) {
  if (!track || typeof track !== 'object') {
    return `Track at index ${index} must be an object`;
  }

  // Local files and episodes can have minimal metadata
  if (track.isLocalFile === true || track.isEpisode === true) {
    return null;
  }

  if (typeof track.title !== 'string' || track.title.trim().length === 0) {
    return `Track at index ${index} is missing a non-empty "title"`;
  }
  if (track.title.length > MAX_STRING_LENGTH) {
    return `Track at index ${index} "title" exceeds max length of ${MAX_STRING_LENGTH}`;
  }

  if (typeof track.artist !== 'string' || track.artist.trim().length === 0) {
    return `Track at index ${index} is missing a non-empty "artist"`;
  }
  if (track.artist.length > MAX_STRING_LENGTH) {
    return `Track at index ${index} "artist" exceeds max length of ${MAX_STRING_LENGTH}`;
  }

  if (track.durationMs !== undefined && track.durationMs !== null) {
    if (typeof track.durationMs !== 'number' || track.durationMs < 0 || track.durationMs > MAX_DURATION_MS) {
      return `Track at index ${index} has an invalid "durationMs"`;
    }
  }

  if (track.isrc !== undefined && track.isrc !== null) {
    if (typeof track.isrc !== 'string' || track.isrc.length > 30) {
      return `Track at index ${index} has an invalid "isrc"`;
    }
  }

  return null;
}

// POST /api/v1/import/match
router.post('/match', localJsonParser, importLimiter, asyncHandler(async (req, res, next) => {
  const t0 = Date.now();
  const body = req.body;

  if (!body || typeof body !== 'object') {
    return res.status(400).json(Errors.invalidRequest('Request body must be a JSON object').toJSON());
  }

  const importId = typeof body.importId === 'string' && body.importId.trim()
    ? body.importId.trim()
    : 'unknown';
  const chunkIndex = typeof body.chunkIndex === 'number' && body.chunkIndex >= 0
    ? body.chunkIndex
    : 0;

  if (!Array.isArray(body.tracks)) {
    return res.status(400).json(Errors.invalidRequest('Field "tracks" must be an array').toJSON());
  }

  if (body.tracks.length > MAX_TRACKS_PER_CHUNK) {
    return res.status(400).json(
      Errors.invalidRequest(`Max ${MAX_TRACKS_PER_CHUNK} tracks allowed per chunk request`).toJSON()
    );
  }

  // Validate all tracks
  for (let i = 0; i < body.tracks.length; i++) {
    const errorMsg = validateTrack(body.tracks[i], i);
    if (errorMsg) {
      return res.status(400).json(Errors.invalidRequest(errorMsg).toJSON());
    }
  }

  // Sanitize tracks (drop spotifyUri, ensure typed fields)
  const sanitizedTracks = body.tracks.map((t, index) => ({
    sourceOrder: typeof t.sourceOrder === 'number' ? t.sourceOrder : index,
    title: (t.title || '').trim(),
    artist: (t.artist || '').trim(),
    album: typeof t.album === 'string' ? t.album.trim() : '',
    durationMs: typeof t.durationMs === 'number' ? t.durationMs : null,
    isLocalFile: Boolean(t.isLocalFile),
    isEpisode: Boolean(t.isEpisode),
  }));

  try {
    const results = await matchTracksChunk(sanitizedTracks);

    // Aggregate summary for structured logging
    let confident = 0;
    let ambiguous = 0;
    let unmatched = 0;
    let skipped = 0;

    for (const r of results) {
      if (r.status === 'matched') confident++;
      else if (r.status === 'ambiguous') ambiguous++;
      else if (r.status === 'unmatched') unmatched++;
      else if (r.status === 'skipped') skipped++;
    }

    const elapsedMs = Date.now() - t0;

    logger.info('[IMPORT] Chunk matching completed', {
      importId,
      chunkIndex,
      totalTracks: sanitizedTracks.length,
      confident,
      ambiguous,
      unmatched,
      skipped,
      elapsedMs,
      cacheHitRate: `${importMatchCache.hitRate}%`,
    });

    res.json({
      importId,
      chunkIndex,
      results,
    });
  } catch (err) {
    logger.error('[IMPORT] Unexpected failure in chunk matching', {
      importId,
      chunkIndex,
      error: err.message,
    });
    next(Errors.internal('Failed to process import matching'));
  }
}));

module.exports = router;
