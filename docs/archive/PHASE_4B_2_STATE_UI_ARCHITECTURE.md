# SONARA ANDROID — PHASE 4B-2 STATE & UI ARCHITECTURE

## 1. Executive Summary

This document establishes the **State & UI Architecture** for Sonara Android. It defines how data flows from the domain/infrastructure layers to Jetpack Compose, how user intents are handled, and how state is preserved across configuration changes and process death.

The primary architectural paradigm is **Pragmatic Layered Unidirectional Data Flow (UDF)**. It avoids the heavy boilerplate of strict MVI (Model-View-Intent with single event streams) in favor of state-driven UI with method-based intent handling. It enforces strict isolation of high-frequency playback position state to prevent recomposition bottlenecks, and mandates the modern Compose pattern of "state-driven effects" over unreliable one-shot event channels.

---

## 2. Tool / MCP / Skill Usage

| Tool / Resource | Usage in This Phase |
| :--- | :--- |
| **`search_web` (Android Docs)** | ✅ Verified Google's current guidance on one-shot events (State-driven effects vs Channels) and high-frequency state management in Compose (deferred state reading). |
| **`ponytail` (Mental Model)** | ✅ Enforced pragmatism. Rejected strict MVI event-buses, "God ViewModels", and over-engineered `Channel` wrappers in favor of simple state modeling. |
| **Phase 4B-1 Document** | ✅ Referenced to strictly preserve the frozen playback boundaries. |
| **Code Refactoring/Serena** | ❌ Not applicable yet; no implementation exists. |

---

## 3. Frozen Dependencies from Phase 4B-1

The following playback architecture constraints are **FROZEN** and cannot be circumvented by the UI architecture:

1. **`ExoPlayer` is authoritative** for actual playback state.
2. **`MediaSessionService`** owns actual playback execution.
3. **`MediaController`** is the only UI-side bridge to the player.
4. **`PlayerViewModel`** is purely a UI projection/aggregation layer, not a second source of truth.
5. **State Conflation**: `PlayerViewModel` must aggregate `Player.Listener` callbacks into coherent UI state to prevent tearing.
6. **Restoration**: Session recovery restores into a `PAUSED` state.

---

## 4. State Architecture Requirements

| Requirement | Architectural Impact |
| :--- | :--- |
| **Native Jetpack Compose** | UI must observe state via `StateFlow` and `collectAsStateWithLifecycle()`. |
| **One-Way Data Flow** | UI emits events to ViewModels; ViewModels mutate state; UI observes state. |
| **Lifecycle-Aware** | State observation must pause when screens are not visible to save resources. |
| **No Recomposition Storms** | Playback position (60fps/100ms updates) must not trigger full-screen recomposition. |
| **Offline-Capable UI** | Screens must gracefully handle cached partial data while loading fresh data. |
| **Separation of Concerns** | UI components must not know about databases, Media3, or networking. |

---

## 5. State Ownership Model

To prevent the "multiple sources of truth" failure mode seen in Sonara Web, every piece of state has exactly **one authoritative owner**.

| State Domain | Authoritative Owner | UI Projection (State Holder) | Scope / Persistence |
| :--- | :--- | :--- | :--- |
| **Playback (Playing, Queue)** | `ExoPlayer` | `PlayerViewModel` | Activity-scoped / Session Snapshot |
| **Library (Liked Songs)** | `LibraryRepository` (SQLite) | `LibraryViewModel` | Feature-scoped / Persistent |
| **History** | `LibraryRepository` (SQLite) | `HistoryViewModel` | Feature-scoped / Persistent |
| **Settings / Theme** | `SettingsRepository` (DataStore) | `SettingsViewModel` | Activity-scoped / Persistent |
| **Search Query / Results** | `SearchViewModel` (Query) / `SearchRepository` (Results) | `SearchViewModel` | Feature-scoped / Ephemeral |
| **Lyrics Data** | `LyricsRepository` (SQLite/Cache) | `LyricsViewModel` | Feature-scoped / Cache DB |
| **Ephemeral UI (Dialogs, Tabs)**| Compose Memory | Compose `rememberSaveable` | UI-scoped / `Bundle` |

