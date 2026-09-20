# PHASE 5A — HOME SCREEN FORENSIC + CREATIVE DESIGN EXPLORATION

> **Status:** DESIGN / FORENSIC / EXPLORATION PASS ONLY. No code was modified to produce this document. No production UI was written. Nothing here is a product commitment — several concepts imply **new** backend-facing client endpoints, repositories, domain-model fields, and components that do **not** exist today. Those gaps are called out explicitly so that design is never mistaken for capability.
>
> **Frozen and untouched by this pass:** `MiniPlayer.kt`, the dual-layer circular-reveal Theme Toggle (1200ms), and the Petrol / Bone / Oxide design language. This document treats all three as fixed reference points, not variables.
>
> **Scope of authority:** Material 3 is the *engineering foundation* (accessibility, semantics, touch targets, adaptive infra) — **not** the visual authority. Every recommendation below inherits M3's plumbing while rejecting its default look.

---

## PART 0 — HOW TO READ THIS DOCUMENT

Three parts:

1. **Forensic pass** — what Home is *today*, what the design system and backend actually give us, and a five-bucket verdict (PRESERVE / EVOLVE / REMOVE / REPLACE / MISSING).
2. **Creative exploration** — three *structurally different* Home concepts (not palette or card-shape variants), each covering all 16 required dimensions.
3. **Decision** — a nine-dimension comparison matrix, a single recommendation (chosen for fit, **not** for safety), the three visual principles that should govern the rest of the app, and a non-binding implementation blueprint.

A running tension shapes everything: **the brief says "design around real backend capabilities," but the Android *client* today only wires three of them.** The server can do discovery, recommendations, radio, similar/related, artist adjacency, curated playlists, and mood/category — but `SonaraBackendClient` exposes only `search`, `stream/resolve`, and `stream/play`, and `getHomeCatalog()` returns three hardcoded seed tracks. So the honest design question is not "what can the backend do" but "**what can Home do *now* with integrity, and what does each future section cost in client wiring**." Every concept is scored on both.

---

## PART 1 — FORENSIC ANALYSIS

### 1.1 The Home screen as it exists

`feature/home/HomeScreen.kt` is a thin placeholder, not a designed surface:

- A `LazyColumn` with `spaceLg` (16dp) horizontal padding.
- A greeting block: **"Welcome to Sonara"** (`screenTitle`) + **"Calm, focused, local-first music listening."** (`secondaryBody`).
- **"Quick Play Catalog"** — renders `sampleTracks` through `TrackRowItem`.
- **"Recently Played"** — `history.take(5)` through `TrackRowItem`, or a `SonaraCard` + `SonaraEmptyState` when empty.
- An **80dp bottom spacer**, a magic number reserving room for the docked MiniPlayer.

`HomeViewModel` combines `catalogRepository.getHomeCatalog()` (→ `quickPicks`) with `historyRepository.getRecentHistory(limit = 10)` (→ `history`) into `HomeUiState.Content(quickPicks, history)`, with `Loading` / `Error` siblings. Clean UDF, but only **two** content channels, and one of them is stubbed.

**Verdict at a glance:** Home is scaffolding. The design system and the MiniPlayer are mature; Home has not yet been designed. That is the opportunity.

### 1.2 Design tokens — the real visual vocabulary

| Layer | Reality | Design consequence |
|---|---|---|
| **Color** | `SonaraColors` semantic roles feed M3 via `toMaterialColorScheme()`. Dark = **Petrol** (bg `#071A1C` → surfaceVariant `#152F31`), Light = **Bone** (bg `#F6F2EA` → surfaceVariant `#E2D8CF`). Accent = **Oxide** `#A25A3A` in both. | Hierarchy is built from **tonal steps**, not elevation. Three surface tiers per theme is the real "card" system. |
| **accentSoft asymmetry** | Dark `#7C4A2F` (oxide-brown) vs Light `#7C8862` (**sage/olive**). | The light theme is *not* a tint-flip of dark. Any Home surface that uses accentSoft must be theme-aware, not mirrored. |
| **Typography** | `display` 32 / `screenTitle` 26 / `sectionTitle` 20 / `cardTitle` 16 / `trackTitle` 16 / `artistMetadata` 14 / `body` 15 / `secondaryBody` 13 / `caption` 11. Negative letter-spacing on display & screenTitle. | An **editorial** register already exists in the type scale. The tight display sizes are underused — Home currently uses only screenTitle/secondaryBody. |
| **Spacing** | 4dp base: Xs4 … 6Xl64; `minTouchTarget` 48; divider 1dp. | Rhythm exists but Home only uses `spaceLg`. Section separation (24–32dp) is available and unused. |
| **Shape** | small 8 / medium 12 / large 16 / sheet 24-top / circular. Explicit rule: *not everything is a pill.* | Radii are restrained by design. Any "pill everything" or "card everything" Home would violate the system. |

