'use strict';

/**
 * highresService.js — High-resolution 8K discovery and session orchestration service.
 *
 * Responsibilities:
 * - Query the internal Python service (:5001 /get-youtube-video-highres).
 * - Validate upstream response contract and stream URLs.
 * - Resolve Expiry Pairing: ensure both video and audio expiry timestamps are valid,
 *   taking the earlier of the two as effective session expiry.
 * - Create a secure, opaque session in sessionStore.
 * - Return sanitized Android response contract (no raw signed googlevideo URLs,
 *   no internal request headers).
 */

const config = require('../config/env');
const { Errors } = require('../errors/errors');
const { validateVideoId, validateAudioUrl } = require('../stream/streamValidator');
const { sessionStore } = require('./sessionStore');
const logger = require('../utils/logger');

/**
 * Parses unix expiration timestamp from googlevideo URL.
 * Returns 0 if missing, non-numeric, or malformed. Never invents an offset.
 *
 * @param {string} urlStr
 * @returns {number}
 */
function extractUrlExpire(urlStr) {
  if (!urlStr || typeof urlStr !== 'string') {
    return 0;
  }
  try {
    const parsed = new URL(urlStr);
    const exp = parsed.searchParams.get('expire');
    if (exp && /^\d+$/.test(exp)) {
      return parseInt(exp, 10);
    }
  } catch {
    // Malformed URL
  }
  return 0;
}

/**
 * Validates and retrieves high-resolution video streams from internal Python service,
 * creating an opaque streaming session for genuine 8K sources.
 *
 * @param {string} videoId - 11-character YouTube video ID
 * @returns {Promise<Object>} Sanitized response contract safe for Android
 */
