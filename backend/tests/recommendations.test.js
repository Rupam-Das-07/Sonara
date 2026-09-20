'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { filterRecommendations } = require('../src/recommendation/RecommendationFilter');
const { getAdjacency } = require('../src/discovery/artistAdjacencyMap');

describe('Recommendation & Discovery Graph', () => {
  it('filterRecommendations rejects duplicates and junk titles', () => {
    const raw = [
      { id: '1', videoId: '1', title: 'Good Song', artist: 'Artist A' },
      { id: '2', videoId: '2', title: 'Good Song (Podcast Interview)', artist: 'Artist A' },
      { id: '1', videoId: '1', title: 'Good Song Duplicate', artist: 'Artist A' },
    ];
    const filtered = filterRecommendations(raw);
    assert.equal(filtered.length, 1);
    assert.equal(filtered[0].videoId, '1');
  });

  it('getAdjacency returns related artists from discovery graph', () => {
    const arijit = getAdjacency('Arijit Singh');
    assert.notEqual(arijit, null);
    assert.equal(Array.isArray(arijit.related), true);
    assert.equal(arijit.related.length > 0, true);
  });

  it('getSimilarCandidates maps candidate.similarity to playableTrack.lbScore (F-11)', async () => {
    const lb = require('../src/recommendation/ListenBrainzService');
    const mb = require('../src/identity/MusicBrainzService');
    const { identityStore } = require('../src/identity/IdentityStore');

    const origFetchMeta = mb.fetchImmediateMetadata;
    const origGetSimilar = lb.getSimilarRecordings;
    const origGetBatch = lb.getRecordingMetadataBatch;
    const origHas = identityStore.has;
    const origGet = identityStore.get;
    const origReverseGet = identityStore.reverseGet;

    try {
      mb.fetchImmediateMetadata = async () => ({ artistMbid: 'artist-mbid-1' });
      lb.getSimilarRecordings = async () => ({
        candidates: [{ mbid: 'cand-mbid-1', similarity: 0.9421 }],
      });
      lb.getRecordingMetadataBatch = async () => ({
        'cand-mbid-1': { title: 'Similar Song', artistName: 'Similar Artist' },
      });
      identityStore.has = (vid) => vid === 'seed-vid-1';
      identityStore.get = (vid) => (vid === 'seed-vid-1' ? { mbid: 'seed-mbid-1' } : null);
      identityStore.reverseGet = (mbid) => (mbid === 'cand-mbid-1' ? 'resolved-vid-1' : null);

      delete require.cache[require.resolve('../src/recommendation/recommendationService')];
      const RecommendationService = require('../src/recommendation/recommendationService');

      const result = await RecommendationService.getSimilarCandidates('seed-vid-1');

      assert.equal(result.success, true);
      assert.equal(result.tracks.length, 1);
      assert.equal(result.tracks[0].lbScore, 0.9421);
    } finally {
      mb.fetchImmediateMetadata = origFetchMeta;
      lb.getSimilarRecordings = origGetSimilar;
      lb.getRecordingMetadataBatch = origGetBatch;
      identityStore.has = origHas;
      identityStore.get = origGet;
      identityStore.reverseGet = origReverseGet;
      delete require.cache[require.resolve('../src/recommendation/recommendationService')];
    }
  });

  describe('getRadioCandidates — characterization', () => {
    it('returns successful radio response with projected tracks on happy path', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getWatchPlaylist;

      try {
        ytmusic.getWatchPlaylist = async (videoId) => [
          {
            id: 'radio-track-1',
            videoId: 'radio-track-1',
            title: 'Radio Song 1',
            artist: 'Radio Artist 1',
            album: 'Radio Album 1',
            duration: 210,
            thumbnails: [{ url: 'https://img/small.jpg' }, { url: 'https://img/large.jpg' }],
          },
          {
            id: 'radio-track-2',
            videoId: 'radio-track-2',
            title: 'Radio Song 2',
            artist: 'Radio Artist 2',
            album: 'Radio Album 2',
            duration: 195,
            artworkUrl: 'https://img/direct.jpg',
            thumbnails: [],
          },
        ];

        const result = await RecommendationService.getRadioCandidates('seed-radio-1');

        assert.equal(result.success, true);
        assert.equal(result.source, 'radio');
        assert.equal(result.seed, 'seed-radio-1');
        assert.equal(result.error, null);
        assert.equal(result.tracks.length, 2);
        assert.equal(result.metadata.generated, 2);
        assert.equal(result.metadata.filtered, 0);
        assert.equal(typeof result.metadata.executionTimeMs, 'number');

        const t1 = result.tracks[0];
        assert.equal(t1.id, 'radio-track-1');
        assert.equal(t1.videoId, 'radio-track-1');
        assert.equal(t1.title, 'Radio Song 1');
        assert.equal(t1.artist, 'Radio Artist 1');
        assert.equal(t1.album, 'Radio Album 1');
        assert.equal(t1.duration, 210);
        assert.equal(t1.artworkUrl, 'https://img/large.jpg');
        assert.equal(t1.resultType, 'song');

        const t2 = result.tracks[1];
        assert.equal(t2.artworkUrl, 'https://img/direct.jpg');
        assert.equal(t2.resultType, 'song');
      } finally {
        ytmusic.getWatchPlaylist = orig;
      }
    });

    it('handles fallback values when track properties are missing', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getWatchPlaylist;

      try {
        ytmusic.getWatchPlaylist = async () => [
          {
            id: 'sparse-1',
          },
        ];

        const result = await RecommendationService.getRadioCandidates('seed-sparse');
        assert.equal(result.success, true);
        assert.equal(result.tracks.length, 1);

        const t = result.tracks[0];
        assert.equal(t.id, 'sparse-1');
        assert.equal(t.videoId, 'sparse-1');
        assert.equal(t.title, 'Unknown Title');
        assert.equal(t.artist, 'Unknown Artist');
        assert.equal(t.album, '');
        assert.equal(t.duration, 0);
        assert.equal(t.artworkUrl, '');
        assert.deepEqual(t.thumbnails, []);
        assert.equal(t.resultType, 'song');
      } finally {
        ytmusic.getWatchPlaylist = orig;
      }
    });

    it('filters raw recommendations before mapping (rejects junk, duplicates, out-of-bound durations)', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getWatchPlaylist;

      try {
        ytmusic.getWatchPlaylist = async () => [
          { id: 'good-1', title: 'Good Song', artist: 'Artist 1', duration: 180 },
          { id: 'junk-podcast', title: 'Song (Podcast Interview)', artist: 'Artist 1', duration: 180 },
          { id: 'junk-reaction', title: 'Song (Reaction Video)', artist: 'Artist 1', duration: 180 },
          { id: 'too-short', title: 'Short Clip', artist: 'Artist 1', duration: 15 },
          { id: 'too-long', title: 'Endless Mix', artist: 'Artist 1', duration: 1200 },
          { id: 'good-1', title: 'Good Song Duplicate', artist: 'Artist 1', duration: 180 },
        ];

        const result = await RecommendationService.getRadioCandidates('seed-filter');
        assert.equal(result.success, true);
        assert.equal(result.tracks.length, 1);
        assert.equal(result.tracks[0].id, 'good-1');
      } finally {
        ytmusic.getWatchPlaylist = orig;
      }
    });

    it('returns structured error envelope when videoId is missing', async () => {
      const RecommendationService = require('../src/recommendation/recommendationService');
      const result = await RecommendationService.getRadioCandidates('');

      assert.equal(result.success, false);
      assert.equal(result.source, 'radio');
      assert.equal(result.seed, '');
      assert.deepEqual(result.tracks, []);
      assert.equal(result.error, 'videoId is required');
      assert.equal(result.metadata.generated, 0);
      assert.equal(result.metadata.filtered, 0);
    });

    it('returns structured error envelope when provider throws without uncaught exception', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getWatchPlaylist;

      try {
        ytmusic.getWatchPlaylist = async () => {
          throw new Error('Upstream timeout');
        };

        const result = await RecommendationService.getRadioCandidates('seed-fail');
        assert.equal(result.success, false);
        assert.equal(result.source, 'radio');
        assert.equal(result.seed, 'seed-fail');
        assert.deepEqual(result.tracks, []);
        assert.equal(result.error, 'Upstream timeout');
      } finally {
        ytmusic.getWatchPlaylist = orig;
      }
    });
  });

  describe('getRelatedCandidates — characterization', () => {
    it('returns successful related response with projected tracks on happy path', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getRelatedSongs;

      try {
        ytmusic.getRelatedSongs = async (videoId) => [
          {
            id: 'related-track-1',
            videoId: 'related-track-1',
            title: 'Related Song 1',
            artist: 'Related Artist 1',
            album: 'Related Album 1',
            duration: 240,
            thumbnails: [{ url: 'https://img/rel-thumb.jpg' }],
          },
        ];

        const result = await RecommendationService.getRelatedCandidates('seed-rel-1');

        assert.equal(result.success, true);
        assert.equal(result.source, 'related');
        assert.equal(result.seed, 'seed-rel-1');
        assert.equal(result.error, null);
        assert.equal(result.tracks.length, 1);
        assert.equal(result.metadata.generated, 1);
        assert.equal(result.metadata.filtered, 0);

        const t = result.tracks[0];
        assert.equal(t.id, 'related-track-1');
        assert.equal(t.videoId, 'related-track-1');
        assert.equal(t.title, 'Related Song 1');
        assert.equal(t.artist, 'Related Artist 1');
        assert.equal(t.album, 'Related Album 1');
        assert.equal(t.duration, 240);
        assert.equal(t.artworkUrl, 'https://img/rel-thumb.jpg');
        assert.equal(t.resultType, 'song');
      } finally {
        ytmusic.getRelatedSongs = orig;
      }
    });

    it('handles fallback values when related track properties are missing', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getRelatedSongs;

      try {
        ytmusic.getRelatedSongs = async () => [
          {
            id: 'sparse-rel-1',
          },
        ];

        const result = await RecommendationService.getRelatedCandidates('seed-sparse-rel');
        assert.equal(result.success, true);
        assert.equal(result.tracks.length, 1);

        const t = result.tracks[0];
        assert.equal(t.id, 'sparse-rel-1');
        assert.equal(t.videoId, 'sparse-rel-1');
        assert.equal(t.title, 'Unknown Title');
        assert.equal(t.artist, 'Unknown Artist');
        assert.equal(t.album, '');
        assert.equal(t.duration, 0);
        assert.equal(t.artworkUrl, '');
        assert.deepEqual(t.thumbnails, []);
        assert.equal(t.resultType, 'song');
      } finally {
        ytmusic.getRelatedSongs = orig;
      }
    });

    it('filters raw related recommendations before mapping', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getRelatedSongs;

      try {
        ytmusic.getRelatedSongs = async () => [
          { id: 'rel-good', title: 'Valid Related', artist: 'Artist 1', duration: 150 },
          { id: 'rel-podcast', title: 'Podcast Special', artist: 'Artist 1', duration: 150 },
          { id: 'rel-good', title: 'Duplicate Related', artist: 'Artist 1', duration: 150 },
        ];

        const result = await RecommendationService.getRelatedCandidates('seed-filter-rel');
        assert.equal(result.success, true);
        assert.equal(result.tracks.length, 1);
        assert.equal(result.tracks[0].id, 'rel-good');
      } finally {
        ytmusic.getRelatedSongs = orig;
      }
    });

    it('returns structured error envelope when videoId is missing', async () => {
      const RecommendationService = require('../src/recommendation/recommendationService');
      const result = await RecommendationService.getRelatedCandidates(null);

      assert.equal(result.success, false);
      assert.equal(result.source, 'related');
      assert.equal(result.seed, null);
      assert.deepEqual(result.tracks, []);
      assert.equal(result.error, 'videoId is required');
    });

    it('returns structured error envelope when related provider throws', async () => {
      const ytmusic = require('../src/search/ytmusicProvider');
      const RecommendationService = require('../src/recommendation/recommendationService');
      const orig = ytmusic.getRelatedSongs;

      try {
        ytmusic.getRelatedSongs = async () => {
          throw new Error('Network error');
        };

        const result = await RecommendationService.getRelatedCandidates('seed-fail-rel');
        assert.equal(result.success, false);
        assert.equal(result.source, 'related');
        assert.equal(result.seed, 'seed-fail-rel');
        assert.deepEqual(result.tracks, []);
        assert.equal(result.error, 'Network error');
      } finally {
        ytmusic.getRelatedSongs = orig;
      }
    });
  });
});

