'use strict';

/**
 * discoveryFeedService.js — Shared Native Discovery Feed Service for Sonara
 *
 * Provides bounded caching, in-flight stampede protection, and identity-aware
 * feed resolution for native YouTube Music discovery sources (Charts, Playlists).
 *
 * Guarantees:
 *  1. Cache keys cleanly separate feed types, countries, languages, and chart IDs.
 *  2. In-flight requests coalesce into a single upstream fetch.
 *  3. Bounded cache (max 50 entries, 6-hour TTL).
 *  4. Empty / non-OK results are NEVER cached to prevent sticky degradation.
 *  5. Normalized CanonicalTrack outputs.
 */

const BoundedCache = require('../utils/BoundedCache');
const ytmusic = require('../search/ytmusicProvider');
const { createTrack, validateTrack } = require('../search/trackModel');
const logger = require('../utils/logger');

const FEED_TTL_MS = 6 * 60 * 60 * 1000; // 6 hours
const FEED_MAX_ENTRIES = 50;

const _feedCache = new BoundedCache({ maxSize: FEED_MAX_ENTRIES, ttlMs: FEED_TTL_MS });
const _inFlightFeeds = new Map();

// ── Native New Releases shelf ────────────────────────────────────────────────
//
// FRESHNESS SEMANTICS — READ BEFORE CHANGING ANY CONSTANT BELOW.
// This is an EDITORIAL APPROXIMATION, not verified release-date filtering. Upstream
// supplies no release date anywhere on this path: the only freshness signals are
// membership of YouTube Music's own New Releases shelf and the container `type`
// ('Single' / 'EP' / 'Album'). Album-level `year` is positional in the upstream
// parser and frequently absent, so it is inherited only when present and NEVER
// synthesised. A Single or EP can still contain older recordings, reissues, or a
// recent re-upload of old material; the playlist's existing era bounds and negative
// keywords are what actually keep those out.
//
// REQUEST BUDGET — the shelf costs 1 upstream call and returns ~24 containers, each
// of which needs its own album lookup to yield a playable videoId. Resolving them all
// would cost ~25 round trips per refresh, so resolution is capped hard.
const ALBUM_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const ALBUM_MAX_ENTRIES = 200;
const SHELF_MAX_CONTAINERS = 10; // hard cap on album lookups per feed refresh
const ALBUM_CONCURRENCY = 4;     // hard cap on simultaneous album lookups

// Full-length 'Album' entries are where reissues, deluxe editions and anniversary
// pressings live, so only upstream's own Single/EP containers are resolved.
const FRESH_CONTAINER_TYPES = new Set(['single', 'ep']);

const _albumCache = new BoundedCache({ maxSize: ALBUM_MAX_ENTRIES, ttlMs: ALBUM_TTL_MS });
const _inFlightAlbums = new Map();

function sourceFailureResult() {
  const result = [];
  Object.defineProperty(result, 'sourceFailed', { value: true, enumerable: false });
  return result;
}

/**
 * Generates a deterministic cache key distinguishing feed type, country, language, and chart ID.
 *
 * @param {string} feedType - 'chart', 'explore', etc.
 * @param {object} [options={}]
 * @returns {string} Cache key
 */
function getFeedCacheKey(feedType, options = {}) {
  const type = String(feedType || 'feed').trim().toLowerCase();
  const country = String(options.country || 'IN').trim().toUpperCase();
  const language = String(options.language || 'Hindi').trim().toLowerCase();
  const chartId = String(options.chartId || options.playlistId || 'dynamic').trim();
  return `feed:${type}:${country}:${language}:${chartId}`;
}

/**
 * Validates a YouTube playlist ID format.
 *
 * @param {string} id
 * @returns {boolean}
 */
function isValidPlaylistId(id) {
  if (!id || typeof id !== 'string') return false;
  return /^PL[a-zA-Z0-9_-]{10,}$/.test(id.trim());
}

/**
 * Validates an album browseId. Upstream's documented form starts with "MPRE".
 *
 * @param {string} id
 * @returns {boolean}
 */
function isValidAlbumBrowseId(id) {
  if (!id || typeof id !== 'string') return false;
  return /^MPRE[a-zA-Z0-9_-]{6,}$/.test(id.trim());
}

