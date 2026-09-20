'use strict';

/**
 * PlaylistService.js — Curated Playlist Engine in sonara-backend
 * Curated Playlist Data Quality Engine V2.
 *
 * Resolves candidate tracks for the 22 curated playlist definitions using:
 *  1. Round-robin multi-query candidate harvesting (prevents Query 1 monopolization)
 *  2. Packaging-stripped canonical deduplication (merges official/lyric/audio variants)
 *  3. Stateless hard eligibility gates (EligibilityEngine.js)
 *  4. Robust primary artist extraction with creative duo preservation
 *  5. Hard primary artist diversity cap (closes collaborator bypass)
 *  6. Soft secondary contributor de-prioritization with controlled fallback
 *  7. Minimal deterministic artist de-clustering
 *  8. Structured post-generation QA health metrics (diagnostics only, never runtime gates)
 */

const ytmusic = require('../search/ytmusicProvider');
const { getDefinitionById } = require('./PlaylistDefinitions');
const { isEligible } = require('./EligibilityEngine');
const BoundedCache = require('../utils/BoundedCache');
const logger = require('../utils/logger');

const _playlistCache = new BoundedCache({ maxSize: 50, ttlMs: 60 * 60 * 1000 }); // 1 hour
const _coverCache = new BoundedCache({ maxSize: 100, ttlMs: 24 * 60 * 60 * 1000 }); // 24 hours

// Known creative duos and composer partnerships that must not be split
const CREATIVE_DUOS = [
  { pattern: /\b(vishal\s*[-&]\s*shekhar|vishal\s+and\s+shey?khar)\b/i, normalized: 'vishal-shekhar' },
  { pattern: /\b(sachin\s*[-&]\s*jigar|sachin\s+and\s+jigar)\b/i, normalized: 'sachin-jigar' },
  { pattern: /\b(jatin\s*[-&]\s*lalit|jatin\s+and\s+lalit)\b/i, normalized: 'jatin-lalit' },
  { pattern: /\b(sajid\s*[-&]\s*wajid|sajid\s+and\s+wajid)\b/i, normalized: 'sajid-wajid' },
  { pattern: /\b(salim\s*[-&]\s*sulaiman|salim\s+and\s+sulaiman)\b/i, normalized: 'salim-sulaiman' },
  { pattern: /\b(anand\s*[-&]\s*milind|anand\s+and\s+milind)\b/i, normalized: 'anand-milind' },
  { pattern: /\b(laxmikant\s*[-&]\s*pyarelal|laxmikant\s+and\s+pyarelal)\b/i, normalized: 'laxmikant-pyarelal' },
  { pattern: /\b(shankar\s*[-&]\s*ehsaan\s*[-&]\s*loy|shankar\s+ehsaan\s+loy)\b/i, normalized: 'shankar-ehsaan-loy' },
  { pattern: /\b(nadeem\s*[-&]\s*shravan|nadeem\s+and\s+shravan)\b/i, normalized: 'nadeem-shravan' },
  // F-04 additions: missing classical and modern Indian composer duos
  { pattern: /\b(kalyanji\s*[-&]?\s*anandji|kalyanji\s+and\s+anandji)\b/i, normalized: 'kalyanji-anandji' },
  { pattern: /\b(ajay\s*[-&]?\s*atul|ajay\s+and\s+atul)\b/i, normalized: 'ajay-atul' },
  // International established partnerships / iconic bands containing & or ,
  { pattern: /\b(simon\s*[-&]\s*garfunkel|simon\s+and\s+garfunkel)\b/i, normalized: 'simon-and-garfunkel' },
  { pattern: /\bearth\s*,\s*wind\s*[-&]\s*fire\b/i, normalized: 'earth-wind-and-fire' },
];

