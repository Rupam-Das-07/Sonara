'use strict';

/**
 * artistCatalogDedup.test.js — Stage C-3E regression coverage for in-flight
 * request deduplication in artistCatalogService.generateArtistCatalog().
 *
 * WHAT IS BEING GUARDED
 * ---------------------
 * /api/artist-catalog-deep is the heaviest upstream call in the backend (Python
 * wraps it in a 20s gevent timeout) and it had no stampede protection on either
 * side: ytmusic_service.py holds no cache and no in-flight map for it, and the
 * Node 8-hour cache is only populated after a build finishes. Concurrent
 * requests for the same artist therefore each ran a full catalog build.
 *
 * These tests pin BOTH halves of the contract:
 *   - concurrent identical requests collapse to ONE upstream build, and
 *   - nothing else changed: response schema, track ordering, the 8h cache, the
 *     search fallback, the never-cache-an-empty-catalog rule, the never-throws
 *     contract, abort/timeout wiring, and the distinct-artist key boundary.
 *
 * ISOLATION: generateArtistCatalog owns a module-level 8-hour BoundedCache, so
 * every test uses a UNIQUE artist name. That keeps the tests independent without
 * adding a cache-clearing hook to the production surface.
 */

const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const { generateArtistCatalog } = require('../../src/discovery/artistCatalogService');

// ---------------------------------------------------------------------------
// Fake upstream — mirrors the house pattern in tests/routes/health.test.js
// (save globalThis.fetch, replace, restore).
// ---------------------------------------------------------------------------

const originalFetch = globalThis.fetch;

/** Calls recorded by the fake, in order. */
let calls;
/** Per-path behaviour. */
let handlers;

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return { ok, status, json: async () => body };
}

function deepCatalogBody(artistName, trackCount = 4) {
  return {
    artistName,
    browseId: 'UC' + 'a'.repeat(22),
    artistImage: 'https://img/artist.jpg',
    description: 'desc',
    subscriberCount: '1M',
    tracks: Array.from({ length: trackCount }, (_, i) => ({
      videoId: String(i).padStart(11, 'q'),
      title: `Song ${i}`,
      artist: artistName,
      duration: 200 + i,
      thumbnails: [{ url: `https://i.ytimg.com/vi/t${i}/hq.jpg`, width: 480, height: 360 }],
    })),
    totalRawTracks: trackCount,
  };
}

