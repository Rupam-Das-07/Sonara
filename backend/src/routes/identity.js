'use strict';

const express = require('express');
const { resolveIdentity, fetchImmediateMetadata, fetchDeferredMetadata, getCacheMetrics } = require('../identity/MusicBrainzService');
const { identityStore } = require('../identity/IdentityStore');
const { Errors } = require('../errors/errors');
const asyncHandler = require('../middleware/asyncHandler');
const logger = require('../utils/logger');

const router = express.Router();

// NOTE: these handlers previously called `Errors.badRequest`, which the Errors
// factory does not export (the correct name is `invalidRequest`). Because the
// call sat outside the try/catch inside an async handler, the resulting
// TypeError became an unhandled rejection and terminated the process under
// Node >= 20. Handlers are additionally wrapped in asyncHandler so no future
// throw can escape into the process-level rejection handler.

router.get('/resolve', asyncHandler(async (req, res, next) => {
  const { title, artist, duration } = req.query;
  if (!title || !artist) {
    return next(Errors.invalidRequest('Query parameters "title" and "artist" are required'));
  }

  try {
    const durationSeconds = duration ? parseInt(duration, 10) : undefined;
    const match = await resolveIdentity({ title, artist, durationSeconds });
    res.json(match);
  } catch (err) {
    logger.error(`[ROUTES/IDENTITY] Resolve failed: ${err.message}`);
    next(err);
  }
}));

router.get('/metadata/:mbid', asyncHandler(async (req, res, next) => {
  const { mbid } = req.params;
  if (!mbid) {
    return next(Errors.invalidRequest('MBID parameter is required'));
  }

  try {
    const immediate = await fetchImmediateMetadata(mbid);
    if (!immediate) {
      return next(Errors.notFound(`No metadata found for MBID: ${mbid}`));
    }

    const deferred = await fetchDeferredMetadata(mbid, immediate.artistMbid);
    res.json({
      ...immediate,
      relationships: deferred?.recordingRelationships || [],
      releaseGroups: deferred?.releaseGroups || [],
    });
  } catch (err) {
    logger.error(`[ROUTES/IDENTITY] Metadata failed: ${err.message}`);
    next(err);
  }
}));

router.get('/store/snapshot', (req, res) => {
  res.json({
    store: identityStore.getSnapshot(),
    cache: getCacheMetrics(),
  });
});

module.exports = router;
