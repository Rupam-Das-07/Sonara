'use strict';

/**
 * errors.js — Structured error types for sonara-backend.
 *
 * All errors must produce a sanitized JSON response.
 * Stack traces, internal Python URLs, and yt-dlp details
 * must NEVER reach the client.
 */

class SonaraBackendError extends Error {
  /**
   * @param {string} code   - Machine-readable error code (e.g. PROVIDER_FAILURE)
   * @param {string} message - Human-readable message safe to send to Android
   * @param {number} status  - HTTP status code
   */
  constructor(code, message, status = 500) {
    super(message);
    this.name = 'SonaraBackendError';
    this.code = code;
    this.status = status;
  }

  toJSON() {
    return { error: { code: this.code, message: this.message } };
  }
}

const Errors = {
  invalidRequest: (msg = 'Invalid request') =>
    new SonaraBackendError('INVALID_REQUEST', msg, 400),

  notFound: (msg = 'Resource not found') =>
    new SonaraBackendError('NOT_FOUND', msg, 404),

  forbidden: (msg = 'Forbidden') =>
    new SonaraBackendError('FORBIDDEN', msg, 403),

  rateLimited: (msg = 'Rate limit exceeded. Please try again shortly.') =>
    new SonaraBackendError('RATE_LIMITED', msg, 429),

  providerFailure: (msg = 'Upstream provider unavailable') =>
    new SonaraBackendError('PROVIDER_FAILURE', msg, 502),

  streamUnavailable: (msg = 'Unable to resolve audio stream') =>
    new SonaraBackendError('STREAM_UNAVAILABLE', msg, 502),

  youtubeConfigMissing: (msg = 'YouTube video verification is not configured') =>
    new SonaraBackendError('YOUTUBE_CONFIG_MISSING', msg, 503),

  invalidVideoId: (msg = 'Invalid YouTube video ID') =>
    new SonaraBackendError('INVALID_VIDEO_ID', msg, 400),

  youtubeVideoNotFound: (msg = 'YouTube video not found') =>
    new SonaraBackendError('YOUTUBE_VIDEO_NOT_FOUND', msg, 404),

  youtubeQuotaExceeded: (msg = 'YouTube API quota exceeded') =>
    new SonaraBackendError('YOUTUBE_QUOTA_EXCEEDED', msg, 429),

  youtubeRateLimited: (msg = 'YouTube API rate limit exceeded') =>
    new SonaraBackendError('YOUTUBE_RATE_LIMITED', msg, 429),

  youtubeUpstreamFailure: (msg = 'YouTube API unavailable') =>
    new SonaraBackendError('YOUTUBE_UPSTREAM_FAILURE', msg, 502),

  youtubeTimeout: (msg = 'YouTube API request timed out') =>
    new SonaraBackendError('YOUTUBE_TIMEOUT', msg, 504),

  youtubeNetworkFailure: (msg = 'Unable to reach YouTube API') =>
    new SonaraBackendError('YOUTUBE_NETWORK_ERROR', msg, 502),

  youtubeMalformedResponse: (msg = 'YouTube API returned an invalid response') =>
    new SonaraBackendError('YOUTUBE_MALFORMED_RESPONSE', msg, 502),

  internal: (msg = 'Internal server error') =>
    new SonaraBackendError('INTERNAL_ERROR', msg, 500),

  sessionNotFound: (msg = 'Streaming session not found or server restarted') =>
    new SonaraBackendError('SESSION_NOT_FOUND', msg, 404),

  sessionExpired: (msg = 'Streaming session has expired') =>
    new SonaraBackendError('SESSION_EXPIRED', msg, 410),
};

module.exports = { SonaraBackendError, Errors };
