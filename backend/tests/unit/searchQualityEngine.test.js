'use strict';

/**
 * searchQualityEngine.test.js — Unit tests for the SQE pipeline.
 *
 * Uses Node.js built-in test runner (node --test).
 * No external test framework required.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const SQE = require('../../src/search/SearchQualityEngine');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function makeTrack(overrides = {}) {
  const id = overrides.id ?? 'dQw4w9WgXcQ';
  return {
    id,
    videoId:    id,  // always mirrors id
    title:      overrides.title      ?? 'Never Gonna Give You Up',
    artist:     overrides.artist     ?? 'Rick Astley',
    duration:   overrides.duration   ?? 213,
    resultType: overrides.resultType ?? 'song',
    thumbnails: overrides.thumbnails ?? [],
    album:      overrides.album      ?? '',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe('SearchQualityEngine', () => {

  test('returns empty array for empty input', () => {
    assert.deepEqual(SQE.process([]), []);
    assert.deepEqual(SQE.process(null), []);
    assert.deepEqual(SQE.process(undefined), []);
  });

  test('results are frozen objects', () => {
    const results = SQE.process([makeTrack()]);
    assert.ok(Object.isFrozen(results[0]), 'result track should be frozen');
  });

  test('song type gets bonus score', () => {
    // Different titles+artists so dedup doesn't collapse them
    const song  = makeTrack({ id: 'aaa11111111', title: 'Alpha Song',         artist: 'ArtistAlpha', resultType: 'song' });
    const video = makeTrack({ id: 'bbb11111111', title: 'Beta Video Content', artist: 'ArtistBeta',  resultType: 'video' });
    const results = SQE.process([video, song]);
    // song gets +30 bonus so aaa should rank first
    assert.equal(results[0].videoId, 'aaa11111111');
  });

  test('karaoke track gets penalized', () => {
    const original = makeTrack({ id: 'orig1111111', title: 'Shape of You',         artist: 'Ed Sheeran',   resultType: 'song' });
    const karaoke  = makeTrack({ id: 'kara1111111', title: 'Shape of You Karaoke', artist: 'Karaoke Band', resultType: 'song' });
    const results  = SQE.process([karaoke, original]);
    assert.equal(results[0].videoId, 'orig1111111', 'original should rank above karaoke');
  });

  test('query-contextual penalty neutralization', () => {
    // If user searches for "karaoke", karaoke penalty should be neutralised
    const karaoke = makeTrack({ title: 'Shape of You Karaoke', resultType: 'song' });
    const results = SQE.process([karaoke], 'shape of you karaoke');
    assert.equal(results.length, 1);
    // Score should not be penalized
    assert.equal(results[0].qualityScore, 130); // 100 base + 30 song bonus
  });

  test('tracks over 15 minutes are filtered out', () => {
    const longTrack = makeTrack({ id: 'long', duration: 901 }); // 901s > 15min
    const normal    = makeTrack({ id: 'norm', duration: 200 });
    const results   = SQE.process([longTrack, normal]);
    const ids = results.map(r => r.id);
    assert.ok(!ids.includes('long'), 'long track should be filtered');
    assert.ok(ids.includes('norm'), 'normal track should remain');
  });

  test('deduplication collapses same title+artist', () => {
    const t1 = makeTrack({ id: 'aaa', title: 'Shape of You', resultType: 'song' });
    const t2 = makeTrack({ id: 'bbb', title: 'Shape of You (Official Video)', resultType: 'video' });
    const results = SQE.process([t1, t2]);
    assert.equal(results.length, 1);
  });

  test('title is sanitized (official video suffix removed)', () => {
    const track = makeTrack({ title: 'Shape of You (Official Video)', resultType: 'song' });
    const results = SQE.process([track]);
    assert.equal(results[0].title, 'Shape of You');
  });

  test('original track object is not mutated', () => {
    const original = makeTrack();
    const originalTitle = original.title;
    SQE.process([original]);
    assert.equal(original.title, originalTitle, 'source track must not be mutated');
  });

  test('stable sort preserves rank as tiebreaker', () => {
    // Two tracks with same resultType (no type bonus difference)
    // and different original ranks
    const t1 = makeTrack({ id: 'first',  resultType: 'video' });
    const t2 = makeTrack({ id: 'second', resultType: 'video' });
    const results = SQE.process([t1, t2]);
    assert.equal(results[0].id, 'first', 'should preserve original rank as tiebreaker');
  });
});
