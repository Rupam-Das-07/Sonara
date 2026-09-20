'use strict';

/**
 * IdentityMatcher.js — MusicBrainz Confidence Scoring
 *
 * Determines whether a MusicBrainz search result is a reliable match for
 * a Sonara track. Produces a structured confidence score between 0.0 and 1.0.
 *
 * Scoring model:
 *   Title match  (exact=0.45, fuzzy=0–0.35)
 *   Artist match (exact=0.35, fuzzy=0–0.25)
 *   Duration     (±2s=+0.10, ±5s=+0.05, >10s=-0.15)
 *
 * ACCEPTANCE_THRESHOLD = 0.75
 * Results below this threshold are treated as no-match.
 *
 * Design principle: prefer returning null over returning a wrong MBID.
 * Incorrect identity mappings corrupt ranking signals downstream.
 */

const ACCEPTANCE_THRESHOLD = 0.75;

/**
 * Normalizes a string for comparison:
 *  - Lowercase
 *  - Strip featuring credits: "ft.", "feat.", "featuring", "with", "(feat...)", "[feat...]"
 *  - Strip common noise: "official video", "official audio", "lyrics", "hd", "4k"
 *  - Collapse whitespace
 *  - Strip non-alphanumeric characters except spaces
 */
function normalize(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .toLowerCase()
    .replace(/\(feat\.?[^)]*\)/gi, '')
    .replace(/\[feat\.?[^\]]*\]/gi, '')
    .replace(/\b(feat\.?|ft\.?|featuring|with)\b.*/gi, '')
    .replace(/\b(official video|official audio|lyrics|hd|4k|audio|video|music video|official)\b/gi, '')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Calculates a simple character-overlap similarity (Dice coefficient on bigrams).
 * Returns a value in [0.0, 1.0].
 */
function diceSimilarity(a, b) {
  if (!a || !b) return 0;
  if (a === b) return 1;

  const bigrams = (s) => {
    const set = new Set();
    for (let i = 0; i < s.length - 1; i++) set.add(s.slice(i, i + 2));
    return set;
  };

  const setA = bigrams(a);
  const setB = bigrams(b);
  if (setA.size === 0 || setB.size === 0) return 0;

  let intersection = 0;
  for (const bg of setA) { if (setB.has(bg)) intersection++; }

  return (2 * intersection) / (setA.size + setB.size);
}

/**
 * Evaluates a single MusicBrainz search result against the input track data.
 *
 * @param {Object} input   - { title, artist, album?, durationSeconds? }
 * @param {Object} mbResult - MusicBrainz recording object from search API
 * @returns {{ confidence: number, matchedFields: Object, reasons: string[] }}
 */
