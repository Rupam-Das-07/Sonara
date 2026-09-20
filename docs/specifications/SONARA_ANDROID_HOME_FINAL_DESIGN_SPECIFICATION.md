# SONARA ANDROID — HOME FINAL DESIGN SPECIFICATION

**Status:** AUTHORITATIVE — READY FOR IMPLEMENTATION  
**Primary Creative Direction:** Hybrid Concept B — Signal / Music Journal  
**Implementation:** Jetpack Compose / Android

---

## 1. PURPOSE

This document is the single source of truth for the Sonara Android Home redesign.

Antigravity MUST read this document completely before modifying the frontend. Do not silently reinterpret, simplify, replace, or redesign these decisions.

If implementation reality conflicts with this specification, STOP and report the conflict before making a major change.

---

## 2. CORE DESIGN DIRECTION

### Hybrid Concept B — Signal / Music Journal

Sonara Home is a personal music journal/listening space, not a conventional streaming-service dashboard.

Central editorial idea:

> **Music, noted.**

The Home should feel:

- editorial
- calm
- tactile
- personal
- musical
- distinctive
- restrained
- modern without being futuristic
- expressive without becoming decorative

The goal:

> If the Sonara wordmark were removed, the interface should still feel recognizably like Sonara.

---

## 3. DESIGN PRINCIPLES

### One Instrument, Many Faces

The MiniPlayer is the strongest existing expression of Sonara's playback/material language. Other screens should relate to it without copying it.

### Oxide Is a Verb

Reserve Oxide for:

- play
- resume
- selected
- active
- current
- meaningful interaction

Do not use Oxide as generic decoration.

### Tonal Hierarchy Over Card Stacking

Hierarchy should come primarily from typography, tonal surfaces, whitespace, artwork, composition, and editorial boundaries.

Avoid excessive cards, heavy borders, large shadows, and generic M3 dashboard styling.

### Editorial Calm

Content should breathe. Use intentional spacing and hierarchy instead of filling every pixel.

---

## 4. COLOR SYSTEM

Retain the established Sonara language:

### Dark
- Petrol
- Deep Petrol
- Bone-derived supporting tones
- Oxide accents

### Light
- Bone
- Muted Bone
- Petrol for meaningful instrument/state surfaces
- Oxide for active state

Light mode must remain genuinely light. Do not simply invert dark mode.

---

## 5. PAPER-CUT / TORN EDITORIAL LANGUAGE

The torn-paper/paper-cut transitions from the original Hybrid Concept B are a CORE visual direction.

They establish rhythm between major editorial sections.

Desired rhythm:

```text
Listening Moment
      ↓
paper-cut editorial boundary
      ↓
Artist Relationship
      ↓
paper-cut editorial boundary
      ↓
Collection
      ↓
paper-cut editorial boundary
      ↓
Listening Flow
```

### Implementation

Use lightweight Android UI structure:

- Compose-drawn geometry
- vector/path treatment
- deterministic shapes
- no large bitmap textures

Do NOT use:

- photographed paper
- scanned paper
- paper fibers
- realistic textures
- stock imagery
- Photoshop composites
- baked screenshots
- heavyweight decorative images

The result should FEEL like a paper cut while remaining a clean production UI primitive.

The exact path geometry may be tuned during implementation. The treatment should be visible and intentional, but must not turn Sonara into a scrapbook.

---

## 6. TYPOGRAPHY — APPROVED DIRECTION

### Primary: Clash Display + Satoshi

#### Clash Display

Use sparingly for:

- major editorial display moments
- journal statements
- selected large album/artist emphasis
- important editorial titles

Clash must remain special. Do not use it for every title.

#### Satoshi

Use for:

- section headers
- track titles
- metadata
- body copy
- buttons
- navigation labels
- search
- lyrics/UI
- settings
- durations
- numbers
- everyday interface content

Suggested weights:

- Regular — body/default
- Medium — secondary emphasis
- Bold — section/UI emphasis

Use tabular figures where appropriate for durations and numerical playback information.

### Multilingual fallback

Before final typography freeze, verify Android rendering for:

