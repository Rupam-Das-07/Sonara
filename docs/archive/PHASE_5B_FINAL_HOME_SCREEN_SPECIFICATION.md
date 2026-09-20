# SONARA ANDROID — PHASE 5B
# FINAL HOME SCREEN VISUAL SPECIFICATION
### Correction + Reconciliation + Freeze Pass

> **Status:** FROZEN — supersedes `PHASE_5B_HOME_WIREFRAME_SPEC.md` (retained as historical reference).
> **Scope:** Design / specification only. No code modified. No production UI implemented.
> **Thesis:** THE CONSOLE (structural spine) + THE TUNER (progressive discovery) + EDITORIAL ROOMS (typographic discipline).
> **Verification basis:** All geometry, color, breakpoint, backend, and shell facts in this document were read directly from source on 2026-08-26 (`Color.kt`, `Dimensions.kt`, `Typography.kt`, `Shape.kt`, `MiniPlayer.kt`, `SonaraAppRoot.kt`, `backend/src/index.js`, route files, `SonaraBackendClient.kt`). Where a dimension is not a base token, it is labelled honestly as *derived* or *new-justified*.

---

## 1. Executive Summary

This pass does not restart the Home design. It accepts the corrections from the adversarial review, reconciles them against the *actual* Sonara design system and the *actual* independent `sonara-backend`, resolves the contradictions the previous 5B spec carried, and freezes the result.

**What the thesis is.** Home is a single instrument with three registers. **The Console** is the structural spine: a resume-first re-entry surface at the top that gets you back into listening in one tap. **The Tuner** is the progressive discovery body beneath it — modules that appear only when they have something real to show. **Editorial Rooms** is the typographic discipline that makes each section read as a named editorial space rather than a Material dashboard card.

**What changed from the previous 5B spec (nine accepted corrections).**

