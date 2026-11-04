'use strict';

/**
 * newReleasesAdversarial.test.js — ADVERSARIAL suite for the hybrid
 * `fresh_releases` candidate source: YouTube Music's native New Releases shelf
 * merged with (never replacing) the three existing search queries.
 *
 * Written from the stated acceptance criteria and the public interface only.
 * Every expected value is derived from the spec, from the real `fresh_releases`
 * definition in PlaylistDefinitions.js, or from the CanonicalTrack contract in
 * trackModel.js. Nothing here was calibrated by observing the implementation.
 *
 * Cases (label — acceptance criteria — critical path):
 *   N01 [boundary]    exported bounds are the declared ones            AC4 AC5 AC8 AC9
 *   N02 [input]       isValidAlbumBrowseId accepts only MPRE ids       AC7
 *   N03 [boundary]    selectFreshContainers vs a hand-computed oracle  AC4 AC6 AC7  P1
 *   N04 [property]    selectFreshContainers fuzz invariants            AC4 AC6 AC7  P1
 *   N05 [property]    mapWithConcurrency fuzz: order/once/peak         AC5          P4
 *   N06 [input]       albumTracksToCanonical metadata contract         AC10         P5
 *   N07 [input]       albumTracksToCanonical malformed input           AC11         P5
 *   N08 [input]       feed survives null/garbage album payloads        AC11         P5
 *   N09 [concurrency] peak simultaneous album lookups                  AC5 AC8      P4
 *   N10 [state]       shelf fetched once per refresh, calls coalesce   AC9          P3
 *   N11 [state]       album cache TTL boundaries, never fetched twice  AC8 AC9      P3
 *   N12 [error]       shelf throws / [] / non-array -> search only     AC2          P2
 *   N13 [state]       a broken shelf never destroys the snapshot       AC3          P2
 *   N14 [happy]       both sources in the pool, 3 queries still run    AC1 AC4 AC6 AC7 AC9  P1
 *   N15 [regression]  native tracks obey fresh_releases eligibility    AC12
 *   N16 [regression]  maxArtistTracks=1 applies to native tracks       AC12
 *   N17 [boundary]    size=20 caps a native-heavy pool                 AC12
 *   N18 [regression]  old album year rejected, absent year allowed     AC13
 *   N19 [regression]  catalogue + other playlists untouched            AC14         P6
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

const { createTrack, validateTrack } = require('../../src/search/trackModel');
const {
  getAllDefinitions,
  getDefinitionById,
  ARTIST_PLAYLIST_DEFINITIONS,
} = require('../../src/discovery/PlaylistDefinitions');

// ---------------------------------------------------------------------------
// Harness — inject a stub provider through the require cache and re-require the
// modules so every test starts from empty module state (same pattern as
// tests/discovery/chartPlaylistIngestion.test.js).
// ---------------------------------------------------------------------------

let shelfHook;        // () => container[] | throws
let albumHook;        // (browseId) => albumDetail | null | throws
let searchHook;       // (query) => CanonicalTrack[]
let chartHook;        // () => CanonicalTrack[]

let shelfCalls;       // how many times the shelf was requested upstream
let albumLookups;     // browseIds sent to getAlbumDetail, in call order
let searchQueries;    // queries sent to searchSongs, in call order
let albumInFlight;    // currently running getAlbumDetail calls
let albumPeak;        // max simultaneous getAlbumDetail calls

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function installProvider() {
  for (const k of Object.keys(require.cache)) {
    if (k === PROVIDER || k === FEED_SERVICE || k === SERVICE) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      getNewReleaseShelf: async () => {
        shelfCalls++;
        return shelfHook();
      },
      getAlbumDetail: async (browseId) => {
        albumLookups.push(browseId);
        albumInFlight++;
        if (albumInFlight > albumPeak) albumPeak = albumInFlight;
        try {
          return await albumHook(browseId);
        } finally {
          albumInFlight--;
        }
      },
      searchSongs: async (query) => {
        searchQueries.push(query);
        return searchHook(query);
      },
      getChartTracks: async () => chartHook(),
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
      searchMetrics: {},
    },
  };
}

/** Fresh feed service module with the stub provider underneath. */
function freshFeed() {
  installProvider();
  return require(FEED_SERVICE);
}

