'use strict';

/**
 * streamMatcher.test.js — Unit tests for deterministic JioSaavn candidate matcher.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { evaluateMatch } = require('../../src/stream/streamMatcher');

describe('streamMatcher — 5-Point Quality Gate', () => {
  const baseTarget = {
    videoId: 'dQw4w9WgXcQ',
    title: 'Kesariya',
    artist: 'Arijit Singh, Pritam',
    duration: 268,
  };

  const baseCandidate = {
    song: 'Kesariya',
    singers: 'Arijit Singh, Pritam, Amitabh Bhattacharya',
    duration: '268',
    '320kbps': 'true',
    encrypted_media_url: 'https://example.com/enc',
  };

  test('1. Exact title + artist + duration + 320 -> ACCEPT', () => {
    const res = evaluateMatch(baseTarget, baseCandidate);
    assert.equal(res.ok, true);
    assert.equal(res.deltaSeconds, 0);
  });

  test('2. Duration delta 1 second -> ACCEPT', () => {
    const candidate = { ...baseCandidate, duration: '269' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, true);
    assert.equal(res.deltaSeconds, 1);
  });

  test('3. Duration delta 3 seconds -> ACCEPT', () => {
    const candidate = { ...baseCandidate, duration: '271' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, true);
    assert.equal(res.deltaSeconds, 3);
  });

  test('4. Duration delta 4 seconds -> REJECT', () => {
    const candidate = { ...baseCandidate, duration: '272' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'duration_mismatch');
    assert.equal(res.deltaSeconds, 4);
  });

  test('5. Wrong artist -> REJECT', () => {
    const candidate = { ...baseCandidate, singers: 'Badshah, Neha Kakkar' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'artist_mismatch');
  });

  test('6. Adele / Alda Rikson-style cover mismatch -> REJECT', () => {
    const target = {
      videoId: 'YQHsXMglC9A',
      title: 'Hello',
      artist: 'Adele',
      duration: 295,
    };
    const candidate = {
      song: 'Hello',
      singers: 'Alda Rikson',
      duration: '295',
      '320kbps': 'true',
      encrypted_media_url: 'https://example.com/enc',
    };
    const res = evaluateMatch(target, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'artist_mismatch');
  });

  test('7. Karaoke -> REJECT', () => {
    const candidate = { ...baseCandidate, song: 'Kesariya (Karaoke Version)' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'junk_variant');
  });

  test('8. 8D Audio -> REJECT', () => {
    const candidate = { ...baseCandidate, song: 'Kesariya 8D Audio' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'junk_variant');
  });

  test('9. Lofi / Lo-fi -> REJECT', () => {
    const candidate1 = { ...baseCandidate, song: 'Kesariya Lofi Flip' };
    assert.equal(evaluateMatch(baseTarget, candidate1).reason, 'junk_variant');

    const candidate2 = { ...baseCandidate, song: 'Kesariya (Lo-Fi Version)' };
    assert.equal(evaluateMatch(baseTarget, candidate2).reason, 'junk_variant');
  });

  test('10. Slowed -> REJECT', () => {
    const candidate = { ...baseCandidate, song: 'Kesariya (Slowed + Reverb)' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'junk_variant');
  });

  test('11. Instrumental -> REJECT', () => {
    const candidate = { ...baseCandidate, song: 'Kesariya Instrumental' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'junk_variant');
  });

  test('12. Missing 320kbps -> REJECT', () => {
    const candidate = { ...baseCandidate, '320kbps': 'false' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'missing_320kbps');
  });

  test('13. Malformed / absent 320kbps -> REJECT', () => {
    const candidate1 = { ...baseCandidate, '320kbps': null };
    assert.equal(evaluateMatch(baseTarget, candidate1).reason, 'missing_320kbps');

    const candidate2 = { ...baseCandidate, '320kbps': undefined };
    delete candidate2['320kbps'];
    assert.equal(evaluateMatch(baseTarget, candidate2).reason, 'missing_320kbps');
  });

  test('14. Legitimate duet / collaboration -> ACCEPT when artist overlap is valid', () => {
    const target = {
      videoId: 'abc12345678',
      title: 'Beautiful People',
      artist: 'Ed Sheeran feat. Khalid',
      duration: 198,
    };
    const candidate = {
      song: 'Beautiful People',
      singers: 'Ed Sheeran',
      duration: '198',
      '320kbps': 'true',
      encrypted_media_url: 'https://example.com/enc',
    };
    const res = evaluateMatch(target, candidate);
    assert.equal(res.ok, true);
  });

  test('15. Case / punctuation title differences -> ACCEPT where appropriate', () => {
    const target = {
      videoId: 'xyz98765432',
      title: 'Naatu Naatu (From "RRR")',
      artist: 'Rahul Sipligunj, Kaala Bhairava',
      duration: 215,
    };
    const candidate = {
      song: 'Naatu Naatu',
      singers: 'Rahul Sipligunj, Kaala Bhairava, M.M. Keeravani',
      duration: '216',
      '320kbps': 'true',
      encrypted_media_url: 'https://example.com/enc',
    };
    const res = evaluateMatch(target, candidate);
    assert.equal(res.ok, true);
    assert.equal(res.deltaSeconds, 1);
  });

  test('16. Clearly unrelated title -> REJECT', () => {
    const candidate = { ...baseCandidate, song: 'Apna Bana Le' };
    const res = evaluateMatch(baseTarget, candidate);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'title_mismatch');
  });

  test('17. Invalid duration -> REJECT', () => {
    const candidate = { ...baseCandidate, duration: '0' };
    assert.equal(evaluateMatch(baseTarget, candidate).reason, 'invalid_duration');

    const candidate2 = { ...baseCandidate, duration: 'invalid' };
    assert.equal(evaluateMatch(baseTarget, candidate2).reason, 'invalid_duration');

    const target2 = { ...baseTarget, duration: 0 };
    assert.equal(evaluateMatch(target2, baseCandidate).reason, 'invalid_duration');
  });

  test('18. Boundary behavior around 3 seconds', () => {
    // Delta = -3 -> accept
    const candMinus3 = { ...baseCandidate, duration: '265' };
    assert.equal(evaluateMatch(baseTarget, candMinus3).ok, true);

    // Delta = -4 -> reject
    const candMinus4 = { ...baseCandidate, duration: '264' };
    assert.equal(evaluateMatch(baseTarget, candMinus4).ok, false);
    assert.equal(evaluateMatch(baseTarget, candMinus4).reason, 'duration_mismatch');
  });

  test('19. Missing media URL -> REJECT', () => {
    const candidate = { ...baseCandidate, encrypted_media_url: '' };
    assert.equal(evaluateMatch(baseTarget, candidate).reason, 'missing_media_url');
  });
});
