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
const { sessionStore } = require('../videos/sessionStore');
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

  // Request-scoped cancellation and deadlines. Header acquisition is bounded;
  // established streams have a byte-inactivity timeout, not a total lifetime cap.
  let reader = null;
  let upstreamBody = null;
  let terminated = false;
  let disconnected = false;
  let completed = false;
  let timeout = null;
  let cancelledBody = false;
  let cancellation = Promise.resolve();
  const abortController = new AbortController();

  const clearDeadline = () => {
    clearTimeout(timeout);
    timeout = null;
  };
  const armDeadline = () => {
    clearDeadline();
    timeout = setTimeout(() => {
      abortController.abort(new DOMException('Stream timed out', 'TimeoutError'));
    }, config.streamResolveTimeoutMs);
  };

  const cleanup = () => {
    terminated = true;
    clearDeadline();
    abortController.abort();
    // A disconnect can precede fetch resolution. Re-check for a late body in
    // finally, but cancel each body only once and always consume rejections.
    const source = reader || upstreamBody;
    if (source && !cancelledBody) {
      cancelledBody = true;
      try {
        cancellation = Promise.resolve(source.cancel('stream_terminated')).catch(() => {});
      } catch (_) {
        // Cancellation must not mask the original failure.
      }
    }
  };

  // Detect premature client disconnect (skip, seek, player close, network drop).
  // res.on('close') fires for BOTH normal completion and client abort.
  // We distinguish them via res.writableEnded:
  //   true  → normal end()  → no action needed
  //   false → premature     → cancel upstream reader & abort fetch
  const onClientClose = () => {
    if (!res.writableEnded) {
      disconnected = true;
      logger.debug('[STREAM/PLAY] Client disconnected mid-stream', { videoId });
      cleanup();
    }
  };
  res.on('close', onClientClose);

  // Waits for the response writable stream to drain (emit 'drain') or for the
  // client to disconnect (emit 'close'). Returns true if drained normally,
  // false if the client disconnected while we were waiting.
  // Listener cleanup is always guaranteed to prevent leaks.
  const waitForDrainOrClose = () =>
    new Promise((resolve) => {
      if (terminated || res.destroyed) return resolve(false);
      const finish = (drained) => {
        res.removeListener('drain', onDrain);
        res.removeListener('close', onClose);
        resolve(drained);
      };
      const onDrain = () => {
        finish(true);
      };
      const onClose = () => {
        finish(false);
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

    // Clear the header timer as soon as fetch resolves: a healthy audio stream
    // may last arbitrarily longer than the resolution timeout.
    armDeadline();
    const pythonRes = await fetch(pythonStreamUrl.toString(), {
      headers: fetchHeaders,
      signal: abortController.signal,
    });
    clearDeadline();
    upstreamBody = pythonRes.body;
    if (terminated) return;

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
    armDeadline();
    const pump = async () => {
      while (true) {
        // If cleanup() was already called (disconnect raced ahead of the loop),
        // stop without writing more data.
        if (terminated) return;

        const { done, value } = await reader.read();
        if (terminated) return;

        if (done) {
          // Normal end-of-stream — complete the response.
          completed = true;
          res.end();
          return;
        }

        // Empty chunks are not progress and must not extend the deadline.
        if (!value.byteLength) continue;
        armDeadline();

        const flushed = res.write(Buffer.from(value));

        // Honour backpressure: if the OS send buffer is full, wait for drain.
        // Also bail out early if the client disconnects while waiting.
        if (!flushed) {
          // Deliberately not reading while the downstream buffer is full is
          // not an upstream stall. Resume a full inactivity window on drain.
          clearDeadline();
          const drained = await waitForDrainOrClose();
          if (!drained || terminated) return;
          armDeadline();
        }
      }
    };
    await pump();

  } catch (err) {
    // Disconnect cancellation is terminal, even if fetch/read rejects with a
    // transport-specific error instead of AbortError.
    if (disconnected) return;
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
    // Some body implementations report a generic AbortError rather than the
    // supplied abort reason. A first-byte timeout still needs an HTTP error.
    if (err.name === 'TimeoutError' || abortController.signal.reason?.name === 'TimeoutError') {
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
  } finally {
    clearDeadline();
    res.removeListener('close', onClientClose);
    if (!completed) cleanup();
    await cancellation;
    reader?.releaseLock?.();
  }
});


// ---------------------------------------------------------------------------
// GET & HEAD /api/v1/stream/video?sessionId=:sessionId&track=video|audio
//
// Session-based proxy for high-resolution DASH video and audio stream chunks.
// Supports:
//   - GET and HEAD methods
//   - Byte range requests (RFC 9110)
//   - Automatic backpressure and socket cleanup
//   - In-memory session binding (sessionId -> internal googlevideo URL)
//   - Domain validation against *.googlevideo.com
//   - Rejection of expired sessions (HTTP 410) or missing sessions (HTTP 404)
// ---------------------------------------------------------------------------

/**
 * Safely masks a sessionId for logging purposes (e.g. "c6faee...").
 * Prevents bearer credential leakage into log collectors.
 *
 * @param {string} id
 * @returns {string}
 */
function maskSessionId(id) {
  if (!id || typeof id !== 'string') {
    return '[none]';
  }
  return id.length > 8 ? `${id.slice(0, 6)}...` : '***';
}

const handleVideoProxy = async (req, res, next) => {
  const sessionId = (req.query.sessionId || '').trim();
  const track = (req.query.track || '').trim().toLowerCase();

  if (!sessionId) {
    return res.status(400).json(Errors.invalidRequest('sessionId is required').toJSON());
  }

  if (track !== 'video' && track !== 'audio') {
    return res.status(400).json(Errors.invalidRequest("track must be 'video' or 'audio'").toJSON());
  }

  // Session resolution
  const session = sessionStore.getSession(sessionId);
  if (!session) {
    const expired = sessionStore.isExpired(sessionId);
    const status = expired ? 410 : 404;
    const err = expired
      ? Errors.sessionExpired('Streaming session has expired')
      : Errors.sessionNotFound('Streaming session not found or server restarted');
    logger.warn('[STREAM/VIDEO] Session invalid or expired', { sessionId: maskSessionId(sessionId), track, status });
    return res.status(status).json(err.toJSON());
  }

  // Resolve target URL and headers from session
  let targetUrl;
  let upstreamHeaders;
  if (track === 'video') {
    targetUrl = session.videoUrl;
    upstreamHeaders = session.videoHeaders || {};
  } else {
    targetUrl = session.audioUrl;
    upstreamHeaders = session.audioHeaders || {};
  }

  // Domain security validation
  const urlCheck = validateAudioUrl(targetUrl);
  if (!urlCheck.valid) {
    logger.warn('[STREAM/VIDEO] Target URL failed domain validation', { sessionId: maskSessionId(sessionId), track });
    return res.status(403).json(Errors.forbidden('Stream URL is not from a trusted provider').toJSON());
  }

  // Range header validation & forwarding
  const fetchHeaders = {
    'User-Agent': upstreamHeaders['User-Agent'] || 'SonaraBackend/1.0',
    'Accept': '*/*',
  };

  const rawRange = req.headers['range'];
  if (rawRange) {
    const rangeMatch = /^bytes=(\d*)-(\d*)$/.exec(rawRange.trim());
    if (!rangeMatch) {
      logger.warn('[STREAM/VIDEO] Malformed Range header', { sessionId: maskSessionId(sessionId), track, range: rawRange });
      return res.status(416).json(Errors.invalidRequest('Malformed Range header').toJSON());
    }
    fetchHeaders['Range'] = rawRange.trim();
  }

  let reader = null;
  let upstreamBody = null;
  let terminated = false;
  let disconnected = false;
  let completed = false;
  let timeout = null;
  let cancelledBody = false;
  let cancellation = Promise.resolve();
  const abortController = new AbortController();

  const clearDeadline = () => {
    clearTimeout(timeout);
    timeout = null;
  };
  const armDeadline = () => {
    clearDeadline();
    timeout = setTimeout(() => {
      abortController.abort(new DOMException('Stream timed out', 'TimeoutError'));
    }, config.streamResolveTimeoutMs);
  };

  const cleanup = () => {
    terminated = true;
    clearDeadline();
    abortController.abort();
    const source = reader || upstreamBody;
    if (source && !cancelledBody) {
      cancelledBody = true;
      try {
        cancellation = Promise.resolve(source.cancel('stream_terminated')).catch(() => {});
      } catch (_) {}
    }
  };

  const onClientClose = () => {
    if (!res.writableEnded) {
      disconnected = true;
      logger.debug('[STREAM/VIDEO] Client disconnected mid-stream', { sessionId: maskSessionId(sessionId), track });
      cleanup();
    }
  };
  res.on('close', onClientClose);

  const waitForDrainOrClose = () =>
    new Promise((resolve) => {
      if (terminated || res.destroyed) return resolve(false);
      const finish = (drained) => {
        res.removeListener('drain', onDrain);
        res.removeListener('close', onClose);
        resolve(drained);
      };
      const onDrain = () => finish(true);
      const onClose = () => finish(false);
      res.once('drain', onDrain);
      res.once('close', onClose);
    });

  try {
    let currentUrl = targetUrl;
    let upstreamRes = null;
    let redirectCount = 0;
    const MAX_REDIRECTS = 2;

    while (true) {
      armDeadline();
      upstreamRes = await fetch(currentUrl, {
        method: req.method,
        headers: fetchHeaders,
        signal: abortController.signal,
        redirect: 'manual',
      });
      clearDeadline();
      if (terminated) return;

      // Handle 3xx redirects explicitly
      if (upstreamRes.status >= 300 && upstreamRes.status < 400) {
        if (upstreamRes.body) {
          try {
            upstreamRes.body.cancel().catch(() => {});
          } catch (_) {}
        }

        const location = upstreamRes.headers.get('location');
        if (!location) {
          logger.warn('[STREAM/VIDEO] Upstream redirect missing Location header', {
            sessionId: maskSessionId(sessionId),
            track,
            status: upstreamRes.status,
          });
          return next(Errors.streamUnavailable('Upstream redirect missing destination'));
        }

        redirectCount++;
        if (redirectCount > MAX_REDIRECTS) {
          logger.warn('[STREAM/VIDEO] Exceeded maximum upstream redirects', {
            sessionId: maskSessionId(sessionId),
            track,
            redirectCount,
          });
          return next(Errors.streamUnavailable('Exceeded maximum upstream redirects'));
        }

        let nextUrl;
        try {
          nextUrl = new URL(location, currentUrl).toString();
        } catch {
          logger.warn('[STREAM/VIDEO] Malformed redirect Location header', {
            sessionId: maskSessionId(sessionId),
            track,
          });
          return next(Errors.streamUnavailable('Invalid upstream redirect location'));
        }

        // Validate destination URL against trusted domain (*.googlevideo.com) and HTTPS
        const redirectCheck = validateAudioUrl(nextUrl);
        if (!redirectCheck.valid) {
          logger.warn('[STREAM/VIDEO] Upstream redirect destination untrusted', {
            sessionId: maskSessionId(sessionId),
            track,
            reason: redirectCheck.reason,
          });
          return res.status(403).json(Errors.forbidden('Upstream redirect to untrusted destination').toJSON());
        }

        currentUrl = nextUrl;
        continue;
      }

      break;
    }

    upstreamBody = upstreamRes.body;
    if (terminated) return;

    if (upstreamRes.status === 403) {
      logger.warn('[STREAM/VIDEO] Upstream returned 403 Forbidden', { sessionId: maskSessionId(sessionId), track });
      return next(Errors.streamUnavailable('Upstream media chunk forbidden or expired'));
    }

    if (upstreamRes.status === 410) {
      logger.warn('[STREAM/VIDEO] Upstream returned 410 Gone', { sessionId: maskSessionId(sessionId), track });
      return next(Errors.sessionExpired('Upstream media resource is no longer available'));
    }

    if (upstreamRes.status === 416) {
      res.status(416);
      const cr = upstreamRes.headers.get('content-range');
      if (cr) res.setHeader('content-range', cr);
      res.end();
      return;
    }

    if (!upstreamRes.ok && upstreamRes.status !== 206) {
      logger.warn('[STREAM/VIDEO] Upstream unexpected status', { sessionId: maskSessionId(sessionId), track, status: upstreamRes.status });
      return next(Errors.streamUnavailable(`Upstream stream error (${upstreamRes.status})`));
    }

    // Forward status and relevant headers
    res.status(upstreamRes.status);
    const forwardHeaders = ['content-type', 'content-length', 'content-range', 'accept-ranges'];
    for (const header of forwardHeaders) {
      const value = upstreamRes.headers.get(header);
      if (value) res.setHeader(header, value);
    }
    if (!res.getHeader('accept-ranges')) {
      res.setHeader('accept-ranges', 'bytes');
    }

    // HEAD request completes after headers are written
    if (req.method === 'HEAD') {
      completed = true;
      res.end();
      return;
    }

    if (!upstreamRes.body) {
      completed = true;
      res.end();
      return;
    }

    // Stream with backpressure and disconnect awareness
    reader = upstreamRes.body.getReader();
    armDeadline();
    const pump = async () => {
      while (true) {
        if (terminated) return;
        const { done, value } = await reader.read();
        if (terminated) return;
        if (done) {
          completed = true;
          res.end();
          return;
        }
        if (!value.byteLength) continue;
        armDeadline();

        const flushed = res.write(Buffer.from(value));
        if (!flushed) {
          clearDeadline();
          const drained = await waitForDrainOrClose();
          if (!drained || terminated) return;
          armDeadline();
        }
      }
    };
    await pump();

  } catch (err) {
    if (disconnected) return;
    if (res.headersSent) {
      if (err.name !== 'AbortError') {
        logger.error('[STREAM/VIDEO] Mid-stream error (headers sent)', { sessionId: maskSessionId(sessionId), track, error: err.message });
      }
      cleanup();
      if (!res.destroyed) res.destroy();
      return;
    }

    if (err.name === 'TimeoutError' || abortController.signal.reason?.name === 'TimeoutError') {
      logger.warn('[STREAM/VIDEO] Upstream fetch timed out', { sessionId: maskSessionId(sessionId), track });
      return next(Errors.streamUnavailable('Stream request timed out'));
    }
    if (err.name === 'AbortError') {
      logger.debug('[STREAM/VIDEO] Fetch aborted by client disconnect', { sessionId: maskSessionId(sessionId), track });
      return;
    }
    logger.error('[STREAM/VIDEO] Unexpected streaming error', { sessionId: maskSessionId(sessionId), track, error: err.message });
    next(Errors.internal('Streaming failed'));
  } finally {
    clearDeadline();
    res.removeListener('close', onClientClose);
    if (!completed) cleanup();
    await cancellation;
    reader?.releaseLock?.();
  }
};

router.get('/video', handleVideoProxy);
router.head('/video', handleVideoProxy);

module.exports = router;