**Invariant**: Feature ViewModels (e.g., `HomeViewModel`, `SearchViewModel`) must **NEVER** hold state about what is currently playing. They must remain completely ignorant of the playback engine.

---

## 6. ViewModel Architecture

### 6.1 Pragmatic UDF (Chosen Approach)

Sonara uses **Pragmatic UDF** (Method-based intents).

```kotlin
// UI Event triggers public method
fun onPlayTrackClicked(trackId: String) {
    viewModelScope.launch {
        // Mutate state or call domain
        _uiState.update { it.copy(isLoading = true) }
        playTrackUseCase(trackId)
    }
}
```

### 6.2 Rejected: Strict MVI

Strict MVI uses a single `onEvent(event: UiEvent)` entry point with a massive `when` statement.
**Why Rejected**: It introduces unnecessary boilerplate (sealed classes for every button click) without providing concrete benefits for a music player, as Sonara does not require event-sourcing or complex event replay.

### 6.3 ViewModel Responsibilities

**A ViewModel SHOULD:**
- Translate domain state into a single immutable `UiState` data class.
- Expose state via `StateFlow`.
- Provide public methods for the UI to signal user intents.
- Coordinate with Repositories or Use Cases using `viewModelScope`.
- Survive configuration changes (screen rotations).

**A ViewModel MUST NOT:**
- Import or hold references to `android.content.Context`, `View`, or Compose `Modifier` / UI elements.
- Hold direct references to `ExoPlayer` or playback services.
- Communicate directly with other ViewModels (ViewModel-to-ViewModel coupling is forbidden).
- Perform direct I/O (file/disk) or execute network/persistence operations directly (must delegate to Repositories / UseCases).
- Perform I/O or long-running work inside constructors.
- Resolve Android Resources (`R.string.*`, `R.drawable.*`, `Resources`, `Locale`) directly.

---

## 7. PlayerViewModel Boundary

The `PlayerViewModel` is a unique, Activity-scoped (or Root NavGraph-scoped) ViewModel because playback state (Mini Player) is visible across multiple screens within the same active UI host. It is explicitly NOT an Application singleton and must not become a global process-wide state container.

### 7.1 Responsibilities
- **Holds `MediaController`**: Establishes the Binder connection to `SonaraPlaybackService`.
- **State Conflation**: Registers a `Player.Listener`, catches all callbacks, and aggressively batches them into a single `_playerUiState.update { ... }` block to prevent Compose tearing.
- **Exposes Commands**: Provides methods like `play()`, `pause()`, `skipToNext()` that delegate to `MediaController`.

### 7.2 UI Exposure
It exposes an aggregated data class:
```kotlin
data class PlayerUiState(
    val currentTrack: TrackUiModel?,
    val isPlaying: Boolean,
    val playbackState: PlaybackState, // Buffering, Ready, Ended
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean
)
```

---

## 8. UDF / Event Flow

The strict one-way flow of data and events:

1. **User Action**: User taps a track.
2. **Intent**: Compose calls `viewModel.onTrackSelected(trackId)`.
3. **Execution**: ViewModel calls `MediaController.play(trackId)` (or a UseCase).
4. **Side Effect**: Media3 begins loading the stream.
5. **Observation**: `MediaController` receives `onPlaybackStateChanged(BUFFERING)`.
6. **State Mutation**: `PlayerViewModel` updates `_playerUiState`.
7. **Recomposition**: `StateFlow` emits new state; Compose re-renders the Mini Player to show a loading spinner.

Data flows **down** (StateFlow). Events flow **up** (method calls).

---

## 9. StateFlow / Compose State Strategy

Sonara utilizes a specific hierarchy of state mechanisms:

