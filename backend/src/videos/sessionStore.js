'use strict';

/**
 * sessionStore.js — In-memory, bounded session storage for high-resolution video streaming.
 *
 * Requirements:
 * - Server-side only: binds opaque sessionId to internal video and audio stream URLs.
 * - Enforces effective expiry: earlier of video and audio upstream expiry timestamps.
 * - Rejects expired sessions (HTTP 410).
 * - Bounded capacity: max 500 sessions, evicts expired or least recently accessed (LRU) entries on saturation.
 * - Thread-safe within Node's event loop; handles container restarts gracefully (404/410).
 */

const crypto = require('crypto');

const DEFAULT_MAX_SESSIONS = 500;
const CLEANUP_INTERVAL_MS = 60 * 1000;
const RECENTLY_EXPIRED_TTL_S = 600; // 10 minutes

class SessionStore {
  constructor({ maxSessions = DEFAULT_MAX_SESSIONS } = {}) {
    this.maxSessions = maxSessions;
    this.sessions = new Map();
    this.recentlyExpired = new Map();

    // Periodic sweep of expired sessions (unref'd so it doesn't block shutdown)
    this.cleanupTimer = setInterval(() => this.cleanup(), CLEANUP_INTERVAL_MS);
    if (this.cleanupTimer.unref) {
      this.cleanupTimer.unref();
    }
  }

  /**
   * Generates a cryptographically random, unpredictable session ID.
   * @returns {string} 32-character hexadecimal string
   */
  generateSessionId() {
    return crypto.randomBytes(16).toString('hex');
  }

  /**
   * Creates and stores a streaming session.
   *
   * @param {Object} params
   * @param {string} params.videoId
   * @param {string} params.videoUrl
   * @param {string} params.audioUrl
   * @param {Object} [params.videoHeaders]
   * @param {Object} [params.audioHeaders]
   * @param {number} params.videoExpiresAt - Unix epoch seconds
   * @param {number} params.audioExpiresAt - Unix epoch seconds
   * @param {number} params.effectiveExpiresAt - Unix epoch seconds (min of video/audio)
   * @returns {Object} Stored session object
   */
  createSession({
    videoId,
    videoUrl,
    audioUrl,
    videoHeaders = {},
    audioHeaders = {},
    videoExpiresAt,
    audioExpiresAt,
    effectiveExpiresAt,
  }) {
    if (!videoId || typeof videoId !== 'string') {
      throw new Error('videoId is required');
    }
    if (!videoUrl || typeof videoUrl !== 'string') {
      throw new Error('videoUrl is required');
    }
    if (!audioUrl || typeof audioUrl !== 'string') {
      throw new Error('audioUrl is required');
    }

    const now = Math.floor(Date.now() / 1000);

    const vExp = typeof videoExpiresAt === 'number' ? videoExpiresAt : 0;
    const aExp = typeof audioExpiresAt === 'number' ? audioExpiresAt : 0;
    const effectiveExp = typeof effectiveExpiresAt === 'number'
      ? effectiveExpiresAt
      : Math.min(vExp, aExp);

    if (effectiveExp <= now) {
      throw new Error('Cannot create session with expired or non-positive expiration timestamp');
    }

    // Capacity management
    this.evictIfFull(now);

    const sessionId = this.generateSessionId();
    const session = {
      sessionId,
      videoId,
      videoUrl,
      audioUrl,
      videoHeaders: { ...videoHeaders },
      audioHeaders: { ...audioHeaders },
      videoExpiresAt: vExp,
      audioExpiresAt: aExp,
      effectiveExpiresAt: effectiveExp,
      createdAt: now,
      lastAccessedAt: now,
    };

    this.sessions.set(sessionId, session);
    return session;
  }

