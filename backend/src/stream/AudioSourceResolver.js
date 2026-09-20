'use strict';

/**
 * AudioSourceResolver.js — Central audio source resolver for Sonara.
 *
 * Implements the approved Phase 3 quality policy:
 *
 *   AUTO / STANDARD:
 *     → YouTube baseline (Opus in WebM, ~160 kbps)
 *
 *   HIGH / VERY_HIGH:
 *     → Opportunistic JioSaavn (~320 kbps AAC in MP4)
 *     → On ANY failure / mismatch: unconditional fallback to YouTube baseline
 *
 * Invariant: YouTube remains the canonical identity and unconditional playback fallback.
 * Playback MUST NEVER fail merely because High Quality was requested.
 */

const { JioSaavnStreamProvider } = require('./providers/jiosaavnStreamProvider');
const { YouTubeStreamProvider } = require('./providers/youtubeStreamProvider');
const BoundedCache = require('../utils/BoundedCache');
const logger = require('../utils/logger');

// Cache TTLs
const MATCH_CACHE_TTL_MS = 2 * 60 * 60 * 1000;    // 2 hours
const NEGATIVE_CACHE_TTL_MS = 60 * 60 * 1000;       // 1 hour

class AudioSourceResolver {
  /**
   * @param {object} [options]
   * @param {object} [options.jiosaavnProvider]
   * @param {object} [options.youtubeProvider]
   */
  constructor(options = {}) {
    this.jiosaavnProvider = options.jiosaavnProvider || new JioSaavnStreamProvider();
    this.youtubeProvider = options.youtubeProvider || new YouTubeStreamProvider();

    // Cache for successful high-quality matches
    this.matchCache = new BoundedCache({ maxSize: 1000, ttlMs: MATCH_CACHE_TTL_MS });
    // Negative cache for tracks that failed JioSaavn matching
    this.negativeCache = new BoundedCache({ maxSize: 1000, ttlMs: NEGATIVE_CACHE_TTL_MS });
  }

  /**
   * Normalizes incoming quality parameter.
   * @param {string} quality
   * @returns {'STANDARD'|'HIGH'}
   */
  normalizeQuality(quality) {
    if (!quality || typeof quality !== 'string') return 'STANDARD';
    const upper = quality.trim().toUpperCase();
    if (upper === 'HIGH' || upper === 'VERY_HIGH') return 'HIGH';
    return 'STANDARD';
  }

  /**
   * Resolves audio stream according to target quality preference.
   *
   * @param {object} target - { videoId, title, artist, duration, durationMs }
   * @param {string} [requestedQuality='STANDARD'] - 'AUTO' | 'HIGH' | 'VERY_HIGH' | 'STANDARD'
   * @returns {Promise<object|null>} ResolvedSource or null if both providers fail
   */
  async resolve(target, requestedQuality = 'STANDARD') {
    if (!target || !target.videoId) {
      return null;
    }

    const qualityTier = this.normalizeQuality(requestedQuality);
    const videoId = target.videoId;

    // Path 1: STANDARD / AUTO -> direct YouTube baseline (0ms JioSaavn overhead)
    if (qualityTier === 'STANDARD') {
      logger.debug(`[RESOLVER] Resolving standard baseline for ${videoId}`);
      const source = await this.youtubeProvider.resolve(target);
      if (source) source.qualityTier = 'STANDARD';
      return source;
    }

    // Path 2: HIGH -> Opportunistic JioSaavn with YouTube fallback
    logger.debug(`[RESOLVER] Attempting High-Quality resolution for ${videoId}`);

    // Check match cache
    const cachedSource = this.matchCache.get(videoId);
    if (cachedSource) {
      logger.debug(`[RESOLVER] High-Quality match cache hit for ${videoId}`);
      return cachedSource;
    }

    // Check negative cache
    const isKnownMiss = this.negativeCache.get(videoId);
    if (isKnownMiss) {
      logger.debug(`[RESOLVER] Negative cache hit for ${videoId}; falling back to YouTube immediately`);
      const fallbackSource = await this.youtubeProvider.resolve(target);
      if (fallbackSource) fallbackSource.qualityTier = 'STANDARD';
      return fallbackSource;
    }

    // Attempt JioSaavn resolution if metadata is present
    let highQualitySource = null;
    if (target.title) {
      try {
        highQualitySource = await this.jiosaavnProvider.resolve(target);
      } catch (err) {
        logger.warn(`[RESOLVER] Unexpected JioSaavn provider error for ${videoId}: ${err.message}`);
        highQualitySource = null;
      }
    } else {
      logger.debug(`[RESOLVER] Target has no title metadata, skipping JioSaavn for ${videoId}`);
    }

    if (highQualitySource) {
      highQualitySource.qualityTier = 'HIGH';
      this.matchCache.set(videoId, highQualitySource);
      return highQualitySource;
    }

    // JioSaavn unavailable or no match -> record negative cache and fall back to YouTube
    logger.info(`[RESOLVER] High-Quality unavailable for ${videoId}; falling back to YouTube baseline`);
    this.negativeCache.set(videoId, true);

    const fallbackSource = await this.youtubeProvider.resolve(target);
    if (fallbackSource) {
      fallbackSource.qualityTier = 'STANDARD';
    }
    return fallbackSource;
  }

  clearCaches() {
    this.matchCache.clear();
    this.negativeCache.clear();
  }
}

module.exports = {
  AudioSourceResolver,
};
