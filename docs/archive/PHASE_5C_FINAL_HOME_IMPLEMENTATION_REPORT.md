# SONARA ANDROID — PHASE 5C
# FINAL HOME IMPLEMENTATION REPORT

**Date:** 2026-08-26
**Scope:** Translate the frozen Phase 5B Home design into production Android/Jetpack Compose with a corrected information architecture, backed by a repaired and extended sonara-backend.
**Verification posture:** Backend tests were executed in this environment (91/91 pass). The Android Gradle build and physical-device pass could **not** be run here (no Gradle/adb in the sandbox) and are handed off to the user — see §22 and §23. Per the governing rule, success is **not** declared from static review alone.

---

## 1. Executive Summary

Phase 5C delivers the Home screen the 5B design froze, with one deliberate correction to the information architecture and Trending explicitly deferred. The screen is assembled as five independent, self-hiding modules — **Console Hero → Featured Artists → Curated Playlists → Quick Picks → Because You Listen To…** — each loading into its own state slot so that a single failed or empty section silently omits itself rather than collapsing the screen or fabricating filler.

The work spanned three layers. The **backend** was repaired (six Stage 0 defects) and extended with a server-side Quick Picks endpoint, and now passes 91/91 tests across 21 suites. The **data layer** added a `DiscoveryRepository` mapping 1:1 to five independently fault-tolerant endpoints, plus a per-module Home state model. The **UI layer** added the Console Hero re-entry surface, four discovery modules, and a set of shared truthful primitives, then removed the old placeholder scaffolding (including all Unsplash artwork).

Three disciplines held throughout: **no fabricated or placeholder data** (verified — zero Unsplash URLs remain in the source tree); **Oxide (accent) is spent only as a verb** (play/resume/retry/selected), never as resting decoration; and the **frozen playback and theme systems were not modified** — Android continues to talk only to sonara-backend, with `videoId` as canonical identity and stream URLs ephemeral.

What remains before this can be called done: the user must run `compileDebugKotlin`, `testDebugUnitTest`, and `assembleDebug`, then confirm behavior on a physical device. This report marks those two sections as **PENDING**.

---

## 2. Final Home Information Architecture

The authoritative 5C order, top to bottom:

1. **Console Hero** — the re-entry surface (always present).
2. **Featured Artists** — editorial roster.
3. **Curated Playlists** — the strongest discovery pillar (inline accordion).
4. **Quick Picks** — genuinely playable, rotatable picks.
5. **Because You Listen To…** — seed-based, truthful, self-hiding.

**Trending is intentionally deferred and absent.** The backend `/api/v1/trending` legitimately returns `[]`; surfacing an empty or fabricated Trending rail would violate the no-fabrication rule, so no Trending module is rendered. This is a correction to 5B, not an omission.

Every section below the hero is driven by a `HomeModule<T>` that is one of `Loading`, `Ready(value)`, or `Hidden`. **Hidden covers both failure and emptiness** — the module emits nothing, and the `LazyColumn` closes the gap. No section ever renders a "couldn't load" placeholder in the resting layout, and none invents content to fill space.

---

## 3. Backend Capabilities Used

The Android client (`SonaraBackendClient`) consumes five discovery endpoints, each mapped to one repository method and one Home module:

| Module | Endpoint | Client method |
|---|---|---|
| Featured Artists | `GET /api/v1/artists/featured` | `getFeaturedArtists()` |
| Curated Playlists | `GET /api/v1/playlists` | `getPlaylists()` |
| Playlist detail (accordion) | `GET /api/v1/playlists/{id}` | `getPlaylistDetail(id)` |
| Quick Picks | `GET /api/v1/quickpicks` (+`?refresh=1`) | `getQuickPicks(refresh)` |
| Because You Listen To… | `GET /api/v1/recommendations/related/{id}` | `getRelatedTracks(seedVideoId)` |

Each endpoint is **independently fault-tolerant**: a failure surfaces as `Result.failure` and self-hides that one module. All track lists are pre-filtered — server- and client-side — so every entry is genuinely playable (`videoId`-backed); no fabricated or placeholder track crosses the repository boundary. Discovery calls use a dedicated discovery timeout; the heavier `playlists/{id}` and `quickpicks` calls use the longer read timeout because they may fan out into multiple upstream searches. No secrets live on the client; Android never contacts the Python services (:5000/:5001) directly.

---

