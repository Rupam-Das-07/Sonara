'use strict';

// The enrichment budget is read from config at module load, so it must be set before
// anything requires the config module. node:test runs each test FILE in its own child
// process, so this cannot leak into other suites.
process.env.YTMUSIC_TIMEOUT_MS = '120';

/**
 * freshReleasesLatencyBudget.test.js — the native New-Releases feed is ENRICHMENT, so a
 * stalled shelf must not hold back a playlist that search can already fill.
 *
 * Root cause being regressed: resolveCandidatePool awaited the native promise
 * unconditionally. The native path is 1 shelf call + ceil(10/4)=3 album waves = FOUR
 * sequential upstream round trips, and GET /api/playlists/:id awaits it with no timeout
 * of its own, so a user paid the whole thing even when search had already returned
 * enough eligible tracks.
 *
 *   L1 [latency]     stalled native + sufficient search returns on the budget, full
 *   L2 [state]       thin search skips the budget so native can still contribute
 *   L3 [error]       native throwing leaves search candidates intact and fast
 *   L4 [error]       search failing entirely lets native rescue the playlist
 *   L5 [concurrency] abandoned work still respects the 4-lookup and 10-container caps
 *   L6 [state]       early return leaks no in-flight state and writes no empty cache
 *   L7 [regression]  cadence suppression + cold-start backoff (snapshot: see N13)
 *   L8 [regression]  caps / dedup / ordering survive a merge of partial results
 *   L9 [property]    elapsed tracks min(nativeDelay, budget) across generated pairs
 *  L10 [state]       abandoned lookups cannot pollute a later test's measurements
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

const { createTrack } = require('../../src/search/trackModel');
const { getDefinitionById } = require('../../src/discovery/PlaylistDefinitions');

const BUDGET_MS = 120;                 // == YTMUSIC_TIMEOUT_MS above
const WAVE_MS = 200;                   // per-call stub delay
const FULL_NATIVE_MS = WAVE_MS * 4;    // shelf + 3 album waves = the old blocking cost
const SIZE = getDefinitionById('fresh_releases').size;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let shelfHook;
let albumHook;
let searchHook;
let unhandled;

/**
 * Per-test recorder.
 *
 * Album lookups abandoned by the enrichment budget keep running AFTER the test that
 * started them has finished, so counters shared across tests get polluted: a later
 * test would see the earlier test's browseIds in its own call list, and its "peak
 * concurrency" would really be the max across two overlapping pools. That produces
 * false failures, not real ones.
 *
 * installProvider() therefore mints a NEW recorder per install and the stubs close
 * over THAT object. Abandoned work holds the provider closure it started with, so it
 * can only ever write to the recorder that was live at the time. The hooks below stay
 * module-level on purpose (tests assign them after installing), which is safe because
 * hooks only decide behaviour — all recording happens in the per-install wrapper.
 */
let rec;

const newRecorder = () => ({ shelfCalls: 0, albumCalls: [], inFlight: 0, albumPeak: 0 });

function installProvider() {
  for (const k of Object.keys(require.cache)) {
    if (k === PROVIDER || k === FEED_SERVICE || k === SERVICE) delete require.cache[k];
  }
  const own = newRecorder();
  rec = own;
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      getNewReleaseShelf: async () => { own.shelfCalls++; return shelfHook(); },
      getAlbumDetail: async (browseId) => {
        own.albumCalls.push(browseId);
        own.inFlight++;
        if (own.inFlight > own.albumPeak) own.albumPeak = own.inFlight;
        try { return await albumHook(browseId); } finally { own.inFlight--; }
      },
      searchSongs: async (q) => searchHook(q),
      getChartTracks: async () => [],
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
      searchMetrics: {},
    },
  };
  return own;
}

