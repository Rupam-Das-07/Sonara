'use strict';

const express = require('express');
const { generateArtistCatalog } = require('../discovery/artistCatalogService');
const {
  getFeaturedArtists,
  resolveArtistImage,
} = require('../discovery/featuredArtistsService');
const { getAdjacency } = require('../discovery/artistAdjacencyMap');
const { getDefinitionsForArtist } = require('../discovery/PlaylistDefinitions');
const asyncHandler = require('../middleware/asyncHandler');
const logger = require('../utils/logger');

const router = express.Router();

/**
 * GET /featured — editorial artist roster for the Home screen.
 *
 * Previously returned `{ name, genre }` only, with no identifier and no image,
 * which is not renderable or navigable as a Home module. Now returns
 * `{ id, name, genre, imageUrl }` where imageUrl is the artist's real YTMusic
 * thumbnail, or null when it could not be resolved (client renders a monogram).
 *
 * Always returns the full roster in a stable order; image resolution failures
 * degrade individual tiles rather than the module.
 */
router.get('/featured', asyncHandler(async (req, res) => {
  const featured = await getFeaturedArtists();
  res.json({ featured });
}));

/**
 * GET /image?name=... — best-effort portrait for a locally derived artist.
 *
 * Local history intentionally stays on-device; this endpoint receives only the
 * display name and returns null when the trusted upstream cannot resolve an
 * image. The Android client uses it only for personal candidates that have no
 * editorial metadata, so an upstream miss keeps the existing monogram fallback.
 */
router.get('/image', asyncHandler(async (req, res) => {
  const name = typeof req.query.name === 'string' ? req.query.name.trim() : '';
  if (!name) {
    return res.status(400).json({ error: 'name is required' });
  }
  const imageUrl = await resolveArtistImage(name);
  return res.json({ imageUrl });
}));

router.get('/:browseId', asyncHandler(async (req, res, next) => {
  const { browseId } = req.params;
  const artistName = req.query.name || browseId;

  try {
    const catalog = await generateArtistCatalog(browseId, artistName);
    const adjacency = getAdjacency(artistName || browseId);

    const resolvedArtistName = catalog?.artist || artistName || '';
    const artistPlaylists = getDefinitionsForArtist(resolvedArtistName);
    const playlists = artistPlaylists.map((d) => ({
      id: d.id,
      name: d.name,
      description: d.description,
      size: d.size,
      coverImage: null,
    }));

    res.json({
      ...catalog,
      relatedArtists: adjacency?.related || [],
      playlists,
    });
  } catch (err) {
    logger.error(`[ROUTES/ARTISTS] Failed for ${browseId}: ${err.message}`);
    next(err);
  }
}));

module.exports = router;
