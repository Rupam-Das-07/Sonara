'use strict';

/**
 * trackDtoArtists.test.js — the TrackDTO must carry STRUCTURED artist credits.
 *
 * Why this exists
 * ---------------
 * Featured Artists personalization needs to attribute a listen to a VERIFIED artist
 * identity. Before this change the wire format exposed only the flat `artist` display
 * string, so the Android client had to parse a credit like
 * "Hans Zimmer & Benjamin Wallfisch" with separator heuristics — which cannot
 * distinguish a two-artist credit from a single act whose name contains a separator
 * ("AC/DC", "Simon & Garfunkel"). `trackModel.createTrack` already carried an
 * `artists` array internally; `toTrackDTO` simply dropped it.
 *
 * Contract asserted here:
 *   - `artists` is ALWAYS present and ALWAYS an array (never undefined/null).
 *   - Each entry is `{ name, id }` with both fields strings.
 *   - Entries without a usable name are dropped; a missing `id` becomes ''.
 *   - A non-array / absent upstream `artists` yields [].
 *   - The flat `artist` field is unchanged, so existing clients keep working.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const ROUTE = require.resolve('../../src/routes/search');

/** Install a stub provider, then load a FRESH route module bound to it. */
function freshRoute(tracks) {
  for (const k of Object.keys(require.cache)) {
    if (k === PROVIDER || k === ROUTE) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER,
    filename: PROVIDER,
    loaded: true,
    exports: {
      searchSongs: async () => tracks,
      searchMetrics: {
        cacheHits: 0, cacheMisses: 0, upstreamSearches: 0,
        totalTimeMs: 0, lastTimeMs: 0, inFlight: 0, cacheSize: 0, hitRate: 0,
      },
      getChartTracks: async () => [],
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
      getNewReleaseShelf: async () => [],
      getAlbumDetail: async () => null,
    },
  };
  return require(ROUTE);
}

function getHandler(router, routePath) {
  const layer = router.stack.find(
    (l) => l.route && l.route.path === routePath && l.route.methods.get
  );
  if (!layer) throw new Error(`GET ${routePath} not found`);
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

const flush = () => new Promise((resolve) => setImmediate(resolve));

/** Drives GET / with a unique query so the route's response cache cannot interfere. */
async function search(tracks, query) {
  const router = freshRoute(tracks);
  const handler = getHandler(router, '/');
  let body = null;
  let nextErr = null;
  const req = { query: { q: query } };
  const res = { status() { return this; }, json(data) { body = data; return this; } };
  handler(req, res, (e) => { nextErr = e; });
  await flush();
  await flush();
  await flush();
  if (nextErr) throw nextErr;
  return body;
}

/** A track shaped like trackModel.createTrack output. */
function track(overrides = {}) {
  return {
    id: 'vid00000001',
    videoId: 'vid00000001',
    title: 'Time',
    artist: 'Hans Zimmer',
    artists: [{ name: 'Hans Zimmer', id: 'UC_hz' }],
    album: 'Inception',
    duration: 240,
    thumbnails: [{ url: 'https://img/a.jpg' }],
    resultType: 'song',
    ...overrides,
  };
}

describe('TrackDTO structured artist credits', () => {

  test('a single structured credit is forwarded with name and id', async () => {
    const body = await search([track()], 'q-single');
    assert.ok(Array.isArray(body), 'the search route returns an array of DTOs');
    assert.equal(body.length, 1);
    assert.deepEqual(body[0].artists, [{ name: 'Hans Zimmer', id: 'UC_hz' }]);
    assert.equal(body[0].artist, 'Hans Zimmer', 'the flat field is unchanged');
  });

  test('a genuine multi-artist credit forwards every credited identity in order', async () => {
    const body = await search([track({
      artist: 'Hans Zimmer & Benjamin Wallfisch',
      artists: [
        { name: 'Hans Zimmer', id: 'UC_hz' },
        { name: 'Benjamin Wallfisch', id: 'UC_bw' },
      ],
    })], 'q-multi');

    assert.deepEqual(body[0].artists, [
      { name: 'Hans Zimmer', id: 'UC_hz' },
      { name: 'Benjamin Wallfisch', id: 'UC_bw' },
    ], 'both identities survive, so the client never has to split the flat string');
    assert.equal(body[0].artist, 'Hans Zimmer & Benjamin Wallfisch');
  });

  test('a single act whose NAME contains a separator stays one identity', async () => {
    // The whole point of carrying structure: "AC/DC" must not look like two artists.
    const body = await search([track({
      artist: 'AC/DC',
      artists: [{ name: 'AC/DC', id: 'UC_acdc' }],
    })], 'q-acdc');

    assert.equal(body[0].artists.length, 1);
    assert.equal(body[0].artists[0].name, 'AC/DC');
  });

  test('artists is always an array, even when upstream omits or malforms it', async () => {
    for (const [label, value] of [
      ['missing', undefined],
      ['null', null],
      ['string', 'Hans Zimmer'],
      ['object', { name: 'Hans Zimmer' }],
      ['number', 7],
      ['empty array', []],
    ]) {
      const body = await search([track({ artists: value })], `q-shape-${label}`);
      assert.ok(Array.isArray(body[0].artists), `${label} must still yield an array`);
      assert.deepEqual(body[0].artists, [], `${label} must yield []`);
      assert.equal(body[0].artist, 'Hans Zimmer', `${label} must not disturb the flat field`);
    }
  });

  test('unusable entries are dropped and a missing id becomes an empty string', async () => {
    const body = await search([track({
      artists: [
        null,
        'not-an-object',
        42,
        {},                               // no name
        { id: 'UC_orphan' },              // id without a name
        { name: '' },                     // blank name
        { name: '   ' },                  // whitespace-only name
        { name: 7 },                      // non-string name
        { name: '  Lorne Balfe  ' },      // valid, padded, no id
        { name: 'Hans Zimmer', id: 'UC_hz' },
        { name: 'Steve Mazzaro', id: 42 }, // non-string id
      ],
    })], 'q-malformed');

    assert.deepEqual(body[0].artists, [
      { name: 'Lorne Balfe', id: '' },
      { name: 'Hans Zimmer', id: 'UC_hz' },
      { name: 'Steve Mazzaro', id: '' },
    ], 'only entries with a usable name survive; ids default to empty strings');

    for (const credit of body[0].artists) {
      assert.equal(typeof credit.name, 'string');
      assert.equal(typeof credit.id, 'string');
    }
  });

  test('the DTO key set is unchanged apart from the added artists field', async () => {
    const body = await search([track()], 'q-keys');
    assert.deepEqual(Object.keys(body[0]).sort(), [
      'album', 'artist', 'artists', 'artworkUrl', 'duration',
      'id', 'resultType', 'thumbnails', 'title', 'videoId',
    ], 'no field was removed or renamed');
    assert.ok(!('qualityScore' in body[0]), 'qualityScore stays omitted');
  });
});
