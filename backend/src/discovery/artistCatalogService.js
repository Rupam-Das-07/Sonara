'use strict';

/**
 * artistCatalogService.js — Deep Artist Catalog Engine in sonara-backend
 *
 * Fetches an artist's catalog from the shared Python YTMusic service, then
 * applies junk-variant rejection and title-level deduplication.
 *
 * REPAIRS APPLIED
 * ---------------
 * Both outbound URLs were wrong, so this module never returned any tracks:
 *
 *  - The primary call was `GET {python}/artist-catalog/{id}`. The Python service
 *    exposes `GET /api/artist-catalog-deep?artistName=|browseId=`. The `/api`
 *    prefix was missing and the path name differed, so it always 404'd.
 *  - The fallback was `POST {python}/search` with a JSON body. Python exposes
 *    `GET /api/search?q=`. Wrong prefix, wrong path and wrong HTTP method, so
 *    the fallback always 404'd too.
 *
 * The function therefore always reached its final `return` and handed back
 * `{ tracks: [] }`, which is why the artist drill-through appeared empty. A
 * consequence was that `isJunkVariant` and `normalizeTitle` — the module's stated
 * purpose — never executed on real data. They are now applied on both paths.
 *
 * The deep endpoint returns `{ artistName, browseId, artistImage, description,
 * subscriberCount, tracks, totalRawTracks }` and resolves the browseId from a
 * name on its own, with an internal deep-search supplement. It carries a 20s
 * server-side gevent timeout, so the client timeout must exceed the old 10s.
 */

const BoundedCache = require('../utils/BoundedCache');
const { createTrack } = require('../search/trackModel');
const config = require('../config/env');
const logger = require('../utils/logger');

const artistCatalogCache = new BoundedCache({ maxSize: 500, ttlMs: 8 * 60 * 60 * 1000 }); // 8 hours

// Python's /api/artist-catalog-deep wraps its work in a 20s gevent timeout.
const DEEP_CATALOG_TIMEOUT_MS = 21000;
const SEARCH_FALLBACK_TIMEOUT_MS = 8000;
const MAX_CATALOG_TRACKS = 60;

const JUNK_TITLE_PATTERNS = [
  /\(?\s*lyric(s|al)?\s*(video)?\s*\)?/i,
  /\(?\s*official\s+(lyric|audio)\s*(video)?\s*\)?/i,
  /\(?\s*slowed\s*(\+\s*reverb|& reverb)?\s*\)?/i,
  /\(?\s*reverb\s*\)?/i,
  /\(?\s*sped\s+up\s*\)?/i,
  /\(?\s*8d\s+audio\s*\)?/i,
  /\(?\s*karaoke\s*\)?/i,
  /\(?\s*cover\s*\)?/i,
  /\(?\s*remix\s*\)?/i,
  /\(?\s*remaster(ed)?\s*\)?/i,
  /\(?\s*live\s+(at|from|in|session|performance|concert)\b/i,
  /\(?\s*acoustic\s+version\s*\)?/i,
  /\(?\s*unplugged\s*\)?/i,
  /\(?\s*instrumental\s*\)?/i,
];

function isJunkVariant(title) {
  const lower = (title || '').toLowerCase();
  if (lower.includes('karaoke') || lower.includes('8d audio') || lower.includes('cover')) return true;
  return JUNK_TITLE_PATTERNS.some((re) => re.test(title));
}

function normalizeTitle(title) {
  let t = (title || '').toLowerCase();
  t = t.replace(/\(from\s+"[^"]*"\)/gi, '');
  t = t.replace(/\([^)]*\)/g, '');
  t = t.replace(/\[[^\]]*\]/g, '');
  t = t.replace(/[^a-z0-9]/g, '');
  return t;
}

/**
 * Canonicalizes raw Python tracks, drops junk variants, and deduplicates by
 * both videoId and normalized title so "Song", "Song (Lyrical)" and
 * "Song (Official Video)" collapse to one entry.
 *
 * @param {object[]} rawTracks
 * @param {string} fallbackArtist
 * @returns {object[]} Outbound track DTOs
 */
function dedupeAndClean(rawTracks, fallbackArtist) {
  const out = [];
  const seenIds = new Set();
  const seenTitles = new Set();

  for (const raw of Array.isArray(rawTracks) ? rawTracks : []) {
    if (out.length >= MAX_CATALOG_TRACKS) break;

    const track = createTrack(raw);
    if (!track || !track.videoId) continue;
    if (isJunkVariant(track.title)) continue;

    const titleKey = normalizeTitle(track.title);
    if (!titleKey) continue;
    if (seenIds.has(track.videoId) || seenTitles.has(titleKey)) continue;

    seenIds.add(track.videoId);
    seenTitles.add(titleKey);

    const thumbnails = Array.isArray(track.thumbnails) ? track.thumbnails : [];
    out.push({
      id: track.videoId,
      videoId: track.videoId,
      title: track.title,
      artist: track.artist || fallbackArtist || 'Unknown Artist',
      album: track.album || '',
      duration: track.duration || 0,
      artworkUrl: thumbnails.length > 0 ? thumbnails[thumbnails.length - 1].url : '',
      thumbnails,
      resultType: 'song',
    });
  }

  return out;
}

