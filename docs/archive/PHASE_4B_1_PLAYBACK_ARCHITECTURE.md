# SONARA ANDROID — PHASE 4B-1 PLAYBACK ARCHITECTURE & OWNERSHIP

**Phase**: 4B-1 — First Concrete Architecture Decision
**Status**: COMPLETE — Awaiting External Review and Approval
**Frozen Baseline**: `PHASE_4A_ARCHITECTURE_DISCOVERY.md` (not modified)

---

## 1. Executive Summary

This document makes the first concrete architectural decisions for Sonara Android. It defines the **complete playback architecture** — who owns the player, how commands flow, how state flows, how the system survives lifecycle events, and how each related subsystem (lyrics, continuity, stream resolution, error handling) connects to the playback boundary without violating domain independence.

### Summary of Decisions Made in This Phase

| Area | Decision |
| :--- | :--- |
| Player Ownership | `ExoPlayer` lives exclusively inside `SonaraPlaybackService` |
| Service Boundary | `MediaSessionService` subclass (`SonaraPlaybackService`) |
| Client Bridge | `MediaController` is a **client-side** artifact, owned by the application process |
| Queue Ownership | ExoPlayer's internal `Timeline` is the single source of truth for the active queue |
| Queue Mutation Authority | Centralized through `MediaController` commands; never mutated directly from UI |
| Stream Resolution | Dedicated `StreamResolver`, invoked by the service via `StreamResolverPort` |
| ContinuityEngine | Pure domain — invoked via `ContinuityPort` interface, no Android dependency |
| Audio Focus | Delegated entirely to Media3's built-in `AudioFocusManager` |
| System Controls | All control surfaces route through `MediaSession` |
| State Authority | Media3 authoritative for playback state ONLY; separate repositories own all other domains |
| High-Frequency Position | Architectural requirement established; exact mechanism deferred |
| Rapid Command Model | Last-writer-wins with structured cancellation; single active resolution job slot |
| Session Recovery | Required to survive process death; serialization mechanism deferred |

---

## 2. Tool / MCP / Skill Usage

### 2.1 Tool Audit for Phase 4B-1

| Tool / Resource | Classification | Usage in This Phase |
| :--- | :--- | :--- |
| **`search_web` (developer.android.com)** | ✅ REQUIRED NOW | Verified current Media3 `MediaSessionService`, audio focus, foreground service type, stream URL refresh, process death restoration, `MediaController`/`Player` interface contract, and Compose/StateFlow integration patterns. |
| **`view_file` / Phase 4A baseline** | ✅ REQUIRED NOW | Read frozen Phase 4A document as the established architectural discovery baseline. |
| **`context7`** | 🟡 ATTEMPTED — Tool schema mismatch | Attempted Media3 library resolution; schema validation failed. Fell back to `search_web` for authoritative documentation. |
| **`sequential-thinking`** | ✅ USEFUL — Applied internally | Used for multi-step reasoning across the rapid skip model, queue ownership decision, and state tearing analysis. |
| **`ponytail`** | ✅ USEFUL NOW | Applied throughout to guard against over-engineering (e.g., resisting custom audio focus infrastructure, resisting a DTO layer between `Player.Listener` and `StateFlow`). |
| **`android-cli`** | 🟡 USEFUL (Reference) | Useful for verifying foreground service type declarations when implementation begins. Reserved for Phase 5. |
| **`serena`** | ⏳ RESERVED FOR LATER | AST navigation is not relevant until source code exists. Reserved for Phase 6+. |
| **`stitch`** | ⏳ RESERVED FOR LATER | UI design system tooling; not relevant to playback architecture. |
| **`playwright` / `reticle`** | ❌ NOT RELEVANT | DOM-based tools. Not applicable to native Android. |

### 2.2 Key Documentation Verified

- **Media3 `MediaSessionService`**: Confirmed as the canonical Android mechanism for background audio playback. `ExoPlayer` and `MediaSession` are initialized in `onCreate()` and released in `onDestroy()`. The service manages its own foreground state automatically based on player state.
- **Audio Focus**: Confirmed that Media3 handles audio focus natively when `AudioAttributes` are set with `handleAudioFocus = true`. No custom focus management infrastructure is needed.
- **Stream URL Refresh**: Confirmed that `ResolvingDataSource.Factory` and custom `DataSource` wrappers are the correct mechanism for transparent URL regeneration on HTTP 403/410 without restarting the `MediaItem`.
- **`MediaController` implements `Player`**: Confirmed. `MediaController` implements the full `Player` interface, making it a transparent client-side proxy.
- **Foreground Service Requirements**: Confirmed that Android 14 (API 34) requires `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission and `foregroundServiceType="mediaPlayback"` in the manifest.
- **`media3-ui-compose`**: Confirmed that since Media3 1.6.0+, a dedicated Compose module exists with state holders that bridge `Player` interface to Compose declaratively, reducing manual `StateFlow` translation.

---

## 3. Established Foundations

The following are non-negotiable and are NOT reopened in this phase:

| Foundation | Decision |
| :--- | :--- |
| Language | Kotlin |
| UI Toolkit | Jetpack Compose |
| Design System | Material 3 + Custom Sonara (Petrol / Bone / Oxide) |
| Audio Engine | AndroidX Media3 / ExoPlayer |
| `minSdk` | 26 (Android 8.0 Oreo) |
| `targetSdk` | 35 (Android 15) |
| Architecture Pattern | Pragmatic Layered UDF |
| Single-Module Structure | `:app` with strict package boundaries |
| Web Reference Rule | Inspiration only — not an architectural template |

---

## 4. Playback Architectural Requirements

### 4.1 Functional Requirements Driving Playback Architecture

| Requirement | Source | Architectural Impact |
| :--- | :--- | :--- |
| Background playback (screen off, app hidden) | Requirements V1.2 MVP | Player must live in a foreground service, independent of UI lifecycle |
| Lockscreen / notification transport controls | Requirements V1.2 MVP | `MediaSession` must be published so OS can expose controls |
| Bluetooth AVRCP / headset button handling | Requirements V1.2 MVP | Must route through `MediaSession`, not handled in UI |
| Queue management (add, remove, reorder) | Requirements V1.2 MVP | Queue owned by single authority to prevent race conditions |
| Shuffle & Repeat modes | Requirements V1.2 MVP | State owned by `ExoPlayer`; observable by UI |
| Synced Karaoke Lyrics | Requirements V1.2 MVP | Requires high-frequency position without recomposition storms |
| Continuity Engine (auto-queue) | Requirements V1.2 MVP | Pure domain logic invoked at queue exhaustion, via interface |
| Liked Songs reactive display | Requirements V1.2 MVP | `LibraryRepository` owns; must NOT be conflated with playback state |
| Listening History logging | Requirements V1.2 MVP | Triggered by playback events; written by service to `LibraryRepository` |
| Session restoration after process death | Requirements V1.2 MVP | Active track + position + queue must be recoverable |
| Zero tracking / telemetry | Requirements V1.2 invariant | No analytics SDK may observe playback state |

### 4.2 Anti-Requirements (Explicitly Prevented)

- ❌ UI must never hold an `ExoPlayer` reference.
- ❌ `MediaController` must never live inside the playback service boundary.
- ❌ Playback position must never trigger whole-screen recomposition.
- ❌ Multiple components must never independently mutate the queue.
- ❌ Failed transient network errors must never permanently mark tracks as broken.
- ❌ Stream URL must never be persisted to disk.
- ❌ The `ContinuityEngine` must never import `android.*` or `androidx.*`.

---

## 5. Media3 / Player Ownership

### 5.1 Decision: ExoPlayer Lives Exclusively in `SonaraPlaybackService` ✅ DECIDED

**The `ExoPlayer` instance is created in, lives in, and is released by `SonaraPlaybackService`.**

No UI component (Activity, Fragment, ViewModel, Composable) ever holds a direct reference to the `ExoPlayer` instance. All UI interaction with the player is mediated through the `MediaController` client-side bridge.

### 5.2 Why This Is Correct

1. **Lifecycle independence**: The `ExoPlayer` instance must outlive Activity recreation. Placing it in a `Service` provides this isolation automatically.
2. **Background playback**: Android's foreground service mechanism allows audio to continue when the screen is off or the app is in the background.
3. **System media controls**: The `MediaSession` that exposes controls to the lock screen, notification shade, Bluetooth, and Google Assistant must be associated with a `Service`, not an Activity.
4. **Audio focus**: A single `ExoPlayer` with configured `AudioAttributes` in the service is the only clean audio focus model.
5. **Avoids the `PlayerContext` failure mode**: Sonara Web's `PlayerContext` mixed audio execution with UI state, queue governance, and session in a 570-line monolith. The service boundary is the structural enforcement that prevents this.

### 5.3 Alternatives Rejected

| Alternative | Why Rejected |
| :--- | :--- |
| ExoPlayer in `Activity` or `ViewModel` | Destroyed on configuration change. Cannot survive screen-off or navigation. Cannot expose `MediaSession` to system. |
| ExoPlayer in a plain `Service` (not Media3) | Does not provide `MediaSession`, `MediaNotification`, or system integration. Significant boilerplate with no benefit. |
| Multiple ExoPlayer instances (one per surface) | Catastrophic audio focus conflicts. State tearing between instances. |
| ExoPlayer in a custom foreground `Service` not using Media3 | Re-implements what `MediaSessionService` already provides correctly. |

---

## 6. MediaSessionService Boundary

### 6.1 Decision: `SonaraPlaybackService` Extends `MediaSessionService` ✅ DECIDED

**`SonaraPlaybackService` extends `MediaSessionService`.** This is the complete playback service boundary.

### 6.2 Responsibilities of `SonaraPlaybackService`

| Responsibility | Owner |
| :--- | :--- |
| Create and hold the `ExoPlayer` instance | `SonaraPlaybackService` |
| Create and hold the `MediaSession` | `SonaraPlaybackService` |
| Manage system foreground service lifecycle | `MediaSessionService` base class (automatic) |
| Post and update the `MediaStyle` notification | `MediaSessionService` base class (automatic) |
| Handle `MediaSession.Callback` commands | `SonaraPlaybackService` (via `MediaSession.Callback`) |
| Configure audio attributes and audio focus | `SonaraPlaybackService.onCreate()` |
| Invoke `StreamResolverPort` for URL acquisition | `SonaraPlaybackService` playback orchestration |
| Invoke `ContinuityPort` at queue exhaustion | `SonaraPlaybackService` playback orchestration |
| Write listening history to `LibraryRepository` | `SonaraPlaybackService` (on qualifying track completion) |
| Release `ExoPlayer` and `MediaSession` on destroy | `SonaraPlaybackService.onDestroy()` |

### 6.3 What `SonaraPlaybackService` Does NOT Own

| Concern | Correct Owner |
| :--- | :--- |
| Liked Songs / Library state | `LibraryRepository` |
| Lyrics data and timing | `LyricsRepository` + `NaturalInertiaProcessor` |
| Search results | `SearchViewModel` + `SQE` |
| User preferences (theme, quality) | `SettingsRepository` |
| Continuity scoring logic | `ContinuityEngine` (pure domain) |
| Stream provider logic (YTMusic, JioSaavn) | `StreamResolver` (data layer) |
| UI navigation state | Compose navigation graph |

### 6.4 Android 14+ Platform Requirements for the Service

```
Manifest Requirements (applied in Phase 5 / implementation):
  • android.permission.FOREGROUND_SERVICE
  • android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK (API 34+)
  • Service declaration:
      android:foregroundServiceType="mediaPlayback"
      android:exported="true"
      IntentFilter: "androidx.media3.session.MediaSessionService"

