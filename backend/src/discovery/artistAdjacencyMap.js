/**
 * Artist Adjacency Map
 * Curated graph of artist relationships with mood & region metadata.
 * Used by the AffinityEngine for Adjacent Discovery and mood-compatible curated injection.
 * Zero API calls — purely static, lightweight, and fast.
 */

const ADJACENCY_MAP = {
  // ─────────────────────────────────────────
  // Indian / Bollywood — Romantic & Soulful
  // ─────────────────────────────────────────
  "arijit singh": {
    related: ["pritam", "vishal mishra", "jubin nautiyal", "shreya ghoshal", "armaan malik", "mohit chauhan", "atif aslam"],
    mood: "romantic",
    region: "india"
  },
  "shreya ghoshal": {
    related: ["arijit singh", "sunidhi chauhan", "alka yagnik", "lata mangeshkar", "neha kakkar", "palak muchhal"],
    mood: "soulful",
    region: "india"
  },
  "armaan malik": {
    related: ["arijit singh", "darshan raval", "vishal mishra", "jubin nautiyal", "atif aslam"],
    mood: "romantic",
    region: "india"
  },
  "atif aslam": {
    related: ["arijit singh", "rahat fateh ali khan", "armaan malik", "mohit chauhan", "sonu nigam"],
    mood: "soulful",
    region: "india"
  },
  "neha kakkar": {
    related: ["badshah", "honey singh", "shreya ghoshal", "guru randhawa", "tony kakkar"],
    mood: "upbeat",
    region: "india"
  },
  "jubin nautiyal": {
    related: ["arijit singh", "vishal mishra", "armaan malik", "b praak", "darshan raval"],
    mood: "romantic",
    region: "india"
  },
  "sonu nigam": {
    related: ["kk", "mohit chauhan", "atif aslam", "shaan", "arijit singh"],
    mood: "soulful",
    region: "india"
  },
  "mohit chauhan": {
    related: ["arijit singh", "sonu nigam", "kk", "atif aslam", "vishal mishra"],
    mood: "soulful",
    region: "india"
  },
  "kk": {
    related: ["sonu nigam", "mohit chauhan", "shaan", "atif aslam", "arijit singh"],
    mood: "soulful",
    region: "india"
  },

  // ─────────────────────────────────────────
  // Indian — Energetic / Party / Hip-Hop
  // ─────────────────────────────────────────
  "honey singh": {
    related: ["badshah", "raftaar", "neha kakkar", "guru randhawa", "diljit dosanjh"],
    mood: "party",
    region: "india"
  },
  "badshah": {
    related: ["honey singh", "raftaar", "neha kakkar", "divine", "guru randhawa"],
    mood: "party",
    region: "india"
  },

  // ─────────────────────────────────────────
  // Indian — Classical / Legends
  // ─────────────────────────────────────────
  "a.r. rahman": {
    related: ["shankar ehsaan loy", "vishal-shekhar", "pritam", "ilaiyaraaja", "amit trivedi"],
    mood: "cinematic",
    region: "india"
  },
  "lata mangeshkar": {
    related: ["asha bhosle", "kishore kumar", "mohammed rafi", "alka yagnik", "shreya ghoshal"],
    mood: "classic",
    region: "india"
  },
  "asha bhosle": {
    related: ["lata mangeshkar", "kishore kumar", "mohammed rafi", "geeta dutt", "shreya ghoshal"],
    mood: "classic",
    region: "india"
  },
  "kishore kumar": {
    related: ["lata mangeshkar", "asha bhosle", "mohammed rafi", "mukesh", "sonu nigam"],
    mood: "classic",
    region: "india"
  },

  // ─────────────────────────────────────────
  // Indian — Bengali
  // ─────────────────────────────────────────
  "anupam roy": {
    related: ["arijit singh", "rupam islam", "nachiketa", "anjan dutt", "shreya ghoshal"],
    mood: "indie",
    region: "bengali"
  },

  // ─────────────────────────────────────────
  // Indian — Rising / Modern
  // ─────────────────────────────────────────
  "vishal mishra": {
    related: ["arijit singh", "jubin nautiyal", "b praak", "darshan raval", "armaan malik"],
    mood: "romantic",
    region: "india"
  },
  "darshan raval": {
    related: ["armaan malik", "vishal mishra", "jubin nautiyal", "b praak", "arijit singh"],
    mood: "romantic",
    region: "india"
  },
  "pritam": {
    related: ["a.r. rahman", "vishal-shekhar", "amit trivedi", "arijit singh", "shankar ehsaan loy"],
    mood: "cinematic",
    region: "india"
  },
  "amit trivedi": {
    related: ["a.r. rahman", "pritam", "vishal-shekhar", "shankar ehsaan loy", "nucleya"],
    mood: "cinematic",
    region: "india"
  },
  "guru randhawa": {
    related: ["badshah", "neha kakkar", "honey singh", "diljit dosanjh", "harrdy sandhu"],
    mood: "upbeat",
    region: "india"
  },

  // ─────────────────────────────────────────
  // Western — Pop / Mainstream
  // ─────────────────────────────────────────
  "taylor swift": {
    related: ["ed sheeran", "olivia rodrigo", "billie eilish", "dua lipa", "ariana grande"],
    mood: "pop",
    region: "western"
  },
  "ed sheeran": {
    related: ["taylor swift", "shawn mendes", "justin bieber", "sam smith", "lewis capaldi"],
    mood: "pop",
    region: "western"
  },
  "dua lipa": {
    related: ["the weeknd", "billie eilish", "ariana grande", "harry styles", "taylor swift"],
    mood: "dance pop",
    region: "western"
  },
  "billie eilish": {
    related: ["olivia rodrigo", "dua lipa", "the weeknd", "lorde", "taylor swift"],
    mood: "dark pop",
    region: "western"
  },

  // ─────────────────────────────────────────
  // Western — R&B / Hip-Hop
  // ─────────────────────────────────────────
  "the weeknd": {
    related: ["dua lipa", "post malone", "drake", "billie eilish", "frank ocean"],
    mood: "dark pop",
    region: "western"
  },
  "drake": {
    related: ["the weeknd", "post malone", "travis scott", "j. cole", "kendrick lamar"],
    mood: "hip hop",
    region: "western"
  },
  "post malone": {
    related: ["the weeknd", "drake", "khalid", "juice wrld", "travis scott"],
    mood: "hip hop",
    region: "western"
  },

  // ─────────────────────────────────────────
  // Western — Rock / Alt
  // ─────────────────────────────────────────
  "imagine dragons": {
    related: ["onerepublic", "coldplay", "maroon 5", "twenty one pilots", "bastille"],
    mood: "rock",
    region: "western"
  },
  "coldplay": {
    related: ["imagine dragons", "onerepublic", "u2", "radiohead", "the 1975"],
    mood: "rock",
    region: "western"
  },

  // ─────────────────────────────────────────
  // Western — Additional
  // ─────────────────────────────────────────
  "ariana grande": {
    related: ["dua lipa", "taylor swift", "the weeknd", "billie eilish", "selena gomez"],
    mood: "pop",
    region: "western"
  },
  "justin bieber": {
    related: ["ed sheeran", "shawn mendes", "charlie puth", "post malone", "khalid"],
    mood: "pop",
    region: "western"
  },
};

