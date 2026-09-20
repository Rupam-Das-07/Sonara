'use strict';

/**
 * streamMatcher.js — Deterministic cross-provider candidate matcher.
 *
 * Evaluates whether a candidate from JioSaavn matches a canonical
 * YouTube track with sufficient confidence to safely substitute the audio stream.
 *
 * 5-Point Quality Gate:
 *   1. 320 kbps availability check (must be explicitly advertised)
 *   2. Junk variant rejection (karaoke, 8D, lofi, slowed, instrumental, cover)
 *   3. Artist token overlap (prevents wrong-recording cover bands e.g. Alda Rikson)
 *   4. Title equivalence / containment check
 *   5. Duration delta check: |target.duration - candidate.duration| <= 3 seconds
 */

const { isJunkVariant, normalizeTitle } = require('../discovery/artistCatalogService');

const MAX_STREAM_DURATION_DELTA_SECONDS = 3;
const MIN_ARTIST_TOKEN_LENGTH = 3;

// Common artist stop words that should not be used as the sole matching token
const ARTIST_STOP_WORDS = new Set([
  'the', 'and', 'feat', 'featuring', 'with', 'official', 'music', 'records', 'band'
]);

// Additional junk patterns specific to streaming audio substitution
const STREAM_JUNK_PATTERNS = [
  /\bkaraoke\b/i,
  /\b8d\s*(audio)?\b/i,
  /\blo-?fi\b/i,
  /\bslowed(\s*(\+|&)\s*reverb)?\b/i,
  /\breverb\b/i,
  /\bsped\s+up\b/i,
  /\binstrumental\b/i,
  /\bcover(\s+version)?\b/i,
  /\btribute\b/i,
];

/**
 * Checks if candidate explicitly advertises 320 kbps.
 * Handles both boolean and string representations defensively.
 */
function has320kbps(candidate) {
  if (!candidate || typeof candidate !== 'object') return false;
  const val = candidate['320kbps'] ?? candidate.more_info?.['320kbps'];
  return val === true || val === 'true' || val === 1 || val === '1';
}

/**
 * Detects if a title contains junk, karaoke, lofi, or alternate recording markers.
 */
function isStreamJunk(title) {
  if (!title || typeof title !== 'string') return true;
  if (isJunkVariant(title)) return true;
  return STREAM_JUNK_PATTERNS.some((pattern) => pattern.test(title));
}

/**
 * Extracts significant lowercase word tokens from an artist string.
 */
function extractArtistTokens(artist) {
  if (!artist || typeof artist !== 'string') return new Set();
  return new Set(
    artist
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .filter((t) => t.length >= MIN_ARTIST_TOKEN_LENGTH && !ARTIST_STOP_WORDS.has(t))
  );
}

/**
 * Collects all artist strings from candidate metadata across possible API shapes.
 */
function extractCandidateArtistString(candidate) {
  if (!candidate || typeof candidate !== 'object') return '';
  const parts = [];

  if (candidate.singers && typeof candidate.singers === 'string') {
    parts.push(candidate.singers);
  }
  if (candidate.primary_artists && typeof candidate.primary_artists === 'string') {
    parts.push(candidate.primary_artists);
  }
  if (candidate.music && typeof candidate.music === 'string') {
    parts.push(candidate.music);
  }

  // Structured artist maps
  const artistMap = candidate.artist_map || candidate.more_info?.artistMap;
  if (artistMap?.primary_artists && Array.isArray(artistMap.primary_artists)) {
    for (const a of artistMap.primary_artists) {
      if (a?.name && typeof a.name === 'string') parts.push(a.name);
    }
  }

  return parts.join(', ');
}

/**
 * Verifies that candidate artist shares at least one significant token with target artist.
 */
