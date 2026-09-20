# SONARA ANDROID --- DESIGN SYSTEM

## `DESIGN_SYSTEM_ANDROID.md`

**Document:** Android Visual & Interaction Design System Specification\
**Purpose:** Pre-implementation design-system contract for Jetpack
Compose\
**Status:** **DRAFT --- NOT FROZEN**\
**Scope:** Visual system, reusable UI vocabulary, interaction states,
accessibility, and Compose mapping\
**Product:** Sonara Android --- open-source, local-first music
application

------------------------------------------------------------------------

## 0. Document Status and Authority

This document formalizes the already-approved Sonara Android visual
direction. It is a **design-system specification**, not a UI concept,
feature specification, architecture rewrite, or implementation guide.

### Authoritative visual references

1.  Sonara Android V0.3 --- Home / Search / Now Playing / Lyrics visual
    direction.
2.  Sonara Android V0.4 --- Mini Player + Profile / Account visual
    direction.
3.  Sonara Android Final Mini Player exploration.
4.  Sonara Android Final Profile / Account exploration.
5.  Existing Sonara Web references --- visual DNA only, not Android
    layout templates.

The Android design process explicitly establishes **identity over
parity**: Petrol/Bone/Oxide, musical focus, atmospheric mood, and visual
hierarchy are retained, while web geometry and CSS implementation
details are not treated as Android constraints.

### Source/architecture basis reviewed

This specification was cross-checked against the available Phase 4 and
pre-implementation audit material covering:

-   Phase 4A architectural discovery.
-   Phase 4B-1 playback ownership.
-   Phase 4B-2 state/UI architecture.
-   Phase 4B-3 data/persistence/networking.
-   Phase 4C networking resilience.
-   Phase 4D playback failure/recovery.
-   Pre-Implementation Audit 01 --- Android project/package structure.
-   Available Web → Android asset/portability audit material.

### Architectural baseline resolution

The SDK baseline is authoritatively resolved by Phase 4A and
Pre-Implementation Audit 01:

-   **minSdk = 26**
-   **targetSdk = 35**
-   **compileSdk = 35**

The previously observed `minSdk 37` in the workspace was an unconfigured
Android Studio / AGP template artifact and is not an architectural decision.
All design tokens, component contracts, and Compose specifications target this
authoritative baseline.

A minor source discrepancy exists in the light-theme Secondary Surface
token: the current Point 3 brief specifies `#E2D8CF`, while an earlier
visual specification used `#E2DBCF`. This document treats the **current
Point 3 value `#E2D8CF` as the authoritative design-system candidate**.

------------------------------------------------------------------------

# 1. Authoritative Visual Direction

## 1.1 Sonara identity

Sonara Android is defined by:

-   **PETROL** establishes the dark environmental foundation.
-   **BONE** establishes the light environmental foundation and warm
    text/surface character.
-   **OXIDE** is the primary interaction and playback accent.
-   Artwork remains a major source of visual variety.
-   Typography is restrained, editorial, and highly legible.
-   Surface hierarchy is primarily tonal rather than effect-driven.
-   Navigation is quiet and subordinate to content.
-   Playback is a first-class product surface.
-   Lyrics are a first-class music experience.
-   Profile/settings are personal and functional rather than social or
    commercial.
-   The application should feel distinctly Sonara rather than like
    generic Material 3.

## 1.2 Identity rules --- FROZEN

1.  Petrol/Bone/Oxide remains the brand system.
2.  Dark and Light themes are both first-class.
3.  Oxide is selective; it does not decorate the whole interface.
4.  Album/artist artwork is not recolored to fit the theme.
5.  No generic purple/indigo/cyan SaaS styling.
6.  No decorative gradients, neon glow, or heavy glassmorphism.
7.  Material 3 is a structural foundation, not Sonara's visual identity.
8.  Android layouts are purpose-built; Web layouts are reference
    material only.
9.  The Mini Player is one shared component/system, not screen-specific
    copies.
10. Feature UI does not own Media3, repositories, databases, or
    networking.
11. Accessibility is part of the component contract.
12. Visual concepts are not automatically product requirements.

## 1.3 Implementation-tunable rules

The following may be tuned after device validation without changing
identity:

-   exact dp spacing within the defined scale;
-   typography size/line-height adjustments for device classes;
-   exact tonal alpha values;
-   exact corner radii within approved shape families;
-   shadow/elevation strength;
-   animation duration/easing;
-   artwork crop strategy where source aspect ratios require adaptation;
-   exact Material 3 token plumbing;
-   exact Compose component internals;
-   tablet/expanded-width composition.

------------------------------------------------------------------------

# 2. Semantic Color System

## 2.1 Raw approved palette

### Dark Mode

  Semantic role       Current token
  ------------------- ---------------
  Foundation          `#071A1C`
  Elevated Surface    `#0F2426`
  Secondary Surface   `#152F31`
  Primary Text        `#E7E1D6`
  Secondary Text      `#A8A297`
  Border / Divider    `#264043`
  Accent / Oxide      `#A25A3A`
  Accent Soft         `#7C4A2F`

