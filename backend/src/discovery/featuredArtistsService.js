'use strict';

/**
 * featuredArtistsService.js — Dynamic Featured Artists for Sonara Home Screen.
 *
 * ARCHITECTURE (Stage 5C / Phase 6):
 * ---------------------------------
 * 1. Candidate Source:
 *    Fetches top artist candidates from the YouTube Music Global Chart via Python
 *    service (GET /api/charts/artists). The chart is a dynamic popularity signal,
 *    not an unmoderated source of truth.
 *
 * 2. Quality & Identity Gates:
 *    - Validates YouTube Music channel browseId format (/^UC[A-Za-z0-9_-]{22}$/).
 *    - Defensive entity blacklist rejects non-performer lyricists (Sameer, Irshad Kamil)
 *      and record label / aggregator channels (T-Series, Zee Music, Saregama, etc.).
 *    - Rejects fan club, cover, and tribute channel patterns.
 *    - Deduplicates authoritative browseIds; disambiguates public slug collisions.
 *
 * 3. High-Resolution Square Artwork:
 *    Extracts the 1:1 square avatar portrait provided by the chart and deterministically
 *    upgrades the resolution parameter to '=w540-h540-p-l90-rj' via Google CDN.
 *    Non-destructive fallback preserves a trusted raw URL; invalid or untrusted
 *    artwork yields null (client monogram).
 *
 * 4. Resilient Stale-While-Revalidate (SWR) & Core Anchors:
 *    - 6 Core Editorial Anchors (Arijit Singh, Shreya Ghoshal, A.R. Rahman, Atif Aslam,
 *      Coldplay, The Weeknd) with verified browseIds and high-res portraits provide:
 *        a) Cold-start zero-latency initialization without upstream dependencies.
 *        b) Fallback padding if chart filtering yields fewer than target N=8 artists.
 *        c) 100% outage resilience if YouTube Music is down or rate-limited.
 *    - In-memory 24-hour TTL with single-flight mutex (_refreshPromise) prevents request
 *      stampedes and guarantees instant responses to callers.
 */

const config = require('../config/env');
const logger = require('../utils/logger');
const { nameKey, slugify } = require('./artistIdentity');

const ROSTER_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const UPSTREAM_TIMEOUT_MS = 6000; // 6 seconds
const TARGET_ROSTER_SIZE = 8;
const ARTIST_IMAGE_CACHE_MAX = 256;
const ARTIST_IMAGE_SUCCESS_TTL_MS = 5 * 60 * 1000;
const ARTIST_IMAGE_NEGATIVE_TTL_MS = 15 * 1000;
const ARTIST_IMAGE_NAME_MAX_LENGTH = 200;

/**
 * 6 Core Editorial Anchors.
 * Verified browseIds and high-resolution avatars permanently guaranteed.
 */
const CORE_ANCHORS = [
  {
    name: 'Arijit Singh',
    browseId: 'UCDxKh1gFWeYsqePvgVzmPoQ',
    genre: 'Bollywood Romantic',
    imageUrl: 'https://lh3.googleusercontent.com/W_yOqnKSDYyeVOY_AsXhuAtb6rW3vCL3GtJ9DA1GxWOrJfyeSOqzvTv_TkFHijdkVPXWutASBlRFPg=w540-h540-p-l90-rj',
  },
  {
    name: 'Shreya Ghoshal',
    browseId: 'UCrC-7fsdTCYeaRBpwA6j-Eg',
    genre: 'Bollywood Melody',
    imageUrl: 'https://yt3.ggpht.com/PgINZNe0qVxgMSXKG5vF82bNN4WCC12zgWsz9I7OLs4CLF9Cn0Vxq7Xc1ToupnzXrCv0nKfe3VM=w540-h540-p-l90-rj',
  },
  {
    name: 'A.R. Rahman',
    browseId: 'UCtJe0RYzgPddQXKtWduxz_w',
    genre: 'Soundtrack / Fusion',
    imageUrl: 'https://yt3.googleusercontent.com/vHMOuDn8gr3SW9Pm8yFgmtYzM5kj4ayng5HKRjW0OyjG9mPK923XMVtTZTt4NUG_1aemWNLSQ27zjtA=w540-h540-p-l90-rj',
  },
  {
    name: 'Atif Aslam',
    browseId: 'UCVGomUS__PL0c4jDXa0QwXA',
    genre: 'Pop / Sufi',
    imageUrl: 'https://yt3.googleusercontent.com/ykJkyILKum4B2oudDxjnf5WNenWWZAp-WEz0_CHp4cu0VnqB2-uaNDylItqC68WLXV62rdHDun-ahbg=w540-h540-p-l90-rj',
  },
  {
    name: 'Coldplay',
    browseId: 'UCIaFw5VBEK8qaW6nRpx_qnw',
    genre: 'Alternative Rock',
    imageUrl: 'https://lh3.googleusercontent.com/IOKuXtp8PCQ_Fc-vaRKm3sKIXBxFV51gZheLTH5br-YGnWHFQf_Jywcuk7wbprYRoEbQyS_XZY6-nMJX=w540-h540-p-l90-rj',
  },
  {
    name: 'The Weeknd',
    browseId: 'UClYV6hHlupm_S_ObS1W-DYw',
    genre: 'R&B / Pop',
    imageUrl: 'https://lh3.googleusercontent.com/U-SAmNOu4TynE818gLCfKsuHZ0U5YNEtO9mrjSI9WCCKERs98LzrCal5kajBBTQNwdcisoB2Bn-pHp4=w540-h540-p-l90-rj',
  },
];

