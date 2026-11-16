'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { YouTubeStreamProvider } = require('../../src/stream/providers/youtubeStreamProvider');
const { AudioSourceResolver } = require('../../src/stream/AudioSourceResolver');

function provider(metadata = {}) {
  const url = 'https://r3.googlevideo.com/videoplayback?expire=9999999999';
  return new YouTubeStreamProvider({
    fetchFn: async () => ({ ok: true, json: async () => ({
      audio_url: url,
      selectedStream: { identity: createHash('sha256').update(url).digest('hex'), formatId: '251',
        codec: metadata.codec || 'opus', mimeType: metadata.format || 'audio/webm',
        isAudioOnly: true, bitrateKbps: metadata.nominalBitrateKbps },
      ...metadata,
    }) }),
  });
}

test('AUTO retains the selected audio bitrate through STANDARD descriptor caching', async () => {
  const resolver = new AudioSourceResolver({
    youtubeProvider: provider({ format: 'audio/webm', codec: 'opus', nominalBitrateKbps: 137 }),
    jiosaavnProvider: { resolve: async () => assert.fail('AUTO must not resolve JioSaavn') },
  });
  for (const quality of ['AUTO', 'STANDARD']) {
    const source = await resolver.resolve({ videoId: 'dQw4w9WgXcQ' }, quality);
    assert.equal(source.qualityTier, 'STANDARD');
    assert.equal(source.provider, 'youtube');
    assert.equal(source.nominalBitrateKbps, 137);
    assert.equal(source.bitrate, 137);
    assert.equal(source.codec, 'opus');
  }
});

test('mismatched URL identity or muxed descriptor never lends an audio bitrate', async () => {
  for (const selectedStream of [null, { identity: 'wrong', isAudioOnly: true, bitrateKbps: 137 }]) {
    const source = await provider({ nominalBitrateKbps: 137, selectedStream }).resolve('dQw4w9WgXcQ');
    assert.equal(source.nominalBitrateKbps, null);
    assert.equal(source.selectedStream, null);
  }
  const url = 'https://r3.googlevideo.com/videoplayback?expire=9999999999';
  const muxed = { identity: createHash('sha256').update(url).digest('hex'), formatId: '18',
    codec: 'aac', mimeType: 'video/mp4', isAudioOnly: false, bitrateKbps: 999 };
  const source = await provider({ selectedStream: muxed }).resolve('dQw4w9WgXcQ');
  assert.equal(source.nominalBitrateKbps, null);
  assert.equal(source.selectedStream.isAudioOnly, false);
  assert.equal(new URL(source.streamUrl, 'http://localhost').searchParams.get('stream_identity'), muxed.identity);
});

test('selected AAC metadata is not replaced with a generic Opus preset', async () => {
  const source = await provider({ format: 'audio/mp4', codec: 'aac', nominalBitrateKbps: 129 }).resolve('dQw4w9WgXcQ');
  assert.equal(source.format, 'audio/mp4');
  assert.equal(source.codec, 'aac');
  assert.equal(source.bitrate, 129);
});

test('missing or invalid selected bitrate cannot reuse the legacy 160 kbps default', async () => {
  for (const value of [undefined, null, 0, -1, '160', NaN, Infinity]) {
    const source = await provider({ bitrate: 160, nominalBitrateKbps: value }).resolve('dQw4w9WgXcQ');
    assert.equal(source.nominalBitrateKbps, null);
    assert.equal(source.bitrate, 0);
  }
});