### Light Mode

  Semantic role       Current token
  ------------------- ---------------
  Foundation          `#F6F2EA`
  Elevated Surface    `#ECE6DA`
  Secondary Surface   `#E2D8CF`
  Primary Text        `#1B1F1E`
  Secondary Text      `#5B5A53`
  Border / Divider    `#D2CCC1`
  Accent / Oxide      `#A25A3A`
  Accent Soft         `#7C8862`

> **Review note:** An earlier reference used `#E2DBCF` for Light
> Secondary Surface. `#E2D8CF` is retained here because it is the value
> specified by the current Point 3 brief.

## 2.2 Semantic tokens

  -------------------------------------------------------------------------------------------
  Token                                   Dark                Light Meaning
  ----------------------- -------------------- -------------------- -------------------------
  `background`                       `#071A1C`            `#F6F2EA` Primary app canvas

  `surface`                          `#0F2426`            `#ECE6DA` Standard elevated/content
                                                                    surface

  `surfaceVariant`                   `#152F31`            `#E2D8CF` Secondary
                                                                    surface/card/list
                                                                    hierarchy

  `primaryText`                      `#E7E1D6`            `#1B1F1E` Main readable content

  `secondaryText`                    `#A8A297`            `#5B5A53` Metadata/supporting
                                                                    content

  `disabledText`            derived muted tone   derived muted tone Disabled content

  `divider`                          `#264043`            `#D2CCC1` Structural boundaries

  `accent`                           `#A25A3A`            `#A25A3A` Primary
                                                                    identity/interaction
                                                                    accent

  `accentSoft`                       `#7C4A2F`            `#7C8862` Soft accent state

  `accentContainer`          restrained accent    restrained accent Selected/active container
                                   tonal blend          tonal blend 

  `onAccent`             `#F6F2EA` / `#FFFFFF` `#F6F2EA` / `#FFFFFF` Content directly on Oxide
                                                                    (WCAG AA contrast-safe)

  `playbackActive`                   `#A25A3A`            `#A25A3A` Playing/progress/active
                                                                    control

  `playbackBuffering`      accent + restrained                 same Temporary buffering state
                            motion/opacity cue                      

  `playbackUnavailable`         semantic error       semantic error Failed/unavailable
                                     treatment            treatment playback

  `success`                accessible semantic                 same Successful non-brand
                                  success tone                      status

  `warning`                accessible semantic                 same Warning state
                                  warning tone                      

  `error`                  accessible semantic                 same Error/destructive state
                                    error tone                      
  -------------------------------------------------------------------------------------------

### 2.3 Accent rules

Oxide is a **state color**, not a decorative theme wash.

Primary uses:

-   play/pause action;
-   playback progress;
-   selected navigation state where appropriate;
-   active lyric emphasis where appropriate;
-   selected controls;
-   meaningful confirmation/active state;
-   focused interactive control when a stronger cue is required.

Avoid coloring every icon, every border, or every card. Do not use Oxide
gradients or glow as decoration.

### 2.4 Disabled / pressed / focused / selected

-   **Disabled:** reduce emphasis without making content unreadable.
-   **Pressed:** subtle tonal surface change and/or restrained accent
    reinforcement.
-   **Focused:** visible but quiet focus treatment.
-   **Selected:** Oxide plus a tonal/surface distinction where useful.
-   **Active playback:** Oxide + control/icon state; never color alone.

### 2.5 Progress, sliders, switches and chips

-   Progress fill: `accent`.
-   Progress track: low-contrast neutral derived from the current
    surface.
-   Slider thumb: `accent`.
-   Active switch: `accent` with accessible `onAccent` treatment.
-   Inactive switch: neutral surface/border relationship.
-   Chips: reserved for genuine filtering/selection/navigation.

### 2.6 Contrast principle

Every foreground/background pair must be validated at actual rendered
sizes. A screenshot-matching low-contrast state is not acceptable if it
fails accessibility.

`onAccent` rendered on Oxide (`#A25A3A`) must use Bone (`#F6F2EA`) or
White (`#FFFFFF`). The darker Primary Text token (`#E7E1D6`) must not be
used for normal-sized text or icons on Oxide where WCAG AA contrast is
required (`#E7E1D6` on `#A25A3A` $\approx 3.98:1$; `#F6F2EA` on `#A25A3A`
$\approx 4.63:1$; `#FFFFFF` on `#A25A3A` $\approx 5.17:1$).

------------------------------------------------------------------------

# 3. Typography System

## 3.1 Typeface

Use the Android system **Roboto / Variable Font** foundation identified
by the Android audit. No external font package is required for the
initial system.