API Compatibility:
  • MediaSessionService API is available from API 21+.
  • FOREGROUND_SERVICE_MEDIA_PLAYBACK permission declaration is required from API 34.
  • minSdk = 26: all Media3 APIs used are available above minSdk.
```

### 6.5 Process Boundary

`SonaraPlaybackService` runs in the **same process** as the application. A separate OS process is **not used**. The "Client / UI boundary" and "Service boundary" are logical ownership boundaries within the same OS process, not separate OS processes.

---

## 7. MediaController Contract

### 7.1 Decision: MediaController Is Client-Side Only ✅ DECIDED

**`MediaController` is constructed in the application/UI process** (in `PlayerViewModel`). It is **not part of `SonaraPlaybackService`'s internal implementation**.

`MediaController` implements the `Player` interface — it is a transparent client-side proxy to the `ExoPlayer` running in the service. Commands sent to `MediaController` are dispatched via Binder IPC to `SonaraPlaybackService`.

### 7.2 Commands That Cross the Service Boundary (UI → Service)

| Command | Via |
| :--- | :--- |
| Play | `MediaController.play()` |
| Pause | `MediaController.pause()` |
| Stop | `MediaController.stop()` |
| Skip to next | `MediaController.seekToNextMediaItem()` |
| Skip to previous | `MediaController.seekToPreviousMediaItem()` |
| Seek to position | `MediaController.seekTo(positionMs)` |
| Set queue | `MediaController.setMediaItems(items)` |
| Add queue item | `MediaController.addMediaItem(item)` |
| Remove queue item | `MediaController.removeMediaItem(index)` |
| Move queue item | `MediaController.moveMediaItem(from, to)` |
| Set repeat mode | `MediaController.setRepeatMode(mode)` |
| Set shuffle mode | `MediaController.setShuffleModeEnabled(enabled)` |
| Seek to specific queue item | `MediaController.seekToDefaultPosition(index)` |
| Custom commands (e.g., stream-refresh signal) | `MediaController.sendCustomCommand(sessionCommand, args)` |

### 7.3 State That Crosses the Service Boundary (Service → UI)

| State | How It Crosses |
| :--- | :--- |
| Playback state (Playing/Paused/Buffering/Ended) | `Player.Listener.onPlaybackStateChanged` / `onIsPlayingChanged` |
| Current `MediaItem` | `Player.Listener.onMediaItemTransition` |
| Playback position (real-time) | `MediaController.currentPosition` (polled or frame-driven) |
| Buffered position | `MediaController.bufferedPosition` |
| Duration | `MediaController.duration` |
| Queue timeline | `Player.Listener.onTimelineChanged` |
| Repeat mode | `Player.Listener.onRepeatModeChanged` |
| Shuffle mode | `Player.Listener.onShuffleModeEnabledChanged` |
| Playback error | `Player.Listener.onPlayerError` |
| Is loading | `Player.Listener.onIsLoadingChanged` |

### 7.4 What Does NOT Cross This Boundary

The following are **not playback state** and must never be sourced from `MediaController`:

- Liked status of the current track (source: `LibraryRepository`)
- Lyrics data and timing (source: `LyricsRepository`)
- Continuity context (source: `ContinuityEngine`)
- Search results (source: `SearchViewModel` / `SQE`)
- User theme preference (source: `SettingsRepository`)

---

## 8. Playback Command Flow

### 8.1 Decision: Canonical Command Path ✅ DECIDED

```
User Interaction (Compose UI)
        │  UiEvent (e.g., PlayPauseClicked, SkipNextClicked)
        ▼
PlayerViewModel
        │  Translates UiEvent → MediaController command
        ▼
MediaController (client-side Player proxy — same process, logical boundary)
        │  Binder IPC
        ▼
SonaraPlaybackService / MediaSession.Callback
        │  Validates, routes, or applies to orchestration
        ▼
ExoPlayer (internal to SonaraPlaybackService)
```

### 8.2 Why This Path Is Correct

- **UI layer** never makes playback decisions. It only translates user intent into typed events.
- **`PlayerViewModel`** is the sole UI-side component that knows about the `MediaController`.
- **`MediaController`** ensures commands cross the service boundary in a defined, ordered manner.
- **`MediaSession.Callback`** inside the service can intercept commands (e.g., cancel in-flight stream resolution) before applying them to `ExoPlayer`.
- **`ExoPlayer`** is the final receiver of all playback commands and the authoritative source of resulting state.

### 8.3 `media3-ui-compose` State Holders

For standard playback controls (play/pause button, progress bar), the architecture prefers using `media3-ui-compose` state holders (available since Media3 1.6.0) where applicable. For Sonara-specific controls and custom surfaces (Mini Player, Expanded Player, Lyrics), `PlayerViewModel` handles event translation.

---

## 9. Playback State Flow

### 9.1 Decision: Canonical State Path ✅ DECIDED

```
ExoPlayer (authoritative source of playback state)
        │  Player.Listener callbacks
        ▼
