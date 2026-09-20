# SONARA ANDROID — DESKTOP MINI PLAYER PARITY & RESPONSIVE ADAPTATION SPECIFICATION
## Authoritative Web Desktop Translation Contract & Pre-Implementation Audit

**Document:** `DESKTOP_MINIPLAYER_ANDROID_PARITY_SPECIFICATION.md`  
**Status:** **AUTHORITATIVE PRE-IMPLEMENTATION SPECIFICATION (CORRECTED PASS)**  
**Visual Authority:** Sonara Web Desktop Production Implementation (`music-player/src/components/audio/`)  
**Platform Target:** Android Jetpack Compose (minSdk 26 / targetSdk 35)  

---

## 1. Primary Implementation Principle

> ### **"WEB DESKTOP VISUAL AUTHORITY, RESPONSIVE ANDROID GEOMETRY."**

The Sonara Web Desktop Mini Player is the **sole visual and structural authority** for this implementation pass. The Android implementation must faithfully reproduce its:
- **Two-tier vertical topology** (Progress Tier strictly above Main Grid Tier).
- **Floating capsule silhouette** with generous corner radius and floating margins.
- **Progress-above-main-content spatial relationship**.
- **Artwork + metadata visual composition** (square rounded artwork with elevation depth).
- **Grouped transport capsule** (visually bonded pill grouping playback controls).
- **Clear utility hierarchy** (hairline divider separating transport from secondary actions).
- **Visual density and spacing rhythm**.
- **Material and atmospheric depth** (translucent surface, top specular edge, drop shadow, ambient sentiment wash).
- **Optical dominance** of the central Play/Pause control.

**Non-Negotiable Scope Boundary:**  
Under no circumstances should this work be interpreted as a redesign of the Mini Player for mobile. Responsive adaptation is permitted **only** when the Web Desktop geometry cannot physically fit within the available Android screen width. When width becomes constrained, secondary controls, internal spacing, artwork scale, and metadata width may reduce **before** the core two-tier composition is altered. The player must **never** collapse into the legacy single-row Android player.

> *Pixel-for-pixel CSS numerical copying is NOT the objective. Visual proportion, hierarchy, silhouette, spacing rhythm, and control relationships are the true objective.*

---

## 2. Mandatory Implementation Prerequisite

