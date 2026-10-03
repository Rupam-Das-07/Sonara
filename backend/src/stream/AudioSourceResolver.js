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

// Stage C-1A, Part B — STANDARD YouTube stream-descriptor cache.
// STANDARD/AUTO previously bypassed all caching and re-ran the full
// Node -> Python -> yt-dlp resolution on every request. This bounded cache memoizes
// the resolved STANDARD *descriptor* per videoId so repeated requests avoid the
// Node->Python round-trip. It caches ONLY the small stream descriptor — never audio
// bytes — and strictly honours the real upstream stream expiry (the googlevideo
// `expire` embedded in the proxy streamUrl) minus a safety margin, so an expired URL
// is never served.
//
// Bound: 1000 entries — intentionally matched to the sibling match/negative caches
// above; the keyspace is videoIds and each entry is a tiny metadata object, so this
// is a conservative, finite bound (not the generic 2000).
// TTL: a 2h absolute-retention backstop; the real per-entry expiry (below) is usually
// the tighter limit. When the real expiry cannot be determined, a 15-minute bounded
// fallback is used — the contract already permits a bounded fallback (Python falls
// back to +2.5h, the Android cache to a 15-minute default) rather than caching forever.
const STANDARD_CACHE_MAX_ENTRIES = 1000;
const STANDARD_CACHE_MAX_TTL_MS = 2 * 60 * 60 * 1000;      // absolute retention backstop (2h)
const STANDARD_FALLBACK_TTL_MS = 15 * 60 * 1000;           // used only when real expiry is undeterminable
const STANDARD_EXPIRY_SAFETY_MARGIN_MS = 5 * 60 * 1000;    // never serve within 5 min of real upstream expiry

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
    // Stage C-1A: bounded cache of resolved STANDARD YouTube descriptors (metadata only).
    // Per-entry freshness is enforced against the real upstream expiry in resolve();
    // the BoundedCache ttlMs is an additional absolute-retention backstop.
    this.standardCache = new BoundedCache({ maxSize: STANDARD_CACHE_MAX_ENTRIES, ttlMs: STANDARD_CACHE_MAX_TTL_MS });
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
   * Parses the real upstream stream expiry (epoch ms) from a resolved descriptor's
   * proxy streamUrl. The proxy path embeds the googlevideo URL in its `audio_url`
   * query param, and that URL carries an `expire=<unix-seconds>` field.
   * Returns null when it cannot be safely determined (caller then applies a bounded
   * fallback TTL rather than caching indefinitely).
   *
   * @param {string} streamUrl
   * @returns {number|null}
   */
  _parseUpstreamExpiryMs(streamUrl) {
    if (typeof streamUrl !== 'string') return null;
    const audioMatch = /[?&]audio_url=([^&]+)/.exec(streamUrl);
    if (!audioMatch) return null;
    let upstream;
    try {
      upstream = decodeURIComponent(audioMatch[1]);
    } catch {
      return null;
    }
    const expMatch = /[?&]expire=(\d+)/.exec(upstream);
    if (!expMatch) return null;
    const expSeconds = parseInt(expMatch[1], 10);
    if (!Number.isFinite(expSeconds) || expSeconds <= 0) return null;
    return expSeconds * 1000;
  }

  /**
   * Returns a cached STANDARD descriptor only if it is still fresh with respect to
   * the real upstream expiry (minus the safety margin). An expired entry is evicted
   * and treated as a miss, so an expired stream URL is never served.
   *
   * @param {string} cacheKey
   * @returns {object|null}
   */
  _getFreshStandardSource(cacheKey) {
    const entry = this.standardCache.get(cacheKey);
    if (!entry) return null;
    if (Date.now() >= entry.effectiveExpiryMs) {
      this.standardCache.delete(cacheKey);
      return null;
    }
    return entry.source;
  }

  /**
   * Caches a resolved STANDARD descriptor (metadata only). The effective expiry is
   * min(realUpstreamExpiry - safetyMargin, now + maxRetention). When the real expiry
   * is undeterminable, a bounded fallback TTL is used. Entries whose effective expiry
   * is already in the past are not cached. Callers must only pass a valid, non-null
   * descriptor with a streamUrl (failures/malformed results are never cached).
   *
   * @param {string} cacheKey
   * @param {object} source
   */
  _cacheStandardSource(cacheKey, source) {
    const now = Date.now();
    const maxRetentionExpiryMs = now + STANDARD_CACHE_MAX_TTL_MS;
    const upstreamExpiryMs = this._parseUpstreamExpiryMs(source.streamUrl);

    let effectiveExpiryMs;
    if (upstreamExpiryMs !== null) {
      effectiveExpiryMs = Math.min(upstreamExpiryMs - STANDARD_EXPIRY_SAFETY_MARGIN_MS, maxRetentionExpiryMs);
    } else {
      effectiveExpiryMs = Math.min(now + STANDARD_FALLBACK_TTL_MS, maxRetentionExpiryMs);
    }

    // Never cache something that is already (effectively) expired.
    if (effectiveExpiryMs <= now) return;

    this.standardCache.set(cacheKey, { source: { ...source }, effectiveExpiryMs });
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
      const cacheKey = `STANDARD:${videoId}`;

      // Serve a still-fresh cached descriptor, avoiding the Node->Python round-trip.
      const cachedSource = this._getFreshStandardSource(cacheKey);
      if (cachedSource) {
        logger.debug(`[RESOLVER] STANDARD descriptor cache hit for ${videoId}`);
        return { ...cachedSource };
      }

      logger.debug(`[RESOLVER] Resolving standard baseline for ${videoId}`);
      const source = await this.youtubeProvider.resolve(target);
      if (source && source.streamUrl) {
        source.qualityTier = 'STANDARD';
        // Cache only a valid descriptor; expiry safety is enforced inside.
        this._cacheStandardSource(cacheKey, source);
        return source;
      }
      // Unresolved (null) or malformed (missing streamUrl): preserve prior behaviour
      // and never cache a failure.
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
    this.standardCache.clear();
  }
}

module.exports = {
  AudioSourceResolver,
};
