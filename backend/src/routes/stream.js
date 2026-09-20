'use strict';

/**
 * stream.js — Android-facing stream routes for sonara-backend.
 *
 * Routes:
 *   GET /api/v1/stream/resolve?url={youtube-url}
 *     — Calls Python :5001 /get-youtube-audio, returns controlled JSON.
 *
 *   GET /api/v1/stream/play?video_id={id}&audio_url={url}
 *     — Strictly-validated proxy to Python :5001 /stream-youtube-audio.
 *
 * Security requirements enforced here:
 *   - videoId validated against YouTube ID pattern.
 *   - audio_url validated against trusted domain list (*.googlevideo.com).
 *   - Arbitrary URLs are rejected with 403.
 *   - Internal Python service address is NEVER sent to Android.
 *   - Byte-range requests preserved for ExoPlayer seeking.
 *
 * Cache semantics:
 *   - Stream extraction cache is owned by Python :5001 (ThreadSafeTTLCache, ~2.5h TTL).
 *   - Node is responsible for routing and security only.
 *   - Do NOT duplicate the extraction cache here.
 *
 * Stream URL ephemerality:
 *   - /stream/resolve response tells Android to use /api/v1/stream/play
 *   - The play URL is transient — Android must not persist it.
 */

const express = require('express');
const rateLimit = require('express-rate-limit');
const { validateVideoId, validateYouTubeUrl, validateAudioUrl } = require('../stream/streamValidator');
const { Errors } = require('../errors/errors');
const logger = require('../utils/logger');
const config = require('../config/env');

const { AudioSourceResolver } = require('../stream/AudioSourceResolver');
const asyncHandler = require('../middleware/asyncHandler');

const router = express.Router();
const defaultResolver = new AudioSourceResolver();

const resolveLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: config.streamResolveRateLimitMax,
  standardHeaders: true,
  legacyHeaders: false,
  message: Errors.rateLimited().toJSON(),
  skip: () => config.isDev(),
});

// ---------------------------------------------------------------------------
// GET /api/v1/stream/resolve?url={youtube-url}&quality={STANDARD|HIGH}&title=...&artist=...&duration=...
//
// Resolves an audio stream descriptor according to quality preferences:
// - STANDARD / AUTO: YouTube baseline (Opus in WebM, ~160 kbps)
// - HIGH / VERY_HIGH: Opportunistic JioSaavn (~320 kbps AAC in MP4) with YouTube fallback
// ---------------------------------------------------------------------------
router.get('/resolve', resolveLimiter, asyncHandler(async (req, res, next) => {
  const t0 = Date.now();
  const rawUrl = (req.query.url || '').trim();
  let videoId = (req.query.video_id || '').trim();

  if (rawUrl) {
    const urlCheck = validateYouTubeUrl(rawUrl);
    if (!urlCheck.valid) {
      logger.warn('[STREAM/RESOLVE] Invalid URL', { url: rawUrl, reason: urlCheck.reason });
      return res.status(400).json(Errors.invalidRequest(urlCheck.reason).toJSON());
    }
    videoId = urlCheck.videoId;
  } else if (videoId) {
    const idCheck = validateVideoId(videoId);
    if (!idCheck.valid) {
      logger.warn('[STREAM/RESOLVE] Invalid videoId', { videoId, reason: idCheck.reason });
      return res.status(400).json(Errors.invalidRequest(idCheck.reason).toJSON());
    }
  } else {
    logger.warn('[STREAM/RESOLVE] Missing URL or video_id');
    return res.status(400).json(Errors.invalidRequest('Either url or video_id is required').toJSON());
  }

  const requestedQuality = (req.query.quality || 'STANDARD').trim();
  const title = (req.query.title || '').trim();
  const artist = (req.query.artist || '').trim();
  const duration = req.query.duration ? parseInt(req.query.duration, 10) : 0;

  const target = {
    videoId,
    title,
    artist,
    duration,
  };

  try {
    const source = await defaultResolver.resolve(target, requestedQuality);
    if (!source || !source.streamUrl) {
      logger.warn('[STREAM/RESOLVE] Stream unavailable from all providers', { videoId, requestedQuality });
      return next(Errors.streamUnavailable(`Audio extraction failed for ${videoId}`));
    }

    logger.info('[STREAM/RESOLVE] Resolved', {
      videoId,
      provider: source.provider,
      bitrate: source.bitrate,
      qualityTier: source.qualityTier,
      latencyMs: Date.now() - t0,
    });

    res.json({
      audio_url: source.streamUrl,
      trackId: videoId,
      format: source.format,
      codec: source.codec,
      bitrate: source.bitrate,
      qualityTier: source.qualityTier,
      provider: source.provider,
      isDirect: source.isDirect,
      expiresAt: source.expiresAt,
    });
  } catch (err) {
    if (err.name === 'TimeoutError' || err.name === 'AbortError') {
      logger.warn('[STREAM/RESOLVE] Resolution timeout', { videoId });
      return next(Errors.streamUnavailable('Stream resolution timed out'));
    }
    logger.error('[STREAM/RESOLVE] Unexpected resolution error', { videoId, error: err.message });
    next(Errors.internal('Stream resolution failed'));
  }
}));