const GENERIC_CHANNELS = new Set([
  't-series',
  'tseries',
  'tips official',
  'tips',
  'zee music company',
  'zee music',
  'sonymusicindiavevo',
  'sony music india',
  'sony music',
  'yrf',
  'yash raj films',
  'speed records',
  'saregama',
  'saregama music',
  'geet mp3',
  'white hill music',
  'unknown artist',
  'various artists',
]);

/**
 * Extracts the highest-resolution artwork URL from a track.
 *
 * @param {object} track
 * @returns {string} URL, or '' when the track has no artwork.
 */
function bestArtwork(track) {
  const thumbnails = Array.isArray(track?.thumbnails) ? track.thumbnails : [];
  if (thumbnails.length > 0 && thumbnails[thumbnails.length - 1]?.url) {
    return thumbnails[thumbnails.length - 1].url;
  }
  return track?.artworkUrl || track?.albumArt || track?.thumbnailUrl || '';
}

/**
 * Normalizes an artist name to a clean, lowercase key.
 *
 * @param {string} raw
 * @returns {string}
 */
function cleanArtistString(raw) {
  return (raw || '')
    .toLowerCase()
    .replace(/[^\w\s-]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Checks if target performing artist is credited in contributors.
 * Supports solos, duets, featured guests/primaries, and ensemble collaborations.
 *
 * @param {string[]} contributors
 * @param {string} targetArtistName
 * @returns {boolean}
 */
function hasTargetPerformer(contributors, targetArtistName) {
  if (!targetArtistName) return true;
  const targetClean = cleanArtistString(targetArtistName);
  if (!targetClean) return true;
  return contributors.some((c) => {
    if (c === targetClean || c.includes(targetClean) || targetClean.includes(c)) return true;
    const noSpaceC = c.replace(/\s+/g, '');
    const noSpaceT = targetClean.replace(/\s+/g, '');
    return noSpaceC.length >= 3 && (noSpaceC === noSpaceT || noSpaceC.includes(noSpaceT) || noSpaceT.includes(noSpaceC));
  });
}

/**
 * Robust primary artist extraction.
 * Prefers structured CanonicalTrack.artists array, recognizes creative duos,
 * and handles delimiter fallbacks.
 *
 * @param {object} track
 * @returns {string} Normalized primary artist key
 */
function normalizePrimaryArtist(track) {
  if (!track) return 'unknown artist';

  // 1. Check structured artists array if present and valid
  if (Array.isArray(track.artists) && track.artists.length > 0) {
    const firstObj = track.artists[0];
    if (firstObj && typeof firstObj.name === 'string' && firstObj.name.trim()) {
      const candidateName = firstObj.name.trim();
      const lowerCandidate = candidateName.toLowerCase();
      if (!GENERIC_CHANNELS.has(lowerCandidate)) {
        // Check creative duos FIRST — must not split established partnerships
        for (const duo of CREATIVE_DUOS) {
          if (duo.pattern.test(candidateName)) return duo.normalized;
        }
        // F-04: Split collaboration delimiters in structured strings
        // e.g. "Arijit Singh & Shreya Ghoshal" → "arijit singh"
        // NOTE: "&" alone is only split here because CREATIVE_DUOS was already checked above
        const tokens = candidateName.split(/\s*(?:,|;|\bfeat\.?\b|\bfeaturing\b|\bft\.?\b|\bx\b|\bwith\b|&)\s*/i);
        const primaryToken = tokens[0] ? tokens[0].trim() : candidateName;
        const cleaned = cleanArtistString(primaryToken);
        if (cleaned) return cleaned;
      }
    }
  }

  // 2. Fallback to raw track.artist string
  const rawArtist = track.artist || '';
  if (!rawArtist || GENERIC_CHANNELS.has(rawArtist.toLowerCase().trim())) {
    return 'unknown artist';
  }

  // Check creative duos first
  for (const duo of CREATIVE_DUOS) {
    if (duo.pattern.test(rawArtist)) return duo.normalized;
  }

  // Split on delimiters (commas, semicolons, feat, ft, x, with)
  const tokens = rawArtist.split(/\s*(?:,|;|\bfeat\.?\b|\bft\.?\b|\bx\b|\bwith\b)\s*/i);
  const primaryToken = tokens[0] ? tokens[0].trim() : rawArtist;

  return cleanArtistString(primaryToken) || 'unknown artist';
}

/**
 * Extracts all distinct artist contributor tokens from a track.
 *
 * @param {object} track
 * @returns {string[]}
 */
function extractAllContributors(track) {
  const contributors = new Set();

  if (Array.isArray(track?.artists) && track.artists.length > 0) {
    for (const a of track.artists) {
      if (a && a.name) {
        const cleaned = cleanArtistString(a.name);
        if (cleaned && !GENERIC_CHANNELS.has(cleaned)) contributors.add(cleaned);
      }
    }
  }

  const raw = track?.artist || '';
  if (raw) {
    const tokens = raw.split(/\s*(?:,|;|\bfeat\.?\b|\bft\.?\b|\bx\b|\bwith\b|\band\b|&)\s*/i);
    for (const tok of tokens) {
      const cleaned = cleanArtistString(tok);
      if (cleaned && !GENERIC_CHANNELS.has(cleaned)) contributors.add(cleaned);
    }
  }

  if (contributors.size === 0) contributors.add('unknown artist');
  return Array.from(contributors);
}

/**
 * Canonical title normalization for deduplication.
 * Strips packaging/video noise while preserving intentional musical versions.
 *
 * @param {object} track
 * @param {boolean} [allowRemix=false]
 * @param {boolean} [allowAcoustic=false]
 * @returns {string} Canonical deduplication key
 */
function getCanonicalTrackKey(track, allowRemix = false, allowAcoustic = false) {
  let title = (track.title || '').toLowerCase();

  // Extract intentional version marker before stripping noise
  let versionTag = '';
  if (/\b(acoustic|unplugged)\b/i.test(title)) {
    versionTag = allowAcoustic ? '::acoustic' : '';
  } else if (/\b(remix|re-mix|mashup)\b/i.test(title)) {
    versionTag = allowRemix ? '::remix' : '';
  } else if (/\b(live(\s+session)?)\b/i.test(title)) {
    versionTag = '::live';
  }

  // F-08: Extract language version tag ONLY when enclosed as explicit standalone specifier in parens/brackets.
  // Matches: (Tamil), [Telugu], (Hindi Version), (Telugu Audio) — NOT "(From \"Hindi Medium\")".
  // Language words inside movie titles are not matched because they are not isolated in a standalone clause.
  const standaloneLangMatch = title.match(/[\(\[]\s*(hindi|tamil|telugu|kannada|malayalam|punjabi|bengali)\s*(?:version|audio|track)?\s*[\)\]]/i);
  const langTag = standaloneLangMatch ? `::${standaloneLangMatch[1].toLowerCase()}` : '';

  // Strip YouTube title pipe suffixes (e.g. "| T-Series")
  title = title.replace(/\s*\|.*$/g, '');
  // Strip all parentheticals and brackets (where packaging noise resides)
  title = title.replace(/\([^)]*\)/g, '');
  title = title.replace(/\[[^\]]*\]/g, '');
  // Strip packaging noise words outside parens if any
  title = title.replace(/\b(official\s+(audio|video|lyric|lyrics|video\s*song)|full\s*(song|video|audio)|video\s*song|lyric\s*video)\b/gi, '');
  title = title.replace(/[^\w\s]/g, '');
  title = title.replace(/\s+/g, ' ').trim();

  const primary = normalizePrimaryArtist(track);
  return `${title}::${primary}${versionTag}${langTag}`;
}