## 3.2 Semantic hierarchy

  ----------------------------------------------------------------------------
  Role                      Approx. size Weight            Character
  ---------------- --------------------- ----------------- -------------------
  Display / Hero                30--36sp Bold              Rare, major
                                                           player/identity
                                                           moments

  Screen Title                  24--28sp Bold              Primary page
                                                           identity

  Section Title                 18--22sp SemiBold/Bold     Content grouping

  Card / Track                  16--18sp Medium/SemiBold   Primary music
  Title                                                    identity

  Artist /                      13--15sp Regular/Medium    Supporting
  Metadata                                                 information

  Body                          14--16sp Regular           Explanatory content

  Secondary Body                12--14sp Regular           Supporting
                                                           descriptions

  Caption                       11--12sp Medium/Regular    Low-priority
                                                           metadata

  Navigation Label              11--12sp Medium            Bottom navigation

  Button Label                  14--15sp Medium            Interactive text

  Playback Timing               11--13sp Medium            Position/duration

  Lyrics                        20--28sp Regular/Medium    Primary lyric
                                                           reading

  Active Lyrics                 22--30sp SemiBold/Bold     Current lyric
                                                           emphasis

  Romanized Lyrics              15--20sp Regular           Secondary phonetic
                                                           line
  ----------------------------------------------------------------------------

These are semantic ranges; exact values are implementation-tunable.

## 3.3 Rules

-   Titles receive hierarchy through weight/size, not saturation.
-   Metadata stays subordinate.
-   Avoid excessive uppercase labels.
-   Compact track titles normally remain one line.
-   Supporting metadata truncates rather than competing with titles.
-   Section headings do not compete with hero content.
-   Navigation labels remain compact.
-   Avoid arbitrary letter-spacing.

## 3.4 Lyrics typography

Hierarchy:

1.  active line --- strongest;
2.  nearby lines --- readable but quieter;
3.  distant lines --- progressively reduced emphasis;
4.  Romanized/secondary line --- subordinate but readable;
5.  loading/unavailable --- calm explanatory typography.

Support long Indic-script lines, Latin text, Romanized text, mixed
scripts, and user text scaling. Do not rely on blur alone for hierarchy.

------------------------------------------------------------------------

# 4. Spacing System

## 4.1 Base unit

Use **4dp as the base spacing unit**.

Core scale:

`4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 / 64`

## 4.2 Semantic spacing

  Token          Default
  ------------ ---------
  `spaceXs`          4dp
  `spaceSm`          8dp
  `spaceMd`         12dp
  `spaceLg`         16dp
  `spaceXl`         20dp
  `space2Xl`        24dp
  `space3Xl`        32dp
  `space4Xl`        40dp
  `space5Xl`        48dp
  `space6Xl`        64dp

## 4.3 Usage

-   Screen horizontal padding: normally 16--20dp.
-   Primary section separation: 24--32dp.
-   List row internal spacing: 12--16dp.
-   Card padding: normally 16dp.
-   Mini Player padding: 8--12dp.
-   Settings rows: 16dp vertical rhythm with 12--16dp horizontal
    padding.
-   Sheets: 20--24dp horizontal content padding.
-   Lyrics use larger vertical rhythm than ordinary lists.

One-off values require a component-specific reason.

------------------------------------------------------------------------

# 5. Shape / Corner System

Sonara uses a restrained shape vocabulary:

  Shape role                Guideline
  ------------------------- ----------------------------------------------------
  Small radius              8dp
  Medium radius             12dp
  Large radius              16--20dp
  Circular                  50%
  Artwork                   typically 12--20dp when rounded
  Bottom sheet              large top corners
  Dialog                    medium/large radius
  Search field              medium radius; not automatically pill-shaped
  Mini Player               medium/large radius according to approved geometry
  Primary playback button   circular

Do not make every surface rounded. Avoid pill components except where
selection/filter semantics justify them.

------------------------------------------------------------------------

# 6. Elevation / Surface Hierarchy

Hierarchy comes primarily from:

1.  tonal difference;
2.  spacing;
3.  subtle border/divider;
4.  controlled elevation;
5.  artwork contrast.

## Dark Mode

Deep Petrol canvas, restrained elevated Petrol surfaces, warm Bone text,
quiet borders, minimal shadow.

## Light Mode

Warm Bone canvas, restrained warm elevated surfaces, dark neutral text,
subtle borders, low shadow strength.

## Artwork-derived atmosphere

Allowed only as restrained contextual atmosphere around prominent
artwork. Artwork itself remains untouched. Derived color must not
replace semantic theme, reduce contrast, become a full-screen colorful
gradient, or become a requirement if performance/clarity argues against
it.

------------------------------------------------------------------------

# 7. Iconography

Use standard Compose/Material-compatible icons for ordinary actions and
approved Sonara assets for brand-specific identity.

### Brand assets

-   Sonara S symbol;
-   Sonara wordmark;
-   Romanization `aA` identity;
-   approved Sonara-specific branding.

### Standard functional icons

-   play/pause;
-   next/previous;
-   shuffle/repeat;
-   search/library;
-   favorite;
-   overflow;
-   settings;
-   navigation;
-   download;
-   back/chevrons.

Do not import dozens of Web SVGs when native Compose icons communicate
the function correctly.

Typical visible glyph sizes: 20--24dp for standard actions, 28--32dp for
large playback icons. Hit targets remain comfortably accessible even
when glyphs are smaller.

------------------------------------------------------------------------

# 8. Component System

## Core UI

### `SonaraButton`

