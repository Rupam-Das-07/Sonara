'use strict';

/**
 * youtubeStreamProvider.js — Adapter for existing YouTube/yt-dlp stream pipeline.
 *
 * Calls Python :5001 /get-youtube-audio, validates upstream googlevideo URL,
 * and returns the controlled proxy path /api/v1/stream/play.
 *
 * This remains the unconditional baseline audio provider for Sonara.
 */

const config = require('../../config/env');
const { validateVideoId, validateAudioUrl } = require('../streamValidator');
const logger = require('../../utils/logger');

class YouTubeStreamProvider {
  /**
   * @param {object} [options]
   * @param {function} [options.fetchFn] - Custom fetch for testing
   * @param {string} [options.pythonAudioUrl]
   * @param {number} [options.timeoutMs]
   */
  constructor(options = {}) {
    this.fetch = options.fetchFn || globalThis.fetch;
    this.pythonAudioUrl = options.pythonAudioUrl || config.pythonAudioUrl;
    this.timeoutMs = options.timeoutMs || config.streamResolveTimeoutMs || 8000;
  }

  /**
   * Resolves a YouTube stream for a canonical track.
   *
   * @param {object} target - { videoId } or string videoId
   * @returns {Promise<object|null>} ResolvedSource or null on failure
   */
  async resolve(target) {
    const videoId = typeof target === 'string' ? target : target?.videoId;
    const idCheck = validateVideoId(videoId);
    if (!idCheck.valid) {
      logger.warn(`[YOUTUBE_STREAM] Invalid videoId: ${videoId}`);
      return null;
    }

    const watchUrl = `https://www.youtube.com/watch?v=${videoId}`;
    const pythonUrl = `${this.pythonAudioUrl}/get-youtube-audio?url=${encodeURIComponent(watchUrl)}`;

    try {
      const pythonRes = await this.fetch(pythonUrl, {
        signal: AbortSignal.timeout(this.timeoutMs),
      });

      if (!pythonRes.ok) {
        logger.warn(`[YOUTUBE_STREAM] Python service error ${pythonRes.status} for ${videoId}`);
        return null;
      }

      const data = await pythonRes.json();
      const rawAudioUrl = data.audio_url || '';
      if (!rawAudioUrl) {
        logger.warn(`[YOUTUBE_STREAM] Empty audio_url returned for ${videoId}`);
        return null;
      }

      let upstreamAudioUrl = rawAudioUrl;
      if (rawAudioUrl.includes('audio_url=')) {
        const match = rawAudioUrl.match(/[?&]audio_url=([^&]+)/);
        if (match) {
          upstreamAudioUrl = decodeURIComponent(match[1]);
        }
      }

      const audioCheck = validateAudioUrl(upstreamAudioUrl);
      if (!audioCheck.valid) {
        logger.warn(`[YOUTUBE_STREAM] Untrusted audio URL for ${videoId}: ${audioCheck.reason}`);
        return null;
      }

      const playPath = `/api/v1/stream/play?video_id=${encodeURIComponent(videoId)}&audio_url=${encodeURIComponent(upstreamAudioUrl)}`;

      return {
        streamUrl: playPath,
        format: 'audio/webm',
        codec: 'opus',
        bitrate: 160,
        provider: 'youtube',
        isDirect: false,
        expiresAt: 0,
      };
    } catch (err) {
      logger.error(`[YOUTUBE_STREAM] Resolution failed for ${videoId}: ${err.message}`);
      return null;
    }
  }
}

module.exports = {
  YouTubeStreamProvider,
};
