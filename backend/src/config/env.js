'use strict';

require('dotenv').config();

/**
 * env.js — Environment configuration and validation.
 *
 * All runtime configuration is read from process.env.
 * Hard-coded values are explicitly forbidden.
 * Startup fails fast with a clear message if required vars are absent.
 */

function requireEnv(key, fallback) {
  const value = process.env[key];
  if (value !== undefined && value.trim() !== '') return value.trim();
  if (fallback !== undefined) return fallback;
  throw new Error(`[CONFIG] Required environment variable "${key}" is not set. Check your .env file.`);
}

/**
 * Resolves effective NODE_ENV fail-closed:
 * 1. An explicit, non-empty process.env.NODE_ENV is always respected.
 * 2. If NODE_ENV is unset/empty and `--dev` is present in process.argv (e.g. via `npm run dev`),
 *    it resolves to 'development'.
 * 3. Otherwise, defaults strictly to 'production' so an ordinary production startup
 *    (e.g. `npm start`, `node src/index.js`, PM2, container/systemd) is secure by construction.
 */
function resolveNodeEnv(env = process.env, argv = process.argv) {
  const rawEnv = (env.NODE_ENV || '').trim();
  if (rawEnv !== '') {
    return rawEnv;
  }
  if (Array.isArray(argv) && argv.includes('--dev')) {
    return 'development';
  }
  return 'production';
}

const initialNodeEnv = resolveNodeEnv();
if (!process.env.NODE_ENV) {
  process.env.NODE_ENV = initialNodeEnv;
}

const config = {
  // Server
  port: parseInt(requireEnv('PORT', '3002'), 10),
  nodeEnv: initialNodeEnv,

  // Internal Python service URLs — internal only, never exposed to clients
  pythonYtmusicUrl: requireEnv('PYTHON_YTMUSIC_URL', 'http://127.0.0.1:5000'),
  pythonAudioUrl: requireEnv('PYTHON_AUDIO_URL', 'http://127.0.0.1:5001'),

  // Timeouts
  ytmusicTimeoutMs: parseInt(requireEnv('YTMUSIC_TIMEOUT_MS', '8000'), 10),
  streamResolveTimeoutMs: parseInt(requireEnv('STREAM_RESOLVE_TIMEOUT_MS', '30000'), 10),
  searchTimeoutMs: parseInt(requireEnv('SEARCH_TIMEOUT_MS', '12000'), 10),

  // Rate limits
  searchRateLimitMax: parseInt(requireEnv('SEARCH_RATE_LIMIT_MAX', '60'), 10),
  streamResolveRateLimitMax: parseInt(requireEnv('STREAM_RESOLVE_RATE_LIMIT_MAX', '20'), 10),
  globalRateLimitMax: parseInt(requireEnv('GLOBAL_RATE_LIMIT_MAX', '200'), 10),

  isDev() { return (this.nodeEnv || '').toLowerCase() === 'development'; },
  isProd() { return (this.nodeEnv || '').toLowerCase() === 'production'; },
  resolveNodeEnv,
};

module.exports = config;