// Preserved for backward test compatibility
const FEATURED_ARTISTS = CORE_ANCHORS.map((a) => ({ name: a.name, genre: a.genre }));

/**
 * Defensive blacklist: Known non-performer lyricists and corporate record label channels.
 */
const ENTITY_BLACKLIST = new Set([
  'sameer',
  'sameer anjaan',
  'irshad kamil',
  't-series',
  't series',
  'zee music company',
  'tips official',
  'tips industries',
  'saregama',
  'saregama music',
  'sony music india',
  'speed records',
  'yrf',
  'yash raj films',
  'white hill music',
  'venus',
]);

const FAN_PARODY_RE = /(?:fan\s*club|ka\s*fan|official\s*channel|tribute|covers?)$/i;
const BROWSE_ID_RE = /^UC[A-Za-z0-9_-]{22}$/;

/**
 * Non-destructively upgrades Google-hosted thumbnail URLs to high-resolution square avatars.
 *
 * @param {string} url
 * @returns {string|null}
 */
function upgradeAvatarResolution(url) {
  if (!url || typeof url !== 'string') return null;
  const isGoogle = url.includes('googleusercontent.com') || url.includes('ggpht.com');
  if (!isGoogle) return url;
  if (url.includes('=w540-h540') || url.includes('=s540')) return url;

  // Pattern A: standard dimension parameters e.g. =w120-h120... or =w540-h225...
  if (/=w\d+(?:-[a-z])?-h\d+/.test(url)) {
    return url.replace(/=w\d+(?:-[a-z])?-h\d+.*$/, '=w540-h540-p-l90-rj');
  }

  // Pattern B: single dimension parameter e.g. =s120-c
  if (/=s\d+/.test(url)) {
    return url.replace(/=s\d+.*$/, '=s540-p-l90-rj');
  }

  return url;
}

/**
 * Builds the initial fallback roster from Core Anchors.
 *
 * @returns {object[]}
 */
function buildDefaultRoster() {
  return CORE_ANCHORS.map((a) => ({
    id: slugify(a.name),
    name: a.name,
    browseId: a.browseId,
    genre: a.genre,
    imageUrl: a.imageUrl,
  }));
}

/** Opaque upstream IDs are never normalized; malformed supplied IDs reject the row. */
function isValidPublicId(id) {
  return typeof id === 'string' && id.trim().length > 0
    && !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(id.replace(/[\u200c\u200d]/g, ''));
}

/**
 * Reserve immutable IDs (including anchor IDs) before allocating generated ones.
 * BrowseId is authoritative even when names or lossy public slugs are identical.
 * Conflicting immutable IDs cannot safely represent two artists: reject those rows.
 */
function assignCandidateIds(candidates) {
  const anchors = buildDefaultRoster();
  const anchorOwners = new Map(anchors.map((anchor) => [anchor.id, anchor.browseId]));
  const owners = new Map(anchors.map((anchor) => [anchor.id, new Set([anchor.browseId])]));
  for (const candidate of candidates) {
    if (!candidate.authoritativeId) continue;
    const { id, browseId } = candidate.artist;
    if (!owners.has(id)) owners.set(id, new Set());
    owners.get(id).add(browseId);
  }

  const seenBrowseIds = new Set();
  const accepted = candidates.filter((candidate) => {
    const { id, browseId } = candidate.artist;
    if (candidate.authoritativeId && owners.get(id).size > 1) {
      logger.warn('[featuredArtists] Rejecting conflicting authoritative artist id');
      return false;
    }
    if (seenBrowseIds.has(browseId)) return false;
    seenBrowseIds.add(browseId);
    return true;
  });
  const generated = accepted.filter((candidate) => !candidate.authoritativeId);
  const slugCounts = new Map();
  for (const { artist } of generated) {
    slugCounts.set(artist.id, (slugCounts.get(artist.id) || 0) + 1);
  }
  const usedIds = new Set(owners.keys());
  // Allocation is independent of chart order, while the response keeps chart order.
  for (const { artist } of [...generated].sort((a, b) => (
    a.artist.browseId < b.artist.browseId ? -1 : 1
  ))) {
    const slug = artist.id;
    const ownAnchor = anchorOwners.get(slug) === artist.browseId;
    if (!ownAnchor && (slugCounts.get(slug) > 1 || usedIds.has(slug))) {
      artist.id = `${slug}--${artist.browseId}`;
      // An arbitrary authoritative ID may itself equal a disambiguated slug.
      while (usedIds.has(artist.id)) artist.id += `--${artist.browseId}`;
    }
    usedIds.add(artist.id);
  }
  return accepted.map((candidate) => candidate.artist);
}