MediaController (client-side mirror — synchronized via Binder)
        │  Player.Listener registered on MediaController
        ▼
PlayerViewModel (maps Player callbacks → Sonara UiState)
        │  StateFlow<PlayerUiState>
        ▼
Compose UI (collects StateFlow via collectAsStateWithLifecycle())
        │
        ├──► Mini Player
        ├──► Expanded Player
        ├──► Lyrics Screen (playback position via separate port)
        ├──► Queue Sheet
        └──► Any screen showing now-playing metadata
```

### 9.2 State Derivation Rules

- `PlayerViewModel` is the **only** component that calls `Player.Listener` on the `MediaController` to produce `UiState`.
- `PlayerViewModel` emits **immutable** `PlayerUiState` snapshots.
- **State Conflation**: `Player.Listener` callbacks are NOT individually treated as complete UI states. `PlayerViewModel` must aggregate/conflate related callbacks into coherent `PlayerUiState` snapshots. UI observers must receive coherent aggregated state rather than a sequence of independent partial updates.
- Compose screens **never** call `MediaController` methods directly.
- Screens not visible on screen must not hold active `Player.Listener` registrations. `collectAsStateWithLifecycle()` provides lifecycle-scoped collection automatically.

### 9.3 Preventing Feedback Loops

```
ExoPlayer state → Player.Listener → PlayerViewModel → StateFlow → Compose displays
Compose emits UiEvent → PlayerViewModel → MediaController.seekTo() → ExoPlayer applies
ExoPlayer emits new state → (loop continues cleanly)
```

Commands and state travel in opposite directions through separate paths. There is no `UI state → Player state → UI state` short-circuit.

---

## 10. State Ownership Matrix

One authoritative owner per state property. No state has two simultaneous owners.

| State | Authoritative Owner | Primary Observers | Writable By | Persisted? |
| :--- | :--- | :--- | :--- | :--- |
| **Playback state** (Playing/Paused/Buffering/Ended) | `ExoPlayer` | `PlayerViewModel` → Mini Player, Expanded Player, Notification | `SonaraPlaybackService` / `MediaController` commands | No |
| **Current `MediaItem`** | `ExoPlayer` timeline | `PlayerViewModel` → Mini Player, Expanded Player, Lockscreen | `MediaController` queue commands | Session snapshot only |
| **Playback position** (real-time) | Hardware audio clock → `ExoPlayer` | Progress scrubber, Lyrics timing engine | `MediaController.seekTo()` | Session snapshot only |
| **Buffered position** | `ExoPlayer` buffer | Progress scrubber | Not directly settable | No |
| **Duration** | `ExoPlayer` / `MediaItem` metadata | Progress scrubber, Expanded Player | Not settable | No |
| **Active queue / timeline** | `ExoPlayer` internal `Timeline` | Queue sheet, `PlayerViewModel`, Continuity Orchestration | `MediaController` queue commands only | Session snapshot only |
| **Repeat mode** | `ExoPlayer` | Repeat button UI | `MediaController.setRepeatMode()` | User preference (lightweight) |
| **Shuffle mode** | `ExoPlayer` | Shuffle button UI | `MediaController.setShuffleModeEnabled()` | User preference (lightweight) |
| **Is loading** | `ExoPlayer` | Loading indicator UI | Not directly settable | No |
| **Playback error** | `ExoPlayer` → `SonaraPlaybackService` (classified) | `PlayerViewModel` → Error UI | Not settable | No |
| **Stream URL** (resolved playable link) | `StreamResolver` (in-memory, ephemeral) | `SonaraPlaybackService` only | `StreamResolver` | **NEVER persisted to disk** |
| **Stream resolution state** | `SonaraPlaybackService` orchestration | `PlayerViewModel` (via custom session command) | `SonaraPlaybackService` | No |
| **Liked status** | `LibraryRepository` → SQLite | Expanded Player heart, Track list rows | User heart actions | Yes — SQLite |
| **Listening history** | `LibraryRepository` → SQLite | Library / History screen | `SonaraPlaybackService` (on completion) | Yes — SQLite |
| **Continuity context** | `ContinuityEngine` (computed on demand) | `SonaraPlaybackService` orchestration | Not persisted | No |
| **Lyrics data** | `LyricsRepository` | `LyricsViewModel` → Lyrics screen | `LyricsRepository` (cache write) | Yes — SQLite cache |
| **Active lyric line** | `LyricsViewModel` (derived from position + lyrics data) | Lyrics screen UI | Computed; user scroll overrides | No |
| **User theme preference** | `SettingsRepository` → DataStore | Root `SonaraTheme` composable | Settings screen | Yes — DataStore |
| **Audio quality preference** | `SettingsRepository` → DataStore | `StreamResolver` (quality hint) | Settings screen | Yes — DataStore |
| **Romanization preference** | `SettingsRepository` → DataStore | `IndicTransliterationEngine` trigger | Settings screen | Yes — DataStore |

---

## 11. Queue Ownership & Synchronization

### 11.1 Decision: ExoPlayer's Timeline Is the Single Queue Authority ✅ DECIDED

**ExoPlayer's internal `Timeline` is the single, authoritative source of truth for the active playback queue.**

There is no separate "Sonara logical queue" object running parallel to the `ExoPlayer` timeline. The `ExoPlayer` timeline IS the Sonara queue.

### 11.2 Queue Mutation Flow

```
User Action (add/remove/reorder)
        │
        ▼
PlayerViewModel
        │  MediaController.addMediaItem() / removeMediaItem() / moveMediaItem()
        ▼
SonaraPlaybackService (validated and applied to ExoPlayer)
        │
        ▼
ExoPlayer timeline updated
        │  Player.Listener.onTimelineChanged()
        ▼
MediaController state updated → PlayerViewModel → UI
```

**No UI component, ViewModel, or domain engine may maintain a parallel in-memory queue object.** This directly prevents the Sonara Web queue corruption failure mode where multiple components held stale snapshots and mutated the queue independently.

### 11.3 Continuity Prefetch Trigger

Continuity generation must begin **while the final track is still playing**, before playback stops. `STATE_ENDED` represents actual queue exhaustion/completion, not the moment when continuity work begins.

1. `SonaraPlaybackService` orchestration detects that the user has entered the final-track region of the active queue (e.g., via `onMediaItemTransition` when the final item begins, using a configurable/tunable threshold).
2. Orchestration invokes `ContinuityPort` with the current `ContinuityContext`.
3. `ContinuityEngine` returns an ordered `List<Track>` of candidates.
4. Orchestration constructs `MediaItem` objects and adds them to the player queue via `MediaController.addMediaItems()`.
5. Stream resolution for the newly added track is prepared, allowing the queue to continue seamlessly without audio-focus transitions or audible gaps.
6. If `ContinuityEngine` returns no candidates and the queue genuinely exhausts, playback reaches `STATE_ENDED` and the service sends a custom `PlaybackMessage.QueueEmpty` signal to the UI.

---

## 12. Rapid Command / Cancellation Model

### 12.1 The Web Race Condition Context

Sonara Web's rapid "Next → Next → Next" failures: multiple concurrent stream resolution jobs ran simultaneously, untagged and un-cancellable. Older slow resolutions overwrote newer ones, causing out-of-order track loads and stuck loading states.

### 12.2 Decision: Single-Authority Serialization with Cancellation ✅ DECIDED

Three rules govern rapid command handling:

**Rule 1 — Command Serialization at the Service Boundary**

`MediaSession.Callback` receives commands sequentially (Binder guarantees ordering). On each new skip command, any in-flight stream resolution work for the previous target is immediately cancelled.

**Rule 2 — Single Active Resolution Job Slot**

The `SonaraPlaybackService` orchestration layer maintains exactly one "active resolution job" at a time:

```
New skip command received:
    1. Cancel existing resolution job (structured cancellation — cooperative).
    2. Clear the job slot.
    3. Launch a new resolution job for the new target track.
    4. Store the new job in the slot.
