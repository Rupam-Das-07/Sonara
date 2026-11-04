'use strict';

/**
 * videoStatusService.js — Minimal YouTube Data API v3 status verification.
 *
 * Only the normalized result is exposed to callers. Upstream response bodies,
 * request URLs, and credentials are deliberately kept out of errors and logs.
 */

const config = require('../config/env');
const { Errors } = require('../errors/errors');
const { validateVideoId } = require('../stream/streamValidator');

const YOUTUBE_VIDEOS_URL = 'https://www.googleapis.com/youtube/v3/videos';
const YOUTUBE_REQUEST_TIMEOUT_MS = config.youtubeDataApiTimeoutMs;

const MADE_FOR_KIDS_STATUS = Object.freeze({
  TRUE: 'MADE_FOR_KIDS',
  FALSE: 'NOT_MADE_FOR_KIDS',
  UNKNOWN: 'UNKNOWN',
});

const EMBEDDABILITY_STATUS = Object.freeze({
  TRUE: 'EMBEDDABLE',
  FALSE: 'NOT_EMBEDDABLE',
  UNKNOWN: 'UNKNOWN',
});

/**
 * Maps YouTube's optional status.madeForKids value to the backend contract.
 * Missing, null, and malformed values are intentionally treated as unknown.
 *
 * @param {unknown} value
 * @returns {'MADE_FOR_KIDS'|'NOT_MADE_FOR_KIDS'|'UNKNOWN'}
 */
function mapMadeForKidsStatus(value) {
  if (value === true) return MADE_FOR_KIDS_STATUS.TRUE;
  if (value === false) return MADE_FOR_KIDS_STATUS.FALSE;
  return MADE_FOR_KIDS_STATUS.UNKNOWN;
}

function mapEmbeddabilityStatus(value) {
  if (value === true) return EMBEDDABILITY_STATUS.TRUE;
  if (value === false) return EMBEDDABILITY_STATUS.FALSE;
  return EMBEDDABILITY_STATUS.UNKNOWN;
}

function isTimeoutError(error, controller) {
  return controller.signal.aborted || error?.name === 'TimeoutError';
}

/**
 * Extracts only the documented Google API error reason. The body is never
 * included in an error, log entry, or client response.
 *
 * @param {Response} response
 * @returns {Promise<string|null>}
 */
async function readUpstreamReason(response) {
  try {
    const body = await response.json();
    const errors = Array.isArray(body?.error?.errors) ? body.error.errors : [];
    const reason = errors.find((item) => typeof item?.reason === 'string')?.reason;
    return reason || null;
  } catch (error) {
    // Preserve cancellation so the caller can distinguish a bounded timeout
    // from a non-JSON error payload.
    if (error?.name === 'AbortError') throw error;
    return null;
  }
}

function mapUpstreamStatus(status, reason) {
  if (status === 403 && reason === 'quotaExceeded') {
    return Errors.youtubeQuotaExceeded();
  }
  if (status === 429) {
    return Errors.youtubeRateLimited();
  }
  return Errors.youtubeUpstreamFailure();
}

/**
 * Reads a YouTube video's made-for-kids and embeddability metadata.
 * Embeddability metadata does not guarantee playback.
 *
 * @param {string} videoId - An 11-character YouTube video ID.
 * @returns {Promise<{videoId: string, madeForKidsStatus: string, embeddabilityStatus: string}>}
 * @throws {SonaraBackendError} Sanitized, categorized failures.
 */
async function getVideoStatus(videoId) {
  const idCheck = validateVideoId(videoId);
  if (!idCheck.valid) {
    throw Errors.invalidVideoId('videoId must be an 11-character YouTube video ID');
  }

  const apiKey = config.youtubeDataApiKey;
  if (!apiKey) {
    throw Errors.youtubeConfigMissing();
  }

  const url = new URL(YOUTUBE_VIDEOS_URL);
  url.searchParams.set('part', 'id,status');
  url.searchParams.set('id', videoId);
  url.searchParams.set('key', apiKey);

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), YOUTUBE_REQUEST_TIMEOUT_MS);

  let response;
  try {
    response = await fetch(url.toString(), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
  } catch (error) {
    clearTimeout(timeout);
    if (isTimeoutError(error, controller)) {
      throw Errors.youtubeTimeout();
    }
    throw Errors.youtubeNetworkFailure();
  }

  if (!response.ok) {
    try {
      const reason = await readUpstreamReason(response);
      if (controller.signal.aborted) {
        throw Errors.youtubeTimeout();
      }
      throw mapUpstreamStatus(response.status, reason);
    } catch (error) {
      if (isTimeoutError(error, controller)) {
        throw Errors.youtubeTimeout();
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  let body;
  try {
    body = await response.json();
    if (controller.signal.aborted) {
      throw Errors.youtubeTimeout();
    }
  } catch (error) {
    clearTimeout(timeout);
    if (isTimeoutError(error, controller)) {
      throw Errors.youtubeTimeout();
    }
    throw Errors.youtubeMalformedResponse();
  }
  clearTimeout(timeout);

  if (!body || typeof body !== 'object' || !Array.isArray(body.items)) {
    throw Errors.youtubeMalformedResponse();
  }

  if (body.items.length === 0) {
    throw Errors.youtubeVideoNotFound();
  }

  let item;
  for (const candidate of body.items) {
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)
      || typeof candidate.id !== 'string' || candidate.id.length === 0) {
      throw Errors.youtubeMalformedResponse();
    }
    if (candidate.id === videoId) {
      if (item) throw Errors.youtubeMalformedResponse();
      item = candidate;
    }
  }

  if (!item) {
    throw Errors.youtubeVideoNotFound();
  }

  return {
    videoId,
    madeForKidsStatus: mapMadeForKidsStatus(item.status?.madeForKids),
    embeddabilityStatus: mapEmbeddabilityStatus(item.status?.embeddable),
  };
}

module.exports = {
  getVideoStatus,
  mapMadeForKidsStatus,
  mapEmbeddabilityStatus,
  MADE_FOR_KIDS_STATUS,
  EMBEDDABILITY_STATUS,
  YOUTUBE_VIDEOS_URL,
  YOUTUBE_REQUEST_TIMEOUT_MS,
};
