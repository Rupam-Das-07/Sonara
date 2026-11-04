'use strict';

/**
 * searchVideosCache.test.js — Stage C-3F regression coverage for the
 * /api/v1/search/videos Node-side cache and in-flight deduplication.
 *
 * WHAT IS BEING GUARDED
 * ---------------------
 * /search/videos was the only Android-facing read path with no protection at any
 * layer. Node held no cache and no in-flight map, and on the Python side
 * youtube_service.search_youtube() calls _extract_with_retry() directly — the
 * `_in_flight_extractions` dedup map lives in get_audio_info(), NOT in
 * _extract_with_retry() — so concurrent identical searches each ran a full
 * yt-dlp `ytsearchN:` extraction (15s timeout, up to 2 retries).
 *
 * These tests pin the new behaviour AND that nothing else moved: response
 * schema, item order, the non-OK -> empty-200 contract, the network-failure ->
 * 502 contract, and the rule that an emptiness is never cached.
 *
 * ISOLATION: the cache is module-level, so every test uses a UNIQUE query.
 */

const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const searchRouter = require('../../src/routes/search');

const VIDEO_SEARCH_TTL_MS = searchRouter.VIDEO_SEARCH_TTL_MS;

// ---------------------------------------------------------------------------
// Handler extraction + mocks — same approach as tests/routes/search.test.js.
// ---------------------------------------------------------------------------

