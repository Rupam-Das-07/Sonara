'use strict';

// The enrichment budget is read from config at module load, so it must be set before
// anything requires the config module. node:test runs each test FILE in its own child
// process, so this cannot leak into other suites.
process.env.YTMUSIC_TIMEOUT_MS = '120';

/**
 * freshReleasesUnderfill.test.js — the specific case left unresolved by the latency
 * review: a search pool that LOOKS large but yields too few tracks after selection,
 * combined with native enrichment that stalls.
 *
 * The hazard is that the enrichment budget is an optimisation for "search already has
 * enough", so it must be keyed on how many tracks survive selection — dedup,
 * eligibility, the hard primary-artist cap and contributor fallback — not on how many
 * raw candidates the queries returned. Raw count can overstate usable tracks by an
 * order of magnitude, and an under-filled result is then committed to the snapshot and
 * advances the cadence stamp, so it is served for the whole next refresh window.
 *
 *   U1 [regression] many raw / few eligible / stalled native -> still FILLED, budget not applied
 *   U2 [regression] genuinely empty search pool -> native rescue still works
 *   U3 [latency]    selected search CAN fill -> budget still applied (optimisation preserved)
 *   U4 [state]      when neither source can fill, document what is actually persisted
 *   U5 [state]      the full-wait path issues no duplicate upstream work and leaks nothing
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

const { createTrack } = require('../../src/search/trackModel');
const { getDefinitionById } = require('../../src/discovery/PlaylistDefinitions');

const DEF = getDefinitionById('fresh_releases');
const SIZE = DEF.size;                 // 20
const BUDGET_MS = 120;                 // == YTMUSIC_TIMEOUT_MS above
const WAVE_MS = 200;                   // per-call stub delay
const FULL_NATIVE_MS = WAVE_MS * 4;    // shelf + ceil(10/4)=3 album waves

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let shelfHook;
let albumHook;
let searchHook;
let unhandled;

/** Per-install recorder: work abandoned by a budget trip can only write to its own. */
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

const elapsed = async (fn) => {
  const t0 = process.hrtime.bigint();
  const value = await fn();
  return { value, ms: Number(process.hrtime.bigint() - t0) / 1e6 };
};

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** An ELIGIBLE search track with a unique primary artist. */
function goodSearchTrack(i) {
  return createTrack({
    videoId: `good${String(i).padStart(7, '0')}`,
    title: `Good Song ${i}`,
    artist: `Good Artist ${i}`,
    artists: [{ name: `Good Artist ${i}`, id: `UC_g${i}` }],
    album: 'Search Album',
    year: '2026',
    duration_seconds: 200,
    thumbnails: [{ url: `https://img/g${i}.jpg` }],
  });
}

/**
 * A search track that is REJECTED by the real fresh_releases eligibility rules.
 * 30s is below minDurationSeconds (90), taken from the definition, not the code.
 */
function ineligibleSearchTrack(i) {
  return createTrack({
    videoId: `short${String(i).padStart(6, '0')}`,
    title: `Too Short ${i}`,
    artist: `Short Artist ${i}`,
    artists: [{ name: `Short Artist ${i}`, id: `UC_s${i}` }],
    album: 'Search Album',
    year: '2026',
    duration_seconds: 30,
    thumbnails: [{ url: `https://img/s${i}.jpg` }],
  });
}

/** A shelf of n Single containers, each resolving to one track by a distinct artist. */
function nativeShelf(n) {
  const containers = Array.from({ length: n }, (_, i) => ({
    browseId: `MPREb_${String(i).padStart(12, 'x')}`,
    title: `Native Album ${i}`,
    type: 'Single',
    artist: `Native Artist ${i}`,
    artists: [{ name: `Native Artist ${i}`, id: `UC_n${i}` }],
    year: '',
    thumbnailUrl: `https://img/n${i}.jpg`,
    isExplicit: false,
  }));
  const detailFor = (browseId) => {
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
  };
  return { containers, detailFor };
}

const NATIVE = nativeShelf(24);
const stalledShelf = () => sleep(WAVE_MS).then(() => NATIVE.containers);
const stalledAlbum = async (id) => { await sleep(WAVE_MS); return NATIVE.detailFor(id); };

