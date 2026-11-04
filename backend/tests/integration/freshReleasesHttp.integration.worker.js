'use strict';

// This worker is deliberately a separate process for every scenario.  The
// discovery/feed/provider caches are module-local, so a fresh process is the
// smallest reliable way to test their HTTP-visible behaviour without reaching
// into production state.

const http = require('node:http');

const SCENARIO = process.argv[2];
const TIMEOUT_MS = 500;
const REQUEST_TIMEOUT_MS = 5_000;

function listen(server, host = '127.0.0.1') {
  return new Promise((resolve, reject) => {
    const onError = (err) => {
      server.removeListener('listening', onListening);
      reject(err);
    };
    const onListening = () => {
      server.removeListener('error', onError);
      resolve(server.address());
    };
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen(0, host);
  });
}

function closeServer(server) {
  if (!server) return Promise.resolve();
  if (typeof server.closeIdleConnections === 'function') server.closeIdleConnections();
  if (typeof server.closeAllConnections === 'function') server.closeAllConnections();

  return new Promise((resolve) => {
    let settled = false;
    const timer = setTimeout(() => {
      server.closeAllConnections();
      finish();
    }, 1_000);
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve();
    };
    server.close(finish);
  });
}

function jsonResponse(res, status, payload) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify(payload));
}

function makeSearchTrack() {
  return {
    videoId: 'search-track-2026',
    title: 'Search Result Song',
    artist: 'Search Artist',
    artists: [{ name: 'Search Artist', id: 'search-artist' }],
    album: 'Search Album',
    year: '2026',
    duration_seconds: 200,
    thumbnails: [{ url: 'https://images.invalid/search.jpg' }],
    resultType: 'song',
  };
}

function makeContainer() {
  return {
    browseId: 'MPREb_123456789',
    title: 'Fresh Single',
    type: 'Single',
    artist: 'Fresh Artist',
    artists: [{ name: 'Fresh Artist', id: 'fresh-artist' }],
    thumbnailUrl: 'https://images.invalid/fresh.jpg',
  };
}

function makePartialFailureContainers() {
  return [
    makeContainer(),
    {
      ...makeContainer(),
      browseId: 'MPREb_987654321',
      title: 'Second Fresh Single',
      artist: 'Second Fresh Artist',
      artists: [{ name: 'Second Fresh Artist', id: 'second-fresh-artist' }],
    },
  ];
}

function makeAlbumDetail(empty = false) {
  return {
    id: 'MPREb_123456789',
    title: 'Fresh Single',
    year: '2026',
    thumbnailUrl: 'https://images.invalid/fresh.jpg',
    tracks: empty ? [] : [{
      videoId: 'fresh-track-2026',
      title: 'Fresh Native Song',
      artists: [{ name: 'Fresh Artist', id: 'fresh-artist' }],
      duration_seconds: 210,
      thumbnails: null,
      isAvailable: true,
    }],
  };
}

function scenarioConfig(name) {
  const configs = {
    'valid-shelf-album': { shelf: 'valid', album: 'valid' },
    'shelf-503': { shelf: '503', album: 'valid' },
    'shelf-timeout': { shelf: 'timeout', album: 'valid' },
    'valid-empty-shelf': { shelf: 'empty', album: 'valid' },
    'malformed-shelf-json': { shelf: 'malformed', album: 'valid' },
    'album-500': { shelf: 'valid', album: '500' },
    'partial-album-500': { shelf: 'partial', album: 'partial-500' },
    'partial-album-500-fast': { shelf: 'partial', album: 'partial-500', slowSearch: true },
    'valid-empty-album': { shelf: 'valid', album: 'empty' },
    'shelf-recovery': { shelf: 'recovery', album: 'valid' },
  };
  if (!configs[name]) throw new Error(`Unknown scenario: ${name}`);
  return configs[name];
}

