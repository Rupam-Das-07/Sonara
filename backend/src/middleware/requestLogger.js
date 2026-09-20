'use strict';

/**
 * requestLogger.js — Per-request logging middleware.
 *
 * Logs: method, route, status, duration, request ID.
 * MUST NOT log: credentials, API keys, raw audio URLs, internal addresses.
 */

const logger = require('../utils/logger');
const { randomUUID } = require('crypto');

function requestLogger(req, res, next) {
  const requestId = randomUUID().slice(0, 8);
  const t0 = Date.now();

  req.requestId = requestId;

  res.on('finish', () => {
    logger.info('[REQ]', {
      requestId,
      method:     req.method,
      path:       req.path,
      status:     res.statusCode,
      latencyMs:  Date.now() - t0,
    });
  });

  next();
}

module.exports = requestLogger;