## 4. Quick Picks — Forensic Result

**Governing decision (in force):** *preserve the Web Quick Picks behavior, but implement it server-side against `ytmusicProvider.searchSongs()`, rather than touching JioSaavn.*

The Web client's Quick Picks logic was reproduced as a backend service (`quickPicksService.js`) exposed at `/api/v1/quickpicks`. JioSaavn was **not** modified. The service preserves the Web constants and behavior, enforces `videoId`-only playability, groups by artist, and balances variety so a single artist can't dominate the rail; its DTO shape matches the canonical `toTrackDTO` used by search. `?refresh=1` bypasses the server session cache and rotates the fallback query so the set visibly changes — wired on the client to the section header's "Refresh" action via a targeted `refreshQuickPicks()` that rotates only this rail and leaves the hero and other modules untouched.

This behavior is locked by five tests (suite 10–14): *constants preserve the Web behaviour*, *sanitizePlayable enforces videoId-only playability*, *artistKey grouping*, *balanceVariety*, and *toDTO shape matches routes/search.js toTrackDTO*. All pass.

---

## 5. Featured Artists Integration

Sourced from the real `/api/v1/artists/featured` roster. Each artist carries a stable slug `id`, display `name`, a human-authored `genre` descriptor, and a real portrait `imageUrl` or `null`. When the image is absent, the tile renders a typographic **monogram** (up to two initials) over the neutral surface — never a stock image.

The section is labelled editorially — header "Featured Artists", subtitle "A selection to explore" — and is **never** framed as personalization ("based on your listening"). Tapping a tile drills through **by name** into the real Search surface (`searchViewModel.onQueryChange(artist.name)` then navigate to Search), because a dedicated artist page is deferred (§25). The tile announces "View {name}" to screen readers.

---

## 6. Curated Playlists Integration

The strongest discovery pillar. `/api/v1/playlists` returns summary tiles rendered as a full-width vertical list; tapping a tile expands it **inline** (accordion) and lazily fetches `/api/v1/playlists/{id}`. The expanded body animates via `Modifier.animateContentSize()` — chosen over `AnimatedVisibility` specifically so a stale "couldn't load" body can never flash during collapse (the body is simply absent when collapsed). A late-arriving detail response is discarded if the user has since collapsed or switched tiles.

Playlist covers use server-derived artwork (the previously unroutable `coverImage` was replaced upstream in Stage 0); when absent, a neutral glyph fallback is shown. An expanded playlist that resolves to **no playable tracks self-hides its body** with an honest "No playable tracks in this set right now." line rather than showing an empty list. The tile announces "Expand/Collapse playlist" by state, and its decorative chevron is hidden from screen readers.

---

## 7. Recommendation Integration ("Because You Listen To…")

Seed = the head of the user's listening history (`getRecentHistory`), observed reactively so the rail re-seeds when the most-recent track changes and reloads only when the seed id actually changes. Recommendations come from `/api/v1/recommendations/related/{seedVideoId}`; the seed track itself is filtered out of the results so the app never recommends a song back to the listener who just played it.

The header is **truthful and specific** — "Because you listened to {seed title}" — never a generic "Recommended for you." The section stays **hidden until `Ready`**: while loading, the seed title is not yet known, so rendering a header would risk mislabeling it. Empty results or a provider failure self-hide the module (a provider outage surfaces as a failure, not as a misleading "no results").

---

## 8. Console Hero

The first module and the screen's re-entry surface. It is deliberately **not a second MiniPlayer**: no transport row, no seek-drag, no next/previous. It answers one question — "what do I return to?" — with a single primary action, and renders seven truthful states:

- **Loading** — baseline resolution (calm skeleton, no spinner).
- **Buffering / Active / Paused** — *live* states, overlaid from the authoritative player.
- **Resumable** — a real persisted session (artwork/title/artist/progress from the snapshot; primary action Resume).
- **ColdStart** — nothing playing and nothing to resume → an invitation to start listening.
- **Offline** — no connectivity and nothing live/resumable → a Retry.

The **baseline** (Loading / Resumable / ColdStart / Offline) is computed in the ViewModel from the persisted playback session; the **live** overlay (Buffering / Active / Paused) is resolved by the shell via the pure `resolveConsoleHero(playerState, baseline)`, whose live-track guard mirrors the MiniPlayer (`isConnected && trackTitle.isNotBlank() && trackTitle != "No Track Selected"`). The hero therefore **never fabricates a now-playing track** and never duplicates playback ownership. Accent (Oxide) is spent only on the single circular action button and on the "NOW PLAYING" label when a track is genuinely active.

