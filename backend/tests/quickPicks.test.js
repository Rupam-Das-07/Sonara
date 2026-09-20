'use strict';

/**
 * quickPicks.test.js — Unit tests for the server-side Quick Picks engine.
 *
 * No network access. Every test exercises the pure helpers that shape the
 * response: playability sanitization, per-artist variety, and DTO shape. These
 * are the guarantees that keep the "every Quick Pick must be playable" and
 * "preserve the Web variety behaviour" rules from silently regressing.
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const {
  balanceVariety,
  sanitizePlayable,
  artistKey,
  toDTO,
  QUICK_PICKS_COUNT,
  MAX_PER_ARTIST,
  PRIMARY_QUERY,
  SAFE_POOL_QUERIES,
} = require('../src/discovery/quickPicksService');

// A syntactically valid 11-char YouTube videoId for fixtures.
const VID = (n) => `vid_${String(n)}`.padEnd(11, '0').slice(0, 11);

describe('Quick Picks — constants preserve the Web behaviour', () => {
  it('caps the surface at 12 picks', () => {
    assert.equal(QUICK_PICKS_COUNT, 12);
  });

  it('allows at most 2 tracks per artist', () => {
    assert.equal(MAX_PER_ARTIST, 2);
  });

  it('uses the Web primary query and SAFE_POOL fallback', () => {
    assert.equal(PRIMARY_QUERY, 'trending global songs');
    assert.deepEqual(SAFE_POOL_QUERIES, [
      'trending bollywood',
      'latest hindi songs',
      'romantic hindi hits',
      'bollywood party songs',
      'hindi chill vibes',
    ]);
  });
});

describe('Quick Picks — sanitizePlayable enforces videoId-only playability', () => {
  it('drops tracks with a null videoId (JioSaavn-style rows are unplayable)', () => {
    const input = [
      { videoId: null, id: null, title: 'JioSaavn Song', artist: 'A' },
      { videoId: VID(1), title: 'Real Song', artist: 'B' },
    ];
    const out = sanitizePlayable(input);
    assert.equal(out.length, 1);
    assert.equal(out[0].videoId, VID(1));
  });

  it('drops ids that are not 11-char YouTube videoIds (e.g. curated_1 stub)', () => {
    const input = [
      { id: 'curated_1', videoId: 'curated_1', title: 'Stub', artist: 'A' },
      { videoId: VID(2), title: 'Real', artist: 'B' },
    ];
    const out = sanitizePlayable(input);
    assert.equal(out.length, 1);
    assert.equal(out[0].videoId, VID(2));
  });

  it('falls back to id when videoId is absent but id is a valid videoId', () => {
    const out = sanitizePlayable([{ id: VID(3), title: 'T', artist: 'A' }]);
    assert.equal(out.length, 1);
  });

  it('de-duplicates by videoId, keeping first occurrence', () => {
    const out = sanitizePlayable([
      { videoId: VID(4), title: 'First', artist: 'A' },
      { videoId: VID(4), title: 'Dup', artist: 'A' },
      { videoId: VID(5), title: 'Second', artist: 'B' },
    ]);
    assert.equal(out.length, 2);
    assert.equal(out[0].title, 'First');
  });

  it('tolerates non-array input', () => {
    assert.deepEqual(sanitizePlayable(null), []);
    assert.deepEqual(sanitizePlayable(undefined), []);
  });
});

describe('Quick Picks — artistKey grouping', () => {
  it('keys on the first comma-separated artist, lowercased', () => {
    assert.equal(artistKey('Arijit Singh, Shreya Ghoshal'), 'arijit singh');
    assert.equal(artistKey('ARIJIT SINGH'), 'arijit singh');
  });

  it('is safe on empty/undefined', () => {
    assert.equal(artistKey(''), '');
    assert.equal(artistKey(undefined), '');
  });
});

describe('Quick Picks — balanceVariety', () => {
  it('caps each artist at MAX_PER_ARTIST', () => {
    const input = [
      { videoId: VID(1), artist: 'A' },
      { videoId: VID(2), artist: 'A' },
      { videoId: VID(3), artist: 'A' }, // third A must be dropped
      { videoId: VID(4), artist: 'B' },
    ];
    const out = balanceVariety(input);
    const aCount = out.filter((t) => artistKey(t.artist) === 'a').length;
    assert.equal(aCount, MAX_PER_ARTIST);
    assert.equal(out.length, 3);
  });

  it('avoids consecutive same-artist when an alternative exists', () => {
    const input = [
      { videoId: VID(1), artist: 'A' },
      { videoId: VID(2), artist: 'A' },
      { videoId: VID(3), artist: 'B' },
      { videoId: VID(4), artist: 'C' },
    ];
    const out = balanceVariety(input);
    for (let i = 1; i < out.length; i++) {
      assert.notEqual(
        artistKey(out[i].artist),
        artistKey(out[i - 1].artist),
        `positions ${i - 1},${i} share an artist`
      );
    }
  });

  it('accepts a consecutive repeat only when nothing else remains', () => {
    // Two A's and nothing else: capping keeps both, and there is no alternative,
    // so the second is accepted rather than dropped.
    const out = balanceVariety([
      { videoId: VID(1), artist: 'A' },
      { videoId: VID(2), artist: 'A' },
    ]);
    assert.equal(out.length, 2);
  });

  it('preserves every distinct-artist track', () => {
    const input = [
      { videoId: VID(1), artist: 'A' },
      { videoId: VID(2), artist: 'B' },
      { videoId: VID(3), artist: 'C' },
    ];
    assert.equal(balanceVariety(input).length, 3);
  });
});

describe('Quick Picks — toDTO shape matches routes/search.js toTrackDTO', () => {
  it('emits exactly the client-safe fields, deriving artwork from the last thumbnail', () => {
    const dto = toDTO({
      id: VID(9),
      videoId: VID(9),
      title: 'Song',
      artist: 'Artist',
      album: 'Album',
      duration: 210,
      thumbnails: [{ url: 'small.jpg' }, { url: 'large.jpg' }],
      resultType: 'song',
      // internal fields that must NOT leak:
      qualityScore: 0.9,
      rawTitle: 'Song (Official)',
    });

    assert.deepEqual(Object.keys(dto).sort(), [
      'album', 'artist', 'artworkUrl', 'duration', 'id', 'thumbnails', 'title', 'videoId',
    ].concat(['resultType']).sort());
    assert.equal(dto.artworkUrl, 'large.jpg');
    assert.equal(dto.qualityScore, undefined);
    assert.equal(dto.rawTitle, undefined);
  });

  it('yields an empty artworkUrl when there are no thumbnails', () => {
    const dto = toDTO({ id: VID(8), videoId: VID(8), title: 'T', artist: 'A', duration: 100, resultType: 'song' });
    assert.equal(dto.artworkUrl, '');
    assert.deepEqual(dto.thumbnails, []);
  });
});