Primary/secondary textual action. States: default, pressed, focused,
disabled, loading. Oxide reserved for meaningful primary actions.

### `SonaraIconButton`

Compact icon action. Requires content description and state semantics.

### `SonaraSurface`

Semantic surface primitive: background, surface, surfaceVariant,
elevated.

### `SonaraCard`

Content grouping when a container improves hierarchy. Not the default
for every row.

### `SonaraDivider`

Quiet structural separator; use sparingly.

### `SonaraChip`

Filtering/selection only; never decorative filler.

### `SonaraSearchField`

Calm search entry supporting idle/focused/populated/loading/error
states.

### `SonaraLoadingIndicator`

Contextual progress without blanking useful cached content
unnecessarily.

### `SonaraErrorBanner`

Recoverable/non-fatal error communication without making the whole
application feel broken.

### `SonaraEmptyState`

Quiet no-content state with an appropriate next action.

## Navigation

### `SonaraBottomNavigation`

Persistent top-level Android navigation. Quiet, accessible, inset-aware.
Selected state may use Oxide selectively. Must support Android
back/predictive-back behavior.

### Top App Bar

Restrained title/back/contextual actions. Never a Web desktop navigation
recreation.

## Music

### `TrackRow`

Artwork + title + artist/metadata + optional duration/action. States:
default, pressed, selected, playing, unavailable. Playing state may use
Oxide without tinting the entire row.

### `CompactTrackRow`

Higher-density music row for queue/search contexts.

### `Artwork`

Supports loading, loaded, unavailable, variable aspect ratio, and
appropriate rounded/circular treatment. Remote artwork remains runtime
content.

### `ArtistChip`, `AlbumCard`, `PlaylistCard`, `SectionHeader`

Use only when their semantic purpose improves hierarchy.

------------------------------------------------------------------------

# 9. Playback Component System

## `MiniPlayer`

A **single shared system component** that persists above primary
navigation where appropriate.

### Required states

-   Playing
-   Paused
-   Buffering
-   Transitioning
-   Error / Unavailable
-   No Active Track

### Visual contract

-   artwork;
-   title;
-   artist;
-   primary playback action;
-   restrained progress;
-   queue/essential secondary access;
-   clear entry into Expanded Player.

### Behavioral contract

The UI does not own playback execution. Commands flow through the
MediaController/session boundary. ExoPlayer remains authoritative inside
`SonaraPlaybackService`. The Mini Player is a projection of playback
state, not a second playback state machine.

### Performance contract

High-frequency playback position must not cause broad root-screen
recomposition. Position-sensitive rendering belongs in isolated
leaf-level consumers.

## `PlaybackControls`

Reusable play/pause, previous/next, shuffle/repeat, and approved seek
controls. Primary action receives strongest emphasis.

## `ProgressIndicator`

Playback fill uses `accent`; compact player progress stays subtle;
detailed seeking remains an Expanded Player concern.

## `QueueActionSurface`

Secondary queue/playback actions without crowding the compact player.

## Expanded Player entry

Mini and Expanded Player share artwork, track identity, state, progress
continuity, accent, and control language. Transition geometry is
implementation-tunable but the conceptual continuity is frozen.

------------------------------------------------------------------------

# 10. Lyrics Component System

### `LyricsLine`

Base line representation.

### `ActiveLyricsLine`

Strongest current-line emphasis.

### `RomanizedLyricsLine`

Secondary phonetic presentation.

### `LyricsModeSelector`

Original/Romanized selection where applicable.

Rules:

-   active line is obvious without color alone;
-   surrounding lines remain readable;
-   long lines wrap naturally;
-   generous line spacing;
-   Romanized text is subordinate but readable;
-   timing is driven by the playback clock;
-   high-frequency timing is isolated from root-screen recomposition;
-   loading/plain/synced/error states are supported.

------------------------------------------------------------------------

# 11. Profile / Settings Component System

### `ProfileHeader`

Local/personal identity with avatar, display name, optional identifier,
and profile action where appropriate. Never social.

### `LibraryStat`

Compact summary for liked songs, playlists, albums, artists, downloads
where supported. Informational, not gamified.

### `ProfileSection`

Groups related settings/navigation.

### `SettingsRow`

Leading icon where useful, title, optional supporting text/value,
chevron or switch.

### `SettingsSection`, `ToggleRow`, `SliderRow`, `SelectionRow`

Reusable settings primitives. Exact feature availability remains
product-dependent.

------------------------------------------------------------------------

# 12. Mini Player Design Contract

The Mini Player is finalized as a **system-level component**, not a
screen-specific design.

  -----------------------------------------------------------------------
  Playback state                      Visual behavior
  ----------------------------------- -----------------------------------
  Playing                             Normal artwork/title hierarchy +
                                      pause + progress

  Paused                              Same composition + play

  Buffering                           Same composition + restrained
                                      loading cue

  Transitioning                       Maintain surface continuity while
                                      track identity changes

  Error / Unavailable                 Calm failure state + recovery where
                                      supported

  No Active Track                     Minimal Sonara identity/neutral
                                      empty state
  -----------------------------------------------------------------------

