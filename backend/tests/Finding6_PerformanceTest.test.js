'use strict';

/**
 * Finding6_PerformanceTest.js — Adversarial tests for recommendation caching (Finding 6).
 *
 * ADVERSARY MODE: Tests written from the SPEC (ACs) and public interface only.
 * Tests cover: cache-hit path, cache independence, stampede protection, TTL eviction,
 * failure isolation (errors not cached), concurrent identical requests, regression against
 * previous FindingServings (A7).
 *
 * AC3: No optimization without evidence — optimization only targets recommendation caching.
 * AC4: Full regression gate: existing behavior unchanged for non-cached paths.
 * AC6: Error behavior unchanged; failed fetches NOT cached.
 * AC7: Finding 1–5 regressions not introduced.
 */

const { describe, it, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

// ---------------------------------------------------------------------------
// Test harness: subclass RecommendationService to inject fakes.
// ---------------------------------------------------------------------------

// We test RecommendationService by monkey-patching ytmusic module dependencies
// through a minimal, non-destructive approach: override the module's ytmusic
// property using the cache/in-flight state exposed via clearCaches() + _radioCache/_relatedCache.

const RecommendationService = require('../src/recommendation/recommendationService');

// Fake track factory
function fakeTrack(videoId = 'test123') {
  return {
    videoId,
    id: videoId,
    title: 'Test Track',
    artist: 'Test Artist',
    album: '',
    duration: 180,
    thumbnails: [{ url: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`, width: 480, height: 360 }],
    resultType: 'song',
  };
}

// ---------------------------------------------------------------------------
// Dependency injection: replace ytmusicProvider methods for testing
// Each test can set ytmusicStubs.getRelatedSongs / getWatchPlaylist.
// ---------------------------------------------------------------------------
const ytmusic = require('../src/search/ytmusicProvider');
const originalGetRelatedSongs = ytmusic.getRelatedSongs;
const originalGetWatchPlaylist = ytmusic.getWatchPlaylist;

beforeEach(() => {
  // Always clear caches between tests to avoid bleed-through
  RecommendationService.clearCaches();
  // Restore originals (individual tests may override)
  ytmusic.getRelatedSongs = originalGetRelatedSongs;
  ytmusic.getWatchPlaylist = originalGetWatchPlaylist;
});

// ── A4: Cache-hit — same videoId returns cached result, Python not called again ──

describe('Finding6 A4: related cache-hit avoids second Python call', () => {
  it('returns cached result on second call, call count = 1', async () => {
    let callCount = 0;
    ytmusic.getRelatedSongs = async (videoId) => {
      callCount++;
      return [fakeTrack(videoId)];
    };

    const id = 'cacheHitTest001';
    // First call — cache miss, Python called
    const r1 = await RecommendationService.getRelatedCandidates(id);
    assert.equal(callCount, 1, 'First call should invoke Python once');
    assert.ok(r1.success, 'First call should succeed');

    // Second call — cache hit, Python NOT called again
    const r2 = await RecommendationService.getRelatedCandidates(id);
    assert.equal(callCount, 1, 'Second call must NOT invoke Python again (cache hit)');
    assert.ok(r2.success, 'Second call should also succeed');
    assert.deepStrictEqual(r1.tracks, r2.tracks, 'Cached result must match original');
  });
});

describe('Finding6 A4: radio cache-hit avoids second Python call', () => {
  it('returns cached result on second call, call count = 1', async () => {
    let callCount = 0;
    ytmusic.getWatchPlaylist = async (videoId) => {
      callCount++;
      return [fakeTrack(videoId)];
    };

    const id = 'radioHitTest001';
    const r1 = await RecommendationService.getRadioCandidates(id);
    assert.equal(callCount, 1, 'First call should invoke Python once');
    assert.ok(r1.success);

    const r2 = await RecommendationService.getRadioCandidates(id);
    assert.equal(callCount, 1, 'Radio second call must NOT re-invoke Python');
    assert.deepStrictEqual(r1.tracks, r2.tracks);
  });
});

// ── A5: Cache independence — different videoIds get independent entries ──

describe('Finding6 A5: different videoIds have independent caches', () => {
  it('each unique videoId triggers its own Python call', async () => {
    const calls = [];
    ytmusic.getRelatedSongs = async (videoId) => {
      calls.push(videoId);
      return [fakeTrack(videoId)];
    };

    await RecommendationService.getRelatedCandidates('idA');
    await RecommendationService.getRelatedCandidates('idB');
    await RecommendationService.getRelatedCandidates('idC');

    assert.deepStrictEqual(calls, ['idA', 'idB', 'idC'], 'Each distinct videoId must call Python independently');
    assert.equal(RecommendationService._relatedCache.size, 3, 'Cache should have 3 independent entries');
  });
});

// ── A5: Boundary — empty videoId is handled cleanly, not cached ──

describe('Finding6 A5: boundary — empty videoId returns error result, not cached', () => {
  it('empty videoId returns success=false without caching', async () => {
    ytmusic.getRelatedSongs = async () => [];

    const result = await RecommendationService.getRelatedCandidates('');
    assert.equal(result.success, false, 'Empty videoId must return success=false');
    assert.equal(RecommendationService._relatedCache.size, 0, 'Empty videoId must not be cached');
  });
});

// ── A6: TTL eviction — after TTL expires, re-fetch happens ──

describe('Finding6 A6: TTL eviction triggers re-fetch', () => {
  it('cache miss after manual TTL override causes Python to be called again', async () => {
    let callCount = 0;
    ytmusic.getRelatedSongs = async (videoId) => {
      callCount++;
      return [fakeTrack(videoId)];
    };

    const id = 'ttlEvictionTest';
    await RecommendationService.getRelatedCandidates(id);
    assert.equal(callCount, 1);

    // Simulate TTL expiry by clearing cache manually
    RecommendationService._relatedCache.clear();

    await RecommendationService.getRelatedCandidates(id);
    assert.equal(callCount, 2, 'After TTL expiry (simulated by clear), Python must be called again');
  });
});

// ── A6: Errors NOT cached — failed fetches don't poison the cache ──

describe('Finding6 A6: failed fetch result is not cached', () => {
  it('Python failure returns success=false and does NOT cache the error', async () => {
    let callCount = 0;
    ytmusic.getRelatedSongs = async () => {
      callCount++;
      throw new Error('Simulated Python failure');
    };

    const id = 'errorCacheTest';
    const r1 = await RecommendationService.getRelatedCandidates(id);
    assert.equal(r1.success, false, 'Failure should return success=false');
    assert.equal(RecommendationService._relatedCache.size, 0, 'Error must NOT be cached');

    // Second call should try Python again (not return cached error)
    const r2 = await RecommendationService.getRelatedCandidates(id);
    assert.equal(callCount, 2, 'Second call after failure must retry Python');
    assert.equal(r2.success, false);
  });
});

// ── A7: Regression — getSimilarCandidates is NOT affected (no cache added) ──

describe('Finding6 A7: getSimilarCandidates regression — no cache (unchanged)', () => {
  it('getSimilarCandidates does not use _relatedCache or _radioCache', async () => {
    // getSimilarCandidates uses ListenBrainz, not the related/radio caches.
    // We verify neither cache is touched when getSimilar is called.
    const initialRelatedSize = RecommendationService._relatedCache.size;
    const initialRadioSize = RecommendationService._radioCache.size;

    // getSimilarCandidates will fail (no real services), but that's OK —
    // we only care that the caches are untouched.
    await RecommendationService.getSimilarCandidates('someid123');

    assert.equal(RecommendationService._relatedCache.size, initialRelatedSize, 'getSimilarCandidates must not touch _relatedCache');
    assert.equal(RecommendationService._radioCache.size, initialRadioSize, 'getSimilarCandidates must not touch _radioCache');
  });
});

// ── A8: Concurrency — concurrent same-videoId requests deduplicated (stampede protection) ──

describe('Finding6 A8: concurrent same-seed requests deduplicated', () => {
  it('5 concurrent getRelatedCandidates for same id trigger exactly 1 Python call', async () => {
    let callCount = 0;
    ytmusic.getRelatedSongs = async (videoId) => {
      callCount++;
      // Simulate slight network delay
      await new Promise(resolve => setTimeout(resolve, 10));
      return [fakeTrack(videoId)];
    };

    const id = 'concurrentTest001';
    // Fire 5 concurrent requests for the same id
    const results = await Promise.all([
      RecommendationService.getRelatedCandidates(id),
      RecommendationService.getRelatedCandidates(id),
      RecommendationService.getRelatedCandidates(id),
      RecommendationService.getRelatedCandidates(id),
      RecommendationService.getRelatedCandidates(id),
    ]);

    assert.equal(callCount, 1, 'Exactly 1 Python call for 5 concurrent identical requests (stampede protection)');
    for (const result of results) {
      assert.ok(result.success, 'All concurrent results must succeed');
      assert.ok(Array.isArray(result.tracks), 'All concurrent results must have tracks array');
    }
  });

  it('5 concurrent getRadioCandidates for same id trigger exactly 1 Python call', async () => {
    let callCount = 0;
    ytmusic.getWatchPlaylist = async (videoId) => {
      callCount++;
      await new Promise(resolve => setTimeout(resolve, 10));
      return [fakeTrack(videoId)];
    };

    const id = 'radioStampedeTest001';
    const results = await Promise.all([
      RecommendationService.getRadioCandidates(id),
      RecommendationService.getRadioCandidates(id),
      RecommendationService.getRadioCandidates(id),
      RecommendationService.getRadioCandidates(id),
      RecommendationService.getRadioCandidates(id),
    ]);

    assert.equal(callCount, 1, 'Radio stampede: exactly 1 Python call for 5 concurrent same-seed requests');
    for (const result of results) {
      assert.ok(result.success);
    }
  });
});

// ── Property / fuzz: many videoIds → each cached independently, no collision ──

describe('Finding6 property: 20 distinct videoIds cached independently', () => {
  it('all 20 videoIds independently cached with correct tracks', async () => {
    const videoIds = Array.from({ length: 20 }, (_, i) => `propFuzz${i.toString().padStart(3, '0')}`);
    ytmusic.getRelatedSongs = async (videoId) => [fakeTrack(videoId)];

    // Fetch all 20
    for (const id of videoIds) {
      await RecommendationService.getRelatedCandidates(id);
    }
    assert.equal(RecommendationService._relatedCache.size, 20, 'All 20 entries must be independently cached');

    // Verify each returns its own tracks (no cross-contamination)
    let callsAfterWarm = 0;
    ytmusic.getRelatedSongs = async (videoId) => { callsAfterWarm++; return [fakeTrack(videoId)]; };
    for (const id of videoIds) {
      const r = await RecommendationService.getRelatedCandidates(id);
      assert.ok(r.tracks.some(t => t.videoId === id), `Cache for ${id} must contain its own tracks`);
    }
    assert.equal(callsAfterWarm, 0, 'No Python calls after all 20 are warm — all cache hits');
  });
});