function scoreCandidate(input, mbResult) {
  let confidence = 0;
  const matchedFields = {};
  const reasons = [];

  const inputTitle  = normalize(input.title  || '');
  const inputArtist = normalize(input.artist || '');

  // ── Title Matching (max 0.45) ──────────────────────────────────────────────
  const mbTitle = normalize(mbResult.title || '');
  if (inputTitle && mbTitle) {
    if (inputTitle === mbTitle) {
      confidence += 0.45;
      matchedFields.title = { input: input.title, matched: mbResult.title, exact: true, score: 0.45 };
      reasons.push('title_exact');
    } else {
      const titleSim = diceSimilarity(inputTitle, mbTitle);
      const titleScore = titleSim * 0.35;
      confidence += titleScore;
      matchedFields.title = { input: input.title, matched: mbResult.title, exact: false, similarity: +titleSim.toFixed(3), score: +titleScore.toFixed(3) };
      if (titleSim >= 0.8) reasons.push('title_fuzzy_strong');
      else if (titleSim >= 0.5) reasons.push('title_fuzzy_moderate');
      else reasons.push('title_fuzzy_weak');
    }
  }

  // ── Artist Matching (max 0.35) ─────────────────────────────────────────────
  // MusicBrainz returns artist-credit as array; join the primary credit names.
  const mbArtistRaw = (mbResult['artist-credit'] || [])
    .map(ac => (typeof ac === 'string' ? ac : (ac.artist?.name || '')))
    .join(' ')
    .trim();
  const mbArtist = normalize(mbArtistRaw);

  if (inputArtist && mbArtist) {
    if (inputArtist === mbArtist) {
      confidence += 0.35;
      matchedFields.artist = { input: input.artist, matched: mbArtistRaw, exact: true, score: 0.35 };
      reasons.push('artist_exact');
    } else {
      const artistSim = diceSimilarity(inputArtist, mbArtist);
      const artistScore = artistSim * 0.25;
      confidence += artistScore;
      matchedFields.artist = { input: input.artist, matched: mbArtistRaw, exact: false, similarity: +artistSim.toFixed(3), score: +artistScore.toFixed(3) };
      if (artistSim >= 0.8) reasons.push('artist_fuzzy_strong');
      else if (artistSim >= 0.5) reasons.push('artist_fuzzy_moderate');
      else reasons.push('artist_fuzzy_weak');
    }
  }

  // ── Duration Matching (max +0.10, min -0.15) ──────────────────────────────
  const inputDuration = input.durationSeconds ? Math.round(input.durationSeconds) : null;
  // MB returns duration in milliseconds
  const mbDurationSec = mbResult.length ? Math.round(mbResult.length / 1000) : null;

  if (inputDuration && mbDurationSec) {
    const deltaSec = Math.abs(inputDuration - mbDurationSec);
    let durationScore = 0;
    if (deltaSec <= 2)       { durationScore = +0.10; reasons.push('duration_exact'); }
    else if (deltaSec <= 5)  { durationScore = +0.05; reasons.push('duration_close'); }
    else if (deltaSec <= 10) { durationScore =  0.00; reasons.push('duration_acceptable'); }
    else                     { durationScore = -0.15; reasons.push('duration_mismatch'); }
    confidence += durationScore;
    matchedFields.duration = { inputSeconds: inputDuration, matchedSeconds: mbDurationSec, deltaSecs: deltaSec, score: durationScore };
  }

  confidence = Math.max(0, Math.min(1, +confidence.toFixed(4)));

  return { confidence, matchedFields, reasons };
}

/**
 * Selects the best-matching candidate from a list of MB search results,
 * returning null if no result meets the acceptance threshold.
 *
 * @param {Object}   input      - { title, artist, album?, durationSeconds? }
 * @param {Object[]} mbResults  - Array of MusicBrainz recording objects
 * @returns {{
 *   resolved:           boolean,
 *   mbid:               string | null,
 *   confidence:         number,
 *   matchedFields:      Object,
 *   rejectedCandidates: Array
 * }}
 */
function findBestMatch(input, mbResults) {
  if (!Array.isArray(mbResults) || mbResults.length === 0) {
    return { resolved: false, mbid: null, confidence: 0, matchedFields: {}, rejectedCandidates: [] };
  }

  let best = null;
  const rejected = [];

  for (const result of mbResults) {
    const { confidence, matchedFields, reasons } = scoreCandidate(input, result);

    if (confidence >= ACCEPTANCE_THRESHOLD) {
      if (!best || confidence > best.confidence) {
        // Demote the previous best to rejected
        if (best) rejected.push({ mbid: best.mbid, title: best.title, confidence: best.confidence, reason: 'outscored' });
        best = { mbid: result.id, title: result.title, confidence, matchedFields, reasons };
      } else {
        rejected.push({ mbid: result.id, title: result.title, confidence, reason: 'below_best' });
      }
    } else {
      const primaryReason = reasons.find(r => r.includes('title') || r.includes('artist')) || 'low_confidence';
      rejected.push({ mbid: result.id, title: result.title, confidence, reason: primaryReason });
    }
  }

  if (!best) {
    return { resolved: false, mbid: null, confidence: 0, matchedFields: {}, rejectedCandidates: rejected };
  }

  return {
    resolved:           true,
    mbid:               best.mbid,
    confidence:         best.confidence,
    matchedFields:      best.matchedFields,
    rejectedCandidates: rejected,
  };
}

module.exports = { findBestMatch, scoreCandidate, normalize, ACCEPTANCE_THRESHOLD };