```

This is a **last-writer-wins with immediate cancellation** model. Cancellation happens before the new job starts, ensuring no stale result from a cancelled job can affect player state.

**Rule 3 — ExoPlayer Track Pointer Moves Immediately**

When a skip command is issued, `ExoPlayer.seekToNextMediaItem()` is called immediately. ExoPlayer moves the current item pointer, and the UI reflects the new track's metadata (title, artist, artwork from `MediaItem.mediaMetadata`) instantly — even while the stream URL for that track is being resolved asynchronously. The player shows buffering state until the URL is available.

### 12.3 Concurrency Model Illustration

```
User taps Next 3 times in 200ms:

Skip 1 → ExoPlayer moves to Track B → Job A launched (resolving stream for Track B)
Skip 2 → ExoPlayer moves to Track C → Job A CANCELLED → Job B launched (resolving Track C)
Skip 3 → ExoPlayer moves to Track D → Job B CANCELLED → Job C launched (resolving Track D)

Job C completes → Track D stream begins playing
Job A, B: cancelled, produce no state effects
```

### 12.4 Commands That Are Not Affected by the Cancellation Model

These commands carry no async work and are applied immediately:
- `pause()` / `play()` toggle
- `setRepeatMode()` / `setShuffleModeEnabled()`
- `seekTo(positionMs)` within the currently loaded track
- Queue reordering within already-resolved items

---

## 13. Stream Resolution Boundary

### 13.1 Decision: StreamResolver Is a Service-Adjacent, Domain-Independent Component ✅ DECIDED

**Stream resolution is explicitly separated from audio playback execution.**

`StreamResolver` obtains a playable HTTP stream URL for a given `Track`. It is:
- **Not part of the domain layer** (it makes network calls)
- **Not embedded in the playback service** (it is independently replaceable and testable)
- **Invoked via `StreamResolverPort`** (interface the service depends on)

### 13.2 Resolution Architecture

```
SonaraPlaybackService orchestration
        │  Requests stream URL via StreamResolverPort interface
        ▼
StreamResolverPort (interface — service depends on abstraction, not implementation)
        │
        ▼
StreamResolver (concrete implementation — data layer)
        │  Resolution logic:
        │    1. Check in-memory URL cache (if not expired, ~4-hour TTL)
        │    2. Attempt primary provider (YTMusicStreamSource)
        │    3. Fallback: attempt secondary provider (JioSaavnStreamSource)
        │    4. Return: resolved URL (success) or StreamResolutionError (failure)
        ▼
SonaraPlaybackService receives resolved URL
        │  Supplies to ExoPlayer via ResolvingDataSource / RefreshingDataSource
        ▼
ExoPlayer begins buffering
```

### 13.3 Expired Stream Handling (HTTP 403 / 410 During Active Playback)

1. `RefreshingDataSource` (wrapping the OkHttp data source) intercepts the HTTP 403/410 error.
2. It notifies the `SonaraPlaybackService` orchestration layer that the stream URL has expired.
3. A fresh resolution is triggered via `StreamResolverPort` for the current `MediaItem`.
4. Once a fresh URL is acquired, `ExoPlayer` resumes from the exact millisecond offset.
5. The user experiences at most a brief buffering pause — no track restart, no loss of position.

**Stream URLs are never written to disk.** Fresh resolution is always required after process death.

### 13.4 Provider Abstraction

`StreamResolver` delegates to provider-specific implementations (`YTMusicStreamSource`, `JioSaavnStreamSource`) that implement a common `StreamSource` interface. Adding a new provider requires only a new `StreamSource` implementation and registration in `StreamResolver`'s fallback chain.

---

## 14. Continuity Engine Boundary

### 14.1 Decision: ContinuityEngine Invoked via Port Interface ✅ DECIDED

```
SonaraPlaybackService playback orchestration
        │  Depends on ContinuityPort (interface defined in domain layer)
        ▼
ContinuityPort
        │  Declares: getCandidateTracks(context: ContinuityContext): List<Track>
        ▼
ContinuityEngine (pure Kotlin, implements ContinuityPort)
        │  Uses: ContinuityScoringStrategy, seed affinity weights, artist fatigue
        ▼
Returns: ordered List<Track> (highest continuity score first)
        │
        ▼
Orchestration converts Track → MediaItem, extends ExoPlayer queue
```

### 14.2 Dependency Direction

```
SonaraPlaybackService → ContinuityPort (interface)
ContinuityEngine implements ContinuityPort

ContinuityEngine has ZERO dependency on:
    • SonaraPlaybackService
    • ExoPlayer  •  MediaSession  •  Android Context  •  Any AndroidX library
```

Domain does not depend on infrastructure. Infrastructure depends on domain via ports.

### 14.3 ContinuityContext (Conceptual — Not Yet Defined as Kotlin API)

The `ContinuityPort` receives a `ContinuityContext` value object containing:
- The current `Track` (seed)
- Recently played `Track` list (artist fatigue context)
- Optional user affinity signals (from `LibraryRepository`)

`ContinuityContext` is a pure domain model — no Android types.

---

## 15. Audio Focus

### 15.1 Decision: Delegate to Media3's Built-In Audio Focus Management ✅ DECIDED

**Sonara does not implement custom audio focus handling.**

Media3 `ExoPlayer` natively manages audio focus when configured with:
```
AudioAttributes:
    Usage: USAGE_MEDIA
    ContentType: CONTENT_TYPE_MUSIC
    handleAudioFocus = true
