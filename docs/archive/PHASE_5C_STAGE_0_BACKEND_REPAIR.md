# SONARA ANDROID — PHASE 5C

# STAGE 0 — BACKEND REPAIR REPORT

**Status:** Complete and self-verified
**Date:** 2026-08-26
**Scope:** `sonara-backend` only. No Android code was written in this stage.
**Verification:** Node 22, `npm test` → 75/75 passing (baseline was 50/50; 25 new tests added). Full route-level end-to-end exercise against a stubbed Python service.

---

## 1. Why Stage 0 exists

The Phase 5B frozen Home specification depends on five discovery pillars: Curated
Playlists, Featured Artists, Trending, Recommendations, and Artist drill-through.
A pre-implementation audit found that **four of the five were non-functional**,
and that every one of them failed *silently* — the route returned HTTP 200 with an
empty or unplayable payload rather than an error.

That failure mode is the reason the defects went unnoticed. It also means the
frozen design could not have been implemented honestly: a module specified to
distinguish loading, success, empty, error and offline states cannot do so when
the backend reports "success, nothing here" for every kind of failure.

Per the decision to **fix the backend first, then implement**, this stage repairs
the data layer before any Compose code is written.

---

## 2. Two corrections to my own earlier audit

Before the repair list, two claims I had previously recorded were wrong, and I
corrected them against source rather than carrying them forward:

**Curated Playlists were not the weak pillar — they are the strongest.**
I had recorded them as lacking playable data. In fact `PlaylistService` resolves
through `ytmusicProvider.searchSongs`, the very same provider that powers working
production search, so it yields real 11-character YouTube videoIds and real
YTMusic thumbnails. The pillar was mis-assessed.

**The recommendation engine was not missing a capability.**
I had recorded it as needing backend capability that did not exist. In fact the
Python service already implements every endpoint required; only the *Node-side
bindings* were absent. A fuller ListenBrainz and MusicBrainz identity stack
already sits behind it, including 251 persisted identity mappings on disk.

---

## 3. Defects repaired

### 3.1 `Errors.badRequest` — a process-killing crash vector

`src/errors/errors.js` exports `invalidRequest`, `notFound`, `forbidden`,
`rateLimited`, `providerFailure`, `streamUnavailable` and `internal`. It does
**not** export `badRequest`. Five route handlers called `Errors.badRequest(...)`.

Each call sat inside an `async` handler, so the resulting `TypeError` became a
rejected promise. Express 4 does not understand promises returned from handlers,
so the rejection went unhandled — and under Node ≥ 20 an unhandled rejection
**terminates the process**. Any client sending a malformed `videoId` or `mbid`
could take the server down.

Two fixes: every call corrected to `Errors.invalidRequest`, and a new
`src/middleware/asyncHandler.js` that routes async rejections into Express's error
pipeline. `Promise.resolve().then(() => handler(...)).catch(next)` is used
deliberately so that *synchronous* throws are captured too.

### 3.2 The playlist eligibility filter was inert

`EligibilityEngine.isEligible(track, config)` takes two parameters and returns
`{ eligible, reason }`. `PlaylistService` called it with three arguments and
negated the returned object. **`!{...}` is always `false`**, so no track was ever
rejected. Duration bounds, remix/lofi/slowed/instrumental exclusion and
excluded-genre rules all silently did nothing, and the `artistCounts` map was
passed to a function that never reads it.

Every "curated" playlist was therefore just the first N deduplicated raw search
results. The word *curated* was not earned.

Three further repairs in the same file:

- **`maxArtistTracks` is now enforced.** `EligibilityEngine` documents it as a
  collection-level constraint it deliberately does not handle, and names
  `CandidateSelectionPipeline` as the enforcer — a module that does not exist in
  this backend. The cap is per-definition (`fresh_releases` uses 1, most use 2,
  the single-artist `arijit_singh` playlist uses 30), so it is read from config,
  never hardcoded.
- **Eligibility is evaluated on the raw `CanonicalTrack`, not the outbound DTO.**
  This was a trap worth naming: the DTO coerces unknown duration to `0`, and `0`
  is neither `null` nor `undefined`, so it would have failed the `duration < 60`
  check and rejected *every* track whose duration the provider did not report.
  Fixing the filter without fixing this would have replaced "nothing is filtered"
  with "almost everything is filtered".
- **Candidate queries now run in parallel.** They were sequential `await`s, so a
  three-query playlist cost up to 3 × 8s = 24s on a cold cache. One query failing
  no longer discards the others' results.

Because filtering now actually rejects tracks, a playlist may under-fill. That is
logged as a warning with per-reason rejection counts, never raised as an error.
Empty results are no longer cached, so a transient provider outage cannot pin an
empty playlist for an hour.

