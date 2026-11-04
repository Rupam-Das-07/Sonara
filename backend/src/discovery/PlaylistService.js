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
const { shouldRefresh, resolveIntervalDays } = require('./PlaylistRefreshPolicy');
const { DiscoveryFeedService } = require('./discoveryFeedService');
const BoundedCache = require('../utils/BoundedCache');
const config = require('../config/env');
const logger = require('../utils/logger');

const _playlistCache = new BoundedCache({ maxSize: 50, ttlMs: 60 * 60 * 1000 }); // 1 hour
const _coverCache = new BoundedCache({ maxSize: 100, ttlMs: 24 * 60 * 60 * 1000 }); // 24 hours

// Last-known-good generated content per playlist, retained independently of
// `_playlistCache`. Two product requirements need exactly this and cannot be served
// by the cache alone:
//   1. serving a playlist for its whole declared cadence — the cache's own 1-hour TTL
//      would otherwise evict it long before a 2/7/14-day cadence elapses;
//   2. "a failed refresh must never destroy a healthy playlist" — after eviction there
//      would be no healthy result left to fall back to.
// Bounded by the number of definitions (30), so this cannot grow without limit.
const _playlistSnapshot = new Map();
const _playlistGeneratedAt = new Map();
// One generation/commit per playlist; unrelated definitions progress independently.
const _playlistInFlight = new Map();

// Negative-result backoff.
//
// Some playlists can legitimately resolve to NOTHING on a given catalogue: the
// era-gated ones (`retro_bollywood` requires 1980–2000, `golden_bollywood` requires
// pre-1985) reject every modern candidate, and an inherently thin query set can
// under-produce. Because an empty result is deliberately never cached (so it can
// never replace a healthy playlist), such a playlist was previously retried on EVERY
// single request, forever — measured at 1,443 regeneration attempts / 4,329 upstream
// search calls in one simulated day of traffic for a single playlist.
//
// This records "do not re-attempt before" for those candidates. The window is the
// playlist's own declared cadence, floored at [EMPTY_RETRY_FLOOR_MS] so a playlist
// that is empty for an unrelated transient reason still recovers promptly rather than
// being wedged for a fortnight. A cached healthy snapshot, when one exists, is always
// preferred over this path, so this can never mask a good playlist.
const _playlistEmptyUntil = new Map();
// A non-empty but under-filled generation is retryable only when at least one
// attempted source failed. Successful sources may legitimately yield fewer tracks
// than the editorial target, so those results remain cacheable.
const _playlistRetryUntil = new Map();
const EMPTY_RETRY_FLOOR_MS = 60 * 60 * 1000; // 1 hour

/**
 * How long to wait before re-attempting a playlist whose generation produced nothing.
 *
 * The playlist's own declared cadence, floored at [EMPTY_RETRY_FLOOR_MS] so a playlist
 * that is temporarily empty still recovers promptly rather than being wedged for a
 * fortnight. A cadence is always >= 1 day, so in practice this is the cadence; the
 * floor only matters if that guarantee ever changes.
 *
 * @param {object} definition
 * @returns {number} milliseconds
 */
function emptyBackoffMs(definition) {
  const cadenceMs = resolveIntervalDays(definition) * 24 * 60 * 60 * 1000;
  return Math.max(EMPTY_RETRY_FLOOR_MS, cadenceMs);
}

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
    .normalize('NFC')
    .toLowerCase()
    .normalize('NFC')
    .replace(/[^\p{L}\p{M}\p{N}_\s-]/gu, '')
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
  let title = (track.title || '').normalize('NFC').toLowerCase().normalize('NFC');

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
  title = title.replace(/[^\p{L}\p{M}\p{N}_\s]/gu, '');
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
 * How long a playlist waits for the OPTIONAL native feed before serving the
 * candidates it already has.
 *
 * The native path is bounded at one shelf call plus ceil(10/4) = 3 waves of album
 * lookups, so its worst case is FOUR sequential upstream timeouts. Measured against a
 * 200ms stub the whole path cost ~805ms (4 waves) even though the search queries had
 * already returned enough eligible tracks at ~20ms; at the production timeout that
 * shape extrapolates to ~4 x ytmusicTimeoutMs. One timeout unit is therefore the
 * budget: it comfortably clears a healthy native fetch (~130ms measured) while
 * capping a stalled one at a single wave instead of four.
 */
const NATIVE_ENRICHMENT_BUDGET_MS = config.ytmusicTimeoutMs || 8000;