```

With this configuration, `ExoPlayer` automatically:
- Requests audio focus before beginning playback.
- Pauses on permanent focus loss (e.g., phone call).
- Ducks volume on transient loss with ducking (e.g., navigation alert).
- Resumes when audio focus is regained.
- Pauses immediately on headset disconnect (`ACTION_AUDIO_BECOMING_NOISY`).

### 15.2 Rejected Alternative

| Alternative | Rejection Reason |
| :--- | :--- |
| Custom `AudioManager.OnAudioFocusChangeListener` | Redundant. Media3 implements this correctly internally. Adding a custom listener risks conflicting with Media3's internal focus management. |

---

## 16. Bluetooth / Headset / System Controls

### 16.1 Decision: All External Controls Route Through MediaSession ✅ DECIDED

**All external control surfaces converge at the single `MediaSession` published by `SonaraPlaybackService`.**

| External Control Surface | Entry into Architecture |
| :--- | :--- |
| Bluetooth AVRCP (play/pause, next, previous) | Android OS dispatches media button events to active `MediaSession` |
| Wired headset button (single press) | Android OS routes to `MediaSession` |
| Wired headset disconnection | Media3 `ExoPlayer` handles via `AudioAttributes` |
| Lockscreen transport controls | System reads `MediaSession` state; commands → `MediaSession.Callback` |
| Notification media controls | `MediaSessionService` generates `MediaStyle` notification; commands → `MediaSession.Callback` |
| Android media panel / quick settings | Reads from `MediaSession`; commands → `MediaSession.Callback` |

All surfaces route to `MediaSession.Callback`, which applies commands to `ExoPlayer`. **There is no separate command pathway for any surface.** This prevents the desynchronization failure mode where different control surfaces had partially independent state management paths.

---

## 17. Notification / Lockscreen Integration

### 17.1 Decision: Delegate Notification Management to MediaSessionService ✅ DECIDED

`MediaSessionService` base class automatically manages the `MediaStyle` notification:

- Creates the notification from `MediaItem.mediaMetadata` (title, artist, artwork URI).
- Updates the notification on player state changes.
- Provides play/pause, skip-next, skip-previous actions.
- Manages foreground service lifecycle automatically based on player state.
- Moves service out of foreground when playback stops (allowing OS to stop the service after user removes notification).

### 17.2 Artwork in Notification

`MediaItem.mediaMetadata.artworkUri` is populated when the `MediaItem` is constructed. The notification system reads this URI. Sonara does not implement custom notification bitmap loading at MVP unless the default `MediaSessionService` behavior proves insufficient (validated in implementation phase).

### 17.3 Custom Notification Actions

Custom notification actions (e.g., heart/like button) are **Post-MVP**. MVP notification exposes: play/pause, skip-next, skip-previous only.

---

## 18. Background Playback & Lifecycle

### 18.1 Fundamental Boundary: UI Lifecycle ≠ Playback Lifecycle

| Lifecycle | Owner | What Controls It |
| :--- | :--- | :--- |
| **UI Lifecycle** | Compose / Activity | User navigation, screen rotation, system back gestures, OS config changes |
| **Playback Lifecycle** | `SonaraPlaybackService` | Playback state, foreground service policy, `MediaSession` lifetime, `ExoPlayer` lifetime |

The playback lifecycle outlives any particular UI state. `MediaController` connects and disconnects across UI lifecycle transitions without interrupting playback.

### 18.2 Lifecycle Scenarios and Behavior

| Scenario | UI Effect | Playback Effect | Recovery Mechanism |
| :--- | :--- | :--- | :--- |
| Screen rotation | Activity recreated, Compose recomposed | No interruption | `PlayerViewModel` survives; `MediaController` reconnects |
| User navigates away | UI screen removed | No interruption if service is active | `MediaController` remains in `PlayerViewModel` scope |
| App sent to background | UI suspended | Continues via foreground service; notification shown | No recovery needed |
| Screen turned off | UI invisible | Continues via foreground service; lockscreen controls active | No recovery needed |
| User swipes app from recents | Activity destroyed | Continues if actively playing; may stop if paused (via `onTaskRemoved` policy) | Session snapshot written for recovery |
| OS kills process (RAM pressure) | Entire process terminated | Stops | Session snapshot enables restart recovery |
| Device reboot | Everything terminated | Stops | Library data survives in SQLite; preferences in DataStore |

---

## 19. Process Death & Session Recovery

### 19.1 Survival Requirements by Lifecycle Event

| Data | UI Recreation | Activity Recreation | Process Death | Device Reboot |
| :--- | :--- | :--- | :--- | :--- |
| Playback state (Playing/Paused) | MUST SURVIVE | MUST SURVIVE | NOT REQUIRED | NOT REQUIRED |
| Current track identity | MUST SURVIVE | MUST SURVIVE | MUST SURVIVE | SHOULD SURVIVE |
| Playback position (ms) | MUST SURVIVE | MUST SURVIVE | SHOULD SURVIVE | NOT REQUIRED |
| Active queue (ordered track list) | MUST SURVIVE | MUST SURVIVE | SHOULD SURVIVE | NOT REQUIRED |
| Shuffle / Repeat mode | MUST SURVIVE | MUST SURVIVE | SHOULD SURVIVE | SHOULD SURVIVE |
| Liked songs | — | — | MUST SURVIVE | MUST SURVIVE |
| Listening history | — | — | MUST SURVIVE | MUST SURVIVE |
| User preferences | MUST SURVIVE | MUST SURVIVE | MUST SURVIVE | MUST SURVIVE |
| Stream URL | NOT REQUIRED | NOT REQUIRED | NOT REQUIRED | NOT REQUIRED |

### 19.2 Survival Mechanisms

| Lifecycle | Mechanism |
| :--- | :--- |
| UI Recreation | `ViewModel` retains `PlayerViewModel`; `MediaController` reconnects to running service |
| Activity Recreation | Same as UI Recreation; `MediaController.Builder` reconnects via `SessionToken` |
| Process Death | Session snapshot (mechanism: 🟡 OPEN — Phase 4B-3) |
| Device Reboot | `LibraryRepository` (SQLite) + `SettingsRepository` (DataStore) provide durable state |

### 19.3 Session Snapshot — Architectural Contract

**Requirement**: Session snapshot written atomically before process terminates.

**Minimum content**:
- Current track identifier (stable cross-session domain ID)
- Ordered list of queue track identifiers
- Last known playback position (milliseconds)
- Repeat mode
- Shuffle mode flag

**Write triggers**:
- Every `onMediaItemTransition` (new track started)
- Every `onPlaybackStateChanged` to `STATE_IDLE`
- Periodically during active playback (frequency: 🟣 tunable policy)

**Read trigger**:
- `SonaraPlaybackService.onCreate()` when restarting after process death

**Restoration State**:
- A restored session ALWAYS resumes in the **PAUSED** state. `SonaraPlaybackService` must NOT automatically resume audio playback after process/application recovery. The user must explicitly initiate playback again.

**Serialization mechanism**: 🟡 OPEN — Phase 4B-3 (candidates: JSON file in cache dir, DataStore Proto, Room row)

---

## 20. High-Frequency Playback Position

### 20.1 Architectural Requirement ✅ DECIDED (Mechanism 🟡 OPEN)

**Playback position updates must not cause unnecessary broad Compose recomposition.**

**Architectural rule**: Continuous playback-position observation must be scoped to the minimum Composable surface that requires it, and must automatically suspend when that surface is not visible.

### 20.2 Candidate Mechanisms (🟡 OPEN — Decided in Phase 4B-2 or implementation benchmarking)

| Candidate | Description | Tradeoffs |
| :--- | :--- | :--- |
| **A — Frame-ticked `StateFlow` in scoped composable** | `PlaybackClock` provider ticks using `withFrameMillis` only when the consumer is active | Natural Compose integration; requires careful lifecycle scoping |
| **B — Compose draw-phase Canvas interpolation** | Progress and rings draw inside `Modifier.drawWithContent` using time-delta interpolation | Zero recomposition cost; higher draw-phase complexity |
| **C — Coroutine timestamp polling** | `PlayerViewModel` emits position snapshots at N ms intervals; UI interpolates with `AnimationState` | Lower polling frequency; seek latency in display |

**Lyrics note**: Lyric word highlighting requires ~50–100ms resolution, not 60fps. Candidate C or a hybrid is viable for lyrics specifically, while scrub bars may benefit from Candidate A or B.

---

## 21. Lyrics Integration

### 21.1 Decision: Lyrics Consume Position via PlaybackClockPort ✅ DECIDED

```
SonaraPlaybackService / MediaController (position authority)
        │  (Exact mechanism: TBD in Phase 4B-2)
        ▼
PlaybackClockPort (interface — domain-adjacent, lifecycle-aware)
        │  Provides: current position in milliseconds, lifecycle-scoped
        ▼
LyricsViewModel
        │  Subscribes to clock; applies NaturalInertiaProcessor timestamps
        │  Computes active lyric line index
        ▼
Lyrics Screen (Compose)
        │  Renders active line highlighted
        │  Auto-scrolls to active line
        │  User scroll temporarily overrides auto-scroll
```

### 21.2 Seeking from Lyrics Screen

```
User taps lyric word (UI event)
        ↓
LyricsViewModel: SeekToLyricWord(wordIndex) → calculate targetPositionMs
        ↓
MediaController.seekTo(targetPositionMs)
        ↓
ExoPlayer seeks → position clock advances from new position
        ↓
