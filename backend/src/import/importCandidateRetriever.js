'use strict';

/**
 * importCandidateRetriever.js — Stage A candidate retrieval for import matching.
 *
 * Constructs search queries from imported track metadata and queries the
 * existing ytmusicProvider.searchSongs() engine.
 *
 * Keeps retrieval completely separate from identity scoring.
 */

const { searchSongs } = require('../search/ytmusicProvider');
const logger = require('../utils/logger');

/**
 * Retrieves candidates from YouTube Music for a given title and artist.
 *
 * @param {string} title
 * @param {string} artist
 * @returns {Promise<object[]>} Array of CanonicalTrack objects
 */
async function retrieveCandidates(title, artist) {
  const cleanTitle = (title || '').trim();
  const cleanArtist = (artist || '').trim();

  const query = `${cleanTitle} ${cleanArtist}`.trim();
  if (!query) {
    return [];
  }

  try {
    return await searchSongs(query);
  } catch (err) {
    logger.warn('[IMPORT] Candidate retrieval failed for query', {
      query,
      error: err.message,
    });
    return [];
  }
}

module.exports = { retrieveCandidates };
