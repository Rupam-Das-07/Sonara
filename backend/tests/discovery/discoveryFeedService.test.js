'use strict';

const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const {
  DiscoveryFeedService,
  getFeedCacheKey,
  isValidPlaylistId,
  FEED_TTL_MS,
  _feedCache,
  _inFlightFeeds,
} = require('../../src/discovery/discoveryFeedService');
const ytmusic = require('../../src/search/ytmusicProvider');

describe('DiscoveryFeedService — Native Chart Feed Caching & In-flight Dedup', () => {
  beforeEach(() => {
    _feedCache.clear();
    _inFlightFeeds.clear();
  });

  test('isValidPlaylistId accepts valid YouTube playlist IDs and rejects invalid', () => {
    assert.equal(isValidPlaylistId('PL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX'), true);
    assert.equal(isValidPlaylistId('PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj'), true);
    assert.equal(isValidPlaylistId('PL1234567890abcdef'), true);

    // Invalid
    assert.equal(isValidPlaylistId(''), false);
    assert.equal(isValidPlaylistId(null), false);
    assert.equal(isValidPlaylistId(undefined), false);
    assert.equal(isValidPlaylistId(12345), false);
    assert.equal(isValidPlaylistId('PLshort'), false);
    assert.equal(isValidPlaylistId('NOT_A_PLAYLIST'), false);
    assert.equal(isValidPlaylistId('invalid spaces in id!'), false);
  });

  test('getFeedCacheKey strictly distinguishes country, language, chartId, and feedType', () => {
    const key1 = getFeedCacheKey('chart', { country: 'IN', language: 'Hindi', chartId: 'PL1111111111111111' });
    const key2 = getFeedCacheKey('chart', { country: 'IN', language: 'Punjabi', chartId: 'PL1111111111111111' });
    const key3 = getFeedCacheKey('chart', { country: 'US', language: 'Hindi', chartId: 'PL1111111111111111' });
    const key4 = getFeedCacheKey('chart', { country: 'IN', language: 'Hindi', chartId: 'PL2222222222222222' });
    const key5 = getFeedCacheKey('explore', { country: 'IN', language: 'Hindi', chartId: 'PL1111111111111111' });

    assert.notEqual(key1, key2, 'Languages must yield distinct keys');
    assert.notEqual(key1, key3, 'Countries must yield distinct keys');
    assert.notEqual(key1, key4, 'Chart IDs must yield distinct keys');
    assert.notEqual(key1, key5, 'Feed types must yield distinct keys');
    assert.equal(key1, 'feed:chart:IN:hindi:PL1111111111111111');
  });

  test('cold call fetches from provider and populates feed cache', async () => {
    let providerCalls = 0;
    const fakeTracks = [
      { videoId: 'vid1', title: 'Song 1', artist: 'Artist 1', duration: 180 },
      { videoId: 'vid2', title: 'Song 2', artist: 'Artist 2', duration: 210 },
    ];

    const origGetChart = ytmusic.getChartTracks;
    ytmusic.getChartTracks = async () => {
      providerCalls++;
      return fakeTracks;
    };

    try {
      const res = await DiscoveryFeedService.getChartTracks({
        country: 'IN',
        language: 'Hindi',
        chartId: 'PL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX',
      });

      assert.equal(providerCalls, 1, 'Cold call must query provider');
      assert.equal(res.length, 2);
      assert.equal(_feedCache.size, 1, 'Cache must store result');
    } finally {
      ytmusic.getChartTracks = origGetChart;
    }
  });

  test('warm call serves from cache without hitting provider', async () => {
    let providerCalls = 0;
    const fakeTracks = [
      { videoId: 'vid1', title: 'Song 1', artist: 'Artist 1', duration: 180 },
    ];

    const origGetChart = ytmusic.getChartTracks;
    ytmusic.getChartTracks = async () => {
      providerCalls++;
      return fakeTracks;
    };

    try {
      // First call (cold)
      await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(providerCalls, 1);

      // Second call (warm)
      const cachedRes = await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(providerCalls, 1, 'Warm call must NOT call provider again');
      assert.equal(cachedRes.length, 1);
    } finally {
      ytmusic.getChartTracks = origGetChart;
    }
  });

  test('concurrent requests coalesce into a single upstream fetch (in-flight deduplication)', async () => {
    let providerCalls = 0;
    const fakeTracks = [
      { videoId: 'vid_dedup', title: 'Dedup Song', artist: 'Artist Dedup', duration: 200 },
    ];

    const origGetChart = ytmusic.getChartTracks;
    ytmusic.getChartTracks = async () => {
      providerCalls++;
      await new Promise((r) => setTimeout(r, 25));
      return fakeTracks;
    };

    try {
      const requests = Array.from({ length: 10 }, () =>
        DiscoveryFeedService.getChartTracks({
          country: 'IN',
          language: 'Hindi',
          chartId: 'PL4fGSI1pDJn5RgLW0Sb_zECecWdH_4zOX',
        })
      );

      const results = await Promise.all(requests);
      assert.equal(providerCalls, 1, '10 concurrent calls must result in exactly 1 provider call');
      assert.equal(results.length, 10);
      results.forEach((r) => {
        assert.equal(r.length, 1);
        assert.equal(r[0].videoId, 'vid_dedup');
      });
      assert.equal(_inFlightFeeds.size, 0, 'In-flight map must be cleaned up after resolution');
    } finally {
      ytmusic.getChartTracks = origGetChart;
    }
  });

  test('empty response from provider is NOT cached and in-flight map is cleared', async () => {
    let providerCalls = 0;
    const origGetChart = ytmusic.getChartTracks;
    ytmusic.getChartTracks = async () => {
      providerCalls++;
      return [];
    };

    try {
      const res = await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.equal(res.length, 0);
      assert.equal(providerCalls, 1);
      assert.equal(_feedCache.size, 0, 'Empty result must not be cached');
      assert.equal(_inFlightFeeds.size, 0, 'In-flight map must be clean');
    } finally {
      ytmusic.getChartTracks = origGetChart;
    }
  });

  test('provider error is caught cleanly, returns empty array, and cleans up in-flight map', async () => {
    const origGetChart = ytmusic.getChartTracks;
    ytmusic.getChartTracks = async () => {
      throw new Error('Simulated network timeout');
    };

    try {
      const res = await DiscoveryFeedService.getChartTracks({ country: 'IN', language: 'Hindi' });
      assert.deepEqual(res, []);
      assert.equal(_feedCache.size, 0, 'Failed fetch must not be cached');
      assert.equal(_inFlightFeeds.size, 0, 'In-flight map must be clean after error');
    } finally {
      ytmusic.getChartTracks = origGetChart;
    }
  });
});