LyricsViewModel recomputes active line
```

### 21.3 Manual Scroll Behavior

- Auto-scroll suspends when user scrolls (`isUserScrolling = true` in `LyricsViewModel`).
- Highlight continues advancing (engine keeps running).
- Auto-scroll resumes after a defined idle timeout (🟣 tunable policy).
- `isUserScrolling` is owned by `LyricsViewModel` — it is NOT playback state.

### 21.4 Romanization ('aA') Toggle

- `SettingsRepository` owns the romanization preference.
- `IndicTransliterationEngine` is invoked on-demand when lyrics contain non-Latin script.
- `LyricsViewModel` observes both `LyricsRepository` and `SettingsRepository` to produce the final lyrics `UiState`.
- Romanization is NOT triggered by playback events.

---

## 22. Error Ownership & Recovery

### 22.1 Error Classification Taxonomy

| Error Category | Detection Layer | Classification Layer | Recovery Layer | UI Notification |
| :--- | :--- | :--- | :--- | :--- |
| Stream resolution failure (all providers failed) | `StreamResolver` | `StreamResolver` | `SonaraPlaybackService` (skip or error state) | `PlayerViewModel` → error UI |
| Stream playback failure (HTTP 403/410 mid-play) | `RefreshingDataSource` | `SonaraPlaybackService` | `StreamResolver` refresh → resume | Transparent (brief buffering) |
| Network unavailable | `StreamResolver` / `RefreshingDataSource` | `SonaraPlaybackService` | Retry with bounded backoff | `PlayerViewModel` → offline indicator |
| Expired stream (token TTL exceeded) | `RefreshingDataSource` | `SonaraPlaybackService` | `StreamResolver` fresh URL | Transparent |
| Invalid `MediaItem` | `ExoPlayer` (`onPlayerError`) | `SonaraPlaybackService` | Log, skip to next | `PlayerViewModel` → brief indicator |
| Queue item failure (one item, others succeed) | `ExoPlayer` (`onPlayerError`) | `SonaraPlaybackService` | Skip to next item | Brief indicator |
| Provider API failure (extraction blocked) | `StreamSource` implementations | `StreamResolver` | Try secondary provider | If all fail → resolution failure |
| Lyrics fetch failure | `LyricsRepository` | `LyricsRepository` | Bounded negative cache (~30min); retry next access | Lyrics screen: "Unavailable" |

### 22.2 Negative Cache Contract

- Error must be cached with a bounded TTL only (🟣 tunable: ~15min for stream, ~30min for lyrics).
- Error must **NOT** be written to the relational database as a permanent failure flag.
- On TTL expiry, the next access triggers a fresh attempt.
- This directly prevents the Sonara Web "Negative Cache Poisoning" failure mode.

### 22.3 Error Does Not Halt Playback

A single-item error in the queue must not halt the entire session. `SonaraPlaybackService`:
1. Detects the failure.
2. Attempts recovery (stream refresh or provider fallback).
3. If unrecoverable, skips to next item.
4. Notifies the UI of the skip with the reason via custom `MediaSession` command.

---

## 23. Testing Contract

### 23.1 Test Scenarios by Type

| Test Scenario | Type | Isolation |
| :--- | :--- | :--- |
| `ContinuityEngine` scoring and ranking | Pure JVM unit test | Zero Android SDK; uses only domain models |
| `NaturalInertiaProcessor` lyrics timing | Pure JVM unit test | Zero Android SDK |
| `SearchQualityEngine` keyword ranking | Pure JVM unit test | Zero Android SDK |
| `StreamResolver` provider fallback routing | JVM test with fake `StreamSource` | No real network; uses fakes |
| Rapid skip cancellation (3 skips in 200ms) | Media3 test fixture or instrumentation | Verifies correct track lands; cancelled jobs produce no state |
| Stream expiration recovery (HTTP 403 mid-play) | Integration test with MockWebServer | Verifies resume at correct position |
| `LibraryRepository` liked songs reactive flow | JVM test with in-memory Room DB | Verifies Flow emits on insert/delete |
| History write on qualifying track completion | JVM test with fake `LibraryRepository` | Verifies completion threshold logic |
| `PlayerViewModel` `UiState` from `Player.Listener` | JVM test with fake `MediaController` | Verifies correct `StateFlow` updates |
| Audio focus behavior on transient loss | Instrumentation / Media3 test utilities | May require on-device execution |
| Process death session snapshot write + restore | Instrumentation | Requires process kill and restart |
| Notification synchronized with player state | Instrumentation | Verifies notification shows correct metadata |
| Physical device regression | Instrumented on Redmi Note 12 Pro (API 34) | Smoke tests for Mini Player, Expanded Player, Lyrics |

### 23.2 Domain Isolation Enforcement

Domain engines must pass a compilation check verifying zero `android.*` or `androidx.*` imports. This is testable by compiling the domain package in a pure JVM test context without the Android SDK on the classpath.

---

## 24. Alternatives & Rejected Approaches

### Player Ownership

| Alternative | Rejection Reason |
| :--- | :--- |
| `ExoPlayer` in Activity | Destroyed on rotation; cannot background-play; no system media controls |
| `ExoPlayer` in `ViewModel` | Survives rotation but not task removal; cannot background-play |
| `ExoPlayer` in plain `IntentService` | Deprecated; no `MediaSession` |
| `ExoPlayer` in custom `ForegroundService` | Re-implements what `MediaSessionService` already provides |

### MediaSession Approach

| Alternative | Rejection Reason |
| :--- | :--- |
| No `MediaSession` | Loses all system control surfaces — non-negotiable for a music application |
| `MediaLibraryService` | Adds `MediaBrowser` protocol for Android Auto browsing; Sonara does not need Auto at MVP |

### Queue Ownership

| Alternative | Rejection Reason |
| :--- | :--- |
| Separate Sonara queue object alongside ExoPlayer Timeline | Dual-source-of-truth problem; identical to Sonara Web's queue corruption failure mode |
| ViewModel-owned queue that syncs to ExoPlayer | Sync delay and race conditions on rapid mutations |

### Audio Focus

| Alternative | Rejection Reason |
| :--- | :--- |
| Custom `AudioManager.OnAudioFocusChangeListener` | Redundant with Media3; risk of conflict with Media3's internal handling |
| Ignore audio focus | Breaks Android platform contract |

### Rapid Skip Model

| Alternative | Rejection Reason |
| :--- | :--- |
| Multiple concurrent resolution jobs, discard all but last | Wastes CPU/memory on cancelled work; structured cancellation is simpler |
| Debounce skip commands (wait 300ms) | Introduces unacceptable input lag; visual skip must be immediate |
| Serial command queue processing | Backlog during rapid tapping; 5 skips → 5 sequential resolutions before correct track plays |

### Command Routing

| Alternative | Rejection Reason |
| :--- | :--- |
| UI calls `ExoPlayer` methods directly | Service boundary violation; `ExoPlayer` is not accessible from UI |
| Multiple `MediaController` instances per screen | Redundant connections; state delivered independently to each requires manual sync |

---

## 25. Final Playback Architecture

```
═══════════════════════════════════════════════════════════════════════════
                         CLIENT / UI PROCESS
═══════════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────────────────────────────────────────┐
  │                       PRESENTATION LAYER                             │
  │                                                                      │
  │  Home      Search      Library     Mini Player     Expanded Player   │
  │  Screen    Screen      Screen      (persistent)    (modal / full)    │
  │                                                                      │
  │           Lyrics Screen       Queue Sheet      Settings              │
  │                                                                      │
  │  Compose UI — collects StateFlow from ViewModels                     │
  │  Emits UiEvents up — never calls MediaController directly            │
  └──────────────────┬───────────────────────────────────────────────────┘
                     │  UiEvents
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                       VIEWMODEL LAYER                                │
  │                                                                      │
  │  PlayerViewModel      LyricsViewModel     LibraryViewModel           │
  │  SearchViewModel      SettingsViewModel                              │
  │                                                                      │
  │  PlayerViewModel: holds MediaController, emits PlayerUiState         │
  │  LyricsViewModel: observes PlaybackClockPort + LyricsRepository      │
  │  Other ViewModels: do NOT touch MediaController                      │
  └──────────────┬───────────────────────────────────────────────────────┘
                 │
                 │  Commands via MediaController (Player interface)
                 │  ← PlayerUiState (StateFlow) flows back to UI
                 │
                 │  [Binder IPC — same process, logical service boundary]
                 │
═══════════════════════════════════════════════════════════════════════════
           PLAYBACK SERVICE BOUNDARY (SonaraPlaybackService)
═══════════════════════════════════════════════════════════════════════════
                 │
                 ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │            SonaraPlaybackService (extends MediaSessionService)       │
  │                                                                      │
  │  ┌────────────────────────┐   ┌─────────────────────────────────┐   │
  │  │     MediaSession       │   │  Playback Orchestration Layer   │   │
  │  │  • Session.Callback    │   │  • Active Resolution Job Slot   │   │
  │  │  • Command routing     │   │  • Rapid skip cancellation      │   │
  │  │  • Custom commands     │   │  • Stream refresh handling      │   │
  │  │  • MediaMetadata sync  │   │  • Continuity prefetch trigger  │   │
  │  └────────────────────────┘   │  • History logging trigger      │   │
  │                               └───────────────┬─────────────────┘   │
  │  ┌─────────────────────────────────────────── │ ──────────────────┐ │
  │  │  ExoPlayer Instance                        │                   │ │
  │  │  • AudioAttributes (focus auto-managed)    │                   │ │
  │  │  • RefreshingDataSource (stream expiry)    │                   │ │
  │  │  • Timeline = Sonara active queue          │                   │ │
  │  │  • Player.Listener → state to Controller   │                   │ │
  │  └────────────────────────────────────────────┘                   │ │
  └─────────────────────────────────┬────────────────────────────────┘ │
                                    │  (calls via ports/interfaces)
      ┌─────────────────────────────┼─────────────────────────────┐
      │                             │                             │
      ▼                             ▼                             ▼
