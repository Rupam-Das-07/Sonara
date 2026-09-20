'use strict';

/**
 * curatedPlaylistsUpdater.test.js — unit tests for the Curated Playlists updater.
 *
 * Hermetic: the frozen PlaylistDefinitions and PlaylistService are replaced by
 * injected fakes. The QA-metrics fake emits a log line in the EXACT shape the
 * real logger produces (`console.log(JSON.stringify({ts, level, message,
 * ...qaMetrics}))`) so we can prove the updater REUSES the service's own
 * numbers rather than recomputing them. No network, no real modules.
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const upd = require('../../src/operations/curatedPlaylistsUpdater');

// ── Fixtures ─────────────────────────────────────────────────────────────────

/**
 * Builds a fake PlaylistDefinitions.
 * @param {object[]} defs
 */
function makeDefinitions(defs) {
  const byId = new Map(defs.map((d) => [d.id, d]));
  return {
    getAllDefinitions: () => defs,
    getDefinitionById: (id) => byId.get(id) || null,
    PLAYLIST_DEFINITIONS: Object.fromEntries(defs.map((d) => [d.id, d])),
    ARTIST_PLAYLIST_DEFINITIONS: { 'artist-x': { id: 'artist-x', name: 'Artist X', size: 10 } },
  };
}

/**
 * Builds a fake PlaylistService with call tracking and observable concurrency.
 * @param {object} perId  map id -> { tracks?, throws?, qa? }
 */
function makeService(perId = {}) {
  const calls = { generate: [] };
  let inFlight = 0;
  let maxInFlight = 0;
  return {
    calls,
    getMaxInFlight: () => maxInFlight,
    async generateCuratedPlaylist(id) {
      calls.generate.push(id);
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setImmediate(r)); // yield so batching is observable
      try {
        const spec = perId[id] || {};
        if (spec.throws) throw spec.throws;
        if (spec.qa) {
          // Emit a QA line EXACTLY like the frozen logger would.
          const q = spec.qa;
          const underfilled = q.resolvedCount < q.targetSize;
          console.log(
            JSON.stringify({
              ts: '2026-09-08T00:00:00.000Z',
              level: underfilled ? 'warn' : 'info',
              message: `[PlaylistService:QA] Playlist "${id}" ${underfilled ? 'under-filled' : 'filled'}`,
              playlistId: id,
              targetSize: q.targetSize,
              resolvedCount: q.resolvedCount,
              fillPercentage: `${Math.round((q.resolvedCount / q.targetSize) * 100)}%`,
              uniquePrimaryArtists: q.uniquePrimaryArtists,
              candidatesEvaluated: q.candidatesEvaluated,
              rejections: q.rejections,
            })
          );
        }
        return spec.tracks || [];
      } finally {
        inFlight--;
      }
    },
  };
}

const NOW = () => 1000;

/**
 * Runs an async fn with console.* swapped for a silent collector, so QA/log
 * forwarding is captured (and verifiable) instead of cluttering test output.
 * @param {function} fn
 * @returns {Promise<{val:any, captured:string[]}>}
 */
async function runQuiet(fn) {
  const orig = { log: console.log, error: console.error, info: console.info, warn: console.warn, debug: console.debug };
  const captured = [];
  const sink = (...a) => { captured.push(a.map(String).join(' ')); };
  console.log = sink; console.error = sink; console.info = sink; console.warn = sink; console.debug = sink;
  try {
    const val = await fn();
    return { val, captured };
  } finally {
    Object.assign(console, orig);
  }
}

const tracks = (n) => Array.from({ length: n }, (_, i) => ({ id: `t${i}` }));

// ── Pure helpers ─────────────────────────────────────────────────────────────

describe('clampConcurrency', () => {
  it('defaults invalid input to 2', () => {
    assert.equal(upd.clampConcurrency('nope'), 2);
    assert.equal(upd.clampConcurrency(0), 2);
    assert.equal(upd.clampConcurrency(-4), 2);
    assert.equal(upd.clampConcurrency(NaN), 2);
  });
  it('clamps above the max to 8', () => {
    assert.equal(upd.clampConcurrency(99), 8);
  });
  it('passes valid values through', () => {
    assert.equal(upd.clampConcurrency(5), 5);
    assert.equal(upd.clampConcurrency('3'), 3);
  });
});