| Mechanism | Use Case | Lifecycle |
| :--- | :--- | :--- |
| **`StateFlow`** | Durable feature state exposed by ViewModels | Survives config changes; collected via `collectAsStateWithLifecycle` |
| **`mutableStateOf`** | Ephemeral UI state (e.g., is dropdown expanded) | Scoped to Composable using `remember` / `rememberSaveable` |
| **`derivedStateOf`** | Expensive computations derived from frequently changing state | Scoped to Composable |
| **`snapshotFlow`** | Bridging Compose state into Coroutines (e.g., reacting to scroll position) | Scoped to `LaunchedEffect` |

---

## 10. High-Frequency Playback Position

### 10.1 The Recomposition Bottleneck
Playback position changes every 16ms-100ms. If a ViewModel exposes this in the root `PlayerUiState`, the entire screen (Scaffold, lists, artwork) will recompose continuously, destroying performance.

### 10.2 Architectural Rule: Deferred State Reading ✅ DECIDED
Position state must **bypass** the main UI state object. It is exposed as a separate `StateFlow<Long>` (or similar observable).

The UI must consume it using **Deferred Reads** or **Isolated Composables**:

**Pattern A: Isolated Composable (Preferred for Scrubber)**
```kotlin
// The parent does NOT read the position. It just passes the flow or viewmodel.
@Composable
fun ExpandedPlayerScreen(viewModel: PlayerViewModel) {
    // Artwork, Title (Recomposes only on track change)
    TrackInfo(...) 
    
    // Tiny isolated composable that collects the high-frequency state internally
    PlaybackScrubber(positionFlow = viewModel.positionFlow) 
}
```

**Pattern B: Lambda Deferral (Preferred for text)**
```kotlin
// The value is read only during the layout/draw phase, preventing parent recomposition
@Composable
fun PlaybackTimeText(positionProvider: () -> Long) {
    Text(text = formatTime(positionProvider()))
}
```

**What MUST NOT observe it:** The root `ExpandedPlayerScreen`, `MiniPlayer`, or root `LyricsScreen` composables must not directly subscribe to high-frequency position if doing so causes broad screen-level recomposition.
**What MAY observe it:** Feature UI (including Lyrics and Scrubbers) may consume the playback clock safely through isolated child components (e.g., `LyricsSynchronization` or `PlaybackScrubber`).

---

## 11. Screen State Model

### 11.1 Decision: Data Classes over Sealed Classes for Content Screens ✅ DECIDED

While `sealed interface { Loading, Success, Error }` is common, it fails for apps that need to show cached data while refreshing (Swipe-to-Refresh) or pagination.

**Sonara Model**: A single data class representing the holistic state of a screen. The ViewModel projects domain models into presentation-safe UI models (`TrackUiModel`), preventing Compose from directly owning arbitrary domain models where presentation formatting applies.

```kotlin
data class HomeUiState(
    val isLoading: Boolean = false,       // True during pull-to-refresh
    val isInitialLoading: Boolean = true, // True only on first load
    val recentTracks: List<TrackUiModel> = emptyList(), // Presentation model, not raw domain entity
    val errorMessage: UiText? = null      // State-driven effect / UI text abstraction
)
```

**Why**: This allows Compose to render the `recentTracks` from the local database (projected into `TrackUiModel`s) while simultaneously showing a subtle loading spinner for the network refresh, rather than wiping the screen to show a central `Loading` state.

---

## 12. Feature State Boundaries

Features are heavily decoupled.

| Feature | ViewModel | State Sources | Interactions |
| :--- | :--- | :--- | :--- |
| **Home** | `HomeViewModel` | `LibraryRepository` (History, Liked), `CatalogRepository` | Navigates to Player/Queue/Catalog |
| **Search** | `SearchViewModel` | `SearchRepository` (External Music API Providers) | Ephemeral query state |
| **Library** | `LibraryViewModel`| `LibraryRepository` | Filter/Sort ephemeral state |
| **Lyrics** | `LyricsViewModel` | `LyricsRepository`, `PlaybackClock` | Syncs active line index |

---

## 13. Shared Application State

**State is only shared if strictly necessary.**

