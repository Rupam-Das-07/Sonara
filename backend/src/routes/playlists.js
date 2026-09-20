'use strict';

const express = require('express');
const PlaylistService = require('../discovery/PlaylistService');
const { getAllDefinitions, getDefinitionById } = require('../discovery/PlaylistDefinitions');
const { Errors } = require('../errors/errors');
const asyncHandler = require('../middleware/asyncHandler');

const router = express.Router();

/**
 * GET /api/v1/playlists — curated playlist catalog for the Home screen.
 *
 * TWO REPAIRS
 * -----------
 * 1. `coverImage` previously echoed each definition's
 *    '/assets/playlist-artwork/*.png'. There is no assets/ directory, the PNGs
 *    do not exist, and index.js registers no express.static, so every cover URL
 *    was unroutable. Covers are now derived from the first eligible track's real
 *    thumbnail, and are null when none can be derived.
 *
 * 2. The catch block returned `{ error: 'Failed to fetch playlists' }` — a bare
 *    string where every other route returns the standard
 *    `{ error: { code, message } }` from SonaraBackendError.toJSON(). A client
 *    parsing errors uniformly would have failed on this one shape. Errors now go
 *    through the shared error middleware via asyncHandler.
 */
router.get('/', asyncHandler(async (req, res) => {
  const definitions = getAllDefinitions();
  const covers = await PlaylistService.getPlaylistCovers(definitions.map((d) => d.id));

  const playlists = definitions.map((d) => ({
    id: d.id,
    name: d.name,
    description: d.description,
    coverImage: covers[d.id] || null,
    size: d.size,
  }));

  res.json({ playlists });
}));

router.get('/:id', asyncHandler(async (req, res, next) => {
  const { id } = req.params;
  const definition = getDefinitionById(id);
  if (!definition) {
    return next(Errors.notFound(`Playlist not found: ${id}`));
  }

  const tracks = await PlaylistService.generateCuratedPlaylist(id);

  // Prefer the artwork of the playlist we just resolved; fall back to the
  // derived cover so the tile and the detail view agree.
  const coverImage = (tracks.length > 0 && tracks[0].artworkUrl)
    ? tracks[0].artworkUrl
    : await PlaylistService.getPlaylistCover(id);

  res.json({
    id: definition.id,
    name: definition.name,
    description: definition.description,
    coverImage: coverImage || null,
    tracks,
  });
}));

module.exports = router;