- Bengali
- Hindi / Devanagari
- Telugu
- Tamil
- Malayalam
- Latin
- Romanized lyrics

If Clash/Satoshi lack suitable glyph coverage, define a deliberate Android fallback stack. Multilingual readability takes priority over strict font purity.

---

## 7. ICONOGRAPHY — LOCKED

### Phosphor Icons

Phosphor Icons is the authoritative icon library.

Do NOT replace it with another icon library.

Default UI weight:

- Phosphor Regular

Use stronger weights only for meaningful emphasis/active navigation where appropriate.

Icons communicate action, navigation, or state. Do not use them as decoration.

Avoid mixing arbitrary Material icons with Phosphor unless a genuine platform/system requirement requires it.

---

## 8. FINAL HOME INFORMATION ARCHITECTURE

Conceptual order:

```text
MUSIC, NOTED.
      ↓
LISTENING MOMENT
      ↓
FEATURED ARTISTS
      ↓
CURATED PLAYLISTS
      ↓
QUICK PICKS
      ↓
BECAUSE YOU LISTEN TO...
```

Modules are independently stateful and may hide when no valid content exists.

Never leave:

- blank module shells
- broken section headers
- empty cards
- fake content
- placeholder production artwork

---

## 9. LISTENING MOMENT

The previous large “Console Hero” is NOT a frozen component.

The MiniPlayer already owns persistent playback-control responsibilities.

The Home opening should express the Signal / Music Journal concept using:

- real playback state
- real artwork
- listening history
- current/resumable session where available

Never fabricate a currently playing track.

States:

### Active playback
Show meaningful current-listening context.

### Paused/resumable
Show a legitimate continuation opportunity.

### Cold start
Do not pretend something is playing. Make the Home useful through real discovery content.

### Offline
Gracefully use cached/local information.

---

## 10. FEATURED ARTISTS

Featured Artists follows the Listening Moment.

Purpose:

> Discover a voice / artist.

Use real backend data with real identity, stable ID where supported, genre/metadata, and real artwork where available.

Never use:

- Unsplash
- stock portraits
- fabricated artists
- arbitrary decorative avatars

If artwork is unavailable, use an approved Sonara fallback or omit/hide the item.

Only provide artist navigation where the real client/backend contract supports it.

---

## 11. CURATED PLAYLISTS

Curated Playlists follows Featured Artists.

This is a first-class discovery pillar.

The backend playlist system produces real playable tracks and remains authoritative for curation.

Android consumes curated playlist data rather than rebuilding curation locally.

Playlist artwork must derive from real associated track artwork.

Do NOT use:

- missing/static fake PNG assumptions
- Unsplash
- stock imagery
- fake covers
- decorative placeholder covers

Playlist tracks must ultimately be playable.

---

## 12. QUICK PICKS

Quick Picks replaces Trending in the immediate Home IA.

### Trending

Trending is explicitly DEFERRED.

Do not build a primary Home Trending module and do not weaken validation simply to populate it.

### Quick Picks source

The existing Web Quick Picks behavior is the conceptual source of truth.

It uses:

- `searchSongs("trending global songs")`
- variety balancing
- maximum two tracks per artist
- no consecutive same-artist results
- exposure balancing against other Home content
- session-stable blend rotation
- approximately 45-minute retained rotation

Playback uses:

```text
videoId: song.videoId || song.id
```

Android Quick Picks must be backed by the independent Sonara backend.

Do NOT read JioSaavn directly for Quick Picks.

Do NOT use the fragile JioSaavn → YouTube trending-resolution pipeline.

Intended architecture:

```text
Android
  ↓
Sonara Backend
  ↓
Quick Picks service
  ↓
ytmusicProvider.searchSongs()
  ↓
real YouTube videoId
  ↓
existing stream/playback pipeline
```

Every displayed Quick Pick must be genuinely playable.

---

## 13. BECAUSE YOU LISTEN TO...

This is the contextual recommendation layer.

Semantic meaning:

> Because you listened to X.

Do not present it as generic personalized recommendations unless the backend guarantees that.

Requires a valid seed `videoId`.

If a meaningful seed exists:

- show it
- identify seed context where appropriate
- consume backend recommendations