// In-memory SWR cache state
let _featuredCache = {
  timestamp: 0,
  data: buildDefaultRoster(),
};
let _refreshPromise = null;

// Local-candidate image lookups are bounded and short-lived. A transient
// upstream miss must not become a permanent process-lifetime miss.
const _artistImageCache = new Map();
const _artistImageFlights = new Map();

function hasExplicitPort(value) {
  const authority = value.trim().match(/^[a-z][a-z\d+.-]*:\/\/([^/?#]*)/i)?.[1] || '';
  const hostPort = authority.slice(authority.lastIndexOf('@') + 1);
  return hostPort.includes(':');
}

function isTrustedArtistImageUrl(value) {
  if (typeof value !== 'string' || !value) return false;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password
      || parsed.port || hasExplicitPort(value)) return false;
    const host = parsed.hostname.toLowerCase();
    return ['googleusercontent.com', 'ggpht.com'].some((suffix) => (
      host === suffix || host.endsWith(`.${suffix}`)
    ));
  } catch (_) {
    return false;
  }
}

function readArtistImageCache(key) {
  const entry = _artistImageCache.get(key);
  if (!entry) return undefined;
  if (entry.expiresAt <= Date.now()) {
    _artistImageCache.delete(key);
    return undefined;
  }
  return entry.value;
}

function writeArtistImageCache(key, value) {
  const ttl = value ? ARTIST_IMAGE_SUCCESS_TTL_MS : ARTIST_IMAGE_NEGATIVE_TTL_MS;
  _artistImageCache.set(key, { value, expiresAt: Date.now() + ttl });
  while (_artistImageCache.size > ARTIST_IMAGE_CACHE_MAX) {
    _artistImageCache.delete(_artistImageCache.keys().next().value);
  }
}

/**
 * Resolves artist thumbnail via Python endpoint (kept for individual lookups).
 *
 * @param {string} name
 * @returns {Promise<string|null>}
 */
async function resolveArtistImage(name) {
  if (typeof name !== 'string' || name.length > ARTIST_IMAGE_NAME_MAX_LENGTH) return null;
  const trimmedName = name.trim();
  const key = nameKey(trimmedName);
  if (key === null) return null;

  const cached = readArtistImageCache(key);
  if (cached !== undefined) return cached;
  if (_artistImageFlights.has(key)) return _artistImageFlights.get(key);

  const flight = (async () => {
    let imageUrl = null;
    try {
      const url = `${config.pythonYtmusicUrl}/api/artist-image?name=${encodeURIComponent(trimmedName)}`;
      const res = await fetch(url, {
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
        headers: { Accept: 'application/json' },
      });
      if (!res.ok) return null;
      const data = await res.json();
      const image = typeof data?.image === 'string' && data.image.length > 0 ? data.image : null;
      if (!isTrustedArtistImageUrl(image)) return null;
      imageUrl = upgradeAvatarResolution(image);
      return isTrustedArtistImageUrl(imageUrl) ? imageUrl : null;
    } catch (err) {
      logger.warn(`[featuredArtists] resolveArtistImage failed for "${trimmedName}": ${err.message}`);
      return null;
    } finally {
      writeArtistImageCache(key, imageUrl);
      _artistImageFlights.delete(key);
    }
  })();
  _artistImageFlights.set(key, flight);
  return flight;
}

/**
 * Extracts and scales best thumbnail from a raw chart candidate item.
 *
 * @param {object} candidate
 * @returns {string|null}
 */
function extractCandidateImage(candidate) {
  const thumbs = Array.isArray(candidate.thumbnails) ? candidate.thumbnails : [];
  if (thumbs.length === 0) return null;
  const lastThumb = thumbs[thumbs.length - 1];
  const rawUrl = lastThumb?.url || (typeof lastThumb === 'string' ? lastThumb : null);
  const upgradedUrl = upgradeAvatarResolution(rawUrl);
  // Roster artwork follows the same narrow policy as local artist-image lookups:
  // HTTPS Google image CDNs only, with no credentials or explicit port. A bad
  // thumbnail degrades this artist to the Android monogram instead of dropping
  // the artist or publishing an untrusted image URL.
  return isTrustedArtistImageUrl(upgradedUrl) ? upgradedUrl : null;
}

/**
 * Fetches and builds the fresh Featured Artists roster from YouTube Music Charts.
 *
 * @returns {Promise<object[]>}
 */
async function _doRefresh() {
  const t0 = Date.now();
  try {
    const url = `${config.pythonYtmusicUrl}/api/charts/artists`;
    const res = await fetch(url, {
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      headers: { Accept: 'application/json' },
    });

    if (!res.ok) {
      throw new Error(`Upstream returned HTTP ${res.status}`);
    }

    const json = await res.json();
    const rawCandidates = Array.isArray(json?.artists) ? json.artists : [];

    const validCandidates = [];
    for (const item of rawCandidates) {
      if (!item || typeof item !== 'object') continue;

      // Only an absent title falls back to name; invalid supplied values reject the row.
      const rawTitle = item.title ?? item.name;
      if (nameKey(rawTitle) === null) continue;
      const title = rawTitle.trim();
      const browseId = item.browseId;
      if (typeof browseId !== 'string' || !BROWSE_ID_RE.test(browseId)) continue;
      const authoritativeId = Object.prototype.hasOwnProperty.call(item, 'id');
      if (authoritativeId && !isValidPublicId(item.id)) continue;

      const lowerTitle = title.toLowerCase();
      if (ENTITY_BLACKLIST.has(lowerTitle)) continue;
      if (FAN_PARODY_RE.test(title)) continue;

       const imageUrl = extractCandidateImage(item);

      validCandidates.push({
        authoritativeId,
        artist: {
          id: authoritativeId ? item.id : slugify(title),
          name: title,
          browseId,
          genre: '', // Dynamic chart candidates have honest empty genre
          imageUrl,
        },
      });
    }

    // Compose final roster targeting N=8:
    // Chart candidates take their organic positions.
    const selected = [];
    const rosterSlugs = new Set();
    const rosterBrowseIds = new Set();

    for (const cand of assignCandidateIds(validCandidates)) {
      if (selected.length >= TARGET_ROSTER_SIZE) break;
      selected.push(cand);
      rosterSlugs.add(cand.id);
      rosterBrowseIds.add(cand.browseId);
    }

    // Pad with Core Anchors if chart candidates were fewer than TARGET_ROSTER_SIZE
    if (selected.length < TARGET_ROSTER_SIZE) {
      for (const anchor of CORE_ANCHORS) {
        if (selected.length >= TARGET_ROSTER_SIZE) break;
        const anchorSlug = slugify(anchor.name);
        if (!rosterSlugs.has(anchorSlug) && !rosterBrowseIds.has(anchor.browseId)) {
          selected.push({
            id: anchorSlug,
            name: anchor.name,
            browseId: anchor.browseId,
            genre: anchor.genre,
            imageUrl: anchor.imageUrl,
          });
          rosterSlugs.add(anchorSlug);
          rosterBrowseIds.add(anchor.browseId);
        }
      }
    }

    if (selected.length > 0) {
      _featuredCache = {
        timestamp: Date.now(),
        data: selected,
      };
      logger.info(`[featuredArtists] SWR refresh completed in ${Date.now() - t0}ms (${selected.length} artists)`);
    }

    return _featuredCache.data;
  } catch (err) {
    logger.warn(`[featuredArtists] SWR refresh failed (${err.message}). Retaining existing snapshot.`);
    _featuredCache.timestamp = Date.now() - (ROSTER_TTL_MS - 5 * 60 * 1000); // retry in 5m
    return _featuredCache.data;
  }
}

/**
 * Triggers background refresh with single-flight mutex lock.
 *
 * @returns {Promise<object[]>}
 */
function refreshWithLock() {
  if (_refreshPromise) return _refreshPromise;
  _refreshPromise = _doRefresh().finally(() => {
    _refreshPromise = null;
  });
  return _refreshPromise;
}

/**
 * Returns the featured artist roster using Stale-While-Revalidate caching.
 *
 * @returns {Promise<{ id: string, name: string, browseId: string, genre: string, imageUrl: string|null }[]>}
 */
async function getFeaturedArtists() {
  const now = Date.now();
  const isStale = now - _featuredCache.timestamp > ROSTER_TTL_MS;

  if (isStale) {
    refreshWithLock().catch(() => {});
  }

  return _featuredCache.data;
}

module.exports = {
  getFeaturedArtists,
  resolveArtistImage,
  slugify,
  upgradeAvatarResolution,
  refreshWithLock,
  CORE_ANCHORS,
  FEATURED_ARTISTS,
  isTrustedArtistImageUrl,
  _clearArtistImageCache: () => {
    _artistImageCache.clear();
    _artistImageFlights.clear();
  },
};