/**
 * Picks which shelf containers are worth spending an album lookup on.
 *
 * Everything here runs BEFORE any album request, so each rejection saves a round
 * trip: non-Single/EP types, malformed browseIds, repeats, and second albums by an
 * artist already represented (fresh_releases caps each artist at one track anyway,
 * so a second album by the same artist cannot survive selection).
 *
 * @param {object[]} containers - Raw shelf containers
 * @returns {object[]} At most SHELF_MAX_CONTAINERS containers
 */
function selectFreshContainers(containers) {
  const picked = [];
  const seenBrowseIds = new Set();
  const seenArtists = new Set();

  for (const container of Array.isArray(containers) ? containers : []) {
    if (picked.length >= SHELF_MAX_CONTAINERS) break;
    if (!container || typeof container !== 'object') continue;

    const browseId = typeof container.browseId === 'string' ? container.browseId.trim() : '';
    if (!isValidAlbumBrowseId(browseId)) continue;
    if (seenBrowseIds.has(browseId)) continue;

    const type = String(container.type || '').trim().toLowerCase();
    if (!FRESH_CONTAINER_TYPES.has(type)) continue;

    const primaryArtist = String(
      (Array.isArray(container.artists) && container.artists[0] && container.artists[0].name)
      || container.artist
      || ''
    ).trim().toLowerCase();
    if (primaryArtist && seenArtists.has(primaryArtist)) continue;
    if (primaryArtist) seenArtists.add(primaryArtist);

    seenBrowseIds.add(browseId);
    picked.push({ ...container, browseId });
  }

  return picked;
}

/**
 * Runs `worker` over `items` with at most `limit` promises in flight at once.
 *
 * @param {any[]} items
 * @param {number} limit
 * @param {(item: any, index: number) => Promise<any>} worker
 * @returns {Promise<any[]>} Results in input order
 */
async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;

  const runner = async () => {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await worker(items[index], index);
    }
  };

  const runners = [];
  for (let i = 0; i < Math.min(limit, items.length); i++) runners.push(runner());
  await Promise.all(runners);

  return results;
}

/**
 * Fetches one album's detail, cached for 24h and coalesced while in flight, so the
 * same album is never requested twice even by concurrent callers.
 *
 * @param {string} browseId
 * @returns {Promise<object|null>}
 */
async function getAlbumDetailCached(browseId) {
  const cached = _albumCache.get(browseId);
  if (cached) return cached;

  if (_inFlightAlbums.has(browseId)) return _inFlightAlbums.get(browseId);

  const fetchPromise = (async () => {
    try {
      const detail = await ytmusic.getAlbumDetail(browseId);
      if (!detail || !Array.isArray(detail.tracks)) {
        logger.warn('[DiscoveryFeedService] Album detail unusable; not caching', { browseId });
        return null;
      }
      // Guarantee 4 (see the header): an album that resolved to zero tracks is an
      // empty result. Caching it would pin one partial upstream response for the
      // whole 24h TTL and keep the native source dark that long, so it is returned
      // for this refresh but never cached.
      if (detail.tracks.length === 0) {
        logger.warn('[DiscoveryFeedService] Album detail has no tracks; not caching', { browseId });
        return detail;
      }
      _albumCache.set(browseId, detail);
      return detail;
    } catch (err) {
      logger.warn('[DiscoveryFeedService] Album detail fetch failed', {
        browseId,
        error: err.message,
      });
      return null;
    } finally {
      _inFlightAlbums.delete(browseId);
    }
  })();

  _inFlightAlbums.set(browseId, fetchPromise);
  return fetchPromise;
}

/**
 * Converts one resolved album into CanonicalTracks.
 *
 * Album tracks arrive with `thumbnails: null` and no year of their own, so artwork
 * and year are inherited from the album level — and ONLY when the album actually
 * supplies them. Nothing is invented: a track with no videoId or no title is dropped
 * rather than given a placeholder, and no release date is ever attached, because
 * upstream has none.
 *
 * @param {object} container - Shelf container (artwork/artist fallback)
 * @param {object} detail - Album detail from /api/album/<browse_id>
 * @returns {object[]} CanonicalTrack[]
 */
