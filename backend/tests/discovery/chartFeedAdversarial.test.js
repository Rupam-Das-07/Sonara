'use strict';

/**
 * chartFeedAdversarial.test.js — ADVERSARIAL attacks on the native chart-ingestion
 * feed, designed from the stated architecture and the public interface, targeting the
 * paths the existing suites do not reach.
 *
 * Already covered elsewhere (not duplicated here): cold/warm fetch, in-flight
 * coalescing, empty-not-cached, provider-error cleanup, cache-key separation by
 * example, canonical dedup, search fallback on empty and on throw, snapshot
 * preservation, negative backoff, the 30-definition catalogue invariant, and the
 * maxArtistTracks=2 cap on a chart feed.
 *
 * Gaps attacked here:
 *   A1 [state]      the 6-hour feed TTL actually expires and forces a re-fetch
 *   A2 [boundary]   1 ms before the TTL the entry is still served (off-by-one)
 *   A3 [input]      a malformed chartId never reaches upstream and does not break resolution
 *   A4 [property]   cache-key injectivity over many generated tuples
 *   A5 [regression] chart tracks do NOT bypass the eligibility rules search tracks obey
 *   A6 [state]      the bounded cache never exceeds its declared maximum
 *
 * Acceptance criteria referenced:
 *   AC1 six-hour bounded feed cache
 *   AC2 feed-specific in-flight deduplication
 *   AC4 eligibility / artist caps / dedup / declustering applied to every source
 *   AC6 cache keys separate feed type, country, language and chart id
 *   AC7 an invalid chart id must not prevent resolution
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

const { createTrack } = require('../../src/search/trackModel');
const { getDefinitionById } = require('../../src/discovery/PlaylistDefinitions');

// ---------------------------------------------------------------------------
// Harness — same shape the existing discovery suites use: inject a stub provider
// through the require cache so each test starts from empty module state.
// ---------------------------------------------------------------------------

/** Options the feed service forwarded upstream, newest last. */
let upstreamOptions;
/** What the stub provider returns for getChartTracks. */
let chartResponse;
/** What the stub provider returns for searchSongs. */
let searchResponse;
let chartCalls;
let searchCalls;

function installProvider() {
  for (const k of Object.keys(require.cache)) {
    if (k === PROVIDER || k === FEED_SERVICE || k === SERVICE) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      getChartTracks: async (options) => {
        chartCalls++;
        upstreamOptions.push(options);
        return chartResponse;
      },
      searchSongs: async () => { searchCalls++; return searchResponse; },
      searchMetrics: {},
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
    },
  };
}

/** Fresh feed service with the stub provider underneath. */
function freshFeed() {
  installProvider();
  return require(FEED_SERVICE);
}

/** Fresh PlaylistService (and feed service) with the stub provider underneath. */
function freshPlaylistService() {
  installProvider();
  return require(SERVICE);
}

/** MUST be awaited: the clock has to stay pinned for the whole async body. */
async function withClock(startMs, fn) {
  const real = Date.now;
  let now = startMs;
  Date.now = () => now;
  const advance = (ms) => { now += ms; };
  try {
    return await fn({ advance });
  } finally {
    Date.now = real;
  }
}

function track(overrides = {}) {
  return createTrack({
    videoId: 'vid00000001',
    title: 'Clean New Song',
    artist: 'Solo Artist',
    album: 'Album',
    year: '2026',
    duration_seconds: 200,
    thumbnails: [{ url: 'https://img/x.jpg', width: 480, height: 360 }],
    ...overrides,
  });
}

const VALID_CHART_ID = 'PL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX';

beforeEach(() => {
  upstreamOptions = [];
  chartCalls = 0;
  searchCalls = 0;
  chartResponse = [track()];
  searchResponse = [];
});

