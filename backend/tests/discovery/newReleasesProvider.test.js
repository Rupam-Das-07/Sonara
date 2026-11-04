'use strict';

const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const provider = require('../../src/search/ytmusicProvider');

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test('new-releases provider preserves source failure metadata for non-OK responses', async () => {
  globalThis.fetch = async () => ({ ok: false, status: 503 });

  const result = await provider.getNewReleaseShelf();

  assert.deepEqual(result, []);
  assert.equal(result.sourceFailed, true);
});

test('new-releases provider preserves source failure metadata for timeouts/exceptions', async () => {
  globalThis.fetch = async () => {
    throw Object.assign(new Error('upstream timeout'), { name: 'TimeoutError' });
  };

  const result = await provider.getNewReleaseShelf();

  assert.deepEqual(result, []);
  assert.equal(result.sourceFailed, true);
});

test('new-releases provider treats a valid empty shelf as a successful empty result', async () => {
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    async json() { return { success: true, containers: [] }; },
  });

  const result = await provider.getNewReleaseShelf();

  assert.deepEqual(result, []);
  assert.equal(result.sourceFailed, undefined);
});
