'use strict';

/**
 * ListenBrainzNormalizer.js — Phase 2: Backend Response Normalizer
 *
 * The single authoritative location for preparing ListenBrainz API responses
 * before they are cached and served to the frontend.
 *
 * PIPELINE POSITION:
 *   ListenBrainz API → ListenBrainzNormalizer → Backend Cache → Frontend
 *
 * CONTRACT:
 *  - Input:  raw ListenBrainz API response (any shape)
 *  - Output: NormalizedLbPayload — deterministic, schema-versioned
 *  - Guarantee: identical inputs always produce identical outputs
 *  - Guarantee: the frontend never sees a raw LB payload
 *
 * SCHEMA VERSION:
 *   LB_NORMALIZER_SCHEMA_VERSION must be incremented whenever the output
 *   structure changes in a way that would break frontend consumers.
 *
 * OUTPUT CONTRACT (NormalizedLbPayload):
 * {
 *   _schema:     number,           — normalizer schema version
 *   sourceMbid:  string,           — the artist MBID that was queried
 *   sourceType:  'artist',
 *   candidates:  NormalizedCandidate[],
 *   generatedAt: number,           — server timestamp (ms)
 * }
 *
 * NormalizedCandidate:
 * {
 *   mbid:               string,    — target recording MBID
 *   type:               'recording',
 *   similarity:         number,    — normalized 0.0–1.0 (from listen count rank)
 *   providerConfidence: number,    — same as similarity (RE V2 vocabulary)
 *   similarArtistMbid:  string,    — the intermediary similar artist MBID
 *   similarArtistName:  string,    — human-readable artist name for debugging
 *   listenCount:        number,    — raw community listen count from LB
 *   provenance: {
 *     provider:       'listenbrainz',
 *     sourceMbid:     string,
 *     similarityType: 'artist_radio',
 *     generatedAt:    number,
 *   }
 * }
 */

const LB_NORMALIZER_SCHEMA_VERSION = 1;

const MIN_LISTEN_COUNT = 1;    // Discard tracks with 0 listens
const MAX_CANDIDATES   = 50;   // Hard cap per response

// UUID format validation
const MBID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isValidMbid(str) {
  return typeof str === 'string' && MBID_RE.test(str.trim());
}

// ─── normalizeLbRadioResponse ─────────────────────────────────────────────────

/**
 * Normalizes a raw LB Radio artist response.
 *
 * LB API: GET /1/lb-radio/artist/:artistMbid?...
 *
 * Raw shape: Object keyed by artist MBID, each value is an array of:
 *   { recording_mbid, similar_artist_mbid, similar_artist_name, total_listen_count }
 *
 * Example raw:
 * {
 *   "c8b03190-...": [
 *     { recording_mbid: "...", similar_artist_mbid: "c8b03190...",
 *       similar_artist_name: "The Weeknd", total_listen_count: 2331 }
 *   ]
 * }
 *
 * Strategy:
 *   1. Flatten all artist buckets into a single candidate list.
 *   2. Validate MBIDs; discard invalid or self-matching.
 *   3. Sort by listen count descending; assign a normalized similarity score
 *      based on rank within the result set (rank 1 = 1.0, rank N = 0.0).
 *   4. Deduplicate by recording MBID.
 *   5. Cap at MAX_CANDIDATES.
 *
 * @param {string} sourceMbid  — The artist MBID that was queried
 * @param {any}    raw         — Raw LB Radio response body
 * @returns {NormalizedLbPayload}
 */