Before modifying any Android code, the implementation agent **MUST** re-inspect the authoritative Web Desktop source files listed in this document:
- [`AudioPlayer.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/AudioPlayer.jsx) (specifically lines 705–788: `<div className="ap-desktop-layout">`)
- [`AudioPlayer.css`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/AudioPlayer.css) (desktop layout rules and material definitions)
- [`SongInfo.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/SongInfo.jsx) & [`SongInfo.css`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/SongInfo.css)
- [`PlaybackProgress.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/PlaybackProgress.jsx)
- [`PlaybackControls.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/PlaybackControls.jsx)
- [`ExpandedPlayerTrigger.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/ExpandedPlayerTrigger.jsx)
- [`VolumeControl.jsx`](file:///D:/COLLEGE%20WORK/MY%20PROJECTS/WEB%20DEV/Music%20Streaming%20Web%20Application%20%5B%20REACT%5D%20%20-%20Melodify/react%20final%20project/music-player/src/components/audio/VolumeControl.jsx)

The implementation agent must not rely exclusively on secondary summaries if the actual Web source is available. Visual fidelity to the production Web Desktop application is paramount.

---

## 3. Coordinated Visual Scope Separation

This specification governs two coordinated visual parity updates within the Sonara UI shell:

1. **Desktop Mini Player Visual Parity & Responsive Adaptation**:
   - Scoped strictly to `feature.player.components.MiniPlayer` (layout, artwork, metadata, progress, transport capsule, utilities, sentiment wash, responsive geometry, entrance motion).
2. **Web Theme Toggle Wave-Ripple Parity**:
   - Scoped strictly to `feature.shell.SonaraTopBar` and root-level theme canvas transition in `SonaraAppRoot` (full-screen circular reveal, 1200ms duration, overshoot easing, origin tracking, reduced-motion bypass).

These two components are **strictly decoupled**. No theme-transition shader or overlay code may be placed inside `MiniPlayer.kt`, and no playback logic may be embedded inside `SonaraTopBar.kt`.

---

## 4. Web Desktop Geometry Contract & Value Classification

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│  0:42  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━●──────────────────────────────────────────────  3:18       │  <-- Tier 1: Full-Width Progress
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ┌──────┐  Make You Feel My Love                ┌───────────────────────────────┐ │ ☵  🔈  ⤢    │  <-- Tier 2: Asymmetric Grid
│ │ 60dp │  Adele • 19                           │  🔀   ⏮   ( ▶ )   ⏭   🔁  │ │              │
│ └──────┘                                       └───────────────────────────────┘ │              │
│  Artwork        Metadata (Fluid Left)                 Transport Capsule          │ Utilities    │
│                                                       (Optical Center/Right)     │ (Right)      │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Classification Scheme:
- **`WEB-EXACT`**: Visual property directly reproduced from Web where platform-independent.
- **`WEB-DERIVED`**: Translated proportionally from Web CSS into Compose `dp`/`sp`.
- **`ANDROID-ADAPTED`**: Deliberately adapted due to Android screen width constraints, touch targets ($48\text{dp}$ touch bounds), Compose layout mechanics, or accessibility scaling.

| Property | Web Desktop Value | Sizing Classification | Android Compose Translation | Rationale / Adaptation Rule |
|---|---|---|---|---|
| **Layout Hierarchy** | 2-Tier Stack | `WEB-EXACT` | `Column` hosting Progress `Row` + Grid `Row` | Core signature identity; never compromised. |
| **Player Silhouette** | Pill capsule (`44px` radius) | `WEB-DERIVED` | `RoundedCornerShape(24.dp)` | Scaled proportionally for mobile viewport width. |
| **Outer Margins** | Floating `bottom: 28px` | `ANDROID-ADAPTED` | Horizontal `8–16dp`, bottom `6dp` above BottomNav | Fits Android screen boundaries and bottom navigation bar. |
| **Internal Padding** | `padding: 10px 20px` | `WEB-DERIVED` | `horizontal = 14–16dp, vertical = 8–10dp` | Calibrated internal breathing room. |
| **Artwork Form** | Square rounded | `WEB-EXACT` | `RoundedCornerShape(8.dp)` | Direct port of Web artwork shape. |
| **Artwork Size** | `60px` $\times$ `60px` | `WEB-DERIVED` / `ANDROID-ADAPTED` | `60.dp` (Large/Tablet), `56.dp` (Normal), `48.dp` (Compact) | Preserves square proportion while adapting to constrained widths. |
| **Artwork Shadow** | `0 8px 24px rgba(0,0,0,0.25)` | `WEB-EXACT` | `Modifier.shadow(8.dp, RoundedCornerShape(8.dp))` | Provides physical elevation above translucent glass surface. |
| **Title Typography** | `15px`, Bold (`700`), `-0.015em` | `WEB-EXACT` | `15.sp`, `FontWeight.Bold`, single-line with ellipsis | Replicates Web primary text hierarchy. |
| **Artist Typography** | `13px`, Medium (`500`), opacity `0.85` | `WEB-EXACT` | `13.sp`, `FontWeight.Medium`, `secondaryText.copy(alpha = 0.85f)` | Replicates Web secondary text emphasis. |
| **Album Typography** | `12px`, Regular (`400`), opacity `0.75` | `WEB-EXACT` | `12.sp`, `FontWeight.Normal`, `secondaryText.copy(alpha = 0.75f)` | Tertiary background metadata. |
| **Metadata Row Order** | Title $\to$ Artist • Album | `WEB-EXACT` | Column: Title + Row(Artist + dot + Album) | Preserves horizontal metadata relationship. |
| **Progress Tier** | Left Time + Slider + Right Time | `WEB-EXACT` | Row with `caption` text + `Slider` + `caption` text | Dedicated top scrubber with live elapsed/duration timestamps. |
| **Transport Capsule** | Pill (`rgba(128,128,128,0.08)`) | `WEB-EXACT` | `Box` with `CircleShape` & `surfaceVariant.copy(0.5f)` | Visually bonds transport buttons into a single unit. |
| **Play/Pause Button** | Circular filled accent (`50%` radius) | `WEB-EXACT` | `SonaraIconButton` (`Variant.Accent`, `34–36dp`) | Visually dominant primary interaction anchor. |
| **Prev/Next Buttons** | Ghost buttons (`opacity: 0.75`) | `WEB-EXACT` | `SonaraIconButton` (`Variant.Ghost`, `28–30dp`) | Subordinate transport controls flanking Play/Pause. |
| **Utility Divider** | `1px` $\times$ `20px` vertical line | `WEB-EXACT` | `Box(Modifier.width(1.dp).height(20.dp).background(divider))` | Visual separation between transport and utilities. |
| **Expand Trigger** | `PiCornersOut` / `⤢` button | `WEB-EXACT` | `SonaraIconButton` (`Variant.Ghost`, `32dp`) | Explicit navigation trigger to open Lyrics / Player sheet. |
| **Sentiment Wash** | Radial gradient wash at bottom | `WEB-EXACT` | `Brush.radialGradient` with `accent.copy(alpha = 0.12f)` | Dynamic ambient lighting behind player elements. |

---

## 5. Responsive Degradation Priority

When screen width is constrained, UI elements must degrade in a strict, deterministic priority order. **Secondary information and optional controls must yield space before core composition is altered.**

```text
Priority 1 (NEVER REMOVE)  ───►  Priority 2 (MAY REDUCE)  ───►  Priority 3 (MAY REMOVE)
──────────────────────────────────────────────────────────────────────────────────────────
• Two-tier structure             • Artwork 60dp → 56dp → 48dp   • Album text
• Tier 1 progress row            • Metadata available width      • 1px utility divider
• Artwork + metadata unit        • Internal padding / gaps       • Secondary controls
• Play/Pause button              • Button sizes (min 48dp touch)   (Shuffle, Repeat, Queue)
• Previous / Next buttons                                        • Volume slider track
• Expand action button
• Transport capsule grouping
• No-overlap guarantee
```

---

## 6. Functional Control Policy (Zero Decorative Controls)

> ### **RULE: Never render a playback control solely because sufficient screen width exists.**

1. **Active Core Controls**:
   - **Play / Pause**: 100% backed by `MediaControllerClient` / `PlayerViewModel`.
   - **Previous / Next**: 100% backed by `MediaControllerClient` (`skipToPrevious` / `skipToNext`).
   - **Progress Scrubbing**: 100% backed by `PlayerViewModel.seekToProgress(float)`.
   - **Expand Action**: 100% backed by `showLyricsSheet = true`.
2. **Secondary Controls Policy (Shuffle, Repeat, Queue, Volume)**:
   - If an underlying playback capability (e.g. Queue list, Repeat mode toggle, Shuffle mode toggle) is not yet wired to a live backend in the current Android vertical slice, **it MUST NOT be rendered as a dead or decorative placeholder**.
   - The responsive layout must gracefully allocate space solely to active, functional controls, while reserving structural capacity to display secondary controls on wide screens once their backend support is connected.
   - Volume on Android mobile is primarily handled via physical hardware volume keys; a dedicated on-screen slider is reserved for wide screens ($\ge 600\text{dp}$).

---

## 7. Responsive Width Strategy & Breakpoint Specification

```text
Compact (<360dp)            Normal (360–411dp)             Large / Tablet (≥412dp)
┌──────────────────────┐    ┌─────────────────────────┐    ┌───────────────────────────────┐
│ 0:42 ━━━━●━━━━━ 3:18 │    │ 0:42 ━━━━━━●━━━━━━ 3:18 │    │ 0:42 ━━━━━━━━━━●━━━━━━━━ 3:18 │
│ [48] Title     (▶) ⤢ │    │ [56] Title • Art [(▶)]⤢ │    │ [60] Title • Art [⏮ (▶) ⏭] ☵ ⤢ │
└──────────────────────┘    └─────────────────────────┘    └───────────────────────────────┘
```

### Breakpoint Matrix

| Width Class | Screen Width ($W$) | Player Insets | Artwork Scale | Metadata Strategy | Transport Capsule | Utility Zone | Width Adaptation Rationale |
|---|---|---|---|---|---|---|
| **Compact** | $320\text{dp} \le W < 360\text{dp}$ | `8dp` horizontal, `6dp` bottom | **`48.dp`** | Title (14sp Bold) + Artist (12sp); single line truncated | 3-Button Core: Prev (`24dp`) + Play (`32dp`) + Next (`24dp`) in capsule | Expand button (`28dp`). Divider hidden. | Preserves 2-tier structure completely; scales artwork to 48dp to protect text readability. |
| **Normal** | $360\text{dp} \le W < 412\text{dp}$ | `12dp` horizontal, `6dp` bottom | **`56.dp`** | Title (15sp Bold) + Row: Artist (13sp) • Album (12sp) | 3-Button Core: Prev (`28dp`) + Play (`36dp`) + Next (`28dp`) in capsule | 1px hairline divider + Expand button (`32dp`). | Baseline Android phone experience. 100% faithful to Web Desktop proportions. |
| **Large** | $412\text{dp} \le W < 600\text{dp}$ | `16dp` horizontal, `8dp` bottom | **`60.dp`** | Title (15sp Bold) + Row: Artist (13sp) • Album (12sp) | 3-Button or Extended 5-Button Capsule | 1px divider + (Queue if wired) + Expand button (`32dp`). | Full 60dp artwork; generous metadata breathing room. |
| **Tablet+** | $W \ge 600\text{dp}$ | Max-width `720dp` centered, `24dp` margin | **`60.dp`** | Full Title + Artist • Album with zero truncation | Full 5-Button Capsule (Shuffle + Prev + Play + Next + Repeat) | 1px divider + Queue + Volume slider + Expand. | 1:1 identical to full Web Desktop layout. |

---

## 8. Constraint-Based Compact Width Model ($320\text{dp}$ Minimum)

The compact layout ($320\text{dp}$) must be handled using **fluid Compose constraints and weight modifiers**, not fragile hard-coded pixel widths:

1. **Non-Overlapping Constraint**: Metadata uses `Modifier.weight(1f)` to naturally absorb width variance, ensuring text truncates with ellipsis before touching transport controls.
2. **Slider Elasticity**: The progress slider in Tier 1 occupies `Modifier.weight(1f)` between fixed-width timestamp labels ($32–36\text{dp}$).
3. **Font Scale Resilience**: The layout must safely tolerate Android Accessibility font scaling ($1.0x \to 1.3x$) without clipping controls or breaking the 2-tier container bounds.
4. **Touch Target Integrity**: Even when visual icon glyphs scale down on compact widths ($24\text{dp}$ visual), their interactive touch targets must satisfy Android accessibility guidelines ($\ge 48\text{dp}$ hit bounds via `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` or standard IconButton padding).

---

## 9. Player Positioning & Insets Strategy

```text
┌─────────────────────────────────────────────────────────┐
│                      Screen Content                     │
│               (HomeScreen / Search / Library)           │
│                                                         │
├─────────────────────────────────────────────────────────┤
│    ┌───────────────────────────────────────────────┐    │  <-- Floating MiniPlayer
│    │  0:42  ━━━━━━━━━━━━●━━━━━━━━━━━━━━━━  3:18    │    │      (Rounded pill, 12dp margin)
│    │  [Art] Title / Artist     [ (⏮) (▶) (⏭) ]  ⤢ │    │
│    └───────────────────────────────────────────────┘    │
│                                                         │  <-- 6dp floating separation gap
│  ┌───────────────────────────────────────────────────┐  │
│  │     ⌂ Home          ⚲ Search        ☵ Library     │  │  <-- SonaraBottomNavBar (Solid)
│  └───────────────────────────────────────────────────┘  │
│                      Gesture Inset                      │
└─────────────────────────────────────────────────────────┘
```

1. **Scaffold BottomBar Integration**: `MiniPlayer` is placed in a `Column` directly above `SonaraBottomNavBar` inside `Scaffold.bottomBar`.
2. **Automatic InnerPadding Accounting**: `Scaffold` automatically calculates `innerPadding` to encompass the combined height of the floating `MiniPlayer` ($\sim 96–104\text{dp}$) and `SonaraBottomNavBar` ($64\text{dp}$), ensuring screen content in `HomeScreen`, `SearchScreen`, and `LibraryScreen` is never occluded.
3. **Cross-Tab Persistence**: `MiniPlayer` remains mounted across navigation destination switches without flickering or re-animating.

---

## 10. Theme Toggle Wave-Ripple Motion Translation

As frozen in `WEB_THEME_TOGGLE_MOTION_SPECIFICATION.md`:

1. **Animation Type**: Radial Wave-Ripple full-viewport circular reveal.
2. **Effective Duration**: `1200ms` (Web `800ms * 1.5x`).
3. **Easing Curve**: `CubicBezierEasing(0.68f, -0.55f, 0.265f, 1.55f)` (anticipatory pull-back with rapid expansion and spring settle).
4. **Origin**: Toggle button coordinates $(x, y)$ or viewport center $(W/2, H/2)$.
5. **Implementation in Compose**:
   - Implemented at `SonaraAppRoot` / shell level via a canvas clip-path or shader layer on theme changes.
   - Zero recomposition or drawing overhead when idle.
6. **Accessibility**: If `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` (`prefers-reduced-motion`), the animation duration drops to `0ms` (instant swap).

---

## 11. Existing Design Token Reuse Matrix

The implementation will strictly reuse existing `SonaraTheme` tokens from `com.example.sonara.core.ui.theme`:

| Token Category | Existing Token | Applied Usage in Desktop Mini Player |
|---|---|---|
| **Colors** | `SonaraTheme.colors.surface` | Base container glass fill (`alpha = 0.92f`) |
| | `SonaraTheme.colors.surfaceVariant` | Transport capsule fill (`alpha = 0.5f`) & artwork placeholder |
| | `SonaraTheme.colors.accent` | Play/Pause button background & progress active track |
| | `SonaraTheme.colors.onAccent` | Play/Pause button icon glyph color |
| | `SonaraTheme.colors.primaryText` | Title text & transport Prev/Next icon color |
| | `SonaraTheme.colors.secondaryText` | Artist text, progress timestamps, & expand icon |
| | `SonaraTheme.colors.divider` | Container border stroke & 1px hairline utility divider |
| **Typography** | `SonaraTheme.typography.trackTitle` | Title baseline style (15sp Bold) |
| | `SonaraTheme.typography.artistMetadata` | Artist baseline style (13sp Medium) |
| | `SonaraTheme.typography.caption` | Progress timestamps (`0:42`, `3:18`) |
| **Dimensions** | `SonaraTheme.dimensions.spaceXs` | 4dp spacing grid |
| | `SonaraTheme.dimensions.spaceSm` | 8dp spacing grid |
| | `SonaraTheme.dimensions.spaceMd` | 12dp spacing grid |
| | `SonaraTheme.dimensions.spaceLg` | 16dp spacing grid |
| **Shapes** | `SonaraTheme.shapes.small` | 8dp artwork corner radius |
| | `SonaraTheme.shapes.circular` | CircleShape for Play button & Transport Capsule |

---

## 12. Architectural Invariants

The following architectural baselines remain strictly frozen:

- ✅ `SonaraPlaybackService` remains the sole, authoritative owner of `ExoPlayer` and `MediaSession`.
- ✅ `MediaControllerClient` remains the client-side UI projection bridge.
- ✅ `MiniPlayer` remains purely a reactive UI projection consuming `PlayerUiState`.
- ✅ Stream URLs remain memory-only and **never** persisted to Room or DataStore.
- ✅ No changes to Room database schemas, DataStore preferences, or domain repositories.
- ✅ Package boundaries: `MiniPlayer` remains in `com.example.sonara.feature.player.components`.

---

## 13. Exact Files Expected to Change in Implementation Pass

1. [`feature/player/components/MiniPlayer.kt`](file:///d:/COLLEGE%20WORK/MY%20PROJECTS/VIBE%20CODED%20PROJECTS/Sonara/app/src/main/java/com/example/sonara/feature/player/components/MiniPlayer.kt)
   - Implement Desktop 2-tier layout, responsive width adaptations ($320\text{dp} \to 600\text{dp}+$ via `BoxWithConstraints`), 56–60dp artwork, transport capsule, progress scrubber, and entrance animation.
2. [`feature/shell/SonaraAppRoot.kt`](file:///d:/COLLEGE%20WORK/MY%20PROJECTS/VIBE%20CODED%20PROJECTS/Sonara/app/src/main/java/com/example/sonara/feature/shell/SonaraAppRoot.kt)
   - Connect `onPrevious = { playerViewModel.skipToPrevious() }` and `onSeek = { playerViewModel.seekToProgress(it) }` to `MiniPlayer`.
   - Host the root-level wave-ripple theme transition overlay.
3. [`feature/shell/SonaraTopBar.kt`](file:///d:/COLLEGE%20WORK/MY%20PROJECTS/VIBE%20CODED%20PROJECTS/Sonara/app/src/main/java/com/example/sonara/feature/shell/SonaraTopBar.kt)
   - Coordinate theme toggle click with full-screen wave-ripple trigger.

*(Additional files may ONLY be modified if the existing architecture strictly requires them for correct wiring, with minimal and justified diffs).*

---

## 14. Strengthened Multi-Width Verification Matrix

The implementation must be verified across the entire width spectrum on Android devices and emulators:

| Verification Width | Required Verification Checks | Pass Criteria |
|---|---|---|
| **`320dp` (Compact)** | Outer margin 8dp; 48dp artwork; Title (14sp) + Artist (12sp); 3-button core capsule; Expand button. | Zero clipping, zero overlap, 2-tier structure fully intact. |
| **`360dp` (Small Phone)** | Outer margin 12dp; 56dp artwork; Title (15sp) + Artist • Album; 3-button capsule; 1px divider; Expand. | Perfect baseline proportions; no text collision. |
| **`412dp` (Standard / Pro)** | Outer margin 16dp; 56–60dp artwork; Title + Artist • Album; 3-button capsule; 1px divider; Expand. | Generous breathing room; full metadata visibility. |
| **`480dp` (Large Phablet)** | Outer margin 16dp; 60dp artwork; full metadata; extended capsule. | All desktop elements spacious and balanced. |
| **`540dp` (Compact Tablet)** | Extended capsule; relaxed spacing. | Full visual fidelity to Web Desktop. |
| **`600dp` (Tablet / Foldable)** | Full 5-button capsule; Queue; Volume slider; Expand. | 1:1 complete Web Desktop parity. |
| **`720dp` (Expanded Tablet)** | Max-width `720dp` centered capsule with floating ambient shadow. | Desktop capsule centered elegantly. |
| **Accessibility Font Scaling** | Verify layout with system font scale at `1.3x`. | Text wraps/ellipsizes cleanly without breaking container height. |

---

## 15. Final Implementation Readiness Decision

**READY FOR DESKTOP MINI PLAYER CORRECTION**  
All architectural, visual, geometric, and responsive requirements have been rigorously verified and corrected against the authoritative Web Desktop baseline and frozen Android contracts.