/**
 * Resolves with `promise`'s value, or with [] once `budgetMs` elapses.
 *
 * Work left running past the budget is bounded and self-cleaning, so nothing is
 * orphaned in a harmful sense: it is capped at <= 10 album lookups with concurrency 4
 * and per-request abort timeouts, it cannot reject (getNewReleaseTracks swallows its
 * own errors), and it removes its own in-flight entry in a finally block. The timer is
 * unref'd and always cleared, so it can neither hold the event loop open nor
 * accumulate.
 *
 * COST OF A TRIP — do not overstate this. The abandoned work does write its result
 * into the feed cache when it finishes, but that cache has a 6h TTL while a playlist's
 * cadence and its empty-result backoff can be as long as 48h (fresh_releases:
 * updateIntervalDays 2, and emptyBackoffMs = max(1h, cadence)). When the next
 * regeneration is further away than 6h the entry has already expired, so that late
 * result is NOT generally available to the following refresh, and both caches live in
 * the same process memory so a restart clears them together. In other words a trip can
 * leave this playlist without native enrichment for the whole next refresh window,
 * serving search-only results until then.
 *
 * That is a deliberate trade, not a free lunch: the alternative is making a user wait
 * for up to four sequential upstream timeouts. It is also not an inevitable one — a
 * trip only happens when the native path actually overruns the budget, a healthy
 * fetch (measured ~130ms against stubs) finishes far inside it and contributes
 * normally, and a refresh that does land within 6h of the cached entry still uses it.
 *
 * @param {Promise<object[]>} promise
 * @param {number} budgetMs
 * @param {string} playlistId - for the log line only
 * @returns {Promise<object[]>}
 */
function withEnrichmentBudget(promise, budgetMs, playlistId) {
  let timer = null;

  const budgetExpired = new Promise((resolve) => {
    timer = setTimeout(() => {
      logger.warn(`[PlaylistService] Native feed exceeded its ${budgetMs}ms enrichment budget for "${playlistId}"; serving the candidates already resolved`);
      resolve([]);
    }, budgetMs);
    if (timer && typeof timer.unref === 'function') timer.unref();
  });

  return Promise.race([promise, budgetExpired]).finally(() => clearTimeout(timer));
}

/**
 * Resolves and selects the candidate pool for a definition.
 * Queries run concurrently via Promise.allSettled and results are interleaved round-robin.
 *
 * @param {object} definition
 * @returns {Promise<{finalTracks: object[], rejections: object, candidatesEvaluated: number}>}
 */
