'use strict';

const express = require('express');
const { getTrendingTracks } = require('../discovery/trendingService');
const asyncHandler = require('../middleware/asyncHandler');

const router = express.Router();

/**
 * GET /api/v1/trending — playable trending feed.
 *
 * Previously returned jiosaavnV2Provider output directly, where every item had
 * `videoId: null` and was therefore untappable. Now goes through trendingService,
 * which re-resolves each item to a validated YouTube videoId and drops anything
 * it cannot confidently match.
 *
 * An empty `tracks` array is a legitimate empty state, not an error: it means the
 * upstream feed was unavailable or nothing could be resolved to a playable id.
 */
router.get('/', asyncHandler(async (req, res) => {
  const tracks = await getTrendingTracks();
  res.json({ tracks });
}));

module.exports = router;