### 1.3 Component inventory

**Built and reusable:** `SonaraButton` (Primary/Secondary/Text), `SonaraIconButton` (Standard/Filled/Accent/Ghost), `SonaraSurface` (Canvas/Elevated/Secondary tonal), `SonaraCard` (flat, 0 elevation, tonal fill + 1dp divider border, 12dp radius), `SonaraDivider`, `SonaraEmptyState`, `SonaraErrorBanner`, `SonaraLoadingIndicator`, `LucideIcons` (**only** Sun & Moon).

**De-facto shared but mis-located:** `TrackRowItem` lives inside `LibraryScreen.kt` yet is imported by Home and Search — a 44dp artwork (6dp radius) + `trackTitle` + "artist • album" (`artistMetadata`) + trailing ♥/♡ `SonaraIconButton`. It is the app's most-used row and it lives in the wrong place.

**Declared in the design bible (§8) but NOT built:** `SonaraChip`, `SonaraSearchField`, formalized `TrackRow` / `CompactTrackRow`, `Artwork`, `ArtistChip`, `AlbumCard`, `PlaylistCard`, `SectionHeader`, plus skeleton/shimmer loaders. The bible already sanctions these — they are missing implementations, not new inventions.

### 1.4 Navigation & shell

Manual navigation: a `when(currentDestination)` switch over `mutableStateOf` in `SonaraAppRoot` — **no Jetpack NavHost, no back stack, no saved-state**. `SonaraBottomNavBar` is a *custom* bar (not M3 `NavigationBar`): glyph icons (⌂ ⚲ ☵) + `navigationLabel`, oxide when selected. `SonaraTopBar` = "Sonara" wordmark (display/oxide) + section caption + the frozen theme toggle. Design bible §21 already anticipates `feature/navigation/SonaraNavGraph.kt` + `Screen.kt`.

### 1.5 The MiniPlayer as the identity anchor (frozen, but instructive)

The MiniPlayer is the most finished, most *Sonara* surface in the app, and it teaches the design language Home should echo:

- A **petrol-glass capsule** (`#09191B` @ 0.96) that stays dark **even in light mode** — a deliberate continuity signature.
- Specular 1dp border, 18dp ambient shadow, a faint oxide radial wash (@0.08).
- A **three-tier optical rhythm**: liquid segmented progress rail → artwork + metadata → transport (shuffle · prev · **oxide PLAY 46dp** · next · repeat).
- The **LiquidSegmentedProgressRail** (oxide `#B85D38` ticks, liquid fill) — a genuinely original component.
- Responsive **Quints** at 360 / 412 / 600 breakpoints; oxide reserved strictly for the active/play state.

**This is the single richest source of Sonara-specific DNA in the codebase.** A Home that ignores it will feel like a different app bolted above the MiniPlayer.

### 1.6 Backend capability vs. Android client reality