function freshService() {
  installProvider();
  return require(SERVICE);
}
function freshFeed() {
  installProvider();
  return require(FEED_SERVICE);
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function container(i) {
  return {
    browseId: `MPREb_${String(i).padStart(12, 'x')}`,
    title: `Native Album ${i}`,
    type: i % 2 === 0 ? 'Single' : 'EP',
    artist: `Native Artist ${i}`,
    artists: [{ name: `Native Artist ${i}`, id: `UC_n${i}` }],
    year: '',
    thumbnailUrl: `https://img/n${i}.jpg`,
    isExplicit: false,
  };
}

function albumDetailFor(browseId) {
  const i = browseId.replace(/\D/g, '');
  return {
    id: browseId,
    title: `Native Album ${i}`,
    artist: '',
    year: '2026',
    thumbnailUrl: `https://img/n${i}.jpg`,
    tracks: [{
      videoId: `nat${String(i).padStart(8, '0')}`,
      title: `Native Song ${i}`,
      artists: [{ name: `Native Artist ${i}`, id: `UC_n${i}` }],
      album: `Native Album ${i}`,
      duration: '3:20',
      duration_seconds: 200,
      thumbnails: null,
      isAvailable: true,
    }],
  };
}

function searchTrack(i, overrides = {}) {
  return createTrack({
    videoId: `srch${String(i).padStart(7, '0')}`,
    title: `Search Song ${i}`,
    artist: `Search Artist ${i}`,
    artists: [{ name: `Search Artist ${i}`, id: `UC_s${i}` }],
    album: 'Search Album',
    year: '2026',
    duration_seconds: 200,
    thumbnails: [{ url: `https://img/s${i}.jpg` }],
    ...overrides,
  });
}

/** n search candidates spread over the three queries by the interleaver. */
const searchOf = (n, overrides = {}) =>
  Array.from({ length: n }, (_, i) => searchTrack(i + 1, overrides));

const stalledShelf = () => sleep(WAVE_MS).then(() => Array.from({ length: 24 }, (_, i) => container(i)));
const stalledAlbum = async (id) => { await sleep(WAVE_MS); return albumDetailFor(id); };
const fastShelf = () => Promise.resolve(Array.from({ length: 24 }, (_, i) => container(i)));
const fastAlbum = async (id) => albumDetailFor(id);

const elapsed = async (fn) => {
  const t0 = process.hrtime.bigint();
  const value = await fn();
  return { value, ms: Number(process.hrtime.bigint() - t0) / 1e6 };
};

beforeEach(() => {
  rec = newRecorder();
  shelfHook = () => [];
  albumHook = async () => null;
  searchHook = () => [];
  unhandled = [];
  process.removeAllListeners('unhandledRejection');
  process.on('unhandledRejection', (e) => unhandled.push(e));
});

describe('LATENCY BUDGET — native fresh-releases enrichment', () => {

  // ---------------------------------------------------------------- L1 [latency]

  /**
   * L1 — the regression case. Against the pre-fix implementation this takes the full
   * four-wave native cost (~800ms); with the budget it returns at ~120ms with a
   * complete playlist built from the search candidates that were ready all along.
   */
  test('L1 a stalled native feed does not delay a playlist search can already fill', async () => {
    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => searchOf(30); // >= size, so the budget applies

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    assert.equal(tracks.length, SIZE, 'the playlist must still be completely filled');
    assert.ok(
      ms < FULL_NATIVE_MS * 0.6,
      `must return on the budget, not the full native path: took ${ms.toFixed(0)}ms, full native path is ~${FULL_NATIVE_MS}ms`
    );
    assert.ok(ms >= BUDGET_MS * 0.5, `the budget must actually be awaited, took only ${ms.toFixed(0)}ms`);
    assert.equal(tracks.filter((t) => t.videoId.startsWith('nat')).length, 0,
      'a stalled native feed contributes nothing to THIS refresh');
    assert.deepEqual(unhandled, [], 'abandoning the native promise must not raise an unhandled rejection');
  });

  /** L1b — a HEALTHY native feed must still contribute; the budget must not gut it. */
  test('L1b a healthy native feed still contributes and is not cut by the budget', async () => {
    const svc = freshService();
    shelfHook = fastShelf;
    albumHook = fastAlbum;
    searchHook = () => searchOf(30);

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    assert.equal(tracks.length, SIZE, 'playlist filled');
    assert.ok(tracks.filter((t) => t.videoId.startsWith('nat')).length > 0,
      'a fast native feed MUST still reach the playlist — the budget is for stalls only');
    assert.ok(ms < BUDGET_MS, `a healthy native path must not wait on the budget, took ${ms.toFixed(0)}ms`);
  });

  // ------------------------------------------------------------------ L2 [state]

  /**
   * L2 — when search did not even return `size` raw candidates the native feed may be
   * what makes the playlist whole, so the budget is deliberately skipped.
   */
  test('L2 thin search results skip the budget so the native feed can still contribute', async () => {
    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => searchOf(2); // 3 queries x 2 = 6 raw candidates, well under size

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    const native = tracks.filter((t) => t.videoId.startsWith('nat')).length;
    assert.ok(native > 0, 'the native feed must be waited for when search cannot stand alone');
    assert.ok(ms >= FULL_NATIVE_MS * 0.5,
      `the full native path must be awaited here, took only ${ms.toFixed(0)}ms`);
  });

  // ------------------------------------------------------------------ L3 [error]

  test('L3 a throwing native shelf leaves the search candidates intact and fast', async () => {
    const svc = freshService();
    shelfHook = () => { throw new Error('shelf exploded'); };
    albumHook = async () => { throw new Error('must not be reached'); };
    searchHook = () => searchOf(30);

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    assert.equal(tracks.length, SIZE, 'search candidates survive a native failure');
    assert.equal(rec.albumCalls.length, 0, 'no album lookup is attempted when the shelf fails');
    assert.ok(ms < BUDGET_MS, `a fast failure must not wait on the budget, took ${ms.toFixed(0)}ms`);
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });

  // ------------------------------------------------------------------ L4 [error]

  test('L4 when every search query fails the native feed still rescues the playlist', async () => {
    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => { throw new Error('search down'); };

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.ok(tracks.length > 0, 'an empty search pool must not trigger the budget — native is the only source');
    assert.ok(tracks.every((t) => t.videoId.startsWith('nat')), 'every track came from the native feed');
  });

  // ------------------------------------------------------------ L5 [concurrency]

  /**
   * L5 — work abandoned by the budget keeps running, so it must still respect both
   * bounds: never more than 4 lookups at once and never more than 10 in total.
   *
   * The counts come from THIS test's own recorder (see `rec`), so an earlier test's
   * abandoned lookups cannot inflate them.
   */
  test('L5 work abandoned by the budget still respects the concurrency and container caps', async () => {
    const svc = freshService();
    const own = rec;
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => searchOf(30);

    await svc.generateCuratedPlaylist('fresh_releases');
    const atReturn = own.albumCalls.length;

    // Let the abandoned background work finish.
    await sleep(FULL_NATIVE_MS * 2);

    assert.ok(own.albumPeak <= 4, `concurrency must stay bounded at 4, measured peak ${own.albumPeak}`);
    assert.ok(own.albumCalls.length <= 10,
      `the 10-container cap must hold for abandoned work too, total ${own.albumCalls.length} (${atReturn} at return)`);
    assert.ok(own.albumCalls.length > atReturn,
      'precondition: the abandoned work really did keep running after the early return');
    assert.equal(new Set(own.albumCalls).size, own.albumCalls.length, 'no album is fetched twice');
    assert.deepEqual(unhandled, [], 'no unhandled rejection from the abandoned work');
  });

  // ------------------------------------------------------------------ L6 [state]

  /**
   * L6 — after an early return nothing may be left dangling, and the abandoned work
   * must not write an empty result into the feed cache.
   */
  test('L6 an early return leaks no in-flight state and writes no empty cache entry', async () => {
    installProvider();
    const feed = require(FEED_SERVICE);
    const svc = require(SERVICE);
    const { _feedCache, _inFlightFeeds, _inFlightAlbums, getFeedCacheKey } = feed;
    const key = getFeedCacheKey('new_releases', {});

    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => searchOf(30);

    await svc.generateCuratedPlaylist('fresh_releases');
    assert.ok(_inFlightFeeds.size >= 1, 'the abandoned feed fetch is still tracked while it runs');

    await sleep(FULL_NATIVE_MS * 2);

    assert.equal(_inFlightFeeds.size, 0, 'the in-flight feed entry must be cleaned up');
    assert.equal(_inFlightAlbums.size, 0, 'the in-flight album entries must be cleaned up');

    const cached = _feedCache.get(key);
    assert.ok(Array.isArray(cached) && cached.length > 0,
      'the completed work must warm the feed cache with real tracks, not an empty array');
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });

  // ------------------------------------------------------------- L7 [regression]

  /**
   * L7 — the budget must not disturb the existing refresh-control machinery.
   *
   * Scope note: this case covers (1) cadence suppression and (2) negative-result
   * backoff on a COLD start. It deliberately does NOT claim to cover last-known-good
   * snapshot preservation — phase 2 below installs a fresh service, which starts with
   * an empty snapshot map, so there is no snapshot here to preserve. Preservation is
   * genuinely covered by N13 in newReleasesAdversarial.test.js, which builds a real
   * 20-track snapshot, advances past the cadence, breaks every source, and asserts the
   * returned ids deep-equal the original.
   */
  test('L7 cadence suppression and cold-start negative backoff still hold', async () => {
    // ── phase 1: a healthy generation, then no upstream traffic inside the cadence ──
    const svc = freshService();
    const ownA = rec;
    shelfHook = fastShelf;
    albumHook = fastAlbum;
    searchHook = () => searchOf(30);
    const healthy = await svc.generateCuratedPlaylist('fresh_releases');
    assert.equal(healthy.length, SIZE, 'precondition: a full playlist was generated');
    const shelfAfterFirst = ownA.shelfCalls;

    for (let i = 0; i < 4; i++) await svc.generateCuratedPlaylist('fresh_releases');
    assert.equal(ownA.shelfCalls, shelfAfterFirst,
      'the cadence gate must still suppress upstream traffic');

    // ── phase 2: COLD start with nothing upstream -> empty, then backoff suppresses ──
    // A fresh service has no snapshot, so this measures cold-start emptiness and
    // backoff only. See the scope note above for snapshot preservation.
    const svc2 = freshService();
    const ownB = rec;
    shelfHook = () => [];
    albumHook = async () => null;
    searchHook = () => [];
    const first = await svc2.generateCuratedPlaylist('fresh_releases');
    assert.deepEqual(first, [], 'a cold start with nothing upstream yields an empty playlist');

    const shelfBefore = ownB.shelfCalls;
    for (let i = 0; i < 6; i++) await svc2.generateCuratedPlaylist('fresh_releases');
    assert.equal(ownB.shelfCalls, shelfBefore,
      'negative backoff must still suppress repeat upstream attempts after an empty result');
  });

  // ------------------------------------------------------------- L8 [regression]

  test('L8 caps, dedup and ordering survive a merge of partial results', async () => {
    const svc = freshService();
    shelfHook = fastShelf;
    albumHook = fastAlbum;
    // A duplicate videoId, a duplicate artist, and one ineligible track in the mix.
    searchHook = () => [
      searchTrack(1),
      searchTrack(1),                                        // exact duplicate id
      searchTrack(2, { artist: 'Search Artist 1', artists: [{ name: 'Search Artist 1' }] }),
      searchTrack(3, { duration_seconds: 20 }),              // too short
      ...searchOf(30).slice(3),
    ];

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.equal(tracks.length, SIZE, 'the merged pool still fills exactly `size`');
    const ids = tracks.map((t) => t.videoId);
    assert.equal(new Set(ids).size, ids.length, 'no duplicate videoId survives the merge');
    const artists = tracks.map((t) => t.artist);
    assert.equal(new Set(artists).size, artists.length, 'maxArtistTracks=1 still holds across both sources');
    assert.ok(!ids.includes('srch0000003'), 'an ineligible search track is still rejected');
    for (let i = 1; i < tracks.length; i++) {
      assert.notEqual(tracks[i].artist, tracks[i - 1].artist, 'declustering still separates equal artists');
    }
  });

  // --------------------------------------------------------------- L9 [property]

  /**
   * L9 — property: across generated (nativeDelay, searchCount) pairs the wait is
   * bounded by min(nativeDelay-path, budget) whenever search can stand alone, and the
   * playlist is never empty while any source can supply eligible tracks.
   */
  test('L9 property: the wait tracks min(native path, budget) and the playlist is never needlessly empty', async () => {
    const cases = [];
    for (const delay of [0, 40, 200]) {
      for (const searchCount of [30, 2]) cases.push({ delay, searchCount });
    }

    for (const { delay, searchCount } of cases) {
      const svc = freshService();
      shelfHook = () => sleep(delay).then(() => Array.from({ length: 24 }, (_, i) => container(i)));
      albumHook = async (id) => { await sleep(delay); return albumDetailFor(id); };
      searchHook = () => searchOf(searchCount);

      const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));
      const nativePath = delay * 4;
      const canStandAlone = searchCount * 3 >= SIZE;
      const label = `delay=${delay} searchCount=${searchCount}`;

      assert.ok(tracks.length > 0, `${label}: a usable source must never yield an empty playlist`);

      if (canStandAlone) {
        // Never wait materially longer than the smaller of the two.
        const ceiling = Math.min(nativePath, BUDGET_MS) + 150;
        assert.ok(ms <= ceiling, `${label}: waited ${ms.toFixed(0)}ms, ceiling ${ceiling}ms`);
      } else {
        assert.ok(ms >= nativePath * 0.5 - 1,
          `${label}: a thin search pool must wait for the native feed, waited only ${ms.toFixed(0)}ms`);
      }

      if (nativePath <= BUDGET_MS) {
        assert.ok(tracks.filter((t) => t.videoId.startsWith('nat')).length > 0,
          `${label}: a native feed inside the budget must contribute`);
      }
      assert.deepEqual(unhandled, [], `${label}: no unhandled rejection`);
    }

    assert.equal(cases.length, 6, 'the generated corpus must actually run');
  });

  // ----------------------------------------------------------------- L10 [state]

  /**
   * L10 — regression test for the harness itself.
   *
   * Lookups abandoned by the budget outlive the test that started them. With counters
   * shared across tests (one module-level array reassigned in beforeEach) those late
   * writes land in the NEXT test's array: they inflate its call count, duplicate its
   * browseIds, and merge two bounded pools into its "peak concurrency" — producing
   * false failures rather than real ones.
   *
   * This asserts the isolation property directly: after a second install the first
   * recorder keeps receiving its own late writes while the second stays untouched.
   */
  test("L10 lookups abandoned by the budget cannot pollute a later test's measurements", async () => {
    const svc = freshService();
    const first = rec;

    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => searchOf(30);

    await svc.generateCuratedPlaylist('fresh_releases');
    const firstAtReturn = first.albumCalls.length;

    // Stand in for the next test starting while the abandoned work is still running.
    const second = installProvider();
    assert.notEqual(second, first, 'each install must mint its own recorder');

    await sleep(FULL_NATIVE_MS * 2);

    assert.ok(first.albumCalls.length > firstAtReturn,
      'precondition: the abandoned lookups really did complete after the early return');
    assert.equal(second.albumCalls.length, 0,
      "a later test must observe NONE of the previous test's abandoned lookups");
    assert.equal(second.albumPeak, 0,
      "a later test's peak concurrency must not include the previous test's pool");
    assert.equal(second.shelfCalls, 0, 'a later test must not inherit shelf calls either');
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });
});
