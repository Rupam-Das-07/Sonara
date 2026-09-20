'use strict';

/**
 * trackModel.js — CanonicalTrack factory and validator for sonara-backend.
 *
 * Independently owned copy. No runtime dependency on the Web project.
 */

function parseDuration(durationRaw) {
  if (typeof durationRaw === 'number') return durationRaw;
  if (typeof durationRaw !== 'string') return null;
  const parts = durationRaw.split(':').map(Number);
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  return null;
}

function parseThumbnails(track) {
  if (Array.isArray(track.thumbnails) && track.thumbnails.length > 0) return track.thumbnails;
  if (Array.isArray(track.thumbnail) && track.thumbnail.length > 0) return track.thumbnail;
  if (track.thumbnailUrl) return [{ url: track.thumbnailUrl }];
  if (track.albumArt) return [{ url: track.albumArt }];
  return [];
}

/**
 * Create a frozen CanonicalTrack from a raw provider object.
 * @param {object} rawTrack
 * @returns {object} CanonicalTrack (frozen)
 */
function createTrack(rawTrack) {
  if (!rawTrack) return null;
  const videoId = rawTrack.videoId || rawTrack.id || '';
  return Object.freeze({
    id:          videoId,
    videoId:     videoId,
    title:       rawTrack.title || 'Unknown Title',
    artist:      rawTrack.artist || rawTrack.channelTitle || 'Unknown Artist',
    artists:     Array.isArray(rawTrack.artists) ? Object.freeze([...rawTrack.artists]) : Object.freeze([]),
    thumbnails:  Object.freeze(parseThumbnails(rawTrack)),
    url:         rawTrack.url || `https://www.youtube.com/watch?v=${videoId}`,
    duration:    parseDuration(rawTrack.duration_seconds || rawTrack.duration),
    source:      'youtube_music',
    album:       rawTrack.album || '',
    year:        rawTrack.year || '',
    resultType:  rawTrack.resultType || 'video',
    albumArt:    '',
    thumbnailUrl:'',
  });
}

function validateTrack(track) {
  if (!track || typeof track !== 'object') return false;
  if (!track.id || typeof track.id !== 'string') return false;
  if (!track.title || typeof track.title !== 'string') return false;
  return true;
}

module.exports = { createTrack, validateTrack, parseDuration, parseThumbnails };