| Capability (server can do) | Reachable from Android *today*? | If not, what's the cost |
|---|---|---|
| Search (quality engine) | **Yes** — `/api/v1/search` | — |
| Stream resolve + play | **Yes** — `/api/v1/stream/{resolve,play}` | — |
| Local history | **Yes** — Room via `HistoryRepository` | — |
| Liked songs + track cache | **Yes** — Room via `LibraryRepository` | — |
| Resume / continue session | **Yes (data)** — `PlaybackSessionSnapshot` (lastTrackId, lastPositionMs, queueTrackIds) exists | No UI binds it yet |
| Now-playing projection | **Yes** — `PlayerUiState` (full) | — |
| Discovery / home feed | **No** | New endpoint + repo; `getHomeCatalog()` is stubbed |
| Recommendations (ListenBrainz) | **No** | New endpoint + `RecommendationRepository` |
| Radio / start-a-station | **No** | New endpoint + `RadioRepository` |
| Related / similar tracks | **No** | New endpoint |
| Artist discovery / adjacency | **No** | New endpoint + `Artist` model |
| Curated playlists | **No** | New endpoint + `Playlist` model |
| Mood / category | **No** | New endpoint + `MoodCategory` model |

**Domain-model reality:** `Track(id, title, artist, album, durationMs, artworkUrl?)` — no `artistId`, `genre`, `explicit`, `popularity`, or dominant color. There is no `Artist`, `Playlist`, `MoodCategory`, `RadioSeed`, or `RecommendationSet`. Artist portraits, playlist cards, and mood chips all require model work before they can exist.

**The load-bearing insight:** everything in the *top* half of a great Home — resume, recents, liked, now-playing continuity — is **buildable today with zero new backend.** Everything in the *discovery* half requires client endpoints that don't exist. A responsible design puts the buildable, high-certainty material where the eye lands first, and treats discovery as progressive enhancement that degrades gracefully while the client catches up to the server.

### 1.7 FORENSIC VERDICT

**PRESERVE** *(working, identity-defining — keep and consume as-is)*
- The token system (`SonaraColors` / `Typography` / `Dimensions` / `Shape` / `SonaraTheme` accessor). Exemplary; consume semantic roles, never hardcode.
- The MiniPlayer, its bottom-docked position, and tap-body→Lyrics.
- The frozen dual-layer theme toggle.
- The `TrackRowItem` *pattern* (artwork-left, text-forward, like-trailing).
- Tonal-first hierarchy, 4dp rhythm, restrained radii, **oxide-as-state-only**.
- The top-bar wordmark as persistent brand identity.

**EVOLVE** *(good bones, must grow)*
- `HomeUiState` / `HomeViewModel`: from two flat channels → **per-module** states (each module loads / empties / errors independently), progressively loaded, still UDF + `WhileSubscribed`.
- Navigation: manual `when` → real `SonaraNavGraph` with predictive back and saved state (§21); **keep** the custom bottom-bar *visuals*.
- Bottom-nav glyphs → native/Sonara icon assets (§7 intent).
- Raw section `Text` → a real `SectionHeader` (title + optional "see all").
- Loading: spinner-only → **skeleton/shimmer** that preserves cached content (§16).

**REMOVE** *(no reason to exist)*
- "Welcome to Sonara" greeting block — redundant with the wordmark, not Sonara-specific, and exactly the "Good morning" pattern the brief rejects.
- "Quick Play Catalog" label — meaningless; either it becomes real recommendations or it's cut.
- The stubbed `curatedSeed` + Unsplash placeholder art as a *shipped* surface — that's dev scaffolding.
- The 80dp magic-number spacer → proper `contentPadding` / Scaffold insets.

**REPLACE** *(right idea, wrong implementation/location)*
- `TrackRowItem` (in `LibraryScreen`) → promote to core `TrackRow` / `CompactTrackRow` so Home and Search stop cross-importing a Library file.
- `getHomeCatalog()` stub → either a real discovery endpoint + repository, **or** an honest local-first composition that never pretends placeholder art is content.

**MISSING** *(the gap between backend capability and client reality)*
- Client endpoints for home/discovery, recommendations, radio, related/similar, artist adjacency, curated playlists, mood/category — **none exist** in `SonaraBackendClient`.
- Repositories: `DiscoveryRepository` / `RecommendationRepository` / `RadioRepository` (or an expanded `CatalogRepository`).
- Domain models: richer `Track`, plus `Artist`, `Playlist`, `MoodCategory`, `RadioSeed`, `RecommendationSet`.
- Components: `SectionHeader`, `Artwork`, `HorizontalMediaRow` (LazyRow carousel), `AlbumCard`, `PlaylistCard`, `ArtistChip`, `SonaraChip`, `SonaraSearchField`, skeleton loaders.
- A **Continue/Resume** surface bound to `PlaybackSessionSnapshot` (data exists; UI does not).
- Adaptive Home scaffolding (`WindowSizeClass`) — the MiniPlayer already adapts; Home does not.

