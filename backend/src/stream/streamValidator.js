'use strict';

/**
 * streamValidator.js — Security validation for stream endpoints.
 *
 * CRITICAL: The stream proxy MUST NOT become an open proxy.
 *
 * Rules enforced:
 *   1. videoId must match the YouTube video ID format.
 *   2. audio_url must originate from a trusted upstream domain.
 *   3. Arbitrary external URLs are explicitly rejected.
 *   4. Internal Python service addresses are never returned to clients.
 *
 * Trusted upstream domains (source: observed yt-dlp output patterns):
 *   - *.googlevideo.com   — Google's primary video/audio CDN
 *   - rr*.googlevideo.com — Regional routing subdomains
 *
 * This list is intentionally conservative. Adding domains requires
 * evidence from yt-dlp source or stream interception.
 */

const VIDEO_ID_PATTERN = /^[a-zA-Z0-9_-]{11}$/;

/**
 * Trusted upstream hostname suffixes.
 * Only URLs whose hostname ends with one of these suffixes are permitted.
 */
const TRUSTED_UPSTREAM_SUFFIXES = [
  '.googlevideo.com',
];

/**
 * Validates a YouTube video ID.
 * @param {string} videoId
 * @returns {{ valid: boolean, reason?: string }}
 */
function validateVideoId(videoId) {
  if (!videoId || typeof videoId !== 'string') {
    return { valid: false, reason: 'videoId is required' };
  }
  if (!VIDEO_ID_PATTERN.test(videoId)) {
    return { valid: false, reason: 'videoId must be an 11-character YouTube video ID' };
  }
  return { valid: true };
}

/**
 * Validates an audio_url to prevent open-proxy abuse.
 * Only allows URLs from the trusted upstream domain list.
 *
 * @param {string} audioUrl - The raw audio_url received from the Python service
 * @returns {{ valid: boolean, reason?: string }}
 */
function validateAudioUrl(audioUrl) {
  if (!audioUrl || typeof audioUrl !== 'string') {
    return { valid: false, reason: 'audio_url is required' };
  }

  let parsed;
  try {
    parsed = new URL(audioUrl);
  } catch {
    return { valid: false, reason: 'audio_url is not a valid URL' };
  }

  // Must use HTTPS (Google Video CDN always uses HTTPS)
  if (parsed.protocol !== 'https:') {
    return { valid: false, reason: 'audio_url must use HTTPS' };
  }

  const hostname = parsed.hostname.toLowerCase();
  const isTrusted = TRUSTED_UPSTREAM_SUFFIXES.some(suffix => hostname.endsWith(suffix));

  if (!isTrusted) {
    return {
      valid: false,
      reason: `audio_url hostname is not from a trusted provider`,
    };
  }

  return { valid: true };
}

/**
 * Validates a YouTube watch URL used to trigger stream resolution.
 * @param {string} url
 * @returns {{ valid: boolean, videoId?: string, reason?: string }}
 */
function validateYouTubeUrl(url) {
  if (!url || typeof url !== 'string') {
    return { valid: false, reason: 'url is required' };
  }

  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return { valid: false, reason: 'url is not a valid URL' };
  }

  const isYoutubeDomain =
    parsed.hostname === 'www.youtube.com' ||
    parsed.hostname === 'youtube.com' ||
    parsed.hostname === 'youtu.be';

  if (!isYoutubeDomain) {
    return { valid: false, reason: 'url must be a YouTube URL' };
  }

  const videoId = parsed.searchParams.get('v') || parsed.pathname.slice(1);
  const idCheck = validateVideoId(videoId);
  if (!idCheck.valid) {
    return { valid: false, reason: `Invalid video ID in URL: ${idCheck.reason}` };
  }

  return { valid: true, videoId };
}

module.exports = { validateVideoId, validateAudioUrl, validateYouTubeUrl };