async function getHighResVideo(videoId) {
  const idCheck = validateVideoId(videoId);
  if (!idCheck.valid) {
    throw Errors.invalidVideoId(idCheck.reason);
  }

  const pythonUrl = new URL(`${config.pythonAudioUrl}/get-youtube-video-highres`);
  pythonUrl.searchParams.set('video_id', videoId);

  const abortController = new AbortController();
  const timeout = setTimeout(() => {
    abortController.abort(new DOMException('Highres resolution timeout', 'TimeoutError'));
  }, config.streamResolveTimeoutMs);

  let pythonRes;
  try {
    pythonRes = await fetch(pythonUrl.toString(), {
      signal: abortController.signal,
      headers: { 'Accept': 'application/json' },
    });
  } catch (err) {
    clearTimeout(timeout);
    if (err.name === 'TimeoutError' || abortController.signal.aborted) {
      logger.warn('[VIDEOS/HIGHRES] Python service timed out', { videoId });
      throw Errors.youtubeTimeout('High-resolution video extraction timed out');
    }
    logger.error('[VIDEOS/HIGHRES] Failed to connect to Python highres service', {
      videoId,
      error: err.message,
    });
    throw Errors.providerFailure('High-resolution video service is temporarily unavailable');
  } finally {
    clearTimeout(timeout);
  }

  if (!pythonRes.ok) {
    let detail = '';
    try {
      const errJson = await pythonRes.json();
      detail = errJson?.error || '';
    } catch {
      // Ignore body parsing error
    }

    logger.warn('[VIDEOS/HIGHRES] Python highres service returned error', {
      videoId,
      status: pythonRes.status,
      detail,
    });

    if (pythonRes.status === 400) {
      throw Errors.invalidRequest(detail || 'Invalid videoId for high-resolution extraction');
    }
    if (pythonRes.status === 422) {
      throw Errors.streamUnavailable(detail || 'Video is unavailable for high-resolution extraction');
    }
    throw Errors.providerFailure('High-resolution extraction failed upstream');
  }

  let data;
  try {
    data = await pythonRes.json();
  } catch (err) {
    logger.error('[VIDEOS/HIGHRES] Malformed JSON from Python service', { videoId, error: err.message });
    throw Errors.providerFailure('Invalid response from high-resolution service');
  }

  const now = Math.floor(Date.now() / 1000);

  // If Python reports no genuine 8K, return sanitized negative discovery
  if (!data || !data.hasHighRes) {
    return {
      hasHighRes: false,
      videoId,
      extractedAt: data?.extractedAt || now,
      expiresAt: data?.expiresAt || (now + 7200),
      video: null,
      audio: null,
    };
  }

  // Positive Discovery: validate presence and integrity of stream objects
  const rawVideo = data.video;
  const rawAudio = data.audio;

  if (!rawVideo || !rawAudio || !rawVideo.streamUrl || !rawAudio.streamUrl) {
    logger.warn('[VIDEOS/HIGHRES] Positive response missing video or audio streamUrl', { videoId });
    return {
      hasHighRes: false,
      videoId,
      extractedAt: now,
      expiresAt: now + 7200,
      video: null,
      audio: null,
    };
  }

  // Validate upstream hostnames against trusted CDN (.googlevideo.com)
  const vCheck = validateAudioUrl(rawVideo.streamUrl);
  const aCheck = validateAudioUrl(rawAudio.streamUrl);
  if (!vCheck.valid || !aCheck.valid) {
    logger.warn('[VIDEOS/HIGHRES] Stream URL rejected by domain validator', {
      videoId,
      videoValid: vCheck.valid,
      audioValid: aCheck.valid,
    });
    return {
      hasHighRes: false,
      videoId,
      extractedAt: now,
      expiresAt: now + 7200,
      video: null,
      audio: null,
    };
  }

  // Resolve Expiry Pairing: both video and audio expiry must be known and in the future
  const vExp = extractUrlExpire(rawVideo.streamUrl);
  const aExp = extractUrlExpire(rawAudio.streamUrl);

  if (vExp <= now || aExp <= now) {
    logger.warn('[VIDEOS/HIGHRES] Invalid or expired upstream stream expiry', {
      videoId,
      videoExpiresAt: vExp,
      audioExpiresAt: aExp,
      now,
    });
    return {
      hasHighRes: false,
      videoId,
      extractedAt: now,
      expiresAt: now + 7200,
      video: null,
      audio: null,
    };
  }

  // Effective session expiry is strictly the earlier of the two
  const effectiveExpiresAt = Math.min(vExp, aExp);

  // Store in secure sessionStore
  const session = sessionStore.createSession({
    videoId,
    videoUrl: rawVideo.streamUrl,
    audioUrl: rawAudio.streamUrl,
    videoHeaders: rawVideo.httpHeaders || {},
    audioHeaders: rawAudio.httpHeaders || {},
    videoExpiresAt: vExp,
    audioExpiresAt: aExp,
    effectiveExpiresAt,
  });

  // Construct sanitized Android response contract
  return {
    hasHighRes: true,
    videoId,
    sessionId: session.sessionId,
    extractedAt: data.extractedAt || now,
    expiresAt: effectiveExpiresAt,
    video: {
      itag: rawVideo.itag,
      width: rawVideo.width,
      height: rawVideo.height,
      fps: rawVideo.fps,
      codec: rawVideo.codec,
      mimeType: rawVideo.mimeType,
      bitrate: rawVideo.bitrate,
      duration: rawVideo.duration,
      initRange: rawVideo.initRange,
      indexRange: rawVideo.indexRange,
      streamUrl: `/api/v1/stream/video?sessionId=${session.sessionId}&track=video`,
    },
    audio: {
      itag: rawAudio.itag,
      codec: rawAudio.codec,
      mimeType: rawAudio.mimeType,
      bitrate: rawAudio.bitrate,
      duration: rawAudio.duration,
      initRange: rawAudio.initRange,
      indexRange: rawAudio.indexRange,
      streamUrl: `/api/v1/stream/video?sessionId=${session.sessionId}&track=audio`,
    },
  };
}

module.exports = {
  getHighResVideo,
  extractUrlExpire,
};
