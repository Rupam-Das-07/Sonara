'use strict';

/**
 * SearchQualityEngine.js — Independently owned copy for sonara-backend.
 *
 * Implements the Deterministic Ranking Pipeline.
 *
 * This file is an independently owned copy extracted from the Sonara Web backend.
 * It has NO runtime dependency on the Web project.
 * Future improvements to Android search ranking must be made HERE.
 *
 * Pipeline stages:
 *   1. Filter & Enrich  — validate, drop outliers, spread canonical + SQE fields
 *   2. Quality Scoring  — deterministic penalties/bonuses
 *   3. Deduplication    — collapse duplicates by title+artist key
 *   4. Stable Sorting   — qualityScore DESC, originalRank ASC
 *
 * Design constraints:
 *   - Pure function: no I/O, no HTTP, no Express logic.
 *   - Fully deterministic: same input always produces same output.
 *   - Immutable: every returned object is Object.freeze'd.
 *   - Original CanonicalTrack objects are never mutated.
 */

// ---------------------------------------------------------------------------
// Configuration — Centralised scoring rules
// ---------------------------------------------------------------------------
const SEARCH_SCORING_RULES = {
  penalties: {
    karaoke:      50,
    cover:        20,
    live:         20,
    ugcNoise:     80,  // 8D / slowed+reverb / nightcore / bass-boosted
    reaction:     80,
    podcast:      80,
    mashup:       30,
    instrumental: 40,
  },
  bonuses: {
    isSongType:      30,  // resultType === 'song'
    exactTitleMatch: 20,
  },
  thresholds: {
    maxDurationMs: 900_000,  // 15 minutes
  },
  baseScore: 100,
};

// ---------------------------------------------------------------------------
// Keyword penalty maps
// ---------------------------------------------------------------------------
const PENALTY_KEYWORD_MAP = [
  { patterns: ['karaoke'],                                                rule: 'karaoke'      },
  { patterns: ['cover'],                                                  rule: 'cover'        },
  { patterns: ['live', '(live)'],                                         rule: 'live'         },
  { patterns: ['8d', 'slowed', 'reverb', 'nightcore', 'bass boost', 'bass boosted'], rule: 'ugcNoise' },
  { patterns: ['reaction'],                                               rule: 'reaction'     },
  { patterns: ['podcast'],                                                rule: 'podcast'      },
  { patterns: ['mashup', 'megamix', 'nonstop'],                           rule: 'mashup'       },
  { patterns: ['instrumental'],                                           rule: 'instrumental' },
];

// ---------------------------------------------------------------------------
// Stage 1 — Filter & Enrich
// ---------------------------------------------------------------------------

function sanitizeTitle(text) {
  if (!text) return '';
  let s = text.replace(/[\u{1F600}-\u{1FAFF}\u{2600}-\u{27BF}]/gu, '');
  s = s.replace(/[\(\[].*?(official|video|audio|lyric|remaster|hd|hq|full\s*song).*?[\)\]]/gi, '');
  return s.trim();
}

function normalizeForDedup(text) {
  return (text || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function filterAndEnrich(canonicalTrack, originalRank) {
  if (!canonicalTrack || !canonicalTrack.id) return null;

  const durationMs = canonicalTrack.duration != null
    ? canonicalTrack.duration * 1000
    : null;

  if (durationMs !== null && durationMs > SEARCH_SCORING_RULES.thresholds.maxDurationMs) {
    return null;
  }

  const title = sanitizeTitle(canonicalTrack.title || '');

  return Object.freeze({
    ...canonicalTrack,
    title,
    rawTitle:     canonicalTrack.title || '',
    durationMs,
    originalRank,
    qualityScore: SEARCH_SCORING_RULES.baseScore,
  });
}

// ---------------------------------------------------------------------------
// Stage 2 — Quality Scoring
// ---------------------------------------------------------------------------

function applyScoring(track, queryLower) {
  const titleLower = (track.rawTitle || '').toLowerCase();
  let score = track.qualityScore;

  for (const { patterns, rule } of PENALTY_KEYWORD_MAP) {
    const deduction = SEARCH_SCORING_RULES.penalties[rule];
    const matched = patterns.some(p => titleLower.includes(p));
    if (matched) {
      const userAskedForIt = patterns.some(p => queryLower.includes(p));
      if (!userAskedForIt) {
        score -= deduction;
      }
    }
  }

  if (track.resultType === 'song') {
    score += SEARCH_SCORING_RULES.bonuses.isSongType;
  }

  return Object.freeze({ ...track, qualityScore: score });
}

// ---------------------------------------------------------------------------
// Stage 3 — Deduplication
// ---------------------------------------------------------------------------

function deduplicate(tracks) {
  const seen = new Map();
  for (const track of tracks) {
    const key = normalizeForDedup(track.title + track.artist);
    if (!seen.has(key)) {
      seen.set(key, track);
    } else {
      const existing = seen.get(key);
      if (
        track.qualityScore > existing.qualityScore ||
        (track.qualityScore === existing.qualityScore && track.originalRank < existing.originalRank)
      ) {
        seen.set(key, track);
      }
    }
  }
  return Array.from(seen.values());
}

// ---------------------------------------------------------------------------
// Stage 4 — Stable Sorting
// ---------------------------------------------------------------------------

function stableSort(tracks) {
  return [...tracks].sort((a, b) => {
    if (b.qualityScore !== a.qualityScore) return b.qualityScore - a.qualityScore;
    return a.originalRank - b.originalRank;
  });
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

function process(canonicalResults, query = '') {
  if (!Array.isArray(canonicalResults) || canonicalResults.length === 0) return [];

  const queryLower = (query || '').toLowerCase().trim();

  const enriched = canonicalResults
    .map((track, idx) => filterAndEnrich(track, idx))
    .filter(Boolean);

  const scored = enriched.map(track => applyScoring(track, queryLower));
  const deduped = deduplicate(scored);
  return stableSort(deduped);
}

module.exports = { process, SEARCH_SCORING_RULES };