Architecture alignment:

-   `SonaraPlaybackService` owns ExoPlayer.
-   `MediaSessionService` is the service boundary.
-   `MediaController` is the UI bridge.
-   Media3 is authoritative for playback state.
-   Queue mutation is through controller commands.
-   Restored sessions enter `PAUSED`.
-   Rapid commands use last-writer-wins/structured cancellation.
-   Stream resolution is outside UI.

The Mini Player must remain coherent through navigation, lifecycle
changes, background playback, restoration, buffering, recovery, and
transitions.

------------------------------------------------------------------------

# 13. Profile / Account Design Contract

Approved visual hierarchy:

``` text
Profile Home
    ↓
Library Summary
    ↓
Profile Navigation Sections
    ↓
Settings Home
    ├── Playback
    ├── Appearance
    └── Data & Storage
```

This is a visual/navigation pattern, not a guarantee that every
demonstrated setting is an implemented feature.

Sonara is open-source/local-first. Do not add commercial accounts, paid
plans, advertisements, followers/following, social feeds, creator
monetization, or cloud account dashboards.

------------------------------------------------------------------------

# 14. Light / Dark Theme Contract

## Dark

Deep Petrol, warm Bone text, restrained elevated Petrol, Oxide
interaction, quiet borders, atmospheric artwork framing.

## Light

Warm Bone foundation, restrained warm surfaces, dark neutral text, Oxide
interaction, subtle structural borders.

Light Mode is not a simple inversion of Dark Mode. Neither mode uses
generic purple/indigo/cyan/neon identity colors.

------------------------------------------------------------------------

# 15. Universal State & Feedback Model

Core vocabulary:

-   Default
-   Pressed
-   Focused
-   Selected
-   Disabled
-   Loading
-   Error
-   Success
-   Active
-   Unavailable

Preferred feedback hierarchy:

1.  geometry/state change;
2.  icon/control change;
3.  restrained tonal change;
4.  accent;
5.  explanatory text.

For playback, distinguish user intent from actual playback state. The
architecture separates user intent, transition, resolution, player
state, and audio output.

------------------------------------------------------------------------

# 16. Loading / Offline / Buffering / Error

The UI must accommodate real asynchronous behavior.

### Loading

Preserve useful cached content where possible and show contextual
progress.

### Buffering

Use a restrained playback cue; do not resemble a permanent error.

### Network conditions

The networking specification recognizes healthy, degraded, intermittent,
unusable, provider-unavailable, and request-specific failure. UI should
expose technical details only when user action/understanding requires
them.

### Error

Communicate what failed, whether the user can continue, and whether
recovery is available. Do not expose provider internals.

### Empty

Use calm typography and a meaningful next action where applicable.

------------------------------------------------------------------------

# 17. Motion Principles

Motion supports orientation, continuity, hierarchy, playback state,
navigation, and emotional connection.

Motion must not delay interaction, obscure information, become
decorative spectacle, or be required to understand state.

Categories:

-   micro interaction;
-   navigation transition;
-   Mini → Expanded Player transition;
-   sheet expansion;
-   loading;
-   playback transition.

Exact duration/easing remains implementation-tunable. Respect
reduced-motion settings. Prefer Compose-native animation primitives
rather than unnecessary dependencies.

------------------------------------------------------------------------

# 18. Responsive / Device Rules

### Compact phones

Prioritize artwork/track identity, primary action, readable hierarchy,
thumb-friendly controls, and low secondary density.

### Larger phones

Increase breathing room before simply enlarging every element.

### Tablets / expanded widths

Do not stretch the phone composition indefinitely. Use sensible max
widths, wider gutters, appropriate artwork scaling, and multi-column
composition only when the feature benefits. Web desktop layouts are not
Android tablet templates.

------------------------------------------------------------------------

# 19. Accessibility Contract

Mandatory:

-   comfortable accessible touch targets;
-   content descriptions for meaningful icons;
-   decorative images excluded from announcements;
-   stateful controls expose current state;
-   logical screen-reader order;
-   text scaling support;
-   validated contrast;
-   reduced-motion support;
-   logical focus order;
-   playback state understandable without color;
-   accessible original and Romanized lyrics.

The active lyric must be semantically identifiable. Playback controls
must expose meaningful labels and state.

------------------------------------------------------------------------

# 20. Android / Compose Implementation Mapping

The system maps to the planned stack without introducing new
dependencies.

### Theme

Centralize semantic colors, typography, shapes, dimensions, and Material
3 configuration.

### Color tokens

Feature screens consume semantic roles rather than raw hex values.

### Typography

Centralize semantic text styles.

### Shapes

Centralize approved shape families.

### Dimensions

Centralize spacing/common dimensions.

### CompositionLocal

Use only when a value genuinely represents ambient design context and
cannot naturally be passed as a parameter. Never use it as a substitute
for data/state flow.

### Components

Reusable components accept semantic state/data and callbacks. They do
not resolve repositories, Media3 objects, or Android services.

------------------------------------------------------------------------

# 21. Design System → Folder Mapping

