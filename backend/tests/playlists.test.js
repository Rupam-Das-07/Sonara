'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { getAllDefinitions, getDefinitionById } = require('../src/discovery/PlaylistDefinitions');
const { isEligible } = require('../src/discovery/EligibilityEngine');
const PlaylistService = require('../src/discovery/PlaylistService');

const ORIGINAL_10_IDS = ['chill_nights','lofi_focus','retro_bollywood','punjabi_power','romantic_hits','workout_energy','sufi_vibes','trending_now','fresh_releases','arijit_singh'];
const NEW_12_IDS = ['uncut_bollywood','bollywood_2000s','golden_bollywood','heartbreak_hindi','acoustic_unplugged','indie_india','desi_hip_hop','ghazal_lounge','south_blockbusters','global_top_hits','global_chill','ar_rahman_magic'];
const ALL_22_IDS = [...ORIGINAL_10_IDS, ...NEW_12_IDS];

const BASE = { allowRemix:false, allowLofi:false, allowSlowedReverb:false, allowInstrumental:false, minDurationSeconds:90, maxDurationSeconds:480 };

// ── Catalogue Structure ──────────────────────────────────────────────────────

describe('Curated Playlists V2 - Structure', () => {
  it('22 playlists defined with unique IDs', () => {
    const defs = getAllDefinitions();
    assert.equal(defs.length, 22);
    const ids = defs.map(d => d.id);
    assert.equal(new Set(ids).size, 22);
    ALL_22_IDS.forEach(id => assert.ok(ids.includes(id), 'Missing: ' + id));
  });
  it('getDefinitionById returns null for unknown ID', () => {
    assert.equal(getDefinitionById('non_existent_playlist'), null);
  });
  it('All definitions have required fields', () => {
    getAllDefinitions().forEach(def => {
      assert.equal(typeof def.name, 'string');
      assert.equal(typeof def.description, 'string');
      assert.equal(typeof def.size, 'number');
      assert.equal(typeof def.updateIntervalDays, 'number');
      assert.equal(typeof def.eligibility, 'object');
      assert.ok(Array.isArray(def.candidateQueries));
    });
  });
});

// ── F-09: Invalid Duration Rejection ─────────────────────────────────────────

describe('F-09: Invalid Duration Rejection', () => {
  const mk = d => ({ title:'Song', artist:'Artist', duration:d });
  const rej = (d, label) => {
    const r = isEligible(mk(d), BASE);
    assert.equal(r.eligible, false, label + ' should be rejected');
    assert.equal(r.reason, 'invalid_or_missing_duration', label + ' reason mismatch');
  };
  it('rejects null', () => rej(null, 'null'));
  it('rejects undefined', () => rej(undefined, 'undefined'));
  it('rejects NaN', () => rej(NaN, 'NaN'));
  it('rejects Infinity', () => rej(Infinity, 'Infinity'));
  it('rejects string "4:30"', () => rej('4:30', 'string'));
  it('rejects 0', () => rej(0, '0'));
  it('rejects negative', () => rej(-5, 'negative'));
  it('accepts 90s (min)', () => assert.ok(isEligible(mk(90), BASE).eligible));
  it('accepts 480s (max)', () => assert.ok(isEligible(mk(480), BASE).eligible));
  it('rejects 89s (below min)', () => assert.match(isEligible(mk(89), BASE).reason, /duration_too_short/));
  it('rejects 481s (above max)', () => assert.match(isEligible(mk(481), BASE).reason, /duration_too_long/));
});

// ── F-06: Spoken Content — Compound Patterns Only ────────────────────────────

