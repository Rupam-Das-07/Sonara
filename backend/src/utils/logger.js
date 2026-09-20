'use strict';

/**
 * logger.js — Minimal structured logger for sonara-backend.
 *
 * Logs request IDs, routes, status, latency, and error categories.
 *
 * MUST NOT log:
 *   - API keys
 *   - Credentials
 *   - Internal Python service URLs (they are internal infrastructure)
 *   - Raw sensitive stream tokens
 */

const LOG_LEVELS = { debug: 0, info: 1, warn: 2, error: 3 };
const env = process.env.NODE_ENV || 'development';
const minLevel = env === 'production' ? LOG_LEVELS.info : LOG_LEVELS.debug;

function log(level, message, meta = {}) {
  if (LOG_LEVELS[level] < minLevel) return;
  const entry = {
    ts: new Date().toISOString(),
    level,
    message,
    ...meta,
  };
  const fn = level === 'error' ? console.error : console.log;
  fn(JSON.stringify(entry));
}

module.exports = {
  debug: (msg, meta) => log('debug', msg, meta),
  info:  (msg, meta) => log('info',  msg, meta),
  warn:  (msg, meta) => log('warn',  msg, meta),
  error: (msg, meta) => log('error', msg, meta),
};