The project remains a **single `:app` module** with strict internal
package boundaries aligned with **PRE_IMPLEMENTATION_AUDIT_01 Strategy C**.

A practical mapping is:

``` text
com.example.sonara/                          (Production package name OPEN DECISION)
├── core/
│   ├── ui/                                  (Universal Design Tokens & Generic Primitives)
│   │   ├── theme/                           (Color.kt, Type.kt, Shape.kt, Dimensions.kt, Theme.kt)
│   │   └── components/                      (SonaraButton, SonaraSurface, SonaraDivider, SonaraLoadingIndicator, SonaraErrorBanner, SonaraEmptyState)
│   ├── network/
│   └── error/
├── feature/                                 (UI Layer - Screens, ViewModels, Feature Components)
│   ├── navigation/                          (SonaraNavGraph.kt, Screen.kt)
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   ├── HomeViewModel.kt
│   │   └── components/                      (Home-specific UI components)
│   ├── search/
│   │   ├── SearchScreen.kt
│   │   ├── SearchViewModel.kt
│   │   └── components/                      (Search-specific UI components)
│   ├── library/
│   │   ├── LibraryScreen.kt
│   │   ├── LibraryViewModel.kt
│   │   └── components/                      (Library-specific UI components)
│   ├── player/
│   │   ├── PlayerSheet.kt
│   │   ├── MiniPlayer.kt
│   │   ├── PlayerViewModel.kt
│   │   └── components/                      (PlaybackControls, Scrubber, Artwork display)
│   ├── queue/
│   │   ├── QueueSheet.kt
│   │   ├── QueueViewModel.kt
│   │   └── components/                      (QueueListItem, DragHandle)
│   ├── lyrics/
│   │   ├── LyricsSheet.kt
│   │   ├── LyricsViewModel.kt
│   │   └── components/                      (LyricsLineItem, RomanizationToggle, AutoScrollBox)
│   └── settings/
│       ├── SettingsScreen.kt
│       ├── SettingsViewModel.kt
│       └── components/                      (SettingsSection, ToggleRow, SliderRow)
├── domain/                                  (Pure Kotlin Business Rules & Models)
├── data/                                    (Repositories & Persistence)
├── playback/                                (Media3 Playback Subsystem - Service authority)
└── di/                                      (Dependency Injection)
```

Ownership is the authoritative rule:

### Shared UI (`core.ui`)

Theme (`core.ui.theme`), semantic tokens, and universal generic primitives
(`core.ui.components` such as `SonaraButton`, `SonaraSurface`, `SonaraDivider`,
`SonaraLoadingIndicator`, `SonaraErrorBanner`, `SonaraEmptyState`). Universal
primitives have zero feature or ViewModel dependencies.

### Feature UI (`feature.<name>`)

Screen composition, feature state, feature ViewModels, and feature-specific
components (`feature.<name>.components`). Lyrics display and line tracking
belong to `feature.lyrics`; MiniPlayer, playback controls, and player sheets
belong to `feature.player`; queue items belong to `feature.queue`; Settings
rows belong to `feature.settings`. Feature components compose `core.ui`
primitives without dumping feature-specific widgets into `core.ui`.

### Domain

Pure Kotlin business rules/models. No `android.*`, `androidx.*`, or
Compose imports.

### Data

Repositories, local persistence, providers/adapters, DTO/serialization
boundaries, data sources. Repositories remain single sources of truth.

### Playback

MediaSessionService, ExoPlayer ownership, controller boundary,
queue/transition machinery, stream refresh, playback clock.

------------------------------------------------------------------------

# 22. State Ownership Implications for UI

  -----------------------------------------------------------------------
  State                               Owner
  ----------------------------------- -----------------------------------
  Actual playback state               Media3 / `SonaraPlaybackService`

  Playback UI projection              Player ViewModel / UI projection

  High-frequency playback clock       Dedicated clock mechanism /
                                      isolated leaf consumers

  Feature state                       Feature ViewModel + Repository

  Persistent library data             `LibraryRepository` / Room

  Search                              `SearchRepository` + feature
                                      ViewModel

  Lyrics data                         `LyricsRepository`

  Theme/preferences                   Settings repository / approved
                                      DataStore mechanism

  Session snapshot                    Proto DataStore

  Transient UI state                  Composable/ViewModel scope as
                                      appropriate
  -----------------------------------------------------------------------

No screen component may create an independent global playback source of
truth.

------------------------------------------------------------------------

# 23. Design System Decisions vs Product Requirements

This distinction is mandatory.

**Design system:** "Mini Player active playback uses Oxide."\
**Product/architecture:** "Mini Player supports queue access."

**Design system:** "Settings rows use a restrained
icon/title/value/chevron hierarchy."\
**Product:** "Sonara exposes Crossfade."

**Design system:** "Lyrics active line receives stronger emphasis."\
**Product:** "Sonara provides synchronized lyrics and Romanization."

Visual concepts must never cause Antigravity to invent product
capabilities.

------------------------------------------------------------------------

# 24. Implementation Guardrails

## MUST

