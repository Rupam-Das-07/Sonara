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

const { isJunkVariant } = require('../discovery/artistCatalogService');

const MAX_STREAM_DURATION_DELTA_SECONDS = 3;
const MIN_ARTIST_TOKEN_LENGTH = 3;

// ── Unicode token rule (deliberate, applies to artists and titles) ───────────
//
// A token is a maximal run of Unicode letters, numbers and combining marks.
// Everything else — whitespace, punctuation, symbols — delimits.
//
// Combining marks are KEPT inside tokens on purpose: Indic matras and similar marks
// carry the identity of a name, so treating them as delimiters would shatter
// "अरिजीत" into meaningless fragments and no token would survive the minimum length.
// The previous rule was /[^a-z0-9]+/, under which EVERY character of a non-Latin name
// is a delimiter: the token set came out empty and matchesArtist() rejected the
// candidate as artist_mismatch even when the artists were identical.
//
// Inputs are NFC-normalized first so a composed and a decomposed spelling of the same
// name compare equal. Normalization deliberately stops at NFC: marks are never
// stripped, so "José" still differs from "Jose" and distinct artists are not merged.
const TOKEN_DELIMITER = /[^\p{L}\p{N}\p{M}]+/u;

// A run of combining marks with no base letter or digit carries no identity, so it
// must never become a matchable token — otherwise two names could "agree" on stray
// diacritics alone.
const HAS_LETTER_OR_DIGIT = /[\p{L}\p{N}]/u;
const SHORT_CJK_ARTIST_CHARS = /^[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{M}\s]+$/u;

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
 *
 * Unicode-aware: see the token rule above. Returns an empty Set for missing, empty,
 * whitespace-only, punctuation-only and mark-only input, which callers must treat as
 * "no usable artist identity" rather than as a match.
 */
function extractArtistTokens(artist) {
  if (!artist || typeof artist !== 'string') return new Set();
  return new Set(
    artist
      .normalize('NFC')
      .toLowerCase()
      .split(TOKEN_DELIMITER)
      .filter((t) => t.length >= MIN_ARTIST_TOKEN_LENGTH
        && HAS_LETTER_OR_DIGIT.test(t)
        && !ARTIST_STOP_WORDS.has(t))
  );
}

/**
 * Canonical whole-name form used only for the conservative short-CJK exception.
 * Punctuation is intentionally retained: this is equality, not token overlap.
 */
function normalizeArtistForExactMatch(artist) {
  if (typeof artist !== 'string') return '';
  return artist.normalize('NFC').toLowerCase().replace(/\s+/gu, '');
}

/**
 * Short CJK names need an exact full-name comparison because their single Han,
 * Hiragana/Katakana, or Hangul token cannot clear the Latin token-length floor.
 * The code-point count avoids treating UTF-16 surrogate pairs as two characters.
 */
function isShortCjkArtistName(artist) {
  const normalized = normalizeArtistForExactMatch(artist);
  if (!normalized || !SHORT_CJK_ARTIST_CHARS.test(normalized)) return false;
  const letters = [...normalized].filter((character) => /[\p{L}]/u.test(character));
  return letters.length >= 1 && letters.length <= 2;
}

function matchesShortCjkArtistExactly(targetArtist, candidateArtist) {
  if (!isShortCjkArtistName(targetArtist) || !isShortCjkArtistName(candidateArtist)) return false;
  return normalizeArtistForExactMatch(targetArtist) === normalizeArtistForExactMatch(candidateArtist);
}

/**
 * Splits a title into comparable segments.
 *
 * Bracketed annotations are removed first (identical to discovery's rules), then the
 * remainder is split on the shared Unicode token rule. Keeping the segments — rather
 * than only the concatenated form — is what makes a safe containment check possible:
 * once separators are stripped there is no boundary left to test against.
 *
 * @param {string} title
 * @returns {string[]} lowercased, NFC-normalized segments
 */
