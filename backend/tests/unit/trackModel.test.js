'use strict';

/**
 * trackModel.test.js — Unit tests for createTrack factory.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { createTrack, validateTrack } = require('../../src/search/trackModel');

describe('createTrack', () => {
  test('sets videoId and id from videoId field', () => {
    const t = createTrack({ videoId: 'abc11111111', title: 'Song', artist: 'Artist' });
    assert.equal(t.id, 'abc11111111');
    assert.equal(t.videoId, 'abc11111111');
  });

  test('falls back to id field if videoId missing', () => {
    const t = createTrack({ id: 'abc11111111', title: 'Song', artist: 'Artist' });
    assert.equal(t.videoId, 'abc11111111');
  });

  test('provides defaults for missing fields', () => {
    const t = createTrack({});
    assert.equal(t.title, 'Unknown Title');
    assert.equal(t.artist, 'Unknown Artist');
    assert.equal(t.source, 'youtube_music');
  });

  test('returns null for null input', () => {
    assert.equal(createTrack(null), null);
  });

  test('result is frozen', () => {
    const t = createTrack({ videoId: 'dQw4w9WgXcQ', title: 'T', artist: 'A' });
    assert.ok(Object.isFrozen(t));
  });

  test('parseThumbnails picks thumbnails array', () => {
    const t = createTrack({
      videoId: 'dQw4w9WgXcQ', title: 'T', artist: 'A',
      thumbnails: [{ url: 'https://example.com/t.jpg' }],
    });
    assert.equal(t.thumbnails.length, 1);
    assert.equal(t.thumbnails[0].url, 'https://example.com/t.jpg');
  });
});

describe('validateTrack', () => {
  test('returns true for a valid track', () => {
    const t = createTrack({ videoId: 'dQw4w9WgXcQ', title: 'T', artist: 'A' });
    assert.equal(validateTrack(t), true);
  });

  test('returns false for null', () => {
    assert.equal(validateTrack(null), false);
  });

  test('returns false for track without id', () => {
    assert.equal(validateTrack({ title: 'T' }), false);
  });
});
