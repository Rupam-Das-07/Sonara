'use strict';

/**
 * playlistRefreshPolicy.test.js — the curated-playlist CADENCE contract.
 *
 * Background: every playlist definition has always declared `updateIntervalDays`, but
 * nothing read it — the only reference was a type assertion. `generateCuratedPlaylist`
 * was a pure function of (definition, upstream results), so a playlist regenerated
 * byte-identically whenever the 1-hour cache entry expired, and the declared cadence
 * was inert. These tests pin the policy that makes the cadence real.
 *
 * Observable behaviour under test: WHEN a playlist is allowed to be regenerated.
 * Enforced end-to-end through `generateCuratedPlaylist` in playlistCadence.test.js.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

const {
  shouldRefresh,
  resolveIntervalDays,
  epochFor,
  DEFAULT_INTERVAL_DAYS,
  MIN_INTERVAL_DAYS,
  MAX_INTERVAL_DAYS,
} = require('../../src/discovery/PlaylistRefreshPolicy');
const PlaylistService = require('../../src/discovery/PlaylistService');

const DAY = 24 * 60 * 60 * 1000;
const NOW = 1_760_000_000_000;

const def = (d) => ({ id: 'x', updateIntervalDays: d });

describe('C — resolveIntervalDays', () => {
  test('reads the declared cadence', () => {
    assert.equal(resolveIntervalDays(def(2)), 2);
    assert.equal(resolveIntervalDays(def(7)), 7);
    assert.equal(resolveIntervalDays(def(14)), 14);
  });

  test('falls back for missing, malformed or non-positive values', () => {
    for (const bad of [undefined, null, 'nope', 0, -3, NaN, Infinity, -Infinity, {}, Symbol('bad')]) {
      assert.equal(
        resolveIntervalDays(def(bad)), DEFAULT_INTERVAL_DAYS,
        `"${String(bad)}" must fall back so a bad definition can never refresh constantly`
      );
    }
    assert.equal(resolveIntervalDays(null), DEFAULT_INTERVAL_DAYS);
  });

  test('a fractional cadence floors to whole days', () => {
    assert.equal(resolveIntervalDays(def(2.9)), 2);
    assert.equal(resolveIntervalDays(def('2.9')), 2);
  });

  test('the floor is one day', () => {
    assert.equal(resolveIntervalDays(def(0.4)), DEFAULT_INTERVAL_DAYS);
    assert.ok(MIN_INTERVAL_DAYS >= 1);
  });

  test('the largest supported cadence stays finite and larger values use the default', () => {
    assert.equal(resolveIntervalDays(def(MAX_INTERVAL_DAYS)), MAX_INTERVAL_DAYS);
    assert.equal(resolveIntervalDays(def(MAX_INTERVAL_DAYS + 1)), DEFAULT_INTERVAL_DAYS);
    assert.equal(resolveIntervalDays(def(Number.MAX_VALUE)), DEFAULT_INTERVAL_DAYS);
  });

  test('empty backoff uses the exact same normalized cadence', () => {
    const cases = [
      [undefined, DEFAULT_INTERVAL_DAYS],
      [0.4, DEFAULT_INTERVAL_DAYS],
      [-2, DEFAULT_INTERVAL_DAYS],
      ['2.9', 2],
      [Infinity, DEFAULT_INTERVAL_DAYS],
      [MAX_INTERVAL_DAYS + 1, DEFAULT_INTERVAL_DAYS],
      [14, 14],
    ];
    for (const [raw, expectedDays] of cases) {
      assert.equal(
        PlaylistService.emptyBackoffMs(def(raw)),
        expectedDays * DAY,
        `empty backoff must use normalized interval for ${String(raw)}`
      );
    }
  });
});

describe('C — shouldRefresh cadence', () => {
  test('a never-generated playlist is due', () => {
    const r = shouldRefresh({ definition: def(7), generatedAtMs: undefined, nowMs: NOW });
    assert.equal(r.due, true);
    assert.equal(r.reason, 'never_generated');
  });

  test('within the cadence is NOT due', () => {
    const r = shouldRefresh({ definition: def(7), generatedAtMs: NOW - 3 * DAY, nowMs: NOW });
    assert.equal(r.due, false);
    assert.equal(r.reason, 'within_cadence');
    assert.equal(r.intervalDays, 7);
    assert.equal(r.ageMs, 3 * DAY);
  });

  test('exactly at the cadence boundary is due', () => {
    assert.equal(
      shouldRefresh({ definition: def(7), generatedAtMs: NOW - 7 * DAY, nowMs: NOW }).due,
      true
    );
  });

  test('one millisecond short of the boundary is not due', () => {
    assert.equal(
      shouldRefresh({ definition: def(7), generatedAtMs: NOW - 7 * DAY + 1, nowMs: NOW }).due,
      false
    );
  });

  test('past the cadence is due', () => {
    const r = shouldRefresh({ definition: def(2), generatedAtMs: NOW - 10 * DAY, nowMs: NOW });
    assert.equal(r.due, true);
    assert.equal(r.reason, 'cadence_elapsed');
  });

  test('per-definition cadence is genuinely differentiated', () => {
    const age = 4 * DAY;
    // Trending Now (2d) is overdue while Retro Bollywood (14d) is comfortably inside
    // its window — the whole point of the declared cadences.
    assert.equal(shouldRefresh({ definition: def(2),  generatedAtMs: NOW - age, nowMs: NOW }).due, true);
    assert.equal(shouldRefresh({ definition: def(7),  generatedAtMs: NOW - age, nowMs: NOW }).due, false);
    assert.equal(shouldRefresh({ definition: def(14), generatedAtMs: NOW - age, nowMs: NOW }).due, false);
  });

  test('a backwards clock does not wedge a playlist out of refreshing', () => {
    const r = shouldRefresh({ definition: def(7), generatedAtMs: NOW + 5 * DAY, nowMs: NOW });
    assert.equal(r.due, true);
    assert.equal(r.reason, 'clock_moved_backwards');
  });

  test('is pure — repeated calls with the same inputs agree', () => {
    const args = { definition: def(7), generatedAtMs: NOW - 3 * DAY, nowMs: NOW };
    assert.deepEqual(shouldRefresh(args), shouldRefresh(args));
  });

  test('epochFor advances exactly once per interval', () => {
    const e = epochFor(NOW, 7);
    assert.equal(epochFor(NOW + 6 * DAY, 7), e, 'still the same era');
    assert.equal(epochFor(NOW + 8 * DAY, 7), e + 1, 'one era later');
    assert.equal(epochFor(NOW, 2), epochFor(NOW, 2), 'deterministic');
  });
});