function titleSegments(title) {
  const t = (title || '')
    .normalize('NFC')
    .toLowerCase()
    .replace(/\(from\s+"[^"]*"\)/gi, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/\[[^\]]*\]/g, '');
  return t.split(TOKEN_DELIMITER).filter(Boolean);
}

/**
 * Normalizes a title for comparison inside the matcher.
 *
 * Deliberately matcher-local rather than reusing discovery's normalizeTitle(), which
 * ends in /[^a-z0-9]/ and therefore collapses any non-Latin title to the empty string
 * — matchesTitle() would then reject it as title_mismatch. normalizeTitle() is shared
 * with discovery's dedup, so widening it there would change unrelated dedup
 * behaviour; this keeps the fix inside the stream matcher.
 *
 * The bracket-stripping steps are identical to discovery's. The only intentional
 * divergence is that Unicode letters/numbers/marks are kept instead of ASCII
 * alphanumerics only, so accented Latin letters survive as themselves ("café" rather
 * than "caf"). Separators are dropped, so this form is whitespace- and
 * punctuation-insensitive.
 */
function normalizeTitleForMatch(title) {
  return titleSegments(title).join('');
}

/**
 * True when `shorter` is exactly the leading or trailing run of `longer`.
 *
 * @param {string[]} shorter
 * @param {string[]} longer
 * @returns {boolean}
 */
function isLeadingOrTrailingRun(shorter, longer) {
  if (shorter.length === 0 || shorter.length >= longer.length) return false;
  const head = longer.slice(0, shorter.length);
  const tail = longer.slice(longer.length - shorter.length);
  return shorter.every((s, i) => s === head[i]) || shorter.every((s, i) => s === tail[i]);
}

/**
 * Collects all artist strings from candidate metadata across possible API shapes.
 */
function extractCandidateArtistString(candidate) {
  return extractCandidateArtistStrings(candidate).join(', ');
}

function extractCandidateArtistStrings(candidate) {
  if (!candidate || typeof candidate !== 'object') return [];
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

  return parts;
}

/**
 * Verifies that candidate artist shares at least one significant token with target artist.
 */
function matchesArtist(targetArtist, candidate) {
  const targetTokens = extractArtistTokens(targetArtist);
  const candidateArtistStrings = extractCandidateArtistStrings(candidate);
  const candidateArtistStr = candidateArtistStrings.join(', ');
  // A short CJK name has no usable token under the existing Latin floor. Accept
  // it only when one complete candidate artist field is the same short CJK name;
  // this permits duplicated metadata fields but prevents overlap and substring false positives.
  if (candidateArtistStrings.some((artist) => matchesShortCjkArtistExactly(targetArtist, artist))) {
    return true;
  }

  if (targetTokens.size === 0) {
    // If target has no extractable artist tokens, cannot reliably verify.
    return false;
  }

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
  const targetSegs = titleSegments(targetTitle);
  const candidateSegs = titleSegments(candidateTitle);
  const wanted = targetSegs.join('');
  const got = candidateSegs.join('');

  if (!wanted || !got) return false;

  // Exact normalized match (whitespace- and punctuation-insensitive)
  if (wanted === got) return true;

  // Containment exists for ONE real case: the canonical YouTube target carries an
  // unbracketed annotation the provider's title does not, e.g. target
  // "Kesariya - Official Video" vs candidate "Kesariya". Bracketed annotations never
  // need it — they are stripped above and compare exactly.
  //
  // It is restricted in two ways, both from demonstrated false positives:
  //
  //  1. DIRECTION. Only the target may be the longer side. A provider candidate whose
  //     title is longer than the canonical one is a different song far more often than
  //     it is the same one: target "प्यार" accepted candidate "प्यार का दर्द" (and
  //     "Pyaar" accepted "Pyaar Ka Dard") purely by substring. A false negative here
  //     is safe — the resolver falls back to the YouTube stream — whereas a false
  //     positive plays the wrong recording.
  //
  //  2. SEGMENT ALIGNMENT. The candidate must equal a whole leading or trailing run of
  //     the target's segments, so a mid-word hit cannot match: target "प्यारी" must not
  //     accept candidate "प्यार", and "Hello World" must not accept "Hell".
  //
  // The pre-existing length floor is unchanged.
  if (wanted.length >= 4 && got.length >= 4
    && isLeadingOrTrailingRun(candidateSegs, targetSegs)) {
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
  extractCandidateArtistStrings,
  matchesArtist,
  normalizeArtistForExactMatch,
  isShortCjkArtistName,
  matchesShortCjkArtistExactly,
  matchesTitle,
  titleSegments,
  isLeadingOrTrailingRun,
  normalizeTitleForMatch,
  parseDurationSeconds,
  MAX_STREAM_DURATION_DELTA_SECONDS,
  MIN_ARTIST_TOKEN_LENGTH,
};
