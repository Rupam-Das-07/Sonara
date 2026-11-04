'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');

const WORKER = path.join(__dirname, 'freshReleasesHttp.integration.worker.js');

function runWorker(scenario) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [WORKER, scenario], {
      cwd: path.resolve(__dirname, '../..'),
      env: { ...process.env, NODE_ENV: 'development' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    let settled = false;
    let timedOut = false;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      fn(value);
    };

    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', (err) => finish(reject, err));
    child.once('close', (code, signal) => {
      if (timedOut) {
        finish(reject, new Error(`${scenario} worker exceeded timeout and was killed`));
        return;
      }
      const line = stdout.split(/\r?\n/).find((entry) => entry.startsWith('__RESULT__'));
      if (code !== 0 || !line) {
        finish(reject, new Error(`${scenario} worker failed (code=${code}, signal=${signal})\n${stderr}\n${stdout}`));
        return;
      }
      try {
        finish(resolve, JSON.parse(line.slice('__RESULT__'.length)));
      } catch (err) {
        finish(reject, new Error(`${scenario} worker returned invalid JSON: ${err.message}\n${stdout}`));
      }
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, 10_000);
    timeout.unref();
    child.once('close', () => clearTimeout(timeout));
  });
}

function assertResponseShape(result) {
  assert.equal(result.status, 200);
  assert.deepEqual(Object.keys(result.body).sort(), ['coverImage', 'description', 'id', 'name', 'tracks']);
  assert.equal(result.body.id, 'fresh_releases');
  assert.equal(typeof result.body.name, 'string');
  assert.equal(typeof result.body.description, 'string');
  assert.ok(Array.isArray(result.body.tracks));
  assert.ok(result.body.coverImage === null || typeof result.body.coverImage === 'string');
  for (const track of result.body.tracks) {
    for (const field of ['id', 'videoId', 'title', 'artist', 'duration', 'artworkUrl']) {
      assert.ok(Object.prototype.hasOwnProperty.call(track, field), `track should include ${field}`);
    }
    assert.equal(track.id, track.videoId);
    for (const field of ['id', 'title', 'artist', 'artworkUrl']) assert.equal(typeof track[field], 'string');
    assert.ok(track.duration >= 90 && track.duration <= 480);
  }
}

async function runScenario(scenario) {
  const result = await runWorker(scenario);
  assert.equal(result.scenario, scenario);
  assert.equal(result.responses.length, 2);
  result.responses.forEach(assertResponseShape);
  assert.ok(result.requests.every((request) => request.method === 'GET'));
  assert.ok(result.requests.every((request) => (
    request.pathname === '/api/search'
    || request.pathname === '/api/explore/new-releases'
    || request.pathname === '/api/album/MPREb_123456789'
    || request.pathname === '/api/album/MPREb_987654321'
  )));
  assert.ok(result.outbound.every((url) => result.allowedOrigins.includes(new URL(url).origin)));
  assert.ok(result.allowedOrigins.every((origin) => new URL(origin).hostname === '127.0.0.1'));
  const failed = ['shelf-503', 'shelf-timeout', 'malformed-shelf-json', 'album-500',
    'shelf-recovery', 'partial-album-500', 'partial-album-500-fast'].includes(scenario);
  assert.equal(result.nativeResults[0].failed, failed, 'real feed source-health classification');
  if (scenario !== 'shelf-recovery') assert.equal(result.nativeResults.length, 1, 'repeat must use cache or backoff');
  const valid = scenario === 'valid-shelf-album';
  assert.equal(result.cacheStates[0].feed, valid ? 1 : 0, 'failed or empty native feeds are not cached');
  assert.equal(result.cacheStates[0].album, valid || scenario.startsWith('partial-') ? 1 : 0);
  return result;
}

test('Fresh Releases HTTP integration: valid shelf and album are resolved and cached', async () => {
  const result = await runScenario('valid-shelf-album');
  assert.equal(result.responses[0].body.tracks.length, 2);
  assert.equal(result.responses[1].body.tracks.length, 2);
  assert.deepEqual(result.responses[0].body.tracks.map((track) => track.videoId), ['fresh-track-2026', 'search-track-2026']);
  assert.deepEqual(result.counters, { search: 3, shelf: 1, album: 1 });
});

test('Fresh Releases HTTP integration: shelf 503 is unhealthy and underfilled output is not cached', async () => {
  const result = await runScenario('shelf-503');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 0);
  assert.equal(result.counters.shelf, 1);
  assert.equal(result.counters.album, 0);
});

test('Fresh Releases HTTP integration: shelf timeout is bounded and unhealthy', async () => {
  const result = await runScenario('shelf-timeout');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 0);
  assert.equal(result.counters.shelf, 1);
  assert.equal(result.counters.album, 0);
});

test('Fresh Releases HTTP integration: valid empty shelf caches the intentional smaller result', async () => {
  const result = await runScenario('valid-empty-shelf');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 1);
  assert.deepEqual(result.counters, { search: 3, shelf: 1, album: 0 });
});

test('Fresh Releases HTTP integration: malformed shelf JSON is unhealthy', async () => {
  const result = await runScenario('malformed-shelf-json');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 0);
  assert.equal(result.counters.shelf, 1);
  assert.equal(result.counters.album, 0);
});

test('Fresh Releases HTTP integration: album 500 marks native enrichment unhealthy', async () => {
  const result = await runScenario('album-500');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 0);
  assert.equal(result.counters.shelf, 1);
  assert.equal(result.counters.album, 1);
});

test('Fresh Releases HTTP integration: valid empty album caches the intentional smaller result', async () => {
  const result = await runScenario('valid-empty-album');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 1);
  assert.deepEqual(result.counters, { search: 3, shelf: 1, album: 1 });
});

test('Fresh Releases HTTP integration: temporary shelf failure recovers on a later request', async () => {
  const result = await runScenario('shelf-recovery');
  assert.equal(result.responses[0].body.tracks.length, 1);
  assert.equal(result.responses[1].body.tracks.length, 2);
  assert.deepEqual(result.counters, { search: 6, shelf: 2, album: 1 });
  assert.ok(result.responses[1].body.tracks.some((track) => track.videoId === 'fresh-track-2026'));
  assert.equal(result.nativeResults[1].failed, false);
  assert.deepEqual(result.cacheStates[1], { feed: 1, album: 1 });
});

for (const scenario of ['partial-album-500', 'partial-album-500-fast']) {
  test(`Fresh Releases HTTP integration: ${scenario} preserves mixed native failure health`, async () => {
    const result = await runScenario(scenario);
    assert.equal(result.responses[0].body.tracks.length, 2);
    assert.equal(result.responses[1].body.tracks.length, 0, 'unhealthy partial must not become healthy snapshot');
    assert.deepEqual(result.counters, { search: 3, shelf: 1, album: 2 });
  });
}
