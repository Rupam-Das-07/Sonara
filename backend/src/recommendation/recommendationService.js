'use strict';

/**
 * recommendationService.js — Reintegrated Recommendation Engine for sonara-backend
 *
 * Implements:
 * 1. Radio (via YouTube Music watch playlist)
 * 2. Related Tracks (via YouTube Music related songs)
 * 3. Similar Tracks (via ListenBrainz collaborative filtering + IdentityStore resolution)
 *
 * Finding 6 (Performance): Added TTL caches for radio and related candidates.
 * Evidence: measurements showed repeat requests for the same seed videoId always
 * incurred a full Python :5000 round-trip (~400–1200ms above network floor).
 * Caches use 10-min TTL (results are stable within a session) with stampede
 * protection via in-flight Map — same pattern as ytmusicProvider.searchSongs.
 */

const ytmusic = require('../search/ytmusicProvider');
const { filterRecommendations } = require('./RecommendationFilter');
const { identityStore } = require('../identity/IdentityStore');
const { resolveIdentity, fetchImmediateMetadata } = require('../identity/MusicBrainzService');
const { getSimilarRecordings, getRecordingMetadataBatch } = require('./ListenBrainzService');
const { findBestMatch } = require('../identity/IdentityMatcher');
const BoundedCache = require('../utils/BoundedCache');
const logger = require('../utils/logger');

const IDENTITY_MATCH_THRESHOLD = 0.70;

// TTL caches for radio and related recommendation results (Finding 6).
// 10-minute TTL: recommendations for a given seed are stable within a session.
// maxSize 500: covers typical session breadth without unbounded memory.
const RECOMMENDATION_CACHE_TTL_MS = 10 * 60 * 1000;
const _radioCache = new BoundedCache({ maxSize: 500, ttlMs: RECOMMENDATION_CACHE_TTL_MS });
const _relatedCache = new BoundedCache({ maxSize: 500, ttlMs: RECOMMENDATION_CACHE_TTL_MS });
// Stampede protection: prevents concurrent identical-seed requests from all hitting Python.
const _radioInFlight = new Map();
const _relatedInFlight = new Map();

class RecommendationService {
  static _buildResponse(source, seed, success, tracks = [], error = null, startTime = 0) {
    const executionTimeMs = startTime > 0 ? Date.now() - startTime : 0;
    return {
      success,
      source,
      seed,
      tracks,
      error,
      metadata: {
        generated: tracks.length,
        filtered: 0,
        executionTimeMs,
      },
    };
  }

  static _mapYtTrack(t) {
    return {
      id: t.id || t.videoId,
      videoId: t.videoId || t.id,
      title: t.title || 'Unknown Title',
      artist: t.artist || 'Unknown Artist',
      album: t.album || '',
      duration: t.duration || 0,
      artworkUrl: t.thumbnails && t.thumbnails.length > 0 ? t.thumbnails[t.thumbnails.length - 1].url : (t.artworkUrl || ''),
      thumbnails: t.thumbnails || [],
      resultType: 'song',
    };
  }

  /**
   * Fetches Radio continuation tracks based on a seed videoId.
   * Results are cached for RECOMMENDATION_CACHE_TTL_MS (10 min) to avoid
   * repeat Python :5000 round-trips for the same seed (Finding 6).
   */
  static async getRadioCandidates(videoId) {
    const t0 = Date.now();
    try {
      if (!videoId) throw new Error("videoId is required");

      // Cache hit
      const cached = _radioCache.get(videoId);
      if (cached) {
        logger.debug(`[RecommendationService] radio cache hit for ${videoId}`);
        return cached;
      }

      // Stampede protection
      if (_radioInFlight.has(videoId)) {
        return await _radioInFlight.get(videoId);
      }

      const fetchPromise = (async () => {
        try {
          let rawTracks = await ytmusic.getWatchPlaylist(videoId);
          let tracks = filterRecommendations(rawTracks).map(this._mapYtTrack);
          const result = this._buildResponse('radio', videoId, true, tracks, null, t0);
          _radioCache.set(videoId, result);
          return result;
        } catch (e) {
          logger.warn(`[RecommendationService] getRadioCandidates failed for ${videoId}: ${e.message}`);
          return this._buildResponse('radio', videoId, false, [], e.message || 'Provider unavailable', t0);
        } finally {
          _radioInFlight.delete(videoId);
        }
      })();

      _radioInFlight.set(videoId, fetchPromise);
      return fetchPromise;
    } catch (e) {
      logger.warn(`[RecommendationService] getRadioCandidates failed for ${videoId}: ${e.message}`);
      return this._buildResponse('radio', videoId, false, [], e.message || 'Provider unavailable', t0);
    }
  }

  /**
   * Fetches Related tracks based on a seed videoId.
   * Results are cached for RECOMMENDATION_CACHE_TTL_MS (10 min) to avoid
   * repeat Python :5000 round-trips for the same seed (Finding 6).
   */
  static async getRelatedCandidates(videoId) {
    const t0 = Date.now();
    try {
      if (!videoId) throw new Error("videoId is required");

      // Cache hit
      const cached = _relatedCache.get(videoId);
      if (cached) {
        logger.debug(`[RecommendationService] related cache hit for ${videoId}`);
        return cached;
      }

      // Stampede protection
      if (_relatedInFlight.has(videoId)) {
        return await _relatedInFlight.get(videoId);
      }

      const fetchPromise = (async () => {
        try {
          let rawTracks = await ytmusic.getRelatedSongs(videoId);
          let tracks = filterRecommendations(rawTracks).map(this._mapYtTrack);
          const result = this._buildResponse('related', videoId, true, tracks, null, t0);
          _relatedCache.set(videoId, result);
          return result;
        } catch (e) {
          logger.warn(`[RecommendationService] getRelatedCandidates failed for ${videoId}: ${e.message}`);
          return this._buildResponse('related', videoId, false, [], e.message || 'Provider unavailable', t0);
        } finally {
          _relatedInFlight.delete(videoId);
        }
      })();

      _relatedInFlight.set(videoId, fetchPromise);
      return fetchPromise;
    } catch (e) {
      logger.warn(`[RecommendationService] getRelatedCandidates failed for ${videoId}: ${e.message}`);
      return this._buildResponse('related', videoId, false, [], e.message || 'Provider unavailable', t0);
    }
  }