1. **`PlayerViewModel`**: Shared across the active UI host (Activity-scoped or root NavGraph-scoped) because the MiniPlayer is persistent.
2. **`SettingsViewModel`**: Shared at the root `setContent` block to provide the global Compose `MaterialTheme`.

All other ViewModels are scoped strictly to their respective navigation destinations.

---

## 14. One-Shot Effects (Events)

### 14.1 Decision: State-Driven Effects ✅ DECIDED

Following current Google Architecture guidelines, Sonara uses **State-Driven Effects** as the default mechanism for one-shot events (Snackbars, transient UI messages, dismissible effects).

**How it works (e.g., Error Snackbar):**
1. ViewModel sets `state = state.copy(errorMessage = "Network failed")`
2. Compose observes the non-null string and displays a Snackbar.
3. Compose calls `viewModel.onErrorShown()`
4. ViewModel sets `state = state.copy(errorMessage = null)`

**Navigation Exception & Safety:**
If navigation is represented through state, it requires explicit immediate clearance semantics to prevent duplicate navigation on rotation/recomposition. The sequence must be: navigation effect observed -> navigation occurs -> effect is immediately cleared. 
If strict fire-and-forget semantics are genuinely required and replay must be structurally impossible, a `Channel` (consumed via `LaunchedEffect`) may be considered as a targeted exception, but State remains the architectural default.

---

## 15. Lifecycle & Restoration

### 15.1 Configuration Changes (Rotation, Resize)
- ViewModels survive.
- `StateFlow` retains the last emitted state.
- Compose immediately re-renders the exact state.
- No network requests are re-triggered.

### 15.2 Backgrounding
- `collectAsStateWithLifecycle()` automatically stops collecting Flows when the app goes to the background.
- This prevents ViewModels from wasting CPU updating UI state that isn't visible.

### 15.3 Process Death
- **Navigation State**: `SavedStateHandle` preserves simple arguments (e.g., `albumId`).
- **Feature Data**: Reloaded from SQLite (`LibraryRepository`) on restart.
- **Playback Session**: Handled by `SonaraPlaybackService` snapshot (restores to `PAUSED` as per Phase 4B-1).

---

## 16. Navigation State Boundary

### 16.1 Rule: Pass IDs, Not Objects ✅ DECIDED

**Prohibited**: Passing a `Track` or `Album` object as a Parcelable navigation argument.
**Required**: Pass the `trackId` or `albumId` as a String/Int. The destination ViewModel uses `SavedStateHandle` to retrieve the ID and immediately queries the Repository/Cache for the full object.

**Why**:
1. Prevents `TransactionTooLargeException` when passing large data.
2. Ensures the destination screen always has the most up-to-date representation of the object from the single source of truth.

---

## 17. Compose Recomposition Strategy
1. **State Hoisting**: State is hoisted to the lowest common ancestor that requires access to that state (hoist only as high as necessary, but no higher).
2. **Stable Parameters**: Data classes passed to Composables must be immutable (using `val`).
3. **Keyed Lists**: All `LazyColumn` / `LazyRow` items must use stable `key` parameters to prevent UI jumping and unnecessary recomposition during list mutations.
4. **Lambda Callbacks**: Prefer passing method references `viewModel::onPlayClicked` over inline lambdas where performance matters, as method references are inherently stable.

---

## 18. UI / Domain Separation

The UI layer (Compose + ViewModels) is forbidden from dictating domain logic.

**Boundary Enforcement**:
- ViewModels cannot perform file I/O or network calls. They must call a Repository or UseCase.
- ViewModels must NOT perform presentation formatting that requires Android Context, Resources, Locale, or pluralization.
- ViewModels must expose raw domain/presentation data (e.g., `durationMs: Long`) or a UI-safe text abstraction (e.g., a `UiText` resource wrapper).
- The Compose UI layer (which safely holds Context/Configuration access) is strictly responsible for the final localized string formatting (e.g., using `stringResource`).

---

## 19. Error & Loading State Architecture

