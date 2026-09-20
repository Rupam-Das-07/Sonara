'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  guardModulesShape,
  guardSearchShape,
  guardSongShape,
  extractBestArtwork,
  extractArtistName,
  normalizeV2Song,
} = require('../src/discovery/providers/jiosaavnDiscoveryProvider');

describe('JioSaavn V2 Provider', () => {
  it('guardModulesShape rejects invalid structures', () => {
    assert.equal(guardModulesShape(null), null);
    assert.equal(guardModulesShape({ status: 'Failed' }), null);
    assert.deepEqual(guardModulesShape({ status: 'Success', data: { trending: [] } }), { trending: [] });
  });

  it('guardSearchShape extracts results array', () => {
    assert.equal(guardSearchShape(null), null);
    assert.equal(guardSearchShape({ status: 'Failed' }), null);
    assert.deepEqual(
      guardSearchShape({ status: 'Success', data: { results: [{ id: '1' }] } }),
      [{ id: '1' }]
    );
  });

  it('guardSongShape validates mandatory fields', () => {
    assert.equal(guardSongShape(null), false);
    assert.equal(guardSongShape({ id: '1' }), false);
    assert.equal(guardSongShape({ name: 'Song' }), false);
    assert.equal(guardSongShape({ id: '1', name: 'Song' }), true);
  });

  it('extractBestArtwork picks highest quality image', () => {
    const images = [
      { quality: '50x50', url: 'http://img/50.jpg' },
      { quality: '150x150', url: 'http://img/150.jpg' },
      { quality: '500x500', url: 'http://img/500.jpg' },
    ];
    assert.equal(extractBestArtwork(images), 'http://img/500.jpg');
    assert.equal(extractBestArtwork('http://img/direct.jpg'), 'http://img/direct.jpg');
    assert.equal(extractBestArtwork(null), '');
  });

  it('extractArtistName handles primary_artists and subtitles', () => {
    const song1 = {
      artist_map: {
        primary_artists: [{ name: 'Arijit Singh' }, { name: 'Shreya Ghoshal' }],
      },
    };
    assert.equal(extractArtistName(song1), 'Arijit Singh, Shreya Ghoshal');

    const song2 = { subtitle: 'Pritam, Mohit Chauhan' };
    assert.equal(extractArtistName(song2), 'Pritam, Mohit Chauhan');
  });

  it('normalizeV2Song converts song to canonical track format', () => {
    const raw = {
      id: 'abc123',
      name: 'Kesariya',
      duration: 268,
      artist_map: { primary_artists: [{ name: 'Arijit Singh' }] },
      album: { name: 'Brahmastra' },
      image: [{ url: 'https://c.saavncdn.com/kesariya.jpg' }],
    };
    const track = normalizeV2Song(raw);
    assert.equal(track.id, 'abc123');
    assert.equal(track.title, 'Kesariya');
    assert.equal(track.artist, 'Arijit Singh');
    assert.equal(track.album, 'Brahmastra');
    assert.equal(track.duration, 268);
    assert.equal(track.provider, 'jiosaavn');
    assert.equal(track.artworkUrl, 'https://c.saavncdn.com/kesariya.jpg');
  });
});