describe('F-06: Spoken Content — Compound Patterns Only', () => {
  it('rejects motivational speech', () => assert.equal(isEligible({title:'Never Give Up (Motivational Speech)',artist:'X',duration:200}, BASE).eligible, false));
  it('rejects inspirational speech', () => assert.equal(isEligible({title:'Rise Up: Inspirational Speech',artist:'X',duration:210}, BASE).eligible, false));
  it('rejects powerful speech', () => assert.equal(isEligible({title:'A Powerful Speech on Discipline',artist:'X',duration:195}, BASE).eligible, false));
  it('rejects political speech', () => assert.equal(isEligible({title:'Political Speech at Rally',artist:'X',duration:220}, BASE).eligible, false));
  it('rejects podcast', () => assert.equal(isEligible({title:'Ranveer Show Podcast',artist:'X',duration:300}, BASE).eligible, false));
  it('rejects lecture', () => assert.equal(isEligible({title:'Physics Lecture on Quantum',artist:'X',duration:240}, BASE).eligible, false));
  it('rejects satsang', () => assert.equal(isEligible({title:'Morning Satsang with Swamiji',artist:'X',duration:250}, BASE).eligible, false));
  it('rejects katha', () => assert.equal(isEligible({title:'Evening Katha on Ramayan',artist:'X',duration:250}, BASE).eligible, false));
  it('F-06: does NOT reject "Freedom of Speech" (legitimate song)', () => {
    const r = isEligible({title:'Freedom of Speech',artist:'Above and Beyond',duration:260}, BASE);
    assert.equal(r.eligible, true, 'Got reason: ' + r.reason);
  });
  it('F-06: does NOT reject "Figure of Speech"', () => assert.equal(isEligible({title:'Figure of Speech',artist:'Artist',duration:220}, BASE).eligible, true));
});

// ── F-05: Remaster Year Not Mistaken for Release Year ────────────────────────

describe('F-05: Remaster Year Not Mistaken for Release Year', () => {
  const vCfg = { eraBeforeYear:1985, minDurationSeconds:90, maxDurationSeconds:720, allowRemix:false, allowLofi:false, allowSlowedReverb:false, allowInstrumental:false };
  it('allows vintage track with (2022 Remaster)', () => {
    const r = isEligible({title:'Pal Pal Dil Ke Paas (2022 Remaster)',artist:'Kishore Kumar',duration:310,year:null}, vCfg);
    assert.equal(r.eligible, true, 'Got: ' + r.reason);
  });
  it('allows (Remastered 2020)', () => assert.equal(isEligible({title:'Roop Tera Mastana (Remastered 2020)',artist:'Kishore Kumar',duration:280,year:null}, vCfg).eligible, true));
  it('allows [Remastered] bracket no year', () => assert.equal(isEligible({title:'Yeh Mera Dil [Remastered]',artist:'Asha Bhosle',duration:260,year:null}, vCfg).eligible, true));
  it('allows album with (50th Anniversary Remaster 2021)', () => assert.equal(isEligible({title:'Dum Maro Dum',artist:'Asha Bhosle',duration:320,year:null,album:'Hare Rama Hare Krishna (50th Anniversary Remaster 2021)'}, vCfg).eligible, true));
  it('still rejects genuine modern year 2023 in title', () => assert.equal(isEligible({title:'Besharam Rang 2023',artist:'Modern',duration:200,year:null}, vCfg).eligible, false));
  it('allows legitimate 1975 year token', () => assert.equal(isEligible({title:'Sholay (1975)',artist:'Asha Bhosle',duration:300,year:null}, vCfg).eligible, true));
});

// ── F-02: Token Boundary Negative Keyword Matching ───────────────────────────