-   use semantic design tokens;
-   use shared components;
-   preserve Petrol/Bone/Oxide relationships;
-   support intentional Dark/Light themes;
-   maintain accessible touch targets;
-   reuse one Mini Player system;
-   keep playback UI as a projection of Media3/session state;
-   isolate high-frequency playback position;
-   preserve artwork without recoloring;
-   keep feature UI independent from repositories and Media3 internals;
-   preserve Android back/predictive-back compatibility;
-   validate contrast on physical devices;
-   respect text scaling and reduced motion;
-   keep design tokens independent of product feature invention.

## SHOULD

-   prefer tonal hierarchy over effects;
-   prefer existing components before creating new ones;
-   keep screens calm and readable;
-   reuse established spacing and typography;
-   prefer platform-native Compose behavior;
-   use standard Material icons for ordinary functions;
-   keep error/loading states contextual;
-   keep secondary actions subordinate;
-   test visual density on the primary physical Android device.

## MUST NOT

-   introduce random colors;
-   introduce purple/indigo/cyan/neon identity colors;
-   create feature-specific copies of core components;
-   hardcode screen-specific visual tokens everywhere;
-   redesign the Mini Player independently per screen;
-   turn every element into a card;
-   use gradients/glows merely for decoration;
-   use Web desktop geometry as an Android template;
-   put repositories/databases/Media3/networking inside composables;
-   create a duplicate global playback state tree;
-   make high-frequency position a root-screen recomposition source;
-   treat generated concept images as unrestricted product requirements;
-   add commercial accounts, social feeds, subscriptions, or ads.

------------------------------------------------------------------------

# 25. Relationship to Existing Architecture

## Phase 4A

Follows Android-first Kotlin/Compose/Material 3 foundations,
Petrol/Bone/Oxide direction, native interaction, and avoidance of
premature multi-module complexity.

## Phase 4B-1

Respects ExoPlayer ownership inside `SonaraPlaybackService`,
MediaSessionService boundary, MediaController UI bridge, centralized
queue authority, StreamResolver boundary, ContinuityEngine domain
boundary, and Media3 playback-state authority.

## Phase 4B-2

Respects pragmatic layered UDF, immutable UI-state projection,
lifecycle-aware collection, isolated high-frequency position,
state-driven effects, and UI/data separation.

## Phase 4B-3

Respects repository single sources of truth, Room for relational local
library/history, DataStore for preferences, Proto DataStore for session
snapshots, ephemeral stream URLs, and provider fallback outside UI.

## Phase 4C

Accommodates degraded/intermittent connectivity, provider failures,
cancellation, bounded recovery, cached partial data, and restrained
user-facing network communication.

## Phase 4D

Accommodates deterministic rapid intent, transitions/recovery,
buffering, stream failure, audio focus interruptions, cold-start
restoration, and suppression of inappropriate automatic recovery after
explicit pause.

## Pre-Implementation Audit 01

Fits the single-module/package-isolated model and separates shared UI
from domain/data/playback ownership.

## Web → Android asset/portability findings

Web is treated as visual DNA, brand reference, and engineering lesson
source---not as Android DOM/CSS truth. Dynamic artwork remains runtime
content and should follow the approved Android image/data strategy.

------------------------------------------------------------------------

# 26. Known Contradictions / Review Register

## C-01 --- SDK version baseline --- RESOLVED

**Authoritative baseline:** `minSdk 26`, `targetSdk 35`, `compileSdk 35` (frozen in Phase 4A and confirmed in Pre-Implementation Audit 01).\
**Note:** The previously observed `minSdk 37` in the project skeleton was an unconfigured template artifact, not an architectural decision.

**Action:** Target `minSdk 26`, `targetSdk 35`, `compileSdk 35` during Jetpack Compose implementation.

**Impact:** Fully compatible with Material 3, dynamic theming, edge-to-edge, and accessibility APIs.

**Status:** RESOLVED.

## C-02 --- Light Secondary Surface discrepancy --- NON-BLOCKING

**Current Point 3 brief:** `#E2D8CF`.\
**Earlier reference:** `#E2DBCF`.

**Action:** Keep `#E2D8CF` as the current candidate because it is in the
current specification; confirm during visual token implementation.

**Status:** OPEN / implementation review.

## C-03 --- Networking client naming --- NON-BLOCKING

Some architecture material describes OkHttp + Kotlinx Serialization as
the preferred networking stack, while another repository document says
exact client selection may remain deferred.

**Rule:** This design system does not freeze a networking library
choice. UI remains networking-agnostic.

**Status:** Intentionally implementation-tunable / architecture-owned.

------------------------------------------------------------------------

