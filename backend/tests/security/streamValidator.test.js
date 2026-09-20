'use strict';

/**
 * streamValidator.test.js — Unit tests for stream security validation.
 *
 * Validates that the stream proxy cannot be abused as an open proxy.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { validateVideoId, validateAudioUrl, validateYouTubeUrl } = require('../../src/stream/streamValidator');

describe('validateVideoId', () => {
  test('accepts valid 11-char YouTube video IDs', () => {
    assert.deepEqual(validateVideoId('dQw4w9WgXcQ'), { valid: true });
    assert.deepEqual(validateVideoId('jNQXAC9IVRw'), { valid: true });
    assert.deepEqual(validateVideoId('_-123ABCabc'), { valid: true });
  });

  test('rejects blank or null', () => {
    assert.equal(validateVideoId('').valid, false);
    assert.equal(validateVideoId(null).valid, false);
    assert.equal(validateVideoId(undefined).valid, false);
  });

  test('rejects IDs with wrong length', () => {
    assert.equal(validateVideoId('short').valid, false);
    assert.equal(validateVideoId('toolongvideoidhere').valid, false);
  });

  test('rejects IDs with special characters', () => {
    assert.equal(validateVideoId('dQw4w9Wg<=>').valid, false);
    assert.equal(validateVideoId('dQw4w9WgXcQ ').valid, false); // trailing space
  });
});

describe('validateAudioUrl', () => {
  test('accepts valid googlevideo.com URLs', () => {
    const url = 'https://rr1---sn-abc.googlevideo.com/videoplayback?id=xxx';
    assert.deepEqual(validateAudioUrl(url), { valid: true });
  });

  test('rejects arbitrary external URLs', () => {
    const arb = 'https://evil.com/audio.mp3';
    const result = validateAudioUrl(arb);
    assert.equal(result.valid, false);
  });

  test('rejects HTTP (non-HTTPS) URLs', () => {
    const url = 'http://rr1.googlevideo.com/videoplayback?id=xxx';
    assert.equal(validateAudioUrl(url).valid, false);
  });

  test('rejects null/blank', () => {
    assert.equal(validateAudioUrl('').valid, false);
    assert.equal(validateAudioUrl(null).valid, false);
  });

  test('rejects URLs from other trusted-looking but untrusted domains', () => {
    assert.equal(validateAudioUrl('https://googleapis.com/audio').valid, false);
    assert.equal(validateAudioUrl('https://google.com/audio').valid, false);
    assert.equal(validateAudioUrl('https://youtube.com/audio').valid, false);
  });

  test('rejects javascript: protocol', () => {
    assert.equal(validateAudioUrl('javascript:alert(1)').valid, false);
  });
});

describe('validateYouTubeUrl', () => {
  test('accepts valid youtube.com watch URLs', () => {
    const r = validateYouTubeUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    assert.equal(r.valid, true);
    assert.equal(r.videoId, 'dQw4w9WgXcQ');
  });

  test('rejects non-YouTube domains', () => {
    assert.equal(validateYouTubeUrl('https://evil.com/watch?v=dQw4w9WgXcQ').valid, false);
  });

  test('rejects blank', () => {
    assert.equal(validateYouTubeUrl('').valid, false);
  });

  test('rejects malformed URLs', () => {
    assert.equal(validateYouTubeUrl('not-a-url').valid, false);
  });
});