If no seed exists:

- hide the module

Do not show a large empty placeholder or fabricate personalization.

Android must not recreate recommendation ranking locally.

---

## 14. SEARCH

Search remains a compact top-level affordance.

It must not dominate the Home viewport.

Avoid a giant generic M3 SearchBar.

Use the existing Sonara search architecture and established Search destination.

Do not duplicate search networking or ViewModels.

---

## 15. SHARED UI PRIMITIVES

Promote/reuse shared vocabulary where appropriate:

- TrackRow
- CompactTrackRow
- Artwork
- SectionHeader
- HorizontalMediaRow
- ArtistChip
- PlaylistCard
- SonaraChip

Do not duplicate components.

If a Library track component is reused by Home/Search, move it into an appropriate shared location instead of maintaining cross-screen ownership.

---

## 16. SECTION HEADERS

Section headers should use the editorial typography system.

Avoid generic raw `Text()` section labels as the complete treatment.

The journal grammar may use phrases such as:

- Music, noted.
- Voices we love
- Curated for tonight
- Quick picks
- Because you listened to...

Do not force personalization language unsupported by data.

---

## 17. ARTWORK

Artwork must always be real.

Allowed:

- backend track artwork
- real artist artwork
- playlist artwork derived from real content
- existing artwork URL upgrading
- Coil loading/caching

Forbidden:

- Unsplash
- stock photography
- fabricated portraits
- fake playlist covers
- decorative stock artwork
- invented production content

Artwork should be compositional, not simply inserted into generic cards.

---

## 18. MINIPLAYER — FROZEN

Do NOT redesign or modify:

- geometry
- capsule
- artwork
- controls
- progress rail
- spacing
- typography
- theme behavior
- motion
- interaction model

The Home must coexist with the MiniPlayer rather than duplicate its role.

---

## 19. THEME TOGGLE — FROZEN

Do not modify:

- Theme Toggle
- reveal animation
- theme transition behavior

---

## 20. NAVIGATION

Preserve custom Sonara navigation visuals.

Do NOT replace them with generic M3 NavigationBar visuals.

Navigation architecture may be improved internally if needed, but visual language remains Sonara.

At large/tablet widths:

- NavigationRail direction is approved
- multi-column content is approved
- right-side Now Playing / Queue panel is DEFERRED

---

## 21. RESPONSIVE DESIGN

### Phone

- single-column editorial composition
- controlled horizontal discovery areas
- intentional vertical rhythm

Do not simply stack generic cards.

### Tablet / Large Screens

- NavigationRail
- wider content region
- multi-column discovery composition where appropriate

Tablet should feel designed rather than stretched.

---

## 22. SPACING / GEOMETRY

Use established Sonara spacing vocabulary:

```text
4
8
12
16
20
24
32
40
48
64
```

Derived dimensions are allowed when compositionally justified.

Useful relationships:

- 96dp = 2 × 48dp
- 128dp = 2 × 64dp
- existing compact artwork geometry may be retained where established

Do not invent arbitrary dimensions without reason.

---

## 23. LOADING / EMPTY / ERROR / OFFLINE

Every Home module is independently stateful:

- Loading
- Success
- Empty
- Error
- Offline

A failed module must not break other modules.

Hide unavailable modules where appropriate and preserve surrounding rhythm.

Never leave broken empty shells.

Skeletons should preserve final geometry rather than relying on generic spinners.

---

## 24. MOTION

Motion is restrained.

Avoid:

- decorative bounce
- excessive spring
- gratuitous scaling
- unnecessary parallax
- simultaneous competing animations

Every new animation should define:

- trigger
- duration
- easing
- reduced-motion behavior

Do not animate paper-cut boundaries merely because they exist.

---

## 25. ACCESSIBILITY

Maintain:

- minimum 48dp interactive target
- accessible semantics
- content descriptions
- readable contrast
- scalable text
- TalkBack support
- reduced-motion behavior

Visual geometry and touch geometry may differ.

---

## 26. MATERIAL 3

Material 3 is the engineering foundation, NOT the visual authority.

Use M3 for:

- accessibility
- interaction behavior
- semantics
- adaptive infrastructure
- platform conventions where compatible