1. **Detection**: `StreamResolver` or `LibraryRepository` catches the network/IO exception.
2. **Classification**: Repository throws a typed domain exception (e.g., `NetworkUnavailableException`).
3. **Transformation**: ViewModel catches the domain exception and transforms it into a UI-safe error abstraction (e.g., a typed presentation error or `UiText` abstraction), without referencing `R.string.*` or Android `Context` directly.
4. **Display**: Emitted in `UiState.errorMessage` and resolved to a localized string at the UI/presentation boundary.
5. **Clearance**: UI calls `onErrorMessageShown()` (State-driven effect).

---

## 20. Testing Architecture

| Component | Testing Strategy | Tooling |
| :--- | :--- | :--- |
| **ViewModels** | Unit tests. Verify `StateFlow` emissions sequentially. | JUnit, Coroutines Test, Turbine |
| **State Reduction** | Unit tests. Verify `UiState` mutations given specific intents. | JUnit |
| **Compose UI** | UI Tests. Verify rendering of static `UiState` objects. | Compose Test Rule, Robolectric |

By isolating UI state into pure data classes, 90% of UI logic can be tested in pure JVM unit tests without an emulator.

---

## 21. Alternatives & Rejected Approaches

| Alternative | Rejection Reason |
| :--- | :--- |
| **Strict MVI (`onEvent` reducer)** | Too much boilerplate for simple screens. Method-based intents are more pragmatic. |
| **`Channel` as Default for Events** | Rejected as default state/effect mechanism (vulnerable to event loss/replay across lifecycle). State-driven effects are the DEFAULT. Channel is permitted only as a narrow exception for strict fire-and-forget navigation where replay must be structurally impossible. |
| **Passing Parcelables in Nav** | Risks `TransactionTooLargeException`; breaks single-source-of-truth if DB updates. |
| **God ViewModel** | Monoliths are unmaintainable. Features must have their own ViewModels. |
| **Global `PlaybackPosition` State** | Causes catastrophic app-wide recomposition storms. |

---

## 22. Final Conceptual Architecture

```
═══════════════════════════════════════════════════════════════════════════
                              COMPOSE UI LAYER
═══════════════════════════════════════════════════════════════════════════
 
   ┌─────────────┐       ┌─────────────┐       ┌───────────────┐
   │ Home Screen │       │ Search Screen│       │ Player Screen │
   └──────┬──────┘       └──────┬──────┘       └───────┬───────┘
          │ (Events)            │                      │ (Commands)
          ▲ (StateFlow)         ▲                      ▲ (StateFlow)
═══════════════════════════════════════════════════════════════════════════
                           VIEWMODEL LAYER (UDF)
═══════════════════════════════════════════════════════════════════════════

   ┌───────────────┐     ┌────────────────┐    ┌─────────────────┐
   │ HomeViewModel │     │ SearchViewModel│    │ PlayerViewModel │
   │ (Feature)     │     │ (Feature)      │    │(Activity-Scoped)│
   └──────┬────────┘     └──────┬─────────┘    └───────┬─────────┘
          │                     │                      │
          ▼                     ▼                      ▼ (MediaController)
═══════════════════════════════════════════════════════════════════════════
                          DOMAIN / INFRASTRUCTURE
═══════════════════════════════════════════════════════════════════════════
 
   ┌───────────────┐     ┌────────────────┐    ┌─────────────────┐
   │ LibraryRepo   │     │ Search/Catalog │    │ PlaybackService │
   │ (SQLite)      │     │ Repositories   │    │ (Media3 / Auth) │
   └───────────────┘     └────────────────┘    └─────────────────┘
```

---

## 23. Architectural Contracts

### State Ownership Contract
Every piece of state must have exactly one authoritative owner. ViewModels project state; they do not duplicate ownership.

### UI Event Contract (Pragmatic UDF)
Events flow up via explicit method calls on the ViewModel. State flows down via immutable `StateFlow` snapshots.

### High-Frequency State Contract
Playback position must be read using **Deferred Reads** (lambdas) or inside **Isolated Composables** that collect the flow internally. Feature screens may observe playback position through these isolated components, but broad root-screen observation is prohibited.

