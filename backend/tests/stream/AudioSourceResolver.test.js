'use strict';

/**
 * AudioSourceResolver.test.js — Unit tests for AudioSourceResolver.
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { AudioSourceResolver } = require('../../src/stream/AudioSourceResolver');

describe('AudioSourceResolver', () => {
  const target = {
    videoId: 'dQw4w9WgXcQ',
    title: 'Kesariya',
    artist: 'Arijit Singh',
    duration: 268,
  };

  const sampleJioSource = {
    streamUrl: 'https://aac.saavncdn.com/test_320.mp4',
    format: 'audio/mp4',
    codec: 'aac',
    bitrate: 320,
    provider: 'jiosaavn',
    isDirect: true,
    expiresAt: 0,
  };

  const sampleYtSource = {
    streamUrl: '/api/v1/stream/play?video_id=dQw4w9WgXcQ&audio_url=https://googlevideo.com',
    format: 'audio/webm',
    codec: 'opus',
    bitrate: 160,
    provider: 'youtube',
    isDirect: false,
    expiresAt: 0,
  };

  class FakeJioProvider {
    constructor(source = null, shouldThrow = false) {
      this.source = source;
      this.shouldThrow = shouldThrow;
      this.callCount = 0;
      this.lastQuality = null;
    }
    async resolve(target, quality) {
      this.callCount++;
      this.lastQuality = quality;
      if (this.shouldThrow) throw new Error('JioSaavn network crashed');
      return this.source ? { ...this.source } : null;
    }
  }

  class FakeYtProvider {
    constructor(source = sampleYtSource) {
      this.source = source;
      this.callCount = 0;
    }
    async resolve() {
      this.callCount++;
      return this.source ? { ...this.source } : null;
    }
  }

  test('STANDARD requests bypass JioSaavn completely and route to YouTube', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'STANDARD');

    assert.equal(jio.callCount, 0, 'JioSaavn must not be called for STANDARD');
    assert.equal(yt.callCount, 1, 'YouTube must be called for STANDARD');
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
  });

  test('AUTO requests default to YouTube baseline', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'AUTO');

    assert.equal(jio.callCount, 0);
    assert.equal(yt.callCount, 1);
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
  });

  test('HIGH request successfully resolves JioSaavn when available', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(yt.callCount, 0, 'YouTube must not be called when JioSaavn succeeds');
    assert.equal(res.provider, 'jiosaavn');
    assert.equal(res.bitrate, 320);
    assert.equal(res.qualityTier, 'HIGH');
  });

  test('HIGH request falls back to YouTube when JioSaavn returns null', async () => {
    const jio = new FakeJioProvider(null); // JioSaavn has no match
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(yt.callCount, 1);
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
  });

  test('HIGH request falls back to YouTube when JioSaavn throws', async () => {
    const jio = new FakeJioProvider(null, true); // throws error
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(yt.callCount, 1);
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
  });

  test('HIGH request without title metadata skips JioSaavn and falls back to YouTube', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve({ videoId: 'dQw4w9WgXcQ' }, 'HIGH');

    assert.equal(jio.callCount, 0, 'JioSaavn should not be called without title');
    assert.equal(yt.callCount, 1);
    assert.equal(res.provider, 'youtube');
  });

  test('Match cache serves subsequent HIGH requests without calling JioSaavn again', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res1 = await resolver.resolve(target, 'HIGH');
    const res2 = await resolver.resolve(target, 'HIGH');

    assert.equal(jio.callCount, 1, 'JioSaavn should only be called once');
    assert.equal(res1.streamUrl, res2.streamUrl);
  });

  test('Negative cache skips JioSaavn for previously failed tracks', async () => {
    const jio = new FakeJioProvider(null);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    await resolver.resolve(target, 'HIGH');
    assert.equal(jio.callCount, 1);

    await resolver.resolve(target, 'HIGH');
    assert.equal(jio.callCount, 1, 'Negative cache must prevent second JioSaavn attempt');
    assert.equal(yt.callCount, 2);
  });

  test('Returns null cleanly when both providers fail', async () => {
    const jio = new FakeJioProvider(null);
    const yt = new FakeYtProvider(null);
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'HIGH');
    assert.equal(res, null);
  });

  test('normalizeQuality handles VERY_HIGH, HIGH, STANDARD and defaults unknown safely', () => {
    const resolver = new AudioSourceResolver();
    assert.equal(resolver.normalizeQuality('VERY_HIGH'), 'VERY_HIGH');
    assert.equal(resolver.normalizeQuality('very_high'), 'VERY_HIGH');
    assert.equal(resolver.normalizeQuality('HIGH'), 'HIGH');
    assert.equal(resolver.normalizeQuality('high'), 'HIGH');
    assert.equal(resolver.normalizeQuality('STANDARD'), 'STANDARD');
    assert.equal(resolver.normalizeQuality('AUTO'), 'STANDARD');
    assert.equal(resolver.normalizeQuality(''), 'STANDARD');
    assert.equal(resolver.normalizeQuality(null), 'STANDARD');
    assert.equal(resolver.normalizeQuality('MALFORMED_VALUE'), 'STANDARD');
  });

  test('VERY_HIGH requests pass VERY_HIGH to JioSaavn and return VERY_HIGH qualityTier', async () => {
    const jio = new FakeJioProvider(sampleJioSource);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'VERY_HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(jio.lastQuality, 'VERY_HIGH');
    assert.equal(yt.callCount, 0);
    assert.equal(res.provider, 'jiosaavn');
    assert.equal(res.qualityTier, 'VERY_HIGH');
  });

  test('HIGH requests pass HIGH to JioSaavn and return HIGH qualityTier', async () => {
    const sampleJio160 = { ...sampleJioSource, streamUrl: 'https://aac.saavncdn.com/test_160.mp4', bitrate: 160 };
    const jio = new FakeJioProvider(sampleJio160);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(jio.lastQuality, 'HIGH');
    assert.equal(yt.callCount, 0);
    assert.equal(res.provider, 'jiosaavn');
    assert.equal(res.bitrate, 160);
    assert.equal(res.qualityTier, 'HIGH');
  });

  test('HIGH and VERY_HIGH have isolated match caches and do not cross-pollute', async () => {
    let requestedQ = null;
    const jio = {
      callCount: 0,
      async resolve(t, q) {
        this.callCount++;
        requestedQ = q;
        if (q === 'VERY_HIGH') {
          return { ...sampleJioSource, bitrate: 320, streamUrl: 'https://aac.saavncdn.com/test_320.mp4' };
        } else {
          return { ...sampleJioSource, bitrate: 160, streamUrl: 'https://aac.saavncdn.com/test_160.mp4' };
        }
      }
    };
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    // 1. Resolve HIGH
    const resHigh = await resolver.resolve(target, 'HIGH');
    assert.equal(resHigh.qualityTier, 'HIGH');
    assert.equal(resHigh.bitrate, 160);
    assert.equal(jio.callCount, 1);

    // 2. Resolve VERY_HIGH -> must NOT hit HIGH cache!
    const resVeryHigh = await resolver.resolve(target, 'VERY_HIGH');
    assert.equal(resVeryHigh.qualityTier, 'VERY_HIGH');
    assert.equal(resVeryHigh.bitrate, 320);
    assert.equal(jio.callCount, 2, 'VERY_HIGH must trigger second JioSaavn call instead of serving HIGH cache');

    // 3. Resolve HIGH again -> must hit HIGH cache
    const resHighCached = await resolver.resolve(target, 'HIGH');
    assert.equal(resHighCached.qualityTier, 'HIGH');
    assert.equal(resHighCached.bitrate, 160);
    assert.equal(jio.callCount, 2, 'Cached HIGH must not increment callCount');
  });

  test('VERY_HIGH fallback to YouTube returns STANDARD qualityTier and does not claim 320 kbps', async () => {
    const jio = new FakeJioProvider(null);
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ jiosaavnProvider: jio, youtubeProvider: yt });

    const res = await resolver.resolve(target, 'VERY_HIGH');

    assert.equal(jio.callCount, 1);
    assert.equal(yt.callCount, 1);
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
    assert.equal(res.bitrate, 160);
  });
});