/**
 * Interleaves candidate tracks across multiple search query results in round-robin order.
 * Ensures fair representation from all queries without enforcing rigid final quotas.
 *
 * @param {Array<object[]>} queryResults - Array of track arrays from each candidate query
 * @returns {object[]} Interleaved 1D candidate pool
 */
function interleaveQueries(queryResults) {
  if (!Array.isArray(queryResults) || queryResults.length === 0) return [];
  const validArrays = queryResults.filter((arr) => Array.isArray(arr) && arr.length > 0);
  if (validArrays.length === 0) return [];

  const interleaved = [];
  let maxLen = 0;
  for (const arr of validArrays) {
    if (arr.length > maxLen) maxLen = arr.length;
  }

  for (let i = 0; i < maxLen; i++) {
    for (const arr of validArrays) {
      if (i < arr.length) {
        interleaved.push(arr[i]);
      }
    }
  }
  return interleaved;
}

/**
 * Maps an eligible CanonicalTrack to the outbound playlist DTO.
 *
 * @param {object} track - CanonicalTrack from ytmusicProvider
 * @param {string} videoId - Already-resolved canonical id
 * @returns {object} Outbound track DTO
 */
function toPlaylistTrackDTO(track, videoId) {
  const thumbnails = Array.isArray(track.thumbnails) ? track.thumbnails : [];
  return {
    id: videoId,
    videoId,
    title: track.title || 'Unknown Title',
    artist: track.artist || 'Unknown Artist',
    album: track.album || '',
    duration: track.duration || 0,
    artworkUrl: bestArtwork(track),
    thumbnails,
    resultType: 'song',
  };
}

