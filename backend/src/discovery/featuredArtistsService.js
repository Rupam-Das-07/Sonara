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
 *    - Deduplicates across both browseId and normalized slug.
 *
 * 3. High-Resolution Square Artwork:
 *    Extracts the 1:1 square avatar portrait provided by the chart and deterministically
 *    upgrades the resolution parameter to '=w540-h540-p-l90-rj' via Google CDN.
 *    Non-destructive fallback preserves raw URL or yields null (client monogram).
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

const ROSTER_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const UPSTREAM_TIMEOUT_MS = 6000; // 6 seconds
const TARGET_ROSTER_SIZE = 8;

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
 * Builds a stable, URL-safe identifier from an artist display name.
 * Uses Unicode NFKD normalization so accented characters (e.g. Beyoncé) produce clean slugs.
 *
 * @param {string} name
 * @returns {string}
 */
function slugify(name) {
  return String(name || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

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

// In-memory SWR cache state
let _featuredCache = {
  timestamp: 0,
  data: buildDefaultRoster(),
};
let _refreshPromise = null;

/**
 * Resolves artist thumbnail via Python endpoint (kept for individual lookups).
 *
 * @param {string} name
 * @returns {Promise<string|null>}
 */
async function resolveArtistImage(name) {
  try {
    const url = `${config.pythonYtmusicUrl}/api/artist-image?name=${encodeURIComponent(name)}`;
    const res = await fetch(url, {
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) return null;
    const data = await res.json();
    const image = typeof data?.image === 'string' && data.image.length > 0 ? data.image : null;
    return upgradeAvatarResolution(image);
  } catch (err) {
    logger.warn(`[featuredArtists] resolveArtistImage failed for "${name}": ${err.message}`);
    return null;
  }
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
  return upgradeAvatarResolution(rawUrl);
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
    const seenBrowseIds = new Set();
    const seenSlugs = new Set();

    for (const item of rawCandidates) {
      if (!item || typeof item !== 'object') continue;

      const title = (item.title || item.name || '').trim();
      const browseId = (item.browseId || '').trim();

      if (!title || !browseId) continue;
      if (!BROWSE_ID_RE.test(browseId)) continue;

      const slug = slugify(title);
      if (!slug) continue;

      const lowerTitle = title.toLowerCase();
      if (ENTITY_BLACKLIST.has(lowerTitle)) continue;
      if (FAN_PARODY_RE.test(title)) continue;

      if (seenBrowseIds.has(browseId) || seenSlugs.has(slug)) continue;

      seenBrowseIds.add(browseId);
      seenSlugs.add(slug);

      const imageUrl = extractCandidateImage(item);

      validCandidates.push({
        id: slug,
        name: title,
        browseId,
        genre: '', // Dynamic chart candidates have honest empty genre
        imageUrl,
      });
    }

    // Compose final roster targeting N=8:
    // Chart candidates take their organic positions.
    const selected = [];
    const rosterSlugs = new Set();
    const rosterBrowseIds = new Set();

    for (const cand of validCandidates) {
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
};
