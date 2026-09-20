/**
 * RecommendationFilter.js
 * 
 * Business logic layer for filtering recommendations.
 * Ensures autoplay and similar tracks remain premium and music-focused.
 */

/**
 * Filter out junk/live/lyric tracks from canonical recommendations.
 * @param {Array<Object>} canonicalTracks - Array of CanonicalTrack objects
 * @returns {Array<Object>} Filtered array of CanonicalTrack objects
 */
function filterRecommendations(canonicalTracks) {
  if (!Array.isArray(canonicalTracks)) return [];
  
  const seenIds = new Set();
  
  return canonicalTracks.filter(track => {
    // 1. Identity validation
    if (!track || !track.id) return false;
    
    // 2. Deduplication
    if (seenIds.has(track.id)) return false;
    seenIds.add(track.id);
    
    // 3. Junk filtering (by title)
    const title = (track.title || '').toLowerCase();
    if (title.includes('podcast') || title.includes('interview')) return false;
    if (title.includes('reaction') || title.includes('bass boosted')) return false;
    
    // 4. Duration validation
    // CanonicalTrack duration is in seconds (or null if unknown)
    if (track.duration !== null && (track.duration < 30 || track.duration > 900)) return false;

    return true;
  });
}

module.exports = {
  filterRecommendations
};