/**
 * Resolves the raw candidate pool for a definition.
 * Queries run concurrently via Promise.allSettled and results are interleaved round-robin.
 *
 * @param {object} definition
 * @returns {Promise<object[]>} CanonicalTrack[]
 */
async function resolveCandidatePool(definition) {
  let queries = definition.candidateQueries || [];

  // If candidateQueries is empty, fallback to artistName or name
  if (queries.length === 0) {
    if (definition.artistStrategy === 'top_tracks' && definition.artistName) {
      queries = [`${definition.artistName} top hits`];
    } else {
      queries = [definition.name];
    }
  }

  // F-01: Resolve {year} template tokens at resolution time (NOT module load time).
  // This prevents the year from being frozen if the process boots on Dec 31 and runs into Jan.
  // {year} is a search hint to bias ranking toward current-year uploads — NOT a release-date filter.
  const currentYear = new Date().getFullYear();
  queries = queries.map((q) => q.replace(/\{year\}/g, String(currentYear)));

  const settled = await Promise.allSettled(queries.map((q) => ytmusic.searchSongs(q)));


  const queryResults = [];
  settled.forEach((outcome, i) => {
    if (outcome.status === 'fulfilled' && Array.isArray(outcome.value)) {
      queryResults.push(outcome.value);
    } else if (outcome.status === 'rejected') {
      logger.warn(`[PlaylistService] Query failed "${queries[i]}": ${outcome.reason?.message || outcome.reason}`);
    }
  });

  return interleaveQueries(queryResults);
}

/**
 * Minimal deterministic artist de-clustering.
 * Prevents adjacent tracks from having the same primary artist by shifting
 * conflicts to the nearest viable position without destroying upstream relevance.
 *
 * @param {object[]} tracks
 * @returns {object[]} De-clustered track array
 */