/**
 * Get related artists for a given artist name.
 * @param {string} artistName - normalized lowercase artist name
 * @returns {{ related: string[], mood: string, region: string } | null}
 */
function getAdjacency(artistName) {
  const key = artistName?.trim().toLowerCase();
  return ADJACENCY_MAP[key] || null;
}

function _shuffleArray(array) {
  for (let i = array.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [array[i], array[j]] = [array[j], array[i]];
  }
  return array;
}

function _findCompatible(key, value, exclude = new Set(), limit = 3) {
  const candidates = [];
  for (const [name, data] of Object.entries(ADJACENCY_MAP)) {
    if (data[key] === value && !exclude.has(name)) {
      candidates.push(name);
    }
  }
  return _shuffleArray(candidates).slice(0, limit);
}

/**
 * Find mood-compatible artists from the entire adjacency map.
 * Useful for curated trending injection that respects the user's emotional identity.
 * @param {string} mood - mood tag to match
 * @param {Set<string>} exclude - set of normalized names to exclude
 * @param {number} limit - max results
 * @returns {string[]}
 */
function findMoodCompatibleArtists(mood, exclude = new Set(), limit = 3) {
  return _findCompatible('mood', mood, exclude, limit);
}

/**
 * Find region-compatible artists from the entire adjacency map.
 * @param {string} region
 * @param {Set<string>} exclude
 * @param {number} limit
 * @returns {string[]}
 */
function findRegionCompatibleArtists(region, exclude = new Set(), limit = 3) {
  return _findCompatible('region', region, exclude, limit);
}

module.exports = {
  getAdjacency,
  findMoodCompatibleArtists,
  findRegionCompatibleArtists
};
