'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { scoreCandidate, findBestMatch, normalize } = require('../src/identity/IdentityMatcher');
const { mbRateLimiter } = require('../src/identity/mbRateLimiter');
const { identityStore } = require('../src/identity/IdentityStore');

describe('MusicBrainz Identity Layer', () => {
  it('normalize strips noisy tokens and metadata', () => {
    assert.equal(normalize('Fix You (Official Music Video) [4K]'), 'fix you');
    assert.equal(normalize('Song feat. Drake (feat. Travis Scott)'), 'song');
  });

  it('scoreCandidate rewards exact title, artist, and duration', () => {
    const input = { title: 'Fix You', artist: 'Coldplay', durationSeconds: 296 };
    const mbResult = {
      id: 'mbid-123',
      title: 'Fix You',
      'artist-credit': [{ artist: { name: 'Coldplay' } }],
      length: 296000,
    };
    const score = scoreCandidate(input, mbResult);
    assert.equal(score.confidence >= 0.85, true);
    assert.equal(score.matchedFields.title.exact, true);
    assert.equal(score.matchedFields.artist.exact, true);
  });

  it('findBestMatch selects top candidate above threshold', () => {
    const input = { title: 'Yellow', artist: 'Coldplay', durationSeconds: 269 };
    const candidates = [
      { id: 'mb-bad', title: 'Something Else', 'artist-credit': [{ artist: { name: 'Other' } }] },
      { id: 'mb-good', title: 'Yellow', 'artist-credit': [{ artist: { name: 'Coldplay' } }], length: 269000 },
    ];
    const match = findBestMatch(input, candidates);
    assert.equal(match.resolved, true);
    assert.equal(match.mbid, 'mb-good');
  });

  it('mbRateLimiter queues and tracks metrics', async () => {
    const metrics = mbRateLimiter.getMetrics();
    assert.equal(typeof metrics.currentDepth, 'number');
    assert.equal(typeof metrics.drainIntervalMs, 'number');
  });

  it('IdentityStore persists and looks up mappings', () => {
    identityStore.set('testVid123', {
      mbid: 'testMbid456',
      confidence: 0.95,
      resolved: true,
      matchedFields: {},
    });
    assert.equal(identityStore.has('testVid123'), true);
    const entry = identityStore.get('testVid123');
    assert.equal(entry.mbid, 'testMbid456');
    assert.equal(identityStore.reverseGet('testMbid456'), 'testVid123');
  });
});
