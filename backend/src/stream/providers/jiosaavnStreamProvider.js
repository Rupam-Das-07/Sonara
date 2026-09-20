'use strict';

/**
 * jiosaavnStreamProvider.js — Isolated JioSaavn ~320 kbps AAC stream provider.
 *
 * Provides opportunistic high-quality audio streams (~320 kbps AAC in MP4 container)
 * for canonical YouTube tracks.
 *
 * Invariant: Any failure (network error, API change, mismatch, CDN 404) returns null,
 * allowing the caller (AudioSourceResolver) to fall back to the YouTube baseline.
 */

const { decryptDesEcb } = require('../../utils/des');
const { evaluateMatch } = require('../streamMatcher');
const logger = require('../../utils/logger');

const JIOSAAVN_API_URL = 'https://www.jiosaavn.com/api.php';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// Investigation-verified private DES key for URL-level obfuscation
const DES_KEY = Buffer.from('38346591', 'utf8');

const DEFAULT_SEARCH_TIMEOUT_MS = 2500;
const DEFAULT_PREFLIGHT_TIMEOUT_MS = 1500;

// Circuit Breaker constants
const CIRCUIT_FAILURE_THRESHOLD = 5;
const CIRCUIT_RESET_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

class JioSaavnStreamProvider {
  /**
   * @param {object} [options]
   * @param {function} [options.fetchFn] - Custom fetch for testing/mocking
   * @param {number} [options.searchTimeoutMs]
   * @param {number} [options.preflightTimeoutMs]
   */
  constructor(options = {}) {
    this.fetch = options.fetchFn || globalThis.fetch;
    this.searchTimeoutMs = options.searchTimeoutMs || DEFAULT_SEARCH_TIMEOUT_MS;
    this.preflightTimeoutMs = options.preflightTimeoutMs || DEFAULT_PREFLIGHT_TIMEOUT_MS;

    // Circuit Breaker state
    this.circuitState = 'CLOSED'; // 'CLOSED' | 'OPEN' | 'HALF-OPEN'
    this.consecutiveFailures = 0;
    this.circuitOpenedAt = 0;
  }

  /**
   * Evaluates circuit breaker state.
   * @returns {boolean} true if provider is allowed to execute requests
   */
  _isCircuitAvailable() {
    if (this.circuitState === 'CLOSED') return true;

    const now = Date.now();
    if (this.circuitState === 'OPEN') {
      if (now - this.circuitOpenedAt > CIRCUIT_RESET_TIMEOUT_MS) {
        logger.info('[JIOSAAVN_STREAM] Circuit transitioning from OPEN to HALF-OPEN (testing probe)');
        this.circuitState = 'HALF-OPEN';
        return true;
      }
      return false;
    }

    // HALF-OPEN: allow one probe request
    return true;
  }

  _recordSuccess() {
    if (this.circuitState !== 'CLOSED') {
      logger.info('[JIOSAAVN_STREAM] Circuit probe succeeded; resetting circuit to CLOSED');
    }
    this.circuitState = 'CLOSED';
    this.consecutiveFailures = 0;
    this.circuitOpenedAt = 0;
  }

  _recordFailure(isHardTransportFailure = true) {
    if (!isHardTransportFailure) return;

    this.consecutiveFailures++;
    if (this.circuitState === 'HALF-OPEN' || this.consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
      this.circuitState = 'OPEN';
      this.circuitOpenedAt = Date.now();
      logger.warn(`[JIOSAAVN_STREAM] Circuit tripped to OPEN (${this.consecutiveFailures} consecutive hard failures)`);
    }
  }

  /**
   * Decrypts encrypted_media_url to get raw CDN URL.
   * @param {string} encryptedUrl
   * @returns {string|null}
   */
  decryptUrl(encryptedUrl) {
    if (!encryptedUrl || typeof encryptedUrl !== 'string') return null;
    try {
      const cipherBuf = Buffer.from(encryptedUrl, 'base64');
      const decrypted = decryptDesEcb(cipherBuf, DES_KEY).toString('utf8');
      const cleanUrl = decrypted.trim();
      return cleanUrl.startsWith('http') ? cleanUrl : null;
    } catch (err) {
      logger.debug(`[JIOSAAVN_STREAM] Decryption failed: ${err.message}`);
      return null;
    }
  }

  /**
   * Rewrites lower-bitrate suffixes (_12, _48, _96, _160) to _320.mp4.
   * @param {string} url
   * @returns {string|null}
   */
  rewriteTo320(url) {
    if (!url || typeof url !== 'string') return null;
    if (url.includes('_320.mp4')) return url;

    // Pattern: _(12|48|96|160).mp4
    const rewritten = url.replace(/_(12|48|96|160)\.mp4(\?.*)?$/i, '_320.mp4$2');
    return rewritten.includes('_320.mp4') ? rewritten : null;
  }

