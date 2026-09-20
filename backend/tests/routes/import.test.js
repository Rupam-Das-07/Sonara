'use strict';

/**
 * import.test.js — Integration & route tests for POST /api/v1/import/match
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const importRouter = require('../../src/routes/import');
const { importMatchCache } = require('../../src/import/importMatchCache');
const { matchSingleTrack, THRESHOLD_HIGH, THRESHOLD_LOW } = require('../../src/import/importMatchService');
const { mapConcurrent } = require('../../src/import/importConcurrency');

// Flush microtasks for asyncHandler
const flushMicrotasks = () => new Promise(resolve => setImmediate(resolve));

function getImportHandler() {
  const layer = importRouter.stack.find(
    (l) => l.route && l.route.path === '/match' && l.route.methods.post
  );
  if (!layer) throw new Error('POST /match route not found in import router stack');
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

function makeMocks(body) {
  let statusCode = 200;
  let responseData = null;
  let nextError = null;

  const req = { body: body || {} };
  const res = {
    status(code) { statusCode = code; return this; },
    json(data) { responseData = data; return this; },
  };
  const next = (err) => { nextError = err; };

  return {
    req, res, next,
    getStatus:    () => statusCode,
    getData:      () => responseData,
    getNextError: () => nextError,
  };
}

describe('POST /api/v1/import/match Route Tests', () => {
  beforeEach(() => {
    importMatchCache.clear();
  });

  test('rejects non-object request body with 400', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks('not an object');
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.equal(mocks.getData()?.error?.code, 'INVALID_REQUEST');
  });

  test('rejects missing or non-array tracks with 400', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({ importId: 'test-1', chunkIndex: 0, tracks: 'not-an-array' });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.match(mocks.getData()?.error?.message, /must be an array/);
  });

  test('rejects oversized chunk (> 50 tracks) with 400', async () => {
    const handler = getImportHandler();
    const tracks = new Array(51).fill(0).map((_, i) => ({
      sourceOrder: i,
      title: `Track ${i}`,
      artist: `Artist ${i}`,
    }));
    const mocks = makeMocks({ importId: 'test-oversized', chunkIndex: 0, tracks });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.match(mocks.getData()?.error?.message, /Max 50 tracks/);
  });

  test('rejects track with empty title with 400', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({
      importId: 'test-bad-title',
      chunkIndex: 0,
      tracks: [{ sourceOrder: 0, title: '   ', artist: 'Some Artist' }],
    });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.match(mocks.getData()?.error?.message, /missing a non-empty "title"/);
  });

  test('rejects track with empty artist with 400', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({
      importId: 'test-bad-artist',
      chunkIndex: 0,
      tracks: [{ sourceOrder: 0, title: 'Some Title', artist: '' }],
    });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.match(mocks.getData()?.error?.message, /missing a non-empty "artist"/);
  });

  test('rejects negative durationMs with 400', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({
      importId: 'test-bad-duration',
      chunkIndex: 0,
      tracks: [{ sourceOrder: 0, title: 'Song', artist: 'Artist', durationMs: -100 }],
    });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 400);
    assert.match(mocks.getData()?.error?.message, /invalid "durationMs"/);
  });

  test('short-circuits local files and podcast episodes without upstream calls', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({
      importId: 'test-short-circuits',
      chunkIndex: 0,
      tracks: [
        { sourceOrder: 0, title: 'Local Song', artist: 'Unknown', isLocalFile: true },
        { sourceOrder: 1, title: 'Podcast Ep 1', artist: 'Podcaster', isEpisode: true },
      ],
    });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 200);
    const data = mocks.getData();
    assert.equal(data.results.length, 2);

    assert.equal(data.results[0].status, 'skipped');
    assert.equal(data.results[0].tier, 'none');
    assert.equal(data.results[0].reason, 'local_file');
    assert.equal(data.results[0].resolvedTrack, null);

    assert.equal(data.results[1].status, 'skipped');
    assert.equal(data.results[1].tier, 'none');
    assert.equal(data.results[1].reason, 'episode');
    assert.equal(data.results[1].resolvedTrack, null);
  });

  test('ignores spotifyUri if passed in track payload', async () => {
    const handler = getImportHandler();
    const mocks = makeMocks({
      importId: 'test-spotify-uri',
      chunkIndex: 0,
      tracks: [
        {
          sourceOrder: 0,
          title: 'Test',
          artist: 'Test',
          spotifyUri: 'spotify:track:abcdef123456',
          isLocalFile: true,
        },
      ],
    });
    await handler(mocks.req, mocks.res, mocks.next);
    await flushMicrotasks();

    assert.equal(mocks.getStatus(), 200);
    const item = mocks.getData().results[0];
    assert.equal(item.status, 'skipped');
    assert.equal(item.spotifyUri, undefined); // Server never returns or accepts spotifyUri
  });
});

describe('Import Matching & Concurrency Unit Tests', () => {
  test('bounded concurrency preserves input order', async () => {
    const items = [10, 20, 30, 40, 50];
    let maxRunning = 0;
    let currentlyRunning = 0;

    const results = await mapConcurrent(items, 2, async (val, idx) => {
      currentlyRunning++;
      maxRunning = Math.max(maxRunning, currentlyRunning);
      await new Promise(r => setTimeout(r, 10));
      currentlyRunning--;
      return val * 2;
    });

    assert.deepEqual(results, [20, 40, 60, 80, 100]);
    assert.ok(maxRunning <= 2, `maxRunning was ${maxRunning}, expected <= 2`);
  });

  test('importMatchCache caches and returns hits on repeated track metadata', () => {
    importMatchCache.clear();
    const title = 'Shape of You';
    const artist = 'Ed Sheeran';
    const durationMs = 233000;

    assert.equal(importMatchCache.get(title, artist, durationMs), null);

    const decision = {
      sourceOrder: 0,
      status: 'matched',
      tier: 'confident',
      confidence: 0.95,
      resolvedTrack: { id: 'vid1', videoId: 'vid1', title, artist },
      alternatives: [],
      reason: null,
    };

    importMatchCache.set(title, artist, durationMs, decision);
    const cached = importMatchCache.get(title, artist, durationMs);
    assert.ok(cached);
    assert.equal(cached.status, 'matched');
    assert.equal(cached.confidence, 0.95);
    assert.equal(importMatchCache.hits, 1);
  });

  test('thresholds are properly configured', () => {
    assert.equal(THRESHOLD_HIGH, 0.75);
    assert.equal(THRESHOLD_LOW, 0.55);
  });
});
