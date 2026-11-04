'use strict';

const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const config = require('../../src/config/env');

// The production route is intentionally required by contract. These tests do
// not start a server or contact YouTube: every upstream call goes through the
// native global fetch mock below.
const videosRouter = require('../../src/routes/videos');

function getStatusHandler() {
  const layer = videosRouter.stack.find(
    (entry) => entry.route && entry.route.path === '/:videoId/status' && entry.route.methods.get,
  );
  if (!layer) throw new Error('GET /:videoId/status route is not registered');
  return layer.route.stack[layer.route.stack.length - 1].handle;
}

function callStatus(videoId) {
  const handler = getStatusHandler();
  let settle;
  const done = new Promise((resolve) => { settle = resolve; });
  let status = 200;
  const req = { params: { videoId } };
  const res = {
    status(code) { status = code; return this; },
    json(data) { settle({ status, data }); return this; },
  };
  const next = (error) => settle({
    status: error?.status || status,
    data: typeof error?.toJSON === 'function' ? error.toJSON() : undefined,
    error,
  });
  handler(req, res, next);
  return done;
}

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}

const VIDEO_ID = 'dQw4w9WgXcQ';
const OTHER_VIDEO_ID = 'M7lc1UVf-VE';

function mockResponse(body, status = 200) {
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    return response(status, body);
  };
}

function assertRequest(videoId = VIDEO_ID) {
  assert.equal(calls.length, 1, 'status verification must make exactly one upstream request');
  const upstreamUrl = new URL(calls[0].url);
  assert.equal(upstreamUrl.origin, 'https://www.googleapis.com');
  assert.equal(upstreamUrl.pathname, '/youtube/v3/videos');
  assert.equal(upstreamUrl.hash, '');
  assert.deepEqual([...upstreamUrl.searchParams.entries()].sort(), [
    ['id', videoId],
    ['key', 'test-youtube-key'],
    ['part', 'id,status'],
  ]);
  assert.equal(calls[0].options?.method, 'GET');
}

function assertFailure(result, status, code) {
  assert.equal(result.status, status);
  assert.equal(result.data.error.code, code);
  for (const field of ['madeForKidsStatus', 'embeddabilityStatus']) {
    assert.equal(Object.hasOwn(result.data, field), false, `failure must omit ${field}`);
    assert.equal(Object.hasOwn(result.data.error, field), false, `error must omit ${field}`);
  }
  assert.doesNotMatch(JSON.stringify(result), /test-youtube-key/);
  assert.doesNotMatch(logs.join('\n'), /test-youtube-key/);
}

const originalFetch = globalThis.fetch;
const originalKey = process.env.YOUTUBE_DATA_API_KEY;
const originalConfigKey = config.youtubeDataApiKey;
const originalConsole = {
  log: console.log,
  warn: console.warn,
  error: console.error,
};

let calls;
let logs;

beforeEach(() => {
  process.env.YOUTUBE_DATA_API_KEY = 'test-youtube-key';
  config.youtubeDataApiKey = 'test-youtube-key';
  calls = [];
  logs = [];
  for (const method of Object.keys(originalConsole)) {
    console[method] = (...args) => logs.push(args.map(String).join(' '));
  }
  mockResponse({ items: [{ id: VIDEO_ID, status: { madeForKids: true, embeddable: true } }] });
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  for (const method of Object.keys(originalConsole)) console[method] = originalConsole[method];
  if (originalKey === undefined) delete process.env.YOUTUBE_DATA_API_KEY;
  else process.env.YOUTUBE_DATA_API_KEY = originalKey;
  config.youtubeDataApiKey = originalConfigKey;
});