### One-Shot Effect Contract
One-shot events must be modeled as State (State-driven effects) and cleared by the UI immediately upon consumption. Navigation effects must have explicit consumption semantics to prevent accidental replay after rotation.

### Lifecycle Contract
All UI state observation must use `collectAsStateWithLifecycle()` to suspend processing when the app is backgrounded.

### Navigation Contract
Navigation arguments must be simple IDs (Strings/Ints), never complex Parcelable domain objects.

---

## 24. Phase 4B-2 Decision Register

| Decision | Status | Final Decision | Rationale | Alternatives Rejected | Follow-up |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **UDF Paradigm** | ✅ DECIDED | Pragmatic UDF (Method-based intents) | Less boilerplate; simpler tracing | Strict MVI (single event stream) | None |
| **UI Formatting Resp.** | ✅ DECIDED | ViewModels expose raw presentation data or UI-safe text abstractions. Final localized string formatting belongs to the UI/presentation layer. | Prevents Android Context injection into ViewModels. | ViewModel exposing pre-formatted `String` | None |
| **One-Shot Events** | ✅ DECIDED | State-driven effects remain the default, with explicit immediate consumption/clearance semantics for navigation. | Survives process death; prevents replay. | Defaulting to `Channel` | None |
| **High-Freq Position** | ✅ DECIDED | Feature UI may observe playback position through isolated components; broad root-screen observation is prohibited. | Prevents whole-screen recomposition jank | Root `UiState` holding 60fps clock | Implementation benchmarking |
| **PlayerViewModel Scope**| ✅ DECIDED | Activity-scoped or Root Navigation Graph-scoped; never an Application singleton. | Safe sharing across active UI host without leaking. | Application-scoped Singleton | None |
| **Screen State Model** | ✅ DECIDED | Single Data Class with `isLoading` flags | Supports partial data / cached data display | Strict Sealed Classes (Loading/Success/Error) | None |
| **Nav Arguments** | ✅ DECIDED | Pass IDs only; fetch from Repo | Prevents payload limits; ensures fresh data | Passing full `Parcelable` objects | None |
| **State Hoisting** | ✅ DECIDED | Hoist state to the lowest common ancestor that requires it. | Prevents overly broad recomposition scope. | Hoisting to highest possible level | None |
| **State Observation** | ✅ DECIDED | `collectAsStateWithLifecycle()` | Saves battery by pausing in background | `collectAsState()` (wastes CPU) | None |

---

## 25. Implementation Details Deferred

- **Exact Kotlin classes**: e.g., `HomeUiState`, `PlayerUiState` concrete definitions.
- **Exact Compose Navigation**: `NavHost` implementation, route objects.
- **Exact Dependency Injection**: Hilt/Koin wiring for ViewModels.
- **Exact Coroutine Scopes**: Dispatcher injection for testing.
- **Compose component granularity**: Exact extraction of composables into separate files.

---

## 26. Risks & Remaining Questions

| Risk | Likelihood | Mitigation |
| :--- | :--- | :--- |
| **State-driven effect boilerplate** | Medium | Establish a clean base interface or extension function for handling `consumeMessage()` logic across ViewModels. |
| **Position Scrubber Jank** | Low | Strict enforcement of the Deferred State Reading contract during code review. |

**Questions for External Review (Phase 4B-3 Transition):**
1. Does the separation of `PlayerViewModel` (Activity-scoped) from Feature ViewModels (Screen-scoped) align with the intended multi-screen mini-player requirement?
2. Are there any custom transitions/animations required that might necessitate holding layout state in the ViewModel? (Standard Material 3 transitions operate entirely in Compose memory).

---

## 27. Sources / Evidence

- **Phase 4B-1 Architecture**: Preserved all playback boundaries and Media3 constraints.
- **Google Architecture Guidelines**: Verified State-driven effects over Channels.
- **Jetpack Compose Performance Docs**: Verified Deferred State Reading via lambdas for high-frequency updates.
- **Sonara Web Lessons**: Avoided the God ViewModel by strictly scoping feature states (unlike Web's `PlayerContext` monolith).
