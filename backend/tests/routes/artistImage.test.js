'use strict';

const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const artistsRouter = require('../../src/routes/artists');
const service = require('../../src/discovery/featuredArtistsService');

function getImageHandler() {
  const layer = artistsRouter.stack.find(
    (entry) => entry.route && entry.route.path === '/image' && entry.route.methods.get,
  );
  assert.ok(layer, 'GET /image route must be registered before /:browseId');
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

function callImage(query) {
  const handler = getImageHandler();
  let status = 200;
  let settle;
  const done = new Promise((resolve) => { settle = resolve; });
  const req = { query };
  const res = {
    status(code) { status = code; return this; },
    json(data) { settle({ status, data }); return this; },
  };
  const next = (error) => settle({ status, error });
  handler(req, res, next);
  return done;
}

const originalFetch = globalThis.fetch;

beforeEach(() => {
  service._clearArtistImageCache();
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  service._clearArtistImageCache();
});

describe('GET /api/v1/artists/image', () => {
  test('returns an upgraded trusted portrait and remains ahead of the browse-id route', async () => {
    let calls = 0;
    globalThis.fetch = async () => {
      calls += 1;
      return { ok: true, json: async () => ({ image: 'https://yt3.ggpht.com/hans=w120-h120' }) };
    };

    const result = await callImage({ name: 'Hans Zimmer' });
    assert.deepEqual(result, {
      status: 200,
      data: { imageUrl: 'https://yt3.ggpht.com/hans=w540-h540-p-l90-rj' },
    });
    assert.equal(calls, 1);
  });

  test('returns null for misses, malformed payloads, and untrusted image URLs', async () => {
    for (const image of [undefined, 'http://yt3.ggpht.com/nope', 'https://evil-ggpht.com/a',
      'https://user:pass@yt3.ggpht.com/a', 'https://yt3.ggpht.com:8443/a']) {
      service._clearArtistImageCache();
      globalThis.fetch = async () => ({ ok: true, json: async () => ({ image }) });
      const result = await callImage({ name: `Invalid ${String(image)}` });
      assert.deepEqual(result.data, { imageUrl: null }, String(image));
    }
  });

  test('does not call upstream for invalid names and reports a missing name as 400', async () => {
    let calls = 0;
    globalThis.fetch = async () => { calls += 1; throw new Error('must not fetch'); };
    const tooLong = await callImage({ name: 'A'.repeat(201) });
    assert.deepEqual(tooLong.data, { imageUrl: null });
    assert.equal(calls, 0);

    const missing = await callImage({});
    assert.equal(missing.status, 400);
    assert.equal(calls, 0);
  });

  test('coalesces concurrent lookups and short-caches a negative result', async () => {
    let calls = 0;
    let release;
    const pending = new Promise((resolve) => { release = resolve; });
    globalThis.fetch = async () => {
      calls += 1;
      await pending;
      return { ok: false, status: 404 };
    };

    const first = callImage({ name: 'Cache Miss Artist' });
    const second = callImage({ name: '  cache   miss artist ' });
    release();
    const [one, two, three] = await Promise.all([first, second, callImage({ name: 'Cache Miss Artist' })]);
    assert.deepEqual(one.data, { imageUrl: null });
    assert.deepEqual(two.data, { imageUrl: null });
    assert.deepEqual(three.data, { imageUrl: null });
    assert.equal(calls, 1);
  });
});