  /**
   * Fetches Similar tracks based on a seed videoId using ListenBrainz collaborative filtering.
   */
  static async getSimilarCandidates(videoId) {
    const t0 = Date.now();
    try {
      if (!videoId) throw new Error("videoId is required");
      await identityStore.ready();

      let mbid = null;
      if (identityStore.has(videoId)) {
        mbid = identityStore.get(videoId)?.mbid;
      } else {
        const songData = await ytmusic.getSongDetails(videoId);
        if (songData && songData.title && songData.artist) {
          const match = await resolveIdentity({
            title: songData.title,
            artist: songData.artist,
            durationSeconds: songData.duration_seconds || songData.duration,
          });
          identityStore.set(videoId, match);
          mbid = match.mbid;
        }
      }

      if (!mbid) {
        return this._buildResponse('similar', videoId, false, [], 'no_mbid_resolved', t0);
      }

      let artistMbid = null;
      const meta = await fetchImmediateMetadata(mbid);
      if (meta) {
        artistMbid = meta.artistMbid;
      }

      if (!artistMbid) {
        return this._buildResponse('similar', videoId, false, [], 'no_artist_mbid_found', t0);
      }

      const lbPayload = await getSimilarRecordings({ recordingMbid: mbid, artistMbid });
      if (!lbPayload || !lbPayload.candidates || lbPayload.candidates.length === 0) {
        return this._buildResponse('similar', videoId, false, [], 'zero_lb_candidates', t0);
      }

      const candidates = lbPayload.candidates;
      const candidateMbids = candidates.map(c => c.mbid);
      const batchMetadata = await getRecordingMetadataBatch(candidateMbids);

      const playableTracks = [];
      let networkLookups = 0;
      const MAX_NETWORK_LOOKUPS = 5;

      for (const candidate of candidates) {
        const cmbid = candidate.mbid;
        let resolvedVideoId = identityStore.reverseGet(cmbid);
        let resolvedConfidence = 1.0;
        let source = 'identity_store';

        if (!resolvedVideoId) {
          if (networkLookups >= MAX_NETWORK_LOOKUPS) continue;

          const metaInfo = batchMetadata[cmbid];
          if (!metaInfo || !metaInfo.title || !metaInfo.artistName) continue;

          networkLookups++;
          try {
            const query = `${metaInfo.title} ${metaInfo.artistName}`.trim();
            const results = await ytmusic.searchSongs(query);

            if (results.length > 0) {
              const match = findBestMatch({ title: metaInfo.title, artist: metaInfo.artistName }, results.map(r => ({
                title: r.title,
                id: r.videoId || r.id,
                'artist-credit': r.artist ? [{ artist: { name: r.artist } }] : [],
              })));

              if (match.resolved && match.confidence >= IDENTITY_MATCH_THRESHOLD) {
                resolvedVideoId = match.mbid;
                resolvedConfidence = match.confidence;
                source = 'search';

                identityStore.set(resolvedVideoId, {
                  resolved: true,
                  mbid: cmbid,
                  confidence: resolvedConfidence,
                  matchedFields: match.matchedFields || {},
                });
              }
            }
          } catch (e) {
            logger.warn(`[TrackResolver] Search fallback failed for cmbid=${cmbid}: ${e.message}`);
          }
        }

        if (resolvedVideoId) {
          playableTracks.push({
            id: resolvedVideoId,
            videoId: resolvedVideoId,
            title: batchMetadata[cmbid]?.title || cmbid,
            artist: batchMetadata[cmbid]?.artistName || 'Unknown Artist',
            album: '',
            duration: 0,
            artworkUrl: `https://i.ytimg.com/vi/${resolvedVideoId}/hqdefault.jpg`,
            thumbnails: [{ url: `https://i.ytimg.com/vi/${resolvedVideoId}/hqdefault.jpg`, width: 480, height: 360 }],
            mbid: cmbid,
            source: 'listenbrainz',
            resolutionSource: source,
            resolutionConfidence: resolvedConfidence,
            lbScore: candidate.similarity,
          });
        }
      }

      if (playableTracks.length === 0) {
        return this._buildResponse('similar', videoId, false, [], 'all_candidates_unresolved', t0);
      }

      return this._buildResponse('similar', videoId, true, playableTracks, null, t0);
    } catch (e) {
      logger.warn(`[RecommendationService] getSimilarCandidates failed: ${e.message}`);
      return this._buildResponse('similar', videoId, false, [], e.message || 'Provider unavailable', t0);
    }
  }

  /**
   * Clears all recommendation caches and in-flight maps (for testing only).
   */
  static clearCaches() {
    _radioCache.clear();
    _relatedCache.clear();
    _radioInFlight.clear();
    _relatedInFlight.clear();
  }
}

// Expose caches for test inspection (not for production use).
RecommendationService._radioCache = _radioCache;
RecommendationService._relatedCache = _relatedCache;

module.exports = RecommendationService;