describe('ADVERSARIAL — native chart feed', () => {

  // ----------------------------------------------------------------- A1 [state]

  /** A1 — AC1: once the six-hour TTL elapses the feed must be fetched again. */
  test('A1 the feed cache expires after its TTL and re-fetches', async () => {
    await withClock(1_770_000_000_000, async ({ advance }) => {
      const feed = freshFeed();
      const { DiscoveryFeedService, FEED_TTL_MS } = feed;

      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(chartCalls, 1, 'cold call must reach upstream');

      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(chartCalls, 1, 'warm call must be served from cache');

      advance(FEED_TTL_MS + 1);
      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(chartCalls, 2, 'past the TTL the feed must be re-fetched (AC1)');
    });
  });

  // -------------------------------------------------------------- A2 [boundary]

  /** A2 — AC1: one millisecond inside the TTL must still be a cache hit. */
  test('A2 one millisecond before the TTL the cached feed is still served', async () => {
    await withClock(1_770_000_000_000, async ({ advance }) => {
      const { DiscoveryFeedService, FEED_TTL_MS } = freshFeed();

      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(chartCalls, 1);

      advance(FEED_TTL_MS - 1);
      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(chartCalls, 1, 'inside the TTL there must be no upstream call (AC1)');
    });
  });

  // ------------------------------------------------------------------ A3 [input]

  /**
   * A3 — AC7: a malformed chart id must neither be forwarded upstream nor stop the
   * feed from resolving. Resolution has to continue so the service can fall back to
   * dynamic chart identification.
   */
  test('A3 a malformed chartId is not forwarded upstream and resolution still succeeds', async () => {
    await withClock(1_770_000_000_000, async () => {
      const { DiscoveryFeedService } = freshFeed();

      for (const bad of ['NOT_A_PLAYLIST', 'PLshort', '../../etc/passwd', 'PL bad spaces',
                         '', '   ', 12345, null, undefined, {}, []]) {
        upstreamOptions = [];
        chartCalls = 0;
        const { _feedCache } = require(FEED_SERVICE);
        _feedCache.clear();

        const out = await DiscoveryFeedService.getChartTracks({ chartId: bad });

        assert.ok(Array.isArray(out), `"${String(bad)}" must still resolve to an array (AC7)`);
        assert.equal(chartCalls, 1, `"${String(bad)}" must not abort the upstream fetch (AC7)`);
        const forwarded = upstreamOptions[0] || {};
        assert.ok(
          forwarded.playlistId === undefined || forwarded.playlistId === null,
          `a malformed chartId must never be forwarded upstream, got ${JSON.stringify(forwarded.playlistId)}`
        );
      }
    });
  });

  /** A3b — AC7: a VALID chart id must be forwarded unchanged. */
  test('A3b a valid chartId is forwarded upstream verbatim', async () => {
    await withClock(1_770_000_000_000, async () => {
      const { DiscoveryFeedService } = freshFeed();
      await DiscoveryFeedService.getChartTracks({ chartId: VALID_CHART_ID });
      assert.equal(upstreamOptions[0].playlistId, VALID_CHART_ID);
    });
  });

  // --------------------------------------------------------------- A4 [property]

  /**
   * A4 — AC6 property/fuzz: the cache key must be an injective, deterministic
   * function of (feedType, country, language, chartId). Checked against a
   * brute-force oracle over many generated tuples rather than a few examples.
   */
  test('A4 property: cache keys are injective and deterministic over generated tuples', async () => {
    const { getFeedCacheKey } = freshFeed();

    const types = ['chart', 'explore', 'new_releases'];
    const countries = ['IN', 'US', 'GB', 'JP'];
    const languages = ['Hindi', 'Punjabi', 'Tamil', 'English'];
    const ids = ['PL1111111111111111', 'PL2222222222222222', 'dynamic'];

    const seen = new Map();
    let tuples = 0;

    for (const type of types) {
      for (const country of countries) {
        for (const language of languages) {
          for (const chartId of ids) {
            tuples++;
            const tuple = JSON.stringify([type, country, language, chartId]);
            const key = getFeedCacheKey(type, { country, language, chartId });

            // determinism: same tuple, same key, every time
            assert.equal(key, getFeedCacheKey(type, { country, language, chartId }),
              'the key must be deterministic');

            // injectivity: a key may only ever belong to one tuple
            if (seen.has(key)) {
              assert.equal(seen.get(key), tuple,
                `key collision: "${key}" produced by ${seen.get(key)} and ${tuple} (AC6)`);
            }
            seen.set(key, tuple);
          }
        }
      }
    }

    assert.equal(tuples, types.length * countries.length * languages.length * ids.length);
    assert.equal(seen.size, tuples, `all ${tuples} distinct tuples must yield ${tuples} distinct keys (AC6)`);

    // Case-insensitivity invariant: country and language must normalise, so equal
    // tuples differing only in case must NOT create a second cache entry.
    assert.equal(
      getFeedCacheKey('chart', { country: 'in', language: 'HINDI', chartId: 'dynamic' }),
      getFeedCacheKey('chart', { country: 'IN', language: 'hindi', chartId: 'dynamic' }),
      'country/language case must not fragment the cache (AC6)'
    );
  });

  // ------------------------------------------------------------- A5 [regression]

  /**
   * A5 — AC4: the chart source must not become an eligibility bypass. Tracks that
   * the pipeline rejects for a search source must be rejected for a chart source too.
   *
   * Expected values come from the trending_now DEFINITION (the spec), not from the
   * implementation: minDurationSeconds 90, maxDurationSeconds 480, allowRemix false,
   * negativeKeywords including 'retro'.
   */
  test('A5 chart tracks are filtered by the same eligibility rules as search tracks', async () => {
    const def = getDefinitionById('trending_now');
    assert.equal(def.eligibility.minDurationSeconds, 90, 'spec anchor');
    assert.equal(def.eligibility.maxDurationSeconds, 480, 'spec anchor');
    assert.equal(def.eligibility.allowRemix, false, 'spec anchor');
    assert.ok(def.eligibility.negativeKeywords.includes('retro'), 'spec anchor');

    chartResponse = [
      track({ videoId: 'keepA000001', title: 'Eligible Song A', artist: 'Artist A' }),
      track({ videoId: 'keepB000001', title: 'Eligible Song B', artist: 'Artist B' }),
      track({ videoId: 'dropShort01', title: 'Too Short', artist: 'Artist C', duration_seconds: 45 }),
      track({ videoId: 'dropLong001', title: 'Too Long', artist: 'Artist D', duration_seconds: 600 }),
      track({ videoId: 'dropRemix01', title: 'Eligible Song E (Remix)', artist: 'Artist E' }),
      track({ videoId: 'dropRetro01', title: 'Retro Gold Hits', artist: 'Artist F' }),
    ];

    await withClock(1_770_000_000_000, async () => {
      const svc = freshPlaylistService();
      const out = await svc.generateCuratedPlaylist('trending_now');
      const ids = out.map((t) => t.videoId);

      assert.ok(ids.includes('keepA000001'), 'an eligible chart track must survive');
      assert.ok(ids.includes('keepB000001'), 'an eligible chart track must survive');
      assert.ok(!ids.includes('dropShort01'), 'below minDurationSeconds must be rejected (AC4)');
      assert.ok(!ids.includes('dropLong001'), 'above maxDurationSeconds must be rejected (AC4)');
      assert.ok(!ids.includes('dropRemix01'), 'a remix must be rejected when allowRemix is false (AC4)');
      assert.ok(!ids.includes('dropRetro01'), 'a negativeKeywords hit must be rejected (AC4)');
      assert.equal(out.length, 2, 'exactly the two eligible chart tracks may be selected');
    });
  });

  // ----------------------------------------------------------------- A6 [state]

  /** A6 — AC1: the feed cache is bounded and must never exceed its maximum. */
  test('A6 the feed cache stays within its declared bound under key pressure', async () => {
    await withClock(1_770_000_000_000, async () => {
      const { DiscoveryFeedService, _feedCache } = freshFeed();
      const bound = _feedCache.maxSize;
      assert.ok(Number.isInteger(bound) && bound > 0, 'the cache must declare a bound');

      for (let i = 0; i < bound * 2 + 5; i++) {
        await DiscoveryFeedService.getChartTracks({ country: 'IN', language: `Lang${i}` });
      }

      assert.ok(_feedCache.size <= bound,
        `cache size ${_feedCache.size} must never exceed its bound ${bound} (AC1)`);
    });
  });
});
