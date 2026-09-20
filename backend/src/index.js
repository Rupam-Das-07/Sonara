'use strict';

/**
 * index.js — Entry point for sonara-backend.
 *
 * Independent Sonara Android backend.
 * Port: 3002 (default, overridable via PORT env var)
 *
 * Development topology:
 *   Python YTMusic service: :5000
 *   Python yt-dlp service:  :5001
 *   This server:            :3002
 *   Android (emulator):     → http://10.0.2.2:3002
 *   Android (device):       → http://<LAN-IP>:3002
 *
 * The existing Sonara Web backend (:3001) is NOT required for Android.
 *
 * Middleware order matters — see comments below.
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');

const config = require('./config/env');
const logger = require('./utils/logger');
const requestLogger = require('./middleware/requestLogger');
const errorMiddleware = require('./middleware/errorMiddleware');
const { Errors } = require('./errors/errors');

const searchRoutes = require('./routes/search');
const streamRoutes = require('./routes/stream');
const playlistRoutes = require('./routes/playlists');
const artistRoutes = require('./routes/artists');
const recommendationRoutes = require('./routes/recommendations');
const identityRoutes = require('./routes/identity');
const trendingRoutes = require('./routes/trending');
const quickPicksRoutes = require('./routes/quickpicks');
const importRoutes = require('./routes/import');
const { identityStore } = require('./identity/IdentityStore');
const { checkHealth, STATUS } = require('./operations/healthChecker');

const app = express();

// ---------------------------------------------------------------------------
// 1. Trust proxy — required for accurate rate-limiting behind Nginx in prod
// ---------------------------------------------------------------------------
app.set('trust proxy', 1);

// ---------------------------------------------------------------------------
// 2. CORS
//    Android native HTTP clients don't send CORS headers, so this is
//    primarily for future web admin tools or local testing.
//    Wildcard is acceptable here because we have no session cookies.
// ---------------------------------------------------------------------------
app.use(cors({ origin: '*' }));

// ---------------------------------------------------------------------------
// 3. Request logging
// ---------------------------------------------------------------------------
app.use(requestLogger);

// ---------------------------------------------------------------------------
// 4. Global rate limiter — protects all routes
// ---------------------------------------------------------------------------
const globalLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: config.globalRateLimitMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: Errors.rateLimited().toJSON(),
  skip: () => config.isDev(),
});
app.use(globalLimiter);

// ---------------------------------------------------------------------------
// 5. JSON body parser
// ---------------------------------------------------------------------------
app.use(express.json());

// ---------------------------------------------------------------------------
// 6. Health endpoint — always available, lightweight
// ---------------------------------------------------------------------------
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'sonara-backend',
    version: '1.0.0',
    ts: new Date().toISOString(),
  });
});

app.get('/health/deep', async (req, res, next) => {
  try {
    const report = await checkHealth({ skipGateway: true });
    const statusCode = report.status === STATUS.DOWN ? 503 : 200;
    res.status(statusCode).json(report);
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------------------
// 7. API v1 routes
// ---------------------------------------------------------------------------
app.use('/api/v1/search', searchRoutes);
app.use('/api/v1/stream', streamRoutes);
app.use('/api/v1/playlists', playlistRoutes);
app.use('/api/v1/artists', artistRoutes);
app.use('/api/v1/recommendations', recommendationRoutes);
app.use('/api/v1/identity', identityRoutes);
app.use('/api/v1/trending', trendingRoutes);
app.use('/api/v1/quickpicks', quickPicksRoutes);
app.use('/api/v1/import', importRoutes);

// Compatibility aliases
app.use('/api/search', searchRoutes);
app.get('/search-youtube', (req, res, next) => {
  req.url = '/videos' + (req.originalUrl.includes('?') ? req.originalUrl.substring(req.originalUrl.indexOf('?')) : '');
  searchRoutes(req, res, next);
});

// ---------------------------------------------------------------------------
// 8. 404 handler — must come after all routes
// ---------------------------------------------------------------------------
app.use((req, res) => {
  res.status(404).json(Errors.notFound(`No route: ${req.method} ${req.path}`).toJSON());
});

// ---------------------------------------------------------------------------
// 9. Global error handler — must be last
// ---------------------------------------------------------------------------
app.use(errorMiddleware);

// ---------------------------------------------------------------------------
// 10. Graceful shutdown handler factory
// ---------------------------------------------------------------------------
function createShutdownHandler({
  server,
  store = identityStore,
  log = logger,
  exitFn = process.exit,
  timeoutMs = 10000,
} = {}) {
  let isShuttingDown = false;

  return async function handleSignal(signal) {
    if (isShuttingDown) {
      if (log) log.warn(`[SHUTDOWN] Already shutting down; ignoring duplicate ${signal}`);
      return;
    }
    isShuttingDown = true;

    if (log) log.info(`[SHUTDOWN] Received ${signal}. Initiating graceful shutdown...`);

    // Bounded fallback: if server or stores do not close in time, force exit
    const forceTimer = setTimeout(() => {
      if (log) log.error(`[SHUTDOWN] Graceful shutdown timed out after ${timeoutMs}ms. Forcing exit.`);
      exitFn(1);
    }, timeoutMs);
    if (forceTimer && typeof forceTimer.unref === 'function') {
      forceTimer.unref();
    }

    // 1. Stop accepting new connections and close idle keep-alives
    if (server && typeof server.close === 'function') {
      if (typeof server.closeIdleConnections === 'function') {
        server.closeIdleConnections();
      }
      await new Promise((resolve) => {
        server.close((err) => {
          if (err) {
            if (log) log.error('[SHUTDOWN] Error while closing HTTP server', { error: err.message });
          } else {
            if (log) log.info('[SHUTDOWN] HTTP server closed successfully');
          }
          resolve();
        });
      });
    }

    // 2. Flush pending dirty state in durable stores (IdentityStore)
    if (store && typeof store.forceFlush === 'function') {
      try {
        if (log) log.info('[SHUTDOWN] Flushing IdentityStore to disk...');
        await store.forceFlush();
        if (log) log.info('[SHUTDOWN] IdentityStore flushed successfully');
      } catch (err) {
        if (log) log.error('[SHUTDOWN] Failed to flush IdentityStore on shutdown', { error: err.message });
      }
    }

    if (log) log.info('[SHUTDOWN] Graceful shutdown completed cleanly. Exiting.');
    clearTimeout(forceTimer);
    exitFn(0);
  };
}

// ---------------------------------------------------------------------------
// 11. Start server & register signal listeners (when executed as main entrypoint)
// ---------------------------------------------------------------------------
let server = null;

if (require.main === module) {
  server = app.listen(config.port, () => {
    logger.info('[STARTUP] sonara-backend started', {
      port: config.port,
      env:  config.nodeEnv,
      pythonYtmusic: config.pythonYtmusicUrl,
      pythonAudio:   config.pythonAudioUrl,
    });
    logger.info('[STARTUP] Android should connect to this server — NOT to the Web backend on :3001');
  });

  const shutdown = createShutdownHandler({ server, store: identityStore, log: logger });
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

module.exports = app;
module.exports.createShutdownHandler = createShutdownHandler;
