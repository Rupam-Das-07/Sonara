'use strict';

/**
 * quickpicks.js — Android-facing Quick Picks route for sonara-backend.
 *
 * GET /api/v1/quickpicks           — session-stable set of playable picks.
 * GET /api/v1/quickpicks?refresh=1 — bypass the cache and rotate the fallback.
 *
 * Flow:
 *   Android → /api/v1/quickpicks
 *           → quickPicksService.getQuickPicks()
 *           → ytmusicProvider.searchSongs() (real YouTube videoIds)
 *           → balanced, playable TrackDTO[]
 *
 * Response envelope matches the other discovery routes (`{ playlists }`,
 * `{ featured }`): `{ quickPicks: TrackDTO[] }`. The service never throws, so a
 * provider outage yields `{ quickPicks: [] }` and the Home module self-hides.
 */

const express = require('express');
const { getQuickPicks } = require('../discovery/quickPicksService');
const asyncHandler = require('../middleware/asyncHandler');
const logger = require('../utils/logger');

const router = express.Router();

router.get('/', asyncHandler(async (req, res) => {
  const t0 = Date.now();
  const refresh = req.query.refresh === '1' || req.query.refresh === 'true';

  const quickPicks = await getQuickPicks({ refresh });

  logger.info('[QUICKPICKS] Completed', {
    refresh,
    results: quickPicks.length,
    latencyMs: Date.now() - t0,
  });

  res.json({ quickPicks });
}));

module.exports = router;