function createFixture(config) {
  const state = {
    searchCalls: 0,
    shelfCalls: 0,
    albumCalls: 0,
    requests: [],
    unexpected: [],
    nonLoopback: [],
    pendingTimers: new Set(),
  };

  const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://127.0.0.1').pathname;
    const remote = req.socket.remoteAddress;
    state.requests.push({ method: req.method, pathname });

    if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remote)) {
      state.nonLoopback.push({ remote, pathname });
    }

    const isSearch = req.method === 'GET' && pathname === '/api/search';
    const isShelf = req.method === 'GET' && pathname === '/api/explore/new-releases';
    const isAlbum = req.method === 'GET' && ['/api/album/MPREb_123456789', '/api/album/MPREb_987654321'].includes(pathname);
    if (!isSearch && !isShelf && !isAlbum) {
      state.unexpected.push({ method: req.method, pathname });
      return jsonResponse(res, 500, { error: 'unexpected fixture path' });
    }

    if (isSearch) {
      state.searchCalls++;
      if (config.slowSearch) {
        const timer = setTimeout(() => {
          state.pendingTimers.delete(timer);
          jsonResponse(res, 200, [makeSearchTrack()]);
        }, 150);
        state.pendingTimers.add(timer);
        return;
      }
      return jsonResponse(res, 200, [makeSearchTrack()]);
    }

    if (isShelf) {
      state.shelfCalls++;
      if (config.shelf === 'timeout' && state.shelfCalls === 1) {
        // Do not answer before the Node provider's AbortSignal timeout. The
        // close/abort handlers and bounded cleanup keep this socket harmless.
        const timer = setTimeout(() => {
          state.pendingTimers.delete(timer);
          if (!res.writableEnded) jsonResponse(res, 200, { containers: [] });
        }, TIMEOUT_MS * 4);
        state.pendingTimers.add(timer);
        req.on('aborted', () => {
          clearTimeout(timer);
          state.pendingTimers.delete(timer);
        });
        res.on('close', () => {
          clearTimeout(timer);
          state.pendingTimers.delete(timer);
        });
        return;
      }
      if (config.shelf === '503' || (config.shelf === 'recovery' && state.shelfCalls === 1)) {
        return jsonResponse(res, 503, { error: 'temporary fixture failure' });
      }
      if (config.shelf === 'malformed') {
        res.statusCode = 200;
        res.setHeader('content-type', 'application/json');
        return res.end('{"containers":');
      }
      if (config.shelf === 'empty') return jsonResponse(res, 200, { success: true, containers: [] });
      if (config.shelf === 'partial') return jsonResponse(res, 200, { success: true, containers: makePartialFailureContainers() });
      return jsonResponse(res, 200, { success: true, containers: [makeContainer()] });
    }

    state.albumCalls++;
    if (config.album === '500') return jsonResponse(res, 500, { error: 'temporary album failure' });
    if (config.album === 'partial-500' && pathname.endsWith('987654321')) {
      return jsonResponse(res, 500, { error: 'temporary album failure' });
    }
    return jsonResponse(res, 200, makeAlbumDetail(config.album === 'empty'));
  });

  return { server, state };
}

