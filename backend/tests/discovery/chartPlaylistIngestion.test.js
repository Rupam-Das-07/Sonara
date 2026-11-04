'use strict';

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');
const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const { getAllDefinitions, getDefinitionById, PLAYLIST_DEFINITIONS, ARTIST_PLAYLIST_DEFINITIONS } = require('../../src/discovery/PlaylistDefinitions');
const { createTrack } = require('../../src/search/trackModel');

let chartProviderCalls = 0;
let searchProviderCalls = 0;
let mockChartTracks = [];
let mockSearchTracks = [];

function freshService(customProvider = {}) {
  for (const k of Object.keys(require.cache)) {
    if (k === SERVICE || k === PROVIDER || k === FEED_SERVICE) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      getChartTracks: async (options) => {
        chartProviderCalls++;
        return mockChartTracks;
      },
      searchSongs: async (query) => {
        searchProviderCalls++;
        return mockSearchTracks;
      },
      searchMetrics: {},
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
      ...customProvider,
    },
  };
  return require(SERVICE);
}

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

function generateCandidates(count, artistVariety = 15) {
  return Array.from({ length: count }, (_, i) =>
    createTrack({
      videoId: `chartVid_${String(i).padStart(4, '0')}`,
      title: `Chart Hit Song ${i}`,
      artist: `Artist ${(i % artistVariety) + 1}`,
      artists: [{ name: `Artist ${(i % artistVariety) + 1}`, id: `art_${(i % artistVariety) + 1}` }],
      album: 'Chart Album 2026',
      duration: 180 + (i % 60),
      duration_seconds: 180 + (i % 60),
      thumbnails: [{ url: `https://img.youtube.com/vi/chartVid_${i}/0.jpg`, width: 480, height: 360 }],
    })
  );
}