/** A deferred so a test can hold the upstream open and create real overlap. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

beforeEach(() => {
  calls = [];
  handlers = {};
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    const path = String(url).includes('/api/artist-catalog-deep')
      ? 'deep'
      : (String(url).includes('/api/search') ? 'search' : 'other');
    const handler = handlers[path];
    if (!handler) return jsonResponse({});
    return handler(String(url), options);
  };
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

const countCalls = (kind) => calls.filter((c) => c.url.includes(kind)).length;

describe('C-3E — artistCatalogService in-flight deduplication', () => {

  // -------------------------------------------------------------- CASE 1

  /**
   * CASE 1 — the core regression: N concurrent identical cold requests must
   * perform exactly ONE upstream catalog build.
   */
  test('concurrent identical cold requests trigger exactly one upstream build', async () => {
    const name = 'Dedup Case One Artist';
    const gate = deferred();
    handlers.deep = async () => {
      await gate.promise;                      // hold every caller inside the build
      return jsonResponse(deepCatalogBody(name));
    };

    const inFlight = Array.from({ length: 8 }, () => generateArtistCatalog(null, name));
    // All 8 callers are now registered; release the upstream.
    gate.resolve();
    const results = await Promise.all(inFlight);

    assert.equal(countCalls('/api/artist-catalog-deep'), 1,
      '8 concurrent identical requests must collapse to ONE upstream build');
    assert.equal(results.length, 8);
    for (const r of results) {
      assert.equal(r.artist, name);
      assert.equal(r.tracks.length, 4);
    }
  });

  // -------------------------------------------------------------- CASE 2

  /**
   * CASE 2 — response schema and track ordering are identical for every caller
   * and unchanged from the pre-dedup shape.
   */
  test('every concurrent caller receives the identical schema and track order', async () => {
    const name = 'Dedup Case Two Artist';
    handlers.deep = async () => jsonResponse(deepCatalogBody(name, 5));

    const [a, b, c] = await Promise.all([
      generateArtistCatalog(null, name),
      generateArtistCatalog(null, name),
      generateArtistCatalog(null, name),
    ]);

    assert.deepEqual(Object.keys(a).sort(),
      ['albums', 'artist', 'artistImage', 'browseId', 'description', 'subscriberCount', 'tracks'],
      'outbound catalog schema must be unchanged');
    assert.deepEqual(a.tracks.map((t) => t.videoId), b.tracks.map((t) => t.videoId));
    assert.deepEqual(a.tracks.map((t) => t.videoId), c.tracks.map((t) => t.videoId));
    assert.deepEqual(
      a.tracks.map((t) => t.title),
      ['Song 0', 'Song 1', 'Song 2', 'Song 3', 'Song 4'],
      'track ordering must remain deterministic and upstream-ordered'
    );
    // Track DTO shape (unchanged contract with the Android client).
    assert.deepEqual(Object.keys(a.tracks[0]).sort(),
      ['album', 'artist', 'artworkUrl', 'duration', 'id', 'resultType', 'thumbnails', 'title', 'videoId']);
  });

  // -------------------------------------------------------------- CASE 3

  /**
   * CASE 3 — the dedup key must NOT merge different artists. Two concurrent
   * requests for distinct artists must each get their own build.
   */
  test('distinct artists are not deduplicated into one another', async () => {
    const nameA = 'Dedup Case Three Alpha';
    const nameB = 'Dedup Case Three Beta';
    const gate = deferred();
    handlers.deep = async (url) => {
      await gate.promise;
      const which = url.includes(encodeURIComponent(nameA).replace(/%20/g, '+')) || url.includes('Alpha')
        ? nameA : nameB;
      return jsonResponse(deepCatalogBody(which));
    };

    const pending = [generateArtistCatalog(null, nameA), generateArtistCatalog(null, nameB)];
    gate.resolve();
    const [resA, resB] = await Promise.all(pending);

    assert.equal(countCalls('/api/artist-catalog-deep'), 2,
      'two different artists must produce two upstream builds');
    assert.equal(resA.artist, nameA);
    assert.equal(resB.artist, nameB);
  });

  // -------------------------------------------------------------- CASE 4

  /**
   * CASE 4 — cache behaviour unchanged: once a build completes, later requests
   * are served from the existing 8-hour cache, not from a new build and not
   * from a lingering in-flight entry.
   */
  test('a completed build is served from cache on subsequent requests', async () => {
    const name = 'Dedup Case Four Artist';
    handlers.deep = async () => jsonResponse(deepCatalogBody(name));

    const first = await generateArtistCatalog(null, name);
    assert.equal(countCalls('/api/artist-catalog-deep'), 1);

    const second = await generateArtistCatalog(null, name);
    const third = await generateArtistCatalog(null, name);

    assert.equal(countCalls('/api/artist-catalog-deep'), 1,
      'warm requests must hit the cache, not re-enter the build path');
    assert.equal(second.artist, first.artist);
    assert.deepEqual(third.tracks.map((t) => t.videoId), first.tracks.map((t) => t.videoId));
  });

  // -------------------------------------------------------------- CASE 5

  /**
   * CASE 5 — error behaviour unchanged. An upstream throw still degrades to the
   * empty catalog for EVERY concurrent caller (never throws, never rejects), and
   * because an empty catalog is deliberately not cached the next request must be
   * free to retry — which also proves the in-flight entry was released.
   */
  test('upstream failure degrades to an empty catalog and does not poison later requests', async () => {
    const name = 'Dedup Case Five Artist';
    let attempt = 0;
    const gate = deferred();
    handlers.deep = async () => {
      attempt += 1;
      if (attempt === 1) {
        await gate.promise;
        throw new Error('simulated upstream network failure');
      }
      return jsonResponse(deepCatalogBody(name));
    };

    const pending = [
      generateArtistCatalog(null, name),
      generateArtistCatalog(null, name),
      generateArtistCatalog(null, name),
    ];
    gate.resolve();
    const failed = await Promise.all(pending);

    assert.equal(countCalls('/api/artist-catalog-deep'), 1,
      'the failing build is still shared — a failure must not fan out either');
    for (const r of failed) {
      assert.equal(r.tracks.length, 0, 'failure degrades to an empty catalog');
      assert.equal(r.artist, name, 'empty catalog still echoes the requested artist');
      assert.equal(r.browseId, null);
      assert.deepEqual(Object.keys(r).sort(),
        ['albums', 'artist', 'artistImage', 'browseId', 'description', 'subscriberCount', 'tracks'],
        'the degraded shape must match the success shape');
    }

    // Empty catalogs are never cached, and the in-flight entry must be gone, so
    // a retry has to reach upstream again.
    const retried = await generateArtistCatalog(null, name);
    assert.equal(countCalls('/api/artist-catalog-deep'), 2,
      'a failed build must be released so the next request can retry');
    assert.equal(retried.tracks.length, 4, 'the retry succeeds normally');
  });

  // -------------------------------------------------------------- CASE 6

  /**
   * CASE 6 — the search fallback is preserved and also shared. A non-OK deep
   * response must still fall back to /api/search exactly once for N concurrent
   * callers.
   */
  test('non-OK deep response still falls back to search, once, for all callers', async () => {
    const name = 'Dedup Case Six Artist';
    const gate = deferred();
    handlers.deep = async () => {
      await gate.promise;
      return jsonResponse({ error: 'no profile' }, { ok: false, status: 404 });
    };
    handlers.search = async () => jsonResponse([
      {
        videoId: 'fallback001',
        title: 'Fallback Song',
        artist: name,
        duration: 180,
        thumbnails: [{ url: 'https://i.ytimg.com/vi/fb/hq.jpg', width: 480, height: 360 }],
      },
    ]);

    const pending = Array.from({ length: 5 }, () => generateArtistCatalog(null, name));
    gate.resolve();
    const results = await Promise.all(pending);

    assert.equal(countCalls('/api/artist-catalog-deep'), 1, 'one shared deep attempt');
    assert.equal(countCalls('/api/search'), 1, 'one shared fallback search');
    for (const r of results) {
      assert.equal(r.tracks.length, 1);
      assert.equal(r.tracks[0].videoId, 'fallback001');
      assert.equal(r.tracks[0].title, 'Fallback Song');
    }
  });

  // -------------------------------------------------------------- CASE 7

  /**
   * CASE 7 — timeout / cancellation wiring preserved: the deep request is still
   * issued with an abort signal, and an abort still degrades to empty rather
   * than rejecting.
   */
  test('the deep request still carries an abort signal and aborts degrade to empty', async () => {
    const name = 'Dedup Case Seven Artist';
    handlers.deep = async () => jsonResponse(deepCatalogBody(name));
    await generateArtistCatalog(null, name);

    const deepCall = calls.find((c) => c.url.includes('/api/artist-catalog-deep'));
    assert.ok(deepCall.options && deepCall.options.signal,
      'the deep catalog fetch must still pass an AbortSignal (timeout budget preserved)');
    assert.equal(typeof deepCall.options.signal.aborted, 'boolean');
    assert.equal(deepCall.options.headers.Accept, 'application/json');

    // An abort-shaped rejection must still be swallowed into the empty catalog.
    const abortName = 'Dedup Case Seven Abort Artist';
    handlers.deep = async () => {
      const err = new Error('The operation was aborted due to timeout');
      err.name = 'TimeoutError';
      throw err;
    };
    const aborted = await generateArtistCatalog(null, abortName);
    assert.equal(aborted.tracks.length, 0);
    assert.equal(aborted.artist, abortName);
  });

  // -------------------------------------------------------------- CASE 8

  /**
   * CASE 8 — the no-identity short circuit is untouched: it must still return an
   * empty catalog without any upstream call and without registering anything.
   */
  test('a request with neither browseId nor name makes no upstream call', async () => {
    handlers.deep = async () => jsonResponse(deepCatalogBody('never'));

    const result = await generateArtistCatalog(null, null);

    assert.equal(countCalls('/api/artist-catalog-deep'), 0);
    assert.equal(result.tracks.length, 0);
    assert.equal(result.artist, '');
    assert.equal(result.browseId, null);
  });

  // -------------------------------------------------------------- CASE 9

  /**
   * CASE 9 — browseId and display-name requests for the SAME artist key share a
   * build, matching the existing cache key (`artist:<browseId || lowercased name>`).
   * A real browseId keys on the id; a plain name keys on the normalized name, so
   * case-differing names must share while a browseId stays separate.
   */
  test('name-keyed requests share a build case-insensitively; browseId keys separately', async () => {
    const browseId = 'UC' + 'z'.repeat(22);
    const name = 'Dedup Case Nine Artist';
    const gate = deferred();
    handlers.deep = async () => {
      await gate.promise;
      return jsonResponse(deepCatalogBody(name));
    };

    const pending = [
      generateArtistCatalog(null, name),
      generateArtistCatalog(null, name.toUpperCase()),   // same normalized key
      generateArtistCatalog(browseId, name),             // keys on the browseId
    ];
    gate.resolve();
    const results = await Promise.all(pending);

    assert.equal(countCalls('/api/artist-catalog-deep'), 2,
      'case-differing names share one build; a real browseId is a separate key');
    for (const r of results) assert.equal(r.tracks.length, 4);
  });
});
