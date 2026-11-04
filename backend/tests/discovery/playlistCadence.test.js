'use strict';

/**
 * playlistCadence.test.js — END-TO-END observable behaviour of the curated-playlist
 * cadence, driven through the real `generateCuratedPlaylist` entry point.
 *
 * Proves the four things that matter to the product:
 *   1. a stale entry inside its declared cadence is served WITHOUT any upstream call;
 *   2. once the cadence elapses, the playlist IS regenerated from upstream;
 *   3. a failed/empty regeneration can never replace a healthy playlist;
 *   4. preserving the cadence honours each definition's own interval.
 *
 * The provider is faked at the ytmusicProvider boundary — the same seam the service
 * uses in production — so no network is involved and results are deterministic.
 */

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PROVIDER = require.resolve('../../src/search/ytmusicProvider');
const SERVICE = require.resolve('../../src/discovery/PlaylistService');

const DAY = 24 * 60 * 60 * 1000;

/** Number of upstream searches issued since the last reset. */
let upstreamCalls;
/** What the fake provider does. */
let respond;
/** Monotonic catalogue mutation hook. */
let generation;

function tracks(prefix, n) {
  return Array.from({ length: n }, (_, i) => ({
    videoId: `${prefix}${String(i).padStart(7, '0')}`,
    title: `${prefix} Track ${i}`,
    artist: `Artist ${i % 9}`,
    album: 'Album',
    duration: 200 + (i % 30),
    thumbnails: [{ url: `https://img/${prefix}/${i}.jpg`, width: 480, height: 360 }],
  }));
}

/**
 * Loads a FRESH PlaylistService with the fake provider installed, so each test starts
 * from empty module caches (equivalent to a process restart).
 */
function freshService() {
  for (const k of Object.keys(require.cache)) {
    if (k === SERVICE || k === PROVIDER) delete require.cache[k];
  }
  require.cache[PROVIDER] = {
    id: PROVIDER, filename: PROVIDER, loaded: true,
    exports: {
      searchSongs: async () => { upstreamCalls++; return respond(); },
      searchMetrics: {},
      getWatchPlaylist: async () => [],
      getRelatedSongs: async () => [],
      getSongDetails: async () => null,
    },
  };
  return require(SERVICE);
}

beforeEach(() => {
  upstreamCalls = 0;
  generation = 1;
  respond = () => tracks('gen1', 20);
});

/**
 * Deterministic clock control: the service reads Date.now().
 *
 * MUST be awaited by the caller. An earlier version restored the real clock as soon
 * as the async body reached its first `await`, which silently defeated every
 * cadence-elapse assertion — the clock has to stay pinned for the whole body.
 */
async function withClock(startMs, fn) {
  const real = Date.now;
  let now = startMs;
  Date.now = () => now;
  const advance = (ms) => { now += ms; };
  try {
    return await fn({ advance });
  } finally {
    Date.now = real;
  }
}