function declusterArtists(tracks) {
  if (!Array.isArray(tracks) || tracks.length <= 2) return tracks;

  const result = [...tracks];

  // Phase 1: Forward sweep for indices 1..N-2 (unchanged behavior)
  for (let i = 1; i < result.length - 1; i++) {
    const prevArtist = normalizePrimaryArtist(result[i - 1]);
    const currArtist = normalizePrimaryArtist(result[i]);

    if (prevArtist && currArtist && prevArtist === currArtist) {
      // Find the next track with a different primary artist
      for (let j = i + 1; j < result.length; j++) {
        const candidateArtist = normalizePrimaryArtist(result[j]);
        if (candidateArtist !== currArtist) {
          const temp = result[i];
          result[i] = result[j];
          result[j] = temp;
          break;
        }
      }
    }
  }

  // F-07 Phase 2: Final pair check.
  // The forward sweep cannot fix a conflict at the last position since there is
  // nothing to swap forward to. Handle it explicitly by trying to swap backward.
  const lastIdx = result.length - 1;
  const lastArtist = normalizePrimaryArtist(result[lastIdx]);
  const penArtist = normalizePrimaryArtist(result[lastIdx - 1]);

  if (lastArtist && penArtist && lastArtist === penArtist) {
    let swapped = false;

    // Strategy 1 (Preferred): Swap penultimate element result[lastIdx - 1] backward.
    // Minimal disruption: keeps Track #1 (and upstream tracks) in place.
    // e.g. [A, B, C, C] → [A, C, B, C]
    for (let k = lastIdx - 2; k >= 0; k--) {
      const candidateArtist = normalizePrimaryArtist(result[k]);
      const prevK = k > 0 ? normalizePrimaryArtist(result[k - 1]) : null;
      if (k === lastIdx - 2) {
        // Swapping adjacent elements result[lastIdx-2] and result[lastIdx-1]:
        if (candidateArtist !== penArtist && (!prevK || penArtist !== prevK)) {
          const temp = result[lastIdx - 1];
          result[lastIdx - 1] = result[k];
          result[k] = temp;
          swapped = true;
          break;
        }
      } else {
        const nextK = normalizePrimaryArtist(result[k + 1]);
        const leftPenNeighbor = normalizePrimaryArtist(result[lastIdx - 2]);
        if (candidateArtist !== penArtist &&
            candidateArtist !== leftPenNeighbor &&
            (!prevK || penArtist !== prevK) &&
            penArtist !== nextK) {
          const temp = result[lastIdx - 1];
          result[lastIdx - 1] = result[k];
          result[k] = temp;
          swapped = true;
          break;
        }
      }
    }

    // Strategy 2 (Fallback): If penultimate swap was not possible, attempt tail swap.
    // Swaps result[lastIdx] with an interior slot k (k <= lastIdx - 3).
    // Note: if k === lastIdx - 2, result[k+1] is penArtist (which equals lastArtist),
    // so moving lastArtist to k would create an adjacent collision with k+1.
    if (!swapped) {
      for (let k = lastIdx - 3; k >= 0; k--) {
        const candidateArtist = normalizePrimaryArtist(result[k]);
        const prevK = k > 0 ? normalizePrimaryArtist(result[k - 1]) : null;
        const nextK = normalizePrimaryArtist(result[k + 1]);
        if (candidateArtist !== penArtist &&
            (!prevK || lastArtist !== prevK) &&
            lastArtist !== nextK) {
          const temp = result[lastIdx];
          result[lastIdx] = result[k];
          result[k] = temp;
          swapped = true;
          break;
        }
      }
    }
  }

  return result;
}