### 3.3 `axios` was called but never installed

`MusicBrainzService` and `ListenBrainzService` both called `axios.get(...)`
without requiring axios — and axios appears in neither `package.json` nor
`node_modules`. Every call threw `ReferenceError: axios is not defined`. Because
the recommendation engine wraps these calls in `try/catch`, the failure surfaced
as HTTP 200 with `{ success: false, tracks: [] }` instead of an obvious crash.

Rather than add a dependency, a new `src/utils/httpJson.js` provides `getJson`
over native fetch, matching `ytmusicProvider` which already states "No axios
dependency". It normalizes the four places fetch differs from axios: non-2xx must
be converted to a throw (fetch resolves on 4xx/5xx), query strings need
`URLSearchParams`, timeouts need `AbortSignal.timeout`, and bodies need an
explicit `.json()`.

Error messages reference the *path* rather than the full URL, so query parameters
never reach logs.

### 3.4 Three missing `ytmusicProvider` exports

`recommendationService` calls `ytmusic.getWatchPlaylist`, `getRelatedSongs` and
`getSongDetails`. The module exported only `{ searchSongs, searchMetrics }`. All
three were `undefined`.

`getSongDetails` was a defect discovered mid-repair — the original audit had found
only the first two.

All three were added with a shared `_detailGet` helper providing a 30-minute
cache and in-flight stampede protection, treating Python's 404 as "nothing
available" rather than failure. **`searchSongs` was deliberately left untouched**
as the live production search path; this repair should not risk changing it.

### 3.5 `artistCatalogService` called two URLs that do not exist

- Primary: `GET {python}/artist-catalog/{id}` — Python exposes
  `GET /api/artist-catalog-deep?artistName=|browseId=`. Wrong prefix, wrong name.
- Fallback: `POST {python}/search` — Python exposes `GET /api/search?q=`. Wrong
  prefix, wrong path, wrong method.

Both always 404'd, so the function always reached its final `return` and handed
back `{ tracks: [] }`. A consequence: `isJunkVariant` and `normalizeTitle` — the
module's entire stated purpose — never executed on real data. They now run on
both paths.

A **browseId shape guard** was added. Python only resolves an artist name when no
browseId is supplied, so passing a slug in the browseId position would be
accepted, fail the official-playlist lookup, and silently degrade to deep search.
Only values matching `/^(UC|MPLA|MPAD)[A-Za-z0-9_-]{10,}$/` are forwarded.

The client timeout was raised from 10s to 21s because the Python endpoint wraps
its own work in a 20s gevent timeout — the old value could not have succeeded on
a cold lookup.

### 3.6 Featured Artists were neither renderable nor navigable

`GET /featured` returned a hardcoded array of `{ name, genre }`. No identifier, so
a tile had nothing to drill through with; no image, so a tile had nothing to
render but text.

New `src/discovery/featuredArtistsService.js` supplies both, using only real
data. Images come from Python `GET /api/artist-image?name=`, which returns the
artist's real YTMusic thumbnail. Lookups run in parallel, are individually
fault-tolerant, and are cached 24 hours. **No fabricated or stock imagery is
introduced** — when an image cannot be resolved, `imageUrl` is null and the client
renders a typographic monogram, per the artwork decision. A missing image degrades
one tile; it never blanks or hides the module.

Verified behaviour: with two of six lookups deliberately failing (one HTTP 500,
one 200-with-null), all six entries still returned, in stable order, with ids.

Only real hits are cached — caching a null would pin a missing portrait for a full
day after one transient timeout.

### 3.7 Trending was fully unplayable

`jiosaavnV2Provider.normalizeV2Song` sets `videoId: null`, with the comment
"JioSaavn IDs are not YouTube videoIds". Sonara's canonical track identity **is**
the YouTube videoId and streaming resolves through it, so every trending item
shipped to the client was untappable — a fully rendered module where nothing
responds to a tap.

New `src/discovery/trendingService.js` retains JioSaavn only as the *editorial
signal* for what is currently trending, then re-resolves each item to a real
videoId by matching title and artist through `ytmusicProvider.searchSongs`.
Unmatched items are **dropped rather than shipped unplayable**.

Matching is deliberately conservative. Accepting the first search hit would
quietly substitute karaoke tracks, covers and 8D remixes for real releases. A
candidate must clear junk-variant rejection, normalized-title match, artist token
overlap, and a duration sanity check, and the resulting videoId is validated with
the same `validateVideoId` the stream endpoint uses. Resolution runs at
concurrency 4 so listing twenty items does not fire twenty simultaneous searches
at Python.

