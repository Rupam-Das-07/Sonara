'use strict';

/**
 * importMatchService.js — Core matching service for imported playlist tracks.
 *
 * Implements Stage B identity scoring using the existing IdentityMatcher.findBestMatch API.
 * Classifies match results into:
 *  - confident: confidence >= THRESHOLD_HIGH (0.75) -> matched
 *  - review:    THRESHOLD_LOW (0.55) <= confidence < THRESHOLD_HIGH -> ambiguous
 *  - none:      confidence < THRESHOLD_LOW -> unmatched
 *
 * Enforces bounded concurrency (K=6) across chunk tracks.
 */

const { findBestMatch } = require('../identity/IdentityMatcher');
const candidateRetriever = require('./importCandidateRetriever');
const { importMatchCache } = require('./importMatchCache');
const { mapConcurrent } = require('./importConcurrency');

const THRESHOLD_HIGH = 0.75;
const THRESHOLD_LOW = 0.55;
const DEFAULT_CONCURRENCY = 6;

/**
 * Converts a CanonicalTrack to the client-safe TrackDTO format.
 *
 * @param {object} track
 * @returns {object} TrackDTO
 */
function toTrackDTO(track) {
  if (!track) return null;
  const thumbnails = track.thumbnails || [];
  const artworkUrl = thumbnails.length > 0
    ? thumbnails[thumbnails.length - 1].url
    : (track.artworkUrl || '');

  return {
    id:         track.videoId || track.id,
    videoId:    track.videoId || track.id,
    title:      track.title,
    artist:     track.artist,
    album:      track.album || '',
    duration:   track.duration !== undefined ? track.duration : null,
    artworkUrl: artworkUrl,
    thumbnails: thumbnails,
    resultType: track.resultType || 'video',
  };
}

/**
 * Evaluates a single imported track against YouTube Music candidates using IdentityMatcher.findBestMatch.
 *
 * @param {object} track - { sourceOrder, title, artist, album, durationMs, isLocalFile, isEpisode }
 * @returns {Promise<object>} Match result
 */
async function matchSingleTrack(track) {
  const sourceOrder = track.sourceOrder ?? 0;

  // 1. Short-circuit: Local files
  if (track.isLocalFile) {
    return {
      sourceOrder,
      status: 'skipped',
      tier: 'none',
      confidence: 0.0,
      resolvedTrack: null,
      alternatives: [],
      reason: 'local_file',
    };
  }

  // 2. Short-circuit: Podcast episodes
  if (track.isEpisode) {
    return {
      sourceOrder,
      status: 'skipped',
      tier: 'none',
      confidence: 0.0,
      resolvedTrack: null,
      alternatives: [],
      reason: 'episode',
    };
  }

  const title = (track.title || '').trim();
  const artist = (track.artist || '').trim();

  // 3. Short-circuit: Empty query
  if (!title && !artist) {
    return {
      sourceOrder,
      status: 'skipped',
      tier: 'none',
      confidence: 0.0,
      resolvedTrack: null,
      alternatives: [],
      reason: 'empty_query',
    };
  }

  // 4. Cache check
  const durationMs = track.durationMs || null;
  const cached = importMatchCache.get(title, artist, durationMs);
  if (cached) {
    return {
      ...cached,
      sourceOrder, // Adopt the current track's sourceOrder
    };
  }

  // 5. Stage A: Candidate retrieval
  const candidates = await candidateRetriever.retrieveCandidates(title, artist);
  if (!candidates || candidates.length === 0) {
    const noCandidatesResult = {
      sourceOrder,
      status: 'unmatched',
      tier: 'none',
      confidence: 0.0,
      resolvedTrack: null,
      alternatives: [],
      reason: 'no_candidates',
    };
    importMatchCache.set(title, artist, durationMs, noCandidatesResult);
    return noCandidatesResult;
  }

  // Map candidates for IdentityMatcher input and fast lookup
  const trackMap = new Map();
  const mbCandidates = candidates.map(c => {
    const id = c.videoId || c.id;
    trackMap.set(id, c);
    return {
      id,
      title: c.title,
      'artist-credit': c.artist ? [{ artist: { name: c.artist } }] : [],
      length: c.duration ? c.duration * 1000 : null,
    };
  });

  const input = {
    title,
    artist,
    album: track.album || '',
    durationSeconds: durationMs ? Math.round(durationMs / 1000) : null,
  };

  // 6. Stage B: Identity scoring via canonical findBestMatch
  const matchResult = findBestMatch(input, mbCandidates);

  let decision;

  if (matchResult.resolved && matchResult.confidence >= THRESHOLD_HIGH) {
    // High confidence match
    const primaryCandidate = trackMap.get(matchResult.mbid);
    const alternatives = (matchResult.rejectedCandidates || [])
      .filter(r => r.confidence >= THRESHOLD_LOW && r.mbid !== matchResult.mbid)
      .slice(0, 3)
      .map(r => toTrackDTO(trackMap.get(r.mbid)))
      .filter(Boolean);

    decision = {
      sourceOrder,
      status: 'matched',
      tier: 'confident',
      confidence: matchResult.confidence,
      resolvedTrack: toTrackDTO(primaryCandidate),
      alternatives,
      reason: null,
    };
  } else {
    // Best candidate is either the resolved candidate or the highest in rejectedCandidates
    const allScored = [...(matchResult.rejectedCandidates || [])];
    if (matchResult.mbid) {
      allScored.push({
        mbid: matchResult.mbid,
        title: matchResult.matchedFields?.title?.matched || title,
        confidence: matchResult.confidence,
        reason: 'below_threshold',
      });
    }

    allScored.sort((a, b) => b.confidence - a.confidence);
    const best = allScored[0];

    if (best && best.confidence >= THRESHOLD_LOW) {
      // Ambiguous / Review tier
      const primaryCandidate = trackMap.get(best.mbid);
      const alternatives = allScored
        .slice(1, 4)
        .map(r => toTrackDTO(trackMap.get(r.mbid)))
        .filter(Boolean);

      decision = {
        sourceOrder,
        status: 'ambiguous',
        tier: 'review',
        confidence: best.confidence,
        resolvedTrack: toTrackDTO(primaryCandidate),
        alternatives,
        reason: 'below_threshold',
      };
    } else {
      // Low confidence / Unmatched
      decision = {
        sourceOrder,
        status: 'unmatched',
        tier: 'none',
        confidence: best ? best.confidence : 0.0,
        resolvedTrack: null,
        alternatives: [],
        reason: 'below_threshold',
      };
    }
  }

  // Cache the outcome
  importMatchCache.set(title, artist, durationMs, decision);
  return decision;
}

/**
 * Matches a batch of imported tracks with bounded concurrency.
 *
 * @param {object[]} tracks - Array of track objects
 * @param {number} [concurrency=DEFAULT_CONCURRENCY]
 * @returns {Promise<object[]>} Array of match results matching the §7 response shape
 */
async function matchTracksChunk(tracks, concurrency = DEFAULT_CONCURRENCY) {
  return await mapConcurrent(tracks, concurrency, async (track) => {
    return await matchSingleTrack(track);
  });
}

module.exports = {
  matchSingleTrack,
  matchTracksChunk,
  toTrackDTO,
  THRESHOLD_HIGH,
  THRESHOLD_LOW,
};
