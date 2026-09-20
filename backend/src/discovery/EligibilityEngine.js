// backend/src/discovery/EligibilityEngine.js
//
// Curated Playlist Data Quality Engine V2.
// Deterministic, stateless gatekeeper.
// Decides whether a single Track is eligible for a specific playlist
// based on that playlist's EligibilityConfig from PlaylistDefinitions.js.
//
// Rules checked:
//   1. Duration bounds (configurable, default 90s - 480s / 720s)
//   2. Spoken word / non-music rejection (speeches, podcasts, lectures)
//   3. Megamixes, continuous DJ sets & album jukeboxes (never naked "mix")
//   4. Promotional teasers, trailers & clips
//   5. Low-quality format junk (karaoke, 8D audio, sped up, slowed reverb)
//   6. allowRemix — compound remix/mashup check (never naked "mix")
//   7. allowLofi — lofi keyword check
//   8. allowSlowedReverb — slowed/reverb check
//   9. allowInstrumental — instrumental check
//  10. excludedGenres — title + artist keyword check
//  11. Defensive Era Contamination Detection (negative artists & keywords, title years)
//
// Return shape: { eligible: boolean, reason: string }
//   reason is 'ok' when eligible, or a short descriptor of why it was rejected.

'use strict';

// ── Default duration bounds ──────────────────────────────────────────────────
const DEFAULT_MIN_DURATION_SECONDS = 90;   // Reject short clips, teasers, reels audio
const DEFAULT_MAX_DURATION_SECONDS = 480;  // Reject ultra-long tracks / DJ sets (8 mins)

// ── Global Hard Rejection Patterns ───────────────────────────────────────────

// 1. Spoken-word, lectures, motivational speeches, religious discourses
// NOTE: Naked "speech" is intentionally excluded — it appears in legitimate song titles
// (e.g. "Freedom of Speech"). Only compound, clearly spoken-content constructions are matched.
const SPOKEN_WORD_RE = /\b((?:motivational|inspirational|powerful|political|full)\s+speech|speech\s+by\b|podcast|lecture|satsang|katha|dialogue\s+promo|interview)\b/i;

// 2. Megamixes, nonstop mixes, full album rips, jukeboxes
// NOTE: The naked word "mix" is NEVER matched. Only explicit compound multi-track patterns.
const MEGAMIX_RE = /\b(non\s*stop\s*mix|nonstop\s*mix|megamix|dj\s*non\s*stop|audio\s*jukebox|full\s*album|complete\s*album|top\s*\d+\s*songs\s*mashup)\b/i;

// 3. Promotional teasers, trailers, motion posters, short status clips
const TEASER_RE = /\b(teaser|trailer|motion\s*poster|status\s*video|short\s*clip|preview|ringtone)\b/i;

// 4. Low-quality format junk
const JUNK_FORMAT_RE = /\b(karaoke|instrumental\s*cover|8d\s*audio|sped\s*up|slowed\s*\+\s*reverb|slowed\s*reverb)\b/i;

// ── Version / Variant Keyword Sets ───────────────────────────────────────────

// Compound remix & mashup patterns. Applied only when allowRemix === false.
// NOTE: The naked word "mix" is intentionally excluded to allow legitimate titles like "Mix of Emotions".
const COMPOUND_REMIX_RE = /\b(remix|remixed|re-mix|mashup|mash\s*up|club\s*mix|party\s*mix|dj\s*mix|dance\s*mix|lofi\s*flip|flip\s*edit|jhankar\s*beats?|jhankar)\b/i;

const LOFI_RE = /\b(lofi|lo-fi|lo\s*fi)\b/i;
const SLOWED_REVERB_RE = /\b(slowed|slowed\s*\+\s*reverb|slowed\s*reverb)\b/i;
const INSTRUMENTAL_RE = /\b(instrumental|bgm|karaoke|8d|8d\s*audio)\b/i;

// Map genre names to keywords
const GENRE_KEYWORD_MAP = {
  edm:        ['edm', 'electronic', 'electro', 'rave', 'techno', 'house music', 'trance', 'dubstep'],
  metal:      ['metal', 'heavy metal', 'thrash', 'death metal'],
  hardcore:   ['hardcore', 'hardstyle'],
  drill:      ['drill'],
  hip_hop:    ['hip hop', 'hip-hop', 'rap', 'trap'],
  'hip-hop':  ['hip hop', 'hip-hop', 'rap', 'trap'],
  party:      ['party mix', 'club mix', 'dj mix'],
  ghazal:     ['ghazal', 'gazal'],
  devotional: ['aarti', 'bhajan', 'kirtan', 'chalisa', 'mantra', 'stotra'],
  classical:  ['classical', 'raag ', 'raga ', 'thumri', 'dhrupad'],
  sad:        [],
  lofi:       ['lofi', 'lo-fi'],
  modern_pop: [],
};

// 4-digit release year regex for title/album inspection
const FOUR_DIGIT_YEAR_RE = /\b(19\d{2}|20\d{2})\b/;

