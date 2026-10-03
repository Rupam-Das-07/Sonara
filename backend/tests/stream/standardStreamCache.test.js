'use strict';

/**
 * standardStreamCache.test.js — Stage C-1A, Part B.
 *
 * Focused tests for the bounded STANDARD YouTube stream-descriptor cache added to
 * AudioSourceResolver. Verifies that STANDARD resolution is memoized per videoId
 * without weakening expiry safety, quality-tier semantics, or the HIGH/JioSaavn path.
 *
 * These tests are additive and do not modify AudioSourceResolver.test.js.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { AudioSourceResolver } = require('../../src/stream/AudioSourceResolver');

// Build a realistic STANDARD proxy streamUrl whose embedded googlevideo URL carries
// an `expire=<unix-seconds>` field — exactly the shape YouTubeStreamProvider returns.
function ytStreamUrl(videoId, expireSec) {
  const upstream =
    `https://rr3---sn-abc.googlevideo.com/videoplayback?expire=${expireSec}&id=${videoId}&mime=audio%2Fwebm`;
  return `/api/v1/stream/play?video_id=${videoId}&audio_url=${encodeURIComponent(upstream)}`;
}

function nowSec() {
  return Math.floor(Date.now() / 1000);
}

class FakeYtProvider {
  // mode: 'fresh' | 'null' | 'noStreamUrl'
  constructor({ mode = 'fresh', expireSec = null } = {}) {
    this.mode = mode;
    this.expireSec = expireSec;
    this.callCount = 0;
  }

  async resolve(target) {
    this.callCount++;
    const videoId = typeof target === 'string' ? target : target && target.videoId;
    if (this.mode === 'null') return null;
    if (this.mode === 'noStreamUrl') {
      // Malformed: a truthy source object with no streamUrl.
      return { provider: 'youtube', format: 'audio/webm', codec: 'opus', bitrate: 160, isDirect: false, expiresAt: 0 };
    }
    const expSec = this.expireSec != null ? this.expireSec : nowSec() + 3600; // default: 1h ahead
    return {
      streamUrl: ytStreamUrl(videoId, expSec),
      format: 'audio/webm',
      codec: 'opus',
      bitrate: 160,
      provider: 'youtube',
      isDirect: false,
      expiresAt: 0,
    };
  }
}

class FakeJioProvider {
  constructor(source) {
    this.source = source;
    this.callCount = 0;
  }
  async resolve() {
    this.callCount++;
    return this.source ? { ...this.source } : null;
  }
}

const target = { videoId: 'dQw4w9WgXcQ', title: 'Kesariya', artist: 'Arijit Singh', duration: 268 };

describe('AudioSourceResolver — STANDARD descriptor cache (Stage C-1A Part B)', () => {
  test('1. STANDARD cache miss calls the existing resolver', async () => {
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    const res = await resolver.resolve(target, 'STANDARD');

    assert.equal(yt.callCount, 1, 'cache miss must invoke the YouTube provider');
    assert.equal(res.provider, 'youtube');
    assert.equal(res.qualityTier, 'STANDARD');
  });

  test('2. Subsequent identical STANDARD request hits the Node cache (no re-resolve)', async () => {
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    const r1 = await resolver.resolve(target, 'STANDARD');
    const r2 = await resolver.resolve(target, 'STANDARD');

    assert.equal(yt.callCount, 1, 'second identical STANDARD request must be served from cache');
    assert.equal(r1.streamUrl, r2.streamUrl);
    assert.equal(r2.qualityTier, 'STANDARD');
  });

  test('3. Cache key separates different video IDs', async () => {
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    await resolver.resolve({ videoId: 'aaaaaaaaaaa' }, 'STANDARD');
    await resolver.resolve({ videoId: 'bbbbbbbbbbb' }, 'STANDARD');
    // Re-request the first — should still be cached (so total provider calls stay 2).
    await resolver.resolve({ videoId: 'aaaaaaaaaaa' }, 'STANDARD');

    assert.equal(yt.callCount, 2, 'distinct videoIds must not collide; the repeat must be a hit');
  });

  test('4. Quality semantics are separated: a HIGH request is not served from the STANDARD cache', async () => {
    const jioSource = {
      streamUrl: 'https://aac.saavncdn.com/x_320.mp4',
      format: 'audio/mp4', codec: 'aac', bitrate: 320, provider: 'jiosaavn', isDirect: true, expiresAt: 0,
    };
    const yt = new FakeYtProvider();
    const jio = new FakeJioProvider(jioSource);
    const resolver = new AudioSourceResolver({ youtubeProvider: yt, jiosaavnProvider: jio });

    // Prime the STANDARD cache.
    const std = await resolver.resolve(target, 'STANDARD');
    assert.equal(std.qualityTier, 'STANDARD');
    assert.equal(yt.callCount, 1);

    // A HIGH request for the same videoId must go through the JioSaavn path, NOT the STANDARD cache.
    const high = await resolver.resolve(target, 'HIGH');
    assert.equal(jio.callCount, 1, 'HIGH must consult JioSaavn, not the STANDARD cache');
    assert.equal(high.provider, 'jiosaavn');
    assert.equal(high.qualityTier, 'HIGH');

    // STANDARD remains independently cached (still a hit).
    await resolver.resolve(target, 'STANDARD');
    assert.equal(yt.callCount, 1, 'STANDARD cache must be unaffected by the HIGH request');
  });

  test('5. An expired cache entry is never returned (re-resolves instead)', async () => {
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    // Seed an entry that is already past its effective expiry.
    const key = `STANDARD:${target.videoId}`;
    resolver.standardCache.set(key, {
      source: { streamUrl: ytStreamUrl(target.videoId, nowSec() + 3600), provider: 'youtube' },
      effectiveExpiryMs: Date.now() - 1, // already expired
    });

    const res = await resolver.resolve(target, 'STANDARD');
    assert.equal(yt.callCount, 1, 'expired entry must be treated as a miss and re-resolved');
    assert.equal(res.provider, 'youtube');
    assert.equal(resolver.standardCache.get(key) !== null, true, 'a fresh entry should now be cached');
  });

  test('6. Safety margin is respected: a URL expiring within the margin is not cached', async () => {
    // expire only ~2 min ahead; the 5-min safety margin makes effective expiry <= now -> not cacheable.
    const yt = new FakeYtProvider({ expireSec: nowSec() + 120 });
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    await resolver.resolve(target, 'STANDARD');
    await resolver.resolve(target, 'STANDARD');

    assert.equal(yt.callCount, 2, 'a near-expiry URL must not be cached, forcing a re-resolve');
    assert.equal(resolver.standardCache.get(`STANDARD:${target.videoId}`), null, 'nothing should be cached');
  });

  test('7. A failed resolution (null) is not cached', async () => {
    const yt = new FakeYtProvider({ mode: 'null' });
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    const r1 = await resolver.resolve(target, 'STANDARD');
    const r2 = await resolver.resolve(target, 'STANDARD');

    assert.equal(r1, null);
    assert.equal(r2, null);
    assert.equal(yt.callCount, 2, 'a null result must not be cached; both calls hit the provider');
    assert.equal(resolver.standardCache.get(`STANDARD:${target.videoId}`), null);
  });

  test('8. A malformed descriptor (missing streamUrl) is not cached', async () => {
    const yt = new FakeYtProvider({ mode: 'noStreamUrl' });
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    const r1 = await resolver.resolve(target, 'STANDARD');
    const r2 = await resolver.resolve(target, 'STANDARD');

    // Behaviour preserved: a truthy-but-malformed source is still returned, just never cached.
    assert.equal(r1.qualityTier, 'STANDARD');
    assert.equal(yt.callCount, 2, 'a malformed source (no streamUrl) must not be cached');
    assert.equal(resolver.standardCache.get(`STANDARD:${target.videoId}`), null);
  });

  test('9. Cache remains bounded under many distinct videoIds', async () => {
    const yt = new FakeYtProvider();
    const resolver = new AudioSourceResolver({ youtubeProvider: yt });

    const overfill = 1100; // exceeds the 1000-entry bound
    for (let i = 0; i < overfill; i++) {
      await resolver.resolve({ videoId: `vid${i}` }, 'STANDARD');
    }

    assert.equal(resolver.standardCache.size, 1000, 'cache size must be capped at its bound');
    assert.ok(resolver.standardCache.size <= 1000, 'cache must never exceed its bound');
  });

  test('10. Existing HIGH / JioSaavn behavior is unchanged (match + negative caches intact)', async () => {
    // 10a. HIGH match cache: JioSaavn called once across two HIGH requests.
    const jioHit = new FakeJioProvider({
      streamUrl: 'https://aac.saavncdn.com/y_320.mp4', format: 'audio/mp4', codec: 'aac',
      bitrate: 320, provider: 'jiosaavn', isDirect: true, expiresAt: 0,
    });
    const ytA = new FakeYtProvider();
    const r1 = new AudioSourceResolver({ youtubeProvider: ytA, jiosaavnProvider: jioHit });
    const h1 = await r1.resolve(target, 'HIGH');
    const h2 = await r1.resolve(target, 'HIGH');
    assert.equal(jioHit.callCount, 1, 'HIGH match cache must serve the second request');
    assert.equal(h1.provider, 'jiosaavn');
    assert.equal(h2.provider, 'jiosaavn');
    assert.equal(ytA.callCount, 0, 'YouTube must not be called when JioSaavn matches');

    // 10b. HIGH negative cache: a JioSaavn miss falls back to YouTube and the second
    //      HIGH request skips JioSaavn (negative cache) and falls back again.
    const jioMiss = new FakeJioProvider(null);
    const ytB = new FakeYtProvider();
    const r2 = new AudioSourceResolver({ youtubeProvider: ytB, jiosaavnProvider: jioMiss });
    const f1 = await r2.resolve(target, 'HIGH');
    const f2 = await r2.resolve(target, 'HIGH');
    assert.equal(jioMiss.callCount, 1, 'negative cache must prevent a second JioSaavn attempt');
    assert.equal(ytB.callCount, 2, 'both HIGH fallbacks resolve via YouTube directly');
    assert.equal(f1.provider, 'youtube');
    assert.equal(f1.qualityTier, 'STANDARD');
    assert.equal(f2.provider, 'youtube');
  });
});
