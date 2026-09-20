// backend/src/discovery/PlaylistDefinitions.js
//
// Single source of truth for all curated playlist definitions in sonara-backend.
// Curated Playlist Data Quality Engine V2.
//
// Rules live here in code — they are NOT stored in the database.
//
// Every definition satisfies the PlaylistDefinition shape:
// {
//   id:                  string        — unique key
//   name:                string        — display name
//   description:         string        — human-readable editorial purpose
//   size:                number        — target number of tracks
//   updateIntervalDays:  number        — update cadence in days
//   candidateQueries:    string[]      — search queries sent to the provider
//   artistStrategy:      'top_tracks' | 'search'
//   artistName:          string|null
//   eligibility:         EligibilityConfig
// }
//
// EligibilityConfig:
// {
//   excludedGenres:      string[]
//   eraBeforeYear:       number|null
//   eraAfterYear:        number|null
//   allowRemix:          boolean
//   allowLofi:           boolean
//   allowSlowedReverb:   boolean
//   allowInstrumental:   boolean
//   maxArtistTracks:     number
//   minDurationSeconds?: number        (default: 90)
//   maxDurationSeconds?: number        (default: 480; 720 for Sufi/Classical)
//   negativeArtists?:    string[]      (defensive era/genre contamination detector)
//   negativeKeywords?:   string[]      (defensive title/movie contamination detector)
// }

'use strict';

