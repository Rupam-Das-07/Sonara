'use strict';

/**
 * featuredArtistsUpdater.test.js — unit tests for the Featured Artists updater.
 *
 * Hermetic: the frozen featuredArtistsService is replaced by an injected fake
 * exposing getFeaturedArtists(), refreshWithLock(), and CORE_ANCHORS. No network,
 * no real module, no mutation of anything real.
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const upd = require('../../src/operations/featuredArtistsUpdater');

// ── Fixtures ─────────────────────────────────────────────────────────────────

const CORE_ANCHORS = [
  { name: 'Anchor One', browseId: 'UCanchor0000000000000001', genre: 'g', imageUrl: 'https://cdn/x' },
  { name: 'Anchor Two', browseId: 'UCanchor0000000000000002', genre: 'g', imageUrl: 'https://cdn/y' },
];

function anchorRoster() {
  return CORE_ANCHORS.map((a) => ({ id: a.name.toLowerCase().replace(/\s+/g, '-'), name: a.name, browseId: a.browseId, genre: a.genre, imageUrl: a.imageUrl }));
}

/**
 * Builds a fake service with call spies.
 * @param {object} opts
 */
function makeService({ before, after, refreshThrows } = {}) {
  const calls = { getFeatured: 0, refresh: 0 };
  return {
    calls,
    CORE_ANCHORS,
    async getFeaturedArtists() { calls.getFeatured++; return before || anchorRoster(); },
    async refreshWithLock() {
      calls.refresh++;
      if (refreshThrows) throw refreshThrows;
      return after || anchorRoster();
    },
  };
}

const NOW = () => 5_000;

// ── Pure helpers ─────────────────────────────────────────────────────────────

describe('summarizeRoster', () => {
  it('maps to compact fields and never leaks avatar URLs', () => {
    const out = upd.summarizeRoster([{ id: 'a', name: 'A', browseId: 'UCa', imageUrl: 'https://cdn/secretish' }]);
    assert.deepEqual(out, [{ id: 'a', name: 'A', browseId: 'UCa', hasImage: true }]);
    assert.ok(!JSON.stringify(out).includes('https://cdn/secretish'));
  });

  it('handles non-arrays', () => {
    assert.deepEqual(upd.summarizeRoster(null), []);
  });
});

describe('computeRosterDiff', () => {
  it('detects added, removed, and retained by id', () => {
    const before = [{ id: 'a', name: 'A', browseId: 'UCa' }, { id: 'b', name: 'B', browseId: 'UCb' }];
    const after = [{ id: 'b', name: 'B', browseId: 'UCb' }, { id: 'c', name: 'C', browseId: 'UCc' }];
    const diff = upd.computeRosterDiff(before, after);
    assert.deepEqual(diff.added.map((x) => x.id), ['c']);
    assert.deepEqual(diff.removed.map((x) => x.id), ['a']);
    assert.equal(diff.retainedCount, 1);
  });
});

describe('classifyPipelineOutcome', () => {
  it('is "refreshed" when a non-anchor artist is present', () => {
    const after = [...anchorRoster(), { id: 'dyn', name: 'Dyn', browseId: 'UCdynamic00000000000001' }];
    const r = upd.classifyPipelineOutcome(after, CORE_ANCHORS);
    assert.equal(r.outcome, 'refreshed');
    assert.equal(r.dynamicCount, 1);
  });

  it('is "fallback_or_empty" (ambiguous) when only anchors are present', () => {
    const r = upd.classifyPipelineOutcome(anchorRoster(), CORE_ANCHORS);
    assert.equal(r.outcome, 'fallback_or_empty');
    assert.equal(r.dynamicCount, 0);
    assert.match(r.note, /AMBIGUOUS/);
  });
});

// ── Orchestration ────────────────────────────────────────────────────────────

describe('updateFeaturedArtists — dry-run', () => {
  it('does NOT call refreshWithLock and reports the current roster + limitation', async () => {
    const svc = makeService({});
    const report = await upd.updateFeaturedArtists({ dryRun: true, service: svc, now: NOW });
    assert.equal(report.mode, 'dry-run');
    assert.equal(report.performedRefresh, false);
    assert.equal(svc.calls.refresh, 0, 'dry-run must not issue a refresh');
    assert.equal(svc.calls.getFeatured, 1);
    assert.match(report.trueDryRunLimitation, /cannot be implemented without/);
    assert.ok(report.caveats.length >= 4);
  });
});

describe('updateFeaturedArtists — live', () => {
  it('snapshots then performs exactly one sanctioned refresh (single-flight honored)', async () => {
    const before = anchorRoster();
    const after = [...anchorRoster(), { id: 'dyn', name: 'Dyn', browseId: 'UCdynamic00000000000001', imageUrl: 'https://cdn/z' }];
    const svc = makeService({ before, after });
    const report = await upd.updateFeaturedArtists({ service: svc, now: NOW });

    assert.equal(report.ok, true);
    assert.equal(report.performedRefresh, true);
    assert.equal(report.mode, 'default');
    assert.equal(svc.calls.getFeatured, 1);
    assert.equal(svc.calls.refresh, 1, 'exactly one refresh call');
    assert.equal(report.pipeline.outcome, 'refreshed');
    assert.equal(report.counts.added, 1);
    assert.equal(report.counts.before, 2);
    assert.equal(report.counts.after, 3);
    assert.deepEqual(report.diff.added.map((a) => a.name), ['Dyn']);
  });

  it('force mode is labeled but still issues exactly one refresh', async () => {
    const svc = makeService({});
    const report = await upd.updateFeaturedArtists({ force: true, service: svc, now: NOW });
    assert.equal(report.mode, 'force');
    assert.equal(svc.calls.refresh, 1);
    assert.equal(report.ok, true);
  });

  it('reports ambiguous fallback outcome when the roster is only anchors', async () => {
    const svc = makeService({ before: anchorRoster(), after: anchorRoster() });
    const report = await upd.updateFeaturedArtists({ service: svc, now: NOW });
    assert.equal(report.pipeline.outcome, 'fallback_or_empty');
    assert.equal(report.counts.added, 0);
    assert.equal(report.counts.retained, 2);
  });

  it('defensively handles an unexpected refreshWithLock throw (never propagates)', async () => {
    const svc = makeService({ refreshThrows: new Error('unexpected internal') });
    const report = await upd.updateFeaturedArtists({ service: svc, now: NOW });
    assert.equal(report.ok, false);
    assert.equal(report.performedRefresh, false);
    assert.match(report.error, /unexpected internal/);
  });

  it('produces a leak-free report (no avatar URLs)', async () => {
    const after = [{ id: 'dyn', name: 'Dyn', browseId: 'UCdynamic00000000000001', imageUrl: 'https://lh3.googleusercontent.com/SENSITIVE' }];
    const svc = makeService({ before: [], after });
    const report = await upd.updateFeaturedArtists({ service: svc, now: NOW });
    assert.ok(!JSON.stringify(report).includes('SENSITIVE'));
  });
});
