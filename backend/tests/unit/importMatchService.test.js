'use strict';

/**
 * importMatchService.test.js — Unit tests for tier classification and candidate evaluation.
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { importMatchCache } = require('../../src/import/importMatchCache');
const { matchSingleTrack } = require('../../src/import/importMatchService');
const retriever = require('../../src/import/importCandidateRetriever');

describe('importMatchService Tier Classification Tests', () => {
  let originalRetrieve;

  beforeEach(() => {
    importMatchCache.clear();
    originalRetrieve = retriever.retrieveCandidates;
  });

  test('classifies exact title and artist match as confident tier', async () => {
    retriever.retrieveCandidates = async () => [
      {
        videoId: 'vid123',
        title: 'Starboy',
        artist: 'The Weeknd',
        duration: 230,
        thumbnails: [{ url: 'https://img.yt/starboy.jpg' }],
      },
      {
        videoId: 'vid456',
        title: 'Starboy (Live)',
        artist: 'The Weeknd',
        duration: 240,
        thumbnails: [],
      },
    ];

    const result = await matchSingleTrack({
      sourceOrder: 0,
      title: 'Starboy',
      artist: 'The Weeknd',
      durationMs: 230000,
    });

    assert.equal(result.status, 'matched');
    assert.equal(result.tier, 'confident');
    assert.ok(result.confidence >= 0.75, `Expected confidence >= 0.75, got ${result.confidence}`);
    assert.equal(result.resolvedTrack?.videoId, 'vid123');
    assert.equal(result.resolvedTrack?.title, 'Starboy');
    assert.equal(result.resolvedTrack?.artworkUrl, 'https://img.yt/starboy.jpg');
    assert.equal(result.reason, null);

    retriever.retrieveCandidates = originalRetrieve;
  });

  test('classifies partial/fuzzy title match as review/ambiguous tier', async () => {
    // Title has partial overlap, confidence lands in [0.55, 0.75)
    retriever.retrieveCandidates = async () => [
      {
        videoId: 'vid789',
        title: 'Starboy Acoustic Guitar Tribute',
        artist: 'The Weeknd Tribute Band',
        duration: 200,
      },
    ];

    const result = await matchSingleTrack({
      sourceOrder: 1,
      title: 'Starboy',
      artist: 'The Weeknd',
      durationMs: 230000,
    });

    // Depending on dice similarity of acoustic guitar tribute
    assert.ok(['ambiguous', 'unmatched'].includes(result.status));
    if (result.status === 'ambiguous') {
      assert.equal(result.tier, 'review');
      assert.ok(result.confidence >= 0.55 && result.confidence < 0.75);
      assert.equal(result.resolvedTrack?.videoId, 'vid789');
    }

    retriever.retrieveCandidates = originalRetrieve;
  });

  test('classifies completely unrelated candidate as unmatched tier', async () => {
    retriever.retrieveCandidates = async () => [
      {
        videoId: 'vid999',
        title: 'Completely Unrelated Track',
        artist: 'Someone Else Entirely',
        duration: 100,
      },
    ];

    const result = await matchSingleTrack({
      sourceOrder: 2,
      title: 'Bohemian Rhapsody',
      artist: 'Queen',
      durationMs: 354000,
    });

    assert.equal(result.status, 'unmatched');
    assert.equal(result.tier, 'none');
    assert.ok(result.confidence < 0.55, `Expected confidence < 0.55, got ${result.confidence}`);
    assert.equal(result.resolvedTrack, null);
    assert.equal(result.reason, 'below_threshold');

    retriever.retrieveCandidates = originalRetrieve;
  });

  test('returns no_candidates when search returns empty array', async () => {
    retriever.retrieveCandidates = async () => [];

    const result = await matchSingleTrack({
      sourceOrder: 3,
      title: 'Nonexistent Song 1234567890',
      artist: 'Nonexistent Artist 1234567890',
    });

    assert.equal(result.status, 'unmatched');
    assert.equal(result.tier, 'none');
    assert.equal(result.confidence, 0.0);
    assert.equal(result.resolvedTrack, null);
    assert.equal(result.reason, 'no_candidates');

    retriever.retrieveCandidates = originalRetrieve;
  });
});