/**
 * True only for values shaped like a real YTMusic artist browseId.
 *
 * This guard matters because Python's /api/artist-catalog-deep only resolves a
 * browseId from the artist name when no browseId was supplied. If a caller
 * passes a slug or a display name in the browseId position, Python would accept
 * it, fail the official-playlist lookup, and silently fall back to deep search —
 * returning a thinner catalog. Filtering here keeps name-based lookups on the
 * accurate path.
 */
function isRealBrowseId(value) {
  return typeof value === 'string' && /^(UC|MPLA|MPAD)[A-Za-z0-9_-]{10,}$/.test(value);
}

function emptyCatalog(artistName, browseId) {
  return {
    artist: artistName || '',
    browseId: browseId || null,
    artistImage: '',
    description: '',
    subscriberCount: '',
    tracks: [],
    albums: [],
  };
}

/**
 * Fallback path: plain song search for the artist's name.
 * Used only when the deep catalog endpoint is unavailable.
 */
async function searchFallback(artistName, browseId) {
  const query = `${artistName || browseId} songs`;
  const url = `${config.pythonYtmusicUrl}/api/search?q=${encodeURIComponent(query)}`;

  const res = await fetch(url, {
    signal: AbortSignal.timeout(SEARCH_FALLBACK_TIMEOUT_MS),
    headers: { Accept: 'application/json' },
  });

  if (!res.ok) return emptyCatalog(artistName, browseId);

  const data = await res.json();
  const rawTracks = Array.isArray(data) ? data : (data.results || []);

  return {
    ...emptyCatalog(artistName, browseId),
    tracks: dedupeAndClean(rawTracks, artistName),
  };
}

/**
 * Builds an artist catalog, preferring the deep YTMusic endpoint.
 *
 * @param {string} browseId - YTMusic artist browseId, may be null.
 * @param {string} artistName - Display name, may be null if browseId is known.
 * @returns {Promise<object>} Catalog object. Never throws; degrades to empty.
 */
async function generateArtistCatalog(browseId, artistName) {
  // The route derives artistName as `req.query.name || browseId`, so either
  // position may hold either kind of value. Normalize before doing anything.
  const resolvedBrowseId = isRealBrowseId(browseId) ? browseId : null;
  const resolvedName = isRealBrowseId(artistName) ? '' : (artistName || browseId || '');

  if (!resolvedBrowseId && !resolvedName) {
    return emptyCatalog('', null);
  }

  const cacheKey = `artist:${resolvedBrowseId || resolvedName.toLowerCase().trim()}`;
  const cached = artistCatalogCache.get(cacheKey);
  if (cached) return cached;

  try {
    const params = new URLSearchParams();
    if (resolvedName) params.append('artistName', resolvedName);
    if (resolvedBrowseId) params.append('browseId', resolvedBrowseId);

    const url = `${config.pythonYtmusicUrl}/api/artist-catalog-deep?${params.toString()}`;
    const res = await fetch(url, {
      signal: AbortSignal.timeout(DEEP_CATALOG_TIMEOUT_MS),
      headers: { Accept: 'application/json' },
    });

    let catalog;

    if (res.ok) {
      const data = await res.json();
      catalog = {
        artist: data.artistName || resolvedName || '',
        browseId: data.browseId || resolvedBrowseId || null,
        artistImage: data.artistImage || '',
        description: data.description || '',
        subscriberCount: data.subscriberCount || '',
        tracks: dedupeAndClean(data.tracks, data.artistName || resolvedName),
        albums: [],
      };
    } else {
      logger.warn(`[artistCatalogService] Deep catalog returned ${res.status}; falling back to search`);
      catalog = await searchFallback(resolvedName, resolvedBrowseId);
    }

    // Never cache an empty catalog — a transient provider outage would otherwise
    // pin an empty artist page for eight hours.
    if (catalog.tracks.length > 0) {
      artistCatalogCache.set(cacheKey, catalog);
    } else {
      logger.warn(`[artistCatalogService] Empty catalog for "${resolvedName || resolvedBrowseId}" (not cached)`);
    }

    return catalog;
  } catch (err) {
    logger.warn(`[artistCatalogService] Failed to generate catalog for ${resolvedBrowseId || resolvedName}: ${err.message}`);
    return emptyCatalog(resolvedName, resolvedBrowseId);
  }
}

module.exports = { generateArtistCatalog, isJunkVariant, normalizeTitle, isRealBrowseId };