class PlaylistService {
  /**
   * Generates a curated playlist using the V2 Data Quality Engine.
   *
   * @param {string} playlistId
   * @returns {Promise<object[]>} Outbound track DTOs
   */
  static async generateCuratedPlaylist(playlistId) {
    const cached = _playlistCache.get(playlistId);
    if (cached) return cached;

    const definition = getDefinitionById(playlistId);
    if (!definition) {
      throw new Error(`Playlist definition not found: ${playlistId}`);
    }

    const pool = await resolveCandidatePool(definition);
    const eligibility = definition.eligibility || {};
    const maxArtistTracks = Number.isFinite(eligibility.maxArtistTracks)
      ? eligibility.maxArtistTracks
      : Number.POSITIVE_INFINITY;

    const eligibleTracks = [];
    const deferredTracks = [];
    const seenVideoIds = new Set();
    const seenCanonicalKeys = new Set();
    const primaryCounts = new Map();
    const contributorCounts = new Map();
    const rejections = Object.create(null);

    const note = (reason) => {
      rejections[reason] = (rejections[reason] || 0) + 1;
    };

    // ── PASS 1: Strict Quality & Primary Diversity Selection ─────────────────
    for (const rawTrack of pool) {
      if (eligibleTracks.length >= definition.size) break;

      const videoId = rawTrack.videoId || rawTrack.id;
      if (!videoId) {
        note('missing_video_id');
        continue;
      }
      if (seenVideoIds.has(videoId)) {
        note('duplicate_video_id');
        continue;
      }

      // Canonical deduplication (packaging noise stripped)
      const canonicalKey = getCanonicalTrackKey(
        rawTrack,
        Boolean(eligibility.allowRemix),
        definition.id === 'acoustic_unplugged'
      );
      if (seenCanonicalKeys.has(canonicalKey)) {
        note('duplicate_canonical');
        continue;
      }

      // Stateless hard eligibility gate
      const { eligible, reason } = isEligible(rawTrack, eligibility);
      if (!eligible) {
        note(reason);
        continue;
      }

      // Robust primary artist extraction
      const primaryArtist = normalizePrimaryArtist(rawTrack);
      const currentPrimaryCount = primaryCounts.get(primaryArtist) || 0;

      // Primary Artist Cap (HARD)
      if (currentPrimaryCount >= maxArtistTracks) {
        note('max_artist_tracks');
        continue;
      }

      // Contributor Repetition (SOFT)
      const contributors = extractAllContributors(rawTrack);

      // Phase 6 / Stage 5C: Target Artist Performer Membership Gate
      // For performer-oriented artist playlists, the target artist must be present in
      // the provider's credited performer metadata. Solos, duets, and featured collaborations
      // are all valid and accepted. Unrelated tracks and covers are rejected.
      if (definition.artistName && definition.artistRole !== 'composer') {
        if (!hasTargetPerformer(contributors, definition.artistName)) {
          note('target_performer_missing');
          continue;
        }
      }

      const isFrequentContributor = contributors.some(
        (c) => c !== primaryArtist && (contributorCounts.get(c) || 0) >= maxArtistTracks
      );

      if (isFrequentContributor && definition.artistStrategy !== 'top_tracks') {
        // Defer track to Pass 2 rather than rejecting
        deferredTracks.push({ rawTrack, videoId, primaryArtist, contributors, canonicalKey });
        note('contributor_deferred');
        continue;
      }

      // Accept track
      seenVideoIds.add(videoId);
      seenCanonicalKeys.add(canonicalKey);
      primaryCounts.set(primaryArtist, currentPrimaryCount + 1);
      for (const c of contributors) {
        contributorCounts.set(c, (contributorCounts.get(c) || 0) + 1);
      }
      eligibleTracks.push(toPlaylistTrackDTO(rawTrack, videoId));
    }

    // ── PASS 2: Controlled Fallback for Deferred Contributor Tracks ───────────
    if (eligibleTracks.length < definition.size && deferredTracks.length > 0) {
      for (const item of deferredTracks) {
        if (eligibleTracks.length >= definition.size) break;

        const currentPrimaryCount = primaryCounts.get(item.primaryArtist) || 0;
        // F-03: Check both videoId AND canonicalKey to prevent deferred duplicates.
        // Deferred tracks were not added to seenCanonicalKeys in Pass 1, so two different
        // uploads of the same song (official audio vs lyrical) could both survive to Pass 2.
        if (currentPrimaryCount < maxArtistTracks &&
            !seenVideoIds.has(item.videoId) &&
            !seenCanonicalKeys.has(item.canonicalKey)) {
          seenVideoIds.add(item.videoId);
          seenCanonicalKeys.add(item.canonicalKey);
          primaryCounts.set(item.primaryArtist, currentPrimaryCount + 1);
          for (const c of item.contributors) {
            contributorCounts.set(c, (contributorCounts.get(c) || 0) + 1);
          }
          eligibleTracks.push(toPlaylistTrackDTO(item.rawTrack, item.videoId));
          note('contributor_deferred_accepted');
        }
      }
    }


    // ── PASS 3: Minimal Deterministic Artist De-clustering ───────────────────
    const finalTracks = declusterArtists(eligibleTracks);

    // ── POST-GENERATION QA HEALTH METRICS (Diagnostics only) ─────────────────
    const uniquePrimaryArtists = new Set(finalTracks.map((t) => normalizePrimaryArtist(t))).size;
    const fillPercentage = Math.round((finalTracks.length / definition.size) * 100);

    const qaMetrics = {
      playlistId,
      targetSize: definition.size,
      resolvedCount: finalTracks.length,
      fillPercentage: `${fillPercentage}%`,
      uniquePrimaryArtists,
      candidatesEvaluated: pool.length,
      rejections,
    };

    if (finalTracks.length < definition.size) {
      logger.warn(`[PlaylistService:QA] Playlist "${playlistId}" under-filled`, qaMetrics);
    } else {
      logger.info(`[PlaylistService:QA] Playlist "${playlistId}" filled`, qaMetrics);
    }

    if (finalTracks.length > 0) {
      _playlistCache.set(playlistId, finalTracks);
    }
    return finalTracks;
  }