/** @type {Record<string, object>} */
const PLAYLIST_DEFINITIONS = {

  // ── 01: Chill Nights ───────────────────────────────────────────────────────
  chill_nights: {
    id: 'chill_nights',
    name: 'Chill Nights',
    description: 'A calm, late-evening soundtrack. The emotional anchor of the platform.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'bollywood late night chill',
      'hindi acoustic chill songs',
      'bollywood midnight melodies',
    ],
    eligibility: {
      excludedGenres: ['edm', 'metal', 'hardcore', 'drill', 'party'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 02: Lofi Focus ─────────────────────────────────────────────────────────
  lofi_focus: {
    id: 'lofi_focus',
    name: 'Lofi Focus',
    description: 'Study and concentration music. No distractions, only focus.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'hindi lofi chillhop study beats',
      'bollywood lofi focus beats',
      'indian lofi instrumental study',
    ],
    eligibility: {
      excludedGenres: ['party', 'edm', 'hip-hop', 'metal', 'devotional'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: true,
      allowSlowedReverb: true,
      allowInstrumental: true,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['rock version', 'workout', 'bass boosted'],
    },
  },

  // ── 03: Retro Bollywood ────────────────────────────────────────────────────
  retro_bollywood: {
    id: 'retro_bollywood',
    name: 'Retro Bollywood',
    description: 'Nostalgic hits from the 80s and 90s golden era of Hindi cinema.',
    size: 30,
    updateIntervalDays: 14,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      '90s bollywood evergreen hits',
      '80s hindi romantic hits',
      '90s hindi film classic songs',
    ],
    eligibility: {
      excludedGenres: ['edm', 'lofi', 'hip-hop', 'modern pop'],
      eraBeforeYear: 2000,
      eraAfterYear: 1980,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 540,
      negativeArtists: [
        'arijit singh',
        'badshah',
        'neha kakkar',
        'honey singh',
        'diljit dosanjh',
        'ap dhillon',
        'sachin-jigar',
        'armaan malik',
      ],
      negativeKeywords: [
        'dhurandhar',
        'stree 2',
        // F-02: 'stree' removed — collides with the 1961 V. Shantaram classic "Stree".
        // 'stree 2' (the 2024 blockbuster) is the intended target.
        'sanju',
        'animal',
        'brahmastra',
        'jawan',
        'pathaan',
        'war',
        'kabir singh',
        'bhediya',
        'munjya',
      ],
    },
  },

  // ── 04: Punjabi Power ──────────────────────────────────────────────────────
  punjabi_power: {
    id: 'punjabi_power',
    name: 'Punjabi Power',
    description: 'High-energy Punjabi party anthems and bhangra hits.',
    size: 30,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'punjabi party dance hits',
      'bhangra pop hits',
      'latest punjabi party songs',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'devotional', 'classical'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: true,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 05: Romantic Hits ──────────────────────────────────────────────────────
  romantic_hits: {
    id: 'romantic_hits',
    name: 'Romantic Hits',
    description: 'Timeless Bollywood love songs for quiet evenings.',
    size: 30,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'bollywood romantic love songs',
      'timeless hindi love songs',
      'best bollywood romantic hits',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'hip-hop', 'metal', 'bhangra'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 06: Workout Energy ─────────────────────────────────────────────────────
  workout_energy: {
    id: 'workout_energy',
    name: 'Workout Energy',
    description: 'High-intensity gym and workout music to power through training.',
    size: 30,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'high energy bollywood songs',
      'bollywood workout pump hits',
      'fast tempo hindi gym songs',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'classical', 'devotional', 'lofi', 'sad'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: true,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 07: Sufi Vibes ─────────────────────────────────────────────────────────
  sufi_vibes: {
    id: 'sufi_vibes',
    name: 'Sufi Vibes',
    description: 'Spiritual and soulful Sufi music for deep listening.',
    size: 25,
    updateIntervalDays: 14,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'soulful sufi songs hindi',
      'qawwali sufi classics',
      'sufi bollywood spiritual',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'hip-hop', 'metal', 'bhangra'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 720,
    },
  },

  // ── 08: Trending Now ───────────────────────────────────────────────────────
  trending_now: {
    id: 'trending_now',
    name: 'Trending Now',
    description: 'The most popular Bollywood songs at this moment.',
    size: 25,
    updateIntervalDays: 2,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'trending bollywood songs',
      'top hindi hits today',
      'latest trending hindi songs',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'devotional', 'classical', 'lofi'],
      eraBeforeYear: null,
      eraAfterYear: 2024,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['90s', '80s', '70s', 'retro', 'vintage'],
    },
  },

  // ── 09: Fresh Releases ─────────────────────────────────────────────────────
  fresh_releases: {
    id: 'fresh_releases',
    name: 'Fresh Releases',
    // F-01: Removed false "past 30 days" guarantee.
    // YouTube Music search does NOT expose authoritative release timestamps via the song search API.
    // This playlist uses evergreen freshness signals and dynamic {year} query tokens as search hints.
    // Freshness is best-effort based on search ranking, not verified release dates.
    description: 'Fresh chartbusters, new Hindi releases, and rising singles.',
    size: 20,
    updateIntervalDays: 2,
    artistStrategy: 'search',
    artistName: null,
    // F-01: Resilient query triad — 2 evergreen + 1 dynamic {year} token resolved at resolution time.
    // Do NOT freeze year at module load. {year} is replaced in resolveCandidatePool().
    candidateQueries: [
      'latest hindi releases',
      'new bollywood songs',
      'new hindi songs {year}',
    ],
    eligibility: {
      excludedGenres: ['classical', 'devotional', 'lofi'],
      eraBeforeYear: null,
      eraAfterYear: 2025,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 1,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['80s', '90s', '70s', '2000s', '2010s', 'retro', 'classic'],
    },
  },


  // ── 10: Arijit Singh Hits ──────────────────────────────────────────────────
  arijit_singh: {
    id: 'arijit_singh',
    name: 'Arijit Singh Hits',
    description: 'The best of Arijit Singh, updated regularly with his latest releases.',
    size: 30,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Arijit Singh',
    artistRole: 'performer',
    candidateQueries: [
      'Arijit Singh blockbuster hits',
      'Arijit Singh upbeat songs',
      'Arijit Singh popular hits',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 30,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 11: Uncut Bollywood ───────────────────────────────────────────────────
  uncut_bollywood: {
    id: 'uncut_bollywood',
    name: 'Uncut Bollywood',
    description: 'High-octane Bollywood dance anthems, club bangers, and blockbuster energy.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'bollywood dance party bangers',
      'modern hindi club hits',
      'bollywood party dance songs',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'devotional', 'classical', 'sad', 'lofi'],
      eraBeforeYear: null,
      eraAfterYear: 2010,
      allowRemix: true,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['disco dancer', 'jimmy jimmy', 'saat samundar paar', '70s', '80s', '90s'],
    },
  },

  // ── 12: Bollywood 2000s ───────────────────────────────────────────────────
  bollywood_2000s: {
    id: 'bollywood_2000s',
    name: 'Bollywood 2000s',
    description: 'The unforgettable millennial soundtrack — Pritam, KK, and timeless 2000s romantic cinema.',
    size: 30,
    updateIntervalDays: 14,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      '2000s bollywood hits pritam kk',
      'best hindi songs 2000 to 2009',
      'millennial bollywood film hits',
    ],
    eligibility: {
      excludedGenres: ['edm', 'metal', 'drill'],
      eraBeforeYear: 2012,
      eraAfterYear: 2000,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 540,
      negativeArtists: ['kishore kumar', 'mohammed rafi', 'mukesh'],
      // F-02: 'stree' removed — collides with the 1961 V. Shantaram classic "Stree".
      negativeKeywords: ['dhurandhar', 'stree 2', 'animal', 'brahmastra', 'jawan', 'kabir singh', 'bhediya', 'munjya'],
    },
  },

  // ── 13: Classic Bollywood ─────────────────────────────────────────────────
  golden_bollywood: {
    id: 'golden_bollywood',
    name: 'Classic Bollywood',
    description: 'Vintage treasures from the 60s and 70s — Kishore Kumar, Lata Mangeshkar, and R.D. Burman.',
    size: 30,
    updateIntervalDays: 14,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      '70s hindi film evergreen songs',
      '60s 70s bollywood vintage classics',
      'rd burman kishore rafi 70s',
      'lata mangeshkar vintage classics',
    ],
    eligibility: {
      excludedGenres: ['edm', 'hip-hop', 'lofi', 'modern pop'],
      eraBeforeYear: 1985,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 3,
      minDurationSeconds: 90,
      maxDurationSeconds: 720,
      negativeArtists: [
        'arijit singh',
        'badshah',
        'neha kakkar',
        'honey singh',
        'diljit dosanjh',
        'armaan malik',
        'pritam',
        'shreya ghoshal',
        'atif aslam',
        'vishal dadlani',
        'shekhar ravjiani',
        'kumar sanu',
        'alka yagnik',
        'udit narayan',
      ],
      negativeKeywords: [
        'dhurandhar',
        'stree 2',
        // F-02: 'stree' removed — matches the 1961 Lata Mangeshkar classic film "Stree".
        'sanju',
        'animal',
        'brahmastra',
        'jawan',
        'pathaan',
        'war',
        'kabir singh',
        'rockstar',
        'aashiqui 2',
        'disco dancer',
      ],
    },
  },

  // ── 14: Broken Strings ────────────────────────────────────────────────────
  heartbreak_hindi: {
    id: 'heartbreak_hindi',
    name: 'Broken Strings',
    description: 'Soul-stirring melancholy, heartbreak ballads, and poignant Hindi melodies.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'bollywood heartbreak sad songs',
      'hindi emotional melancholy songs',
      'soul stirring sad hindi songs',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal', 'dance', 'bhangra'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 15: Acoustic Sessions ─────────────────────────────────────────────────
  acoustic_unplugged: {
    id: 'acoustic_unplugged',
    name: 'Acoustic Sessions',
    description: 'Stripped-down guitars, warm pianos, and intimate acoustic sessions.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'hindi acoustic unplugged songs',
      'bollywood acoustic guitar piano',
      'indian acoustic pop sessions',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal', 'drill'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 16: Indie India ───────────────────────────────────────────────────────
  indie_india: {
    id: 'indie_india',
    name: 'Indie India',
    description: 'Modern Indian independent music, singer-songwriters, and alternative pop.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'indian indie singer songwriter',
      'hindi indie pop songs',
      'prateek kuhad anuv jain indie',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal', 'drill'],
      eraBeforeYear: null,
      eraAfterYear: 2016,
      allowRemix: false,
      allowLofi: true,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeArtists: ['jaymes young', 'imagine dragons', 'the weeknd', 'ed sheeran', 'maroon 5'],
    },
  },

  // ── 17: Desi Hip Hop ──────────────────────────────────────────────────────
  desi_hip_hop: {
    id: 'desi_hip_hop',
    name: 'Desi Hip Hop',
    description: 'Raw lyricism, heavy beats, and street anthems from the subcontinent.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'desi hip hop rap hits',
      'divine seedhe maut krsna rap',
      'indian underground hip hop',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'devotional', 'classical', 'lofi'],
      eraBeforeYear: null,
      eraAfterYear: 2018,
      allowRemix: true,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 18: Ghazal Lounge ─────────────────────────────────────────────────────
  ghazal_lounge: {
    id: 'ghazal_lounge',
    name: 'Ghazal Lounge',
    description: 'Poetic serenity and vocal mastery from Jagjit Singh, Pankaj Udhas, and Ghulam Ali.',
    size: 25,
    updateIntervalDays: 14,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'jagjit singh ghazals',
      'pankaj udhas ghazals',
      'ghulam ali mehdi hassan ghazals',
      'poetic hindi urdu ghazals',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal', 'drill', 'hip-hop'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 3,
      minDurationSeconds: 90,
      maxDurationSeconds: 600,
    },
  },

  // ── 19: South Cinema Hits ─────────────────────────────────────────────────
  south_blockbusters: {
    id: 'south_blockbusters',
    name: 'South Cinema Hits',
    description: 'High-voltage cinematic blockbusters and chartbusters from Tamil and Telugu cinema.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'tamil viral blockbuster hits',
      'telugu mass cinema hits',
      'south cinema dance hits',
    ],
    eligibility: {
      excludedGenres: ['devotional', 'classical', 'lofi'],
      eraBeforeYear: null,
      eraAfterYear: 2018,
      allowRemix: true,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 20: Global Pop ────────────────────────────────────────────────────────
  global_top_hits: {
    id: 'global_top_hits',
    name: 'Global Pop',
    description: 'The biggest worldwide chart-topping pop sensations and stadium anthems.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'billboard hot 100 pop hits',
      'global top pop chartbusters',
      'international pop stadium hits',
    ],
    eligibility: {
      excludedGenres: ['ghazal', 'devotional', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: 2020,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 21: Global Chill ──────────────────────────────────────────────────────
  global_chill: {
    id: 'global_chill',
    name: 'Global Chill',
    description: 'Breezy international bedroom pop, warm acoustics, and ambient sunset vibes.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'search',
    artistName: null,
    candidateQueries: [
      'international bedroom pop chill',
      'sunset acoustic english pop',
      'warm chill pop songs',
    ],
    eligibility: {
      excludedGenres: ['edm', 'metal', 'drill', 'party'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: true,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 2,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
    },
  },

  // ── 22: A.R. Rahman Essentials ────────────────────────────────────────────
  ar_rahman_magic: {
    id: 'ar_rahman_magic',
    name: 'A.R. Rahman Essentials',
    description: 'Trailblazing cinematic compositions and genius soundscapes across Hindi and Tamil cinema.',
    size: 30,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'A.R. Rahman',
    artistRole: 'composer',
    candidateQueries: [
      'A.R. Rahman top hindi hits',
      'A.R. Rahman tamil classics',
      'A.R. Rahman soundtrack masterworks',
    ],
    eligibility: {
      excludedGenres: [],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 30,
      minDurationSeconds: 90,
      maxDurationSeconds: 600,
    },
  },

};

/**
 * 8 New Artist-Specific Curated Playlists (Phase 6 / Stage 5C).
 * Kept in a dedicated dictionary to preserve the frozen 22-item generic curated
 * catalog and regression tests, while allowing seamless on-demand resolution via
 * getDefinitionById().
 *
 * @type {Record<string, object>}
 */
const ARTIST_PLAYLIST_DEFINITIONS = {

  // ── 01: Arijit Singh Romantic ──────────────────────────────────────────────
  arijit_singh_romantic: {
    id: 'arijit_singh_romantic',
    name: 'Arijit Singh Romantic',
    description: 'Soulful love ballads, acoustic melodies, and heartfelt late-night favorites from Arijit Singh.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Arijit Singh',
    artistRole: 'performer',
    candidateQueries: [
      'Arijit Singh acoustic love ballads',
      'Arijit Singh soulful unplugged',
      'Arijit Singh sad melodies',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 02: A.R. Rahman Soundtracks ────────────────────────────────────────────
  ar_rahman_soundtracks: {
    id: 'ar_rahman_soundtracks',
    name: 'A.R. Rahman Soundtracks',
    description: 'Sweeping orchestral scores, instrumental masterworks, and iconic cinematic themes composed by A.R. Rahman.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'A.R. Rahman',
    artistRole: 'composer',
    candidateQueries: [
      'A.R. Rahman cinematic score',
      'A.R. Rahman theme music soundtrack',
      'A.R. Rahman instrumental masterworks',
    ],
    eligibility: {
      excludedGenres: [],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: true,
      maxArtistTracks: 25,
      minDurationSeconds: 60,
      maxDurationSeconds: 600,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 03: Shreya Ghoshal Hits ────────────────────────────────────────────────
  shreya_ghoshal_hits: {
    id: 'shreya_ghoshal_hits',
    name: 'Shreya Ghoshal Hits',
    description: 'The definitive hits and timeless Bollywood melodies of Shreya Ghoshal.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Shreya Ghoshal',
    artistRole: 'performer',
    candidateQueries: [
      'Shreya Ghoshal top songs',
      'Shreya Ghoshal greatest hits',
      'Shreya Ghoshal best melodies',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 04: Shreya Ghoshal Romantic ────────────────────────────────────────────
  shreya_ghoshal_romantic: {
    id: 'shreya_ghoshal_romantic',
    name: 'Shreya Ghoshal Romantic',
    description: 'Soulful love songs, emotional duets, and gentle melodies sung by Shreya Ghoshal.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Shreya Ghoshal',
    artistRole: 'performer',
    candidateQueries: [
      'Shreya Ghoshal romantic songs',
      'Shreya Ghoshal love songs',
      'Shreya Ghoshal emotional melody',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 05: Atif Aslam Hits ───────────────────────────────────────────────────
  atif_aslam_hits: {
    id: 'atif_aslam_hits',
    name: 'Atif Aslam Hits',
    description: 'Signature pop-rock anthems, romantic classics, and Bollywood chartbusters by Atif Aslam.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Atif Aslam',
    artistRole: 'performer',
    candidateQueries: [
      'Atif Aslam top songs',
      'Atif Aslam greatest hits',
      'Atif Aslam best bollywood',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 06: Atif Aslam Sufi & Soul ────────────────────────────────────────────
  atif_aslam_sufi: {
    id: 'atif_aslam_sufi',
    name: 'Atif Aslam Sufi & Soul',
    description: 'Spiritual qawwalis, Coke Studio masterworks, and soulful acoustic performances by Atif Aslam.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Atif Aslam',
    artistRole: 'performer',
    candidateQueries: [
      'Atif Aslam sufi songs',
      'Atif Aslam qawwali acoustic',
      'Atif Aslam soulful spiritual',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 600,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 07: Coldplay Essentials ───────────────────────────────────────────────
  coldplay_essentials: {
    id: 'coldplay_essentials',
    name: 'Coldplay Essentials',
    description: 'The essential stadium anthems, alternative rock classics, and global pop hits by Coldplay.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'Coldplay',
    artistRole: 'performer',
    candidateQueries: [
      'Coldplay greatest hits',
      'Coldplay top songs',
      'Coldplay best anthems',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

  // ── 08: The Weeknd Essentials ─────────────────────────────────────────────
  the_weeknd_essentials: {
    id: 'the_weeknd_essentials',
    name: 'The Weeknd Essentials',
    description: 'Cinematic synth-pop, dark R&B, and signature global chart-toppers from The Weeknd.',
    size: 25,
    updateIntervalDays: 7,
    artistStrategy: 'top_tracks',
    artistName: 'The Weeknd',
    artistRole: 'performer',
    candidateQueries: [
      'The Weeknd greatest hits',
      'The Weeknd top tracks',
      'The Weeknd after hours pop',
    ],
    eligibility: {
      excludedGenres: ['edm', 'party', 'metal'],
      eraBeforeYear: null,
      eraAfterYear: null,
      allowRemix: false,
      allowLofi: false,
      allowSlowedReverb: false,
      allowInstrumental: false,
      maxArtistTracks: 25,
      minDurationSeconds: 90,
      maxDurationSeconds: 480,
      negativeKeywords: ['cover', 'karaoke', 'tribute', 'reaction'],
    },
  },

};

/**
 * Returns all general curated playlist definitions (the frozen 22-item catalog).
 * @returns {object[]}
 */
function getAllDefinitions() {
  return Object.values(PLAYLIST_DEFINITIONS);
}

/**
 * Returns a single playlist definition by ID, resolving from both general
 * curated playlists and artist-specific playlists.
 *
 * @param {string} id
 * @returns {object|null}
 */
function getDefinitionById(id) {
  return PLAYLIST_DEFINITIONS[id] || ARTIST_PLAYLIST_DEFINITIONS[id] || null;
}

/**
 * Returns all artist-specific playlist definitions matching a given artist name.
 *
 * @param {string} artistName
 * @returns {object[]}
 */
function getDefinitionsForArtist(artistName) {
  if (!artistName || typeof artistName !== 'string') return [];
  const target = artistName.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (!target) return [];

  const all = [
    ...Object.values(PLAYLIST_DEFINITIONS),
    ...Object.values(ARTIST_PLAYLIST_DEFINITIONS),
  ];

  return all.filter((d) => {
    if (!d.artistName) return false;
    const dName = d.artistName.toLowerCase().replace(/[^a-z0-9]/g, '');
    return dName === target || dName.includes(target) || target.includes(dName);
  });
}

module.exports = {
  PLAYLIST_DEFINITIONS,
  ARTIST_PLAYLIST_DEFINITIONS,
  getAllDefinitions,
  getDefinitionById,
  getDefinitionsForArtist,
};