function matchesArtist(targetArtist, candidate) {
  const targetTokens = extractArtistTokens(targetArtist);
  if (targetTokens.size === 0) {
    // If target has no extractable artist tokens, cannot reliably verify
    return false;
  }

  const candidateArtistStr = extractCandidateArtistString(candidate);
  const candidateTokens = extractArtistTokens(candidateArtistStr);

  for (const token of targetTokens) {
    if (candidateTokens.has(token)) return true;
  }

  return false;
}

/**
 * Verifies title match between target and candidate.
 */
function matchesTitle(targetTitle, candidateTitle) {
  const wanted = normalizeTitle(targetTitle);
  const got = normalizeTitle(candidateTitle);

  if (!wanted || !got) return false;

  // Exact normalized match
  if (wanted === got) return true;

  // Containment match for multi-word or parenthesized titles
  if (wanted.length >= 4 && got.length >= 4 && (got.includes(wanted) || wanted.includes(got))) {
    return true;
  }

  return false;
}

/**
 * Extracts and parses duration in seconds from candidate or target.
 */
function parseDurationSeconds(duration) {
  if (typeof duration === 'number') {
    return duration > 0 ? Math.round(duration) : null;
  }
  if (typeof duration === 'string') {
    const parsed = parseInt(duration, 10);
    return !isNaN(parsed) && parsed > 0 ? parsed : null;
  }
  return null;
}

/**
 * Evaluates whether a candidate from JioSaavn matches a canonical target track.
 *
 * @param {object} target - Canonical track metadata { videoId, title, artist, duration, durationMs }
 * @param {object} candidate - JioSaavn API candidate object
 * @returns {{ ok: boolean, reason?: string, deltaSeconds?: number }}
 */
function evaluateMatch(target, candidate) {
  if (!target || typeof target !== 'object') {
    return { ok: false, reason: 'missing_target' };
  }
  if (!candidate || typeof candidate !== 'object') {
    return { ok: false, reason: 'missing_candidate' };
  }

  // Gate 1: 320 kbps availability
  if (!has320kbps(candidate)) {
    return { ok: false, reason: 'missing_320kbps' };
  }

  // Candidate must have an encrypted media URL for audio stream extraction
  const encryptedUrl = candidate.encrypted_media_url || candidate.more_info?.encrypted_media_url;
  if (!encryptedUrl || typeof encryptedUrl !== 'string') {
    return { ok: false, reason: 'missing_media_url' };
  }

  // Candidate title extraction
  const candidateTitle = candidate.song || candidate.title || '';
  if (!candidateTitle) {
    return { ok: false, reason: 'empty_candidate_title' };
  }

  // Gate 2: Junk variant rejection
  if (isStreamJunk(candidateTitle)) {
    return { ok: false, reason: 'junk_variant' };
  }

  // Gate 3: Artist token overlap
  if (!matchesArtist(target.artist, candidate)) {
    return { ok: false, reason: 'artist_mismatch' };
  }

  // Gate 4: Title match
  if (!matchesTitle(target.title, candidateTitle)) {
    return { ok: false, reason: 'title_mismatch' };
  }

  // Gate 5: Duration check (<= 3 seconds delta)
  const targetDuration = parseDurationSeconds(target.duration) ??
    (target.durationMs ? Math.round(target.durationMs / 1000) : null);
  const candidateDuration = parseDurationSeconds(candidate.duration ?? candidate.more_info?.duration);

  if (targetDuration === null || candidateDuration === null) {
    return { ok: false, reason: 'invalid_duration' };
  }

  const delta = Math.abs(targetDuration - candidateDuration);
  if (delta > MAX_STREAM_DURATION_DELTA_SECONDS) {
    return { ok: false, reason: 'duration_mismatch', deltaSeconds: delta };
  }

  return { ok: true, deltaSeconds: delta };
}

module.exports = {
  evaluateMatch,
  has320kbps,
  isStreamJunk,
  extractArtistTokens,
  extractCandidateArtistString,
  matchesArtist,
  matchesTitle,
  parseDurationSeconds,
  MAX_STREAM_DURATION_DELTA_SECONDS,
};
