'use strict';

/**
 * quickPicksService.js — Server-side Quick Picks for the Android Home screen.
 *
 * WHY THIS EXISTS
 * ---------------
 * The Android "Quick Picks" module was previously fed by
 * CatalogRepositoryImpl.getHomeCatalog() — three hard-coded `curated_1..3`
 * entries whose ids are NOT YouTube videoIds and whose artwork was stock
 * imagery. Under Android's videoId-only playback contract those rows are
 * unplayable, so the module violated the project's "every track must be
 * genuinely playable" rule. This service replaces that stub with real,
 * playable tracks.
 *
 * DATA SOURCE DECISION (2026-08-26)
 * ---------------------------------
 * The Sonara Web Quick Picks component (QuickPicks.jsx) sources its picks from
 * `searchSongs("trending global songs")` — the YouTube Music search path — with
 * a SAFE_POOL fallback. It does NOT source from JioSaavn. In sonara-backend,
 * jiosaavnV2Provider hard-sets `videoId: null`, so JioSaavn items cannot play on
 * Android at all. Per the governing decision, Quick Picks preserves the Web
 * behaviour but is implemented server-side against `ytmusicProvider.searchSongs()`
 * (real YouTube videoIds), rather than touching JioSaavn.
 *
 * WHAT IS PORTED FROM THE WEB, AND WHAT IS NOT
 * --------------------------------------------
 *  - Ported: QUICK_PICKS_COUNT = 12; the "trending global songs" primary query;
 *    the SAFE_POOL_QUERIES fallback; `balanceVariety` (max 2 per artist, no
 *    consecutive same artist, artist key = first comma-split lowercased);
 *    session stability (≈45 min) with a manual `?refresh` rotation.
 *  - Simplified/deferred (disclosed): the Web's `applyExposureBalancing` (drop
 *    titles that appear in the curated playlists' top-3) requires resolving the
 *    curated-playlist catalog on every request — a cross-module, expensive
 *    dependency. The Web component itself bails out of exposure-balancing when it
 *    would leave fewer than 4 picks. Rather than couple Quick Picks to playlist
 *    resolution and its latency, exposure-balancing is deferred here. Quick Picks
 *    and Curated Playlists can therefore surface overlapping tracks; this is a
 *    known, disclosed simplification.
 *  - Simplified (disclosed): the Web's `blendWithRotation` retains 75% of the
 *    previous set (50% on manual refresh) using client-held prior state. Server
 *    stability is realized instead as a time-windowed cache (stable within a
 *    session) plus a rotating SAFE_POOL query on `?refresh`, which changes the
 *    set on demand without needing to carry the client's previous picks.
 *
 * GUARANTEES
 * ----------
 *  - Never throws. `searchSongs` returns [] on failure; this service degrades to
 *    an empty array, and the Home module self-hides when empty.
 *  - Every returned pick has a real 11-character YouTube videoId (playable).
 *  - Output DTO shape is byte-for-byte the same as routes/search.js `toTrackDTO`,
 *    so the Android TrackMapper path is identical to search.
 */

const BoundedCache = require('../utils/BoundedCache');
const { searchSongs } = require('../search/ytmusicProvider');
const logger = require('../utils/logger');

// ---------------------------------------------------------------------------
// Constants (ported from the Web QuickPicks component)
// ---------------------------------------------------------------------------
const QUICK_PICKS_COUNT = 12;
const MAX_PER_ARTIST = 2;
const MIN_ACCEPTABLE = 4; // Web bailed below this; below it we still return what we have.
const SESSION_STABILITY_MS = 45 * 60 * 1000; // 45 min — matches Web SESSION_STABILITY_MS.

const PRIMARY_QUERY = 'trending global songs';
const SAFE_POOL_QUERIES = [
  'trending bollywood',
  'latest hindi songs',
  'romantic hindi hits',
  'bollywood party songs',
  'hindi chill vibes',
];

// A real YouTube videoId is 11 chars of [A-Za-z0-9_-]. This is the canonical
// identity Android's playback contract requires; anything else is unplayable.
const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;

const _cache = new BoundedCache({ maxSize: 8, ttlMs: SESSION_STABILITY_MS });
let _rotationIndex = 0;

// ---------------------------------------------------------------------------
// Pure helpers (exported for network-free unit testing)
// ---------------------------------------------------------------------------

/**
 * Artist grouping key: the first comma-separated artist, lowercased and trimmed.
 * "Arijit Singh, Shreya Ghoshal" and "arijit singh" collapse to one artist.
 *
 * @param {string} artist
 * @returns {string}
 */
function artistKey(artist) {
  return String(artist || '')
    .split(',')[0]
    .trim()
    .toLowerCase();
}

/**
 * Keeps only genuinely playable tracks and de-duplicates by videoId.
 *
 * A track survives only if it carries a real 11-char YouTube videoId. This is
 * what makes JioSaavn-style `videoId: null` rows — and any malformed id — drop
 * out before they can reach the client.
 *
 * @param {object[]} tracks - CanonicalTrack[] (or anything array-like).
 * @returns {object[]} Playable, de-duplicated tracks in original order.
 */
function sanitizePlayable(tracks) {
  const seen = new Set();
  const out = [];
  for (const t of Array.isArray(tracks) ? tracks : []) {
    const vid = t && (t.videoId || t.id);
    if (!vid || !VIDEO_ID_RE.test(vid)) continue;
    if (seen.has(vid)) continue;
    seen.add(vid);
    out.push(t);
  }
  return out;
}