function albumTracksToCanonical(container, detail) {
  const albumTitle = (detail && detail.title) || (container && container.title) || '';
  const albumYear = (detail && detail.year) || '';
  const albumArt = (detail && detail.thumbnailUrl) || (container && container.thumbnailUrl) || '';
  const rawTracks = detail && Array.isArray(detail.tracks) ? detail.tracks : [];

  // Fallback artist for album tracks whose own credits are missing. Upstream's
  // parse_album output carries only the structured `artists` array and no flat
  // `artist`, so both shapes are read — selectFreshContainers already treats them as
  // equivalent, and dropping a usable name here would surface 'Unknown Artist'.
  const containerArtist = String((container && container.artist) || '').trim()
    || String(
      ((container && Array.isArray(container.artists) ? container.artists : [])
        .find((a) => a && typeof a.name === 'string' && a.name.trim()) || {}).name || ''
    ).trim();

  const tracks = [];
  for (const raw of rawTracks) {
    if (!raw || typeof raw !== 'object') continue;

    const videoId = typeof raw.videoId === 'string' ? raw.videoId.trim() : '';
    if (!videoId) continue;
    if (typeof raw.title !== 'string' || !raw.title.trim()) continue;
    if (raw.isAvailable === false) continue;

    const artists = Array.isArray(raw.artists)
      ? raw.artists.filter((a) => a && typeof a.name === 'string' && a.name.trim())
      : [];
    const artistString = artists.map((a) => a.name.trim()).join(', ') || containerArtist;

    const track = createTrack({
      videoId,
      title: raw.title.trim(),
      artist: artistString,
      artists,
      album: albumTitle,
      year: albumYear,
      duration_seconds: raw.duration_seconds,
      duration: raw.duration,
      thumbnails: Array.isArray(raw.thumbnails) && raw.thumbnails.length > 0
        ? raw.thumbnails
        : (albumArt ? [{ url: albumArt }] : []),
      resultType: 'song',
    });

    if (validateTrack(track)) tracks.push(track);
  }

  return tracks;
}

class DiscoveryFeedService {
  /**
   * Fetches native YouTube Music chart tracks with bounded caching and in-flight deduplication.
   *
   * @param {object} [options={}]
   * @param {string} [options.country='IN']
   * @param {string} [options.language='Hindi']
   * @param {string} [options.chartId]
   * @param {number} [options.limit=100]
   * @returns {Promise<object[]>} Array of CanonicalTrack objects
   */
  static async getChartTracks(options = {}) {
    const country = (options.country || 'IN').toUpperCase();
    const language = options.language || 'Hindi';
    const explicitId = options.chartId || options.playlistId || null;

    let chartId = null;
    if (explicitId) {
      if (isValidPlaylistId(explicitId)) {
        chartId = explicitId.trim();
      } else {
        logger.warn(`[DiscoveryFeedService] Invalid chartId format provided: "${explicitId}"; ignoring`);
      }
    }

    const cacheKey = getFeedCacheKey('chart', { country, language, chartId });

    // 1. Check warm cache
    const cached = _feedCache.get(cacheKey);
    if (Array.isArray(cached) && cached.length > 0) {
      return cached;
    }

    // 2. Stampede protection: Join in-flight request if present
    if (_inFlightFeeds.has(cacheKey)) {
      return _inFlightFeeds.get(cacheKey);
    }

    // 3. Initiate fetch
    const fetchPromise = (async () => {
      const t0 = Date.now();
      try {
        const tracks = await ytmusic.getChartTracks({
          country,
          language,
          playlistId: chartId || undefined,
          limit: options.limit || 100,
        });

        if (Array.isArray(tracks) && tracks.length > 0) {
          _feedCache.set(cacheKey, tracks);
          logger.info('[DiscoveryFeedService] Chart feed fetched and cached', {
            cacheKey,
            count: tracks.length,
            latencyMs: Date.now() - t0,
          });
          return tracks;
        }

        logger.warn('[DiscoveryFeedService] Chart feed returned 0 tracks; not caching', { cacheKey });
        return [];
      } catch (err) {
        logger.error('[DiscoveryFeedService] Chart feed fetch failed', {
          cacheKey,
          error: err.message,
        });
        // Some unit/integration consumers intentionally omit the optional chart
        // provider. Treat that as an unavailable source (the existing fallback
        // behavior), not as an upstream failure that makes a smaller result
        // unusable.
        return /is not a function/.test(String(err?.message || err))
          ? []
          : sourceFailureResult();
      } finally {
        _inFlightFeeds.delete(cacheKey);
      }
    })();

    _inFlightFeeds.set(cacheKey, fetchPromise);
    return fetchPromise;
  }