---

## PART 2 — THREE HOME CONCEPTS

Each concept is scored on all 16 required dimensions. They are **structurally** different — different information architecture, different above-the-fold logic, different relationships between Search / Discovery / Recommendations — not restyles of the same stack.

---

### CONCEPT A — "THE CONSOLE"

**1. Name & one-line identity**
The Console — Home as the *instrument's control surface*: it answers "what now?" before it asks "what's new?"

**2. Design philosophy**
The app is a single instrument, and the MiniPlayer is its most finished face. The Console extends that face upward: the top of Home is a petrol-glass **resume/continue** surface that materially rhymes with the MiniPlayer, so the screen reads as one device bookended top and bottom. Discovery is real but *earned by scrolling* — the first thing Sonara does is get you back into sound, instantly.

**3. Information architecture**
A vertical spine of independently-loading modules, ordered by *certainty and immediacy*: resume → recents → liked shortcuts → (progressive) recommendations → radio → mood. Certainty decreases as you scroll; the buildable-now material is above the fold, the backend-dependent material is below it.

**4. Above-the-fold layout**
A single **Console hero**: the last session (`PlaybackSessionSnapshot` + `PlayerUiState`) rendered on a petrol-glass surface (reusing MiniPlayer *material tokens*, not the component), a large oxide **Resume** affordance, elapsed/duration context, and a compact "jump back in" recents strip. A slim `SonaraSearchField` affordance sits in/near the top bar — present, not dominant. **No greeting.**

**5. Section order & rationale**
Resume (instant re-entry) → Recently played (local, certain) → Liked shortcuts (local, certain) → Recommended for you (server, progressive) → Start a radio (server) → Moods (server). Local-first and certainty-first; nothing above the fold depends on an endpoint that doesn't exist yet.

**6. Artwork strategy**
Restrained, three sizes: **hero** ~96–120dp on the Console surface; **compact rows** 44–56dp for recents/liked; **medium** 120–140dp only once discovery carousels appear below the fold. Text-forward for radio/mood (no forced imagery).

**7. Typography hierarchy**
`screenTitle`/`display` reserved for the Console hero's track title (editorial, negative-tracked). `sectionTitle` for module headers, `trackTitle`+`artistMetadata` for rows, `caption` for context (elapsed, "3 days ago"). One expressive moment, then calm.