/**
 * Enforces variety: at most MAX_PER_ARTIST tracks per artist, and no two
 * consecutive tracks by the same artist when an alternative exists.
 *
 * Two passes so that the "no consecutive" rule reorders rather than discards:
 *   1. Cap each artist at MAX_PER_ARTIST (order preserved).
 *   2. Greedily emit, always preferring the next track whose artist differs from
 *      the one just emitted; if every remaining track shares that artist, accept
 *      it rather than drop it.
 *
 * @param {object[]} tracks
 * @returns {object[]}
 */
function balanceVariety(tracks) {
  // Pass 1 — cap per artist.
  const counts = new Map();
  const capped = [];
  for (const t of Array.isArray(tracks) ? tracks : []) {
    const key = artistKey(t.artist);
    const used = counts.get(key) || 0;
    if (used >= MAX_PER_ARTIST) continue;
    counts.set(key, used + 1);
    capped.push(t);
  }

  // Pass 2 — reorder to avoid consecutive same-artist.
  const remaining = capped.slice();
  const ordered = [];
  while (remaining.length > 0) {
    let idx = 0;
    if (ordered.length > 0) {
      const lastKey = artistKey(ordered[ordered.length - 1].artist);
      const alt = remaining.findIndex((t) => artistKey(t.artist) !== lastKey);
      idx = alt >= 0 ? alt : 0; // all remaining share the artist -> accept.
    }
    ordered.push(remaining.splice(idx, 1)[0]);
  }
  return ordered;
}

/**
 * Maps a CanonicalTrack to the Android-facing DTO.
 * Identical in shape to routes/search.js `toTrackDTO`, so the Android client
 * parses Quick Picks with the exact same mapper it uses for search results.
 *
 * @param {object} track
 * @returns {object} TrackDTO
 */
function toDTO(track) {
  const thumbnails = track.thumbnails || [];
  const artworkUrl = thumbnails.length > 0 ? thumbnails[thumbnails.length - 1].url : '';
  return {
    id: track.id,
    videoId: track.videoId,
    title: track.title,
    artist: track.artist,
    album: track.album || '',
    duration: track.duration,
    artworkUrl,
    thumbnails,
    resultType: track.resultType,
  };
}

/**
 * Advances and returns the next SAFE_POOL query. Module-level rotation gives a
 * different fallback query each time `?refresh` is used, so the set visibly
 * changes without needing the client's previous picks.
 *
 * @returns {string}
 */
function nextSafePoolQuery() {
  const q = SAFE_POOL_QUERIES[_rotationIndex % SAFE_POOL_QUERIES.length];
  _rotationIndex += 1;
  return q;
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

/**
 * Fetches, sanitizes, balances and trims a fresh set of picks.
 *
 * Normal loads lead with the Web's "trending global songs" query and top up from
 * the first SAFE_POOL query only if that returns too few playable tracks.
 * Refreshes lead with a rotated SAFE_POOL query so the surface genuinely changes.
 * Once enough playable tracks are collected the loop stops calling upstream.
 *
 * @param {boolean} refresh
 * @returns {Promise<object[]>} Up to QUICK_PICKS_COUNT playable TrackDTOs.
 */
async function buildPicks(refresh) {
  const queries = refresh
    ? [nextSafePoolQuery(), PRIMARY_QUERY]
    : [PRIMARY_QUERY, SAFE_POOL_QUERIES[0]];

  let combined = [];
  for (const q of queries) {
    const raw = await searchSongs(q); // never throws; [] on failure
    combined = combined.concat(raw);
    if (sanitizePlayable(combined).length >= QUICK_PICKS_COUNT) break;
  }

  const playable = sanitizePlayable(combined);
  const balanced = balanceVariety(playable);
  return balanced.slice(0, QUICK_PICKS_COUNT).map(toDTO);
}

/**
 * Public entry point.
 *
 * Session-stable: within SESSION_STABILITY_MS a normal request returns the same
 * cached set. `refresh: true` bypasses the cache and rotates the fallback query.
 * A too-thin set (< MIN_ACCEPTABLE) is not cached, so a transient upstream
 * hiccup cannot pin an almost-empty module for the full window.
 *
 * @param {{ refresh?: boolean }} [opts]
 * @returns {Promise<object[]>} Quick Picks TrackDTOs (possibly empty).
 */
async function getQuickPicks({ refresh = false } = {}) {
  const cacheKey = 'quickpicks';

  if (!refresh) {
    const cached = _cache.get(cacheKey);
    if (cached && cached.length >= MIN_ACCEPTABLE) return cached;
  }

  const picks = await buildPicks(refresh);

  if (picks.length >= MIN_ACCEPTABLE) {
    _cache.set(cacheKey, picks);
  } else {
    logger.warn(`[quickPicks] Only ${picks.length} playable pick(s) resolved; not caching`);
  }

  return picks;
}

module.exports = {
  getQuickPicks,
  // Exported for unit tests / reuse:
  buildPicks,
  balanceVariety,
  sanitizePlayable,
  artistKey,
  toDTO,
  nextSafePoolQuery,
  QUICK_PICKS_COUNT,
  MAX_PER_ARTIST,
  MIN_ACCEPTABLE,
  PRIMARY_QUERY,
  SAFE_POOL_QUERIES,
};