/** Fresh PlaylistService (plus feed service) with the stub provider underneath. */
function freshPlaylistService() {
  installProvider();
  const mod = require(SERVICE);
  if (mod && typeof mod.generateCuratedPlaylist === 'function') return mod;
  if (typeof mod === 'function') return new mod();
  if (mod && typeof mod.PlaylistService === 'function') return new mod.PlaylistService();
  if (mod && mod.default && typeof mod.default.generateCuratedPlaylist === 'function') return mod.default;
  throw new Error('PlaylistService exposes no generateCuratedPlaylist()');
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

// ---------------------------------------------------------------------------
// Upstream-shaped fixtures (shapes taken verbatim from the spec)
// ---------------------------------------------------------------------------

/** Mirrors the real upstream album browseId shape: 'MPREb_9nlmNtUXfvdNM' (19 chars). */
function albumId(tag, i) {
  return (`MPREb_${tag}${String(i).padStart(3, '0')}zzzzzzzzzzzzzzz`).slice(0, 19);
}

function makeContainer({ browseId, title, type = 'Single', artist, thumbnailUrl = 'https://img/a.jpg', year = '' }) {
  const c = {
    audioPlaylistId: 'OLAK5uy_x',
    title,
    type,
    artist,
    artists: [{ name: artist, id: `UC_${artist.replace(/\s+/g, '')}` }],
    year,
    thumbnailUrl,
    isExplicit: false,
  };
  if (browseId !== undefined) c.browseId = browseId;
  return c;
}

function makeAlbumTrack({ videoId, title, artistNames, album, durationSeconds = 200 }) {
  const mins = Math.floor(durationSeconds / 60);
  const secs = String(durationSeconds % 60).padStart(2, '0');
  return {
    videoId,
    title,
    artists: artistNames.map((n) => ({ name: n, id: `UC_${n.replace(/\s+/g, '')}` })),
    album,                       // a STRING upstream, not an object
    duration: `${mins}:${secs}`,
    duration_seconds: durationSeconds,
    thumbnails: null,            // album tracks carry NULL artwork upstream
    isAvailable: true,
    isExplicit: false,
  };
}

function makeAlbumDetail({ browseId, title, year = '2026', thumbnailUrl = 'https://img/a.jpg', tracks }) {
  return {
    id: browseId,
    title,
    artist: '',
    year,
    thumbnailUrl,
    description: '',
    tracks,
  };
}

/** A search-sourced CanonicalTrack with a unique artist and a year-free album name. */
function searchTrack(i) {
  const artist = `Search Artist ${String(i).padStart(2, '0')}`;
  return createTrack({
    videoId: `srchVid${String(i).padStart(4, '0')}`,
    title: `Search Song ${String(i).padStart(2, '0')}`,
    artist,
    artists: [{ name: artist, id: `UC_s${i}` }],
    album: 'Search Album',
    year: '2026',
    duration_seconds: 200,
    thumbnails: [{ url: `https://img/s${i}.jpg`, width: 480, height: 360 }],
  });
}

function searchPool(n, offset = 0) {
  return Array.from({ length: n }, (_, i) => searchTrack(i + 1 + offset));
}

/**
 * n shelf containers, each a Single by a distinct artist holding one clean track.
 * Album names deliberately contain no 4-digit year so the only year signal is the
 * album-level `year` field.
 */
function nativeFixture(n, { year = '2026', startIndex = 1, tracksPerAlbum = 1, shareArtistWithinAlbum = true } = {}) {
  const containers = [];
  const details = new Map();
  for (let k = 0; k < n; k++) {
    const i = startIndex + k;
    const artist = `Native Artist ${String(i).padStart(2, '0')}`;
    const browseId = albumId('nat', i);
    const title = `Native Album ${String(i).padStart(2, '0')}`;
    containers.push(makeContainer({ browseId, title, type: k % 2 === 0 ? 'Single' : 'EP', artist }));
    const tracks = [];
    for (let t = 1; t <= tracksPerAlbum; t++) {
      const trackArtist = shareArtistWithinAlbum ? artist : `${artist} Side ${t}`;
      tracks.push(makeAlbumTrack({
        videoId: `natVid${String(i).padStart(3, '0')}${t}`,
        title: `Native Song ${String(i).padStart(2, '0')}-${t}`,
        artistNames: [trackArtist],
        album: title,
      }));
    }
    details.set(browseId, makeAlbumDetail({ browseId, title, year, tracks }));
  }
  return { containers, details, hook: async (id) => details.get(id) || null };
}

function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Primary artist of a container — both fields are kept consistent in fixtures. */
function primaryArtistOf(c) {
  if (Array.isArray(c.artists) && c.artists[0] && c.artists[0].name) return c.artists[0].name;
  return String(c.artist || '').split(',')[0].trim();
}

const FRESH = 'fresh_releases';

beforeEach(() => {
  shelfCalls = 0;
  albumLookups = [];
  searchQueries = [];
  albumInFlight = 0;
  albumPeak = 0;
  shelfHook = () => [];
  albumHook = async () => null;
  searchHook = () => [];
  chartHook = () => [];
});

describe('ADVERSARIAL — native New Releases shelf as an additional fresh_releases source', () => {

  // -------------------------------------------------------------- N01 [boundary]

  /** N01 — AC4/AC5/AC8/AC9: the declared bounds must be the exported ones. */
  test('N01 exported bounds equal the declared limits', () => {
    const feed = freshFeed();
    const { FEED_TTL_MS, ALBUM_TTL_MS, SHELF_MAX_CONTAINERS, ALBUM_CONCURRENCY } = feed;

    assert.equal(SHELF_MAX_CONTAINERS, 10, 'AC4: at most 10 album containers per feed refresh');
    assert.equal(ALBUM_CONCURRENCY, 4, 'AC5: at most 4 album-detail lookups in flight');
    assert.equal(ALBUM_TTL_MS, 24 * 60 * 60 * 1000, 'AC8: album detail is cached for 24h');
    assert.equal(FEED_TTL_MS, 6 * 60 * 60 * 1000, 'AC9: the feed TTL is 6h');

    assert.equal(typeof feed.DiscoveryFeedService.getNewReleaseTracks, 'function',
      'AC1: getNewReleaseTracks must be exposed');
    assert.equal(typeof feed.selectFreshContainers, 'function', 'AC7: selectFreshContainers must be exposed');
    assert.equal(typeof feed.albumTracksToCanonical, 'function', 'AC10: albumTracksToCanonical must be exposed');
    assert.equal(typeof feed.mapWithConcurrency, 'function', 'AC5: mapWithConcurrency must be exposed');
    assert.equal(typeof feed.isValidAlbumBrowseId, 'function', 'AC7: isValidAlbumBrowseId must be exposed');
  });

  // ----------------------------------------------------------------- N02 [input]

  /** N02 — AC7: only a browseId of the valid 'MPRE…' form may pass. */
  test('N02 isValidAlbumBrowseId accepts MPRE album ids and rejects everything else', () => {
    const { isValidAlbumBrowseId } = freshFeed();

    for (const good of ['MPREb_xxxxxxxxxx', 'MPREb_9nlmNtUXfvdNM', albumId('nat', 1)]) {
      assert.equal(isValidAlbumBrowseId(good), true,
        `AC7: "${good}" is a valid album browseId and must be accepted`);
    }

    const bad = [
      '', '   ', null, undefined, 12345, 0, true, false, {}, [], NaN,
      'OLAK5uy_abcdefghij',                  // audio playlist id, not an album
      'PL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX',  // playlist id, not an album
      'NOT_AN_ALBUM', 'MPR', 'browseId',
      'XMPREb_xxxxxxxxxx',                   // does not START with MPRE
      'mpreb_xxxxxxxxxx',                    // MPRE prefix is literal
      'MPREb_ bad spaces',
      '../../etc/passwd', 'MPREb_../../etc',
    ];
    for (const b of bad) {
      assert.equal(isValidAlbumBrowseId(b), false,
        `AC7: ${JSON.stringify(b)} is not a valid album browseId and must be rejected`);
    }
  });

  // -------------------------------------------------------------- N03 [boundary]

  /**
   * N03 — AC4/AC6/AC7: container pre-filtering, checked against an expectation
   * computed by hand from the spec. Containers rejected for their type or for an
   * invalid browseId deliberately never share an id or an artist with an accepted
   * one, so the result does not depend on bookkeeping order.
   */
  test('N03 selectFreshContainers drops types, invalid ids, duplicates and anything past 10', () => {
    const { selectFreshContainers, SHELF_MAX_CONTAINERS } = freshFeed();

    const ok = (i, artist, type = 'Single') =>
      makeContainer({ browseId: albumId('ok', i), title: `OK Album ${i}`, type, artist });

    const input = [
      ok(1, 'Artist 01'),                                                              // accept 1
      makeContainer({ browseId: albumId('alb', 1), title: 'Deluxe Reissue', type: 'Album', artist: 'Album Artist 1' }),
      ok(2, 'Artist 02', 'EP'),                                                        // accept 2
      makeContainer({ browseId: 'OLAK5uy_abcdefghij', title: 'Bad Id', artist: 'Bad Artist 1' }),
      makeContainer({ browseId: albumId('ok', 1), title: 'Same Id Again', artist: 'Artist 03' }), // dup browseId
      ok(3, 'Artist 01'),                                                              // dup primary artist
      makeContainer({ title: 'No Id At All', artist: 'Bad Artist 2' }),                 // missing browseId
      ok(4, 'Artist 04', 'EP'),                                                        // accept 3
      null,                                                                            // non-object
      ok(5, 'Artist 05'),                                                              // accept 4
      makeContainer({ browseId: albumId('alb', 2), title: 'Anniversary Edition', type: 'Album', artist: 'Album Artist 2' }),
      ok(6, 'Artist 06'),                                                              // accept 5
      ok(7, 'Artist 07'),                                                              // accept 6
      ok(8, 'Artist 08'),                                                              // accept 7
      ok(9, 'Artist 09', 'EP'),                                                        // accept 8
      ok(10, 'Artist 10'),                                                             // accept 9
      ok(11, 'Artist 11'),                                                             // accept 10
      ok(12, 'Artist 12'),                                                             // past the cap
      ok(13, 'Artist 13', 'EP'),                                                       // past the cap
    ];

    const out = selectFreshContainers(input);
    const ids = out.map((c) => c.browseId);

    assert.deepEqual(
      ids,
      [1, 2, 4, 5, 6, 7, 8, 9, 10, 11].map((i) => albumId('ok', i)),
      'AC4/AC6/AC7: exactly the first ten valid, unique, unique-artist Single/EP containers survive'
    );
    assert.equal(out.length, SHELF_MAX_CONTAINERS, 'AC4: the selection is capped at SHELF_MAX_CONTAINERS');
    assert.ok(!ids.includes(albumId('alb', 1)) && !ids.includes(albumId('alb', 2)),
      'AC6: a container of type Album must never be selected (reissues/deluxe editions live there)');
    assert.ok(!ids.includes(albumId('ok', 3)), 'AC7: a second container by an already-seen artist is dropped');
    assert.ok(!ids.includes(albumId('ok', 12)) && !ids.includes(albumId('ok', 13)),
      'AC4: valid containers past the cap are dropped without spending a lookup');
  });

  // -------------------------------------------------------------- N04 [property]

  /**
   * N04 — AC4/AC6/AC7 property/fuzz: 300 generated container arrays, each checked
   * against the invariants of the filter instead of a fixed expectation.
   */
  test('N04 property: selectFreshContainers invariants hold over 300 generated shelves', () => {
    const { selectFreshContainers, isValidAlbumBrowseId, SHELF_MAX_CONTAINERS } = freshFeed();

    const BAD_IDS = ['OLAK5uy_abcdefghij', '', null, undefined, 12345, 'MPR', 'NOPE',
                     'XMPREb_xxxxxxxxxx', 'mpreb_xxxxxxxxxx', '../../etc/passwd'];
    const rnd = mulberry32(0xC0FFEE);
    let totalSelected = 0;
    let sawOverflow = false;

    for (let trial = 0; trial < 300; trial++) {
      const n = 1 + Math.floor(rnd() * 40);
      const input = [];
      const validPool = [];

      for (let i = 0; i < n; i++) {
        const roll = rnd();
        if (roll < 0.08) {
          input.push(rnd() < 0.5 ? null : (rnd() < 0.5 ? 'not-a-container' : 7));
        } else if (roll < 0.18 && validPool.length) {
          const src = validPool[Math.floor(rnd() * validPool.length)];
          input.push({ ...src, title: `${src.title} (same id)` });                 // duplicate browseId
        } else if (roll < 0.28 && validPool.length) {
          const src = validPool[Math.floor(rnd() * validPool.length)];
          input.push({ ...src, browseId: albumId('cl', i) });                      // duplicate artist
        } else if (roll < 0.45) {
          const wrong = makeContainer({                                        // wrong / missing type
            browseId: albumId('al', i),
            title: `Album ${i}`,
            type: 'Album',
            artist: `Fuzz Album Artist ${i}`,
          });
          const which = rnd();
          if (which < 0.33) wrong.type = 'Playlist';
          else if (which < 0.66) delete wrong.type;
          input.push(wrong);
        } else if (roll < 0.6) {
          input.push(makeContainer({                                               // invalid browseId
            browseId: BAD_IDS[Math.floor(rnd() * BAD_IDS.length)],
            title: `Bad ${i}`,
            type: rnd() < 0.5 ? 'Single' : 'EP',
            artist: `Fuzz Bad Artist ${i}`,
          }));
        } else {
          const c = makeContainer({
            browseId: albumId('v', i),
            title: `Valid ${i}`,
            type: rnd() < 0.5 ? 'Single' : 'EP',
            artist: `Fuzz Artist ${i}`,
          });
          validPool.push(c);
          input.push(c);
        }
      }

      const out = selectFreshContainers(input);
      totalSelected += out.length;
      if (validPool.length > SHELF_MAX_CONTAINERS) sawOverflow = true;

      assert.ok(Array.isArray(out), `trial ${trial}: AC7 must return an array`);
      assert.ok(out.length <= SHELF_MAX_CONTAINERS,
        `trial ${trial}: AC4 selected ${out.length} containers, limit is ${SHELF_MAX_CONTAINERS}`);

      const seenIds = new Set();
      const seenArtists = new Set();
      let cursor = 0;
      for (const c of out) {
        assert.ok(c && typeof c === 'object' && !Array.isArray(c),
          `trial ${trial}: AC7 every selected container must be an object`);
        assert.ok(c.type === 'Single' || c.type === 'EP',
          `trial ${trial}: AC6 selected type "${c.type}" must be Single or EP`);
        assert.equal(isValidAlbumBrowseId(c.browseId), true,
          `trial ${trial}: AC7 selected browseId ${JSON.stringify(c.browseId)} must be valid`);
        assert.equal(seenIds.has(c.browseId), false,
          `trial ${trial}: AC7 browseId ${c.browseId} selected twice`);
        seenIds.add(c.browseId);
        const artist = primaryArtistOf(c);
        assert.equal(seenArtists.has(artist), false,
          `trial ${trial}: AC7 primary artist "${artist}" selected twice`);
        seenArtists.add(artist);

        let found = -1;
        for (let i = cursor; i < input.length; i++) {
          const src = input[i];
          if (src && typeof src === 'object' && src.browseId === c.browseId) { found = i; break; }
        }
        assert.ok(found >= 0,
          `trial ${trial}: AC7 output must be an order-preserving subsequence of the input ` +
          `(${c.browseId} is fabricated or reordered)`);
        cursor = found + 1;
      }
    }

    assert.ok(totalSelected > 0, 'the fuzz corpus must actually produce selections');
    assert.ok(sawOverflow, 'the fuzz corpus must contain shelves with more than 10 eligible containers');
  });

  // -------------------------------------------------------------- N05 [property]

  /**
   * N05 — AC5 property/fuzz: mapWithConcurrency must preserve input order, run
   * every item exactly once, and never exceed its limit while still saturating it.
   */
  test('N05 property: mapWithConcurrency keeps order, runs each item once, respects the limit', async () => {
    const { mapWithConcurrency } = freshFeed();
    const rnd = mulberry32(0xBADBEEF);

    for (let trial = 0; trial < 24; trial++) {
      const n = Math.floor(rnd() * 13);
      const limit = 1 + Math.floor(rnd() * 6);
      const items = Array.from({ length: n }, (_, i) => ({ i, tag: `item-${i}` }));

      let inFlight = 0;
      let peak = 0;
      const processed = [];

      const results = await mapWithConcurrency(items, limit, async (item) => {
        inFlight++;
        if (inFlight > peak) peak = inFlight;
        await sleep(1 + Math.floor(rnd() * 3));
        processed.push(item.i);
        inFlight--;
        return item.i * 2;
      });

      assert.ok(Array.isArray(results), `trial ${trial}: AC5 must resolve to an array`);
      assert.equal(results.length, n, `trial ${trial}: AC5 one result per input item`);
      for (let i = 0; i < n; i++) {
        assert.equal(results[i], i * 2, `trial ${trial}: AC5 results must be in input order at ${i}`);
      }
      assert.equal(processed.length, n, `trial ${trial}: AC5 each item must be processed exactly once`);
      assert.equal(new Set(processed).size, n, `trial ${trial}: AC5 no item may be processed twice`);
      assert.ok(peak <= limit,
        `trial ${trial}: AC5 peak in-flight ${peak} exceeded limit ${limit} (n=${n})`);
      assert.equal(peak, Math.min(n, limit),
        `trial ${trial}: AC5 a bounded pool of ${limit} must saturate with n=${n} (peak was ${peak})`);
    }
  });

  // ----------------------------------------------------------------- N06 [input]

  /** N06 — AC10: metadata the normalizer must keep, derive, inherit or leave empty. */
  test('N06 albumTracksToCanonical keeps real ids/titles/durations and only inherits what the album has', () => {
    const { albumTracksToCanonical } = freshFeed();

    const browseId = albumId('meta', 1);
    const container = makeContainer({
      browseId, title: 'Thunder EP', type: 'EP', artist: 'Aria',
      thumbnailUrl: 'https://img/a.jpg',
    });
    const albumTrack = makeAlbumTrack({
      videoId: 'abc12345678',
      title: 'Thunder Song',
      artistNames: ['Aria', 'Bolt'],
      album: 'Thunder EP',
      durationSeconds: 200,
    });

    const out = albumTracksToCanonical(
      container,
      makeAlbumDetail({ browseId, title: 'Thunder EP', year: '2026', tracks: [albumTrack] })
    );

    assert.equal(Array.isArray(out), true, 'AC10: must return an array of CanonicalTracks');
    assert.equal(out.length, 1, 'AC10: the one available album track must be normalized');
    const t = out[0];

    assert.equal(validateTrack(t), true, 'AC10: the result must satisfy the CanonicalTrack validator');
    assert.equal(t.videoId, 'abc12345678', 'AC10: the real videoId must survive');
    assert.equal(t.id, 'abc12345678', 'AC10: id must mirror the real videoId');
    assert.equal(t.title, 'Thunder Song', 'AC10: the real title must survive');
    assert.equal(t.duration, 200, 'AC10: the real duration (seconds, numeric) must survive');
    assert.deepEqual(t.artists.map((a) => a.name), ['Aria', 'Bolt'], 'AC10: the artists array must survive');
    assert.equal(t.artist, 'Aria, Bolt', "AC10: the flat artist string is the track artists joined with ', '");
    assert.equal(t.album, 'Thunder EP', 'AC10: the album NAME must be preserved');
    assert.equal(t.year, '2026', 'AC10: album-level year is inherited when the album supplies one');
    assert.equal(t.source, 'youtube_music', 'AC10: canonical source');
    assert.ok(String(t.url).includes('abc12345678'), 'AC10: the url must point at the real videoId');
    assert.ok(Array.isArray(t.thumbnails) && t.thumbnails.length > 0,
      'AC10: album artwork must be inherited when the album supplies it');
    assert.equal(t.thumbnails[0].url, 'https://img/a.jpg', 'AC10: inherited artwork must be the album artwork');

    const dateish = Object.keys(t).filter((k) => /release|date/i.test(k));
    assert.deepEqual(dateish, [], `AC10: no release date may ever be attached (found ${dateish.join(', ')})`);
    assert.equal(t.releaseDate, undefined, 'AC10: releaseDate must never exist');

    // An album with no year must not be guessed at.
    for (const [label, album] of [
      ['empty year', makeAlbumDetail({ browseId, title: 'Thunder EP', year: '', tracks: [albumTrack] })],
      ['missing year', (() => {
        const a = makeAlbumDetail({ browseId, title: 'Thunder EP', tracks: [albumTrack] });
        delete a.year;
        return a;
      })()],
    ]) {
      const noYear = albumTracksToCanonical(
        makeContainer({ browseId, title: 'Thunder EP', type: 'EP', artist: 'Aria', year: '' }),
        album
      );
      assert.equal(noYear.length, 1, `AC10 (${label}): the track must still be produced`);
      assert.equal(noYear[0].year, '',
        `AC10 (${label}): with no album year the track year must be '' and never guessed`);
    }

    // Artwork is inherited ONLY when it exists.
    const noArt = albumTracksToCanonical(
      makeContainer({ browseId, title: 'Thunder EP', type: 'EP', artist: 'Aria', thumbnailUrl: '' }),
      makeAlbumDetail({ browseId, title: 'Thunder EP', year: '2026', thumbnailUrl: '', tracks: [albumTrack] })
    );
    assert.equal(noArt.length, 1, 'AC10: an artwork-less album still yields its track');
    assert.deepEqual(noArt[0].thumbnails, [],
      'AC10: artwork must only be inherited when the album actually supplies it');
  });

  // ----------------------------------------------------------------- N07 [input]

  /** N07 — AC11: malformed album payloads and tracks are rejected safely, never thrown. */
  test('N07 albumTracksToCanonical never throws on malformed input and emits nothing unusable', () => {
    const { albumTracksToCanonical } = freshFeed();

    const browseId = albumId('bad', 1);
    const container = makeContainer({ browseId, title: 'Broken EP', type: 'EP', artist: 'Null Artist' });
    const good = makeAlbumTrack({
      videoId: 'goodVid0001', title: 'Still Good', artistNames: ['Null Artist'], album: 'Broken EP',
    });

    const emptyCases = [
      ['null album payload', null],
      ['undefined album payload', undefined],
      ['tracks: null', { id: browseId, title: 'Broken EP', year: '2026', tracks: null }],
      ['tracks: string', { id: browseId, title: 'Broken EP', year: '2026', tracks: 'nope' }],
      ['tracks: object', { id: browseId, title: 'Broken EP', year: '2026', tracks: {} }],
      ['tracks: number', { id: browseId, title: 'Broken EP', year: '2026', tracks: 3 }],
      ['no tracks key', { id: browseId, title: 'Broken EP', year: '2026' }],
      ['album is a string', 'not-an-album'],
      ['album is a number', 42],
      ['album is an array', []],
    ];
    for (const [label, album] of emptyCases) {
      let out;
      assert.doesNotThrow(() => { out = albumTracksToCanonical(container, album); },
        `AC11 (${label}): must never throw`);
      assert.ok(Array.isArray(out), `AC11 (${label}): must return an array`);
      assert.equal(out.length, 0, `AC11 (${label}): nothing can be derived, so nothing may be emitted`);
    }

    for (const [label, badContainer] of [['null container', null], ['undefined container', undefined],
                                         ['string container', 'nope'], ['array container', []]]) {
      let out;
      assert.doesNotThrow(() => {
        out = albumTracksToCanonical(badContainer,
          makeAlbumDetail({ browseId, title: 'Broken EP', year: '2026', tracks: [good] }));
      }, `AC11 (${label}): must never throw`);
      assert.ok(Array.isArray(out), `AC11 (${label}): must return an array`);
    }

    // Per-track rejection: only the one well-formed, available track may survive.
    const mixed = albumTracksToCanonical(container, makeAlbumDetail({
      browseId, title: 'Broken EP', year: '2026',
      tracks: [
        null,
        undefined,
        42,
        'a string track',
        [],
        {},                                                                  // no videoId
        { videoId: '', title: 'Empty Id' },                                  // empty videoId
        { videoId: 'noTitle0001' },                                          // missing title
        { videoId: 'emptyTtl001', title: '' },                               // empty title
        { videoId: 'emptyTtl002', title: '   ' },                            // whitespace title
        { videoId: 'unavail0001', title: 'Gone', isAvailable: false,
          artists: [{ name: 'Null Artist' }], duration_seconds: 200 },       // unavailable
        good,
      ],
    }));

    assert.deepEqual(mixed.map((t) => t.videoId), ['goodVid0001'],
      'AC11: every malformed or unavailable album track must be dropped, the good one kept');
    for (const t of mixed) {
      assert.equal(validateTrack(t), true, 'AC11: no unusable track may be emitted');
    }
  });

  // ----------------------------------------------------------------- N08 [input]

  /** N08 — AC11: one broken album payload must not poison the whole native feed. */
  test('N08 the feed survives null, non-array and half-broken album payloads', async () => {
    await withClock(1_770_000_000_000, async () => {
      const ids = ['a', 'b', 'c', 'd', 'e'].map((t, i) => albumId(`p${t}`, i + 1));
      const containers = ids.map((browseId, i) => makeContainer({
        browseId, title: `Payload Album ${i + 1}`, type: i % 2 ? 'EP' : 'Single',
        artist: `Payload Artist ${i + 1}`,
      }));

      shelfHook = () => containers;
      albumHook = async (id) => {
        if (id === ids[0]) {
          return makeAlbumDetail({ browseId: ids[0], title: 'Payload Album 1', tracks: [
            makeAlbumTrack({ videoId: 'payVid00001', title: 'Payload One', artistNames: ['Payload Artist 1'], album: 'Payload Album 1' }),
          ] });
        }
        if (id === ids[1]) return null;                                   // null album payload
        if (id === ids[2]) return { id: ids[2], title: 'X', tracks: 'not-an-array' };
        if (id === ids[3]) {
          return makeAlbumDetail({ browseId: ids[3], title: 'Payload Album 4', tracks: [
            makeAlbumTrack({ videoId: 'payVid00004', title: 'Payload Four', artistNames: ['Payload Artist 4'], album: 'Payload Album 4' }),
          ] });
        }
        return makeAlbumDetail({ browseId: ids[4], title: 'Payload Album 5', tracks: [
          null, 42, {}, { videoId: 'payBad00001' }, { videoId: 'payBad00002', title: '' },
          { videoId: 'payBad00003', title: 'Nope', isAvailable: false },
          makeAlbumTrack({ videoId: 'payVid00005', title: 'Payload Five', artistNames: ['Payload Artist 5'], album: 'Payload Album 5' }),
        ] });
      };

      const { DiscoveryFeedService } = freshFeed();
      const out = await DiscoveryFeedService.getNewReleaseTracks();

      assert.ok(Array.isArray(out), 'AC11: getNewReleaseTracks must always resolve to an array');
      assert.deepEqual(out.map((t) => t.videoId).sort(), ['payVid00001', 'payVid00004', 'payVid00005'],
        'AC11: broken payloads contribute nothing while healthy albums still contribute');
      for (const t of out) {
        assert.ok(t.videoId && typeof t.videoId === 'string', 'AC11: no track without a videoId may be emitted');
        assert.ok(t.title && t.title.trim().length > 0, 'AC11: no track without a title may be emitted');
      }
    });
  });

  // ------------------------------------------------------------ N09 [concurrency]

  /** N09 — AC5/AC8: measured peak simultaneous album lookups, and in-flight cleanup. */
  test('N09 at most ALBUM_CONCURRENCY album lookups run at the same time', async () => {
    await withClock(1_770_000_000_000, async () => {
      const fx = nativeFixture(10);
      const feed = freshFeed();
      const { DiscoveryFeedService, ALBUM_CONCURRENCY, _inFlightAlbums, _inFlightFeeds } = feed;

      const inFlightMapSizes = [];
      shelfHook = () => fx.containers;
      albumHook = async (id) => {
        await sleep(12);                      // a real await, so overlap is possible
        inFlightMapSizes.push(_inFlightAlbums.size);
        return fx.details.get(id) || null;
      };

      const out = await DiscoveryFeedService.getNewReleaseTracks();

      assert.equal(albumLookups.length, 10, 'AC4: all ten selected containers are resolved');
      assert.equal(out.length, 10, 'each resolved album contributes its one track');
      assert.ok(albumPeak <= ALBUM_CONCURRENCY,
        `AC5: peak simultaneous album lookups was ${albumPeak}, limit is ${ALBUM_CONCURRENCY}`);
      assert.ok(albumPeak >= 2,
        `AC5: album lookups must run through the bounded pool (ALBUM_CONCURRENCY=${ALBUM_CONCURRENCY}), ` +
        `but the measured peak was ${albumPeak} — they ran serially`);
      assert.ok(Math.max(0, ...inFlightMapSizes) >= 1,
        'AC8: the in-flight album map must actually register requests so concurrent ones coalesce');
      assert.equal(_inFlightAlbums.size, 0, 'AC8: the in-flight album map must be cleaned up afterwards');
      assert.equal(_inFlightFeeds.size, 0, 'AC9: the in-flight feed map must be cleaned up afterwards');
    });
  });

  // ----------------------------------------------------------------- N10 [state]

  /** N10 — AC9: the shelf is fetched once per refresh, concurrent callers coalesce. */
  test('N10 ten concurrent getNewReleaseTracks calls cause exactly one shelf fetch', async () => {
    await withClock(1_770_000_000_000, async () => {
      const fx = nativeFixture(10);
      shelfHook = () => fx.containers;
      albumHook = async (id) => { await sleep(5); return fx.details.get(id) || null; };

      const { DiscoveryFeedService, _inFlightFeeds } = freshFeed();

      const results = await Promise.all(Array.from({ length: 10 }, () => DiscoveryFeedService.getNewReleaseTracks()));

      assert.equal(shelfCalls, 1, 'AC9: ten concurrent calls must share a single shelf fetch');
      assert.equal(albumLookups.length, 10,
        `AC8: each album may only be fetched once per refresh (saw ${albumLookups.length} lookups)`);
      assert.equal(new Set(albumLookups).size, albumLookups.length, 'AC8: no browseId may be fetched twice');
      const first = results[0].map((t) => t.videoId);
      for (const r of results) {
        assert.deepEqual(r.map((t) => t.videoId), first, 'AC9: every coalesced caller gets the same feed');
      }
      assert.equal(_inFlightFeeds.size, 0, 'AC9: the in-flight feed map must be cleaned up');

      // A later call inside the TTL must not re-fetch either.
      await DiscoveryFeedService.getNewReleaseTracks();
      assert.equal(shelfCalls, 1, 'AC9: a warm call inside the feed TTL must not re-fetch the shelf');
      assert.equal(albumLookups.length, 10, 'AC8: a warm call must not re-fetch any album');
    });
  });

  // ----------------------------------------------------------------- N11 [state]

  /** N11 — AC8/AC9: album detail is cached for 24h across feed refreshes, then expires. */
  test('N11 album detail is reused across feed refreshes until its 24h TTL expires', async () => {
    const fx = nativeFixture(10);
    const feed0 = freshFeed();
    const { FEED_TTL_MS, ALBUM_TTL_MS } = feed0;
    assert.ok(ALBUM_TTL_MS > 3 * FEED_TTL_MS, 'spec anchor: 24h album TTL outlives the 6h feed TTL');

    await withClock(1_770_000_000_000, async ({ advance }) => {
      shelfHook = () => fx.containers;
      albumHook = async (id) => fx.details.get(id) || null;

      const { DiscoveryFeedService } = freshFeed();

      await DiscoveryFeedService.getNewReleaseTracks();
      assert.equal(shelfCalls, 1, 'AC9: cold call fetches the shelf');
      assert.equal(albumLookups.length, 10, 'AC4: ten album lookups on a cold refresh');

      // Feed TTL expires -> shelf re-fetched, albums still warm.
      advance(FEED_TTL_MS + 1);
      await DiscoveryFeedService.getNewReleaseTracks();
      assert.equal(shelfCalls, 2, 'AC9: past the 6h feed TTL the shelf must be fetched again');
      assert.equal(albumLookups.length, 10,
        `AC8: the album cache must still serve inside its 24h TTL (saw ${albumLookups.length} lookups)`);

      // 1 ms before the album TTL: still cached, even though the feed expired again.
      advance(ALBUM_TTL_MS - 1 - (FEED_TTL_MS + 1));
      await DiscoveryFeedService.getNewReleaseTracks();
      assert.equal(shelfCalls, 3, 'AC9: the feed TTL expired again, so the shelf is re-fetched');
      assert.equal(albumLookups.length, 10,
        'AC8 boundary: 1 ms before ALBUM_TTL_MS the album must still come from cache');

      // Past the album TTL: every album is fetched exactly once more.
      advance(FEED_TTL_MS + 2);
      await DiscoveryFeedService.getNewReleaseTracks();
      assert.equal(shelfCalls, 4, 'AC9: the shelf is re-fetched once more');
      assert.equal(albumLookups.length, 20,
        `AC8: past ALBUM_TTL_MS each album is fetched once more (saw ${albumLookups.length})`);
      assert.equal(new Set(albumLookups.slice(10)).size, 10, 'AC8: still no duplicate fetch within a refresh');
    });
  });

  // ----------------------------------------------------------------- N12 [error]

  /** N12 — AC2: a broken shelf degrades to the search-only pool, never to nothing. */
  test('N12 a shelf that throws, returns [] or returns garbage still yields a search-built playlist', async () => {
    const def = getDefinitionById(FRESH);
    assert.equal(def.size, 20, 'spec anchor: fresh_releases size');

    const failures = [
      ['throws', () => { throw new Error('upstream 500'); }],
      ['returns []', () => []],
      ['returns null', () => null],
      ['returns undefined', () => undefined],
      ['returns a non-array object', () => ({ contents: 'nope' })],
      ['returns a string', () => 'not-a-shelf'],
      ['returns only unusable containers', () => [null, 'x', makeContainer({ browseId: 'OLAK5uy_abcdefghij', title: 'Bad', artist: 'Bad One' })]],
    ];

    for (const [label, hook] of failures) {
      shelfCalls = 0;
      albumLookups = [];
      searchQueries = [];
      shelfHook = hook;
      albumHook = async () => { throw new Error('no album may be fetched in this scenario'); };
      const pool = searchPool(24);
      searchHook = () => pool;

      await withClock(1_770_000_000_000, async () => {
        const feed = freshFeed();
        const native = await feed.DiscoveryFeedService.getNewReleaseTracks();
        assert.ok(Array.isArray(native), `AC2 (${label}): getNewReleaseTracks must never throw`);
        assert.deepEqual(native, [], `AC2 (${label}): a broken shelf yields no native candidates`);

        const svc = freshPlaylistService();
        const out = await svc.generateCuratedPlaylist(FRESH);

        assert.equal(out.length, 20,
          `AC2 (${label}): the playlist must still be built from the search candidates alone`);
        assert.ok(out.every((t) => String(t.videoId).startsWith('srchVid')),
          `AC2 (${label}): every surviving track must come from search`);
        assert.ok(searchQueries.length >= 3,
          `AC2 (${label}): all three candidateQueries must still run (saw ${searchQueries.length})`);
        assert.equal(albumLookups.length, 0,
          `AC2 (${label}): a broken shelf must not trigger album lookups`);
      });
    }
  });

  // ----------------------------------------------------------------- N13 [state]

  /** N13 — AC3: a later empty/failed shelf must not destroy the last-known-good playlist. */
  test('N13 an empty or failed shelf never destroys a healthy playlist snapshot', async () => {
    await withClock(1_770_000_000_000, async ({ advance }) => {
      const fx = nativeFixture(10);
      const pool = searchPool(10);
      shelfHook = () => fx.containers;
      albumHook = fx.hook;
      searchHook = () => pool;

      const svc = freshPlaylistService();
      const healthy = await svc.generateCuratedPlaylist(FRESH);
      assert.equal(healthy.length, 20, 'precondition: a healthy hybrid playlist of 20 tracks');
      const healthyIds = healthy.map((t) => t.videoId);

      // Past the 2-day cadence, with every upstream source now broken.
      advance(3 * 24 * 60 * 60 * 1000);
      shelfHook = () => { throw new Error('shelf down'); };
      albumHook = async () => null;
      searchHook = () => [];

      const after = await svc.generateCuratedPlaylist(FRESH);
      assert.equal(after.length, 20, 'AC3: the last-known-good snapshot must be preserved');
      assert.deepEqual(after.map((t) => t.videoId), healthyIds,
        'AC3: a failed shelf must return the snapshot unchanged, never a truncated or empty playlist');

      // Same again with an empty (not failing) shelf.
      advance(3 * 24 * 60 * 60 * 1000);
      shelfHook = () => [];
      const after2 = await svc.generateCuratedPlaylist(FRESH);
      assert.deepEqual(after2.map((t) => t.videoId), healthyIds,
        'AC3: an empty shelf must also leave the snapshot intact');
    });
  });

  // ----------------------------------------------------------------- N14 [happy]

  /**
   * N14 — AC1/AC4/AC6/AC7/AC9 (P1): the pool is the UNION of the shelf and the three
   * search queries. The fixture gives 10 native and 10 search candidates for a
   * playlist of size 20, so a playlist of more than 10 tracks is only possible if
   * both sources were merged.
   */
  test('N14 the candidate pool contains both native-shelf and search candidates', async () => {
    const def = getDefinitionById(FRESH);
    assert.deepEqual(def.candidateQueries,
      ['latest hindi releases', 'new bollywood songs', 'new hindi songs {year}'],
      'spec anchor: the three existing candidateQueries');
    assert.equal(def.size, 20, 'spec anchor: fresh_releases size');

    await withClock(1_770_000_000_000, async () => {
      const fx = nativeFixture(10);                       // the ten that must be resolved
      const overflow = nativeFixture(4, { startIndex: 90 }); // valid, but past the cap
      const rejects = [
        makeContainer({ browseId: albumId('rej', 1), title: 'Deluxe Reissue', type: 'Album', artist: 'Reissue Artist' }),
        makeContainer({ browseId: 'OLAK5uy_abcdefghij', title: 'Bad Id', artist: 'Bad Id Artist' }),
        makeContainer({ browseId: albumId('nat', 1), title: 'Duplicate Id', artist: 'Dup Id Artist' }),
        makeContainer({ browseId: albumId('rej', 2), title: 'Duplicate Artist', artist: 'Native Artist 01' }),
      ];
      const shelf = [...fx.containers, ...rejects, ...overflow.containers];
      const details = new Map([...fx.details, ...overflow.details]);

      shelfHook = () => shelf;
      albumHook = async (id) => details.get(id) || null;
      const pool = searchPool(10);
      searchHook = () => pool;

      const svc = freshPlaylistService();
      const out = await svc.generateCuratedPlaylist(FRESH);
      const ids = out.map((t) => t.videoId);
      const natives = ids.filter((v) => v.startsWith('natVid'));
      const searches = ids.filter((v) => v.startsWith('srchVid'));

      assert.ok(out.length > 10,
        `AC1: with 10 native and 10 search candidates a playlist of ${out.length} proves only one source ` +
        'was used — both must be merged');
      assert.equal(out.length, 20, 'AC1: the merged pool must fill the playlist size of 20');
      assert.equal(natives.length, 10, `AC1: all ten native candidates must be in the pool (saw ${natives.length})`);
      assert.equal(searches.length, 10, `AC1: all ten search candidates must be in the pool (saw ${searches.length})`);

      // All three search queries still run, with {year} resolved.
      const uniqueQueries = [...new Set(searchQueries)];
      assert.ok(uniqueQueries.includes('latest hindi releases'), 'AC1: query 1 must still run');
      assert.ok(uniqueQueries.includes('new bollywood songs'), 'AC1: query 2 must still run');
      assert.ok(uniqueQueries.some((q) => /^new hindi songs (19|20)\d{2}$/.test(q)),
        `AC1: the dynamic {year} query must still run, saw ${JSON.stringify(uniqueQueries)}`);
      assert.ok(!uniqueQueries.some((q) => String(q).includes('{year}')),
        'AC1: the {year} token must be resolved before the query is sent upstream');

      // Bounded, type-filtered, pre-filtered lookups.
      assert.equal(shelfCalls, 1, 'AC9: the shelf is fetched once per refresh');
      assert.ok(albumLookups.length <= 10,
        `AC4: at most 10 album containers may be resolved, saw ${albumLookups.length}`);
      assert.equal(new Set(albumLookups).size, albumLookups.length, 'AC8: no browseId fetched twice');
      assert.ok(!albumLookups.includes(albumId('rej', 1)),
        'AC6: a container of type Album must never be resolved');
      assert.ok(!albumLookups.includes('OLAK5uy_abcdefghij'),
        'AC7: an invalid browseId must be dropped before any lookup');
      assert.ok(!albumLookups.includes(albumId('rej', 2)),
        'AC7: a second container by an already-seen artist must be dropped before any lookup');
      for (let i = 90; i < 94; i++) {
        assert.ok(!albumLookups.includes(albumId('nat', i)),
          `AC4: container ${i} is past the cap and must not be resolved`);
      }
    });
  });

  // ------------------------------------------------------------ N15 [regression]

  /** N15 — AC12: native candidates are not an eligibility bypass. */
  test('N15 native-shelf tracks obey every fresh_releases eligibility rule', async () => {
    const e = getDefinitionById(FRESH).eligibility;
    assert.equal(e.minDurationSeconds, 90, 'spec anchor');
    assert.equal(e.maxDurationSeconds, 480, 'spec anchor');
    assert.equal(e.allowRemix, false, 'spec anchor');
    assert.ok(e.negativeKeywords.includes('retro'), 'spec anchor');
    assert.deepEqual(e.excludedGenres, ['classical', 'devotional', 'lofi'], 'spec anchor');

    await withClock(1_770_000_000_000, async () => {
      const spec = [
        ['keepA', 'Monsoon Rain', 'Nova One', 200],
        ['keepB', 'Neon Streets', 'Nova Two', 240],
        ['dropShort', 'Too Short Song', 'Nova Three', 45],
        ['dropLong', 'Too Long Song', 'Nova Four', 600],
        ['dropRemix', 'Midnight Drive (Remix)', 'Nova Five', 210],
        ['dropRetro', 'Retro Gold Nights', 'Nova Six', 210],
        ['dropLofi', 'Lofi Dreams', 'Nova Seven', 210],
        ['dropClassical', 'Classical Sunrise', 'Nova Eight', 210],
        ['dupNative', 'Crimson Sky', 'Nova Nine', 210],
      ];

      const containers = [];
      const details = new Map();
      spec.forEach(([tag, title, artist, dur], idx) => {
        const browseId = albumId('el', idx + 1);
        const albumName = `${artist} Single`;
        containers.push(makeContainer({ browseId, title: albumName, type: 'Single', artist }));
        details.set(browseId, makeAlbumDetail({
          browseId, title: albumName, year: '2026',
          tracks: [makeAlbumTrack({ videoId: `${tag}Vid`, title, artistNames: [artist], album: albumName, durationSeconds: dur })],
        }));
      });

      shelfHook = () => containers;
      albumHook = async (id) => details.get(id) || null;
      const dupSearch = createTrack({
        videoId: 'dupSearchVid',
        title: 'Crimson Sky (Official Video)',
        artist: 'Nova Nine',
        artists: [{ name: 'Nova Nine', id: 'UC_nine' }],
        album: 'Nova Nine Single',
        year: '2026',
        duration_seconds: 212,
        thumbnails: [{ url: 'https://img/dup.jpg' }],
      });
      const pool = [...searchPool(5), dupSearch];
      searchHook = () => pool;

      const svc = freshPlaylistService();
      const ids = (await svc.generateCuratedPlaylist(FRESH)).map((t) => t.videoId);

      assert.ok(ids.includes('keepAVid'), 'AC12: an eligible native track must survive');
      assert.ok(ids.includes('keepBVid'), 'AC12: an eligible native track must survive');
      assert.ok(!ids.includes('dropShortVid'), 'AC12: below minDurationSeconds=90 must be rejected');
      assert.ok(!ids.includes('dropLongVid'), 'AC12: above maxDurationSeconds=480 must be rejected');
      assert.ok(!ids.includes('dropRemixVid'), 'AC12: a remix must be rejected when allowRemix is false');
      assert.ok(!ids.includes('dropRetroVid'), "AC12: the negativeKeyword 'retro' must be rejected");
      assert.ok(!ids.includes('dropLofiVid'), 'AC12: lofi must be rejected');
      assert.ok(!ids.includes('dropClassicalVid'), "AC12: the excluded genre 'classical' must be rejected");

      const dupHits = ids.filter((v) => v === 'dupNativeVid' || v === 'dupSearchVid');
      assert.equal(dupHits.length, 1,
        `AC12: a native track and its canonical search twin must collapse to one, saw ${JSON.stringify(dupHits)}`);
    });
  });

  // ------------------------------------------------------------ N16 [regression]

  /** N16 — AC12: maxArtistTracks=1 is enforced across native-shelf tracks too. */
  test('N16 maxArtistTracks=1 holds when one album supplies several tracks by one artist', async () => {
    assert.equal(getDefinitionById(FRESH).eligibility.maxArtistTracks, 1, 'spec anchor');

    await withClock(1_770_000_000_000, async () => {
      const fx = nativeFixture(10, { tracksPerAlbum: 3, shareArtistWithinAlbum: true });
      shelfHook = () => fx.containers;
      albumHook = fx.hook;
      searchHook = () => [];

      const svc = freshPlaylistService();
      const out = await svc.generateCuratedPlaylist(FRESH);

      assert.ok(out.length > 0, 'the native-only pool must still produce a playlist');
      const counts = new Map();
      for (const t of out) {
        const key = String(t.artist || '').toLowerCase().trim();
        counts.set(key, (counts.get(key) || 0) + 1);
      }
      for (const [artist, n] of counts) {
        assert.equal(n, 1, `AC12: artist "${artist}" appears ${n} times, maxArtistTracks is 1`);
      }
      assert.equal(out.length, 10,
        `AC12: 30 native candidates by 10 artists must yield 10 tracks, saw ${out.length}`);
    });
  });

  // -------------------------------------------------------------- N17 [boundary]

  /** N17 — AC12: a native-heavy pool is still capped at the declared size of 20. */
  test('N17 a 40-candidate native pool is capped at size 20', async () => {
    assert.equal(getDefinitionById(FRESH).size, 20, 'spec anchor');

    await withClock(1_770_000_000_000, async () => {
      const fx = nativeFixture(10, { tracksPerAlbum: 4, shareArtistWithinAlbum: false });
      shelfHook = () => fx.containers;
      albumHook = fx.hook;
      searchHook = () => [];

      const svc = freshPlaylistService();
      const out = await svc.generateCuratedPlaylist(FRESH);

      assert.equal(out.length, 20, `AC12: the playlist size of 20 must cap the pool, saw ${out.length}`);
      assert.equal(new Set(out.map((t) => t.videoId)).size, 20, 'AC12: no duplicate videoId may be selected');
      assert.ok(out.every((t) => String(t.videoId).startsWith('natVid')),
        'the native-only pool may only yield native tracks');
    });
  });

  // ------------------------------------------------------------ N18 [regression]

  /** N18 — AC13: an old album year is rejected; an absent album year is still allowed. */
  test('N18 a reissue dated before eraAfterYear is rejected while a year-less album is allowed', async () => {
    assert.equal(getDefinitionById(FRESH).eligibility.eraAfterYear, 2025, 'spec anchor');

    await withClock(1_770_000_000_000, async () => {
      const make = (tag, artist, year) => {
        const browseId = albumId(tag, 1);
        const albumName = `${artist} Single`;
        return {
          container: makeContainer({ browseId, title: albumName, type: 'Single', artist, year: '' }),
          detail: makeAlbumDetail({
            browseId, title: albumName, year,
            tracks: [makeAlbumTrack({ videoId: `${tag}Vid00001`, title: `${tag} Song`, artistNames: [artist], album: albumName })],
          }),
        };
      };

      const reissue = make('reissue', 'Vintage One', '2019');
      const noYear = make('noyear', 'Unknown Year Two', '');
      const fresh = make('freshy', 'Brand New Three', '2026');

      shelfHook = () => [reissue.container, noYear.container, fresh.container];
      const details = new Map([
        [reissue.container.browseId, reissue.detail],
        [noYear.container.browseId, noYear.detail],
        [fresh.container.browseId, fresh.detail],
      ]);
      albumHook = async (id) => details.get(id) || null;
      searchHook = () => searchPool(6);

      const svc = freshPlaylistService();
      const ids = (await svc.generateCuratedPlaylist(FRESH)).map((t) => t.videoId);

      assert.ok(!ids.includes('reissueVid00001'),
        'AC13: an album year older than eraAfterYear=2025 must be rejected, not presented as new');
      assert.ok(ids.includes('freshyVid00001'), 'AC13: a current-year album must be allowed');
      assert.ok(ids.includes('noyearVid00001'),
        'AC13: the era gate must only fire on a year that is actually present — a year-less album stays eligible');
    });
  });

  // ------------------------------------------------------------ N19 [regression]

  /** N19 — AC14 (P6): the catalogue and every other playlist are untouched. */
  test('N19 catalogue ids/names/order are unchanged and other playlists never touch the shelf', async () => {
    const generalDefs = getAllDefinitions();
    assert.equal(generalDefs.length, 22, 'AC14: 22 general curated definitions');
    assert.equal(Object.values(ARTIST_PLAYLIST_DEFINITIONS).length, 8, 'AC14: 8 artist definitions');

    assert.deepEqual(generalDefs.map((d) => d.id), [
      'chill_nights', 'lofi_focus', 'retro_bollywood', 'punjabi_power', 'romantic_hits',
      'workout_energy', 'sufi_vibes', 'trending_now', 'fresh_releases', 'arijit_singh',
      'uncut_bollywood', 'bollywood_2000s', 'golden_bollywood', 'heartbreak_hindi',
      'acoustic_unplugged', 'indie_india', 'desi_hip_hop', 'ghazal_lounge',
      'south_blockbusters', 'global_top_hits', 'global_chill', 'ar_rahman_magic',
    ], 'AC14: definition ids and their order must be unchanged');

    assert.deepEqual(generalDefs.map((d) => d.name), [
      'Chill Nights', 'Lofi Focus', 'Retro Bollywood', 'Punjabi Power', 'Romantic Hits',
      'Workout Energy', 'Sufi Vibes', 'Trending Now', 'Fresh Releases', 'Arijit Singh Hits',
      'Uncut Bollywood', 'Bollywood 2000s', 'Classic Bollywood', 'Broken Strings',
      'Acoustic Sessions', 'Indie India', 'Desi Hip Hop', 'Ghazal Lounge',
      'South Cinema Hits', 'Global Pop', 'Global Chill', 'A.R. Rahman Essentials',
    ], 'AC14: definition names must be unchanged');

    const fresh = getDefinitionById(FRESH);
    assert.equal(fresh.name, 'Fresh Releases', 'AC14');
    assert.equal(fresh.size, 20, 'AC14');
    assert.equal(fresh.updateIntervalDays, 2, 'AC14');
    assert.equal(fresh.artistStrategy, 'search', 'AC14');
    assert.equal(fresh.nativeFeed, 'new_releases', 'AC1: fresh_releases declares the native shelf');

    const chartPool = Array.from({ length: 60 }, (_, i) => {
      const artist = `Chart Artist ${(i % 20) + 1}`;
      return createTrack({
        videoId: `chartVid${String(i).padStart(4, '0')}`,
        title: `Chart Song ${i}`,
        artist,
        artists: [{ name: artist, id: `UC_c${i % 20}` }],
        album: 'Chart Album',
        year: '2026',
        duration_seconds: 200 + (i % 50),
        thumbnails: [{ url: `https://img/c${i}.jpg` }],
      });
    });

    await withClock(1_770_000_000_000, async () => {
      shelfHook = () => { throw new Error('the shelf must not be touched by trending_now'); };
      albumHook = async () => { throw new Error('no album lookup may happen for trending_now'); };
      chartHook = () => chartPool;
      searchHook = () => searchPool(40);

      const svc = freshPlaylistService();
      const trending = await svc.generateCuratedPlaylist('trending_now');
      assert.equal(trending.length, 25, 'AC14: trending_now must still produce 25 tracks');
      assert.equal(shelfCalls, 0, 'AC14: trending_now must never request the New Releases shelf');
      assert.equal(albumLookups.length, 0, 'AC14: trending_now must never resolve album containers');

      const svc2 = freshPlaylistService();
      chartHook = () => chartPool;
      const chill = await svc2.generateCuratedPlaylist('chill_nights');
      assert.equal(chill.length, 25, 'AC14: chill_nights must still produce 25 tracks');
      assert.equal(shelfCalls, 0, 'AC14: a search-only playlist must never request the shelf');
      assert.equal(albumLookups.length, 0, 'AC14: a search-only playlist must never resolve albums');
    });
  });

  // ------------------------------------------------------- N20 [concurrency]

  /**
   * N20 — AC4/AC5, added by the integrator after ablation testing.
   *
   * N09 asserts `albumPeak <= ALBUM_CONCURRENCY`, reading the bound from the module
   * it is testing. An ablation that raised ALBUM_CONCURRENCY to 1000 therefore left
   * N09 passing with a measured peak of 10 — the assertion moves with the constant,
   * so it cannot witness an unbounded-concurrency regression. Only N01's
   * `assert.equal(ALBUM_CONCURRENCY, 4)` pinned the number, and a constant check
   * says nothing about behaviour.
   *
   * This case closes that hole: the bounds are LITERALS here, measured through the
   * real public entry point, so loosening either constant fails this test.
   */
  test('N20 measured album-lookup bounds are 10 and 4 as literals, not as self-reported constants', async () => {
    const { containers, details } = nativeFixture(24);

    shelfHook = () => containers;
    albumHook = async (id) => {
      await sleep(5); // real overlap window, so a broken limiter shows up as a high peak
      return details.get(id) || null;
    };
    searchHook = () => [];

    const { DiscoveryFeedService } = freshFeed();
    const tracks = await DiscoveryFeedService.getNewReleaseTracks();

    assert.equal(shelfCalls, 1, 'AC9: exactly one shelf fetch per refresh');
    assert.ok(albumLookups.length <= 10,
      `AC4: at most 10 album lookups per refresh, measured ${albumLookups.length} from a 24-container shelf`);
    assert.ok(albumPeak <= 4,
      `AC5: at most 4 album lookups in flight at once, measured peak ${albumPeak}`);
    assert.ok(albumPeak >= 2,
      `AC5: lookups must run through the bounded pool, measured peak ${albumPeak} (ran serially)`);
    assert.ok(tracks.length > 0, 'AC1: the bounded feed still yields candidates');
  });
});