describe('F-02: Token Boundary Negative Keyword Matching', () => {
  const kCfg = { eraBeforeYear:1985, negativeKeywords:['war','sanju','stree 2','pathaan','jawan','kabir singh'], minDurationSeconds:90, maxDurationSeconds:720, allowRemix:false, allowLofi:false, allowSlowedReverb:false, allowInstrumental:false };
  it('Deewar must NOT match war', () => {
    const r = isEligible({title:'Kehdoon Tumhe From Deewar',artist:'Kishore Kumar',duration:310,year:null}, kCfg);
    assert.equal(r.eligible, true, 'Deewar substring-matched war — token boundary broken');
  });
  it('Standalone WAR matches', () => assert.equal(isEligible({title:'Ghungroo From WAR',artist:'Vishal Dadlani',duration:220,year:null}, kCfg).eligible, false));
  it('Parwardigar must NOT match war', () => assert.equal(isEligible({title:'Parwardigar',artist:'X',duration:270,year:null}, kCfg).eligible, true));
  it('Talwar must NOT match war', () => assert.equal(isEligible({title:'Talwar',artist:'X',duration:240,year:null}, kCfg).eligible, true));
  it('Sanjana must NOT match sanju', () => assert.equal(isEligible({title:'Sanjana I Love You',artist:'X',duration:255,year:null}, kCfg).eligible, true));
  it('From Sanju matches sanju', () => assert.equal(isEligible({title:'Kar Har Maidaan Fateh From Sanju',artist:'X',duration:240,year:null}, kCfg).eligible, false));
  it('stree 2 matches Stree 2 film', () => assert.equal(isEligible({title:'Aaj Ki Raat From Stree 2',artist:'X',duration:240,year:null}, kCfg).eligible, false));
  it('Stree Teri Yehi Kahani NOT rejected by stree 2', () => assert.equal(isEligible({title:'Stree Teri Yehi Kahani',artist:'Lata Mangeshkar',duration:260,year:null}, kCfg).eligible, true));
});

// ── F-01: Fresh Releases Definition ──────────────────────────────────────────

describe('F-01: Fresh Releases Definition', () => {
  it('description does not claim past 30 days', () => {
    assert.equal(getDefinitionById('fresh_releases').description.includes('30 days'), false);
  });
  it('no hard-coded literal year tokens in queries', () => {
    const bad = getDefinitionById('fresh_releases').candidateQueries.filter(q => /\b20\d{2}\b/.test(q) && !q.includes('{year}'));
    assert.equal(bad.length, 0);
  });
  it('has at least one evergreen query', () => {
    const ev = getDefinitionById('fresh_releases').candidateQueries.filter(q => !q.includes('{year}') && !/\b20\d{2}\b/.test(q));
    assert.ok(ev.length >= 1);
  });
  it('has a {year} template query', () => {
    assert.ok(getDefinitionById('fresh_releases').candidateQueries.some(q => q.includes('{year}')));
  });
});

// ── F-04: Structured Artist Normalization ─────────────────────────────────────

describe('F-04: Structured Artist Normalization', () => {
  it('Earth, Wind & Fire (structured) -> earth-wind-and-fire', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Earth, Wind & Fire'}]}), 'earth-wind-and-fire');
  });
  it('Simon & Garfunkel (structured) -> simon-and-garfunkel', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Simon & Garfunkel'}]}), 'simon-and-garfunkel');
  });
  it('Arijit Singh & Shreya Ghoshal (structured) -> arijit singh', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Arijit Singh & Shreya Ghoshal'}]}), 'arijit singh');
  });
  it('Arijit Singh feat. Shreya Ghoshal (structured) -> arijit singh', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Arijit Singh feat. Shreya Ghoshal'}]}), 'arijit singh');
  });
  it('Arijit Singh, Shreya Ghoshal (structured comma) -> arijit singh', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Arijit Singh, Shreya Ghoshal'}]}), 'arijit singh');
  });
  it('DIVINE feat. Armani White (structured) -> divine', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'DIVINE feat. Armani White'}]}), 'divine');
  });
  it('F-04: Kalyanji-Anandji (hyphen structured) -> kalyanji-anandji', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Kalyanji-Anandji'}]}), 'kalyanji-anandji');
  });
  it('F-04: Kalyanji & Anandji (ampersand structured) -> kalyanji-anandji', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Kalyanji & Anandji'}]}), 'kalyanji-anandji');
  });
  it('F-04: Ajay-Atul (hyphen structured) -> ajay-atul', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Ajay-Atul'}]}), 'ajay-atul');
  });
  it('F-04: Ajay & Atul (ampersand structured) -> ajay-atul', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Ajay & Atul'}]}), 'ajay-atul');
  });
  it('All original 9 creative duos preserved', () => {
    [
      ['Vishal & Shekhar','vishal-shekhar'],['Sachin-Jigar','sachin-jigar'],
      ['Jatin-Lalit','jatin-lalit'],['Sajid-Wajid','sajid-wajid'],
      ['Salim & Sulaiman','salim-sulaiman'],['Anand-Milind','anand-milind'],
      ['Laxmikant-Pyarelal','laxmikant-pyarelal'],['Shankar-Ehsaan-Loy','shankar-ehsaan-loy'],
      ['Nadeem-Shravan','nadeem-shravan'],
    ].forEach(([input, expected]) => {
      assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:input}]}), expected, input);
    });
  });
  it('Solo artist no delimiters', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artists:[{name:'Arijit Singh'}]}), 'arijit singh');
  });
  it('Raw feat. string fallback', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'DIVINE feat. Armani White'}), 'divine');
  });
  it('Raw ft. string fallback', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'Badshah ft. Payal Dev'}), 'badshah');
  });
  it('Raw string duo Vishal & Shekhar preserved', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'Vishal & Shekhar, Shilpa Rao'}), 'vishal-shekhar');
  });
  it('Raw string duo Sachin-Jigar preserved', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'Sachin-Jigar, Shreya Ghoshal'}), 'sachin-jigar');
  });
  it('Raw string Earth, Wind & Fire preserved', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'Earth, Wind & Fire'}), 'earth-wind-and-fire');
  });
  it('Raw string Simon & Garfunkel preserved', () => {
    assert.equal(PlaylistService.normalizePrimaryArtist({title:'X',artist:'Simon & Garfunkel'}), 'simon-and-garfunkel');
  });
});