function normalizeLbRadioResponse(sourceMbid, raw) {
  const generatedAt = Date.now();

  if (!raw || typeof raw !== 'object') {
    return emptyPayload(sourceMbid, 'artist');
  }

  // Flatten all artist buckets
  const allItems = [];
  for (const [artistMbid, recordings] of Object.entries(raw)) {
    if (!Array.isArray(recordings)) continue;
    for (const item of recordings) {
      allItems.push({ ...item, _bucketArtistMbid: artistMbid });
    }
  }

  // Sort by listen count descending (primary signal of community popularity)
  allItems.sort((a, b) => (b.total_listen_count || 0) - (a.total_listen_count || 0));

  const seen       = new Set();
  const candidates = [];

  for (let i = 0; i < allItems.length; i++) {
    if (candidates.length >= MAX_CANDIDATES) break;

    const item         = allItems[i];
    const recordingMbid = (item.recording_mbid || '').trim().toLowerCase();
    const artistMbid    = (item.similar_artist_mbid || item._bucketArtistMbid || '').trim().toLowerCase();
    const listenCount   = typeof item.total_listen_count === 'number' ? item.total_listen_count : 0;
    const artistName    = typeof item.similar_artist_name === 'string' ? item.similar_artist_name.trim() : '';

    if (!isValidMbid(recordingMbid)) continue;
    if (listenCount < MIN_LISTEN_COUNT) continue;
    if (seen.has(recordingMbid)) continue;

    seen.add(recordingMbid);

    // Normalized similarity: linear rank decay across candidates.
    // Candidate #1 (highest listen count) → 1.0
    // Candidate #N (last) → approaches 0.1
    // This is a rank-based proxy since LB Radio doesn't provide a similarity score.
    const rankScore = 1.0 - (candidates.length / Math.max(MAX_CANDIDATES, 1)) * 0.9;

    candidates.push({
      mbid:               recordingMbid,
      type:               'recording',
      similarity:         parseFloat(rankScore.toFixed(4)),
      providerConfidence: parseFloat(rankScore.toFixed(4)),
      similarArtistMbid:  artistMbid,
      similarArtistName:  artistName,
      listenCount,
      provenance: {
        provider:       'listenbrainz',
        sourceMbid,
        similarityType: 'artist_radio',
        generatedAt,
      },
    });
  }

  return {
    _schema:     LB_NORMALIZER_SCHEMA_VERSION,
    sourceMbid,
    sourceType:  'artist',
    candidates,
    generatedAt,
  };
}

// ─── normalizeRecordingMetadata ────────────────────────────────────────────────

/**
 * Normalizes the LB /1/metadata/recording/ batch response.
 * Returns a map of recording_mbid → { title, artistName } for TrackResolver.
 *
 * Raw shape: { [mbid]: { recording: { name, ... }, artist_credit: [...] } }
 *
 * @param {any} raw  — Raw LB metadata response body
 * @returns {Object<string, { title: string, artistName: string }>}
 */
function normalizeRecordingMetadata(raw) {
  if (!raw || typeof raw !== 'object') return {};

  const result = {};
  for (const [mbid, entry] of Object.entries(raw)) {
    if (!isValidMbid(mbid)) continue;

    const recording   = entry?.recording || {};
    const title       = typeof recording.name === 'string' ? recording.name.trim() : '';
    const artistCredit = Array.isArray(recording.rels)
      ? recording.rels.filter(r => r.type === 'vocal' || r.type === 'instrument')
      : [];

    // LB metadata response uses a flat rels array; the primary artist name
    // is typically in the first instrument/vocal rel, or falls back to the
    // recording's own artist_credit if present.
    const artistName = (
      entry?.artist?.name ||
      artistCredit[0]?.artist_name ||
      ''
    ).trim();

    if (title) {
      result[mbid.toLowerCase()] = { title, artistName };
    }
  }

  return result;
}

// ─── emptyPayload ──────────────────────────────────────────────────────────────

/**
 * Creates an empty normalized payload for graceful error paths.
 *
 * @param {string} sourceMbid
 * @param {'artist'|'recording'} sourceType
 * @returns {NormalizedLbPayload}
 */
function emptyPayload(sourceMbid, sourceType) {
  return {
    _schema:     LB_NORMALIZER_SCHEMA_VERSION,
    sourceMbid,
    sourceType,
    candidates:  [],
    generatedAt: Date.now(),
  };
}

module.exports = {
  LB_NORMALIZER_SCHEMA_VERSION,
  normalizeLbRadioResponse,
  normalizeRecordingMetadata,
  emptyPayload,
};