function getVideosHandler() {
  const layer = searchRouter.stack.find(
    (l) => l.route && l.route.path === '/videos' && l.route.methods.get
  );
  if (!layer) throw new Error('GET /videos route not found in search router stack');
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

/**
 * Invokes the /videos handler and resolves once it has produced an outcome.
 * asyncHandler's wrapper returns void, so completion is observed via res.json()
 * or next() rather than by awaiting the handler itself.
 */
function callVideos(query) {
  const handler = getVideosHandler();
  let settle;
  const done = new Promise((r) => { settle = r; });

  let statusCode = 200;
  const req = { query };
  const res = {
    status(code) { statusCode = code; return this; },
    json(data) { settle({ kind: 'json', status: statusCode, data }); return this; },
  };
  const next = (err) => settle({ kind: 'next', error: err });

  handler(req, res, next);
  return done;
}

const originalFetch = globalThis.fetch;

/** Upstream calls recorded by the fake, in order. */
let upstream;
/** Current fake upstream behaviour. */
let behaviour;

function pythonItems(prefix, n) {
  return Array.from({ length: n }, (_, i) => ({
    videoId: `${prefix}${String(i).padStart(11 - prefix.length, '0')}`.slice(0, 11),
    title: `Video ${i}`,
    channelTitle: `Channel ${i}`,
    thumbnail: `https://img.youtube.com/vi/v${i}/maxresdefault.jpg`,
    duration: 100 + i,
  }));
}

/** A deferred so a test can hold the upstream open and create real overlap. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

beforeEach(() => {
  upstream = [];
  behaviour = async () => ({ ok: true, status: 200, json: async () => ({ items: pythonItems('aa', 3) }) });
  globalThis.fetch = async (url, options) => {
    upstream.push({ url: String(url), options });
    return behaviour(String(url), options);
  };
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  searchRouter._inFlightVideoSearches.clear();
});

const upstreamCount = () => upstream.filter((c) => c.url.includes('/search-youtube')).length;

describe('C-3F — /search/videos cache + in-flight dedup', () => {

  // ---------------------------------------------------------------- CASE 1

  /** CASE 1 — a cold request reaches upstream and returns the mapped payload. */
  test('cold request reaches upstream once and returns mapped items', async () => {
    const q = 'c3f cold query alpha';
    const out = await callVideos({ q });

    assert.equal(out.kind, 'json');
    assert.equal(upstreamCount(), 1);
    assert.equal(out.data.items.length, 3);
    assert.ok(upstream[0].url.includes('/search-youtube?q='));
    assert.ok(upstream[0].url.includes('limit=10'), 'default limit preserved');
  });

  // ---------------------------------------------------------------- CASE 2

  /**
   * CASE 2 — response schema and ordering are byte-identical to the previous
   * inline mapper (this is the Android TrackMapper contract).
   */
  test('response schema and item order are unchanged', async () => {
    const q = 'c3f schema query';
    behaviour = async () => ({
      ok: true, status: 200,
      json: async () => ({
        items: [
          { videoId: 'vid00000001', title: 'First', channelTitle: 'ChanA', thumbnail: 'https://t/1.jpg', duration: 11 },
          { videoId: 'vid00000002', title: 'Second', channelTitle: 'ChanB', duration: 22 },
          { title: 'NoId', channelTitle: 'ChanC' },
        ],
      }),
    });

    const out = await callVideos({ q });
    const items = out.data.items;

    assert.deepEqual(Object.keys(out.data), ['items'], 'envelope must remain { items }');
    assert.deepEqual(Object.keys(items[0]).sort(),
      ['album', 'artist', 'artworkUrl', 'duration', 'id', 'resultType', 'thumbnails', 'title', 'videoId']);
    assert.deepEqual(items.map((i) => i.title), ['First', 'Second', 'NoId'], 'upstream order preserved');
    assert.equal(items[0].artist, 'ChanA', 'channelTitle maps to artist');
    assert.equal(items[0].album, 'YouTube');
    assert.equal(items[0].resultType, 'video');
    assert.equal(items[0].artworkUrl, 'https://t/1.jpg', 'explicit thumbnail wins');
    assert.equal(items[1].artworkUrl, 'https://i.ytimg.com/vi/vid00000002/maxresdefault.jpg',
      'missing thumbnail falls back to the derived maxresdefault URL');
    assert.equal(items[2].videoId, '', 'an item with no id still maps (unchanged degradation)');
    assert.equal(items[2].artworkUrl, '', 'no id and no thumbnail yields an empty artwork URL');
    assert.deepEqual(items[0].thumbnails, [{ url: 'https://t/1.jpg' }]);
  });

  // ---------------------------------------------------------------- CASE 3

  /** CASE 3 — a warm request is served from cache without touching upstream. */
  test('warm request is served from cache with no upstream call', async () => {
    const q = 'c3f warm query';
    const first = await callVideos({ q });
    assert.equal(upstreamCount(), 1);

    const second = await callVideos({ q });
    const third = await callVideos({ q: '  C3F   WARM   QUERY  ' }); // normalizes to the same key

    assert.equal(upstreamCount(), 1, 'cache hit must not re-enter upstream');
    assert.deepEqual(second.data.items, first.data.items);
    assert.deepEqual(third.data.items.map((i) => i.videoId), first.data.items.map((i) => i.videoId),
      'query normalization (trim / case / whitespace) matches ytmusicProvider');
  });

  // ---------------------------------------------------------------- CASE 4

  /**
   * CASE 4 — the core regression: N concurrent identical cold requests collapse
   * to ONE upstream yt-dlp search.
   */
  test('N concurrent identical requests trigger exactly one upstream search', async () => {
    const q = 'c3f concurrent query';
    const gate = deferred();
    behaviour = async () => {
      await gate.promise;
      return { ok: true, status: 200, json: async () => ({ items: pythonItems('bb', 4) }) };
    };

    const pending = Array.from({ length: 8 }, () => callVideos({ q }));
    gate.resolve();
    const results = await Promise.all(pending);

    assert.equal(upstreamCount(), 1,
      '8 concurrent identical requests must collapse to ONE upstream search');
    for (const r of results) {
      assert.equal(r.kind, 'json');
      assert.equal(r.data.items.length, 4);
    }
    assert.deepEqual(results[7].data.items, results[0].data.items);
  });

  // ---------------------------------------------------------------- CASE 5

  /** CASE 5 — distinct queries stay independent (no key collision). */
  test('distinct queries remain independent', async () => {
    const gate = deferred();
    behaviour = async (url) => {
      await gate.promise;
      const tag = url.includes('alpha') ? 'al' : 'be';
      return { ok: true, status: 200, json: async () => ({ items: pythonItems(tag, 2) }) };
    };

    const pending = [
      callVideos({ q: 'c3f distinct alpha' }),
      callVideos({ q: 'c3f distinct beta' }),
    ];
    gate.resolve();
    const [a, b] = await Promise.all(pending);

    assert.equal(upstreamCount(), 2, 'two different queries must produce two upstream searches');
    assert.notDeepEqual(a.data.items.map((i) => i.videoId), b.data.items.map((i) => i.videoId));
  });

  // ---------------------------------------------------------------- CASE 6

  /**
   * CASE 6 — the limit is part of the cache identity. A cached 10-item answer
   * must never be served to a request that asked for a different limit.
   */
  test('the limit is part of the cache key', async () => {
    const q = 'c3f limit key query';
    await callVideos({ q, limit: '10' });
    assert.equal(upstreamCount(), 1);

    await callVideos({ q, limit: '25' });
    assert.equal(upstreamCount(), 2, 'a different limit must not reuse the cached entry');

    await callVideos({ q, limit: '10' });
    assert.equal(upstreamCount(), 2, 'the original limit is still cached');
  });

  // ---------------------------------------------------------------- CASE 7

  /** CASE 7 — an expired entry causes a fresh upstream search. */
  test('an expired cache entry triggers a fresh upstream search', async () => {
    const q = 'c3f expiry query';
    await callVideos({ q });
    assert.equal(upstreamCount(), 1);

    // Age the entry past its TTL (BoundedCache stores { data, ts } and evicts lazily).
    const key = `videos:${q}:10`;
    const entry = searchRouter._videoSearchCache.cache.get(key);
    assert.ok(entry, 'the entry must be present under the expected key');
    entry.ts = Date.now() - (VIDEO_SEARCH_TTL_MS + 1000);

    const refreshed = await callVideos({ q });
    assert.equal(upstreamCount(), 2, 'an expired entry must be re-resolved');
    assert.equal(refreshed.data.items.length, 3);
  });

  // ---------------------------------------------------------------- CASE 8

  /**
   * CASE 8 — a non-OK upstream status keeps the previous contract exactly: an
   * empty 200 result that is NOT cached, so the next request retries.
   */
  test('non-OK upstream yields an empty 200 and is never cached', async () => {
    const q = 'c3f non-ok query';
    behaviour = async () => ({ ok: false, status: 500, json: async () => ({ error: 'Search failed.' }) });

    const out = await callVideos({ q });
    assert.equal(out.kind, 'json');
    assert.equal(out.status, 200, 'a provider error still degrades to 200 + empty items');
    assert.deepEqual(out.data, { items: [] });
    assert.equal(upstreamCount(), 1);

    behaviour = async () => ({ ok: true, status: 200, json: async () => ({ items: pythonItems('cc', 2) }) });
    const retried = await callVideos({ q });
    assert.equal(upstreamCount(), 2, 'a transient failure must not pin an empty Videos tab');
    assert.equal(retried.data.items.length, 2);
  });

  // ---------------------------------------------------------------- CASE 9

  /**
   * CASE 9 — a network/timeout failure still reaches next() as a 502-shaped
   * provider failure, is shared by all concurrent waiters, and releases the
   * in-flight entry so a retry reaches upstream.
   */
  test('network failure forwards to next(), is shared, and releases in-flight state', async () => {
    const q = 'c3f network failure query';
    const gate = deferred();
    let attempt = 0;
    behaviour = async () => {
      attempt += 1;
      if (attempt === 1) {
        await gate.promise;
        const err = new Error('fetch failed');
        err.name = 'TypeError';
        throw err;
      }
      return { ok: true, status: 200, json: async () => ({ items: pythonItems('dd', 1) }) };
    };

    const pending = Array.from({ length: 4 }, () => callVideos({ q }));
    gate.resolve();
    const results = await Promise.all(pending);

    assert.equal(upstreamCount(), 1, 'a failing search is shared, not fanned out');
    for (const r of results) {
      assert.equal(r.kind, 'next', 'every waiter must receive the provider failure');
      assert.ok(r.error, 'next() called with an error');
      assert.equal(r.error.statusCode || r.error.status, 502,
        'the 502 provider-failure contract is unchanged');
    }

    const retried = await callVideos({ q });
    assert.equal(upstreamCount(), 2, 'the in-flight entry must be released after a rejection');
    assert.equal(retried.kind, 'json');
    assert.equal(retried.data.items.length, 1);
  });

  // ---------------------------------------------------------------- CASE 10

  /**
   * CASE 10 — a successful but EMPTY result is not cached, so a later request
   * that would succeed is not blocked by a sticky emptiness.
   */
  test('an empty successful result is not cached', async () => {
    const q = 'c3f empty success query';
    behaviour = async () => ({ ok: true, status: 200, json: async () => ({ items: [] }) });

    const out = await callVideos({ q });
    assert.deepEqual(out.data, { items: [] });
    assert.equal(upstreamCount(), 1);

    behaviour = async () => ({ ok: true, status: 200, json: async () => ({ items: pythonItems('ee', 2) }) });
    const later = await callVideos({ q });
    assert.equal(upstreamCount(), 2, 'an empty success must not become sticky for the TTL');
    assert.equal(later.data.items.length, 2);
  });

  // ---------------------------------------------------------------- CASE 11

  /** CASE 11 — validation and timeout wiring are untouched. */
  test('input validation and the upstream abort signal are unchanged', async () => {
    const blank = await callVideos({ q: '   ' });
    assert.equal(blank.status, 400);
    assert.ok(blank.data.error, 'blank query still rejected with the standard error envelope');

    const tooLong = await callVideos({ q: 'x'.repeat(201) });
    assert.equal(tooLong.status, 400);
    assert.equal(upstreamCount(), 0, 'invalid input must never reach upstream');

    await callVideos({ q: 'c3f signal query' });
    const call = upstream.find((c) => c.url.includes('/search-youtube'));
    assert.ok(call.options && call.options.signal, 'the upstream search must still carry an AbortSignal');
    assert.equal(typeof call.options.signal.aborted, 'boolean');
  });

  // ---------------------------------------------------------------- CASE 12

  /** CASE 12 — the limit clamp (1..50) still applies and reaches upstream. */
  test('limit clamping is unchanged', async () => {
    await callVideos({ q: 'c3f clamp high', limit: '999' });
    assert.ok(upstream.at(-1).url.includes('limit=50'), 'limit clamps to 50');

    await callVideos({ q: 'c3f clamp low', limit: '0' });
    assert.ok(upstream.at(-1).url.includes('limit=10'), 'a non-positive limit falls back to the default 10');
  });
});