Do not let default M3 components flatten Sonara's visual identity.

Avoid automatically introducing generic Cards, NavigationBars, SearchBars, TopAppBars, or Sliders when custom Sonara treatments are appropriate.

---

## 27. BACKEND / CLIENT BOUNDARY

Home must use the independent `sonara-backend`.

Do not:

- call the legacy Web backend unnecessarily
- call Python directly from Android
- expose server secrets
- resolve raw Googlevideo URLs on Android
- duplicate yt-dlp logic
- persist ephemeral stream URLs

Canonical identity remains:

```text
videoId
```

Server owns discovery/recommendation logic. Android owns presentation and local playback integration.

---

## 28. FROZEN SYSTEMS

Do not modify unless an explicit compatibility defect requires a minimal correction:

- MiniPlayer
- SonaraPlaybackService
- MediaControllerClient
- PlayerViewModel playback authority
- Room schemas
- DataStore contracts
- LrclibLyricsProvider
- LRC parser
- Romanization
- ArtworkUrlUpgrader
- Coil infrastructure
- Theme Toggle
- theme reveal animation
- videoId identity model
- backend stream security boundary

Do not modify frozen systems for convenience.

---

## 29. ENGINEERING TOOLING / MCP

Use available engineering tools deliberately.

### Sequential Thinking
Use for:

- architecture sequencing
- dependency analysis
- conflict resolution
- regression reasoning
- implementation planning

### Code Refactoring Clean
Use for:

- obsolete scaffolding detection
- duplicate UI/component detection
- shared primitive extraction
- cleanup validation
- architectural regression review

### Ponytail
Use for:

- Kotlin/Java analysis
- reference tracing
- caller/callee impact
- Kotlin implementation verification

### Reticle
**DO NOT USE.**

Never claim an MCP/server was used unless it is actually available and was actually used.

---

## 30. IMPLEMENTATION WORKFLOW

### Stage 1 — Read
Read this document completely before touching code.

### Stage 2 — Forensic Mapping
Map current implementation against this specification.

Identify:

- existing pieces
- reusable pieces
- refactors
- removals
- frozen systems
- new work

### Stage 3 — Conflict Report
Before major implementation, report:

1. conflict
2. evidence
3. root cause
4. minimal correction
5. affected files
6. whether a frozen contract is affected

Do not silently resolve major conflicts.

### Stage 4 — Implementation
After conflicts are resolved:

1. shared primitives
2. editorial section grammar
3. Listening Moment
4. Featured Artists
5. Curated Playlists
6. Quick Picks
7. Because You Listen To...
8. independent module states
9. responsive behavior
10. accessibility
11. motion

---

## 31. OBSOLETE HOME SCAFFOLDING

Remove obsolete Home scaffolding only after reference analysis.

Expected candidates:

- Welcome to Sonara greeting
- old Quick Play Catalog presentation
- `curatedSeed`
- Unsplash placeholder artwork
- redundant 80dp MiniPlayer spacer
- obsolete Home-only components

Do not remove code merely because it appears unused; verify references first.

---

## 32. PLAYBACK

All Home tracks presented as playable must use:

```text
Home
 ↓
ViewModel
 ↓
Repository
 ↓
SonaraBackendClient
 ↓
stream resolution
 ↓
MediaControllerClient
 ↓
SonaraPlaybackService
 ↓
Media3 / ExoPlayer
```

Do not create a second playback path.

Do not make Home own ExoPlayer.

---

## 33. TESTING

Backend verification baseline should be checked against the current project state before relying on any historical test count.

Android:

```text
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Test:

- cold start
- active playback
- paused playback
- resume
- Featured Artists
- Curated Playlists
- Quick Picks
- Because You Listen To...
- missing recommendation seed
- module failure
- empty module
- offline
- artwork unavailable
- search navigation
- MiniPlayer coexistence
- dark mode
- Bone/light mode
- phone
- tablet
- landscape
- TalkBack/accessibility
- reduced motion

---

## 34. PHYSICAL DEVICE VERIFICATION

Do not declare completion from compilation alone.

Verify on a real Android device:

- first viewport composition
- scrolling rhythm
- real artwork
- playback
- Listening Moment behavior
- Featured Artists
- Curated Playlists
- Quick Picks
- recommendation behavior
- MiniPlayer coexistence
- navigation
- cold start
- offline
- loading/error states
- Dark mode
- Bone/light mode
- touch targets
- typography
- multilingual rendering

---

## 35. VISUAL ACCEPTANCE CRITERIA

The final Home must satisfy:

1. Feels like a music journal, not a streaming dashboard.
2. “Music, noted.” has genuine editorial presence.
3. Paper-cut transitions are visible and intentional.
4. Paper-cut treatment remains structural, not scrapbook-like.
5. Generic rounded cards are not the primary grammar.
6. MiniPlayer remains the strongest persistent playback object.
7. Home does not unnecessarily duplicate MiniPlayer.
8. Featured Artists feels editorial rather than generic.
9. Curated Playlists feels curated rather than a standard card grid.
10. Quick Picks feels immediate and playable.
11. Because You Listen To... is truthful and seed-based.
12. Trending is not a primary Home module.
13. Artwork is real.
14. No Unsplash/stock/fabricated production imagery remains.
15. Oxide is restrained and state-driven.
16. Bone mode remains genuinely light.
17. Dark mode retains Petrol identity.
18. Clash Display feels special rather than overused.
19. Satoshi carries everyday product communication.
20. Phosphor Icons are used consistently.
21. Search remains compact.
22. Home has intentional whitespace.
23. Modules fail independently.
24. No awkward blank gaps appear when modules hide.
25. Phone and tablet layouts feel deliberately designed.
26. The result feels unmistakably Sonara without relying on the wordmark.

---

## 36. WHAT MUST NOT HAPPEN

Do NOT:

- return to the old Console Hero simply because it is easier
- recreate the previous Phase 5C card-stack composition
- turn Home into a Spotify-style clone
- turn Home into a scrapbook
- turn Home into a Photoshop-style mockup
- use stock imagery
- use fake content
- fabricate personalization
- reintroduce Trending as a required module
- weaken backend validation to make content appear
- modify MiniPlayer for visual convenience
- replace Phosphor Icons
- replace Clash + Satoshi without explicit review
- introduce another design system
- silently change frozen architecture

---

## 37. DESIGN INTENT

> **Sonara Home should feel like opening a personal music journal whose pages are made from sound, memory, artists, collections, and listening moments — not like opening another streaming-service catalog.**

---

## 38. FINAL IMPLEMENTATION REPORT

After implementation, produce:

# SONARA ANDROID — HOME REDESIGN IMPLEMENTATION REPORT

Include:

1. Executive Summary
2. Final Home IA
3. Hybrid Concept B Translation
4. Paper-Cut Implementation
5. Typography
6. Phosphor Icon Integration
7. Listening Moment
8. Featured Artists
9. Curated Playlists
10. Quick Picks
11. Because You Listen To...
12. Search
13. Shared UI Primitives
14. Loading/Empty/Error/Offline States
15. Artwork Strategy
16. Navigation
17. Responsive Design
18. Accessibility
19. Motion
20. Backend/Client Integration
21. Files Added
22. Files Modified
23. Files Removed
24. Frozen Contract Verification
25. Tests
26. Build Results
27. Physical Device Results
28. Known Limitations
29. Deferred Work
30. Final Visual Audit

For every modified file state:

- why it changed
- what changed
- whether it touches a frozen contract

---

## 39. FINAL AUTHORITY

Creative foundation:

**Hybrid Concept B — Signal / Music Journal**

Visual signatures:

**Music, noted.**  
**Paper-cut editorial transitions**  
**Petrol / Bone / Oxide**  
**Clash Display + Satoshi**  
**Phosphor Icons**

Home IA:

**Listening Moment → Featured Artists → Curated Playlists → Quick Picks → Because You Listen To...**

Trending:

**DEFERRED**

MiniPlayer:

**FROZEN**

Objective:

**A distinctive, production-realistic Sonara Home that feels authored rather than assembled.**

END OF AUTHORITATIVE SPECIFICATION.