// ── F-03: Canonical Deduplication ────────────────────────────────────────────

describe('F-03: Canonical Deduplication', () => {
  it('Packaging noise collapses to same key', () => {
    const a = {title:'Kesariya (Official Audio)',artist:'Arijit Singh'};
    const b = {title:'Kesariya (Lyrical Video Song)',artist:'Arijit Singh'};
    assert.equal(PlaylistService.getCanonicalTrackKey(a), PlaylistService.getCanonicalTrackKey(b));
  });
  it('Different videoId variants of same song produce same canonical key', () => {
    const v1 = {title:'Tum Hi Ho (Official Audio)',artist:'Arijit Singh'};
    const v2 = {title:'Tum Hi Ho (Lyrical)',artist:'Arijit Singh'};
    assert.equal(PlaylistService.getCanonicalTrackKey(v1), PlaylistService.getCanonicalTrackKey(v2));
  });
  it('Acoustic distinct from studio when allowAcoustic=true', () => {
    const s = {title:'Tum Ho Toh',artist:'Vishal Mishra'};
    const a = {title:'Tum Ho Toh (Acoustic)',artist:'Vishal Mishra'};
    assert.notEqual(PlaylistService.getCanonicalTrackKey(s,false,true), PlaylistService.getCanonicalTrackKey(a,false,true));
    assert.match(PlaylistService.getCanonicalTrackKey(a,false,true), /::acoustic/);
  });
});

// ── F-08: Language Version Canonicalization ───────────────────────────────────

describe('F-08: Language Version Canonicalization', () => {
  it('Tamil and Telugu standalone versions produce DISTINCT keys', () => {
    const t = {title:'Arabic Kuthu (Tamil)',artist:'Anirudh Ravichander'};
    const g = {title:'Arabic Kuthu (Telugu)',artist:'Anirudh Ravichander'};
    assert.notEqual(PlaylistService.getCanonicalTrackKey(t), PlaylistService.getCanonicalTrackKey(g));
  });
  it('F-08 CRITICAL: From Hindi Medium does NOT produce ::hindi tag', () => {
    const f = {title:'Suit Suit (From "Hindi Medium")',artist:'Guru Randhawa'};
    const o = {title:'Suit Suit (Official Audio)',artist:'Guru Randhawa'};
    assert.equal(PlaylistService.getCanonicalTrackKey(f), PlaylistService.getCanonicalTrackKey(o), 'hindi tag wrongly applied to film title');
  });
  it('(Tamil Version) standalone recognized', () => {
    assert.match(PlaylistService.getCanonicalTrackKey({title:'Naatu Naatu (Tamil Version)',artist:'X'}), /::tamil/);
  });
  it('[Telugu] bracket standalone recognized', () => {
    assert.match(PlaylistService.getCanonicalTrackKey({title:'Naatu Naatu [Telugu]',artist:'X'}), /::telugu/);
  });
  it('Bare language in title body NOT tagged', () => {
    assert.equal(PlaylistService.getCanonicalTrackKey({title:'Tamil Cinema Classics',artist:'X'}).includes('::tamil'), false);
  });
});

