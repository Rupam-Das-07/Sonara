'use strict';

/**
 * trendingService.js — Makes the Trending feed playable.
 *
 * THE DEFECT
 * ----------
 * Trending came straight from jiosaavnV2Provider, whose normalizer sets
 * `videoId: null` with the comment "JioSaavn IDs are not YouTube videoIds".
 * Sonara's canonical track identity IS the YouTube videoId, and streaming
 * resolves through it, so every trending item shipped to the client was
 * unplayable — a fully-rendered module where nothing responds to a tap.
 *
 * THE APPROACH — AND IT IS A CHOSEN ONE
 * -------------------------------------
 * JioSaavn is retained only as the *editorial signal* for what is currently
 * trending (title + artist). Each item is then re-resolved to a real YouTube
 * videoId by matching title and artist through ytmusicProvider.searchSongs — the
 * same provider that powers production search. Items that cannot be confidently
 * matched are DROPPED rather than shipped unplayable.
 *
 * This is the one Stage 0 repair where more than one defensible option existed:
 *   a) resolve JioSaavn items to YouTube (this file);
 *   b) drop JioSaavn and build trending from YTMusic charts directly;
 *   c) hide the Trending module until a first-party trending source exists.
 * (a) was chosen because it preserves the existing curated trending signal while
 * satisfying the frozen playability contract, and because it required no new
 * external dependency. It should be revisited if a first-party trending source
 * becomes available, since it inherits JioSaavn's regional bias and depends on a
 * third-party self-hosted mirror the project does not control.
 *
 * Matching is deliberately conservative. Accepting the first search hit would
 * quietly substitute karaoke tracks, covers and 8D remixes for real releases, so
 * candidates must clear a title, artist and duration check, and the resulting
 * videoId is validated with the same validator the stream endpoint uses.
 */

const BoundedCache = require('../utils/BoundedCache');
const ytmusic = require('../search/ytmusicProvider');
const { fetchV2Trending } = require('./providers/jiosaavnDiscoveryProvider');
const { isJunkVariant, normalizeTitle } = require('./artistCatalogService');
const { validateVideoId } = require('../stream/streamValidator');
const logger = require('../utils/logger');

// Resolutions are stable — a song's YouTube id does not change. 24h.
const _resolutionCache = new BoundedCache({ maxSize: 1000, ttlMs: 24 * 60 * 60 * 1000 });
const _feedCache = new BoundedCache({ maxSize: 4, ttlMs: 30 * 60 * 1000 });

const MAX_TRENDING_ITEMS = 20;
const RESOLVE_CONCURRENCY = 4;
const MAX_DURATION_DELTA_SECONDS = 45;
const MIN_TOKEN_LENGTH = 3;

/**
 * Significant lowercase word tokens from an artist string.
 * JioSaavn joins collaborators with ", "; YTMusic uses its own formatting, so
 * comparison is by token overlap rather than string equality.
 */
function artistTokens(artist) {
  return new Set(
    String(artist || '')
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .filter((t) => t.length >= MIN_TOKEN_LENGTH)
  );
}

function sharesArtistToken(a, b) {
  const setA = artistTokens(a);
  for (const token of artistTokens(b)) {
    if (setA.has(token)) return true;
  }
  return false;
}

/**
 * Decides whether a YTMusic candidate is the same recording as a trending item.
 *
 * @param {object} item - Normalized JioSaavn trending item.
 * @param {object} candidate - CanonicalTrack from ytmusicProvider.
 * @returns {{ ok: boolean, exact: boolean, reason?: string }}
 */
function evaluateCandidate(item, candidate) {
  if (!candidate || !candidate.videoId) return { ok: false, exact: false, reason: 'no_video_id' };
  if (!validateVideoId(candidate.videoId).valid) return { ok: false, exact: false, reason: 'bad_video_id' };
  if (isJunkVariant(candidate.title)) return { ok: false, exact: false, reason: 'junk_variant' };

  const wanted = normalizeTitle(item.title);
  const got = normalizeTitle(candidate.title);
  if (!wanted || !got) return { ok: false, exact: false, reason: 'empty_title' };

  const exact = wanted === got;
  const contains = wanted.length >= 4 && got.length >= 4 && (got.includes(wanted) || wanted.includes(got));
  if (!exact && !contains) return { ok: false, exact: false, reason: 'title_mismatch' };

  if (!sharesArtistToken(item.artist, candidate.artist)) {
    return { ok: false, exact: false, reason: 'artist_mismatch' };
  }

  // Reject extended mixes / full-album uploads, but tolerate different masters.
  if (item.duration > 0 && candidate.duration > 0) {
    if (Math.abs(item.duration - candidate.duration) > MAX_DURATION_DELTA_SECONDS) {
      return { ok: false, exact: false, reason: 'duration_mismatch' };
    }
  }

  return { ok: true, exact };
}