// ---------------------------------------------------------------------------
// GET /api/v1/stream/play?video_id={id}&audio_url={url}
//
// Strictly-validated proxy that streams audio bytes from Python :5001.
// Preserves Range requests for ExoPlayer seeking.
// MUST NOT become an open proxy — URL is fully validated before proxying.
// ---------------------------------------------------------------------------

// The proxy middleware is applied per-request after validation
// We use a manual pipe approach to control validation before proxying.

router.get('/play', async (req, res, next) => {
  const videoId   = (req.query.video_id  || '').trim();
  const audioUrl  = (req.query.audio_url || '').trim();

  // Security: validate videoId
  const idCheck = validateVideoId(videoId);
  if (!idCheck.valid) {
    logger.warn('[STREAM/PLAY] Invalid videoId', { videoId, reason: idCheck.reason });
    return res.status(400).json(Errors.invalidRequest(`Invalid stream request: ${idCheck.reason}`).toJSON());
  }

  // Security: validate audio_url — reject arbitrary/untrusted URLs
  const urlCheck = validateAudioUrl(audioUrl);
  if (!urlCheck.valid) {
    logger.warn('[STREAM/PLAY] Rejected audio_url', { videoId, reason: urlCheck.reason });
    return res.status(403).json(Errors.forbidden('Stream URL is not from a trusted provider').toJSON());
  }

  // Proxy validated request to Python :5001 /stream-youtube-audio
  // Build internal Python stream URL
  const pythonStreamUrl = new URL(`${config.pythonAudioUrl}/stream-youtube-audio`);
  pythonStreamUrl.searchParams.set('video_id', videoId);
  // Forward the full audio_url to Python's stream endpoint as-is (it expects it)
  pythonStreamUrl.searchParams.set('audio_url', audioUrl);

  // F-01: Request-scoped cancellation state.
  // A single `terminated` flag guards against double-cleanup races.
  // `reader` is assigned once the upstream body is available.
  let reader = null;
  let terminated = false;
  const abortController = new AbortController();

  const cleanup = () => {
    if (terminated) return;
    terminated = true;
    abortController.abort();
    if (reader) reader.cancel('client_disconnected').catch(() => {});
  };

  // Detect premature client disconnect (skip, seek, player close, network drop).
  // res.on('close') fires for BOTH normal completion and client abort.
  // We distinguish them via res.writableEnded:
  //   true  → normal end()  → no action needed
  //   false → premature     → cancel upstream reader & abort fetch
  res.on('close', () => {
    if (!res.writableEnded) {
      logger.debug('[STREAM/PLAY] Client disconnected mid-stream', { videoId });
      cleanup();
    }
  });

  // Waits for the response writable stream to drain (emit 'drain') or for the
  // client to disconnect (emit 'close'). Returns true if drained normally,
  // false if the client disconnected while we were waiting.
  // Listener cleanup is always guaranteed to prevent leaks.
  const waitForDrainOrClose = () =>
    new Promise((resolve) => {
      const onDrain = () => {
        res.removeListener('close', onClose);
        resolve(true);
      };
      const onClose = () => {
        res.removeListener('drain', onDrain);
        resolve(false);
      };
      res.once('drain', onDrain);
      res.once('close', onClose);
    });

  try {
    const fetchHeaders = { 'User-Agent': 'SonaraBackend/1.0' };
    // Preserve Range header for ExoPlayer seeking
    if (req.headers['range']) {
      fetchHeaders['Range'] = req.headers['range'];
    }

    // Combine the resolve-timeout signal with the per-request abort signal so
    // that either a timeout OR a client disconnect aborts the upstream fetch.
    const signal = AbortSignal.any([
      abortController.signal,
      AbortSignal.timeout(config.streamResolveTimeoutMs),
    ]);

    const pythonRes = await fetch(pythonStreamUrl.toString(), {
      headers: fetchHeaders,
      signal,
    });

    if (!pythonRes.ok && pythonRes.status !== 206) {
      logger.warn('[STREAM/PLAY] Python stream error', { videoId, status: pythonRes.status });
      return next(Errors.streamUnavailable('Audio stream unavailable'));
    }

    // Forward status and relevant headers
    res.status(pythonRes.status);
    const forwardHeaders = ['content-type', 'content-length', 'content-range', 'accept-ranges'];
    for (const header of forwardHeaders) {
      const value = pythonRes.headers.get(header);
      if (value) res.setHeader(header, value);
    }

    logger.debug('[STREAM/PLAY] Streaming', { videoId, status: pythonRes.status });

    // Stream bytes with backpressure and disconnect awareness.
    // - res.write() returns false when the internal send buffer is full.
    //   We must wait for 'drain' before writing the next chunk to avoid
    //   unbounded memory growth.
    // - If the client disconnects while we are waiting for drain, we abort
    //   immediately so we stop consuming Python/upstream resources.
    reader = pythonRes.body.getReader();
    const pump = async () => {
      while (true) {
        // If cleanup() was already called (disconnect raced ahead of the loop),
        // stop without writing more data.
        if (terminated) return;

        const { done, value } = await reader.read();

        if (done) {
          // Normal end-of-stream — complete the response.
          res.end();
          return;
        }

        if (terminated) return;

        const flushed = res.write(Buffer.from(value));

        // Honour backpressure: if the OS send buffer is full, wait for drain.
        // Also bail out early if the client disconnects while waiting.
        if (!flushed) {
          const drained = await waitForDrainOrClose();
          if (!drained || terminated) return;
        }
      }
    };
    await pump();

  } catch (err) {
    // After headers have been sent, the HTTP response is already committed —
    // we MUST NOT call next() to attempt a JSON error response (that would
    // cause ERR_HTTP_HEADERS_SENT).  Destroy the socket and log instead.
    if (res.headersSent) {
      if (err.name !== 'AbortError') {
        logger.error('[STREAM/PLAY] Mid-stream error (headers sent)', { videoId, error: err.message });
      }
      cleanup();
      if (!res.destroyed) res.destroy();
      return;
    }

    // Headers not yet sent — safe to return an error response.
    if (err.name === 'TimeoutError') {
      logger.warn('[STREAM/PLAY] Upstream fetch timed out', { videoId });
      return next(Errors.streamUnavailable('Stream timed out'));
    }
    if (err.name === 'AbortError') {
      // Aborted by cleanup() due to client disconnect before fetch completed —
      // response has not started, so there is nothing to send.
      logger.debug('[STREAM/PLAY] Fetch aborted (client disconnected before stream start)', { videoId });
      return;
    }
    logger.error('[STREAM/PLAY] Unexpected error', { videoId, error: err.message });
    next(Errors.internal('Stream failed'));
  }
});

module.exports = router;