// ── F-07: Artist De-clustering Tail Conflict ──────────────────────────────────

describe('F-07: Artist De-clustering Tail Conflict', () => {
  const mk = (a, t) => ({artist:a, title:t});
  const noAdj = (arr, label) => {
    const r = PlaylistService.declusterArtists(arr);
    for (let i=1; i<r.length; i++) {
      assert.notEqual(r[i].artist, r[i-1].artist, label + ' conflict at ' + (i-1) + ',' + i);
    }
  };
  it('F-07: [A,B,C,C] -> [A,C,B,C] (minimal disruption, preserves track A)', () => {
    const r = PlaylistService.declusterArtists([mk('A','1'),mk('B','2'),mk('C','3'),mk('C','4')]);
    assert.deepEqual(r.map(t => t.artist), ['A','C','B','C']);
  });
  it('F-07: [A,B,B,C,C] -> collision-free deterministic [A,B,C,B,C]', () => {
    const r = PlaylistService.declusterArtists([mk('A','1'),mk('B','2'),mk('B','3'),mk('C','4'),mk('C','5')]);
    assert.deepEqual(r.map(t => t.artist), ['A','B','C','B','C']);
  });
  it('F-07: [A,B,C,D,D] -> [A,B,D,C,D] (minimal disruption, preserves A and B)', () => {
    const r = PlaylistService.declusterArtists([mk('A','1'),mk('B','2'),mk('C','3'),mk('D','4'),mk('D','5')]);
    assert.deepEqual(r.map(t => t.artist), ['A','B','D','C','D']);
  });
  it('F-07: [B,C,C] -> [C,B,C] (collision-free deterministic)', () => {
    const r = PlaylistService.declusterArtists([mk('B','1'),mk('C','2'),mk('C','3')]);
    assert.deepEqual(r.map(t => t.artist), ['C','B','C']);
  });
  it('F-07: already-valid [A,B,C,D] MUST remain unchanged', () => {
    const r = PlaylistService.declusterArtists([mk('A','1'),mk('B','2'),mk('C','3'),mk('D','4')]);
    assert.deepEqual(r.map(t => t.artist), ['A','B','C','D']);
  });
  it('F-07: no-safe-swap case [C,C,C] leaves order intact without error', () => {
    const r = PlaylistService.declusterArtists([mk('C','1'),mk('C','2'),mk('C','3')]);
    assert.deepEqual(r.map(t => t.artist), ['C','C','C']);
  });
  it('Adjacent Arijit Singh conflict resolved', () => {
    const inp = [{artist:'Arijit Singh',title:'S1'},{artist:'Arijit Singh',title:'S2'},{artist:'Pritam',title:'S3'},{artist:'Atif Aslam',title:'S4'}];
    const dec = PlaylistService.declusterArtists(inp);
    const arts = dec.map(t => PlaylistService.normalizePrimaryArtist(t));
    assert.notEqual(arts[0], arts[1]);
  });
});

// ── Defensive Era Contamination Detection ────────────────────────────────────

describe('Defensive Era Contamination Detection', () => {
  const eCfg = {eraBeforeYear:1985, negativeArtists:['arijit singh','badshah','neha kakkar','pritam','shreya ghoshal'], negativeKeywords:['dhurandhar','stree 2','animal','brahmastra','kabir singh'], minDurationSeconds:90, maxDurationSeconds:720, allowRemix:false, allowLofi:false, allowSlowedReverb:false, allowInstrumental:false};
  it('catches modern artists in vintage playlists', () => {
    const r = isEligible({title:'Lag Jaa Gale',artist:'Arijit Singh',duration:250}, eCfg);
    assert.equal(r.eligible, false); assert.equal(r.reason, 'negative_era_artist');
  });
  it('catches modern movie markers in vintage playlists', () => {
    const r = isEligible({title:'Gehra Hua From Dhurandhar',artist:'Classic Singer',duration:260}, eCfg);
    assert.equal(r.eligible, false); assert.equal(r.reason, 'negative_era_keyword');
  });
  it('allows vintage track with null year and no contradictory evidence', () => {
    assert.equal(isEligible({title:'Pal Pal Dil Ke Paas',artist:'Kishore Kumar',duration:310,year:null}, eCfg).eligible, true);
  });
  it('does not infer year from non-blacklisted artist', () => {
    assert.equal(isEligible({title:'Old Folk Song',artist:'Traditional Chorus',duration:200,year:null}, eCfg).eligible, true);
  });
});