**8. Spacing strategy**
Generous: 28–32dp between modules, 16dp gutters, 12dp intra-row. The Console hero gets extra breathing room (it's the anchor). Density is *low visually, high in value.*

**9. Interaction model**
Resume is one tap (oxide). Recents/liked rows play on tap, like on trailing tap. Discovery carousels fling horizontally. Search affordance expands into the Search destination. Motion is minimal: a subtle entrance stagger, a resume-press feedback, crossfade on recommendation refresh.

**10. Backend data sources**
Above fold = **100% buildable now**: `PlaybackSessionSnapshot`, `PlayerUiState`, `HistoryRepository`, `LibraryRepository`. Below fold = future `DiscoveryRepository` / `RecommendationRepository` / `RadioRepository`; each module self-degrades (skeleton → empty) until its endpoint lands.

**11. M3 opportunities (foundation, not look)**
`WindowSizeClass` for the hero's adaptive width; M3 semantics/heading roles on `SectionHeader`; touch-target compliance; predictive-back once `NavGraph` exists; adaptive `contentPadding` replacing the 80dp spacer.

**12. Responsive behavior**
Reuse the MiniPlayer's breakpoint philosophy (360/412/600 + tablet): single column → wider gutters → optional two-column discovery grid on tablet. The Console hero scales its artwork/inset by width class, echoing the MiniPlayer Quints.

**13. Accessibility**
Headings on section titles; TalkBack order top-down matching visual order; content descriptions on artwork and the resume action; playback state never color-only (icon + label); full text scaling; reduced-motion honored via the app's existing detection (`ANIMATOR_DURATION_SCALE`).

**14. Strengths**
Highest Sonara identity (instrument continuity); **most buildable today**; answers "what now?" in one glance; degrades gracefully as endpoints arrive; scales cleanly.

**15. Weaknesses**
Discovery is below the fold — a discovery-led product would want more of it up top. The Console hero is empty on true cold-start (no session, no history) and needs a strong first-run state. Risk of feeling "player-heavy" if the hero isn't disciplined.

**16. Why it's unmistakably Sonara**
Nothing else in the app looks like the petrol-glass instrument surface. Extending it to Home makes the whole screen feel like one continuous device — a look no Spotify/YT-Music/Apple clone shares.

---

### CONCEPT B — "EDITORIAL ROOMS"

**1. Name & one-line identity**
Editorial Rooms — Home as a *quiet music magazine*: distinct, typographically-led "rooms," each with its own layout rhythm.

**2. Design philosophy**
Lean hard into the underused editorial type scale (tight negative-tracked `display`/`screenTitle`). Discovery-first, but curatorial rather than algorithmic-feeling. Each "room" (Recently, A room for the evening, Artists you're circling, From your library) has a *different* internal layout, so scrolling feels like turning pages, not scrolling a feed.

**3. Information architecture**
Heterogeneous stacked "rooms," each a self-contained editorial unit with its own header treatment, artwork rule, and density. Order is a *narrative*, not a certainty gradient.

**4. Above-the-fold layout**
A large editorial **feature** — one hero recommendation or "tonight's room" — set with `display` type and a single large artwork or a deliberately *type-only* treatment. Brand identity via the wordmark; search is a top affordance.

**5. Section order & rationale**
Feature → Recently played → an editorial discovery room (mood/curated) → artist adjacency room → library shortcuts. Ordered for *editorial pacing* (loud → calm → loud), not immediacy.

**6. Artwork strategy**
The most expressive of the three: large feature artwork, occasional full-bleed-ish editorial images, but also deliberate **type-only** rooms with no artwork at all. Artwork is a compositional choice per room, not a default.

**7. Typography hierarchy**
The star. `display` 32 negative-tracked for room titles/feature; `sectionTitle` for sub-rooms; strong contrast between title weight and metadata. Type *is* the layout.

**8. Spacing strategy**
Variable by room — some rooms tight and dense, others airy — to create rhythm. Larger inter-room separation (32dp+) to signal "new page."

**9. Interaction model**
Rooms invite browsing; horizontal and vertical mixes. Tap-to-play, tap-to-open-room ("see all"). Slightly more motion budget (staggered room entrance) but still calm.

**10. Backend data sources**
The **most data-hungry**: the feature and most rooms need real recommendations, curated playlists, artist adjacency, and mood — **none reachable from the client today**. Only the Recently/Library rooms are buildable now. Highest client-wiring cost.

**11. M3 opportunities**
Heading semantics per room (critical for TalkBack given heterogeneity); `WindowSizeClass` for room reflow; adaptive typography scaling.

**12. Responsive behavior**
Rooms reflow individually — a type-only room stays single-column; an artwork room becomes a grid on tablet. Most complex responsive story of the three.

**13. Accessibility**
Hardest to get right: heterogeneous layouts need careful heading structure and reading order; type-only rooms must still meet contrast; large display type must scale without breaking composition.

**14. Strengths**
Most visually original; strongest use of the existing editorial type scale; feels crafted, human, non-algorithmic; deeply *not* a commercial-app clone.

**15. Weaknesses**
Most backend-dependent (largely un-buildable today); highest implementation and *curation* cost; heterogeneity risks inconsistency and accessibility bugs; weakest at "get me back into sound fast."

**16. Why it's unmistakably Sonara**
The negative-tracked editorial type + tonal restraint + "rooms" pacing is a register no mainstream music app uses — they're all uniform feeds. This is the most differentiated concept.

---

### CONCEPT C — "THE TUNER"

**1. Name & one-line identity**
The Tuner — Home as an *adaptive, recommendation-first feed* with Search and Discovery fused into one calm entry point.

**2. Design philosophy**
Home is a prioritized surface that adapts to context and taste. A persistent, calm `SonaraSearchField` at the top *is* the discovery entry — search and discovery are one act, not two destinations. Below it, action-forward "press to start" units (radio, mixes, recommended sets) get you into sound in one tap.

**3. Information architecture**
A single adaptive feed whose *module order changes* with signal: strong recent taste → recommendations lead; fresh session → resume leads; sparse data → local + curated lead. The IA is a ranking function, not a fixed list.

**4. Above-the-fold layout**
Persistent `SonaraSearchField` (fused search+discovery) → one or two **"press to start"** action units (Start radio / Your mix) rendered as oxide-action-forward tiles. Immediate, low-text, high-action.

**5. Section order & rationale**
Dynamic. Default: Search field → top action unit → recommended → radio → recents → mood. But the ranker reorders by available signal, so no two users (or sessions) see the same order.

**6. Artwork strategy**
Functional and medium-weight: action units use compact artwork or a generated tonal wash; recommendation carousels use 120–140dp cards. Artwork supports action, never decorates.

**7. Typography hierarchy**
Utility-forward: `sectionTitle` for module headers, strong `buttonLabel`/`cardTitle` on action units, `caption` for rationale ("because you played …"). Less editorial than B, more functional.

**8. Spacing strategy**
Consistent, moderate rhythm (24dp) — the feed is uniform so ranking changes read clearly; action units get slightly more emphasis via padding, not decoration.

**9. Interaction model**
The most action-forward: "press to start" is one tap into playback; search is always at hand; the feed refreshes/re-ranks. Requires careful motion so re-ranking doesn't feel jumpy (crossfade, stable keys).

**10. Backend data sources**
The most recommendation-dependent: needs ListenBrainz recs, radio, similar, and a ranking signal — **none in the client today**, and it *also* needs a client-side ranking layer. Highest "intelligence" cost. Search and local resume/history are buildable now; the differentiator (the ranker + recs) is not.

**11. M3 opportunities**
`WindowSizeClass` for feed width; adaptive layout; semantics for the search field; predictive back into search results; touch targets on action tiles.

**12. Responsive behavior**
Feed widens gutters, then goes two-column on tablet; the search field stays pinned. Scales the best of the three because the feed is uniform.

**13. Accessibility**
Dynamic order is the risk: reading order must stay stable within a session and be announced coherently; search field needs clear labeling; action units need descriptive labels beyond "start."

**14. Strengths**
Best at "one tap into sound"; fuses search+discovery elegantly (solves the Search/Discovery real-estate question); scales best; most personalized *once recs exist*.

**15. Weaknesses**
Most dependent on capabilities the client lacks (recs + a ranker); adaptive ordering is complex and easy to get wrong; risks feeling generic/algorithmic if the calm restraint slips; weakest identity signal of the three (a good feed is still a feed).

**16. Why it's unmistakably Sonara**
The *calm* of it — a recommendation surface with no badges, no "trending," no social, oxide used only for the single start action — is Sonara's restraint applied to a feed. But it leans on discipline, not on a unique structural signature.

---

## PART 3 — COMPARISON, RECOMMENDATION, PRINCIPLES, BLUEPRINT

### 3.1 Comparison matrix

| Dimension | A — The Console | B — Editorial Rooms | C — The Tuner |
|---|---|---|---|
| **Identity** (unmistakably Sonara) | ★★★★★ instrument continuity | ★★★★★ editorial register | ★★★☆☆ calm feed |
| **Information density** (value ÷ visual noise) | ★★★★☆ low-visual, high-value | ★★★☆☆ variable | ★★★★☆ uniform, clear |
| **Discovery** | ★★★☆☆ below fold, progressive | ★★★★★ discovery-led | ★★★★★ rec-led |
| **Personalization** | ★★★★☆ resume+history now | ★★★☆☆ curatorial | ★★★★★ ranked (when recs exist) |
| **Visual originality** | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| **Implementation complexity** | ★★☆☆☆ **lowest** (mostly buildable now) | ★★★★★ highest (data + curation) | ★★★★☆ high (recs + ranker) |
| **Scalability** | ★★★★☆ | ★★★☆☆ curation cost | ★★★★★ |
| **M3 compatibility** | ★★★★★ | ★★★★☆ | ★★★★★ |
| **Sonara fit** | ★★★★★ | ★★★★☆ | ★★★★☆ |

*(★ = weaker/heavier, ★★★★★ = stronger/lighter; for "Implementation complexity," more stars = more work.)*

### 3.2 Recommendation — **Concept A as the spine, fused with C's body and B's headers**

**Recommended direction: build "The Console" as the structural spine, fold in The Tuner's progressive recommendation body below the fold, and govern every section header with Editorial Rooms' typographic discipline.**

This is **not** the safest option in the sense of "do the least." It is a deliberate synthesis, and I'm recommending it over a pure single-concept build for three reasons:

1. **It puts the highest-certainty, most-Sonara material where the eye lands.** The Console hero is 100% buildable today (`PlaybackSessionSnapshot` + `PlayerUiState` + Room history/likes) and it extends the app's single strongest identity signal — the petrol-glass instrument — upward into Home. Nothing else in the codebase differentiates Sonara as sharply. Choosing B or C as the *spine* would put the app's least-built capability (server discovery/recs, unreachable from the client today) in the most prominent position, forcing either placeholder content or a hero that's empty until backend work lands. That's designing on credit.

2. **It refuses to waste the discovery insight.** Pure Concept A buries discovery too deep. So the body adopts **The Tuner's** progressive, self-degrading recommendation modules and its fused Search-as-discovery-entry affordance — each module skeletons then reveals as its endpoint ships, and Home gets richer over time without a redesign. This directly answers the brief's Search/Discovery/Recommendations question: **Search is a compact top affordance that expands into the Search destination; Discovery and Recommendations live in the Home body and share the same `TrackRow` language as Search results — one information architecture, not three.**

3. **It keeps the editorial register alive.** Every `SectionHeader` uses **Editorial Rooms'** typographic discipline (tight, confident, negative-tracked where appropriate), so even a utilitarian feed reads as *composed*, not generated. That's the cheapest way to buy B's originality without B's curation and accessibility cost.

**Why not B as the spine?** Most original, but most backend-dependent and least buildable now — it would ship as scaffolding or as an accessibility-fragile patchwork. Its *value* (editorial headers) is portable, so we take that and leave the risk.

**Why not C as the spine?** Best scaling and "one-tap-into-sound," but its differentiator (the ranker + recs) is exactly what the client can't do yet, and a feed is a weaker identity anchor than the instrument. Its *value* (progressive rec body, fused search) is portable, so we take that too.

**On the specific brief prompts:**
- **Above-the-fold priority:** the Console resume hero + a compact search affordance + the persistent wordmark — **never** "Good morning, Rupam." Re-entry into sound is the priority, not a greeting.
- **Content density:** high info value, low visual density — three artwork scales (hero 96–120dp, carousel 120–140dp, rows 44–56dp), text-forward radio/mood, 28–32dp section separation, single-line metadata truncation.
- **Motion:** entrance stagger, horizontal fling, resume-press feedback, rec-refresh crossfade — all Compose-native and reduced-motion-aware. No decorative parallax or glow (bible §24).
- **Accessibility preserved:** heading semantics, top-down TalkBack order, artwork/action content descriptions, full text scaling, state never color-only, reuse of the existing reduced-motion detection.

### 3.3 The three visual principles that should govern the rest of the app

1. **One instrument, many faces.** The app is a single device; surfaces share the MiniPlayer's material language (petrol-glass anchor, tonal tiers, restrained elevation, the liquid/optical rhythm) so top and bottom of every screen bookend as one instrument. Continuity over novelty. *(Derived from the MiniPlayer + tonal hierarchy — the app's strongest existing identity.)*

2. **Oxide is a verb, not a coat of paint.** The accent marks only *action / active / now* — resume, play, current, selected. Everything else earns hierarchy through weight, size, tone, and space. If oxide is decorating, it's wrong. *(Derived from the design bible's accent-as-state-only rule and the MiniPlayer's disciplined oxide use.)*

3. **Editorial calm — content breathes.** Hierarchy comes from typography and whitespace, not from cards, shadows, or gradients. Generous section rhythm (24–32dp), artwork used deliberately at a few fixed sizes rather than uniformly, one line of metadata, tight confident headers. Restraint is the brand. *(Derived from the type scale, spacing scale, restrained radii, and the brief's "the screen should breathe.")*

### 3.4 Non-binding implementation blueprint (sequence, NOT a build order to execute now)

> **Do not implement any of this yet.** This is the *shape* a future build would take, sequenced so that every step ships something real and nothing depends on capabilities the client lacks.

1. **Shell & scaffolding** — replace the 80dp magic spacer with proper `contentPadding`/insets; add a `WindowSizeClass` hook; restructure `HomeUiState`/`HomeViewModel` into **per-module** sealed states with progressive loading; keep UDF + `WhileSubscribed`.
2. **Shared component foundations (bible-sanctioned)** — promote `TrackRow`/`CompactTrackRow` to `core/ui`; build `SectionHeader`, `Artwork` (Coil wrapper with loading/unavailable states), skeleton shimmer, `SonaraChip`. No new backend required.
3. **The Console hero** — bind `PlaybackSessionSnapshot` + `PlayerUiState`; render a petrol-glass surface reusing MiniPlayer *material tokens* (not the component); large oxide Resume; a "jump back in" recents strip. Design a strong **cold-start** state for no-session/no-history. *(Fully buildable today.)*
4. **Local-first body modules** — Recently played + Liked shortcuts from existing repos. *(Fully buildable today; Home is now genuinely useful with zero new backend.)*
5. **Search-as-discovery affordance** — compact `SonaraSearchField` on Home top routing into the Search destination; unify Home rows and Search results under one `TrackRow` language.
6. **Progressive discovery/recommendation modules** — define client endpoints + `DiscoveryRepository`/`RecommendationRepository`/`RadioRepository`; wire recommended tracks, start-radio, similar-to-recent, curated playlists, mood — **each its own load/empty/error, skeletoned, self-degrading.** Add `HorizontalMediaRow`, `AlbumCard`, `PlaylistCard`, `ArtistChip`, and the required domain models (`Artist`/`Playlist`/`MoodCategory` + richer `Track`).
7. **Real navigation** — introduce `SonaraNavGraph` + `Screen` (§21) with predictive back and saved state; keep the custom bottom-bar visuals; migrate glyphs → native/Sonara icons.
8. **Adaptive behavior** — compact/normal/large/tablet via `WindowSizeClass`, echoing MiniPlayer Quints: single column → wider gutters → optional two-column discovery grid on tablet.
9. **Motion & accessibility pass** — entrance stagger, horizontal fling, resume feedback, rec-refresh crossfade, all reduced-motion-aware; heading semantics, TalkBack order, content descriptions, text scaling, non-color-only state.
10. **Physical-device verification** — light + dark, compact → tablet, offline/degraded (cached-only), true cold-start, TalkBack pass, contrast validation.

### 3.5 Guardrails (so design is never mistaken for capability)

- These are **design concepts**, not product commitments. Every discovery/recommendation/radio/artist/playlist/mood surface implies **new** client endpoints, repositories, and domain models that do not exist today.
- The MiniPlayer, the theme toggle, and the Petrol/Bone/Oxide language remain **frozen**; Home *echoes* the MiniPlayer's material via shared tokens — it never re-implements or modifies it.
- Nothing here should cause anyone (human or tooling) to invent backend capability. Where a section needs a capability the client lacks, that section must **degrade honestly** (skeleton → empty state), never fabricate content or ship placeholder artwork as if it were real.

---

*End of Phase 5A exploration. No code was modified. No production UI was created. Frozen elements (MiniPlayer, Theme Toggle, Petrol/Bone/Oxide language) were treated as fixed reference points throughout.*
