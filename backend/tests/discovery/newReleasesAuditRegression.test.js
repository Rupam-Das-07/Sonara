'use strict';

/**
 * newReleasesAuditRegression.test.js — regression tests from the adversarial audit of
 * the hybrid fresh_releases feeder.
 *
 * Each case here reproduces a defect that was CONFIRMED against the implementation
 * before it was repaired, so each one fails if its fix is reverted.
 *
 *   R1 [state]    a zero-track album response must not be retained in the album cache
 *   R2 [state]    ...so a transient empty album cannot suppress the native source for 24h
 *   R3 [input]    the artist fallback must use container.artists[0].name, not only .artist
 *   R4 [input]    a track with any usable artist name must never become 'Unknown Artist'
 *   R5 [property] fuzz over artist-metadata shapes: 'Unknown Artist' only when no name exists
 *
 * Findings referenced:
 *   F1 (P2) getAlbumDetailCached cached `{tracks: []}` for 24h, contradicting this
 *           module's own documented guarantee 4 ("Empty / non-OK results are NEVER
 *           cached to prevent sticky degradation") and leaving the native source dead
 *           for a full day after one partial upstream response.
 *   F2 (P3) albumTracksToCanonical read only `container.artist` for its fallback while
 *           selectFreshContainers, in the same file, reads `container.artists[0].name`
 *           first. A container shaped like upstream's own parse_album output (which has
 *           `artists` but no flat `artist`) therefore produced 'Unknown Artist'.
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const FEED_SERVICE = require.resolve('../../src/discovery/discoveryFeedService');
const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

// ---------------------------------------------------------------------------
// Harness — stub the provider through the require cache (same pattern as the
// other discovery suites) so each test starts from empty module state.
// ---------------------------------------------------------------------------

let shelfHook;
let albumHook;
let searchHook;
let shelfCalls;
let albumLookups;

function installProvider() {
  for (const k of Object.keys(require.cache)) {
    if (k === PROVIDER || k === FEED_SERVICE || k === SERVICE) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      getNewReleaseShelf: async () => { shelfCalls++; return shelfHook(); },
      getAlbumDetail: async (browseId) => { albumLookups.push(browseId); return albumHook(browseId); },
      searchSongs: async (q) => searchHook(q),
      getChartTracks: async () => [],
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
      searchMetrics: {},
    },
  };
}

function freshFeed() {
  installProvider();
  return require(FEED_SERVICE);
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

const BROWSE_ID = 'MPREb_auditaaaaaaa';

function singleContainer(overrides = {}) {
  return {
    browseId: BROWSE_ID,
    audioPlaylistId: 'OLAK5uy_x',
    title: 'Audit Album',
    type: 'Single',
    artist: 'Audit Artist',
    artists: [{ name: 'Audit Artist', id: 'UC_audit' }],
    year: '',
    thumbnailUrl: 'https://img/audit.jpg',
    isExplicit: false,
    ...overrides,
  };
}

function albumTrack(overrides = {}) {
  return {
    videoId: 'auditVid001',
    title: 'Audit Song',
    artists: [{ name: 'Audit Artist', id: 'UC_audit' }],
    album: 'Audit Album',
    duration: '3:20',
    duration_seconds: 200,
    thumbnails: null,
    isAvailable: true,
    isExplicit: false,
    ...overrides,
  };
}

function albumDetail(tracks, overrides = {}) {
  return {
    id: BROWSE_ID,
    title: 'Audit Album',
    artist: '',
    year: '2026',
    thumbnailUrl: 'https://img/audit.jpg',
    tracks,
    ...overrides,
  };
}

beforeEach(() => {
  shelfCalls = 0;
  albumLookups = [];
  shelfHook = () => [];
  albumHook = async () => null;
  searchHook = () => [];
});

describe('AUDIT REGRESSION — hybrid fresh_releases feeder', () => {

  // ------------------------------------------------------------------ R1 [state]

  /**
   * R1 — F1: an album that resolves to zero tracks is an empty result, and this
   * module guarantees empty results are never cached. Before the fix the detail was
   * cached because `Array.isArray(detail.tracks)` was true for `[]`.
   */
  test('R1 a zero-track album response is not retained in the album cache', async () => {
    await withClock(1_770_000_000_000, async () => {
      const { DiscoveryFeedService, _albumCache } = freshFeed();

      shelfHook = () => [singleContainer()];
      albumHook = async () => albumDetail([]); // HTTP 200, but no usable tracks

      const tracks = await DiscoveryFeedService.getNewReleaseTracks();

      assert.deepEqual(tracks, [], 'an album with no tracks yields no candidates');
      assert.equal(albumLookups.length, 1, 'the album was actually requested');
      assert.equal(_albumCache.size, 0,
        'F1: an empty album result must NOT be cached (module guarantee 4: empty results are never cached)');
    });
  });

  // ------------------------------------------------------------------ R2 [state]

  /**
   * R2 — F1 behavioural consequence: one partial upstream response must not disable
   * the native source until the 24h album TTL expires. Before the fix the cached
   * empty detail was replayed on every later refresh, so the shelf kept being
   * fetched while the feed stayed permanently empty.
   */
  test('R2 a transient zero-track album does not suppress the native source on the next refresh', async () => {
    await withClock(1_770_000_000_000, async ({ advance }) => {
      const { DiscoveryFeedService, FEED_TTL_MS } = freshFeed();

      shelfHook = () => [singleContainer()];
      albumHook = async () => albumDetail([]); // transient glitch

      const first = await DiscoveryFeedService.getNewReleaseTracks();
      assert.deepEqual(first, [], 'the glitched refresh yields nothing');

      // Past the feed TTL the shelf is re-fetched; upstream has recovered by now.
      advance(FEED_TTL_MS + 1);
      albumHook = async () => albumDetail([albumTrack()]);

      const second = await DiscoveryFeedService.getNewReleaseTracks();

      assert.equal(second.length, 1,
        'F1: the recovered album must be picked up on the next refresh, not blocked by a cached empty result');
      assert.equal(second[0].videoId, 'auditVid001', 'the real track survives');
      assert.equal(albumLookups.length, 2, 'the album had to be requested again rather than served from cache');
    });
  });

  // ------------------------------------------------------------------ R3 [input]

  /**
   * R3 — F2: a container carrying only the structured `artists` array (the shape
   * upstream's own parse_album emits) must still yield a real artist name when the
   * album track itself has none.
   */
  test('R3 the artist fallback uses container.artists[0].name when the flat artist field is absent', () => {
    const { albumTracksToCanonical } = freshFeed();

    const container = singleContainer();
    delete container.artist; // upstream parse_album has `artists` but no flat `artist`

    const out = albumTracksToCanonical(container, albumDetail([albumTrack({ artists: null })]));

    assert.equal(out.length, 1, 'the track is still usable');
    assert.equal(out[0].artist, 'Audit Artist',
      "F2: a usable name in container.artists[0].name must not be discarded in favour of 'Unknown Artist'");
  });

  // ------------------------------------------------------------------ R4 [input]

  /**
   * R4 — F2: across the artist-metadata shapes upstream actually produces, a track
   * must never fall back to 'Unknown Artist' while a usable name is present.
   */
  test('R4 no usable artist name is ever replaced by Unknown Artist', () => {
    const { albumTracksToCanonical } = freshFeed();

    const shapes = [
      { label: 'track artists present', trackArtists: [{ name: 'Track Artist' }], container: {}, expect: 'Track Artist' },
      { label: 'track artists null, flat container artist', trackArtists: null, container: {}, expect: 'Audit Artist' },
      { label: 'track artists [], structured container only', trackArtists: [], container: { artist: undefined }, expect: 'Audit Artist' },
      { label: 'track artists nameless entries', trackArtists: [{ id: 'x' }, { name: '   ' }], container: {}, expect: 'Audit Artist' },
      { label: 'multiple track artists joined', trackArtists: [{ name: 'A' }, { name: 'B' }], container: {}, expect: 'A, B' },
    ];

    for (const shape of shapes) {
      const container = singleContainer(shape.container);
      if (shape.container.artist === undefined && 'artist' in shape.container) delete container.artist;

      const out = albumTracksToCanonical(container, albumDetail([albumTrack({ artists: shape.trackArtists })]));

      assert.equal(out.length, 1, `${shape.label}: the track must survive`);
      assert.equal(out[0].artist, shape.expect, `F2: ${shape.label}`);
      assert.notEqual(out[0].artist, 'Unknown Artist', `F2: ${shape.label} had a usable name`);
    }
  });

  // --------------------------------------------------------------- R5 [property]

  /**
   * R5 — F2 property/fuzz: over every combination of the artist-metadata shapes
   * upstream can produce, the canonical artist is 'Unknown Artist' if and only if no
   * usable name exists anywhere. Checked against an independent oracle rather than
   * against the implementation.
   */
  test('R5 property: Unknown Artist appears only when no usable name exists anywhere', () => {
    const { albumTracksToCanonical } = freshFeed();

    const trackArtistShapes = [
      null, undefined, [], [{ id: 'x' }], [{ name: '' }], [{ name: '  ' }],
      [{ name: 'T1' }], [{ name: 'T1' }, { name: 'T2' }],
    ];
    const flatArtistShapes = [undefined, '', '   ', 'Flat Artist'];
    const structuredArtistShapes = [undefined, [], [{ id: 'y' }], [{ name: '' }], [{ name: 'Structured Artist' }]];

    /** Independent oracle: the first usable name, preferring track > flat > structured. */
    const oracle = (trackArtists, flat, structured) => {
      const fromTrack = (Array.isArray(trackArtists) ? trackArtists : [])
        .filter((a) => a && typeof a.name === 'string' && a.name.trim())
        .map((a) => a.name.trim())
        .join(', ');
      if (fromTrack) return fromTrack;
      if (typeof flat === 'string' && flat.trim()) return flat.trim();
      const fromStructured = (Array.isArray(structured) ? structured : [])
        .filter((a) => a && typeof a.name === 'string' && a.name.trim())
        .map((a) => a.name.trim())[0];
      if (fromStructured) return fromStructured;
      return 'Unknown Artist';
    };

    let combinations = 0;
    let unknownCount = 0;

    for (const trackArtists of trackArtistShapes) {
      for (const flat of flatArtistShapes) {
        for (const structured of structuredArtistShapes) {
          combinations++;

          const container = { browseId: BROWSE_ID, title: 'Audit Album', thumbnailUrl: 'https://img/a.jpg' };
          if (flat !== undefined) container.artist = flat;
          if (structured !== undefined) container.artists = structured;

          const out = albumTracksToCanonical(container, albumDetail([albumTrack({ artists: trackArtists })]));

          assert.equal(out.length, 1, 'a track with a valid id and title always survives');
          const expected = oracle(trackArtists, flat, structured);
          assert.equal(out[0].artist, expected,
            `F2: artists=${JSON.stringify(trackArtists)} flat=${JSON.stringify(flat)} structured=${JSON.stringify(structured)}`);

          if (expected === 'Unknown Artist') unknownCount++;
          // No release date may ever be synthesised, whatever the artist metadata.
          assert.equal(out[0].year, '2026', 'the album year is inherited, never invented');
          assert.ok(!('releaseDate' in out[0]) && !('date' in out[0]), 'no release date is ever attached');
        }
      }
    }

    assert.equal(combinations, trackArtistShapes.length * flatArtistShapes.length * structuredArtistShapes.length);
    // Guard against a vacuous pass: the corpus must exercise BOTH outcomes.
    assert.ok(unknownCount > 0, 'the corpus must include genuinely nameless cases');
    assert.ok(unknownCount < combinations, 'the corpus must include cases where a name exists');
  });
});
