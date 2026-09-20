'use strict';

/**
 * jiosaavnStreamProvider.test.js — Unit tests for JioSaavn stream provider.
 * All network calls are mocked deterministically.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { JioSaavnStreamProvider, CIRCUIT_FAILURE_THRESHOLD } = require('../../src/stream/providers/jiosaavnStreamProvider');

describe('JioSaavnStreamProvider', () => {
  const target = {
    videoId: 'dQw4w9WgXcQ',
    title: 'Kesariya',
    artist: 'Arijit Singh',
    duration: 268,
  };

  // Valid encrypted URL for https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_96.mp4
  const validEncUrl = 'ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyryhkSYK5IH2E7FCO52VR6yhNbcEbes5iCcja4+W8xhE0SwtCJToN4Bw7tS9a8Gtq';

  function createMockFetch(options = {}) {
    return async (url, init = {}) => {
      if (options.throwError) {
        const err = new Error(options.throwError);
        err.name = options.errorName || 'Error';
        throw err;
      }

      // Check if HEAD request (preflight validation)
      if (init.method === 'HEAD') {
        if (options.headFail) {
          return { ok: false, status: 404, headers: new Map() };
        }
        return {
          ok: true,
          status: options.headStatus || 200,
          headers: new Map([
            ['content-type', 'audio/mp4'],
            ['content-length', String(options.contentLength || 8_000_000)],
            ['accept-ranges', 'bytes'],
          ]),
        };
      }

      // Search API response
      if (options.searchStatus && options.searchStatus !== 200) {
        return { ok: false, status: options.searchStatus, text: async () => 'Error' };
      }

      if (options.searchMalformed) {
        return { ok: true, status: 200, text: async () => '<HTML>Not JSON' };
      }

      const results = options.candidates !== undefined ? options.candidates : [
        {
          song: 'Kesariya',
          singers: 'Arijit Singh, Pritam',
          duration: '268',
          '320kbps': 'true',
          encrypted_media_url: validEncUrl,
        }
      ];

      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ results }),
      };
    };
  }

  test('successful resolution returns normalized 320k ResolvedSource', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch() });
    const res = await provider.resolve(target);

    assert.ok(res);
    assert.equal(res.provider, 'jiosaavn');
    assert.equal(res.codec, 'aac');
    assert.equal(res.bitrate, 320);
    assert.equal(res.format, 'audio/mp4');
    assert.equal(res.isDirect, true);
    assert.equal(res.streamUrl, 'https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_320.mp4');
  });

  test('DES decryption produces expected clean URL', () => {
    const provider = new JioSaavnStreamProvider();
    const decrypted = provider.decryptUrl(validEncUrl);
    assert.equal(decrypted, 'https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_96.mp4');
  });

  test('URL rewriting transforms lower bitrates to _320.mp4', () => {
    const provider = new JioSaavnStreamProvider();
    assert.equal(
      provider.rewriteTo320('https://aac.saavncdn.com/test_96.mp4'),
      'https://aac.saavncdn.com/test_320.mp4'
    );
    assert.equal(
      provider.rewriteTo320('https://aac.saavncdn.com/test_160.mp4'),
      'https://aac.saavncdn.com/test_320.mp4'
    );
    assert.equal(
      provider.rewriteTo320('https://aac.saavncdn.com/test_320.mp4'),
      'https://aac.saavncdn.com/test_320.mp4'
    );
    assert.equal(provider.rewriteTo320('invalid'), null);
  });

  test('HEAD pre-flight validation accepts status 200 and 206', async () => {
    const p200 = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ headStatus: 200 }) });
    assert.equal(await p200.validateCdnResource('https://aac.saavncdn.com/test.mp4'), true);

    const p206 = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ headStatus: 206 }) });
    assert.equal(await p206.validateCdnResource('https://aac.saavncdn.com/test.mp4'), true);
  });

  test('HEAD failure returns null', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ headFail: true }) });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('API 500 error returns null', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ searchStatus: 500 }) });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('malformed JSON response returns null', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ searchMalformed: true }) });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('empty search results returns null', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch({ candidates: [] }) });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('candidate mismatch returns null', async () => {
    const provider = new JioSaavnStreamProvider({
      fetchFn: createMockFetch({
        candidates: [{
          song: 'Completely Different Song',
          singers: 'Another Singer',
          duration: '100',
          '320kbps': 'true',
          encrypted_media_url: validEncUrl,
        }],
      }),
    });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('candidate missing 320kbps returns null', async () => {
    const provider = new JioSaavnStreamProvider({
      fetchFn: createMockFetch({
        candidates: [{
          song: 'Kesariya',
          singers: 'Arijit Singh, Pritam',
          duration: '268',
          '320kbps': 'false',
          encrypted_media_url: validEncUrl,
        }],
      }),
    });
    const res = await provider.resolve(target);
    assert.equal(res, null);
  });

  test('circuit breaker trips after 5 consecutive hard failures and recovers', async () => {
    const provider = new JioSaavnStreamProvider({
      fetchFn: createMockFetch({ throwError: 'Connection refused', errorName: 'TimeoutError' }),
    });

    assert.equal(provider.circuitState, 'CLOSED');

    for (let i = 0; i < CIRCUIT_FAILURE_THRESHOLD; i++) {
      await provider.resolve(target);
    }

    assert.equal(provider.circuitState, 'OPEN');

    // While OPEN, provider bypasses immediately without calling fetch
    let fetchCalledWhileOpen = false;
    provider.fetch = () => { fetchCalledWhileOpen = true; };
    const resWhileOpen = await provider.resolve(target);
    assert.equal(resWhileOpen, null);
    assert.equal(fetchCalledWhileOpen, false);

    // Simulate reset timeout expiry
    provider.circuitOpenedAt = Date.now() - 6 * 60 * 1000;
    // Next request transitions to HALF-OPEN probe
    provider.fetch = createMockFetch();
    const probeRes = await provider.resolve(target);
    assert.ok(probeRes);
    assert.equal(provider.circuitState, 'CLOSED');
  });

  test('secrets never leak in returned ResolvedSource', async () => {
    const provider = new JioSaavnStreamProvider({ fetchFn: createMockFetch() });
    const res = await provider.resolve(target);
    const jsonString = JSON.stringify(res);

    assert.equal(jsonString.includes('38346591'), false);
    assert.equal(jsonString.includes('encrypted_media_url'), false);
  });
});