---

## 9. Shared UI Primitives

Feature-agnostic building blocks assembled by the modules:

- **HomeSectionHeader** — title + optional subtitle + optional trailing text action (Quick Picks' "Refresh").
- **HomeArtwork** — Coil `AsyncImage` when a URL is present; otherwise the supplied truthful fallback over neutral `surfaceVariant` (never Oxide).
- **Monogram** — up to two initials for people (Featured Artists).
- **GlyphFallback** — a single glyph for tracks (♪, accent) and playlists (≋, neutral).
- **ArtistTile / PlaylistTile / QuickPickCard** — the three deliberately distinct tile treatments (circular portrait row, full-width accordion row, square play card).
- **ShimmerBox** + **ArtistTileSkeleton / PlaylistTileSkeleton / QuickPickCardSkeleton** — calm, accent-free load states.
- **TrackRowItem** (reused from `feature.library`) — the playlist-detail and recommendation track rows reuse the existing library row rather than duplicating it.

---

## 10. Loading / Error / Offline States

State is expressed uniformly through `HomeModule<T>`:

- **Loading** → the module renders its skeleton beneath the already-known static section header (so the section's identity is stable while content is in flight).
- **Ready(value)** → the real content.
- **Hidden** → failure **or** emptiness → nothing rendered.

Offline is *derived*, not guessed: it becomes true only when a network error was seen **and** no module produced usable content. In that condition the hero degrades `ColdStart → Offline` (and recovers `Offline → ColdStart` symmetrically), while a `Resumable` hero is preserved because its locally-cached metadata is still truthful to display. The playlist accordion has its own nested Loading/Ready/Hidden for the on-demand detail fetch.

---

## 11. Artwork Strategy

Images load through Coil `AsyncImage` with `ContentScale.Crop`, reusing the frozen `ArtworkUrlUpgrader` and Coil infrastructure. When a URL is `null` or blank, the container renders a **truthful typographic fallback** (monogram for people, glyph for tracks/playlists) over the neutral Petrol/Bone `surfaceVariant` tone — so a wall of missing images can never masquerade as a wall of "active" accent surfaces.

**No placeholder or stock artwork is used anywhere.** All Unsplash URLs that previously lived in the old scaffolding were removed in Stage 10; a full-tree search confirms **zero** `unsplash` references remain in `app/src/main`.

---

## 12. Search

The Search surface is unchanged and reused as the artist drill-through target. Setting `searchViewModel.onQueryChange(name)` feeds the existing reactive `stateIn` search flow (so assigning the query performs the search), then the shell navigates to Search. No Search code was modified for Phase 5C.

---

## 13. Navigation

The bottom navigation (Home / Search / Library) and the `NavigationDestination` model are unchanged. Home-specific navigation additions are additive: the hero's ColdStart "Start listening" and the artist tiles both route into Search. Critically, the Home `LazyListState` is **hoisted in the shell above the dual-layer theme-ripple branch**, so all three theme layers share one scroll state and the Home scroll position survives the theme-transition recomposition.

---

## 14. Responsive Design

Phone-first, portrait-primary. Horizontal rails (Featured Artists, Quick Picks) are `LazyRow`s of fixed-width tiles (112dp / 150dp), so they scroll gracefully at any width without reflow math. Vertical elements (playlist rows, section headers, hero) are `fillMaxWidth` and adapt to the column. The screen uses `contentPadding` for edge insets and relies on the Scaffold inner padding for the bottom (MiniPlayer + nav) inset. Every text element uses `maxLines` + `TextOverflow.Ellipsis`, so long titles and large system font scales truncate cleanly rather than breaking layout. Very wide tablet layouts are not specially optimized (content simply centers/stretches) — noted in §26.

---

## 15. Material 3 Usage

Material 3 is used strictly as an **engineering foundation**, not as the visual identity. **Dynamic Color is not enabled.** All color, type, spacing, and shape come from the custom `SonaraTheme` accessors (`SonaraTheme.colors/typography/dimensions/shapes`) backed by CompositionLocals — no `MaterialTheme.colorScheme` values leak into Home. M3 is leaned on where it earns its place: `minimumInteractiveComponentSize()` for the 48dp touch-target floor, `ripple()` for press feedback, and `Scaffold` for insets. No hardcoded color literals appear in any Home file.

---

## 16. Accessibility

- **Touch targets:** every interactive control clears 48dp. `SonaraIconButton` applies `minimumInteractiveComponentSize()`, so even the compact 28dp *visual* like-toggle has a full 48dp *interaction* target; the hero's primary action is 56dp.
- **Content descriptions:** all artwork carries a description ("{name} portrait", "{title} artwork", "{playlist} cover"); action buttons carry verbs ("Pause", "Resume {title}", "Retry", "Start listening"); like toggles are **stateful** ("Like {title}" / "Unlike {title}").
- **Click semantics (added this phase):** tap-to-act tiles now announce their action — "View {artist}", "Play {title}", and a stateful "Expand playlist" / "Collapse playlist" — via `onClickLabel`. The purely decorative accordion chevron is removed from the accessibility tree with `clearAndSetSemantics`.
- **Text scaling:** all labels use `sp` type via the theme and truncate with ellipsis, so large font-scale settings do not clip or overflow.

---

## 17. Motion

The signature **theme-reveal ripple honors reduced motion** — when the system animator scale is 0, the shell snaps instead of animating. Home micro-motion is intentionally quiet: the playlist chevron rotates via `animateFloatAsState`, and the accordion body grows/shrinks via `animateContentSize()`. The loading shimmer is a subtle 0.35→0.7 alpha pulse. **Known gap:** the chevron/accordion/shimmer animations are not individually gated to the reduced-motion setting (only the theme ripple is) — recorded in §25 as a refinement.

---

## 18. Files Added

**Android — UI**
- `feature/home/components/ConsoleHero.kt` — the seven-state re-entry surface.
- `feature/home/components/HomeModules.kt` — the four discovery modules as `LazyListScope` extensions + skeletons + `ExpandedPlaylistDetail`.
- `feature/home/components/HomeComponents.kt` — shared primitives (header, artwork, monogram, glyph fallback).
- `feature/home/components/HomeTiles.kt` — `ArtistTile`, `PlaylistTile`, `QuickPickCard`.
- `feature/home/components/HomeSkeletons.kt` — `ShimmerBox` + per-tile skeletons.

**Android — data / domain**
- `domain/repository/DiscoveryRepository.kt` — the discovery interface (5 methods).
- `data/repository/DiscoveryRepositoryImpl.kt` — thin pass-through to the backend client.
- `domain/model/FeaturedArtist.kt`, `PlaylistSummary.kt`, `PlaylistDetail.kt` — discovery domain models.

**Backend**
- `src/routes/quickpicks.js` + `src/discovery/quickPicksService.js` — the server-side Quick Picks endpoint (Stage 4).
- Tests: `tests/quickPicks.test.js`, `tests/discoveryRepairs.test.js`, `tests/playlists.test.js`, `tests/recommendations.test.js`.

*(Where a file predated Phase 5C but was substantially rewritten, it is listed under Modified.)*

---

## 19. Files Modified

For each: what changed, and whether it touches a frozen contract.

- **`feature/home/HomeScreen.kt`** — *rewritten*. Now a single hoisted `LazyColumn` assembling the corrected IA (hero + four module extensions). Old Welcome / Quick Play Catalog / Recently Played / 80dp-spacer scaffolding removed. **Frozen contract:** none.
- **`feature/home/HomeViewModel.kt`** — *rewritten*. Per-module independent loaders into `HomeModule` slots, history-seeded recommendations, hero baseline from the persisted session, derived offline. Added public `refreshQuickPicks()` for targeted rail rotation. **Frozen contract:** none (reads `SettingsRepository.getPlaybackSession()`, does not alter playback).
- **`feature/home/HomeUiState.kt`** — *rewritten*. Introduced `HomeModule<T>`, `ConsoleHeroState` (7 states), `ResumableSession`, `BecauseYouListenedData`, `HomeUiState`, and the pure `resolveConsoleHero`. **Frozen contract:** none; the hero live-track guard deliberately mirrors the MiniPlayer's guard for consistency.
- **`feature/shell/SonaraAppRoot.kt`** — *modified additively*. Hoisted `homeListState = rememberLazyListState()` above the theme-ripple branch; threaded `homeViewModel` + `homeListState` into `AppScaffoldContent` and **all three** call sites (bottom/top/resting layers); rewired the Home branch to the new `HomeScreen` with `resolveConsoleHero`. **Frozen contract — touched carefully:** the MiniPlayer invocation and the dual-layer theme-reveal animation were left byte-for-byte unchanged; only Home wiring was added around them.
- **`MainActivity.kt`** — *modified*. `HomeViewModel` factory now supplies its four dependencies (`discoveryRepository`, `historyRepository`, `libraryRepository`, `settingsRepository`). **Frozen contract:** none; `restorePlaybackSession` remains display/log-only.
- **`AppContainer.kt`** — *modified*. Added `discoveryRepository`; **removed** the dead `catalogRepository`. **Frozen contract:** none.
- **`data/remote/backend/SonaraBackendClient.kt`** — *extended*. Added the five discovery methods + private DTOs/mapping, reusing the existing fetch/timeout/exception-mapping and `videoId`-required filtering. **Frozen contract — preserved:** `videoId` identity, ephemeral stream URLs, and the backend security boundary are unchanged.
- **Backend discovery/recommendation services + routes** (Stage 0 repairs): `EligibilityEngine.js`, `featuredArtistsService.js`, `artistCatalogService.js`, `trendingService.js`, `PlaylistService.js`, `recommendationService.js`, `ytmusicProvider` (recommendation bindings), `routes/playlists.js`, `routes/artists.js`, `routes/recommendations.js`, `routes/trending.js`, and the shared error util (badRequest crash fix; axios→native-fetch). **Frozen contract — preserved:** the security boundary and `videoId` model are intact.

---

## 20. Files Removed

Removed in Stage 10 after verifying **zero** remaining references (checked across `main` and test source sets):

- `data/repository/CatalogRepositoryImpl.kt` — held `curatedSeed` (three Unsplash tracks), `getHomeCatalog()`, `getTrackDetails()`, and an unused `cacheTrack()`. Its only consumer was the old HomeViewModel.
- `domain/repository/CatalogRepository.kt` — the interface, implemented/referenced only by the two removed files + `AppContainer`.
- `data/stream/TestStreamProvider.kt` — test catalog + `sampleTracks` (more Unsplash URLs); zero references. `data/stream/StreamResolverImpl.kt` (the real resolver) is retained.
- `AppContainer.catalogRepository` — the DI property (self-reference only).

Post-removal sweep confirms no dangling references to `CatalogRepository`, `TestStreamProvider`, `getHomeCatalog`, `curatedSeed`, or `catalogRepository`, and no `unsplash` URLs anywhere in `app/src/main`.

---

## 21. Tests

**Backend — executed in this environment:** `npm test` → **91 tests / 21 suites / 91 pass / 0 fail** (~1.2s). Suite roll-call:

- Stage 0 repairs (6): eligibility filter consulted; ytmusic recommendation bindings; browseId shape guard; artist catalog cleaning; trending videoId resolution; featured artists renderable & navigable.
- Providers: JioSaavn V2 Provider; MusicBrainz Identity Layer.
- Curated Playlists & Eligibility.
- Quick Picks (5): constants preserve the Web behaviour; `sanitizePlayable` videoId-only; artistKey grouping; balanceVariety; toDTO shape parity with `routes/search.js`.
- Recommendation & Discovery Graph.
- Security stream validators (3): `validateVideoId`, `validateAudioUrl`, `validateYouTubeUrl`.
- `SearchQualityEngine`; `createTrack`; `validateTrack`.

**Android unit tests:** no new JVM unit tests were added for the Home UI in this phase; the ViewModel's module-state logic is a reasonable next target (§25). Compose UI tests are deferred. `testDebugUnitTest` must still be run by the user (§22).

---

## 22. Build Results — ⚠️ PENDING USER VERIFICATION

Gradle is **not available in this environment**, so the Android build was **not** compiled here. The user must run, from the project root:

```
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

**Static compile-correctness review performed here** (in lieu of, not as a substitute for, compilation): all five discovery client methods and their `Result<>` types verified against the repository and interface; `HistoryItem.track`, `PlaybackSessionSnapshot` fields, `LibraryRepository.setLiked/getTrack`, `SettingsRepository.getPlaybackSession`, and `SonaraIconButtonVariant.Accent` all confirmed present; `MainActivity`'s 4-arg `HomeViewModel` factory confirmed; **all three** `AppScaffoldContent` call sites confirmed to pass `homeViewModel` + `homeListState` (one missing set was found and fixed during review); `when` branches over `HomeModule` and `ConsoleHeroState` confirmed exhaustive; and no dangling references to any removed symbol. This raises confidence but **does not** constitute a successful build.

---

## 23. Physical Device Results — ⚠️ PENDING USER VERIFICATION

`adb` and a device/emulator are **not available in this environment**. Per the governing rule ("Do not declare success from compilation alone — verify on a real Android device"), the following must be checked on hardware, with the backend reachable at the configured address (§26):

- Cold start with no session → hero **ColdStart**; each module loads independently; failed/empty modules disappear (no gaps, no placeholders).
- Play a track, background the app, return → hero **Resumable** with correct artwork/title/progress; live playback shows **Active/Paused/Buffering**.
- Quick Picks "Refresh" rotates only that rail.
- Playlist tile expands inline, lazily loads tracks, and an empty set shows the honest empty line.
- Airplane mode → **Offline** hero and self-hidden modules; recovery on reconnect.
- Dark/light toggle mid-scroll → Home scroll position preserved; ripple honors "remove animations".
- TalkBack pass over tiles and hero.

---

## 24. Frozen Contract Verification

The following were **not modified** (confirmed by scoping all edits to Home/discovery files and leaving these untouched): `MiniPlayer.kt`, `SonaraPlaybackService.kt`, `MediaControllerClient.kt`, the `PlayerViewModel` playback authority, the Theme Toggle, the theme-reveal animation, the Room schemas, the DataStore contracts, `LrclibLyricsProvider`, the LRC parser, Romanization, `ArtworkUrlUpgrader`, and the Coil infrastructure. `SonaraAppRoot.kt` was edited, but additively — the MiniPlayer invocation and the dual-layer theme animation are unchanged.

Preserved architectural invariants: **`videoId` is canonical identity**, **stream URLs remain ephemeral**, **Android communicates only with sonara-backend** (never Python/googlevideo directly), **no server secrets on the client**, **single playback owner** (the hero reads state and issues play/pause/resume through existing paths; it does not own playback), and **no Dynamic Color**.

---

## 25. Deferred Work

- **Trending** — deliberately absent until the backend returns real, playable trending data.
- **Artist detail page** — drill-through currently resolves by name through Search; a dedicated artist screen is future work.
- **Seek-on-resume** — the hero shows the saved position and restarts the track on resume; true seek-to-position is deferred (`MainActivity.restorePlaybackSession` is display/log-only).
- **Queue API** — playback is single-track per tap; there is no queue surface yet.
- **Exposure balancing** — Quick Picks and Curated Playlists may surface overlapping tracks; cross-module de-duplication is deferred.
- **Reduced-motion gating** — extend the reduced-motion honor from the theme ripple to the chevron/accordion/shimmer micro-animations.
- **Tests** — add JVM unit tests for `HomeViewModel` module-state transitions and Compose UI tests for the hero states.
- **`ItunesSearchProvider`** — newly orphaned by the Stage 10 removal of `CatalogRepositoryImpl` (its only consumer). Left in place pending an explicit decision, since it is a functional provider rather than placeholder debris; removing it was out of the specified scope.

---

## 26. Known Limitations

- **Hardcoded backend address:** `SonaraBackendConfig.BASE_URL = "http://192.168.0.4:3002"` (a LAN IP for physical-device testing). Emulators need `10.0.2.2:3002`; other networks need the host's current LAN IP. This is config, not build-time flexible.
- **Single-track playback:** no queue; each tap plays one track.
- **Resume restarts the track:** position is displayed but not seeked to (see §25).
- **Artist catalog latency / possible emptiness:** cold artist-image resolution can be slow and may return few or no entries; the module self-hides rather than stalling the screen.
- **Playlists may under-fill:** eligibility filtering can leave a playlist with fewer playable tracks than its nominal size; an empty detail self-hides.
- **Dual-layer theme ripple double-composes Home:** during the ~1.2s transition, Home is composed twice (both theme layers) — acceptable but not free; the shared hoisted `LazyListState` keeps scroll consistent.
- **Room v1, no migrations:** schema changes will require a migration strategy before shipping.
- **No Compose UI tests yet** for the Home surface (§25).

---

*End of report. Sections 22 and 23 remain PENDING until the user completes the Gradle build and the physical-device pass; this report does not claim a successful Android build.*