// ── Pure helpers ──────────────────────────────────────────────────────────────

/**
 * Escapes regex metacharacters in a string so it can be safely embedded in a RegExp.
 * @param {string} str
 * @returns {string}
 */
function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * F-02: Token-boundary keyword matching.
 * Checks whether any keyword from the list appears as a full token in haystack.
 * Uses (^|[^\w])keyword([^\w]|$) to prevent substring collisions like "war" in "Deewar".
 * All comparison is case-insensitive.
 *
 * @param {string} haystack
 * @param {string[]} keywords
 * @returns {boolean}
 */
function containsAny(haystack, keywords) {
  if (!haystack || !Array.isArray(keywords) || keywords.length === 0) return false;
  const text = haystack.toLowerCase();
  return keywords.some((kw) => {
    if (!kw) return false;
    const pattern = new RegExp('(^|[^\\w])' + escapeRegex(kw.toLowerCase()) + '([^\\w]|$)', 'i');
    return pattern.test(text);
  });
}

/**
 * F-05: Strips remaster, reissue, anniversary, and restored packaging from a string.
 * Removes entire parenthetical/bracketed clauses containing these terms,
 * then strips any bare remaster+year or year+remaster patterns that survived.
 * This prevents packaging timestamps from being misread as the original release year.
 *
 * @param {string} str
 * @returns {string}
 */
function stripRemasterPackaging(str) {
  if (!str) return '';
  // Remove entire parenthetical/bracket clause containing a remaster/reissue/anniversary/restored/edition term
  let result = str.replace(/[\(\[][^\)\]]*(?:remaster(?:ed)?|reissue|anniversary|restored|deluxe)[^\)\]]*[\)\]]/gi, '');
  // Remove bare patterns not enclosed in brackets that may survive (e.g. "Remastered 2022")
  result = result.replace(/\b(?:remaster(?:ed)?|reissue|anniversary|restored)\s*\d{4}\b/gi, '');
  result = result.replace(/\b\d{4}\s*(?:remaster(?:ed)?|reissue|anniversary|restored)\b/gi, '');
  return result;
}

/**
 * F-05: Extracts a four-digit release year from a track's title or album string.
 * Remaster/reissue/anniversary packaging clauses are stripped first so that
 * "(2022 Remaster)" is not mistaken for the song's original release year.
 *
 * @param {string} title
 * @param {string} album
 * @returns {number|null}
 */
function extractTitleYear(title, album) {
  const cleanTitle = stripRemasterPackaging(title);
  const tMatch = cleanTitle.match(FOUR_DIGIT_YEAR_RE);
  if (tMatch) return parseInt(tMatch[1], 10);

  const cleanAlbum = stripRemasterPackaging(album);
  const aMatch = cleanAlbum.match(FOUR_DIGIT_YEAR_RE);
  if (aMatch) return parseInt(aMatch[1], 10);

  return null;
}

// ── Main export ───────────────────────────────────────────────────────────────

/**
 * Determines whether a Track is eligible for a playlist given its EligibilityConfig.
 *
 * @param {object} track
 * @param {object} config
 * @returns {{ eligible: boolean, reason: string }}
 */