describe('Chart Playlist Ingestion & trending_now Pipeline Integration', () => {
  beforeEach(() => {
    chartProviderCalls = 0;
    searchProviderCalls = 0;
    mockChartTracks = generateCandidates(60, 20);
    mockSearchTracks = generateCandidates(60, 20);
  });

  test('trending_now routes through native chart candidate source and selects 25 tracks', async () => {
    const svc = freshService();
    const tracks = await svc.generateCuratedPlaylist('trending_now');

    assert.ok(chartProviderCalls > 0, 'Must query chart provider');
    assert.equal(searchProviderCalls, 0, 'Must NOT query search fallback when chart succeeds');
    assert.equal(tracks.length, 25, 'trending_now must fill target size 25');

    // Validate track metadata integrity
    const artistCounts = {};
    for (let i = 0; i < tracks.length; i++) {
      const t = tracks[i];
      assert.ok(t.videoId && t.videoId.length >= 5, `Track ${i} must have valid native videoId`);
      assert.ok(typeof t.duration === 'number' && t.duration > 0, `Track ${i} must have positive duration`);
      assert.ok(t.artist && t.artist.length > 0, `Track ${i} must have artist`);

      const normArtist = svc.normalizePrimaryArtist(t);
      artistCounts[normArtist] = (artistCounts[normArtist] || 0) + 1;
      assert.ok(artistCounts[normArtist] <= 2, `Artist "${normArtist}" must not exceed maxArtistTracks=2`);

      if (i > 0) {
        const prevArtist = svc.normalizePrimaryArtist(tracks[i - 1]);
        assert.notEqual(prevArtist, normArtist, `Adjacent tracks at index ${i} must be declustered`);
      }
    }
  });

  test('canonical duplicates in chart feed are collapsed before selection', async () => {
    const base = generateCandidates(40, 20);
    // Insert canonical duplicates
    base.splice(1, 0, createTrack({
      videoId: 'dup_vid_1',
      title: 'Chart Hit Song 0 (Official Video)',
      artist: 'Artist 1',
      artists: [{ name: 'Artist 1', id: 'art_1' }],
      album: 'Chart Album 2026',
      duration: 180,
    }));
    base.splice(5, 0, createTrack({
      videoId: 'dup_vid_2',
      title: 'Chart Hit Song 2 [Audio]',
      artist: 'Artist 3',
      artists: [{ name: 'Artist 3', id: 'art_3' }],
      album: 'Chart Album 2026',
      duration: 182,
    }));

    mockChartTracks = base;
    const svc = freshService();
    const tracks = await svc.generateCuratedPlaylist('trending_now');

    assert.equal(tracks.length, 25);
    const seenVids = new Set(tracks.map((t) => t.videoId));
    assert.ok(!seenVids.has('dup_vid_1'), 'Duplicate canonical variant must be rejected');
    assert.ok(!seenVids.has('dup_vid_2'), 'Duplicate canonical audio variant must be rejected');
  });

  test('fallback to search queries when native chart feed produces 0 candidates', async () => {
    mockChartTracks = []; // Empty chart
    const svc = freshService();
    const tracks = await svc.generateCuratedPlaylist('trending_now');

    assert.ok(chartProviderCalls > 0, 'Must have attempted chart provider');
    assert.ok(searchProviderCalls > 0, 'Must fall back to search queries when chart is empty');
    assert.equal(tracks.length, 25, 'Search fallback must fulfill playlist');
  });

  test('fallback to search queries when native chart feed throws an error', async () => {
    const svc = freshService({
      getChartTracks: async () => {
        chartProviderCalls++;
        throw new Error('500 Internal Server Error');
      },
    });

    const tracks = await svc.generateCuratedPlaylist('trending_now');

    assert.equal(chartProviderCalls, 1, 'Attempted chart provider');
    assert.ok(searchProviderCalls > 0, 'Fell back to search queries');
    assert.equal(tracks.length, 25, 'Recovered via search fallback');
  });

  test('last-known-good snapshot preservation when both chart and fallback fail on refresh', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();

      // 1. Initial healthy generation
      const initial = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(initial.length, 25);
      const initialSeedVid = initial[0].videoId;

      // 2. Advance time past 2-day cadence
      advance(3 * 24 * 60 * 60 * 1000);

      // 3. Both chart and search fail on refresh
      mockChartTracks = [];
      mockSearchTracks = [];

      const fallback = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(fallback.length, 25, 'Must preserve healthy snapshot');
      assert.equal(fallback[0].videoId, initialSeedVid, 'Snapshot tracks must match last-known-good');
    });
  });

  test('does not cache a partial refresh when the chart source fails and search is under-filled', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService({
        getChartTracks: async () => { chartProviderCalls++; throw new Error('chart down'); },
      });
      const partial = generateCandidates(2, 20);
      mockSearchTracks = partial;
      const first = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(first.length, 2);

      const callsAfterFirst = searchProviderCalls;
      const second = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(second.length, 0, 'without a prior healthy snapshot, backoff serves no unusable partial');
      assert.equal(searchProviderCalls, callsAfterFirst, 'unusable partial must back off instead of retrying');

      advance(3 * 24 * 60 * 60 * 1000);
      mockChartTracks = generateCandidates(60, 20);
      mockSearchTracks = generateCandidates(60, 20);
      const recovered = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(recovered.length, 25, 'a later successful generation must recover');
    });
  });

  test('preserves a healthy snapshot when a refresh becomes an unusable partial', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      let chartFails = false;
      const svc = freshService({
        getChartTracks: async () => {
          chartProviderCalls++;
          if (chartFails) throw new Error('chart down');
          return mockChartTracks;
        },
      });
      const healthy = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(healthy.length, 25);
      advance(3 * 24 * 60 * 60 * 1000);

      chartFails = true;
      mockChartTracks = [];
      mockSearchTracks = generateCandidates(2, 20);
      const fallback = await svc.generateCuratedPlaylist('trending_now');
      assert.deepEqual(fallback.map((t) => t.videoId), healthy.map((t) => t.videoId));
    });
  });

  test('caches an intentionally smaller result when every attempted source succeeds', async () => {
    const small = generateCandidates(3, 20);
    mockChartTracks = small;
    mockSearchTracks = small;
    const svc = freshService();
    const first = await svc.generateCuratedPlaylist('trending_now');
    assert.equal(first.length, 3);

    const chartCalls = chartProviderCalls;
    const second = await svc.generateCuratedPlaylist('trending_now');
    assert.deepEqual(second.map((t) => t.videoId), first.map((t) => t.videoId));
    assert.equal(chartProviderCalls, chartCalls, 'successful smaller result is cached for the cadence');
  });

  test('negative-result backoff activates on cold empty generation', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      mockChartTracks = [];
      mockSearchTracks = [];
      const svc = freshService();

      const coldEmpty = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(coldEmpty.length, 0);
      const initialChartCalls = chartProviderCalls;
      const initialSearchCalls = searchProviderCalls;

      // Repeat request at +1 hour (during backoff window)
      advance(60 * 60 * 1000);
      const duringBackoff = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(duringBackoff.length, 0);
      assert.equal(chartProviderCalls, initialChartCalls, 'Must NOT query chart during backoff');
      assert.equal(searchProviderCalls, initialSearchCalls, 'Must NOT query search during backoff');
    });
  });

  test('catalogue invariant: exactly 30 definitions, IDs, names, and order are preserved', () => {
    const generalDefs = getAllDefinitions();
    assert.equal(generalDefs.length, 22, 'General curated playlists catalog must have 22 definitions');

    const artistDefs = Object.values(ARTIST_PLAYLIST_DEFINITIONS);
    assert.equal(artistDefs.length, 8, 'Artist curated playlists catalog must have 8 definitions');

    const totalDefs = generalDefs.length + artistDefs.length;
    assert.equal(totalDefs, 30, 'Total curated definitions in Sonara must equal 30');

    const expectedFirst5Ids = ['chill_nights', 'lofi_focus', 'retro_bollywood', 'punjabi_power', 'romantic_hits'];
    for (let i = 0; i < 5; i++) {
      assert.equal(generalDefs[i].id, expectedFirst5Ids[i]);
    }

    const trendingDef = getDefinitionById('trending_now');
    assert.ok(trendingDef, 'trending_now must exist');
    assert.equal(trendingDef.id, 'trending_now');
    assert.equal(trendingDef.name, 'Trending Now');
    assert.equal(trendingDef.size, 25);
    assert.equal(trendingDef.updateIntervalDays, 2);
  });

  test('non-chart curated playlists (e.g. chill_nights) continue using search queries with zero regressions', async () => {
    const svc = freshService();
    const tracks = await svc.generateCuratedPlaylist('chill_nights');

    assert.equal(chartProviderCalls, 0, 'chill_nights must NOT query chart provider');
    assert.ok(searchProviderCalls > 0, 'chill_nights must query search provider');
    assert.equal(tracks.length, 25, 'chill_nights must generate 25 tracks');
  });
});
