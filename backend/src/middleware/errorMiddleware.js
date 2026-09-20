'use strict';

/**
 * errorMiddleware.js — Global error handler for sonara-backend.
 *
 * Converts all SonaraBackendError and unexpected Error instances to
 * sanitized JSON responses. Stack traces, Python URLs, and credentials
 * must NEVER reach the client.
 */

const { SonaraBackendError } = require('../errors/errors');
const logger = require('../utils/logger');

// eslint-disable-next-line no-unused-vars
function errorMiddleware(err, req, res, next) {
  if (err instanceof SonaraBackendError) {
    // Known, sanitized error
    return res.status(err.status).json(err.toJSON());
  }

  // Rate-limit errors from express-rate-limit arrive as plain objects
  if (err && err.status === 429) {
    return res.status(429).json({ error: { code: 'RATE_LIMITED', message: 'Rate limit exceeded' } });
  }

  // Unknown error — log with detail, respond with sanitized message
  logger.error('[ERROR] Unhandled error', {
    path:   req.path,
    method: req.method,
    error:  err?.message || 'Unknown error',
    // Deliberately not logging stack trace to avoid credential/path leakage
  });

  res.status(500).json({ error: { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred' } });
}

module.exports = errorMiddleware;
