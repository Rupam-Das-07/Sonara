'use strict';

const express = require('express');
const rateLimit = require('express-rate-limit');
const asyncHandler = require('../middleware/asyncHandler');
const { getVideoStatus } = require('../videos/videoStatusService');
const { getHighResVideo } = require('../videos/highresService');
const { Errors } = require('../errors/errors');
const config = require('../config/env');
const logger = require('../utils/logger');

const router = express.Router();

const highresLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: config.streamResolveRateLimitMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: Errors.rateLimited().toJSON(),
  skip: () => config.isDev(),
});

// GET /api/v1/videos/:videoId/status
router.get('/:videoId/status', asyncHandler(async (req, res, next) => {
  const videoId = typeof req.params.videoId === 'string' ? req.params.videoId : '';

  try {
    const result = await getVideoStatus(videoId);
    res.json(result);
  } catch (error) {
    logger.warn('[VIDEOS/STATUS] Verification failed', {
      videoId,
      code: error?.code || 'UNKNOWN_ERROR',
    });
    next(error);
  }
}));

// GET /api/v1/videos/:videoId/highres
router.get('/:videoId/highres', highresLimiter, asyncHandler(async (req, res, next) => {
  const videoId = typeof req.params.videoId === 'string' ? req.params.videoId : '';

  try {
    const result = await getHighResVideo(videoId);
    res.json(result);
  } catch (error) {
    logger.warn('[VIDEOS/HIGHRES] High-resolution discovery failed', {
      videoId,
      code: error?.code || 'UNKNOWN_ERROR',
      message: error?.message,
    });
    next(error);
  }
}));

module.exports = router;