function isEligible(track, config) {
  if (!track || typeof track !== 'object') {
    return { eligible: false, reason: 'invalid_track' };
  }

  const title = track.title || '';
  const artist = track.artist || '';
  const titleAndArtist = `${title} ${artist}`;
  const cfg = config || {};

  // ── 1. Duration bounds ─────────────────────────────────────────────────────
  const minDuration = typeof cfg.minDurationSeconds === 'number' ? cfg.minDurationSeconds : DEFAULT_MIN_DURATION_SECONDS;
  const maxDuration = typeof cfg.maxDurationSeconds === 'number' ? cfg.maxDurationSeconds : DEFAULT_MAX_DURATION_SECONDS;

  // F-09: duration must be a valid positive finite number.
  // null/undefined/NaN/Infinity/string/0/negative all indicate a broken or non-playable item.
  const d = track.duration;
  if (typeof d !== 'number' || !Number.isFinite(d) || d <= 0) {
    return { eligible: false, reason: 'invalid_or_missing_duration' };
  }
  if (d < minDuration) {
    return { eligible: false, reason: `duration_too_short (${d}s < ${minDuration}s)` };
  }
  if (d > maxDuration) {
    return { eligible: false, reason: `duration_too_long (${d}s > ${maxDuration}s)` };
  }


  // ── 2. Global Hard Rejections ───────────────────────────────────────────────
  // Non-music / Spoken word
  if (SPOKEN_WORD_RE.test(title)) {
    return { eligible: false, reason: 'spoken_word_not_allowed' };
  }

  // Megamixes, nonstop DJ sets, full album compilations
  if (MEGAMIX_RE.test(title)) {
    return { eligible: false, reason: 'megamix_not_allowed' };
  }

  // Promotional teasers, trailers, short clips
  if (TEASER_RE.test(title)) {
    return { eligible: false, reason: 'teaser_not_allowed' };
  }

  // Format junk (karaoke, 8D audio, sped up, slowed reverb)
  if (JUNK_FORMAT_RE.test(title)) {
    return { eligible: false, reason: 'format_junk_not_allowed' };
  }

  // ── 3. Remix / Mashup check ────────────────────────────────────────────────
  if (!cfg.allowRemix && COMPOUND_REMIX_RE.test(title)) {
    return { eligible: false, reason: 'remix_not_allowed' };
  }

  // ── 4. Lofi check ──────────────────────────────────────────────────────────
  if (!cfg.allowLofi && LOFI_RE.test(title)) {
    return { eligible: false, reason: 'lofi_not_allowed' };
  }

  // ── 5. Slowed / reverb check ───────────────────────────────────────────────
  if (!cfg.allowSlowedReverb && SLOWED_REVERB_RE.test(title)) {
    return { eligible: false, reason: 'slowed_reverb_not_allowed' };
  }

  // ── 6. Instrumental check ──────────────────────────────────────────────────
  if (!cfg.allowInstrumental && INSTRUMENTAL_RE.test(title)) {
    return { eligible: false, reason: 'instrumental_not_allowed' };
  }

  // ── 7. Excluded genres ─────────────────────────────────────────────────────
  if (Array.isArray(cfg.excludedGenres) && cfg.excludedGenres.length > 0) {
    for (const genre of cfg.excludedGenres) {
      const keywords = GENRE_KEYWORD_MAP[genre.toLowerCase()] || [genre.toLowerCase()];
      if (keywords.length > 0 && containsAny(titleAndArtist, keywords)) {
        return { eligible: false, reason: `excluded_genre:${genre}` };
      }
    }
  }

  // ── 8. Defensive Era Contamination Detection ───────────────────────────────
  // Negative artist/keyword rules are defensive contamination detectors, not authoritative release-date classifiers.
  // Never infer a song's exact release year solely from artist identity.

  // Defensive negative artist checks (catches modern artists in vintage playlists or vice-versa)
  if (Array.isArray(cfg.negativeArtists) && cfg.negativeArtists.length > 0) {
    if (containsAny(artist, cfg.negativeArtists)) {
      return { eligible: false, reason: 'negative_era_artist' };
    }
    if (Array.isArray(track.artists)) {
      for (const a of track.artists) {
        if (a && a.name && containsAny(a.name, cfg.negativeArtists)) {
          return { eligible: false, reason: 'negative_era_artist' };
        }
      }
    }
  }

  // Defensive negative keyword checks (catches modern film titles like "Dhurandhar" in vintage playlists)
  if (Array.isArray(cfg.negativeKeywords) && cfg.negativeKeywords.length > 0) {
    if (containsAny(title, cfg.negativeKeywords) || containsAny(track.album || '', cfg.negativeKeywords)) {
      return { eligible: false, reason: 'negative_era_keyword' };
    }
  }

  // Era upper bound: track must be BEFORE eraBeforeYear
  if (cfg.eraBeforeYear !== null && cfg.eraBeforeYear !== undefined) {
    const trackYear = track.year ? parseInt(track.year, 10) : null;
    if (trackYear !== null && !isNaN(trackYear) && trackYear >= cfg.eraBeforeYear) {
      return { eligible: false, reason: `era_too_recent (${trackYear} >= ${cfg.eraBeforeYear})` };
    }
    // Defensive title/album year inspection
    const extractedYear = extractTitleYear(title, track.album);
    if (extractedYear !== null && extractedYear >= cfg.eraBeforeYear) {
      return { eligible: false, reason: `era_too_recent (extracted ${extractedYear} >= ${cfg.eraBeforeYear})` };
    }
  }

  // Era lower bound: track must be FROM eraAfterYear or later
  if (cfg.eraAfterYear !== null && cfg.eraAfterYear !== undefined) {
    const trackYear = track.year ? parseInt(track.year, 10) : null;
    if (trackYear !== null && !isNaN(trackYear) && trackYear < cfg.eraAfterYear) {
      return { eligible: false, reason: `era_too_old (${trackYear} < ${cfg.eraAfterYear})` };
    }
    // Defensive title/album year inspection
    const extractedYear = extractTitleYear(title, track.album);
    if (extractedYear !== null && extractedYear < cfg.eraAfterYear) {
      return { eligible: false, reason: `era_too_old (extracted ${extractedYear} < ${cfg.eraAfterYear})` };
    }
  }

  // ── All checks passed ──────────────────────────────────────────────────────
  return { eligible: true, reason: 'ok' };
}

module.exports = {
  isEligible,
  SPOKEN_WORD_RE,
  MEGAMIX_RE,
  TEASER_RE,
  JUNK_FORMAT_RE,
  COMPOUND_REMIX_RE,
  DEFAULT_MIN_DURATION_SECONDS,
  DEFAULT_MAX_DURATION_SECONDS,
};
