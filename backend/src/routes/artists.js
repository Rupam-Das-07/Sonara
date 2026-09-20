'use strict';

const express = require('express');
const { generateArtistCatalog } = require('../discovery/artistCatalogService');
const { getFeaturedArtists } = require('../discovery/featuredArtistsService');
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