# 27. Design Decision Register

  ----------------------------------------------------------------------------------------------------------------
  Area                    Status                               Decision
  ----------------------- ------------------------------------ ---------------------------------------------------
  Petrol/Bone/Oxide       **FROZEN**                           Brand identity
  identity                                                     

  Dark/Light parity       **FROZEN**                           Both first-class

  Oxide accent usage      **FROZEN**                           Selective interaction/playback accent

  Artwork treatment       **FROZEN**                           Never recolor/tint artwork

  Typography family       **FROZEN**                           Android system Roboto/Variable Font foundation

  Typography exact sizes  **IMPLEMENTATION-TUNABLE**           Tune within semantic hierarchy

  Spacing philosophy      **FROZEN**                           4dp base scale

  Exact spacing values    **IMPLEMENTATION-TUNABLE**           Validate on devices

  Shape language          **FROZEN**                           Restrained soft radii

  Exact radii             **IMPLEMENTATION-TUNABLE**           Device/component tuning

  Surface hierarchy       **FROZEN**                           Tonal first, restrained elevation

  Exact elevation         **IMPLEMENTATION-TUNABLE**           Device validation

  Icon language           **FROZEN**                           Native functional icons + approved Sonara assets

  Mini Player model       **FROZEN**                           One shared persistent system

  Mini Player exact       **VISUAL-FROZEN /                    Preserve approved visual intent
  geometry                IMPLEMENTATION-TUNABLE INTERNALLY**  

  Mini Player states      **FROZEN**                           Playing/Paused/Buffering/Transitioning/Error/None

  Profile hierarchy       **FROZEN VISUAL DIRECTION**          Profile → Library → Settings

  Profile feature list    **PRODUCT-DEPENDENT**                Validate against requirements

  Dark raw tokens         **FROZEN CANDIDATE**                 Current Point 3 values

  Light raw tokens        **FROZEN CANDIDATE**                 Current Point 3 values

  Error/success/warning   **IMPLEMENTATION-TUNABLE**           Accessibility + identity compatibility
  exact colors                                                 

  Motion philosophy       **FROZEN**                           Calm, useful, accessible

  Motion timing/easing    **IMPLEMENTATION-TUNABLE**           Tune during implementation

  Accessibility contract  **FROZEN**                           Mandatory

  Compose theme mapping   **FROZEN PRINCIPLE**                 Centralized semantic tokens

  CompositionLocal usage  **IMPLEMENTATION-TUNABLE**           Only when justified

  Package ownership       **FROZEN PRINCIPLE**                 Shared UI vs feature vs domain/data/playback

  Gradle/module structure **ARCHITECTURE-OWNED**               Single `:app` currently recommended

  SDK baseline            **FROZEN**                           minSdk 26 / targetSdk 35 / compileSdk 35 (Phase 4A / Audit 01)

  Exact networking        **NOT YET DECIDED /                  Design system agnostic
  library                 ARCHITECTURE-OWNED**                 

  Offline storage policy  **PRODUCT/ARCHITECTURE-DEPENDENT**   Do not infer from visual concepts
  ----------------------------------------------------------------------------------------------------------------

------------------------------------------------------------------------

# 28. Self-Audit

## 28.1 Contradictory color definitions

**Result:** One discrepancy found and recorded: Light Secondary Surface
`#E2D8CF` vs earlier `#E2DBCF`.

## 28.2 Inconsistent spacing rules

**Result:** No contradiction. A single 4dp base scale is defined.

## 28.3 Duplicate component definitions

**Result:** No duplicate ownership intended. `SonaraSurface` is generic;
`SonaraCard` is a semantic specialization; feature components compose
shared primitives.

## 28.4 Feature-specific components assigned to shared UI

**Result:** Guarded. Generic primitives are shared; lyrics
synchronization, playback composition, Profile composition, and other
feature behavior remain feature-owned.

## 28.5 Visual concepts becoming product requirements

**Result:** Explicitly separated through design-system vs
product-requirement rules.

## 28.6 Mini Player consistency

**Result:** Consistent. One component, six states,
MediaController/session projection, isolated high-frequency position.

## 28.7 Profile consistency

**Result:** Consistent. Personal/local, not commercial/social; settings
remain grouped and nested.

## 28.8 Light/Dark consistency

**Result:** Consistent. Shared semantic roles and Oxide identity with
intentionally different environmental surfaces.

## 28.9 Accessibility coverage

**Result:** Covered. Touch targets, semantics, contrast, text scaling,
reduced motion, focus, state communication, lyrics, and playback
controls are included.

## 28.10 Compatibility with Phase 4A--4D

**Result:** Fully compatible. The SDK baseline is resolved to `minSdk 26 / targetSdk 35 / compileSdk 35` as established by Phase 4A and Pre-Implementation Audit 01.

------------------------------------------------------------------------

# 29. Freeze Gate

This document is **READY TO FREEZE**.

1.  **SDK baseline:** **RESOLVED** (`minSdk 26`, `targetSdk 35`, `compileSdk 35` per Phase 4A / Audit 01).
2.  **Light Secondary Surface:** **CONFIRMED** (`#E2D8CF` per Point 3 candidate).
3.  **onAccent Contrast:** **CONFIRMED** (`#F6F2EA` / `#FFFFFF` on Oxide `#A25A3A`).
4.  **Package Alignment:** **CONFIRMED** (Aligned with Audit 01 Strategy C `core.ui` + `feature.<name>.components`).

This document serves as the authoritative design-system contract for Sonara Android Jetpack Compose implementation.

**No Kotlin, Compose, Gradle, project-folder, or frozen architecture
document was modified by this specification.**