describe('GET /api/v1/videos/:videoId/status', () => {
  for (const madeForKids of [true, false]) {
    for (const embeddable of [true, false]) {
      test(`independently maps madeForKids=${madeForKids} and embeddable=${embeddable}`, async () => {
        mockResponse({ items: [{ id: VIDEO_ID, status: { madeForKids, embeddable } }] });
        const result = await callStatus(VIDEO_ID);
        assert.equal(result.status, 200);
        assert.deepEqual(result.data, {
          videoId: VIDEO_ID,
          madeForKidsStatus: madeForKids ? 'MADE_FOR_KIDS' : 'NOT_MADE_FOR_KIDS',
          embeddabilityStatus: embeddable ? 'EMBEDDABLE' : 'NOT_EMBEDDABLE',
        });
        assertRequest();
        assert.doesNotMatch(JSON.stringify(result), /test-youtube-key/);
        assert.doesNotMatch(logs.join('\n'), /test-youtube-key/);
      });
    }
  }

  const unknownValues = [
    ['missing', undefined],
    ['null', null],
    ['string true', 'true'],
    ['string false', 'false'],
    ['empty string', ''],
    ['zero', 0],
    ['one', 1],
    ['object', {}],
    ['array', []],
  ];

  for (const field of ['madeForKids', 'embeddable']) {
    for (const [label, value] of unknownValues) {
      for (const otherValue of [true, false]) {
        test(`maps ${field}=${label} to UNKNOWN independently of the other field=${otherValue}`, async () => {
          const otherField = field === 'madeForKids' ? 'embeddable' : 'madeForKids';
          const status = { [otherField]: otherValue };
          if (value !== undefined) status[field] = value;
          mockResponse({ items: [{ id: VIDEO_ID, status }] });
          const result = await callStatus(VIDEO_ID);
          assert.equal(result.status, 200);
          assert.deepEqual(result.data, {
            videoId: VIDEO_ID,
            madeForKidsStatus: field === 'madeForKids'
              ? 'UNKNOWN' : (otherValue ? 'MADE_FOR_KIDS' : 'NOT_MADE_FOR_KIDS'),
            embeddabilityStatus: field === 'embeddable'
              ? 'UNKNOWN' : (otherValue ? 'EMBEDDABLE' : 'NOT_EMBEDDABLE'),
          });
          assertRequest();
        });
      }
    }
  }

  for (const [label, item] of [
    ['missing status', { id: VIDEO_ID }],
    ['null status', { id: VIDEO_ID, status: null }],
    ['empty status', { id: VIDEO_ID, status: {} }],
    ['both nonboolean fields', { id: VIDEO_ID, status: { madeForKids: 'false', embeddable: 'true' } }],
  ]) {
    test(`returns both statuses as UNKNOWN for ${label}`, async () => {
      mockResponse({ items: [item] });
      const result = await callStatus(VIDEO_ID);
      assert.equal(result.status, 200);
      assert.deepEqual(result.data, {
        videoId: VIDEO_ID,
        madeForKidsStatus: 'UNKNOWN',
        embeddabilityStatus: 'UNKNOWN',
      });
      assertRequest();
    });
  }

  test('never upgrades an unknown result to NOT_MADE_FOR_KIDS', async () => {
    mockResponse({ items: [{ id: VIDEO_ID, status: { selfDeclaredMadeForKids: false, embeddable: true } }] });
    const result = await callStatus(VIDEO_ID);
    assert.equal(result.status, 200);
    assert.deepEqual(result.data, {
      videoId: VIDEO_ID,
      madeForKidsStatus: 'UNKNOWN',
      embeddabilityStatus: 'EMBEDDABLE',
    });
    assertRequest();
  });

  test('maps an empty items array to the sanitized not-found error', async () => {
    mockResponse({ items: [] });
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 404, 'YOUTUBE_VIDEO_NOT_FOUND');
    assert.match(result.data.error.message, /not found/i);
    assertRequest();
  });

  for (const status of [{ madeForKids: true, embeddable: false }, { madeForKids: false, embeddable: true }]) {
    test(`does not use a nonmatching video's status ${JSON.stringify(status)}`, async () => {
      mockResponse({ items: [{ id: OTHER_VIDEO_ID, status }] });
      const result = await callStatus(VIDEO_ID);
      assertFailure(result, 404, 'YOUTUBE_VIDEO_NOT_FOUND');
      assertRequest();
    });
  }

  test('uses only the matching video when another video comes first', async () => {
    mockResponse({ items: [
      { id: VIDEO_ID, status: { madeForKids: true, embeddable: false } },
      { id: OTHER_VIDEO_ID, status: { madeForKids: false, embeddable: true } },
    ] });
    const result = await callStatus(OTHER_VIDEO_ID);
    assert.equal(result.status, 200);
    assert.deepEqual(result.data, {
      videoId: OTHER_VIDEO_ID,
      madeForKidsStatus: 'NOT_MADE_FOR_KIDS',
      embeddabilityStatus: 'EMBEDDABLE',
    });
    assertRequest(OTHER_VIDEO_ID);
  });

  for (const [label, body] of [
    ['null body', null],
    ['string body', 'unexpected'],
    ['array body', []],
    ['missing items', {}],
    ['null items', { items: null }],
    ['object items', { items: {} }],
    ['string items', { items: 'unexpected' }],
    ['null item', { items: [null] }],
    ['string item', { items: ['unexpected'] }],
    ['number item', { items: [1] }],
    ['boolean item', { items: [false] }],
    ['array item', { items: [[]] }],
    ['missing item id', { items: [{ status: { madeForKids: true, embeddable: true } }] }],
    ['null item id', { items: [{ id: null }] }],
    ['number item id', { items: [{ id: 123 }] }],
    ['object item id', { items: [{ id: {} }] }],
    ['empty item id', { items: [{ id: '' }] }],
    ['malformed entry before a matching item', { items: [null, { id: VIDEO_ID }] }],
    ['malformed entry after a matching item', { items: [{ id: VIDEO_ID }, null] }],
    ['malformed entry after a nonmatching item', { items: [{ id: OTHER_VIDEO_ID }, {}] }],
    ['duplicate matching IDs', { items: [
      { id: VIDEO_ID, status: { madeForKids: false, embeddable: true } },
      { id: VIDEO_ID, status: { madeForKids: true, embeddable: false } },
    ] }],
  ]) {
    test(`rejects ${label} as a malformed upstream response`, async () => {
      mockResponse(body);
      const result = await callStatus(VIDEO_ID);
      assertFailure(result, 502, 'YOUTUBE_MALFORMED_RESPONSE');
      assertRequest();
    });
  }

  test('maps malformed upstream JSON to provider failure', async () => {
    globalThis.fetch = async (...args) => {
      calls.push({ url: String(args[0]), options: args[1] });
      return { ok: true, status: 200, json: async () => { throw new SyntaxError('invalid json'); } };
    };
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 502, 'YOUTUBE_MALFORMED_RESPONSE');
    assertRequest();
  });

  test('does not fetch when the API key is missing', async () => {
    delete process.env.YOUTUBE_DATA_API_KEY;
    config.youtubeDataApiKey = '';
    const result = await callStatus('vid00000008');
    assert.equal(calls.length, 0);
    assertFailure(result, 503, 'YOUTUBE_CONFIG_MISSING');
  });

  test('does not fetch for an invalid video id', async () => {
    for (const videoId of ['', 'short', 'contains spaces', 'a'.repeat(12)]) {
      const result = await callStatus(videoId);
      assertFailure(result, 400, 'INVALID_VIDEO_ID');
    }
    assert.equal(calls.length, 0);
  });

  test('maps quotaExceeded 403 to rate limited without exposing upstream details', async () => {
    mockResponse({
      error: {
        code: 403,
        message: 'quotaExceeded: test-youtube-key',
        errors: [{ reason: 'quotaExceeded' }],
      },
    }, 403);
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 429, 'YOUTUBE_QUOTA_EXCEEDED');
    assertRequest();
  });

  for (const status of [403, 500, 502, 503]) {
    test(`maps generic upstream ${status} to provider failure`, async () => {
      mockResponse({ error: { message: 'upstream failure: test-youtube-key' } }, status);
      const result = await callStatus(VIDEO_ID);
      assertFailure(result, 502, 'YOUTUBE_UPSTREAM_FAILURE');
      assertRequest();
    });
  }

  test('maps a generic upstream 429 to a sanitized rate-limit error', async () => {
    mockResponse({ error: { message: 'too many requests: test-youtube-key' } }, 429);
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 429, 'YOUTUBE_RATE_LIMITED');
    assertRequest();
  });

  test('maps a network timeout to a sanitized timeout error', async () => {
    globalThis.fetch = async (...args) => {
      calls.push({ url: String(args[0]), options: args[1] });
      throw Object.assign(new Error('The operation timed out: test-youtube-key'), { name: 'TimeoutError' });
    };
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 504, 'YOUTUBE_TIMEOUT');
    assertRequest();
  });

  test('maps a network rejection to a sanitized network error', async () => {
    globalThis.fetch = async (...args) => {
      calls.push({ url: String(args[0]), options: args[1] });
      throw new TypeError('fetch failed: https://www.googleapis.com/youtube/v3/videos?key=test-youtube-key');
    };
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 502, 'YOUTUBE_NETWORK_ERROR');
    assertRequest();
  });

  test('sends the key upstream but never includes credentials in response or logs', async () => {
    mockResponse({ error: { message: 'upstream unavailable: test-youtube-key' } }, 500);
    const result = await callStatus(VIDEO_ID);
    assertFailure(result, 502, 'YOUTUBE_UPSTREAM_FAILURE');
    assertRequest();
    assert.match(calls[0].url, /key=test-youtube-key/);
    assert.doesNotMatch(JSON.stringify(result), /test-youtube-key/);
    assert.doesNotMatch(logs.join('\n'), /test-youtube-key/);
  });
});