const nativeCount = (tracks) => tracks.filter((t) => t.videoId.startsWith('nat')).length;

beforeEach(() => {
  rec = newRecorder();
  shelfHook = () => [];
  albumHook = async () => null;
  searchHook = () => [];
  unhandled = [];
  process.removeAllListeners('unhandledRejection');
  process.on('unhandledRejection', (e) => unhandled.push(e));
});

describe('UNDER-FILL — selected search tracks, not raw candidates, gate the budget', () => {

  // -------------------------------------------------------------- U1 [regression]

  /**
   * U1 — the reproduction case.
   *
   * 60 raw search candidates (3 queries x 20) but only 14 survive selection, which is
   * short of size 20. Native stalls. Keying the budget on the RAW pool (60 >= 20)
   * abandons native after one budget unit and commits a 14-track playlist; keying it
   * on the SELECTED count (14 < 20) waits for the bounded native path and fills.
   *
   * Spec anchors come from the definition, never from the implementation.
   */
  test('U1 a large-but-ineligible search pool still waits for native and fills the playlist', async () => {
    assert.equal(DEF.size, 20, 'spec anchor: fresh_releases size');
    assert.equal(DEF.eligibility.minDurationSeconds, 90, 'spec anchor: minDurationSeconds');
    assert.equal(DEF.eligibility.maxArtistTracks, 1, 'spec anchor: maxArtistTracks');

    const eligible = 14;
    const perQuery = [
      ...Array.from({ length: eligible }, (_, i) => goodSearchTrack(i + 1)),
      ...Array.from({ length: 46 }, (_, i) => ineligibleSearchTrack(i + 1)),
    ];

    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => perQuery;

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    // Precondition: the raw pool really is large enough to fool a raw-count gate.
    assert.ok(perQuery.length >= SIZE,
      `precondition: the raw pool (${perQuery.length}) must exceed size ${SIZE}`);

    assert.equal(tracks.length, SIZE,
      `an under-filled playlist must not be committed: got ${tracks.length}/${SIZE}`);
    assert.ok(nativeCount(tracks) > 0,
      'native enrichment must have been waited for and merged, not abandoned');
    assert.ok(ms >= FULL_NATIVE_MS * 0.5,
      `the bounded native path must be awaited when selection is short, waited only ${ms.toFixed(0)}ms`);
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });

  // -------------------------------------------------------------- U2 [regression]

  /** U2 — the pre-existing native rescue must not regress. */
  test('U2 a genuinely empty search pool still lets native rescue the playlist', async () => {
    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => [];

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.ok(tracks.length > 0, 'native must still rescue an empty search pool');
    assert.ok(tracks.every((t) => t.videoId.startsWith('nat')), 'every track came from native');
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });

  /** U2b — all queries rejecting is the same situation and must behave the same. */
  test('U2b every search query failing still lets native rescue the playlist', async () => {
    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => { throw new Error('search down'); };

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.ok(tracks.length > 0, 'native must still rescue when every query fails');
    assert.ok(tracks.every((t) => t.videoId.startsWith('nat')), 'every track came from native');
  });

  // ----------------------------------------------------------------- U3 [latency]

  /**
   * U3 — the latency optimisation must survive the stricter gate: when the SELECTED
   * search tracks already fill the playlist, a stalled native feed is still abandoned
   * on the budget rather than awaited in full.
   */
  test('U3 when selected search tracks already fill, a stalled native is still abandoned on the budget', async () => {
    const perQuery = Array.from({ length: 30 }, (_, i) => goodSearchTrack(i + 1));

    const svc = freshService();
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => perQuery;

    const { value: tracks, ms } = await elapsed(() => svc.generateCuratedPlaylist('fresh_releases'));

    assert.equal(tracks.length, SIZE, 'the playlist is filled from search alone');
    assert.equal(nativeCount(tracks), 0, 'a stalled native feed contributes nothing to this refresh');
    assert.ok(ms < FULL_NATIVE_MS * 0.6,
      `the budget must still short-circuit the stalled native path, took ${ms.toFixed(0)}ms`);
    assert.ok(ms >= BUDGET_MS * 0.5, `the budget must actually be awaited, took only ${ms.toFixed(0)}ms`);
  });

  /** U3b — a HEALTHY native feed must still enrich a playlist search could fill alone. */
  test('U3b a healthy native feed still enriches even when search could fill alone', async () => {
    const perQuery = Array.from({ length: 30 }, (_, i) => goodSearchTrack(i + 1));

    const svc = freshService();
    shelfHook = () => NATIVE.containers;
    albumHook = async (id) => NATIVE.detailFor(id);
    searchHook = () => perQuery;

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.equal(tracks.length, SIZE, 'playlist filled');
    assert.ok(nativeCount(tracks) > 0,
      'the stricter gate must not suppress native enrichment in the healthy case');
  });

  // ------------------------------------------------------------------- U4 [state]

  /**
   * U4 — documents what actually happens when NEITHER source can fill: the partial
   * result is committed and advances the cadence stamp, so it is served for the whole
   * refresh window rather than retried on the next request.
   *
   * This is deliberate pre-existing policy, not a budget effect: committing it is what
   * stops a playlist that genuinely cannot fill from re-issuing its upstream queries on
   * every single request. The assertion pins the behaviour so a future change to it is
   * visible rather than silent.
   */
  test('U4 when neither source can fill, the partial result is committed and reused for the window', async () => {
    const perQuery = [
      goodSearchTrack(1),
      goodSearchTrack(2),
      ...Array.from({ length: 40 }, (_, i) => ineligibleSearchTrack(i + 1)),
    ];
    const thin = nativeShelf(3);

    const svc = freshService();
    shelfHook = () => thin.containers;
    albumHook = async (id) => thin.detailFor(id);
    searchHook = () => perQuery;

    const first = await svc.generateCuratedPlaylist('fresh_releases');
    assert.ok(first.length > 0 && first.length < SIZE,
      `precondition: neither source can fill, got ${first.length}/${SIZE}`);

    const shelfAfterFirst = rec.shelfCalls;
    const second = await svc.generateCuratedPlaylist('fresh_releases');

    assert.deepEqual(second.map((t) => t.videoId), first.map((t) => t.videoId),
      'the partial result is served again from the snapshot');
    assert.equal(rec.shelfCalls, shelfAfterFirst,
      'and the cadence stamp was advanced, so no further upstream traffic is issued');
  });

  // ------------------------------------------------------------------- U5 [state]

  /**
   * U5 — the full-wait path must not cost extra upstream work: one shelf call, at most
   * the 10-container cap, concurrency still 4, and nothing left in flight.
   */
  test('U5 the full-wait path issues no duplicate upstream work and leaks no in-flight state', async () => {
    installProvider();
    const own = rec;
    const feed = require(FEED_SERVICE);
    const svc = require(SERVICE);

    const perQuery = [
      ...Array.from({ length: 5 }, (_, i) => goodSearchTrack(i + 1)),
      ...Array.from({ length: 40 }, (_, i) => ineligibleSearchTrack(i + 1)),
    ];
    shelfHook = stalledShelf;
    albumHook = stalledAlbum;
    searchHook = () => perQuery;

    const tracks = await svc.generateCuratedPlaylist('fresh_releases');

    assert.ok(tracks.length > 5, 'native was merged into the short search selection');
    assert.equal(own.shelfCalls, 1, 'exactly one shelf fetch for the whole generation');
    assert.ok(own.albumCalls.length <= 10,
      `the 10-container cap still holds, saw ${own.albumCalls.length}`);
    assert.equal(new Set(own.albumCalls).size, own.albumCalls.length, 'no album fetched twice');
    assert.ok(own.albumPeak <= 4, `concurrency still bounded at 4, measured ${own.albumPeak}`);
    assert.equal(feed._inFlightFeeds.size, 0, 'no feed request left in flight');
    assert.equal(feed._inFlightAlbums.size, 0, 'no album request left in flight');
    assert.deepEqual(unhandled, [], 'no unhandled rejection');
  });
});