  /**
   * Retrieves an active session by ID.
   * Returns null if session does not exist or has expired.
   *
   * @param {string} sessionId
   * @returns {Object|null}
   */
  getSession(sessionId) {
    if (!sessionId || typeof sessionId !== 'string') {
      return null;
    }

    const session = this.sessions.get(sessionId);
    if (!session) {
      return null;
    }

    const now = Math.floor(Date.now() / 1000);
    if (now >= session.effectiveExpiresAt) {
      // Session has expired — evict and record in recentlyExpired
      this.sessions.delete(sessionId);
      this.recentlyExpired.set(sessionId, now);
      return null;
    }

    // Update last access timestamp on every successful, non-expired retrieval
    session.lastAccessedAt = now;

    return session;
  }

  /**
   * Checks whether a session ID is known to have expired.
   * @param {string} sessionId
   * @returns {boolean}
   */
  isExpired(sessionId) {
    if (!sessionId || typeof sessionId !== 'string') {
      return false;
    }

    const now = Math.floor(Date.now() / 1000);

    if (this.recentlyExpired.has(sessionId)) {
      return true;
    }

    const session = this.sessions.get(sessionId);
    if (session && now >= session.effectiveExpiresAt) {
      this.sessions.delete(sessionId);
      this.recentlyExpired.set(sessionId, now);
      return true;
    }

    return false;
  }

  /**
   * Evicts expired entries first, then least recently accessed (LRU) entries if capacity limit is reached.
   * Handles equal timestamps deterministically using createdAt followed by sessionId.
   *
   * @param {number} now - Current Unix epoch seconds
   */
  evictIfFull(now) {
    if (this.sessions.size < this.maxSessions) {
      return;
    }

    // 1. Evict expired entries first
    for (const [id, s] of this.sessions.entries()) {
      if (now >= s.effectiveExpiresAt) {
        this.sessions.delete(id);
        this.recentlyExpired.set(id, now);
      }
    }

    // 2. If still at or over capacity, evict least recently accessed session (LRU)
    while (this.sessions.size >= this.maxSessions && this.sessions.size > 0) {
      let lruId = null;
      let lruSession = null;

      for (const [id, s] of this.sessions.entries()) {
        if (!lruSession) {
          lruId = id;
          lruSession = s;
          continue;
        }

        const sAccess = typeof s.lastAccessedAt === 'number' ? s.lastAccessedAt : s.createdAt;
        const lruAccess = typeof lruSession.lastAccessedAt === 'number' ? lruSession.lastAccessedAt : lruSession.createdAt;

        if (sAccess < lruAccess) {
          lruId = id;
          lruSession = s;
        } else if (sAccess === lruAccess) {
          // Deterministic tie-breaker 1: earlier creation time
          if (s.createdAt < lruSession.createdAt) {
            lruId = id;
            lruSession = s;
          } else if (s.createdAt === lruSession.createdAt) {
            // Deterministic tie-breaker 2: lexicographical order of sessionId
            if (id < lruId) {
              lruId = id;
              lruSession = s;
            }
          }
        }
      }

      if (lruId) {
        this.sessions.delete(lruId);
        this.recentlyExpired.set(lruId, now);
      } else {
        break;
      }
    }
  }

  /**
   * Periodic garbage collection of expired sessions.
   */
  cleanup() {
    const now = Math.floor(Date.now() / 1000);

    for (const [id, s] of this.sessions.entries()) {
      if (now >= s.effectiveExpiresAt) {
        this.sessions.delete(id);
        this.recentlyExpired.set(id, now);
      }
    }

    for (const [id, expTime] of this.recentlyExpired.entries()) {
      if (now - expTime > RECENTLY_EXPIRED_TTL_S) {
        this.recentlyExpired.delete(id);
      }
    }
  }

  /**
   * Reset all sessions (primarily for testing and clean container states).
   */
  clear() {
    this.sessions.clear();
    this.recentlyExpired.clear();
  }

  get size() {
    return this.sessions.size;
  }
}

const defaultSessionStore = new SessionStore();

module.exports = {
  SessionStore,
  sessionStore: defaultSessionStore,
};