describe('C — curated playlist cadence (end to end)', () => {

  test('first generation calls upstream and is cached', async () => {
    await withClock(1_760_000_000_000, async () => {
      const svc = freshService();
      const first = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(first.length > 0, 'a playlist must be produced');
      assert.ok(upstreamCalls > 0, 'the first generation must consult upstream');
    });
  });

  test('a repeat request within the cadence issues ZERO upstream calls', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      const first = await svc.generateCuratedPlaylist('chill_nights');
      const callsAfterFirst = upstreamCalls;

      advance(1 * DAY);                        // chill_nights is a 7-day playlist
      const second = await svc.generateCuratedPlaylist('chill_nights');

      assert.equal(upstreamCalls, callsAfterFirst,
        'within the declared cadence the cached playlist must be served with no upstream work');
      assert.deepEqual(second.map(t => t.videoId), first.map(t => t.videoId));
    });
  });

  test('the cadence is honoured per definition, not globally', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      await svc.generateCuratedPlaylist('trending_now');    // 2-day cadence
      await svc.generateCuratedPlaylist('retro_bollywood'); // 14-day cadence

      advance(3 * DAY);                    // inside 14d, past 2d
      const before = upstreamCalls;

      await svc.generateCuratedPlaylist('trending_now');
      const afterTrending = upstreamCalls;
      assert.ok(afterTrending > before,
        'a 2-day playlist must be regenerated once 3 days have elapsed');

      await svc.generateCuratedPlaylist('retro_bollywood');
      assert.equal(upstreamCalls, afterTrending,
        'a 14-day playlist must still be served from cadence after only 3 days');
    });
  });

  test('once the cadence elapses the playlist IS regenerated and picks up new upstream tracks', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      const before = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(before.every(t => t.videoId.startsWith('gen1')));

      // Upstream catalogue gains an entirely new generation of releases.
      generation = 2;
      respond = () => tracks('gen2', 20);

      advance(2 * DAY + 1);                // exactly at/after the 2-day cadence
      const after = await svc.generateCuratedPlaylist('trending_now');

      assert.ok(after.length > 0);
      assert.ok(after.every(t => t.videoId.startsWith('gen2')),
        'after the cadence elapses the playlist must reflect the new upstream catalogue');
      assert.notDeepEqual(after.map(t => t.videoId), before.map(t => t.videoId));
    });
  });

  test('a failed regeneration NEVER destroys a healthy playlist', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      const healthy = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(healthy.length > 0);

      // Upstream now returns nothing usable, and the cadence has elapsed so a
      // regeneration is genuinely attempted.
      respond = () => [];
      advance(5 * DAY);

      const after = await svc.generateCuratedPlaylist('trending_now');
      assert.deepEqual(after.map(t => t.videoId), healthy.map(t => t.videoId),
        'an empty regeneration must fall back to the previous healthy playlist');
    });
  });

  /**
   * A failed regeneration must not be treated as a completed one: the cadence stamp is
   * not advanced, so the playlist remains "due" and does recover — but recovery is
   * BOUNDED by the negative-result backoff, not attempted on literally the next request
   * (that unbounded behaviour was the storm this look pass fixed). Cadence is answered
   * separately by the test above; this one pins that a failure is never silently
   * swallowed as success.
   */
  test('a failed regeneration does not consume the cadence, so the playlist still recovers', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      await svc.generateCuratedPlaylist('trending_now');

      respond = () => [];
      advance(5 * DAY);
      await svc.generateCuratedPlaylist('trending_now');
      const afterFailure = upstreamCalls;

      // Past the backoff window (max(1h, 2-day cadence) for trending_now).
      advance(2 * DAY + 1);
      respond = () => tracks('recovered', 20);
      const recovered = await svc.generateCuratedPlaylist('trending_now');

      assert.ok(upstreamCalls > afterFailure, 'the failure must not be permanent');
      assert.ok(recovered.every(t => t.videoId.startsWith('recovered')),
        'and the playlist must pick up the recovered upstream catalogue');
    });
  });

  test('the emitted DTO shape is unchanged', async () => {
    await withClock(1_760_000_000_000, async () => {
      const svc = freshService();
      const out = await svc.generateCuratedPlaylist('chill_nights');
      assert.deepEqual(Object.keys(out[0]).sort(),
        ['album', 'artist', 'artworkUrl', 'duration', 'id', 'resultType', 'thumbnails', 'title', 'videoId'],
        'the Android-facing track DTO contract must not change');
    });
  });

  test('a playlist that cannot fill is NOT retried on every request (negative backoff)', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      respond = () => [];                       // permanently unfillable

      await svc.generateCuratedPlaylist('retro_bollywood');
      const afterFirstAttempt = upstreamCalls;
      assert.ok(afterFirstAttempt > 0, 'the first attempt must consult upstream');

      // A day of traffic. Without negative-result backoff this is 1,443 regenerations
      // (measured) because an empty result is never cached.
      for (let i = 0; i < 200; i++) {
        await svc.generateCuratedPlaylist('retro_bollywood');
        advance(60 * 1000);                     // one request per minute
      }
      assert.equal(upstreamCalls, afterFirstAttempt,
        'a permanently empty playlist must not re-issue its queries on every request');
    });
  });

  test('the negative backoff expires so a recoverable playlist is retried', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      respond = () => [];
      await svc.generateCuratedPlaylist('trending_now');
      const afterFailure = upstreamCalls;

      // The backoff window is max(1h, the playlist's declared cadence) — trending_now
      // declares 2 days, so it must NOT retry after only an hour...
      advance(60 * 60 * 1000);
      respond = () => tracks('early', 20);
      await svc.generateCuratedPlaylist('trending_now');
      assert.equal(upstreamCalls, afterFailure,
        'must not retry inside its own backoff window');

      // ...but it must retry once that window has passed.
      advance(2 * 24 * 60 * 60 * 1000);
      const recovered = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(upstreamCalls > afterFailure, 'the backoff must expire');
      assert.ok(recovered.length > 0, 'and the recovered playlist must be served');
    });
  });

  /**
   * LOOK-PASS REGRESSION.
   *
   * The negative-result backoff was originally applied only when NO healthy snapshot
   * existed. That left a second storm: a playlist WITH a healthy snapshot whose cadence
   * had elapsed re-attempted generation on every request while upstream kept returning
   * empty, because serving the fallback does not advance the cadence stamp. Measured at
   * 3 upstream calls per request, indefinitely (3,000+ calls for 1,000 requests).
   *
   * The rule must hold identically whether or not a healthy result exists to serve.
   */
  test('an empty regeneration is not retried per-request even when a healthy snapshot exists', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      const healthy = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(healthy.length > 0, 'precondition: a healthy playlist exists');

      // Past the 2-day cadence, and upstream is now permanently empty.
      respond = () => [];
      advance(3 * DAY);

      const firstAfterEmpty = await svc.generateCuratedPlaylist('trending_now');
      assert.deepEqual(firstAfterEmpty.map(t => t.videoId), healthy.map(t => t.videoId),
        'the healthy snapshot must still be served');
      const afterFirstAttempt = upstreamCalls;

      // 200 further requests inside the same elapsed-cadence window.
      for (let i = 0; i < 200; i++) {
        await svc.generateCuratedPlaylist('trending_now');
        advance(60 * 1000);
      }

      assert.equal(upstreamCalls, afterFirstAttempt,
        'a healthy playlist whose refresh yields nothing must not re-issue its queries per request');
    });
  });

  test('the backoff on the healthy path still expires and allows recovery', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      await svc.generateCuratedPlaylist('trending_now');

      respond = () => [];                       // 2-day cadence -> 2-day backoff
      advance(3 * DAY);
      await svc.generateCuratedPlaylist('trending_now');
      const suppressed = upstreamCalls;

      advance(60 * 60 * 1000);                  // only an hour: inside the window
      await svc.generateCuratedPlaylist('trending_now');
      assert.equal(upstreamCalls, suppressed, 'must not retry inside its window');

      advance(2 * DAY);                         // window elapsed
      respond = () => tracks('recovered', 20);
      const recovered = await svc.generateCuratedPlaylist('trending_now');
      assert.ok(upstreamCalls > suppressed, 'the backoff must expire');
      assert.ok(recovered.every(t => t.videoId.startsWith('recovered')),
        'and the fresh generation must replace the stale snapshot once it is valid');
    });
  });

  test('a recovered playlist clears its backoff state', async () => {
    await withClock(1_760_000_000_000, async ({ advance }) => {
      const svc = freshService();
      respond = () => [];
      await svc.generateCuratedPlaylist('chill_nights');   // 7-day cadence

      advance(8 * 24 * 60 * 60 * 1000);                   // past max(1h, 7d)
      respond = () => tracks('good', 20);
      const good = await svc.generateCuratedPlaylist('chill_nights');
      assert.ok(good.length > 0, 'recovery must succeed once the backoff expires');

      // Having recovered, it returns to its normal cadence: inside the 7-day window the
      // healthy snapshot is served with no upstream work, and no stale backoff remains.
      const afterRecovery = upstreamCalls;
      advance(1 * DAY);
      const again = await svc.generateCuratedPlaylist('chill_nights');
      assert.equal(upstreamCalls, afterRecovery, 'a recovered playlist returns to normal cadence');
      assert.deepEqual(again.map(t => t.videoId), good.map(t => t.videoId));
    });
  });

  test('an unknown id still throws (contract preserved)', async () => {
    await withClock(1_760_000_000_000, async () => {
      const svc = freshService();
      await assert.rejects(
        () => svc.generateCuratedPlaylist('no_such_playlist'),
        /Playlist definition not found/
      );
    });
  });

  test('every one of the 30 definitions declares a usable cadence', async () => {
    await withClock(1_760_000_000_000, async () => {
      const defs = require('../../src/discovery/PlaylistDefinitions');
      const all = [
        ...Object.values(defs.PLAYLIST_DEFINITIONS),
        ...Object.values(defs.ARTIST_PLAYLIST_DEFINITIONS),
      ];
      assert.equal(all.length, 30);
      for (const d of all) {
        assert.ok(Number.isFinite(d.updateIntervalDays) && d.updateIntervalDays >= 1,
          `${d.id} must declare a usable cadence`);
      }
      const distinct = new Set(all.map(d => d.updateIntervalDays));
      assert.ok(distinct.size >= 3, 'cadences must genuinely differ between playlists');
      assert.ok(distinct.has(2) && distinct.has(7) && distinct.has(14));
    });
  });
});