describe('chunk', () => {
  it('splits with a remainder', () => {
    assert.deepEqual(upd.chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  });
  it('handles an empty array', () => {
    assert.deepEqual(upd.chunk([], 3), []);
  });
});

describe('createQaCapture', () => {
  it('captures QA lines, forwards everything, ignores non-QA', () => {
    const cap = upd.createQaCapture();
    const forwarded = [];
    const origLog = console.log;
    console.log = (...a) => forwarded.push(a.join(' '));
    try {
      cap.start();
      console.log(JSON.stringify({ level: 'info', message: '[PlaylistService:QA] Playlist "p1" filled', playlistId: 'p1', resolvedCount: 30, targetSize: 30 }));
      console.log(JSON.stringify({ level: 'info', message: 'something unrelated' }));
      console.log('not json at all');
      cap.stop();
    } finally {
      console.log = origLog;
    }
    assert.equal(cap.captured.length, 1);
    assert.equal(cap.captured[0].playlistId, 'p1');
    assert.equal(forwarded.length, 3, 'all three lines forwarded, none swallowed');
  });
});

describe('listValidIds', () => {
  it('gathers ids from both frozen catalogs', () => {
    const defs = makeDefinitions([{ id: 'a', name: 'A', size: 10 }, { id: 'b', name: 'B', size: 10 }]);
    const ids = upd.listValidIds(defs);
    assert.ok(ids.includes('a') && ids.includes('b') && ids.includes('artist-x'));
  });
});

// ── Dry-run ──────────────────────────────────────────────────────────────────

describe('updateCuratedPlaylists — dry-run', () => {
  it('performs NO generation and reports the plan + mutation finding', async () => {
    const definitions = makeDefinitions([{ id: 'a', name: 'A', size: 30 }, { id: 'b', name: 'B', size: 20 }]);
    const service = makeService();
    const report = await upd.updateCuratedPlaylists({ dryRun: true, definitions, service, now: NOW });

    assert.equal(report.mode, 'dry-run');
    assert.equal(report.performedGeneration, false);
    assert.equal(report.ok, true);
    assert.equal(service.calls.generate.length, 0, 'dry-run must not call generateCuratedPlaylist');
    assert.equal(report.targetCount, 2);
    assert.match(report.mutationFinding, /mutates the in-memory _playlistCache|NO generation/);
    assert.ok(report.caveats.length >= 4);
  });
});

// ── Live orchestration ─────────────────────────────────────────────────────

describe('updateCuratedPlaylists — live', () => {
  it('REUSES the service-computed QA metrics (does not recompute)', async () => {
    const definitions = makeDefinitions([{ id: 'a', name: 'A', size: 30 }]);
    const service = makeService({
      a: { tracks: tracks(25), qa: { targetSize: 30, resolvedCount: 25, uniquePrimaryArtists: 21, candidatesEvaluated: 140, rejections: { ineligible: 12 } } },
    });

    const { val: report, captured } = await runQuiet(() =>
      upd.updateCuratedPlaylists({ definitions, service, concurrency: 2, now: NOW })
    );

    const r = report.results[0];
    assert.equal(r.qaSource, 'service');
    assert.equal(r.resolvedCount, 25);
    assert.equal(r.qaMetrics.uniquePrimaryArtists, 21, 'reused verbatim from service log');
    assert.equal(r.qaMetrics.candidatesEvaluated, 140);
    assert.deepEqual(r.qaMetrics.rejections, { ineligible: 12 });
    assert.equal(r.underfilled, true, '25 < 30');
    assert.ok(captured.some((l) => l.includes('[PlaylistService:QA]')), 'QA log forwarded, not swallowed');
    // Underfill alone does not fail the run.
    assert.equal(report.ok, true);
    assert.equal(report.totals.underfilled, 1);
    assert.equal(report.totals.failed, 0);
  });

  it('derives grounded metrics when no QA log is emitted (marks qaSource=derived)', async () => {
    const definitions = makeDefinitions([{ id: 'a', name: 'A', size: 30 }]);
    const service = makeService({ a: { tracks: tracks(30) } }); // no qa spec → no QA log
    const { val: report } = await runQuiet(() =>
      upd.updateCuratedPlaylists({ definitions, service, now: NOW })
    );
    const r = report.results[0];
    assert.equal(r.qaSource, 'derived');
    assert.equal(r.resolvedCount, 30);
    assert.equal(r.underfilled, false, '30 == 30 is not underfilled');
    assert.ok(!('qaMetrics' in r), 'no fabricated internal metrics');
    assert.match(r.qaNote, /not fabricated/);
  });

  it('isolates a single playlist failure and still generates the rest', async () => {
    const definitions = makeDefinitions([
      { id: 'ok1', name: 'OK1', size: 10 },
      { id: 'bad', name: 'BAD', size: 10 },
      { id: 'ok2', name: 'OK2', size: 10 },
    ]);
    const service = makeService({
      ok1: { tracks: tracks(10) },
      bad: { throws: new Error('generation blew up') },
      ok2: { tracks: tracks(10) },
    });
    const { val: report } = await runQuiet(() =>
      upd.updateCuratedPlaylists({ definitions, service, concurrency: 2, now: NOW })
    );

    assert.equal(service.calls.generate.length, 3, 'all three attempted despite one failing');
    assert.equal(report.totals.succeeded, 2);
    assert.equal(report.totals.failed, 1);
    assert.equal(report.ok, false, 'a failed playlist makes the run not-ok');
    const bad = report.results.find((r) => r.id === 'bad');
    assert.equal(bad.ok, false);
    assert.match(bad.error, /blew up/);
  });

  it('bounds concurrency with native batching', async () => {
    const definitions = makeDefinitions([
      { id: 'a', name: 'A', size: 10 },
      { id: 'b', name: 'B', size: 10 },
      { id: 'c', name: 'C', size: 10 },
      { id: 'd', name: 'D', size: 10 },
    ]);
    const service = makeService({
      a: { tracks: tracks(10) }, b: { tracks: tracks(10) }, c: { tracks: tracks(10) }, d: { tracks: tracks(10) },
    });
    const { val: report } = await runQuiet(() =>
      upd.updateCuratedPlaylists({ definitions, service, concurrency: 2, now: NOW })
    );
    assert.ok(service.getMaxInFlight() <= 2, `max in-flight ${service.getMaxInFlight()} must be <= 2`);
    assert.equal(report.totals.playlists, 4);
    assert.equal(report.totals.succeeded, 4);
    assert.equal(report.concurrency, 2);
  });

  it('targets a single validated playlist when --playlist is given', async () => {
    const definitions = makeDefinitions([{ id: 'a', name: 'A', size: 10 }, { id: 'b', name: 'B', size: 10 }]);
    const service = makeService({ a: { tracks: tracks(10) }, b: { tracks: tracks(10) } });
    const { val: report } = await runQuiet(() =>
      upd.updateCuratedPlaylists({ playlistId: 'a', definitions, service, now: NOW })
    );
    assert.deepEqual(service.calls.generate, ['a']);
    assert.equal(report.totals.playlists, 1);
  });

  it('rejects an unknown playlist id without generating', async () => {
    const definitions = makeDefinitions([{ id: 'a', name: 'A', size: 10 }]);
    const service = makeService({ a: { tracks: tracks(10) } });
    const report = await upd.updateCuratedPlaylists({ playlistId: 'nope', definitions, service, now: NOW });
    assert.equal(report.ok, false);
    assert.match(report.error, /Unknown playlist id: nope/);
    assert.ok(report.validIds.includes('a'));
    assert.equal(service.calls.generate.length, 0);
  });
});