Verified: 4 upstream items → 2 playable. The karaoke variant was rejected, an
unmatchable item was dropped, and two distinct JioSaavn entries that resolved to
the same video were collapsed.

### 3.8 Playlist cover artwork pointed at files that do not exist

All ten definitions carried `coverImage: '/assets/playlist-artwork/*.png'`. There
is **no `assets/` directory**, none of those PNGs were ever created, and
`src/index.js` registers **no `express.static`**. Every one of the ten cover URLs
was unroutable — a guaranteed broken image on the Home screen.

Covers are now derived from real track artwork via
`PlaylistService.getPlaylistCover`, per the artwork decision. It deliberately does
**not** resolve the full playlist: Home lists all ten at once, and ten full builds
would cost up to twenty-five provider queries. It reuses an already-warm playlist
when one exists, and otherwise runs a single query and takes the first eligible
track's thumbnail. Cached 24 hours. Null is a legitimate outcome and the client
renders its typographic fallback.

The dead `coverImage` field was removed from all ten definitions, with a note in
the header explaining why it must not be reintroduced as a bare path string.

### 3.9 A non-standard error body

`GET /api/v1/playlists` returned `{ error: 'Failed to fetch playlists' }` — a bare
string, where every other route returns `{ error: { code, message } }` from
`SonaraBackendError.toJSON()`. A client parsing errors uniformly would have
failed on this one shape. It now goes through the shared error middleware.

### 3.10 Empty-but-200 eliminated on recommendations

Three near-identical recommendation handlers were replaced with a factory. A
provider failure now returns **502 `PROVIDER_FAILURE`** with a sanitized client
message; the internal failure reason is logged server-side only. This is the
change that lets the frozen design's error and empty states mean different things.

### 3.11 Misleading documentation corrected

The JSDoc usage examples in `mbRateLimiter.js` and `lbRateLimiter.js` still told
future developers to call `axios.get`. Cosmetic, but actively misleading now that
axios is confirmed absent. Both now show `getJson`.

---

## 4. Verification performed

**Unit tests.** `npm test` → **75/75 passing**, up from a clean 50/50 baseline
taken before any change. The 25 new tests in `tests/discoveryRepairs.test.js`
target the exact silent-failure modes, including the two traps most likely to
regress: that `isEligible`'s rejection object is *truthy* (so callers must read
`.eligible`), and that a DTO-coerced `duration: 0` must not be treated as a
length claim.

**Route-level end-to-end.** The real Express app was booted against a stubbed
Python service implementing the genuine response shapes. Every previously-broken
route now returns a well-formed payload:

| Route | Result |
|---|---|
| `GET /api/v1/playlists` | 200 · 10 playlists, 7 with derived covers |
| `GET /api/v1/playlists/:id` | 200 · tracks with real videoIds |
| `GET /api/v1/artists/featured` | 200 · 6 artists, all with ids, 4 with images |
| `GET /api/v1/artists/:slug?name=` | 200 · deduped catalog, slug correctly not forwarded as browseId |
| `GET /api/v1/trending` | 200 · every item a validated 11-char videoId |
| `GET /api/v1/recommendations/radio` | 200 · real tracks |
| `GET /api/v1/recommendations/related` | 200 · real tracks |
| `GET /api/v1/recommendations/similar` | 502 `PROVIDER_FAILURE` — correct: MusicBrainz is unreachable from the sandbox |

That last row is itself a verification win. The `fetch failed` in the logs proves
`getJson` is now attempting a real HTTP call, where previously the code path threw
`ReferenceError: axios is not defined` before any network activity.

**Crash tests.** Every input that previously killed the process now returns a
correctly-shaped error, and the process survives all of them:

| Input | Result |
|---|---|
| `/recommendations/radio/abc` | 400 `INVALID_REQUEST` |
| `/recommendations/related/%20` | 400 `INVALID_REQUEST` |
| `/recommendations/radio/../../etc/passwd` | 404 — path normalized, no traversal |
| `/playlists/does_not_exist` | 404 `NOT_FOUND` |
| `/identity/metadata/not-a-mbid` | 404 `NOT_FOUND` |

`GET /health` returned 200 afterwards, confirming survival.

**Regression sweep.** No live `Errors.badRequest` or `axios` references remain
(only explanatory comments). No `videoId: null` reaches any client route. All four
async handlers left unwrapped were individually confirmed to have complete
`try/catch` coverage, so no crash vector remains.

---

## 5. Security boundary — unchanged

No change crosses the boundary. Android still communicates only with
`sonara-backend`. No Python address, API key, secret, `yt-dlp` invocation or raw
`googlevideo` extraction logic is exposed. `SearchQualityEngine` was not
duplicated. `videoId` remains the canonical identity, and the trending repair
*strengthens* it by validating every id with the same validator the stream proxy
uses. Provider error text is logged server-side and never returned to clients.