async function requestJson(baseUrl, path) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${baseUrl}${path}`, { signal: controller.signal });
    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
    return { status: response.status, body };
  } finally {
    clearTimeout(timer);
  }
}

function assertRouteContract(result) {
  if (result.status !== 200) throw new Error(`expected route HTTP 200, got ${result.status}`);
  const body = result.body;
  if (!body || typeof body !== 'object') throw new Error('route did not return an object');
  for (const key of ['id', 'name', 'description', 'coverImage', 'tracks']) {
    if (!Object.prototype.hasOwnProperty.call(body, key)) throw new Error(`route missing ${key}`);
  }
  if (!Array.isArray(body.tracks)) throw new Error('route tracks is not an array');
  for (const track of body.tracks) {
    for (const key of ['id', 'videoId', 'title', 'artist', 'duration', 'artworkUrl']) {
      if (!Object.prototype.hasOwnProperty.call(track, key)) throw new Error(`track missing ${key}`);
    }
  }
  return body;
}

async function run() {
  const config = scenarioConfig(SCENARIO);
  const fixture = createFixture(config);
  const fixtureAddress = await listen(fixture.server);
  const fixtureUrl = `http://127.0.0.1:${fixtureAddress.port}`;
  let appServer;
  let originalDateNow;
  const nativeFetch = globalThis.fetch;
  const allowedOrigins = new Set([fixtureUrl]);
  const outbound = [];
  const blocked = [];
  // Safety guard only: allowed calls still use Node's REAL native fetch and TCP.
  // No redirects, proxying, DNS names or external destinations are permitted.
  globalThis.fetch = (input, options) => {
    const url = new URL(typeof input === 'string' ? input : input.url || String(input));
    if (!allowedOrigins.has(url.origin)) {
      blocked.push(url.origin);
      throw new Error('Integration test blocked a non-fixture destination');
    }
    outbound.push(url.href);
    return nativeFetch(input, { ...options, redirect: 'error' });
  };

  try {
    process.env.NODE_ENV = 'development';
    process.env.PYTHON_YTMUSIC_URL = fixtureUrl;
    process.env.YTMUSIC_TIMEOUT_MS = String(TIMEOUT_MS);
    process.env.PORT = '0';

    // Mount the real router only; avoid unrelated gateway initialization and stores.
    const app = require('express')();
    app.use('/api/v1/playlists', require('../../src/routes/playlists'));
    const feed = require('../../src/discovery/discoveryFeedService');
    const nativeResults = [];
    const realGetNative = feed.DiscoveryFeedService.getNewReleaseTracks;
    feed.DiscoveryFeedService.getNewReleaseTracks = async function (...args) {
      const tracks = await realGetNative.apply(this, args);
      nativeResults.push({ count: tracks.length, failed: tracks.sourceFailed === true });
      return tracks;
    };
    appServer = app.listen(0, '127.0.0.1');
    await new Promise((resolve, reject) => {
      appServer.once('listening', resolve);
      appServer.once('error', reject);
    });
    const address = appServer.address();
    const apiUrl = `http://127.0.0.1:${address.port}`;
    allowedOrigins.add(apiUrl);

    const responses = [];
    const cacheStates = [];
    const recordResponse = async () => {
      const result = await requestJson(apiUrl, '/api/v1/playlists/fresh_releases');
      responses.push({ status: result.status, body: assertRouteContract(result) });
      cacheStates.push({ feed: feed._feedCache.size, album: feed._albumCache.size });
    };
    await recordResponse();

    if (SCENARIO === 'shelf-recovery') {
      // PlaylistService intentionally backs off after an unusable partial. Move
      // the logical clock beyond that cadence to prove a later request retries
      // the fixture and recovers, without waiting two real days.
      originalDateNow = Date.now;
      Date.now = () => originalDateNow() + (3 * 24 * 60 * 60 * 1000);
      await recordResponse();
      Date.now = originalDateNow;
    } else {
      await recordResponse();
    }

    if (fixture.state.unexpected.length > 0) throw new Error(`unexpected fixture requests: ${JSON.stringify(fixture.state.unexpected)}`);
    if (fixture.state.nonLoopback.length > 0) throw new Error(`non-loopback fixture requests: ${JSON.stringify(fixture.state.nonLoopback)}`);
    if (blocked.length) throw new Error('Attempted non-fixture network request');

    process.stdout.write(`__RESULT__${JSON.stringify({
      scenario: SCENARIO,
      responses,
      counters: {
        search: fixture.state.searchCalls,
        shelf: fixture.state.shelfCalls,
        album: fixture.state.albumCalls,
      },
      requests: fixture.state.requests,
      nativeResults,
      cacheStates,
      outbound,
      allowedOrigins: [...allowedOrigins],
    })}\n`);
  } finally {
    if (originalDateNow) Date.now = originalDateNow;
    for (const timer of fixture.state.pendingTimers) clearTimeout(timer);
    await closeServer(appServer);
    await closeServer(fixture.server);
    globalThis.fetch = nativeFetch;
  }
}

run().catch((err) => {
  process.stderr.write(`${err.stack || err}\n`);
  process.exitCode = 1;
});
