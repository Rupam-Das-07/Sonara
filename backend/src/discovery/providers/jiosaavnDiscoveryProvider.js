'use strict';

/**
 * jiosaavnV2Provider.js — Isolated JioSaavn V2 Provider Layer for sonara-backend
 *
 * Provides secondary search fallback and trending feeds from the self-hosted JioSaavn V2 API.
 * Uses native Node.js fetch() with AbortSignal.timeout(), BoundedCache, and shape guards.
 */

const BoundedCache = require('../../utils/BoundedCache');
const logger = require('../../utils/logger');

const JIOSAAVN_V2_BASE = process.env.JIOSAAVN_V2_BASE || 'https://jiosaavn.rajputhemant.dev/api';
const MODULES_CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes
const REQUEST_TIMEOUT_MS   = 6000;            // 6 seconds

const _modulesCache = new BoundedCache({ maxSize: 50, ttlMs: MODULES_CACHE_TTL_MS });

function guardModulesShape(apiPayload) {
  if (!apiPayload || typeof apiPayload !== 'object') return null;
  const { status, data } = apiPayload;
  if (status !== 'Success' || !data || typeof data !== 'object') return null;
  return data;
}

function guardSearchShape(apiPayload) {
  if (!apiPayload || typeof apiPayload !== 'object') return null;
  const { status, data } = apiPayload;
  if (status !== 'Success' || !data) return null;
  const results = data.results || data.songs || data;
  if (!Array.isArray(results)) return null;
  return results;
}

function guardSongShape(song) {
  if (!song || typeof song !== 'object') return false;
  if (!song.id || typeof song.id !== 'string') return false;
  if (!song.name || typeof song.name !== 'string') return false;
  return true;
}

function extractBestArtwork(imageField) {
  if (!imageField) return '';
  if (Array.isArray(imageField)) {
    for (let i = imageField.length - 1; i >= 0; i--) {
      const entry = imageField[i];
      const url = entry?.url || entry?.link || (typeof entry === 'string' ? entry : '');
      if (url && typeof url === 'string' && url.startsWith('http')) {
        return url;
      }
    }
    return '';
  }
  if (typeof imageField === 'string' && imageField.startsWith('http')) {
    return imageField;
  }
  return '';
}

function extractArtistName(song) {
  if (song.artist_map?.primary_artists?.length > 0) {
    return song.artist_map.primary_artists
      .map((a) => a.name)
      .filter(Boolean)
      .join(', ');
  }
  if (song.subtitle && typeof song.subtitle === 'string') {
    return song.subtitle;
  }
  if (song.more_info?.artistMap?.primary_artists?.length > 0) {
    return song.more_info.artistMap.primary_artists
      .map((a) => a.name)
      .filter(Boolean)
      .join(', ');
  }
  return 'Unknown Artist';
}

function normalizeV2Song(song) {
  if (!guardSongShape(song)) return null;

  const rawArtworkUrl = extractBestArtwork(song.image);
  const artistName = extractArtistName(song);
  const albumName = song.album?.name || song.more_info?.album || '';
  const duration = typeof song.duration === 'number' ? song.duration : parseInt(song.duration, 10) || 0;

  return {
    id: song.id,
    videoId: null, // JioSaavn IDs are not YouTube videoIds
    title: song.name.trim(),
    artist: artistName,
    album: albumName,
    duration: duration,
    artworkUrl: rawArtworkUrl,
    thumbnails: rawArtworkUrl ? [{ url: rawArtworkUrl, width: 500, height: 500 }] : [],
    provider: 'jiosaavn',
    resultType: 'song',
  };
}

async function fetchV2Trending() {
  const cached = _modulesCache.get('trending');
  if (cached) return cached;

  const url = `${JIOSAAVN_V2_BASE}/modules?language=hindi,english`;
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      headers: { 'Accept': 'application/json' },
    });

    if (!res.ok) {
      logger.warn('[JIOSAAVN_V2] Modules returned HTTP ' + res.status);
      return [];
    }

    const payload = await res.json();
    const data = guardModulesShape(payload);
    if (!data) return [];

    const trendingItems = data.trending?.songs || data.trending || [];
    const songs = (Array.isArray(trendingItems) ? trendingItems : [])
      .map(normalizeV2Song)
      .filter(Boolean);

    _modulesCache.set('trending', songs);
    return songs;
  } catch (err) {
    logger.error('[JIOSAAVN_V2] fetchV2Trending error: ' + err.message);
    return [];
  }
}

module.exports = {
  fetchV2Trending,
  normalizeV2Song,
  extractBestArtwork,
  extractArtistName,
  guardModulesShape,
  guardSearchShape,
  guardSongShape,
};
