'use strict';

/**
 * recommendations.js — Track-seeded recommendation routes for sonara-backend.
 *
 *   GET /api/v1/recommendations/radio/:videoId    — endless radio from a seed
 *   GET /api/v1/recommendations/related/:videoId  — YTMusic "related songs"
 *   GET /api/v1/recommendations/similar/:videoId  — ListenBrainz collaborative
 *
 * All three are SEED-BASED. There is no personalized-feed endpoint, which is
 * why the Android client labels these truthfully ("More like X", "Radio from X")
 * rather than implying a trained recommender.
 *
 * Two defects were repaired here:
 *
 * 1. `Errors.badRequest` does not exist — the factory exports `invalidRequest`.
 *    Calling it threw a synchronous TypeError inside an async handler, which
 *    Express 4 does not catch, producing an unhandled rejection that terminates
 *    the process under Node >= 20. Any malformed videoId could kill the server.
 *
 * 2. Failures were reported as HTTP 200 with `{ success: false, tracks: [] }`.
 *    A client cannot distinguish that from "no results", so a broken provider
 *    looked like an empty one. Provider failures now return 502 through the
 *    sanitized error middleware, which lets the Android module self-hide on
 *    error as the Home specification requires. Internal failure reasons are
 *    logged server-side and never sent to the client.
 */

const express = require('express');
const RecommendationService = require('../recommendation/recommendationService');
const { validateVideoId } = require('../stream/streamValidator');
const { Errors } = require('../errors/errors');
const asyncHandler = require('../middleware/asyncHandler');
const logger = require('../utils/logger');

const router = express.Router();

/**
 * Builds a route handler for one recommendation strategy.
 *
 * @param {string} label - Strategy name, used for logging only.
 * @param {(videoId: string) => Promise<object>} fetch - Service method.
 */
function recommendationRoute(label, fetch) {
  return asyncHandler(async (req, res, next) => {
    const { videoId } = req.params;

    const validation = validateVideoId(videoId);
    if (!validation.valid) {
      return next(Errors.invalidRequest(`Invalid videoId: ${validation.reason}`));
    }

    const result = await fetch(videoId);

    if (!result || result.success !== true) {
      // Log the real reason; return a sanitized 502 so the client can treat the
      // module as unavailable rather than empty.
      logger.warn(`[ROUTES/RECOMMENDATIONS] ${label} unavailable for ${videoId}`, {
        reason: result?.error || 'unknown',
      });
      return next(Errors.providerFailure(`Recommendations are unavailable for this track`));
    }

    return res.json(result);
  });
}

router.get(
  '/radio/:videoId',
  recommendationRoute('radio', (videoId) => RecommendationService.getRadioCandidates(videoId))
);

router.get(
  '/related/:videoId',
  recommendationRoute('related', (videoId) => RecommendationService.getRelatedCandidates(videoId))
);

router.get(
  '/similar/:videoId',
  recommendationRoute('similar', (videoId) => RecommendationService.getSimilarCandidates(videoId))
);

module.exports = router;