/**
 * Resolves one trending item to a playable canonical track.
 *
 * @param {object} item - Normalized JioSaavn trending item.
 * @returns {Promise<object|null>} Playable track DTO, or null if unresolvable.
 */
async function resolveTrendingItem(item) {
  const cacheKey = `trend:${item.id}`;
  const cached = _resolutionCache.get(cacheKey);
  if (cached) return cached;

  let candidates;
  try {
    candidates = await ytmusic.searchSongs(`${item.title} ${item.artist}`);
  } catch (err) {
    logger.warn(`[trendingService] Search failed for "${item.title}": ${err.message}`);
    return null;
  }

  if (!Array.isArray(candidates) || candidates.length === 0) return null;

  let best = null;
  let lastReason = 'no_candidates';

  for (const candidate of candidates.slice(0, 6)) {
    const verdict = evaluateCandidate(item, candidate);
    if (!verdict.ok) {
      lastReason = verdict.reason;
      continue;
    }
    // An exact normalized-title match wins immediately; a looser containment
    // match is held back in case an exact one appears further down the list.
    if (verdict.exact) {
      best = candidate;
      break;
    }
    if (!best) best = candidate;
  }

  if (!best) {
    logger.debug(`[trendingService] Unresolved: "${item.title}" by "${item.artist}" (${lastReason})`);
    return null;
  }

  const thumbnails = Array.isArray(best.thumbnails) ? best.thumbnails : [];
  const ytArtwork = thumbnails.length > 0 ? thumbnails[thumbnails.length - 1].url : '';

  const resolved = {
    id: best.videoId,
    videoId: best.videoId,
    title: best.title,
    artist: best.artist || item.artist,
    album: best.album || item.album || '',
    duration: best.duration || item.duration || 0,
    // Prefer YTMusic artwork so the client's existing artwork upgrade pipeline
    // applies uniformly; fall back to the JioSaavn image only if absent.
    artworkUrl: ytArtwork || item.artworkUrl || '',
    thumbnails: thumbnails.length > 0 ? thumbnails : item.thumbnails || [],
    resultType: 'song',
  };

  _resolutionCache.set(cacheKey, resolved);
  return resolved;
}

/**
 * Runs an async mapper over items with bounded concurrency, so resolving 20
 * trending items does not fire 20 simultaneous searches at the Python service.
 */
async function mapWithConcurrency(items, limit, mapper) {
  const results = new Array(items.length).fill(null);
  let cursor = 0;

  async function worker() {
    while (cursor < items.length) {
      const index = cursor++;
      try {
        results[index] = await mapper(items[index]);
      } catch {
        results[index] = null;
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

/**
 * Returns the trending feed as playable canonical tracks.
 *
 * Every returned item carries a validated 11-character YouTube videoId.
 * Unresolvable items are omitted, so the list may be shorter than the upstream
 * feed and may legitimately be empty — which the client must treat as the
 * module's empty state, not an error.
 *
 * @returns {Promise<object[]>}
 */
async function getTrendingTracks() {
  const cached = _feedCache.get('trending');
  if (cached) return cached;

  const raw = await fetchV2Trending();
  if (!Array.isArray(raw) || raw.length === 0) {
    logger.warn('[trendingService] Upstream trending feed returned no items');
    return [];
  }

  const items = raw.slice(0, MAX_TRENDING_ITEMS);
  const resolved = (await mapWithConcurrency(items, RESOLVE_CONCURRENCY, resolveTrendingItem))
    .filter(Boolean);

  // Deduplicate: distinct JioSaavn entries can resolve to the same YouTube video.
  const seen = new Set();
  const tracks = resolved.filter((t) => {
    if (seen.has(t.videoId)) return false;
    seen.add(t.videoId);
    return true;
  });

  logger.info(`[trendingService] Resolved ${tracks.length}/${items.length} trending items to playable videoIds`);

  if (tracks.length > 0) _feedCache.set('trending', tracks);
  return tracks;
}

module.exports = {
  getTrendingTracks,
  resolveTrendingItem,
  evaluateCandidate,
  artistTokens,
  sharesArtistToken,
};