  /**
   * Performs lightweight HTTP pre-flight validation on the CDN URL.
   * @param {string} cdnUrl
   * @returns {Promise<boolean>}
   */
  async validateCdnResource(cdnUrl) {
    if (!cdnUrl || !cdnUrl.startsWith('https://')) return false;

    try {
      const res = await this.fetch(cdnUrl, {
        method: 'HEAD',
        signal: AbortSignal.timeout(this.preflightTimeoutMs),
        headers: {
          'User-Agent': USER_AGENT,
        },
      });

      if (!res.ok && res.status !== 206) {
        logger.debug(`[JIOSAAVN_STREAM] Pre-flight HEAD failed with status ${res.status}`);
        return false;
      }

      // Check content length if available (must be reasonable audio size, > 500KB)
      const lengthHeader = res.headers.get('content-length');
      if (lengthHeader) {
        const length = parseInt(lengthHeader, 10);
        if (!isNaN(length) && length < 500_000) {
          logger.debug(`[JIOSAAVN_STREAM] Pre-flight HEAD content-length too small: ${length}`);
          return false;
        }
      }

      return true;
    } catch (err) {
      logger.debug(`[JIOSAAVN_STREAM] Pre-flight HEAD error: ${err.message}`);
      return false;
    }
  }

  /**
   * Resolves a ~320 kbps AAC stream for a canonical target track.
   *
   * @param {object} target - { videoId, title, artist, duration, durationMs }
   * @returns {Promise<object|null>} ResolvedSource or null on any mismatch/failure
   */
  async resolve(target) {
    if (!target || !target.videoId || !target.title) {
      return null;
    }

    if (!this._isCircuitAvailable()) {
      logger.debug('[JIOSAAVN_STREAM] Circuit is OPEN, bypassing JioSaavn');
      return null;
    }

    const query = `${target.title} ${target.artist || ''}`.trim();
    const searchUrl = `${JIOSAAVN_API_URL}?__call=search.getResults&_format=json&cc=in&p=1&n=5&q=${encodeURIComponent(query)}`;

    let candidates = [];
    try {
      const res = await this.fetch(searchUrl, {
        signal: AbortSignal.timeout(this.searchTimeoutMs),
        headers: {
          'User-Agent': USER_AGENT,
          'Accept': 'application/json',
        },
      });

      if (!res.ok) {
        logger.warn(`[JIOSAAVN_STREAM] Search API returned HTTP ${res.status}`);
        this._recordFailure(res.status >= 500);
        return null;
      }

      const text = await res.text();
      let data;
      try {
        data = JSON.parse(text);
      } catch (parseErr) {
        logger.debug(`[JIOSAAVN_STREAM] JSON parse failed: ${parseErr.message}`);
        this._recordFailure(false);
        return null;
      }

      candidates = Array.isArray(data.results) ? data.results : (Array.isArray(data) ? data : []);
      this._recordSuccess();
    } catch (err) {
      const isHardFailure = err.name === 'TimeoutError' || err.name === 'AbortError' || err.code === 'ECONNREFUSED';
      logger.debug(`[JIOSAAVN_STREAM] Search fetch error: ${err.message}`);
      this._recordFailure(isHardFailure);
      return null;
    }

    if (candidates.length === 0) {
      logger.debug(`[JIOSAAVN_STREAM] No candidates found for "${query}"`);
      return null;
    }

    // Match candidate using 5-point quality gate
    for (const candidate of candidates) {
      const matchVerdict = evaluateMatch(target, candidate);
      if (!matchVerdict.ok) {
        logger.debug(`[JIOSAAVN_STREAM] Candidate rejected: ${matchVerdict.reason}`);
        continue;
      }

      // Decrypt URL
      const encUrl = candidate.encrypted_media_url || candidate.more_info?.encrypted_media_url;
      const decryptedUrl = this.decryptUrl(encUrl);
      if (!decryptedUrl) continue;

      // Rewrite to 320 kbps
      const rewrittenUrl = this.rewriteTo320(decryptedUrl);
      if (!rewrittenUrl) continue;

      // Validate CDN stream accessibility
      const isValidCdn = await this.validateCdnResource(rewrittenUrl);
      if (!isValidCdn) {
        logger.debug(`[JIOSAAVN_STREAM] CDN validation failed for ${rewrittenUrl}`);
        continue;
      }

      logger.info(`[JIOSAAVN_STREAM] High-Quality stream resolved for ${target.videoId} (delta: ${matchVerdict.deltaSeconds}s)`);
      return {
        streamUrl: rewrittenUrl,
        format: 'audio/mp4',
        codec: 'aac',
        bitrate: 320,
        provider: 'jiosaavn',
        isDirect: true,
        expiresAt: 0, // Unknown/unobserved expiry per Phase 2 Correction Pass
      };
    }

    return null;
  }
}

module.exports = {
  JioSaavnStreamProvider,
  CIRCUIT_FAILURE_THRESHOLD,
  CIRCUIT_RESET_TIMEOUT_MS,
};
