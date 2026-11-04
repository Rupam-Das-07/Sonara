'use strict';

const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const service = require('../../src/discovery/featuredArtistsService');
const { slugify } = require('../../src/discovery/artistIdentity');

const originalFetch = globalThis.fetch;
let artists;
let calls;
const browseId = (letter) => `UC${letter.repeat(22)}`;
const row = (title, letter, extra = {}) => ({ title, browseId: browseId(letter), ...extra });
const identities = (roster) => Object.fromEntries(roster.map((artist) => [artist.browseId, artist.id]));

beforeEach(() => {
  artists = [];
  calls = 0;
  globalThis.fetch = async (url, options) => {
    calls += 1;
    assert.ok(String(url).endsWith('/api/charts/artists'));
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.headers.Accept, 'application/json');
    return { ok: true, json: async () => ({ artists }) };
  };
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test('native chart rows survive with unchanged serialized DTO and artwork behavior', async () => {
  artists = [
    row('अरिजीत सिंह', 'a', { thumbnails: [{ url: 'https://lh3.googleusercontent.com/avatar=w120-h120' }] }),
    { name: 'অরিজিৎ সিং', browseId: browseId('b'), thumbnails: ['https://yt3.ggpht.com/avatar=w120-h120'] },
  ];
  const roster = await service.refreshWithLock();
  assert.deepEqual(JSON.parse(JSON.stringify({ featured: roster })).featured.slice(0, 2), [
    { id: 'अरिजीत-सिंह', name: 'अरिजीत सिंह', browseId: browseId('a'), genre: '',
      imageUrl: 'https://lh3.googleusercontent.com/avatar=w540-h540-p-l90-rj' },
    { id: 'অরিজিৎ-সিং', name: 'অরিজিৎ সিং', browseId: browseId('b'), genre: '',
      imageUrl: 'https://yt3.ggpht.com/avatar=w540-h540-p-l90-rj' },
  ]);
  for (const artist of roster) {
    assert.deepEqual(Object.keys(artist).sort(), ['browseId', 'genre', 'id', 'imageUrl', 'name']);
  }
  assert.equal(roster.length, 8);
  assert.equal(service.slugify, slugify);
});

test('editorial roster keeps artists while nulling invalid image URLs', async () => {
  artists = [
    row('Trusted Artist', 'a', { thumbnails: [{ url: 'https://yt3.ggpht.com/avatar=w120-h120' }] }),
    row('HTTP Artist', 'b', { thumbnails: [{ url: 'http://yt3.ggpht.com/avatar=w120-h120' }] }),
    row('Credential Artist', 'c', { thumbnails: [{ url: 'https://user:pass@yt3.ggpht.com/avatar' }] }),
    row('Port Artist', 'd', { thumbnails: [{ url: 'https://yt3.ggpht.com:8443/avatar' }] }),
    row('Malformed Artist', 'e', { thumbnails: [{ url: 'not a URL' }] }),
    row('Untrusted Artist', 'f', { thumbnails: [{ url: 'https://example.com/avatar.jpg' }] }),
  ];

  const roster = await service.refreshWithLock();
  const byName = new Map(roster.map((artist) => [artist.name, artist]));

  assert.equal(byName.get('Trusted Artist').imageUrl, 'https://yt3.ggpht.com/avatar=w540-h540-p-l90-rj');
  for (const name of ['HTTP Artist', 'Credential Artist', 'Port Artist', 'Malformed Artist', 'Untrusted Artist']) {
    assert.ok(byName.has(name), `${name} must remain in the roster`);
    assert.equal(byName.get(name).imageUrl, null, `${name} must use the monogram fallback`);
  }
});

test('authoritative IDs remain exact opaque strings even with identical lossy slugs', async () => {
  artists = [row('Beyonce', 'a', { id: 'Artist:ABC/One' }),
    row('Beyoncé', 'b', { id: '  Artist:ABC/Two  ' }),
    row('Beyonce', 'c', { id: 'Artist:ABC/Three' }),
    row('Native Artist', 'd', { id: 'ক\u200dক' })];
  const roster = await service.refreshWithLock();
  assert.deepEqual(roster.slice(0, 4).map((artist) => artist.id), artists.map((artist) => artist.id));
  assert.equal(new Set(roster.map((artist) => artist.id)).size, roster.length);
});

test('lossy slug collisions and identical names with distinct browseIds survive deterministically', async () => {
  artists = [row('Beyonce', 'a'), row('Beyoncé', 'b'), row('AC/DC', 'c'),
    row('AC-DC', 'd'), row('Same Artist', 'e'), row('Same Artist', 'f')];
  const first = await service.refreshWithLock();
  assert.equal(first.length, 8);
  assert.deepEqual(first.slice(0, 6).map((artist) => artist.browseId), artists.map((artist) => artist.browseId));
  assert.equal(new Set(first.map((artist) => artist.id)).size, first.length);
  assert.equal(first[0].id, `beyonce--${browseId('a')}`);
  assert.deepEqual(await service.refreshWithLock(), first);
  artists.reverse();
  assert.deepEqual(identities(await service.refreshWithLock()), identities(first));
});

test('exact browseId duplicates are omitted even under different names and IDs', async () => {
  artists = [row('Artist One', 'a', { id: 'Original' }),
    row('Artist Alternate', 'a', { id: 'Alternate' }), row('Artist Two', 'b')];
  const roster = await service.refreshWithLock();
  assert.equal(roster.filter((artist) => artist.browseId === browseId('a')).length, 1);
  assert.equal(roster[0].id, 'Original');
  assert.equal(roster[1].name, 'Artist Two');
});

test('malformed individual names and identifiers do not abort valid chart candidates', async () => {
  artists = [null, 42, {}, row({}, 'a'), row(42, 'a'), row([], 'a'),
    row('Artist\ud800', 'a'), row('Artist\u0000', 'a'), row('़', 'a'),
    row('Artist', 'a', { browseId: {} }), row('Artist', 'a', { browseId: ' UCaaaaaaaaaaaaaaaaaaaaaa ' }),
    ...[null, undefined, 42, {}, [], '', ' ', '\ud800', 'bad\u0000id'].map((id) => row('Artist', 'a', { id })),
    row('Surviving Artist', 'b')];
  const roster = await service.refreshWithLock();
  assert.equal(roster[0].name, 'Surviving Artist');
  assert.equal(roster.some((artist) => artist.browseId === browseId('a')), false);
  assert.equal(roster.length, 7);
});

test('padding retains original anchor IDs and metadata when dynamic names collide', async () => {
  artists = [row('Arijit Singh', 'a'), row('A.R. Rahman', 'b')];
  const roster = await service.refreshWithLock();
  for (const anchor of service.CORE_ANCHORS) {
    assert.deepEqual(roster.find((artist) => artist.browseId === anchor.browseId), {
      id: slugify(anchor.name), name: anchor.name, browseId: anchor.browseId,
      genre: anchor.genre, imageUrl: anchor.imageUrl,
    });
  }
  assert.equal(roster[0].id, `arijit-singh--${browseId('a')}`);
  assert.equal(new Set(roster.map((artist) => artist.id)).size, roster.length);
});

test('anchor browseIds deduplicate across native display names', async () => {
  artists = [row('अरिजीत सिंह', 'a', { browseId: service.CORE_ANCHORS[0].browseId })];
  const roster = await service.refreshWithLock();
  assert.equal(roster.length, 6);
  assert.equal(roster[0].name, 'अरिजीत सिंह');
  assert.equal(new Set(roster.map((artist) => artist.browseId)).size, roster.length);
});

test('immutable ID conflicts reject unsafe rows and never steal an anchor key', async () => {
  artists = [row('Artist One', 'a', { id: 'Shared' }), row('Artist Two', 'b', { id: 'Shared' }),
    row('Artist Three', 'c', { id: 'arijit-singh' }), row('Survivor', 'd')];
  const first = await service.refreshWithLock();
  assert.equal(first[0].name, 'Survivor');
  assert.equal(first.length, 7);
  assert.equal(first.some((artist) => artist.id === 'Shared'), false);
  assert.equal(first.find((artist) => artist.id === 'arijit-singh').browseId, service.CORE_ANCHORS[0].browseId);
  artists.reverse();
  assert.deepEqual(identities(await service.refreshWithLock()), identities(first));
});

test('generated collision suffixes cannot overwrite arbitrary authoritative IDs', async () => {
  artists = [row('Beyonce', 'a'), row('Beyoncé', 'b'),
    row('Opaque Artist', 'c', { id: `beyonce--${browseId('a')}` })];
  const roster = await service.refreshWithLock();
  assert.equal(roster[2].id, artists[2].id);
  assert.equal(new Set(roster.map((artist) => artist.id)).size, roster.length);
  assert.equal(roster[0].id, `beyonce--${browseId('a')}--${browseId('a')}`);
});

test('conflicting upstream IDs cannot rename a genuine chart anchor', async () => {
  const anchor = service.CORE_ANCHORS[0];
  artists = [row('Other Artist', 'a', { id: slugify(anchor.name) }),
    { title: anchor.name, browseId: anchor.browseId }];
  const roster = await service.refreshWithLock();
  assert.equal(roster[0].browseId, anchor.browseId);
  assert.equal(roster[0].id, slugify(anchor.name));
  assert.equal(roster.length, 6);
});

test('existing blacklist, fan filtering and eight-artist cap remain in force', async () => {
  artists = [row('Sameer', 'x'), row('T-Series', 'y'), row('Artist fan club', 'z'),
    ...'abcdefghij'.split('').map((letter) => row(`Artist ${letter}`, letter))];
  const roster = await service.refreshWithLock();
  assert.deepEqual(roster.map((artist) => artist.name), 'abcdefgh'.split('').map((letter) => `Artist ${letter}`));
});

test('SWR serves its snapshot, shares one refresh and retains it after upstream failure', async () => {
  artists = [row('Snapshot Artist', 'a')];
  const snapshot = await service.refreshWithLock();
  assert.deepEqual(await service.getFeaturedArtists(), snapshot);
  assert.equal(calls, 1);
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  globalThis.fetch = async () => {
    calls += 1;
    await gate;
    return { ok: true, json: async () => ({ artists: [row('Refreshed Artist', 'b')] }) };
  };
  const first = service.refreshWithLock();
  const second = service.refreshWithLock();
  try {
    assert.equal(first, second);
    assert.deepEqual(await service.getFeaturedArtists(), snapshot);
    assert.equal(calls, 2);
  } finally {
    release();
    await Promise.all([first, second]);
  }
  const refreshed = await first;
  globalThis.fetch = async () => { throw new Error('Mock upstream unavailable'); };
  assert.deepEqual(await service.refreshWithLock(), refreshed);
});

test('local artist image resolution preserves the trusted upstream image', async () => {
  globalThis.fetch = async (url, options) => {
    assert.ok(String(url).includes('/api/artist-image?name=Hans%20Zimmer'));
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.headers.Accept, 'application/json');
    return {
      ok: true,
      json: async () => ({ image: 'https://lh3.googleusercontent.com/hans=w120-h120' }),
    };
  };

  assert.equal(
    await service.resolveArtistImage('Hans Zimmer'),
    'https://lh3.googleusercontent.com/hans=w540-h540-p-l90-rj',
  );
});