  /**
   * Resolves YouTube Music's native New Releases shelf into CanonicalTracks.
   *
   * Bounded by construction: one shelf call (feed-cached 6h, in-flight coalesced),
   * at most SHELF_MAX_CONTAINERS album lookups per refresh, at most
   * ALBUM_CONCURRENCY of those in flight at once, each album cached 24h and
   * coalesced by browseId.
   *
   * This is an editorial freshness approximation, NOT verified release-date
   * filtering — see the FRESHNESS SEMANTICS note at the top of this file. The
   * returned tracks are CANDIDATES only; they still have to pass the playlist's
   * normal eligibility pipeline.
   *
   * @returns {Promise<object[]>} CanonicalTrack[] ([] on any failure)
   */
  static async getNewReleaseTracks() {
    const cacheKey = getFeedCacheKey('new_releases', {});

    const cached = _feedCache.get(cacheKey);
    if (Array.isArray(cached) && cached.length > 0) {
      return cached;
    }

    if (_inFlightFeeds.has(cacheKey)) {
      return _inFlightFeeds.get(cacheKey);
    }

    const fetchPromise = (async () => {
      const t0 = Date.now();
      try {
        const containers = await ytmusic.getNewReleaseShelf();
        if (containers?.sourceFailed) return sourceFailureResult();
        const selected = selectFreshContainers(containers);

        if (selected.length === 0) {
          logger.warn('[DiscoveryFeedService] New releases shelf yielded 0 usable containers; not caching', { cacheKey });
          return [];
        }

        const perAlbum = await mapWithConcurrency(selected, ALBUM_CONCURRENCY, async (container) => {
          const detail = await getAlbumDetailCached(container.browseId);
          return {
            tracks: detail ? albumTracksToCanonical(container, detail) : [],
            failed: !detail,
          };
        });

        const tracks = perAlbum.flatMap((result) => result.tracks);
        const failedAlbumLookups = perAlbum.filter((result) => result.failed).length;
        if (failedAlbumLookups > 0) {
          Object.defineProperty(tracks, 'sourceFailed', { value: true, enumerable: false });
        }

        if (tracks.length > 0) {
          if (failedAlbumLookups === 0) {
            _feedCache.set(cacheKey, tracks);
          } else {
            logger.warn('[DiscoveryFeedService] New releases feed had failed album lookups; not caching', {
              cacheKey,
              failedAlbumLookups,
            });
          }
          logger.info('[DiscoveryFeedService] New releases feed fetched and cached', {
            cacheKey,
            containersSeen: Array.isArray(containers) ? containers.length : 0,
            containersResolved: selected.length,
            count: tracks.length,
            latencyMs: Date.now() - t0,
          });
          return tracks;
        }

        logger.warn('[DiscoveryFeedService] New releases feed produced 0 tracks; not caching', { cacheKey });
        // Preserve source health when every selected album failed. Returning a
        // fresh plain [] here would erase the marker attached above and make
        // PlaylistService treat an upstream outage as an intentional, cacheable
        // underfilled result. A valid empty album has failedAlbumLookups === 0
        // and intentionally remains an unmarked empty result.
        if (failedAlbumLookups > 0) return sourceFailureResult();
        return [];
      } catch (err) {
        logger.error('[DiscoveryFeedService] New releases feed fetch failed', {
          cacheKey,
          error: err.message,
        });
        return sourceFailureResult();
      } finally {
        _inFlightFeeds.delete(cacheKey);
      }
    })();

    _inFlightFeeds.set(cacheKey, fetchPromise);
    return fetchPromise;
  }
}

module.exports = {
  DiscoveryFeedService,
  getFeedCacheKey,
  isValidPlaylistId,
  isValidAlbumBrowseId,
  selectFreshContainers,
  albumTracksToCanonical,
  mapWithConcurrency,
  FEED_TTL_MS,
  ALBUM_TTL_MS,
  SHELF_MAX_CONTAINERS,
  ALBUM_CONCURRENCY,
  _feedCache,
  _inFlightFeeds,
  _albumCache,
  _inFlightAlbums,
};