No frozen system was touched: `MiniPlayer.kt`, `SonaraPlaybackService.kt`,
`MediaControllerClient.kt`, `PlayerViewModel.kt`, the theme toggle and its reveal
animation, Room schemas, DataStore contracts, the LRCLIB/LRC/romanization stack,
`ArtworkUrlUpgrader`, Coil infrastructure, and the M3 foundation are all
unmodified. `searchSongs` — the live production search path — was deliberately
left alone.

---

## 6. One place where I chose an approach

The trending repair is the only Stage 0 change where more than one defensible
option existed, so it is flagged rather than buried:

1. **Resolve JioSaavn items to YouTube** — chosen.
2. Drop JioSaavn and build trending from YTMusic charts directly.
3. Hide the Trending module until a first-party trending source exists.

Option 1 preserves the existing curated trending signal while satisfying the
frozen playability contract, and required no new external dependency. It should be
revisited, because it inherits JioSaavn's regional bias and depends on
`jiosaavn.rajputhemant.dev` — a third-party self-hosted mirror this project does
not control.

---

## 7. Deliberately not done

**Shared Python was not modified.** `/api/artist-image` internally obtains each
artist's browseId from its YTMusic artist search and then discards it, returning
only `{ name, image }`. Surfacing it would be purely additive and would let artist
drill-through skip a resolution step. It was *not* done, because the Python
service is shared infrastructure with an independent consumer, and expanding scope
into it silently is not warranted. Featured artists therefore resolve by name,
which works correctly today via the browseId shape guard. Recorded as a follow-up.

---

## 8. Carried into Phase 5C implementation

Constraints found during Stage 0 that shape the Android work, reported now rather
than absorbed silently:

- **`similar` is structurally slower than `radio` and `related`.** The latter two
  resolve through local Python/YTMusic; `similar` depends on MusicBrainz and
  ListenBrainz, external services rate-limited to roughly 1 req/sec. Any Home
  module built on `similar` must be lowest priority, must never block, and will
  legitimately self-hide more often than its siblings.
- **Room v1 has no migrations**, so no new entities may be added for Home.
- **`MediaControllerClient` has no queue API**, so the Console Hero can restore
  track and position but not `queueTrackIds`.
- **The dual-layer theme ripple composes `AppScaffoldContent` twice** during the
  1200 ms animation, so state `remember`ed inside `HomeScreen` — a
  `rememberLazyListState`, for instance — will duplicate or reset.
- **`SonaraBackendConfig.kt:28` hardcodes `http://192.168.0.4:3002`.**
- **Playlists may legitimately under-fill** now that filtering works, and
  **trending may legitimately return fewer items than upstream** — both are empty
  or partial states, not errors, and the frozen per-module state model must treat
  them as such.

---

## 9. Files changed

**Created**

- `src/middleware/asyncHandler.js`
- `src/utils/httpJson.js`
- `src/discovery/featuredArtistsService.js`
- `src/discovery/trendingService.js`
- `tests/discoveryRepairs.test.js`

**Modified**

- `src/routes/recommendations.js` · `src/routes/identity.js` ·
  `src/routes/artists.js` · `src/routes/trending.js` · `src/routes/playlists.js`
- `src/discovery/PlaylistService.js` · `src/discovery/PlaylistDefinitions.js` ·
  `src/discovery/artistCatalogService.js`
- `src/identity/MusicBrainzService.js` · `src/identity/mbRateLimiter.js`
- `src/recommendation/ListenBrainzService.js` ·
  `src/recommendation/lbRateLimiter.js`
- `src/search/ytmusicProvider.js`

**Untouched by design:** `src/search/trackModel.js`, `src/routes/search.js`,
`src/routes/stream.js`, `src/errors/errors.js`,
`src/middleware/errorMiddleware.js`, `src/stream/streamValidator.js`,
`jiosaavnV2Provider.js`, and all Python services.

---

## 10. What to run on the host

```
cd backend
npm test                 # expect 75/75
npm start                # with the Python services on :5000 and :5001
```

Then, against a live stack, spot-check that real data flows:

```
curl localhost:3002/api/v1/playlists
curl localhost:3002/api/v1/artists/featured
curl localhost:3002/api/v1/trending
curl localhost:3002/api/v1/recommendations/radio/<a-real-videoId>
```

Trending and featured artists are the two worth watching most closely, since both
depend on live upstream matching that a stub cannot represent faithfully. Expect
trending to return fewer items than the upstream feed — that is the repair working,
not a fault.