  /**
   * Derives a playlist's cover artwork from real track artwork.
   *
   * @param {string} playlistId
   * @returns {Promise<string|null>} Artwork URL or null
   */
  static async getPlaylistCover(playlistId) {
    const cached = _coverCache.get(playlistId);
    if (cached) return cached;

    const definition = getDefinitionById(playlistId);
    if (!definition) return null;

    const warm = _playlistCache.get(playlistId);
    if (Array.isArray(warm) && warm.length > 0 && warm[0].artworkUrl) {
      _coverCache.set(playlistId, warm[0].artworkUrl);
      return warm[0].artworkUrl;
    }

    const queries = definition.candidateQueries || [];
    const query = queries[0] || (definition.artistName ? `${definition.artistName} top hits` : definition.name);

    if (!query) {
      logger.warn(`[PlaylistService] No query available to derive cover for "${playlistId}"`);
      return null;
    }

    try {
      const candidates = await ytmusic.searchSongs(query);
      const eligibility = definition.eligibility || {};

      for (const track of Array.isArray(candidates) ? candidates : []) {
        if (!(track.videoId || track.id)) continue;
        if (!isEligible(track, eligibility).eligible) continue;

        const url = bestArtwork(track);
        if (url) {
          _coverCache.set(playlistId, url);
          return url;
        }
      }
    } catch (err) {
      logger.warn(`[PlaylistService] Cover derivation failed for "${playlistId}": ${err.message}`);
    }

    logger.warn(`[PlaylistService] No cover artwork derivable for "${playlistId}"`);
    return null;
  }

  /**
   * Derives covers for several playlists concurrently.
   *
   * @param {string[]} playlistIds
   * @returns {Promise<Record<string, string|null>>}
   */
  static async getPlaylistCovers(playlistIds) {
    const settled = await Promise.allSettled(
      playlistIds.map((id) => PlaylistService.getPlaylistCover(id))
    );

    const covers = Object.create(null);
    playlistIds.forEach((id, i) => {
      covers[id] = settled[i].status === 'fulfilled' ? (settled[i].value || null) : null;
    });
    return covers;
  }
}

// Export helpers for unit testing
PlaylistService.interleaveQueries = interleaveQueries;
PlaylistService.normalizePrimaryArtist = normalizePrimaryArtist;
PlaylistService.extractAllContributors = extractAllContributors;
PlaylistService.getCanonicalTrackKey = getCanonicalTrackKey;
PlaylistService.declusterArtists = declusterArtists;
PlaylistService.cleanArtistString = cleanArtistString;
PlaylistService.hasTargetPerformer = hasTargetPerformer;

module.exports = PlaylistService;
