'use strict';

/**
 * discoveryRepairs.test.js — Regression tests for the Stage 0 discovery repairs.
 *
 * Each test here corresponds to a defect that shipped silently: the code ran, the
 * route returned HTTP 200, and the payload was empty or unplayable. These tests
 * exist so that failure mode cannot return unnoticed.
 *
 * No network access. Every test exercises pure logic or module surface area.
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const { isEligible } = require('../src/discovery/EligibilityEngine');
const { isJunkVariant, normalizeTitle, isRealBrowseId } = require('../src/discovery/artistCatalogService');
const { evaluateCandidate, sharesArtistToken } = require('../src/discovery/trendingService');
const { slugify, FEATURED_ARTISTS, CORE_ANCHORS, upgradeAvatarResolution, getFeaturedArtists } = require('../src/discovery/featuredArtistsService');
const { PLAYLIST_DEFINITIONS, ARTIST_PLAYLIST_DEFINITIONS, getAllDefinitions, getDefinitionById, getDefinitionsForArtist } = require('../src/discovery/PlaylistDefinitions');
const ytmusic = require('../src/search/ytmusicProvider');

const PERMISSIVE = {
  allowRemix: true, allowLofi: true, allowSlowedReverb: true,
  allowInstrumental: true, excludedGenres: [],
};

describe('Stage 0 — eligibility filter is actually consulted', () => {
  // PlaylistService previously called isEligible(...) and discarded the result,
  // treating the returned {eligible, reason} object as truthy. Every track passed.
  it('isEligible returns an object whose truthiness is NOT the verdict', () => {
    const rejected = isEligible({ title: 'Intro', artist: 'A', duration: 15 }, PERMISSIVE);
    assert.equal(rejected.eligible, false);
    // The trap: the rejection object itself is truthy.
    assert.ok(rejected, 'rejection object is truthy — callers must read .eligible');
    assert.equal(typeof rejected.reason, 'string');
  });

  it('rejects a track whose duration was coerced to 0 by a DTO', () => {
    // The outbound playlist DTO coerces unknown duration to 0. Evaluating
    // eligibility on the DTO instead of the raw track rejected every track whose
    // duration the provider did not report, because 0 < MIN_DURATION_SECONDS.
    const asDto = isEligible({ title: 'Song', artist: 'A', duration: 0 }, PERMISSIVE);
    assert.equal(asDto.eligible, false);
    // V2 F-09 update: null duration is also explicitly rejected (invalid_or_missing_duration).
    // Tracks from the provider with null duration are broken stubs that cannot be evaluated.
    // The eligibility engine must never guess a duration for a track that reports none.
    const asRaw = isEligible({ title: 'Song', artist: 'A', duration: null }, PERMISSIVE);
    assert.equal(asRaw.eligible, false, 'null duration must be rejected as invalid (F-09)');
    assert.equal(asRaw.reason, 'invalid_or_missing_duration');
  });

});

describe('Stage 0 — ytmusicProvider exposes the recommendation bindings', () => {
  // recommendationService called three methods this module never exported, so
  // each was `undefined` and threw a TypeError inside a try/catch.
  for (const name of ['getWatchPlaylist', 'getRelatedSongs', 'getSongDetails']) {
    it(`exports ${name} as a function`, () => {
      assert.equal(typeof ytmusic[name], 'function', `${name} must be callable`);
    });
  }

  it('still exports the untouched production search path', () => {
    assert.equal(typeof ytmusic.searchSongs, 'function');
    assert.equal(typeof ytmusic.searchMetrics, 'object');
  });
});

describe('Stage 0 — browseId shape guard', () => {
  // Python only resolves an artist name when NO browseId is supplied, so passing
  // a slug in the browseId position silently degrades the catalog to deep search.
  it('accepts real YTMusic browseIds', () => {
    assert.equal(isRealBrowseId('UCPRWWK1Vp1zHRnGKmSSXtaA'), true);
    assert.equal(isRealBrowseId('MPLAUC123456789012'), true);
  });

  it('rejects slugs, display names and empty values', () => {
    assert.equal(isRealBrowseId('arijit-singh'), false);
    assert.equal(isRealBrowseId('Arijit Singh'), false);
    assert.equal(isRealBrowseId('UCshort'), false);
    assert.equal(isRealBrowseId(''), false);
    assert.equal(isRealBrowseId(null), false);
    assert.equal(isRealBrowseId(undefined), false);
  });
});

describe('Stage 0 — artist catalog cleaning', () => {
  it('collapses title variants of the same recording to one key', () => {
    const key = normalizeTitle('Tum Hi Ho');
    assert.equal(normalizeTitle('Tum Hi Ho (Lyrical)'), key);
    assert.equal(normalizeTitle('Tum Hi Ho (Official Video)'), key);
    assert.equal(normalizeTitle('Tum Hi Ho [HD]'), key);
  });

  it('strips the "(From ...)" soundtrack suffix', () => {
    assert.equal(normalizeTitle('Kesariya (From "Brahmastra")'), normalizeTitle('Kesariya'));
  });

  it('flags karaoke, 8D and cover uploads as junk', () => {
    assert.equal(isJunkVariant('Song - Karaoke'), true);
    assert.equal(isJunkVariant('Song 8D Audio'), true);
    assert.equal(isJunkVariant('Song (Cover)'), true);
    assert.equal(isJunkVariant('Song (Slowed + Reverb)'), true);
  });

  it('does not flag a plain release title', () => {
    assert.equal(isJunkVariant('Tum Hi Ho'), false);
    assert.equal(isJunkVariant('Kesariya'), false);
  });
});

describe('Stage 0 — trending videoId resolution', () => {
  // Every trending item previously shipped with videoId: null, so the whole
  // module rendered but nothing was playable.
  const item = {
    id: 'js1',
    title: 'Kesariya (From "Brahmastra")',
    artist: 'Arijit Singh, Pritam',
    duration: 268,
  };

  it('accepts a genuine match and reports it as exact', () => {
    const v = evaluateCandidate(item, {
      videoId: 'BddP6PYo2gs', title: 'Kesariya', artist: 'Arijit Singh', duration: 270,
    });
    assert.equal(v.ok, true);
    assert.equal(v.exact, true);
  });

  it('refuses a null videoId — the original defect', () => {
    const v = evaluateCandidate(item, {
      videoId: null, title: 'Kesariya', artist: 'Arijit Singh', duration: 270,
    });
    assert.equal(v.ok, false);
    assert.equal(v.reason, 'no_video_id');
  });

  it('refuses a malformed videoId so unplayable ids cannot ship', () => {
    const v = evaluateCandidate(item, {
      videoId: 'short', title: 'Kesariya', artist: 'Arijit Singh', duration: 270,
    });
    assert.equal(v.ok, false);
    assert.equal(v.reason, 'bad_video_id');
  });

  it('refuses karaoke and remix substitutes', () => {
    const karaoke = evaluateCandidate(item, {
      videoId: 'aaaaaaaaaaa', title: 'Kesariya - Karaoke', artist: 'Arijit Singh', duration: 270,
    });
    assert.equal(karaoke.reason, 'junk_variant');
  });

  it('refuses a different song by the same artist', () => {
    const v = evaluateCandidate(item, {
      videoId: 'eeeeeeeeeee', title: 'Tum Hi Ho', artist: 'Arijit Singh', duration: 270,
    });
    assert.equal(v.reason, 'title_mismatch');
  });

  it('refuses the same title by an unrelated artist', () => {
    const v = evaluateCandidate(item, {
      videoId: 'ccccccccccc', title: 'Kesariya', artist: 'Some Cover Guy', duration: 270,
    });
    assert.equal(v.reason, 'artist_mismatch');
  });

  it('refuses an extended mix but tolerates a different master', () => {
    const extended = evaluateCandidate(item, {
      videoId: 'ddddddddddd', title: 'Kesariya', artist: 'Arijit Singh', duration: 600,
    });
    assert.equal(extended.reason, 'duration_mismatch');

    const master = evaluateCandidate(item, {
      videoId: 'ddddddddddd', title: 'Kesariya', artist: 'Arijit Singh', duration: 285,
    });
    assert.equal(master.ok, true);
  });

  it('does not reject on duration when either side is unknown', () => {
    const v = evaluateCandidate(item, {
      videoId: 'fffffffffff', title: 'Kesariya', artist: 'Pritam', duration: 0,
    });
    assert.equal(v.ok, true);
  });

  it('matches collaborating artists by token overlap', () => {
    assert.equal(sharesArtistToken('Arijit Singh, Pritam', 'Arijit Singh'), true);
    assert.equal(sharesArtistToken('Arijit Singh', 'Shreya Ghoshal'), false);
  });
});

describe('Stage 0 — featured artists are renderable and navigable', () => {
  // The route previously returned { name, genre } only: no id to navigate with
  // and no image to render.
  it('produces a stable URL-safe id for every artist', () => {
    assert.equal(slugify('Arijit Singh'), 'arijit-singh');
    assert.equal(slugify('A.R. Rahman'), 'a-r-rahman');
    assert.equal(slugify('The Weeknd'), 'the-weeknd');
  });

  it('slugify is deterministic', () => {
    assert.equal(slugify('A.R. Rahman'), slugify('A.R. Rahman'));
  });

  it('every roster entry has a name and a genre', () => {
    assert.ok(FEATURED_ARTISTS.length > 0);
    for (const artist of FEATURED_ARTISTS) {
      assert.equal(typeof artist.name, 'string');
      assert.ok(artist.name.length > 0);
      assert.equal(typeof artist.genre, 'string');
      assert.ok(artist.genre.length > 0);
    }
  });

  it('slugs are unique across the roster', () => {
    const slugs = FEATURED_ARTISTS.map((a) => slugify(a.name));
    assert.equal(new Set(slugs).size, slugs.length);
  });
});

// ── Phase 6 — Dynamic Featured Artists & SWR Anchors ────────────────────────

describe('Phase 6 — Featured Artists: Identity, Artwork, and SWR Anchors', () => {
  it('slugify handles accented Unicode via NFKD normalization', () => {
    assert.equal(slugify('Beyoncé'), 'beyonce');
    assert.equal(slugify('Café Del Mar'), 'cafe-del-mar');
    assert.equal(slugify('Sigur Rós'), 'sigur-ros');
  });

  it('CORE_ANCHORS contains exactly 6 verified anchors with valid browseIds', () => {
    assert.equal(CORE_ANCHORS.length, 6);
    const BROWSE_ID_RE = /^UC[A-Za-z0-9_-]{22}$/;
    for (const anchor of CORE_ANCHORS) {
      assert.equal(typeof anchor.name, 'string');
      assert.ok(anchor.name.length > 0);
      assert.equal(typeof anchor.browseId, 'string');
      assert.ok(BROWSE_ID_RE.test(anchor.browseId), `Invalid browseId format for ${anchor.name}: ${anchor.browseId}`);
      assert.equal(typeof anchor.genre, 'string');
      assert.ok(anchor.genre.length > 0);
      assert.equal(typeof anchor.imageUrl, 'string');
      assert.ok(anchor.imageUrl.startsWith('https://'));
    }
  });

  it('upgradeAvatarResolution upgrades Google CDN dimensions deterministically', () => {
    // Standard dimension parameters
    assert.equal(
      upgradeAvatarResolution('https://lh3.googleusercontent.com/abc=w120-h120-p-l90-rj'),
      'https://lh3.googleusercontent.com/abc=w540-h540-p-l90-rj'
    );
    assert.equal(
      upgradeAvatarResolution('https://yt3.googleusercontent.com/xyz=w544-h544-l90-rj'),
      'https://yt3.googleusercontent.com/xyz=w540-h540-p-l90-rj'
    );
    assert.equal(
      upgradeAvatarResolution('https://lh3.googleusercontent.com/banner=w540-h225-p-l90-rj'),
      'https://lh3.googleusercontent.com/banner=w540-h540-p-l90-rj'
    );
    // Single dimension parameter
    assert.equal(
      upgradeAvatarResolution('https://lh3.googleusercontent.com/item=s120-c'),
      'https://lh3.googleusercontent.com/item=s540-p-l90-rj'
    );
    // ggpht with internal -c-
    assert.equal(
      upgradeAvatarResolution('https://yt3.ggpht.com/avatar=w120-c-h120-k-c0x00ffffff-no-l90-rj'),
      'https://yt3.ggpht.com/avatar=w540-h540-p-l90-rj'
    );
    // Preserves already 540
    assert.equal(
      upgradeAvatarResolution('https://lh3.googleusercontent.com/abc=w540-h540-p-l90-rj'),
      'https://lh3.googleusercontent.com/abc=w540-h540-p-l90-rj'
    );
    // Preserves non-Google URLs
    assert.equal(
      upgradeAvatarResolution('https://example.com/artist.jpg'),
      'https://example.com/artist.jpg'
    );
    // Null/undefined/empty returns null
    assert.equal(upgradeAvatarResolution(null), null);
    assert.equal(upgradeAvatarResolution(undefined), null);
    assert.equal(upgradeAvatarResolution(''), null);
    assert.equal(upgradeAvatarResolution(1234), null);
  });

  it('getFeaturedArtists returns valid DTO array with stable fallback', async () => {
    const roster = await getFeaturedArtists();
    assert.ok(Array.isArray(roster));
    assert.ok(roster.length >= 6 && roster.length <= 8);
    const BROWSE_ID_RE = /^UC[A-Za-z0-9_-]{22}$/;
    for (const item of roster) {
      assert.equal(typeof item.id, 'string');
      assert.ok(item.id.length > 0);
      assert.equal(typeof item.name, 'string');
      assert.ok(item.name.length > 0);
      assert.ok(BROWSE_ID_RE.test(item.browseId), `Invalid browseId: ${item.browseId}`);
      assert.equal(typeof item.genre, 'string');
      assert.ok(item.imageUrl === null || typeof item.imageUrl === 'string');
    }
  });
});

// ── Phase 6 — Artist Playlist Definitions & Lookups ─────────────────────────

describe('Phase 6 — Artist Playlist Definitions', () => {
  it('PLAYLIST_DEFINITIONS maintains exactly 22 general curated playlists', () => {
    assert.equal(Object.keys(PLAYLIST_DEFINITIONS).length, 22);
    assert.equal(getAllDefinitions().length, 22);
  });

  it('ARTIST_PLAYLIST_DEFINITIONS has exactly 8 new definitions', () => {
    assert.equal(Object.keys(ARTIST_PLAYLIST_DEFINITIONS).length, 8);
    const expectedIds = [
      'arijit_singh_romantic',
      'ar_rahman_soundtracks',
      'shreya_ghoshal_hits',
      'shreya_ghoshal_romantic',
      'atif_aslam_hits',
      'atif_aslam_sufi',
      'coldplay_essentials',
      'the_weeknd_essentials',
    ];
    for (const id of expectedIds) {
      assert.ok(ARTIST_PLAYLIST_DEFINITIONS[id], `Missing artist definition: ${id}`);
    }
  });

  it('Total artist playlists across Sonara is exactly 10', () => {
    const all = { ...PLAYLIST_DEFINITIONS, ...ARTIST_PLAYLIST_DEFINITIONS };
    const artistPlaylists = Object.values(all).filter((d) => d.artistName);
    assert.equal(artistPlaylists.length, 10);
  });

  it('getDefinitionById resolves definitions from both catalogs', () => {
    // Curated general
    assert.ok(getDefinitionById('chill_nights'));
    assert.equal(getDefinitionById('chill_nights').name, 'Chill Nights');
    // Pre-existing artist in curated
    assert.ok(getDefinitionById('arijit_singh'));
    assert.equal(getDefinitionById('arijit_singh').name, 'Arijit Singh Hits');
    assert.ok(getDefinitionById('ar_rahman_magic'));
    // New artist definitions
    assert.ok(getDefinitionById('arijit_singh_romantic'));
    assert.equal(getDefinitionById('arijit_singh_romantic').name, 'Arijit Singh Romantic');
    assert.ok(getDefinitionById('ar_rahman_soundtracks'));
    assert.ok(getDefinitionById('shreya_ghoshal_hits'));
    assert.ok(getDefinitionById('shreya_ghoshal_romantic'));
    assert.ok(getDefinitionById('atif_aslam_hits'));
    assert.ok(getDefinitionById('atif_aslam_sufi'));
    assert.ok(getDefinitionById('coldplay_essentials'));
    assert.ok(getDefinitionById('the_weeknd_essentials'));
    // Non-existent
    assert.equal(getDefinitionById('non_existent_id'), null);
  });

  it('getDefinitionsForArtist matches artist definitions accurately', () => {
    assert.equal(getDefinitionsForArtist('Arijit Singh').length, 2);
    assert.equal(getDefinitionsForArtist('A.R. Rahman').length, 2);
    assert.equal(getDefinitionsForArtist('Shreya Ghoshal').length, 2);
    assert.equal(getDefinitionsForArtist('Atif Aslam').length, 2);
    assert.equal(getDefinitionsForArtist('Coldplay').length, 1);
    assert.equal(getDefinitionsForArtist('The Weeknd').length, 1);
    assert.equal(getDefinitionsForArtist('Unknown Singer').length, 0);
    assert.equal(getDefinitionsForArtist(null).length, 0);
    assert.equal(getDefinitionsForArtist('').length, 0);
  });
});

// ── Phase 6 — Artist Role Semantics & Performer Membership ──────────────────

describe('Phase 6 — Artist Role Semantics & Performer Membership', () => {
  const PlaylistService = require('../src/discovery/PlaylistService');

  // Verify performer definition vs composer definition
  it('assigns artistRole: composer to A.R. Rahman playlists', () => {
    const rahman1 = getDefinitionById('ar_rahman_magic');
    const rahman2 = getDefinitionById('ar_rahman_soundtracks');
    assert.equal(rahman1.artistRole, 'composer');
    assert.equal(rahman2.artistRole, 'composer');
  });

  it('assigns artistRole: performer to vocalist and band playlists', () => {
    for (const id of [
      'arijit_singh',
      'arijit_singh_romantic',
      'shreya_ghoshal_hits',
      'shreya_ghoshal_romantic',
      'atif_aslam_hits',
      'atif_aslam_sufi',
      'coldplay_essentials',
      'the_weeknd_essentials',
    ]) {
      const def = getDefinitionById(id);
      assert.equal(def.artistRole, 'performer', `Definition ${id} must have artistRole='performer'`);
    }
  });

  it('hasTargetPerformer accepts solo tracks', () => {
    const contributors = PlaylistService.extractAllContributors({
      title: 'Tum Hi Ho',
      artist: 'Arijit Singh',
      artists: [{ name: 'Arijit Singh' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(contributors, 'Arijit Singh'), true);
  });

  it('hasTargetPerformer accepts duets for both performing artists', () => {
    const contributors = PlaylistService.extractAllContributors({
      title: 'Samjhawan',
      artist: 'Arijit Singh & Shreya Ghoshal',
      artists: [{ name: 'Arijit Singh' }, { name: 'Shreya Ghoshal' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(contributors, 'Arijit Singh'), true);
    assert.equal(PlaylistService.hasTargetPerformer(contributors, 'Shreya Ghoshal'), true);
  });

  it('hasTargetPerformer accepts featured primary and guest collaborations', () => {
    // Primary with guest
    const featGuest = PlaylistService.extractAllContributors({
      title: 'Starboy',
      artist: 'The Weeknd feat. Daft Punk',
      artists: [{ name: 'The Weeknd' }, { name: 'Daft Punk' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(featGuest, 'The Weeknd'), true);

    // Guest appearance
    const featPrimary = PlaylistService.extractAllContributors({
      title: 'Young Dumb & Broke (Remix)',
      artist: 'Khalid feat. Rae Sremmurd & Lil Yachty',
      artists: [{ name: 'Khalid' }, { name: 'Rae Sremmurd' }, { name: 'Lil Yachty' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(featPrimary, 'Rae Sremmurd'), true);
  });

  it('hasTargetPerformer rejects unrelated artists to prevent playlist contamination', () => {
    const unrelated1 = PlaylistService.extractAllContributors({
      title: 'Maula Mere Maula',
      artist: 'Roop Kumar Rathod',
      artists: [{ name: 'Roop Kumar Rathod' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(unrelated1, 'Shreya Ghoshal'), false);

    const unrelated2 = PlaylistService.extractAllContributors({
      title: 'Raanjhanaa',
      artist: 'Shiraz Uppal, Jaswinder Singh',
      artists: [{ name: 'Shiraz Uppal' }, { name: 'Jaswinder Singh' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(unrelated2, 'Atif Aslam'), false);
  });

  it('hasTargetPerformer rejects third-party covers and tribute singers', () => {
    const cover = PlaylistService.extractAllContributors({
      title: 'Tum Hi Ho (Acoustic Cover)',
      artist: 'SANAM',
      artists: [{ name: 'SANAM' }],
    });
    assert.equal(PlaylistService.hasTargetPerformer(cover, 'Arijit Singh'), false);
  });
});