┌─────────────┐         ┌────────────────────┐      ┌─────────────────────────┐
│   ANDROID   │         │   DOMAIN LAYER     │      │   DATA LAYER            │
│   SYSTEM    │         │   (Pure Kotlin)    │      │                         │
│             │         │                    │      │  • MusicRepository      │
│  AudioFocus │         │  ContinuityEngine  │      │  • LyricsRepository     │
│  Lockscreen │         │  (via ContinuityPort) │   │  • LibraryRepository    │
│  Notif.     │         │  SearchQualityEng. │      │  • SettingsRepository   │
│  Bluetooth  │         │  NaturalInertia    │      │  • StreamResolver       │
│  AVRCP      │         │  IndicTranslit.    │      │    (via StreamResolverPort)
│  MediaStyle │         │  Domain Models     │      │  • YTMusicStreamSource  │
│  Notif.     │         │  (Track, Lyrics,   │      │  • JioSaavnStreamSource │
│             │         │   Queue,           │      │  • LyricsSource (LRCLIB)│
└─────────────┘         │   SearchResult)    │      │  • ArtworkSource        │
                        └────────────────────┘      └─────────────────────────┘
```

---

## 26. Final Architectural Contracts

### 26.1 Playback Control Contract

- **Source**: `PlayerViewModel` (application side)
- **Medium**: `MediaController` implementing `Player` interface
- **Destination**: `MediaSession.Callback` → `ExoPlayer`
- **Rule**: All playback commands originate exclusively from `PlayerViewModel` via `MediaController`. No other component may issue commands to the player.
- **Ordering**: Commands processed in order received at `MediaSession.Callback`. Rapid skip commands cancel active resolution work before starting new resolution.

### 26.2 Playback State Contract

- **Source**: `ExoPlayer` inside `SonaraPlaybackService`
- **Medium**: `Player.Listener` callbacks propagated by `MediaController`
- **Destination**: `PlayerViewModel` → `StateFlow<PlayerUiState>` → Compose screens
- **Rule**: `PlayerViewModel` is the exclusive translator between `Player.Listener` callbacks and Compose-observable `StateFlow`s. `PlayerViewModel` must aggregate related callbacks to ensure UI observers receive coherent/conflated playback state. All state is immutable when emitted. No Compose screen observes `Player.Listener` directly.

### 26.3 Queue Contract

- **Single Authority**: `ExoPlayer` `Timeline` is the only queue.
- **Mutation**: Only through `MediaController` queue commands.
- **Observation**: Via `Player.Listener.onTimelineChanged()` → `PlayerViewModel` → `StateFlow<QueueUiState>`.
- **Continuity Prefetch**: Orchestration detects the final queued track playing and invokes `ContinuityPort` before the queue exhausts.
- **Prohibition**: No component may hold a parallel queue data structure synchronized with `ExoPlayer`.

### 26.4 Stream Resolution Contract

- **Port**: `StreamResolverPort` interface (the service depends on this, not the implementation)
- **Implementation**: `StreamResolver` (data layer)
- **Input**: `Track` domain model + quality preference hint
- **Output**: Resolved stream URL (success) or typed `StreamResolutionError` (failure)
- **Cache**: In-memory only; ~4-hour TTL (🟣 tunable); **never persisted to disk**
- **Refresh**: On HTTP 403/410, `RefreshingDataSource` signals orchestration; fresh URL resolved via `StreamResolverPort`; playback resumes at saved position
- **Fallback**: Primary provider → secondary provider → `StreamResolutionError`

### 26.5 Continuity Contract

- **Port**: `ContinuityPort` interface (defined in domain layer)
- **Implementation**: `ContinuityEngine` (pure Kotlin, zero Android dependencies)
- **Trigger**: `SonaraPlaybackService` orchestration detects the final queued track playing, beginning continuity preparation before `STATE_ENDED`.
- **Input**: `ContinuityContext` (seed track, recently played tracks, optional affinity signals)
- **Output**: `List<Track>` ordered by continuity score (highest first)
- **Prohibition**: `ContinuityEngine` must import zero Android or AndroidX classes. The port interface is the sole contact point between infrastructure and domain.

### 26.6 Playback Position Contract

- **Source**: Hardware audio clock → `ExoPlayer`
- **Access**: `MediaController.currentPosition` (polled or frame-driven)
- **Principle**: Must not be collected at root composable level; must scope to the minimum consuming surface
- **Consumers**: Progress scrubber (player surfaces) and Lyrics highlight engine (via `PlaybackClockPort`)
- **Mechanism**: 🟡 OPEN — determined in Phase 4B-2 or implementation benchmarking

### 26.7 Lifecycle Contract

- UI lifecycle is structurally independent of the playback lifecycle.
- `MediaController` connects on `Activity.onStart()` and releases on `Activity.onStop()` (or `ViewModel.onCleared()`).
- `ExoPlayer` and `MediaSession` are scoped to `SonaraPlaybackService` — not tied to any UI component.
- `SonaraPlaybackService` starts on first `MediaController` connection; may stop when all connections are released and playback is not active (handled automatically by `MediaSessionService` base class).
- Restored sessions (after process recovery) are always in the PAUSED state and require explicit user playback initiation.

### 26.8 System Media Control Contract

- All external control surfaces route through `MediaSession`.
- `MediaSession.Callback` is the single entry point for all external commands.
- Callback commands are applied directly to `ExoPlayer` inside the service.
- `MediaMetadata` exposed to the system is sourced from `MediaItem.mediaMetadata` set at queue construction time.

### 26.9 Error Contract

- `SonaraPlaybackService` is the central error detection and classification point for playback-related errors.
- Classified errors are communicated to the UI via custom `MediaSession` commands or `Player.Listener.onPlayerError()`.
- `PlayerViewModel` maps errors to typed `PlayerUiState.ErrorState` values.
- Transient errors trigger recovery before surfacing to UI.
- Permanent errors cause item skip and UI notification.
- No error flags are permanently written to the relational database. All negative caches have bounded TTLs.

### 26.10 Testing Contract

- All pure domain engines must be fully testable on the JVM without the Android SDK on the classpath.
- `StreamResolver` is testable with fake `StreamSource` implementations and `MockWebServer`.
- Rapid skip cancellation and stream recovery use Media3 test utilities or instrumented tests.
- `PlayerViewModel` is testable with a fake `MediaController` / fake `Player` implementation.
- Physical device regression runs on the primary development device (Redmi Note 12 Pro, API 34).

---

## 27. Phase 4B-1 Decision Register

| Decision Area | Status | Final Decision | Rationale | Alternatives Rejected | Phase 4B Follow-up |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Player Ownership** | ✅ DECIDED | `ExoPlayer` lives exclusively in `SonaraPlaybackService` | Lifecycle independence, background playback, system controls, audio focus | Activity/ViewModel ownership, multiple player instances | None |
| **Service Boundary** | ✅ DECIDED | `SonaraPlaybackService extends MediaSessionService` | `MediaSession`, notification management, lifecycle automation | Plain `ForegroundService`, `MediaLibraryService` | None |
| **MediaController Placement** | ✅ DECIDED | Client-side, in `PlayerViewModel` (application process) | UI-side bridge; not part of service internals | Inside service (architecturally incorrect) | None |
| **Queue Authority** | ✅ DECIDED | `ExoPlayer` `Timeline` is the only queue | Eliminates dual-source-of-truth race conditions | Separate Sonara queue, ViewModel-owned queue | None |
| **Queue Mutation Authority** | ✅ DECIDED | `MediaController` queue commands only | Single entry point; ordering guaranteed by Binder | Direct ExoPlayer access from UI, parallel mutators | None |
| **Rapid Skip Cancellation** | ✅ DECIDED | Last-writer-wins; single active resolution job slot; cancellation before new job | Prevents stale results affecting player state | Debounce (lag), serial queue (wrong result order) | None |
| **Audio Focus** | ✅ DECIDED | Delegated to Media3's built-in `AudioFocusManager` | Zero boilerplate; no conflict risk; verified by documentation | Custom `OnAudioFocusChangeListener` | None |
| **System Controls Routing** | ✅ DECIDED | All surfaces route through `MediaSession` | Single command entry point; no surface-specific logic | Per-surface command paths | None |
| **Stream Resolution Boundary** | ✅ DECIDED | `StreamResolverPort` + `StreamResolver`; not embedded in service | Provider independence, replaceability, testability | Stream resolution inside service or domain | None |
| **Stream URL Persistence** | ✅ DECIDED | Stream URLs NEVER persisted to disk | Prevents expired token playback on restore | Disk caching of stream URLs | None |
| **Continuity Engine Boundary** | ✅ DECIDED | Invoked via `ContinuityPort`; engine has zero Android dependencies | Domain independence; testable without Android SDK | Direct Android-to-engine dependency | None |
| **Continuity Prefetch** | ✅ DECIDED | Continuity preparation begins while the final queued track is still playing, before `STATE_ENDED` | Prevents audible playback gaps and focus transitions | Waiting for `STATE_ENDED` (causes silence) | None |
| **Player State Conflation** | ✅ DECIDED | `PlayerViewModel` exposes coherent aggregated UI state rather than independently committing every `Player.Listener` callback | Prevents Compose state tearing and transient inconsistencies | Direct 1:1 emission of callbacks to UI | None |
| **Session Recovery Playback State** | ✅ DECIDED | Restored sessions enter `PAUSED` state and require explicit user playback initiation | Adheres to Android background execution guidelines | Auto-playing on restart | None |
| **Notification Management** | ✅ DECIDED | Delegated to `MediaSessionService` base class | Handles foreground state, creation, and update automatically | Custom `NotificationManager` | None (custom actions Post-MVP) |
| **Background Playback** | ✅ DECIDED | Foreground `MediaSessionService` with `foregroundServiceType="mediaPlayback"` | Required by Android 14+ for background media playback | WorkManager (wrong tool), no background playback | None |
| **Process Isolation** | ✅ DECIDED | Single OS process (no `android:process` for service) | IPC overhead not justified for single-app player | Separate OS process for service | None |
| **Error Recovery Model** | ✅ DECIDED | Tiered: refresh → fallback → skip; bounded negative caches only | Prevents poisoning; provides graceful degradation | Permanent failure flags, single-shot retry | None |
| **History Write Authority** | ✅ DECIDED | `SonaraPlaybackService` writes to `LibraryRepository` on qualifying completion | Service has authoritative play event data | ViewModel-side history write | Phase 4B-3: define qualifying threshold |
| **State Ownership** | ✅ DECIDED | Media3 authoritative for playback state ONLY; separate repos for all other domains | Prevents conflation of domain state with player state | Single global player-as-truth-for-everything | None |
| **High-Frequency Position Mechanism** | 🟡 OPEN | Isolated from broad state; exact mechanism TBD | Principle decided; implementation measurement needed | Global root-level `StateFlow<Long>` (rejected) | Phase 4B-2 or implementation benchmarking |
| **Session Snapshot Mechanism** | 🟡 OPEN | Required to survive process death; format TBD | Requirement decided; serialization format not specified | — | Phase 4B-3 persistence architecture |
| **Media Resumption API** | 🟡 OPEN | Opt-in API for post-reboot resumption; evaluation deferred | Adds user value; requires additional service callback implementation | — | Phase 4B-3 |

---

## 28. Implementation Details Deferred

### Deferred to Phase 4B-2 (State & UI Architecture)
- Exact `StateFlow` type hierarchy for `PlayerUiState`
- Exact `PlaybackClockPort` interface definition and chosen high-frequency mechanism
- Exact `UiEvent` sealed class hierarchy
- Exact `PlayerViewModel` coroutine scope and lifecycle structure

### Deferred to Phase 4B-3 (Persistence Architecture)
- Session snapshot serialization format (JSON file, DataStore Proto, or Room)
- Session snapshot write frequency policy
- Media Resumption API opt-in decision
- Room entity schemas for Liked Songs and History
- DataStore preference key definitions
- History write qualifying threshold (e.g., >30 seconds played)

### Deferred to Phase 4B-4+ (Other Architecture Phases)
- Application package identifier (`[SONARA_APPLICATION_PACKAGE]`)
- `compileSdk` version selection
- Navigation graph route definitions
- `AppContainer` dependency graph wiring
- Gradle artifact coordinates and versions

### Deferred to Phase 5 (Implementation)
- Exact Kotlin class names, package paths, and file locations
- `ExoPlayer.Builder` configuration
- `MediaSession.Builder` configuration
- `RefreshingDataSource` implementation details
- `ResolvingDataSource` configuration
- Coroutine dispatcher assignments
- `AndroidManifest.xml` service declaration
- Exact `Player.Listener` callback implementations
- Exact `StateFlow` emission logic in `PlayerViewModel`

### Deferred to Implementation Verification
- High-frequency position mechanism final selection (requires on-device benchmarking)
- Notification bitmap loading behavior verification

---

## 29. Risks & Remaining Questions

| Risk | Likelihood | Mitigation |
| :--- | :--- | :--- |
| `RefreshingDataSource` URL swap does not resume at correct millisecond position | Medium | Integration test with simulated 403 at various playback offsets using `MockWebServer` (Phase 5) |
| `MediaSessionService` auto-notification insufficient for Sonara's artwork rendering | Low | Validate on physical device (Phase 5); custom `MediaNotificationManager` if needed |
| High-frequency position mechanism causes battery regression on lower-end devices | Medium | Benchmark all three candidates on primary test device; measure CPU and battery impact |
| `ContinuityPort` latency blocking when queue reaches exhaustion | Low | Pre-compute candidate list N tracks before queue end as a Phase 4B-3 optimization consideration |
| History write from service duplicating records on service restart | Medium | Phase 4B-3: define history write idempotency via timestamp + trackId composite deduplication |
| Process death snapshot restores incorrect position after stream expiry | Low | Fresh URL always resolved on restore; position is valid regardless of URL validity |

### Remaining Open Questions for External Review

1. Should `ContinuityEngine` pre-compute candidates proactively (N tracks before queue exhaustion) rather than reactively at exhaustion? Affects latency but adds trigger complexity.
2. Should the MVP notification include a like/heart action, or is play/pause/skip sufficient for V1.0?
3. Should Sonara implement the Android Media Resumption API from MVP, or is this a V1.1 addition?
4. What is the history write qualifying threshold? (e.g., >30 seconds played, >50% duration, or both?)

---

## 30. Sources / Evidence

| Source | Type | Used For |
| :--- | :--- | :--- |
| **Sonara Android — Requirements Specification V1.2** | Approved Product Requirements | Deriving all functional requirements driving the playback architecture |
| **`PHASE_4A_ARCHITECTURE_DISCOVERY.md`** | Frozen Architecture Baseline | Established foundations, anti-patterns, Web engineering lessons, state ownership taxonomy |
| **Android Developer Documentation — `MediaSessionService`** | Official Platform Documentation | Verified: service lifecycle, Android 14 foreground type requirements, notification management |
| **Android Developer Documentation — `MediaController`** | Official Platform Documentation | Verified: client-side `Player` interface, `buildAsync()` connection lifecycle, Binder IPC |
| **Media3 Audio Focus Documentation** | Official Platform Documentation | Verified: `AudioAttributes` with `handleAudioFocus=true` handles all focus cases automatically |
| **Media3 `ResolvingDataSource.Factory` documentation** | Official Platform Documentation | Verified: mechanism for transparent stream URL refresh on HTTP errors without track restart |
| **`media3-ui-compose` documentation (Media3 1.6.0+)** | Official Platform Documentation | Verified: Compose state holders bridge `Player` interface to Compose declaratively |
| **Sonara Web `PlayerContext.jsx`** (Web reference — read-only) | Engineering Lesson Archive | Anti-pattern: 570-line god object; directly informs service boundary and queue ownership decisions |
| **Sonara Web playback race condition analysis** (Phase 4A lessons) | Engineering Lesson Archive | Informs rapid skip cancellation model and queue single-authority rule |
| **Community documentation: Custom DataSource for 403/stream refresh** | Verified Community Source | Confirmed `RefreshingDataSource` wrapper is the established approach for mid-playback token expiry |