// ── Round-Robin Interleaving ──────────────────────────────────────────────────

describe('Round-Robin Interleaving', () => {
  it('interleaves evenly across queries', () => {
    const r = PlaylistService.interleaveQueries([[{id:'q1_0'},{id:'q1_1'},{id:'q1_2'}],[{id:'q2_0'},{id:'q2_1'}],[{id:'q3_0'},{id:'q3_1'},{id:'q3_2'},{id:'q3_3'}]]);
    assert.deepEqual(r.map(t=>t.id), ['q1_0','q2_0','q3_0','q1_1','q2_1','q3_1','q1_2','q3_2','q3_3']);
  });
  it('handles empty arrays gracefully', () => {
    assert.deepEqual(PlaylistService.interleaveQueries([[{id:'q1_0'}],[],[{id:'q3_0'}]]).map(t=>t.id), ['q1_0','q3_0']);
  });
});

// ── F-10: No Tier-3 Duration Relaxation ──────────────────────────────────────

describe('F-10: No Tier-3 Duration Relaxation', () => {
  it('89s rejected (strict 90s floor)', () => assert.match(isEligible({title:'X',artist:'Y',duration:89}, BASE).reason, /duration_too_short/));
  it('85s also rejected', () => assert.equal(isEligible({title:'X',artist:'Y',duration:85}, BASE).eligible, false));
});

// ── Hard Rejection Gates (full regression) ────────────────────────────────────

describe('Hard Rejection Gates (regression)', () => {
  it('NEVER rejects naked word mix', () => {
    assert.equal(isEligible({title:'Mix of Emotions',artist:'X',duration:210}, BASE).eligible, true);
    assert.equal(isEligible({title:'A Mix In The Rain',artist:'X',duration:195}, BASE).eligible, true);
  });
  it('rejects nonstop megamix', () => assert.equal(isEligible({title:'Ultimate Bollywood Nonstop Mix 2024',artist:'DJ',duration:400}, BASE).eligible, false));
  it('rejects full audio jukebox', () => assert.equal(isEligible({title:'Aashiqui 2 Full Audio Jukebox',artist:'X',duration:450}, BASE).eligible, false));
  it('rejects compound remix when not allowed', () => assert.equal(isEligible({title:'Kesariya (Club Mix)',artist:'Pritam',duration:200}, BASE).eligible, false));
  it('rejects sped up version', () => assert.equal(isEligible({title:'Apna Bana Le Sped Up Version',artist:'X',duration:150}, BASE).eligible, false));
  it('rejects slowed reverb', () => assert.equal(isEligible({title:'Galiyaan (Slowed + Reverb)',artist:'X',duration:300}, BASE).eligible, false));
  it('rejects teaser', () => assert.equal(isEligible({title:'Animal Title Track (Official Teaser)',artist:'X',duration:100}, BASE).eligible, false));
  it('rejects karaoke', () => assert.equal(isEligible({title:'Tum Hi Ho (Karaoke Track with Lyrics)',artist:'X',duration:240}, BASE).eligible, false));
  it('rejects 8D audio', () => assert.equal(isEligible({title:'Chaleya (8D Audio)',artist:'X',duration:200}, BASE).eligible, false));
  it('rejects jhankar beats', () => assert.equal(isEligible({title:'Tip Tip Barsa Paani Jhankar Beats',artist:'X',duration:280}, BASE).eligible, false));
});