async function resolveCandidatePool(definition) {
  const sourceHealth = { attempted: [], failed: [] };
  // Stage 1B: Native Chart Feed Candidate Resolution
  if (definition && (definition.candidateSource === 'chart' || definition.chartConfig)) {
    let chartTracks;
    try {
      const chartConfig = definition.chartConfig || {};
      chartTracks = await DiscoveryFeedService.getChartTracks(chartConfig);
      sourceHealth.attempted.push('chart');
      if (chartTracks?.sourceFailed) sourceHealth.failed.push('chart');
      if (!Array.isArray(chartTracks) || chartTracks.length === 0) {
        logger.warn(`[PlaylistService] Native chart source returned 0 candidates for "${definition.id}"; falling back to search queries`);
      }
    } catch (err) {
      sourceHealth.attempted.push('chart');
      sourceHealth.failed.push('chart');
      logger.warn(`[PlaylistService] Native chart source failed for "${definition.id}": ${err.message}; falling back to search queries`);
    }
    if (Array.isArray(chartTracks) && chartTracks.length > 0) {
      return { ...selectPlaylistTracks(chartTracks, definition), sourceHealth };
    }
  }

  // Stage 1C: Native New-Releases feeder — ADDITIVE, never a replacement.
  // Started here rather than awaited so it overlaps the search queries below. The
  // search queries always still run and contribute. A slow native path can be
  // abandoned only when search can fill the playlist after selection. Freshness
  // from this source is an editorial approximation (shelf
  // membership + container type), NOT a verified release date.
  let readyNativePool;
  let nativeFeedFailed = false;
  const nativeFeedPromise = definition && definition.nativeFeed === 'new_releases'
    ? DiscoveryFeedService.getNewReleaseTracks().catch((err) => {
      nativeFeedFailed = true;
      logger.warn(`[PlaylistService] Native new-releases feed failed for "${definition.id}": ${err.message}; using search candidates only`);
      return [];
    }).then((tracks) => {
      readyNativePool = Array.isArray(tracks) ? tracks : [];
      if (readyNativePool.sourceFailed) nativeFeedFailed = true;
      return readyNativePool;
    })
    : null;

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
  sourceHealth.attempted.push('search');
  if (settled.some((outcome) => outcome.status === 'rejected')) sourceHealth.failed.push('search');


  const queryResults = [];
  settled.forEach((outcome, i) => {
    if (outcome.status === 'fulfilled' && Array.isArray(outcome.value)) {
      queryResults.push(outcome.value);
    } else if (outcome.status === 'rejected') {
      logger.warn(`[PlaylistService] Query failed "${queries[i]}": ${outcome.reason?.message || outcome.reason}`);
    }
  });

  const searchPool = interleaveQueries(queryResults);

  // If native is already healthy, select the hybrid directly: search sufficiency
  // must never suppress native-first round robin or add a needless search-only pass.
  if (readyNativePool?.length > 0) {
    sourceHealth.attempted.push('native');
    if (nativeFeedFailed) sourceHealth.failed.push('native');
    return { ...selectPlaylistTracks(interleaveQueries([readyNativePool, searchPool]), definition), sourceHealth };
  }

  const searchSelection = selectPlaylistTracks(searchPool, definition);
  if (!nativeFeedPromise) return { ...searchSelection, sourceHealth };

  // Count actual final slots, including dedup, eligibility, the hard primary cap
  // and soft-contributor fallback. Raw results can greatly overstate usable tracks.
  // A full search selection gets one timeout unit of OPTIONAL enrichment wait.
  // A short selection waits for the existing bounded native path: one shelf call
  // plus ceil(10 containers / 4 concurrent lookups) = three album waves, each with
  // its existing per-call abort. No new freshness, cadence or backoff policy.
  const searchCanStandAlone = searchSelection.finalTracks.length >= definition.size;
  const nativePool = searchCanStandAlone
    ? await withEnrichmentBudget(nativeFeedPromise, NATIVE_ENRICHMENT_BUDGET_MS, definition.id)
    : await nativeFeedPromise;

  if (!Array.isArray(nativePool) || nativePool.length === 0) {
    sourceHealth.attempted.push('native');
    if (nativeFeedFailed) sourceHealth.failed.push('native');
    logger.warn(`[PlaylistService] Native new-releases feed contributed 0 candidates for "${definition.id}"; using search candidates only`);
    return { ...searchSelection, sourceHealth };
  }

  // Native arrived during the wait: select the hybrid anew because native-first
  // competition can change dedup winners, artist caps and deferred contributors.
  // Appending selected search DTOs would lose that information. The selection is
  // pure; only this final result is logged and committed by the generator.
  sourceHealth.attempted.push('native');
  if (nativeFeedFailed) sourceHealth.failed.push('native');
  return { ...selectPlaylistTracks(interleaveQueries([nativePool, searchPool]), definition), sourceHealth };
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


/**
 * Pure selection shared by the search sufficiency decision and final generation.
 * Keeps the three passes and DTO ordering identical; diagnostics are returned,
 * never logged here, and no cache/cadence state or input candidate is mutated.
 */
function selectPlaylistTracks(pool, definition) {
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
  return { finalTracks: declusterArtists(eligibleTracks), rejections, candidatesEvaluated: pool.length };
}

class PlaylistService {
  /**
   * Generates a curated playlist using the V2 Data Quality Engine.
   *
   * @param {string} playlistId
   * @returns {Promise<object[]>} Outbound track DTOs
   */
  static async generateCuratedPlaylist(playlistId) {
    const existing = _playlistInFlight.get(playlistId);
    if (existing) return existing;

    // Defer execution until the entry is registered, including synchronous failure
    // paths. Store/return the finally promise itself so rejection cleanup cannot
    // create an unobserved secondary rejection.
    const flight = Promise.resolve()
      .then(() => PlaylistService.#generateCuratedPlaylist(playlistId))
      .finally(() => _playlistInFlight.delete(playlistId));
    _playlistInFlight.set(playlistId, flight);
    return flight;
  }

  static async #generateCuratedPlaylist(playlistId) {
    const definition = getDefinitionById(playlistId);
    if (!definition) {
      throw new Error(`Playlist definition not found: ${playlistId}`);
    }

    // Honour the definition's declared cadence. Within the window we serve the stable
    // snapshot (which outlives the cache's shorter memory TTL) without touching upstream.
    const refresh = shouldRefresh({
      definition,
      generatedAtMs: _playlistGeneratedAt.get(playlistId),
      nowMs: Date.now(),
    });

    const healthy = _playlistSnapshot.get(playlistId);

    // A recent attempt already told us upstream has nothing usable. Do not hammer it
    // again until the window expires — and this is checked BEFORE the snapshot so the
    // rule holds identically whether or not a healthy result exists to serve.
    //
    // Review finding: applying this only on the no-snapshot path left a second storm.
    // A playlist with a healthy snapshot whose cadence had elapsed re-attempted
    // generation on EVERY request while upstream kept returning empty (measured: 3
    // upstream calls per request, indefinitely), because a served fallback does not
    // advance the cadence stamp. Every request did a full upstream round trip.
    const emptyUntil = _playlistEmptyUntil.get(playlistId);
    if (emptyUntil && Date.now() < emptyUntil) {
      return healthy || [];
    }
    const retryUntil = _playlistRetryUntil.get(playlistId);
    if (retryUntil && Date.now() < retryUntil) {
      return healthy || [];
    }

    if (healthy && !refresh.due) {
      _playlistCache.set(playlistId, healthy);   // keep the fast path warm
      return healthy;
    }

    const { finalTracks, rejections, candidatesEvaluated, sourceHealth } = await resolveCandidatePool(definition);

    // ── POST-GENERATION QA HEALTH METRICS (Diagnostics only) ─────────────────
    const uniquePrimaryArtists = new Set(finalTracks.map((t) => normalizePrimaryArtist(t))).size;
    const fillPercentage = Math.round((finalTracks.length / definition.size) * 100);

    const qaMetrics = {
      playlistId,
      targetSize: definition.size,
      resolvedCount: finalTracks.length,
      fillPercentage: `${fillPercentage}%`,
      uniquePrimaryArtists,
      candidatesEvaluated,
      rejections,
    };

    if (finalTracks.length < definition.size) {
      logger.warn(`[PlaylistService:QA] Playlist "${playlistId}" under-filled`, qaMetrics);
    } else {
      logger.info(`[PlaylistService:QA] Playlist "${playlistId}" filled`, qaMetrics);
    }

    const underfilled = finalTracks.length > 0 && finalTracks.length < definition.size;
    const unusablePartial = underfilled && sourceHealth.failed.length > 0;

    if (finalTracks.length > 0 && !unusablePartial) {
      _playlistCache.set(playlistId, finalTracks);
      _playlistSnapshot.set(playlistId, finalTracks);
      _playlistGeneratedAt.set(playlistId, Date.now());
      _playlistEmptyUntil.delete(playlistId);
      _playlistRetryUntil.delete(playlistId);
    } else if (unusablePartial && healthy) {
      const retryMs = emptyBackoffMs(definition);
      _playlistRetryUntil.set(playlistId, Date.now() + retryMs);
      logger.warn(`[PlaylistService] Unusable partial regeneration for "${playlistId}"; serving previous healthy result (${healthy.length} tracks)`);
      _playlistCache.set(playlistId, healthy);
      return healthy;
    } else if (unusablePartial) {
      const retryMs = emptyBackoffMs(definition);
      _playlistRetryUntil.set(playlistId, Date.now() + retryMs);
      logger.warn(`[PlaylistService] Unusable partial generation for "${playlistId}"; backing off ${Math.round(retryMs / 60000)}min`);
      return finalTracks;
    } else if (healthy) {
      // A refresh produced nothing usable. NEVER let that replace a healthy playlist:
      // serve the previous good result, leave its cadence stamp untouched, AND record
      // the backoff so the next request does not immediately repeat this round trip.
      _playlistEmptyUntil.set(playlistId, Date.now() + emptyBackoffMs(definition));
      logger.warn(`[PlaylistService] Empty regeneration for "${playlistId}"; serving previous healthy result (${healthy.length} tracks)`);
      _playlistCache.set(playlistId, healthy);
      return healthy;
    } else {
      // Nothing healthy to fall back to. Back off before retrying so a playlist that
      // cannot currently fill does not re-issue its queries on every request.
      const backoffMs = emptyBackoffMs(definition);
      _playlistEmptyUntil.set(playlistId, Date.now() + backoffMs);
      logger.warn(`[PlaylistService] Empty generation for "${playlistId}"; backing off ${Math.round(backoffMs / 60000)}min`);
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
PlaylistService.emptyBackoffMs = emptyBackoffMs;

module.exports = PlaylistService;