1. **Search** moves out of the first viewport into a compact top-shell affordance. It no longer sits as a large field above the Console Hero, which had quietly contradicted the resume-first thesis.
2. **Hero artwork** moves from an arbitrary `88dp` to `96dp` (on-grid, `2×space5Xl`, ≈2× the MiniPlayer's `46dp` artwork), giving a minimum Hero height of `128dp`.
3. **Light-mode Hero** is no longer permanently Petrol. The frozen MiniPlayer already provides the one permanent Petrol anchor in Bone mode; a second permanent dark slab is dropped. The Hero uses Petrol only when there is a resumable/active session, and a Bone-native surface when cold.
4. **Content order** stops reproducing the `Resume → Recently Played → Your Music` template. Discovery is pulled forward; the personal library block moves later, because the Hero already surfaces the single most-recent track.
5. **Cold start** becomes genuinely useful — populated with non-personalized, seed-free capability rather than an elegant empty state.
6. **Moods** is removed as a standalone module. No backend contract exists for it; mood intent is carried by curated playlists (e.g. *Chill Nights*).
7. **Trending** is added as a first-class discovery pillar — it is confirmed live (`GET /api/v1/trending`) and needs no seed.
8. **Recommendation language** becomes honest and contextual (*Because you played X*, *More like X*, *Radio from X*) rather than an opaque *Recommended For You*, because the backend exposes only track-seeded recommendations, not a personalized feed.
9. **Backend capabilities** are reconciled from the live routes, not from stale docs. Seven routes are mounted; the Android client currently consumes three.

**Two honesty corrections to the review's own framing**, surfaced rather than rubber-stamped:

- The MiniPlayer is **~140–146dp of occupied height** (measured from `MiniPlayer.kt`), which is *taller* than the `128dp` Hero. The Hero is therefore not "a giant MiniPlayer"; it is the shorter, quieter of the two anchors. The earlier claim that the Hero dwarfs the MiniPlayer was wrong.
- `96dp` and `128dp` are **not** members of the base token scale (which ends at `space6Xl = 64dp`). They are on-grid *derived* composites. The spec labels them as such; it does not pretend they are named tokens.

**Backend posture (the load-bearing reality).** Of the modules this design shows, only **Search** is wired into the Android client today. **Trending, Playlists, Artists, and Recommendations exist on the backend but are not yet wired into the client.** The design is therefore built so that every discovery module is *independently optional and self-hiding*: Home is honest and complete with only Search wired (today), and scales up to the fully populated composition as each endpoint is wired in Phase 5C — with no redesign.

---

## 2. Backend Capability Verification

Read from `backend/src/index.js` (route mounts), the route files, and `SonaraBackendClient.kt` (client surface) on 2026-08-26. The README's endpoint table is stale and was **not** trusted.

Three tiers are distinguished:
**AVAILABLE NOW** = backend route exists *and* the Android client already calls it.
**BACKEND EXISTS / CLIENT NOT WIRED** = route exists; the client does not call it yet (Phase 5C wiring).
**NOT CURRENTLY AVAILABLE** = no route; must not be designed as if it exists.

| Capability | Backend Endpoint | Client Wired? | Home-Ready? | Status | Notes |
|---|---|---|---|---|---|
| Search | `GET /api/v1/search?q=` | **Yes** | Yes | **AVAILABLE NOW** | Powers the search affordance. |
| Stream resolve / play | `GET /api/v1/stream/resolve`, `GET /api/v1/stream/play` | **Yes** | Yes (infra) | **AVAILABLE NOW** | Not a Home module. Domain-allowlist enforced server-side; Android never touches :5000/:5001. |
| Trending | `GET /api/v1/trending` | No | **Yes — no seed** | BACKEND EXISTS / CLIENT NOT WIRED | Returns `{ tracks }` via provider. Ideal cold-start module. |
| Curated Playlists | `GET /api/v1/playlists`, `GET /api/v1/playlists/:id` | No | **Yes — no seed** | BACKEND EXISTS / CLIENT NOT WIRED | Rule-based curated sets (e.g. *Chill Nights*). Absorbs "moods". |
| Featured Artists | `GET /api/v1/artists/featured` | No | **Yes — no seed** | BACKEND EXISTS / CLIENT NOT WIRED | Server-defined featured list. |
| Artist adjacency / catalog | `GET /api/v1/artists/:browseId?name=` | No | Yes — needs artist id | BACKEND EXISTS / CLIENT NOT WIRED | Related artists via adjacency. |
| Related tracks | `GET /api/v1/recommendations/related/:videoId` | No | Yes — needs track seed | BACKEND EXISTS / CLIENT NOT WIRED | Seed = a specific track. |
| Similar tracks | `GET /api/v1/recommendations/similar/:videoId` | No | Yes — needs track seed | BACKEND EXISTS / CLIENT NOT WIRED | Powers *More like X*. |
| Track-seeded Radio | `GET /api/v1/recommendations/radio/:videoId` | No | Yes — needs track seed | BACKEND EXISTS / CLIENT NOT WIRED | *Radio from X* — contextual, not pre-baked stations. |
| Identity (MusicBrainz) | `GET /api/v1/identity/...` | No | Indirect | BACKEND EXISTS / CLIENT NOT WIRED | Internal enrichment (canonical artist metadata). Not a user-facing module. |
| Personalized feed (blended) | *(none exposed)* | — | No | **NOT CURRENTLY AVAILABLE** | Recommendations are seed-based only. Do not label anything "Recommended For You". |
| Moods (dedicated) | *(none exposed)* | — | No | **NOT CURRENTLY AVAILABLE** | Represent through curated Playlists. |
| Server-side library / likes / history | *(deferred, Phase 4E)* | — | Local only | **NOT CURRENTLY AVAILABLE** | History is local (`PlaybackSessionSnapshot`); it seeds recommendations and the Hero. |

**Consequences for Home.** Trending, Playlists, and Featured Artists are seed-free and therefore cold-start-capable. Related / Similar / Radio require a track seed, which comes from the local most-recent/liked track — so they self-hide at true first run and appear once any listening exists. There is no personalized-feed endpoint, so recommendation copy is contextual and truthful. Identity is enrichment only and never becomes its own row.

---

## 3. Final Information Architecture

The required balance is Resume · Immediate discovery · Contextual recommendation · Curated discovery · Personal library. The frozen order:

1. **Console / Resume Hero** — re-entry into listening.
2. **Trending** — immediate, non-personalized discovery.
3. **Because You Played X / More Like X** — contextual, track-seeded recommendation.
4. **Curated Playlists** — editorial, rule-based discovery (absorbs moods).
5. **Featured Artists** — entity-level discovery.
6. **From Your Listening** — recent tracks + library re-entry.

**Why this order, and why it is not the conventional template.** The Spotify/Apple/YouTube pattern leads with the Hero and then immediately with *Recently Played*, on the assumption of a returning user with deep history. Sonara deliberately inverts the middle: after re-entry, it leads with **discovery**, and defers the personal library block to last. Two reasons. First, the Console Hero already surfaces the single most-recent track and its resume position — so an immediate *Recently Played* row directly beneath it duplicates the same focus and wastes the most valuable strip of the screen. Second, the discovery modules are what make Home feel alive and specifically *Sonara*; leading with them (Trending needs no personal data at all) means even a brand-new user meets a living screen, not a personal-history mirror they haven't filled yet.

**Why each module exists.**
- **Console Hero** — the app's dominant recurring intent is *continue listening*. One-tap re-entry is the single most-used action, so it earns the top. It self-adapts to a *Start listening* invitation when there is nothing to resume.
- **Trending** — the only module that is compelling with zero personal data and zero seed. It is the backbone of a useful cold start and the reason Home is never barren.
- **Because You Played X / More Like X** — honest, track-seeded deepening of current taste (`recommendations/similar` · `related`). Appears only when a seed track exists.
- **Curated Playlists** — human-intent, rule-based editorial sets (`playlists`). Carries mood-oriented discovery (*Chill Nights*) without inventing a "moods" API.
- **Featured Artists** — entity-level lateral discovery (`artists/featured`, adjacency). Cold-start friendly.
- **From Your Listening** — continuity depth (recent + library). Present but deferred, because the headline continuity job is already done by the Hero.

Every module is independently optional. Any module that has no data — or is not yet wired — collapses to zero height and leaves no gap.

---

## 4. Console Hero Specification

**Role — stated explicitly.** The Console Hero is **re-entry into listening**. The MiniPlayer is the **compact controller you use while listening**. They are the same instrument in two registers, and they must not be confused:

| | Console Hero | MiniPlayer (frozen) |
|---|---|---|
| Job | Get back *into* a session | Control the *current* session |
| Occupied height | `128dp` (embedded panel) | ~`140–146dp` (floating capsule) |
| Controls | **one** primary action | full transport (play/prev/next/shuffle/repeat) |
| Elevation | inset into the page, hairline border, **no** float shadow | floats, `18dp` shadow, `28dp` capsule |
| Presence | scrolls with content | persistent across destinations |
| Progress | optional thin line, when resumable | always-on segmented "liquid" rail |

This is why the Hero is **not** "a giant MiniPlayer": it is shorter, quieter, single-action, embedded rather than floating, and it disappears upward as you scroll. It is the front door; the MiniPlayer is the steering wheel.

**Final design question — is the Hero visually dominant?** It *anchors* without *dominating by default*. It leads in reading order and carries the screen's only Oxide element and its largest composed surface, so the eye lands there first. But it is the shorter of the two anchors, it uses a single control rather than a transport cluster, and — critically — it only earns full prominence when there is something to resume. At cold start it is an invitation, not a hero image. Prominence is *conditional on there being a listening state to re-enter*, which is the honest expression of a resume-first thesis.

**Preferred geometry (frozen target).**

- Artwork `96dp`, rounded-square, radius `12dp` (`shape.medium`).
- Minimum Hero height `128dp` = `96` artwork + `16dp` top + `16dp` bottom (`spaceLg` both sides).
- Internal padding `spaceLg` (`16dp`) on all sides.
- One primary action: filled Oxide button, `48dp` (`space5Xl` / `minTouchTarget`), icon `20–24dp`.
- Optional progress line `2dp` (`2×dividerThickness`), Oxide (`playbackActive`), shown only when a position exists.
- Metadata is compact: an eyebrow (state label), track title, artist. No secondary chrome, no card stack.

**Material relationship without copying.** When Petrol, the Hero surface uses the semantic token **`PetrolElevatedSurface #0F2426`** with a **`PetrolBorder #264043`** hairline and an optional **`OxideAccent @ 0.08` radial wash** — the same material gesture the MiniPlayer uses — but it does **not** reuse the MiniPlayer's bespoke `#09191B @ 0.96` capsule glass or its `10dp` artwork radius. Same family, distinct face.

**The seven Hero states.**

1. **Active playback** — eyebrow `NOW PLAYING`; current track + artwork; live `2dp` Oxide progress line; primary action = Pause; tapping the surface opens the full player. (The MiniPlayer carries live transport; the Hero stays single-action.)
2. **Paused** — eyebrow `PAUSED`; progress frozen at position; primary action = Resume (Play).
3. **Resumable previous session** — eyebrow `CONTINUE LISTENING`; last track + position drawn from `PlaybackSessionSnapshot`; primary action = Resume (restores queue, seeks to `lastPositionMs`).
4. **No history / cold start** — eyebrow `START LISTENING`; **no fabricated track**; invitation copy ("Play something to begin"); primary action opens Search or the Trending row; **no progress line**; must not imply playback. Bone-native surface in light mode, Petrol acceptable in dark.
5. **Missing artwork** — `♪` monogram on a tonal placeholder surface (token-based, not the MiniPlayer's `#132B2D`); never a broken image.
6. **Loading playback context** — tonal `128dp` skeleton block, action disabled, no eyebrow claim until resolved.
7. **Offline playback context** — resumable metadata from the cached snapshot is shown, but if the audio is not cached the primary action reflects it ("Reconnect to play") rather than implying instant playback.

The Hero must never visually imply playback when none exists (states 4, 6).

---

## 5. Search Specification

**Placement (Correction 1).** Search is a **compact top-shell affordance**, not a field above the Hero. Home content opens directly on the Console Hero.

**Recommended form.** A search entry in `SonaraTopBar` (trailing), `48dp` touch target, icon `iconMedium 24dp`, tonal/ghost styling. Tapping it navigates to the existing Search destination (or expands into a field), with a container-transform / shared-axis motion so the origin is legible.

**Permitted alternative (secondary only).** If a *visible* field on Home is wanted, it may appear as a slim pill **below** the Hero — height `48dp`, radius `24dp` (matching the existing Search field), surface-tonal. It must remain visually subordinate to the Hero and must never return to the top of the first viewport.

**Copy.** The control says what it does: `Search` (placeholder "Search songs, artists, playlists"), active voice, sentence case. It keeps the same name through the flow.

---

## 6. Discovery Module Strategy

Discovery is **The Tuner**: modules that resolve into view as they acquire real data, and vanish cleanly when they do not. Each module is a horizontal editorial rail (few, large items) under an Editorial-Rooms header — deliberately *not* a dense card grid on phone.

| Module | Source | Seed | Cold-start? | Honest label |
|---|---|---|---|---|
| Trending | `trending` | none | **Yes** | "Trending now" |
| More Like X | `recommendations/similar` · `related` | most-recent/liked track | No (self-hides) | "More like *{track}*" / "Because you played *{track}*" |
| Radio from X | `recommendations/radio/:videoId` | a track | No (self-hides) | "Radio from *{track}*" |
| Curated Playlists | `playlists` | none | **Yes** | "Made for the mood" / playlist names |
| Featured Artists | `artists/featured` (+ adjacency) | none | **Yes** | "Artists to explore" |

**Rules that make the strategy safe.**
- **Self-hiding:** a module with empty/error/offline data renders **zero height**. No placeholders-for-placeholders, no skeleton that never resolves.
- **Seed honesty:** track-seeded modules only appear when a real seed exists; their titles name the seed track, so the recommendation is legible and truthful.
- **No fabrication:** nothing is shown as personalized unless it is. There is no "Recommended For You".
- **Moods folded:** mood discovery is expressed as curated playlists, not a separate pillar.
- **Wiring-independent:** because each module is optional, the set gracefully spans "Search-only" (today) to "fully populated" (post-5C) with no layout redesign.

---

## 7. Section Header System (Editorial Rooms)

No reusable section header exists in the codebase today — this system is **net-new for Phase 5C**. It is deliberately typographic, with no container, card, or decorative divider.

| Part | Type token | Case | Tracking | Color | Notes |
|---|---|---|---|---|---|
| Eyebrow (optional) | `caption` (11sp Medium) | UPPERCASE | +`0.8sp` (widened) | `secondaryText` | Names the "room" (e.g. `DISCOVER`, `FROM YOUR LISTENING`). Omit when redundant. |
| Title | `sectionTitle` (20sp SemiBold, lh 26) | Sentence case | `0sp` | `primaryText` | The room name. |
| Trailing action (optional) | `buttonLabel` (14sp Medium) | Sentence case | `0.1sp` | **Oxide accent** | e.g. "See all". Oxide = action (a verb). Baseline-aligned to title. |

**Spacing.** Top margin from the previous module `space3Xl` (`32dp`); eyebrow-to-title gap `spaceXs` (`4dp`); header-to-content gap `spaceLg` (`16dp`). Start-aligned to the content gutter; trailing action end-aligned.

**Reuse.** The same component serves Home, Search, Library, Artist, Album, and Playlist screens. It is a single composable with `eyebrow?`, `title`, `trailingAction?`.

---

## 8. Artwork System

A deliberately small set of measures. Every value is either an existing pattern or an on-grid derivation — none arbitrary.

| Role | Size | Shape / radius | Source |
|---|---|---|---|
| Hero artwork | `96dp` | rounded square, `12dp` (`shape.medium`) | **Derived** — `2×space5Xl` (`48`); on 4dp grid; ≈2× MiniPlayer artwork (`46`). |
| Medium discovery | `128dp` (normal/large) · `96dp` (compact) | rounded square, `12dp` | **Derived** — `2×space6Xl` (`64`) / `2×space5Xl`. |
| Artist avatar | `96dp` | **circular** (`shape.circular`) | **Derived** — `2×space5Xl`. Same measure as the Hero artwork, different shape ("one instrument, many faces"). |
| Compact track | `44dp` | rounded square, `8dp` (`shape.small`) | **Existing pattern** — matches `LibraryScreen` track artwork (`44dp`). |

**On the apparent hero-vs-discovery inversion.** The Hero artwork (`96`) is smaller than a discovery tile (`128`). This is intentional and not an inconsistency: the Hero's weight comes from its *full-width composed surface* (full width × `128dp`, Petrol, the only Oxide action), not from its artwork square. A `128dp` discovery tile is a single image-forward object in a scrolling rail; the Hero is a panel that contains a `96dp` square plus metadata plus an action. Surface-to-tile, the Hero is far larger; artwork-to-artwork is the wrong comparison.

The banned arbitrary values from the prior spec (`88`, `132`, `116`, `140`) are **not** used anywhere in this system.

---

## 9. Typography

Home introduces **no new type styles**; it maps to the frozen `SonaraTypography` scale (`Typography.kt`).

| Surface element | Style token | Size / weight |
|---|---|---|
| Hero eyebrow (state label) | `caption` (uppercased) | 11sp Medium, +tracking |
| Hero track title | `sectionTitle` | 20sp SemiBold |
| Hero artist | `artistMetadata` | 14sp Normal |
| Hero timing | `playbackTiming` | 12sp Medium |
| Section title | `sectionTitle` | 20sp SemiBold |
| Discovery card title | `cardTitle` | 16sp SemiBold |
| Discovery card subtitle | `secondaryBody` | 13sp Normal |
| Track-row title | `trackTitle` | 16sp Medium |
| Track-row artist | `artistMetadata` | 14sp Normal |
| Trailing action ("See all") | `buttonLabel` | 14sp Medium |

Personality comes from disciplined use of the *existing* ramp — the Hero title and section titles share `sectionTitle` (20sp SemiBold) to bind the Console and the Rooms into one voice — not from novel faces. Type scales with the system font-scale setting (see §20).

---

## 10. Surface / Depth

Editorial calm: hierarchy comes from type, whitespace, scale, and tone — not from shadows or nested cards. There is exactly **one** floating element on the screen, and it is the frozen MiniPlayer.

Depth order (three planes only):

1. **Canvas** — `PetrolCanvas #071A1C` (dark) / `BoneCanvas #F6F2EA` (light). No elevation.
2. **Panels & surfaces** — the Console Hero and any tonal blocks sit one tonal step above the canvas (`PetrolElevatedSurface #0F2426` / `BoneElevatedSurface #ECE6DA`), separated by a single hairline border (`PetrolBorder` / `BoneBorder`). **No drop shadow** — they are inset into the page, which is precisely what distinguishes them from the MiniPlayer.
3. **MiniPlayer** — the only element that floats (its frozen `18dp` shadow, `28dp` capsule).

Discovery tiles are **image-forward**: the artwork *is* the surface. Labels sit on the canvas beneath the tile, with no surrounding card. This is the deliberate defense against "card soup": the screen is a canvas with one embedded panel, image tiles, and one floating capsule — nothing else competes for depth.

---

## 11. Dark Mode

Petrol hierarchy, Bone typography, Oxide as state/action, controlled tonal depth.

| Element | Token | Hex |
|---|---|---|
| Canvas | `PetrolCanvas` | `#071A1C` |
| Hero surface (all states) | `PetrolElevatedSurface` | `#0F2426` |
| Secondary tonal blocks | `PetrolSecondarySurface` | `#152F31` |
| Borders / hairlines | `PetrolBorder` | `#264043` |
| Primary text | `BonePrimaryText` | `#E7E1D6` |
| Secondary text | `BoneSecondaryText` | `#A8A297` |
| Action / active (Oxide) | `OxideAccent` | `#A25A3A` |
| On-Oxide text | `OnAccentBone` | `#F6F2EA` |

Dark mode may retain Petrol on the Hero in **all** states, including cold start — there is no sandwiching problem because the whole canvas is already Petrol.

---

## 12. Light Mode

Light mode is **designed, not inverted**. The test it must pass: it must not read as "dark mode with the background swapped to Bone."

| Element | Token | Hex |
|---|---|---|
| Canvas | `BoneCanvas` | `#F6F2EA` |
| Hero surface — **cold/empty** | `BoneElevatedSurface` | `#ECE6DA` |
| Hero surface — **active/resumable** | `PetrolElevatedSurface` (permitted only here) | `#0F2426` |
| Secondary tonal blocks | `BoneSecondarySurface` | `#E2D8CF` |
| Borders / hairlines | `BoneBorder` | `#D2CCC1` |
| Primary text | `DarkPrimaryText` | `#1B1F1E` |
| Secondary text | `DarkSecondaryText` | `#5B5A53` |
| Action / active (Oxide) | `OxideAccentLight` | `#A25A3A` |
| Secondary / sage relationship | `AccentSoftLight` | `#7C8862` |

**Correction 3 in force.** The frozen MiniPlayer is the *one* permanent dark Petrol anchor in Bone mode. The Hero therefore uses Petrol **only when there is a resumable/active session** (a transient, earned dark moment tied to real playback) and a **Bone-native** surface when cold. This prevents Bone mode from becoming a beige stripe sandwiched between two permanent dark slabs.

**How light mode is genuinely its own design, not an inversion:** the sage/olive `AccentSoftLight #7C8862` appears as a secondary/eligibility accent that has *no* counterpart in dark mode; the Hero's surface *changes role by state* (Bone when cold, Petrol only when live) rather than merely swapping hex values; and Oxide stays the single action color across both themes so "action" reads identically. The result is two designed themes that share a grammar, not one theme recolored.

---

## 13. Cold Start

The previous cold start was an elegant empty state. This one is useful — and honest about two horizons.

**Design target (after 5C wiring).** With Trending, Curated Playlists, and Featured Artists wired (all seed-free), a brand-new user with zero history sees a populated, alive screen:
- **Hero** in its `START LISTENING` state — an invitation, Bone surface in light / Petrol in dark, no fabricated track, no progress line.
- **Trending now** — real tracks, no personal data required.
- **Made for the mood** — a curated playlist or two.
- **Artists to explore** — featured artists.

No personalized rows, no invented history, no "recommended for you". Track-seeded modules stay hidden until the user plays something.

**Current reality (today: only Search wired).** Until 5C wires the discovery endpoints, cold start honestly offers the `START LISTENING` Hero plus a prominent path into Search. Because every module is self-hiding, the cold start scales from "search-only" to "fully populated" as endpoints come online — **without a redesign**. The design does not pretend the unwired endpoints already feed the client.

**Copy.** The empty Hero is an invitation to act, in the interface's voice: "Start listening" / "Play something to begin" — never "Nothing here yet."

---

## 14. Loading

Per-module, never a single global spinner.

- **Hero** — a tonal `128dp` skeleton block (surface-variant tone); action disabled; no eyebrow claim until the playback context resolves.
- **Discovery rails** — 2–3 tonal rounded rectangles at the module's tile size (`128dp` / `96dp`), with a short label bar beneath.
- **Track lists** — 3–5 tonal rows at `60dp`.
- **Motion** — a slow shimmer *only* if system animations are enabled; under reduced motion the skeleton is a static tonal block (see §21).
- A module that resolves to empty transitions from skeleton to **zero height** (self-hide), not to an empty card.

---

## 15. Offline / Error

Per-module, in the interface's voice; failures explain what happened and what to do, and never apologize or go vague (per the writing discipline in §7/§21).

- **Network-dependent modules** (Trending, recommendations, playlists, artists) collapse to a compact inline state: "Offline — reconnect to load," or on hard error, a one-line retry affordance. A failed module never leaves a hole; it collapses.
- **Hero** — if a session is resumable from the cached `PlaybackSessionSnapshot` but the audio is not cached, the Hero shows the metadata and its action reflects reality ("Reconnect to play"). If nothing is resumable, it falls to the cold `START LISTENING` state.
- **Locally available content** (recently played that is cached) stays usable offline.
- **No global error screen** for partial failure — Home degrades module-by-module so that whatever *can* be shown, is.

Each module formally supports five states: **loading · success · empty · error · offline.** Empty and error both resolve to self-hide or a single inline line; neither breaks layout rhythm.

---

## 16. MiniPlayer Relationship

The MiniPlayer is **frozen**: geometry, typography, controls, artwork, progress system, capsule, and motion are not touched. Verified geometry (from `MiniPlayer.kt`): 3-tier column (progress rail `16dp` → artwork/metadata row `46dp` → transport row `46dp`), content padding `9dp` top/bottom, inter-tier `spacedBy 7dp`, outer margin `3dp` vertical → **~140dp content, ~146dp occupied**; capsule radius `28dp`; artwork `46dp` (normal) at radius `10dp`; bespoke glass `#09191B @ 0.96`.

What this pass defines is the **relationship**, not the component:

- **Docking.** The MiniPlayer occupies the Scaffold `bottomBar` slot (in `SonaraAppRoot.kt`), stacked directly above `SonaraBottomNavBar` within that slot. It is part of the persistent shell chrome, not a per-screen element.
- **Structural bottom inset (replaces the 80dp magic spacer).** Because the MiniPlayer and bottom nav live in `bottomBar`, the Scaffold's `innerPadding.calculateBottomPadding()` **already includes their combined height plus system nav insets**. Home must consume that `innerPadding` as `LazyColumn` content padding. The `80dp` spacer in the current placeholder Home is therefore redundant and is removed in 5C; the only additive is an optional `spaceLg` (`16dp`) of breathing room as the last content gap — a token, not a magic number.
- **Scroll termination.** The last Home item scrolls to rest exactly above the MiniPlayer capsule; no content is ever hidden behind it, and there is no dead gap.
- **Distinction preserved.** The Hero (embedded, `128dp`, single action, no float) and the MiniPlayer (floating, ~`146dp`, full transport) never visually merge, even though the Hero scrolls up toward the docked MiniPlayer.

---

## 17. Navigation Relationship

- **Phone (< 600dp).** `SonaraBottomNavBar` (frozen destinations) sits at the very bottom; the MiniPlayer floats directly above it — both in the Scaffold `bottomBar`. Home is one destination among the frozen set. The MiniPlayer persists across destinations because it lives in the shell, not in Home.
- **Tablet (≥ 600dp) — proposed primary adaptive direction.** Replace the bottom nav with a **`NavigationRail`** on the leading edge. **Implementation implication (for 5C, not now):** the shell's `bottomBar` must gain an adaptive branch keyed on the existing `600dp` breakpoint — bottom nav below `600`, rail at/above — and the MiniPlayer's docking must follow (it remains a bottom-docked bar spanning the content region, above where the bottom nav used to be). This is a shell change, and it is the reason the rail is *proposed* here but only *frozen as a direction*, not as finished geometry.
- **Right-side Now Playing / Queue panel** — **optional future exploration only. Not frozen.** It is deferred until the phone Home and the broader tablet product architecture justify it.

---

## 18. Responsive Layouts

Aligned to the **actual Sonara breakpoints** used by the shell/MiniPlayer (`360` / `412` / `600`), not to invented `WindowSizeClass` names. Each class specifies a **structural** change, not merely scaled dp. (A shared breakpoint token is a 5C cleanup note — today the constants are MiniPlayer-local.)

**1. Compact phone (< 360dp).** Single column. Gutter `spaceMd` (`12dp`). Hero `128dp` (artwork `96`). Discovery tiles drop to `96dp`; ~2.5 visible per rail. Track rows `44dp` artwork. Eyebrows may be suppressed to save vertical space. Nothing wraps or clips.

**2. Normal phone (360–411dp) — primary target.** Single column. Gutter `spaceLg` (`16dp`). Discovery tiles `128dp` (~2.5 visible). Hero `128dp`. This is the reference composition for all wireframes unless noted.

**3. Large phone (412–599dp).** Single column, wider gutter `space2Xl` (`24dp`). Discovery tiles `128dp` with ~3 visible and more horizontal peek. Hero keeps `128dp` minimum; extra width goes to the metadata column, not to enlarging the artwork.

**4. Landscape phone.** *Structural change:* the Hero becomes **horizontal** — `96dp` artwork on the leading edge, metadata + action trailing — to reclaim vertical space (footprint stays ~`128dp` tall but uses width). Content region gets a max line-length cap so metadata doesn't stretch. Above the fold prioritizes Hero + one discovery rail; the rest scrolls. MiniPlayer stays bottom.

**5. Tablet (≥ 600dp).** *Structural change, not an enlarged phone:* `NavigationRail` leading; content in a centered, max-width canvas; discovery becomes **multi-column grids** rather than single-height h-scroll rails; the Hero spans the content width with `96dp` (or `128dp`) artwork leading and a richer metadata block. Optional right context panel is *not* frozen (§17).

---

## 19. Tablet Strategy

The previous tablet proposal (a centered, widened phone column) was too conservative and is rejected. The frozen *direction* (geometry to be finalized in 5C):

- **Navigation:** `NavigationRail` on the leading edge replaces the bottom nav at `≥600dp`.
- **Discovery:** multi-column grids (e.g. 2–3 columns of `128dp` tiles) instead of single h-scroll rails, so the extra width becomes *more content*, not more whitespace.
- **Hero:** a wide, horizontal composition — artwork leading, metadata and the single Oxide action trailing — spanning the content column.
- **Composition:** stronger editorial rhythm with multiple content regions; the canvas is centered with a max width so line lengths stay readable on large panels.
- **Right Now-Playing / Queue panel:** explored but **explicitly not frozen** — a candidate for a later phase once the phone Home ships and tablet usage justifies the added surface and maintenance.

**Why not freeze the panel now:** the current navigation and MiniPlayer are bottom-bar-based; a persistent right panel implies a second playback surface competing with the frozen MiniPlayer, which is a product decision beyond this pass. The rail + grid direction is safe to commit; the panel is not.

---

## 20. Accessibility

Visual dimensions and interaction dimensions are kept separate throughout.

- **Touch targets ≥ 48dp.** The Hero action is `48dp`. The search affordance is `48dp`. "See all" and any visually-smaller control (e.g. a `34dp` glyph) carries an invisible `48dp` touch area via padding. Discovery tiles are ≥ `96dp` and inherently large enough.
- **Scalable text.** All type is `sp` and honors the system font-scale. Hero and section headers must reflow (grow height / wrap) under large font scales — no fixed-height text container may clip. This is a first-class layout constraint, not an afterthought.
- **TalkBack semantics.** The Hero exposes a single, well-named action ("Resume *{track}* by *{artist}*"). Section headers carry heading semantics. Discovery tiles have meaningful `contentDescription` (title + type). Decorative artwork washes/gradients are marked decorative and skipped by the reader.
- **Contrast.** Body/secondary text on canvas meets AA in both themes (verified pairings: `#E7E1D6`/`#A8A297` on `#071A1C`; `#1B1F1E`/`#5B5A53` on `#F6F2EA`). The Oxide action button must pair with `OnAccentBone #F6F2EA` / `OnAccentWhite` and use a SemiBold/large label; the exact Oxide-on-Petrol and Oxide-on-Bone text ratios must be **verified against AA in 5C** and the label upsized if it lands below `4.5:1` for normal text (UI-component contrast `3:1` applies to the button shape itself).
- **Reduced motion.** Reuse the existing detection in `SonaraAppRoot.kt` (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`); every animation below has a defined reduced-motion fallback.

---

## 21. Motion

Only meaningful motion. No decorative bouncing, no continuous parallax, no ambient loops.

| Motion | Trigger | Duration | Easing | Reduced-motion |
|---|---|---|---|---|
| Hero state transition | playback state changes (cold↔resumable↔active↔paused) | 200ms | `FastOutSlowIn` | instant swap |
| Search → Search screen | tap search affordance | 300ms | shared-axis / container transform | simple fade |
| Module reveal | a module's data resolves | 150ms fade-in | `LinearOutSlowIn` | appear directly, no fade |
| Oxide action press | press on the Hero action | 100ms | scale to `0.96` + M3 ripple | ripple only |

The Theme Toggle's circular-reveal animation (in `SonaraAppRoot.kt`) is **independent and frozen** and is not modified or extended by this spec.

---

## 22. M3 Strategy

Material 3 (`androidx.compose.material3`) remains the **engineering foundation**, not the **visual authority**. Per component:

| Component | Classification | Rationale |
|---|---|---|
| `Scaffold` / `innerPadding` | **M3 INFRASTRUCTURE ONLY** | Layout scaffolding + structural insets; no visual imprint. |
| `SonaraBottomNavBar` | **WRAP M3** (frozen) | M3 semantics/a11y, Sonara styling. |
| `NavigationRail` (tablet) | **WRAP M3** | M3 rail for semantics; restyled to Sonara. |
| Console Hero | **CUSTOM** | Bespoke `Surface` + layout; not a `Card`. M3 `Surface` used for shape/elevation semantics only. |
| Search affordance | **WRAP M3** | `IconButton` semantics; reject M3 `SearchBar` default look. |
| Section header | **CUSTOM** | `Text` + optional `TextButton`; no M3 header component. |
| Discovery tile | **CUSTOM** | `Surface` + image + label; not an M3 `Card`. |
| Track row | **WRAP M3** | Optional `ListItem` semantics; Sonara styling; matches Library. |
| Oxide action button | **WRAP M3** | M3 `Button` for ripple/semantics/touch target; restyled to Oxide. |
| Ripple / indication / touch target / semantics | **DIRECT M3** | Accessibility infrastructure — used as-is. |
| Skeleton / shimmer | **CUSTOM** | No M3 equivalent. |

Rule: **REJECT M3** visual defaults (Card elevations, FilledTonal color roles, default SearchBar chrome). Adopt M3 only for accessibility, semantics, interaction states, ripple, touch targets, and adaptive scaffolding.

---

## 23. Final Geometry Table

Every dimension carries a mandatory **Source**, classified as: **TOKEN** (a named value in `Dimensions.kt`/`Shape.kt`), **DERIVED** (on-grid composite of tokens), **EXISTING PATTERN** (matches shipped component geometry), **FROZEN** (from a frozen component), or **NEW-JUSTIFIED**.

| Element | Size | Padding | Radius | Gap | Source |
|---|---|---|---|---|---|
| **Console Hero** | height `128dp` min | `16dp` all sides (`spaceLg`) | — | — | **DERIVED** — `96` artwork + `2×spaceLg` = `2×space6Xl` |
| **Hero artwork** | `96dp` | — | `12dp` (`shape.medium`) | — | **DERIVED** — `2×space5Xl`; ≈2× MiniPlayer `46dp` |
| **Hero primary action** | `48dp` | — | pill / `24dp` | — | **TOKEN** — `space5Xl` / `minTouchTarget` |
| **Hero progress line** | `2dp` tall | — | — | — | **DERIVED** — `2×dividerThickness` (`1dp`) |
| **Hero eyebrow → title gap** | — | — | — | `4dp` | **TOKEN** — `spaceXs` |
| **Search affordance** | `48dp` target | — | `24dp` (matches Search field) | — | **TOKEN** target + **EXISTING PATTERN** radius |
| **SectionHeader title** | 20sp | top `32dp`, to-content `16dp` | — | eyebrow `4dp` | **TOKEN** — `sectionTitle`; `space3Xl` / `spaceLg` / `spaceXs` |
| **Medium discovery artwork** | `128dp` (`96dp` compact) | — | `12dp` (`shape.medium`) | inter-tile `12dp` (`spaceMd`) | **DERIVED** — `2×space6Xl` / `2×space5Xl` |
| **Artist avatar** | `96dp` | — | circular | — | **DERIVED** — `2×space5Xl`, `shape.circular` |
| **Compact track artwork** | `44dp` | row `8dp` V (`spaceSm`) | `8dp` (`shape.small`) | — | **EXISTING PATTERN** — `LibraryScreen` |
| **Track row** | `60dp` height | — | — | — | **DERIVED** — `44` + `2×spaceSm` |
| **Content gutter** | `12 / 16 / 24dp` | — | — | — | **TOKEN** — `spaceMd` / `spaceLg` / `space2Xl` by breakpoint |
| **Inter-module gap** | `32dp` | — | — | — | **TOKEN** — `space3Xl` |
| **Bottom inset** | = Scaffold `innerPadding` bottom | +`16dp` optional | — | — | **FROZEN + TOKEN** — MiniPlayer+nav in `bottomBar`; `spaceLg` breathing |
| **MiniPlayer (ref)** | ~`140–146dp` occupied | `9dp` V content, `3dp` V outer | `28dp` capsule | tiers `7dp` | **FROZEN** — `MiniPlayer.kt` |
| **Breakpoints** | `360 / 412 / 600dp` | — | — | — | **EXISTING PATTERN** — shell/MiniPlayer constants |

No dimension is introduced silently. None of the banned arbitrary artwork values (`88 / 132 / 116dp`, or `140dp` used *as an artwork size*) appear in this system. The only `~140dp` in this document is the **measured, frozen MiniPlayer height** cited in §16 — a fact read from `MiniPlayer.kt`, not an introduced value.

---

## 24. Final Wireframes

Legend: `▓` artwork/filled · `░` skeleton/tonal · `[ ]` control · `▶` play/resume · `≡` nav · box = surface with hairline. Reference width = normal phone (360–411dp) unless noted.

### 24.1 — DARK · NORMAL PHONE (resumable session)
```
┌───────────────────────────────────────────┐
│  SONARA                        ☾   ⌕        │  top shell: wordmark · toggle · search(48)
│                                             │
│  ┌───────────────────────────────────────┐ │
│  │ CONTINUE LISTENING                     │ │  Hero — Petrol #0F2426, hairline
│  │ ┌──────┐  Midnight Drive               │ │  eyebrow=caption/upper
│  │ │ ▓▓▓▓ │  The Weeknd            ( ▶ )  │ │  title=sectionTitle 20sp
│  │ │ ▓96▓ │  ──────────────·······        │ │  96dp art · 48dp Oxide action
│  │ └──────┘  1:24            2dp Oxide     │ │  128dp tall · no float shadow
│  └───────────────────────────────────────┘ │
│                                             │
│  TRENDING NOW                               │  SectionHeader (eyebrow+title)
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ▓▓128▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │   →      │  128dp image-forward tiles
│  └────────┘ └────────┘ └────────┘          │
│  Title · Artist   Title      Title          │
│                                             │
│  MORE LIKE “MIDNIGHT DRIVE”         See all │  track-seeded · honest label
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │   →      │
│  └────────┘ └────────┘ └────────┘          │
│                                             │
│  MADE FOR THE MOOD                          │  curated playlists (absorbs moods)
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │   →      │
│  └────────┘ └────────┘ └────────┘          │
│                          ⋮ (scrolls: Artists, From Your Listening)
│═════════════════════════════════════════════│
│  ┌───────────────────────────────────────┐ │  ── MiniPlayer (FROZEN, floats) ──
│  │ ▁▁▁▁▁▁▁▁ liquid rail ▁▁▁▁▁▁▁▁▁▁        │ │  ~146dp, 28dp capsule, 18dp shadow
│  │ ▓  Midnight Drive        ⏮  ▮▮  ⏭     │ │
│  └───────────────────────────────────────┘ │
│   ≡ Home    ⌕ Search    ♪ Library           │  SonaraBottomNavBar
└───────────────────────────────────────────┘
```

### 24.2 — LIGHT · NORMAL PHONE (resumable session)
```
┌───────────────────────────────────────────┐
│  SONARA                        ☀   ⌕        │  Bone canvas #F6F2EA
│  ┌───────────────────────────────────────┐ │
│  │ CONTINUE LISTENING                     │ │  Hero Petrol #0F2426 — permitted
│  │ ┌──────┐  Midnight Drive               │ │  ONLY because a session is resumable
│  │ │ ▓96▓ │  The Weeknd            ( ▶ )  │ │  (transient dark, earned by playback)
│  │ └──────┘  1:24  ──────·······          │ │
│  └───────────────────────────────────────┘ │
│  TRENDING NOW                               │  tiles + labels on Bone
│  ┌────────┐ ┌────────┐ ┌────────┐          │  text = #1B1F1E / #5B5A53
│  │ ▓▓128▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │   →      │  sage #7C8862 = secondary accent
│  └────────┘ └────────┘ └────────┘          │
│  MADE FOR THE MOOD                  See all │
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │   →      │
│  └────────┘ └────────┘ └────────┘          │
│═════════════════════════════════════════════│
│  ┌───────────────────────────────────────┐ │  MiniPlayer stays Petrol (the ONE
│  │ ▓  Midnight Drive        ⏮  ▮▮  ⏭     │ │  permanent dark anchor in Bone)
│  └───────────────────────────────────────┘ │
│   ≡ Home    ⌕ Search    ♪ Library           │
└───────────────────────────────────────────┘
   Note: exactly ONE permanent dark slab (MiniPlayer). Hero is Bone when cold.
```

### 24.3 — DARK · COMPACT PHONE (< 360dp)
```
┌─────────────────────────────────┐
│ SONARA               ☾   ⌕       │  gutter 12dp, eyebrows suppressed
│ ┌─────────────────────────────┐ │
│ │ ┌────┐ Midnight Drive        │ │  Hero 128dp, art 96dp
│ │ │▓96▓│ The Weeknd      ( ▶ ) │ │
│ │ └────┘ 1:24 ────·····         │ │
│ └─────────────────────────────┘ │
│ Trending now                     │  tiles drop to 96dp
│ ┌──────┐ ┌──────┐ ┌──────       │  ~2.5 visible
│ │ ▓96▓ │ │ ▓▓▓▓ │ │ ▓▓▓        │
│ └──────┘ └──────┘ └───          │
│ More like “Midnight Drive”       │
│ ┌──────┐ ┌──────┐ ┌──────       │
│ │ ▓▓▓▓ │ │ ▓▓▓▓ │ │ ▓▓▓        │
│ └──────┘ └──────┘ └───          │
│════════════════════════════════ │
│ ┌─────────────────────────────┐ │  MiniPlayer (compact quint: art 40)
│ │ ▓ Midnight Drive   ⏮ ▮▮ ⏭   │ │
│ └─────────────────────────────┘ │
│  ≡ Home   ⌕ Search   ♪ Library   │
└─────────────────────────────────┘
```

### 24.4 — LIGHT · COMPACT PHONE (< 360dp) — cold start
```
┌─────────────────────────────────┐
│ SONARA               ☀   ⌕       │
│ ┌─────────────────────────────┐ │
│ │  START LISTENING             │ │  Hero = Bone surface #ECE6DA
│ │  Play something to begin     │ │  (NOT Petrol — nothing to resume)
│ │                    ( Browse )│ │  no artwork claim, no progress line
│ └─────────────────────────────┘ │
│ Trending now                     │  seed-free → populated at cold start
│ ┌──────┐ ┌──────┐ ┌──────       │
│ │ ▓96▓ │ │ ▓▓▓▓ │ │ ▓▓▓        │
│ └──────┘ └──────┘ └───          │
│ Artists to explore               │  featured artists (circular 96)
│ ( ● ) ( ● ) ( ● ) ( ●            │
│  Name  Name  Name                │
│════════════════════════════════ │
│  (no MiniPlayer — nothing has played yet)
│  ≡ Home   ⌕ Search   ♪ Library   │
└─────────────────────────────────┘
   Cold start is POPULATED (Trending + Artists), not an empty state.
```

### 24.5 — TABLET (≥ 600dp) — rail + multi-column grid
```
┌──────┬──────────────────────────────────────────────────┐
│      │  SONARA                                   ⌕        │
│  ≡   │  ┌────────────────────────────────────────────┐  │
│ Home │  │ CONTINUE LISTENING                          │  │  wide horizontal Hero
│      │  │ ┌──────┐ Midnight Drive — The Weeknd        │  │  art leading, action trailing
│  ⌕   │  │ │ ▓96▓ │ 1:24 ───────·······        ( ▶ )  │  │
│ Srch │  │ └──────┘                                    │  │
│      │  └────────────────────────────────────────────┘  │
│  ♪   │  TRENDING NOW                                     │
│ Libr │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐    │  multi-column GRID
│      │  │ ▓▓128▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │    │  (not single h-scroll)
│ Nav  │  └────────┘ └────────┘ └────────┘ └────────┘    │
│ Rail │  MADE FOR THE MOOD                               │
│      │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐    │
│ (WRAP│  │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │    │
│  M3) │  └────────┘ └────────┘ └────────┘ └────────┘    │
│      │  ┌────────────────────────────────────────────┐  │
│      │  │ ▓ Midnight Drive          ⏮  ▮▮  ⏭         │  │  MiniPlayer spans content
│      │  └────────────────────────────────────────────┘  │
└──────┴──────────────────────────────────────────────────┘
   [ Optional right Now-Playing/Queue panel = FUTURE, NOT FROZEN ]
```

### 24.6 — COLD START (design target, normal phone, dark)
```
┌───────────────────────────────────────────┐
│  SONARA                        ☾   ⌕        │
│  ┌───────────────────────────────────────┐ │
│  │ START LISTENING                        │ │  Hero invitation (Petrol OK in dark)
│  │ Play something to begin        (Browse)│ │  no fabricated track / progress
│  └───────────────────────────────────────┘ │
│  TRENDING NOW                               │  seed-free, no personal data
│  ┌────────┐ ┌────────┐ ┌────────┐   →      │
│  │ ▓▓128▓ │ │ ▓▓▓▓▓▓ │ │ ▓▓▓▓▓▓ │          │
│  └────────┘ └────────┘ └────────┘          │
│  MADE FOR THE MOOD                          │  curated playlists
│  ┌────────┐ ┌────────┐ ┌────────┐   →      │
│  └────────┘ └────────┘ └────────┘          │
│  ARTISTS TO EXPLORE                         │  featured artists (circular)
│  ( ●96 )  ( ● )  ( ● )  ( ●          →      │
│  (no “More like” / “Radio” — no seed track yet: self-hidden)
│═════════════════════════════════════════════│
│  (no MiniPlayer until something plays)      │
│   ≡ Home    ⌕ Search    ♪ Library           │
└───────────────────────────────────────────┘
   Today (only Search wired): Hero + prominent Search; rows appear as 5C wires them.
```

### 24.7 — LOADING (per-module skeletons)
```
┌───────────────────────────────────────────┐
│  SONARA                        ☾   ⌕        │
│  ┌───────────────────────────────────────┐ │
│  │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   │ │  Hero skeleton (tonal 128dp block)
│  │ ░░░░░░  ░░░░░░░░░░░░░░░           ░░    │ │  action disabled, no eyebrow claim
│  └───────────────────────────────────────┘ │
│  ░░░░░░░░░░                                 │  section-title bar skeleton
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ░░128░ │ │ ░░░░░░ │ │ ░░░░░░ │          │  tonal tiles
│  └────────┘ └────────┘ └────────┘          │
│  ░░░░░  ░░░       ░░░░░                     │
│  ░░░░░░░░░░                                 │
│  ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ ░░░░░░ │ │ ░░░░░░ │ │ ░░░░░░ │          │
│  └────────┘ └────────┘ └────────┘          │
│  (shimmer only if animations enabled; static tonal under reduced motion)
│  A module that resolves EMPTY → collapses to 0 height (no empty card).
└───────────────────────────────────────────┘
```

### 24.8 — OFFLINE / PARTIAL FAILURE
```
┌───────────────────────────────────────────┐
│  SONARA                        ☾   ⌕        │
│  ┌───────────────────────────────────────┐ │
│  │ CONTINUE LISTENING                     │ │  cached snapshot → metadata shown
│  │ ┌────┐ Midnight Drive                  │ │
│  │ │▓96▓│ The Weeknd     ( Reconnect ▷ )  │ │  audio not cached → honest action
│  │ └────┘                                 │ │
│  └───────────────────────────────────────┘ │
│  TRENDING NOW                               │
│  ┌───────────────────────────────────────┐ │
│  │  Offline — reconnect to load           │ │  network module → compact inline
│  └───────────────────────────────────────┘ │  (not a broken/empty card)
│                                             │
│  FROM YOUR LISTENING                        │  cached history stays usable
│  ┌──┐ Nightcall              ▷             │
│  │▓ │ Kavinsky                              │
│  └──┘                                       │
│  ( “More like” / “Radio” / “Artists” self-hide while offline — no holes )
│═════════════════════════════════════════════│
│  ┌───────────────────────────────────────┐ │
│  │ ▓ Midnight Drive        ⏮  ▮▮  ⏭      │ │  MiniPlayer per its frozen offline rules
│  └───────────────────────────────────────┘ │
│   ≡ Home    ⌕ Search    ♪ Library           │
└───────────────────────────────────────────┘
```

---

## 25. Frozen Design Invariants

The following are now FROZEN for Phase 5B:

1. **Home hierarchy / IA** — Console Hero → Trending → Because-You-Played-X/More-Like → Curated Playlists → Featured Artists → From Your Listening. Discovery precedes the personal-library block; the conventional `Resume → Recently Played → Your Music` template is rejected.
2. **Console Hero role** — re-entry into listening; anchors but does not dominate by default; prominence is conditional on a resumable/active state.
3. **Search placement** — compact top-shell affordance; never a large field in the first viewport; any visible field is secondary and below the Hero.
4. **Hero artwork size** — `96dp` (derived `2×space5Xl`); Hero height `128dp` minimum.
5. **Light/Dark Hero behavior** — Petrol only when resumable/active; Bone-native when cold in light mode; the MiniPlayer is the sole permanent dark anchor in Bone.
6. **Oxide usage** — a verb: playback/active/action/selected/current only; never decorative branding.
7. **Artwork scale system** — `{44 (compact) · 96 (hero/artist) · 128 (medium discovery)}`; no `88/132/116/140`.
8. **Section-header system** — typographic Editorial Rooms header (eyebrow + `sectionTitle` + optional Oxide trailing action), no container; reusable across screens.
9. **MiniPlayer relationship** — frozen component; structural bottom inset via Scaffold `innerPadding`; the `80dp` magic spacer is retired; Hero and MiniPlayer never visually merge.
10. **Navigation relationship** — bottom nav + floating MiniPlayer on phone; `NavigationRail` is the proposed adaptive direction at `≥600dp`.
11. **Responsive strategy** — Sonara breakpoints `360/412/600`; structural changes (horizontal Hero in landscape, rail + grid on tablet), not scaled dp.
12. **Cold-start strategy** — populated via seed-free capability (Trending/Playlists/Featured Artists); invitation Hero; no fabrication; self-hiding scales from "Search-only today" to "fully populated post-5C".
13. **Future discovery-module strategy** — every module independently optional and self-hiding across loading/success/empty/error/offline; recommendation labels are contextual and truthful; no "Recommended For You"; no standalone "Moods".
14. **M3 posture** — foundation, not visual authority; per-component classification in §22 governs.

Not frozen (explicitly open for later): the tablet **right Now-Playing/Queue panel**; the final rail geometry; a shared breakpoint token.

---

## 26. Phase 5C Implementation Sequence

*What* will be implemented, in order. No implementation is written here.

1. **Wire discovery endpoints into the client.** Extend `SonaraBackendClient` + repositories with sanitized DTOs for `trending`, `recommendations/{related,similar,radio}`, `playlists`, `artists/featured` — preserving the security boundary (no server secrets in Android; stream domain-allowlist unchanged; Android talks only to `:3002`). Foundational: until this lands, discovery modules render as self-hidden.
2. **Build the reusable `SectionHeader`** (net-new) per §7.
3. **Build the Console Hero** composable and its seven states (§4), consuming `PlaybackSessionSnapshot` for resume.
4. **Build the discovery modules** (Trending, More-Like/Because-You-Played-X, Radio-from-X, Curated Playlists, Featured Artists) — each independently optional with loading/success/empty/error/offline.
5. **Rebuild `HomeScreen`** to consume Scaffold `innerPadding` (remove the `80dp` spacer), compose Hero + modules in the frozen IA, all self-hiding.
6. **Add the search affordance** to `SonaraTopBar` (compact), wired to Search with container-transform motion.
7. **Adaptive navigation:** introduce breakpoint-driven `NavigationRail` at `≥600dp` in the shell; keep bottom nav below `600`; follow the MiniPlayer docking implication (§17).
8. **Tablet composition:** multi-column discovery grids + wide horizontal Hero. (Right context panel remains out of scope / not frozen.)
9. **Accessibility + motion pass:** semantics, `≥48dp` targets, reduced-motion via the existing `ANIMATOR_DURATION_SCALE` detection, and the AA contrast verification flagged in §20.
10. **Verification:** screenshot/instrumented review across the five responsive classes (§18) and the eight wireframe states (§24), confirming self-hide behavior leaves no layout holes.

**Sequencing note:** Trending / Playlists / Featured Artists are seed-free — the cheapest, highest-value first wins (they light up cold start immediately). Related / Similar / Radio depend on a local seed track (`PlaybackSessionSnapshot` / history) and should follow.

---

*End of Phase 5B — Final Home Screen Visual Specification. Design freeze complete. No code, UI, or production files were modified in producing this document.*
