# SONARA ANDROID — PHASE 4C NETWORKING RESILIENCE & BEHAVIORAL SPECIFICATION

## 1. Executive Summary

This document establishes the **Networking Resilience & Behavioral Specification** for Sonara Android. While previous phases (4A through 4B-3) established **where** architectural responsibilities live and **how** data and state flow through the system, Phase 4C defines **how Sonara behaves under real-world, unpredictable, and adverse network conditions**.

Mobile network connectivity is inherently non-binary. A device is rarely just "online" or "offline"; it encounters captive portals, high packet loss, multi-second latency, transient DNS failures, mid-stream socket resets, Wi-Fi to cellular handovers, and rate-limiting upstream providers. 

This specification acts as the definitive behavioral contract for the implementation phase. It dictates exact UI transitions, caching fallbacks, automatic retry boundaries, request cancellation rules, and playback continuity policies so that Sonara remains responsive, graceful, and predictable regardless of network quality.

---

## 2. Frozen Architecture Dependencies

Phase 4C is a behavioral specification that sits strictly on top of the established, frozen architectural foundations:

1. **`PHASE_4A_ARCHITECTURE_DISCOVERY.md`**: Native Android, Kotlin, Jetpack Compose, Material 3, Android-first design authority.
2. **`PHASE_4B_1_PLAYBACK_ARCHITECTURE.md`**: `ExoPlayer` inside `SonaraPlaybackService` is the single playback authority; `MediaController` is the client bridge; `StreamResolverPort` owns audio URL resolution; mid-stream HTTP 403 refresh is handled via custom `DataSource`; restored sessions enter `PAUSED`.
3. **`PHASE_4B_2_STATE_UI_ARCHITECTURE.md`**: Pragmatic Layered UDF; ViewModels project state and do not resolve Android resources (`R.string.*` / `Context`); State-driven effects are default; high-frequency playback position is isolated from root-screen recomposition.
4. **`PHASE_4B_3_DATA_PERSISTENCE_NETWORKING_ARCHITECTURE.md`**: Repositories are single sources of truth; `CatalogRepository` owns deep entity data; `SearchRepository` owns queries; `LibraryRepository` owns Room persistence; Proto DataStore owns session snapshots; manual negative caching of artwork/media is strictly forbidden.

**Invariant**: Phase 4C does NOT alter, reopen, or redesign any architectural boundaries from Phases 4A–4B-3.

---

## 3. Tool / MCP / Skill Usage

| Tool / Resource | Classification | Usage in Phase 4C |
| :--- | :--- | :--- |
| **`search_web` (Android & Media3 Docs)** | ✅ REQUIRED | Verified official Android guidance on `ConnectivityManager.NetworkCallback` (`NET_CAPABILITY_VALIDATED`), `LoadErrorHandlingPolicy` in Media3, and OkHttp cancellation semantics. |
| **`ponytail` (Mental Model)** | ✅ REQUIRED | Enforced pragmatic simplicity: avoided bloated global network state machines; prioritized bounded, predictable behaviors over complex retry heuristics. |
| **Context7 / Serena** | ⏸ RESERVED | Reserved for code generation and refactoring during the upcoming implementation phases. |
| **Reticle / Playwright** | ⏸ RESERVED | Reserved for automated UI and network chaos testing during verification phases. |

---

## 4. Network Behavior Model

Sonara rejects the naive binary model of "Connected vs Disconnected". The system recognizes four distinct layers of network reality:

```text
┌─────────────────────────────────────────────────────────────┐
│ 1. Network Connectivity  (Link up: Wi-Fi / Cellular active) │
├─────────────────────────────────────────────────────────────┤
│ 2. Network Usability     (Valid IP, DNS resolves, WAN reach)│
├─────────────────────────────────────────────────────────────┤
│ 3. Provider Availability (Target API server up, HTTP 200/404)│
├─────────────────────────────────────────────────────────────┤
│ 4. Request Success       (Valid JSON schema, non-empty data)│
└─────────────────────────────────────────────────────────────┘
```

### 4.1 Recognized Network States

| State | Definition | System Characteristics |
| :--- | :--- | :--- |
| **A. Healthy Connectivity** | Low latency (<500ms), high bandwidth, zero packet loss, validated WAN reachability. | Requests succeed quickly; playback buffers smoothly. |
| **B. Degraded Connectivity** | High latency (>2000ms), low bandwidth (2G/throttled), or high jitter. | Requests take long; images load slowly; audio buffering delays. |
| **C. Intermittent Connectivity** | Frequent packet drop, flapping interface, captive portals without internet. | Sockets reset mid-flight; requests timeout intermittently. |
| **D. No Usable Connectivity** | Airplane mode, radio off, interface disconnected, or unvalidated WAN. | Immediate socket/DNS failure (`UnknownHostException`). |
| **E. Provider Unavailable** | Network is healthy, but music API returns 502/503/504, Cloudflare block, or 429. | HTTP errors with healthy local network link. |
| **F. Request-Specific Failure** | Endpoint returns 404 Not Found, corrupted body, or unparseable JSON. | Single entity failure; rest of provider remains functional. |
| **G. Recovery / Reconnection** | Interface transitions from unvalidated/disconnected to validated WAN. | Reconnection event fires; paused operations evaluate resumption. |

---

## 5. Behavioral Principles

1. **Fail-Fast Over Pre-Flight Checking**: Sonara never guards API calls with `if (!isNetworkConnected())`. The system attempts requests and handles failures via typed domain exceptions. `ConnectivityManager` callbacks are used solely for UI hints and reconnection triggers.
2. **Never Retry Indefinitely**: All automated retries are strictly bounded (maximum attempts) and governed by exponential backoff with randomized jitter.
3. **Never Hammer Downed Providers**: Upstream 5xx errors or 429 rate limits must trigger backoff and immediate provider fallback, never aggressive rapid retries.
4. **Structured Cancellation is Absolute**: When a user navigates away or changes a search query, in-flight network requests and coroutines are cancelled immediately.
5. **Newer Requests Supersede Older Requests**: Out-of-order asynchronous responses are strictly rejected via generation IDs or coroutine cancellation. Older responses never overwrite newer state.
6. **Preserve Existing UI on Refresh Failure**: A failed refresh (pull-to-refresh or background revalidation) must NEVER blank an already populated screen. The existing cached data remains visible with a non-intrusive error banner.
7. **No Negative Cache Poisoning**: Transient network errors or temporary provider 404s are NEVER cached to disk or memory as permanent "not found" states.
8. **Differentiate Playback from Browsing**: Playback uses pre-buffered audio to survive network drops silently. Browsing immediately informs the user of failure.
9. **Zero Blocking Modals for Background Failures**: Non-critical failures (prefetch, lyrics, artwork) degrade silently without interrupting the active user flow.
10. **Automatic Recovery Must Not Surprise the User**: Safe operations (resuming a stalled stream or fetching missing artwork) auto-recover on reconnect; disruptive actions (navigating, re-executing searches, mutative posts) require user initiation.

---

## 6. Slow / Degraded Network

When bandwidth is low or latency is high:

### 6.1 UI & Loading Behavior
- **Immediate Feedback**: User actions immediately trigger a localized loading state (e.g., subtle progress bar, skeleton shimmer, or button spinner) promptly after a user-initiated operation.
- **Retain Existing Content**: When re-fetching or paginating under slow network, existing list items remain interactive. The UI does not freeze.

### 6.2 Timeout Policies
- **Differential Timeouts**:
  - Search Autocomplete / Queries: Fast timeout (implementation-tunable, ~5s–8s) to prevent queuing outdated keystrokes.
  - Catalog / Entity Metadata: Medium timeout (~10s–15s).
  - Stream URL Resolution: Strict timeout (~8s–10s) before falling back to secondary provider.
  - Image Loading: Background low-priority timeout (~15s) handled by Coil/Glide.

### 6.3 Cancellation & Degradation
- If the user interacts with another element while a slow request is pending, the slow request is cancelled immediately.
- If a slow request exceeds timeout, the system throws a `TimeoutException`, which maps to a friendly "Connection is slow. Tap to retry" UI message.

---

## 7. Request Lifecycle

Every network-bound operation follows a strict deterministic lifecycle:

```text
    [INITIATED] (User intent or auto-trigger)
         │
         ▼
    [EXECUTING] (Coroutine launched, HTTP socket open)
         │
         ├──────────────────────────────┬──────────────────────────────┐
         ▼                              ▼                              ▼
    [CANCELLED]                    [TIMEOUT]                      [HTTP ERROR]
    (User navigated / changed)    (Exceeded limit)               (4xx / 5xx)
         │                              │                              │
         ▼                              ▼                              ▼
    Drop silently                  Map Domain Error               Map Domain Error
                                        │                              │
                                        ▼                              ▼
                                 [RETRY EVAL] ─── Exceeded ───► [TERMINAL ERROR]
                                        │                              │
                                   Within Limit                        ▼
                                        │                      [UI STATE-EFFECT]
                                        ▼                      (Non-intrusive msg)
                                 [BACKOFF DELAY]
                                        │
                                        ▼
                                 [RE-EXECUTING]
```

### 7.1 Lifecycle Rules
- **Cancellation**: Coroutine cancellation immediately invokes `Call.cancel()` on the underlying HTTP client (e.g. OkHttp), tearing down the socket and releasing connection pool resources.
- **Deduplication**: If an identical idempotent GET request is already executing for the exact same resource ID, subsequent callers join the existing `Deferred` rather than firing a duplicate network request.

---

## 8. Search Network Behavior

Search is the most high-frequency, latency-sensitive network interaction in Sonara.

```text
User Types:   "a" ────────► "ar" ────────► "ari" ────────► "arijit"
              │             │              │               │
Debounce:     (300ms)       (300ms)        (300ms)         (300ms)
              │             │              │               │
HTTP Call:    [CANCELLED]   [CANCELLED]    [CANCELLED]     [FIRED ──► SUCCESS]
```

### 8.1 Rapid Query Changes
1. **Debounce**: A 300ms debounce window prevents network calls during active typing.
2. **Immediate Cancellation**: The moment the user types a new character, the coroutine for the preceding query is cancelled. Any in-flight HTTP request is aborted immediately.
3. **Out-of-Order Rejection**: Every search request is tagged with a monotonically increasing `queryGenerationId`. If a delayed response arrives for an older generation, it is discarded immediately without mutating UI state.

### 8.2 Slow Network During Search
- When a search request is in-flight:
  - If previous search results exist on screen, they remain visible with a subtle top-level linear progress indicator.
  - Results are swapped atomically only when the new query succeeds.

### 8.3 Disconnection & Reconnection
- **Disconnect**: If network drops while typing, the active search fails fast with a `NetworkUnavailableException`. The UI shows a clean "No connection. Check your network" state with a manual "Retry Search" button.
- **Reconnect**: Sonara **NEVER** automatically executes a pending search upon reconnection. The user may have abandoned the query or moved away. Reconnection updates the connectivity state; the user must tap "Retry" or edit the search query.

---

## 9. Browsing / Catalog Behavior

| Feature Area | Strategy | Offline / Failure Behavior | Stale Data Policy |
| :--- | :--- | :--- | :--- |
| **Home Screen** | `Cache-First, Background Revalidate` | Displays cached sections (Quick Picks, Recent). If network fails, shows cached data + subtle "Offline" badge. | Stale cache is acceptable until fresh network payload arrives. |
| **Artist Page** | `Network-First, Cache Fallback` | Attempts fresh load. If fails and disk/memory cache exists, displays cached artist bio/top tracks. If no cache, shows error screen with Retry. | Replaces cache upon successful network fetch. |
| **Album Page** | `Network-First, Cache Fallback` | Attempts fresh load for full tracklist. Falls back to cached album entity if available. | Long-lived cached tracklists remain usable while available and are revalidated on explicit user refresh or appropriate reconnection/revalidation opportunities. |
| **Track Metadata**| `Cache-First` | Reads local metadata from SQLite/Room or in-memory cache. Fetches remotely only if missing. | Updated only on explicit catalog refresh. |
| **Library (Liked)**| `Local-Only (Room)` | 100% functional offline. Never blocks on network. | Authoritative single source of truth. |
| **History** | `Local-Only (Room)` | 100% functional offline. Writes directly to local SQLite. | Authoritative single source of truth. |

---

## 10. Cache Behavior

Sonara implements strict cache hygiene to eliminate the failure modes observed in legacy web players.

### 10.1 Cache Scenarios
1. **Cache HIT (Fresh)**: Returns cached data immediately. No network request emitted.
2. **Cache HIT (Stale)**: Returns cached data immediately to populate UI, then fires an asynchronous background revalidation. If background revalidation succeeds, UI updates smoothly; if it fails, cached data remains visible without disrupting the user.
3. **Cache MISS**: Emits loading state, executes network request. On success, writes to cache and displays data. On failure, emits error state.
4. **Network Failure with Existing Cache**: Retains existing cached data on screen. Shows a non-intrusive snackbar ("Couldn't refresh").
5. **Corrupt / Unparseable Cache**: Silently purges the corrupted cache entry and falls back to a fresh network fetch.

### 10.2 Strict Rule on Negative Caching
- **Forbidden**: Writing "Track Not Found", HTTP 500 responses, or network timeout errors into the persistent cache.
- **Allowed**: Only validated, successful domain payloads (successful response, valid payload structure, and successful domain validation for a cacheable resource) may update persistent cache state. Failures or corrupted responses must never overwrite valid cached data.

---

## 11. Provider Failure & Fallback

Sonara's `CatalogRepository` and `SearchRepository` orchestrate external music providers using a transparent fallback pipeline.

```text
                  ┌────────────────────────────────────┐
                  │ Repository Request (e.g. Search)   │
                  └─────────────────┬──────────────────┘
                                    │
                                    ▼
                  ┌────────────────────────────────────┐
                  │  Primary Provider (e.g. YouTube)   │
                  └─────────────────┬──────────────────┘
                                    │
                         ┌──────────┴──────────┐
                         ▼                     ▼
                     [SUCCESS]             [FAILURE] (5xx / Timeout / Rate Limit)
                         │                     │
                         ▼                     ▼
                   Return Result      ┌────────────────────────────────────┐
                                      │  Secondary Provider (e.g. Saavn)   │
                                      └─────────────────┬──────────────────┘
                                                        │
                                             ┌──────────┴──────────┐
                                             ▼                     ▼
                                         [SUCCESS]             [FAILURE]
                                             │                     │
                                             ▼                     ▼
                                       Return Result      [ALL PROVIDERS EXHAUSTED]
                                                                   │
                                                                   ▼
                                                          Throw Domain Exception
```

### 11.1 Fallback Trigger Rules
- **Triggers Fallback**: 
  - Server errors: HTTP 500/502/503/504.
  - Rate limiting: HTTP 429.
  - Connection failures: Socket timeouts, host unreachable, connection resets.
  - Payload defects: Malformed or unparseable JSON schemas.
  - **Provider-Specific Absence**: An HTTP 404 returned by a specific provider when the requested entity/stream is simply not indexed on that particular catalog and may legitimately exist on alternative configured providers.
- **Does NOT Trigger Fallback**: 
  - **Definitive Content Absence**: The requested content is confirmed non-existent across all domains, is structurally invalid, or represents an illegal query syntax.
  - **User Cancellation**: The operation was cancelled by user action or navigation.
- **Partial Results**: If Primary Provider returns a valid list of 10 tracks, the system accepts it. Fallback is only invoked if the primary provider yields a total operational failure.

### 11.2 Bounded, Acyclic Fallback Rule
Provider fallback is strictly **bounded and acyclic**. A failed provider must not cause an already-attempted provider to be retried within the same resolution operation unless an explicitly defined recovery policy permits it.

$$\text{Primary Provider (Fails)} \longrightarrow \text{Secondary Provider (Fails)} \longrightarrow \text{STOP (Throw Domain Exception)}$$
*(Never: $\text{Primary} \longrightarrow \text{Secondary} \longrightarrow \text{Primary} \longrightarrow \dots$)*

This rule applies universally across `CatalogRepository`, `SearchRepository`, and `StreamResolverPort`.

---

## 12. Retry Policy

### 12.1 Automatic Retry Rules
Automatic retries are permitted **only** when all of the following conditions are met:
1. The HTTP method is idempotent (GET).
2. The error is transient (e.g. socket timeout, connection reset, 503 Service Unavailable).
3. The request has not been superseded or cancelled.
4. The maximum retry count (default: **2 retries**) has not been exceeded.

### 12.2 Exponential Backoff & Jitter
All automatic retries must calculate backoff delay using exponential growth and full jitter to prevent server thundering herds:
$$\text{Delay} = \text{random}(0, \min(\text{MAX\_DELAY}, \text{BASE\_DELAY} \times 2^{\text{attempt}}))$$
*(Default base: 1000ms, max: 8000ms).*

### 12.3 Operations That MUST NOT Auto-Retry
- Non-idempotent actions (e.g., logging a play event to an external API).
- Search keystroke queries (typing already generates a new request).
- Standard HTTP 400 Bad Request, 401 Unauthorized, or standard provider/API HTTP 403 Forbidden (generic coroutine-level retries are forbidden; playback stream 403s are handled separately on the ExoPlayer loader thread via dedicated stream-token refresh in `AuthenticatingDataSource`).
- Definitive content absence confirmed across all eligible providers (no further fallback or retry).
- Requests where the parent ViewModel/Screen lifecycle has ended.

---

## 13. Reconnection

When the device transitions from `No Usable Connectivity` $\to$ `Healthy Connectivity`:

### 13.1 Reconnection Coalescing & Deduplication
Reconnection-triggered refreshes must be **deduplicated, coalesced, bounded**, and limited strictly to currently visible, relevant, or stale resources. Network recovery must never cause every cached resource or background screen to refresh simultaneously.

### 13.2 Subsystem Reconnection Behaviors

| Subsystem | Reconnection Behavior | User Action Required? |
| :--- | :--- | :--- |
| **Playback (Stalled)** | Auto-resumes buffering if within the bounded stall-recovery window and audio was actively playing when disconnected. | **No** (Resumes automatically). |
| **Playback (Exhausted Error)** | Player entered error state. Preserves position; displays "Connection restored. Tap to play". | **Yes** (Tap Play). |
| **Active Screen (Catalog/Home)** | Silently revalidates stale data in background; updates UI smoothly if data changed. | **No** (Auto-revalidates). |
| **Failed Screen (Empty Error)** | Does NOT auto-reload immediately to prevent UI jumps. Displays a prominent "Retry" button or pull-to-refresh. | **Yes** (Tap Retry). |
| **Search Screen** | Does NOT auto-fire the previous query. Maintains user input; user taps Search or edits text. | **Yes** (Tap Search). |
| **Lyrics View** | If currently viewing an un-synced/cached track, attempts background fetch for synced lyrics once. | **No** (Auto-fetches). |
| **Artwork Images** | Image loader automatically resumes loading visible uncached image views. | **No** (Handled by Coil). |
| **Library / History** | No action required (all mutations already succeeded in local Room database). | **No**. |

---

## 14. Network Switching

Transitions between **Wi-Fi $\to$ Cellular**, **Cellular $\to$ Wi-Fi**, or **Wi-Fi $\to$ Wi-Fi**:

### 14.1 Playback Stream Resilience
- Active ExoPlayer streaming connections may experience a socket reset or transport error during IP address handover.
- **Transport Error Handling**: ExoPlayer handles transient load errors (such as socket resets or broken pipes) according to its configured load error handling policy (e.g., `DefaultLoadErrorHandlingPolicy` or a tuned policy) with bounded retries before failing the load.
- **Stream Token Expiry (HTTP 403)**: If stream loading fails specifically due to an HTTP 403 (expired or invalidated stream authorization token), `SonaraPlaybackService` intercepts the error on the ExoPlayer loader thread via its custom `DataSource` (`AuthenticatingDataSource`), re-resolves the stream URL via `StreamResolverPort`, updates the `DataSpec`, and attempts transparent recovery to resume playback at the exact millisecond offset without unnecessary audible interruption.

### 14.2 API & Browsing Requests
- In-flight HTTP calls that drop during interface handover fail with `SocketException` and follow the standard bounded auto-retry policy (Section 12).
- Cached data remains fully valid; network switching never clears persistent Room or DataStore storage.

---

## 15. Background / Foreground Transitions

```text
App Foregrounded ────────────────────────► App Backgrounded
       │                                         │
       ▼                                         ▼
• Collect UI Flows via                         • collectAsStateWithLifecycle() stops
  collectAsStateWithLifecycle()                  all UI state collection
• Revalidate stale screen data                 • In-flight UI requests cancelled
• Restore image loader pipeline                • SonaraPlaybackService continues
                                                 uninterrupted (Foreground Service)
```

### 15.1 Backgrounding Rules
- **UI Collection Lifecycle**: `collectAsStateWithLifecycle()` pauses Flow collection when the Activity/Screen is in the background, preventing unnecessary Compose recomposition.
- **Screen vs. ViewModel Scopes**: UI-bound work whose lifetime is explicitly tied to the screen/lifecycle (e.g., within Compose `LaunchedEffect` or lifecycle-bound coroutines) is cancelled when that lifecycle becomes inactive. ViewModel-owned work (`viewModelScope`) must not be assumed to cancel merely because `Activity.onStop()` occurs; rather, non-essential background execution is pruned cooperatively when the navigation scope is popped or cleared.
- **Playback Exception**: `SonaraPlaybackService` is an Android Foreground Service (`foregroundServiceType="mediaPlayback"`); it maintains its own independent lifecycle and continues streaming audio while the application UI is backgrounded.

### 15.2 Foregrounding Rules
- When the user returns to the app, `collectAsStateWithLifecycle()` automatically resumes collecting UI state.
- If the app was backgrounded beyond the configured stale cache threshold (recommended initial default: 15 minutes, implementation-tunable), visible catalog screens trigger a silent background revalidation without wiping current UI content.

---

## 16. Playback Network Behavior

This section operationalizes the frozen playback architecture from Phase 4B-1.

```text
Audio Playing ────────► Network Lost ────────► Buffered Data Plays ────────► Buffer Empty
     │                                                │                            │
     ▼                                                ▼                            ▼
ExoPlayer streams                            Playback continues           Playback Stalls
audio                                        while buffer lasts           ExoPlayer enters BUFFERING
                                                                                   │
                                                                                   ▼
                                                                          Network Returns?
                                                                          ├── YES ──► Auto-resumes
                                                                          └── NO  ──► Enters PAUSED
```

### 16.1 Scenario A: Network Lost While Audio is Playing
1. ExoPlayer continues rendering audio from its internal memory/disk buffer as long as sufficient data remains buffered.
2. The duration of uninterrupted playback after network loss is runtime-dependent (governed by media bitrate, LoadControl settings, and current buffer fill) and must not be treated as a guaranteed number of seconds.
3. If network returns before the buffer runs out, ExoPlayer resumes filling the buffer seamlessly.
4. If the buffer is completely exhausted:
   - ExoPlayer enters `STATE_BUFFERING`.
   - UI shows a non-blocking buffering spinner over the Play/Pause button.
   - The playback notification updates to indicate buffering.
   - A bounded stall-recovery timer (recommended default: 60 seconds, implementation-tunable) starts in `SonaraPlaybackService`.

### 16.2 Scenario B: Buffer Exhausted & Stall Timeout
- If network does not return within the bounded stall-recovery window (e.g. 60s default):
  - Playback transitions to `STATE_PAUSED` at the exact current position.
  - UI displays a snackbar: "Playback paused due to network connection."
  - Audio focus is retained temporarily without remaining indefinitely in a buffering loop.

### 16.3 Scenario C: Stream URL Expiry (HTTP 403 / 410)
- As defined in Phase 4B-1, streaming URLs contain short-lived tokens.
- When ExoPlayer encounters an HTTP 403 during playback:
  - Custom `AuthenticatingDataSource` / `RefreshingDataSource` catches the 403 on ExoPlayer's background loader thread.
  - It blocks the loader thread and calls `StreamResolverPort.resolveStreamUrl(trackId)`.
  - It updates the `DataSpec` with the fresh URL and retries the HTTP read.
  - The system attempts transparent recovery to minimize audible interruption while completely avoiding UI state tearing.

### 16.4 Scenario D: Queued Track Prefetch Fails
- When the prefetch trigger fires for the next track in queue and network is unavailable:
  - The prefetch job fails silently in the background.
  - The current track finishes playing completely from available buffered data.
  - When the timeline transitions to the next track: the player attempts stream resolution. If network is still offline, the player transitions to paused state and alerts the user.

---

## 17. Artwork Network Behavior

Artwork loading is handled by the dedicated Compose image loading layer (Coil).

### 17.1 Failure & Degradation
- **Network Lost + Cached Bitmap Available**: Coil loads the image from disk cache immediately. Zero visual degradation.
- **Network Lost + No Cached Bitmap**: Coil fails to load. The UI renders a deterministic, stylish fallback (e.g. Petrol/Bone colored gradient with track initial or music note vector).
- **No Layout Shift**: All artwork containers must define strict, fixed aspect ratios and dimensions (e.g. 1:1 square) so image failures never cause UI layout jumping.
- **Preserve Existing Art**: When navigating between tracks, the previous artwork is cross-faded with the new artwork or fallback placeholder smoothly.

---

## 18. Lyrics Network Behavior

### 18.1 Fetching & Degradation
- When a track begins playing, `LyricsRepository` attempts to load lyrics from local cache, then remote providers.
- **Network Lost + Cached Lyrics**: Synced or unsynced lyrics are rendered from local cache immediately. Real-time line highlighting continues perfectly using local playback clock.
- **Network Lost + No Cached Lyrics**: UI displays a clean "Lyrics unavailable offline" placeholder.
- **Malformed / Corrupted Lyrics Payload**: Falls back gracefully to unsynced plain text or the unavailable placeholder. Never crashes the UI.

---

## 19. Library / History / Settings

As established in Phase 4B-3, the user's personal data is local-first.

| Domain | Network Dependency | Offline Behavior |
| :--- | :--- | :--- |
| **Liked Songs** | None (Room DB) | 100% functional offline. Toggling like/unlike writes to SQLite instantly. |
| **Listening History**| None (Room DB) | 100% functional offline. Play events are recorded locally on every track finish. |
| **Theme & Settings** | None (DataStore) | 100% functional offline. Preferences update synchronously in memory and asynchronously to disk. |

---

## 20. Offline / Degraded Capability Matrix

| Feature | Offline Capability Level | Exact System Behavior |
| :--- | :--- | :--- |
| **Audio Playback** | 🟡 DEGRADED | Plays pre-buffered audio only. Cannot resolve new remote streams without network. |
| **Liked Songs Screen** | 🟢 FULLY FUNCTIONAL | Displays all liked tracks from Room. Allows sorting, filtering, and local queueing. |
| **Listening History** | 🟢 FULLY FUNCTIONAL | Displays complete history from Room. Logs new local playback events. |
| **Search Screen** | 🔴 REQUIRES NETWORK | Displays offline message. Cached recent search terms remain visible. |
| **Home Screen** | 🟡 DEGRADED | Displays cached recommendations and quick picks. Pull-to-refresh disabled with offline banner. |
| **Artist / Album Details**| 🟡 DEGRADED | Displays cached metadata and tracklists if previously viewed. Shows offline error if uncached. |
| **Lyrics View** | 🟡 DEGRADED | Displays synced/unsynced lyrics if cached; shows placeholder if uncached. |
| **Settings & Theme** | 🟢 FULLY FUNCTIONAL | Full access to all preferences and Material 3 theme toggles. |

---

## 21. Error Presentation

To preserve Phase 4B-2's strict UI/Domain separation, network errors are categorized into presentation-safe abstractions before reaching Compose.

```text
Raw Network Error (SocketTimeoutException)
                    │
                    ▼ (Mapped by Network Adapter)
Domain Exception (NetworkTimeoutException)
                    │
                    ▼ (Mapped by ViewModel)
UI Text Abstraction (UiText.StringResource / Presentation Error)
                    │
                    ▼ (Rendered by Compose)
Localized UI: "Connection timed out. Please check your internet."
```

### 21.1 Standard Error Categories

> **Note on HTTP 404 & Content Availability**: A provider-specific 404 triggers transparent fallback across configured catalog providers (Section 11.1) and produces no user error if fallback succeeds. `ContentNotFoundException` is emitted only upon definitive absence after the fallback chain is exhausted, or for structurally invalid/non-existent resources.

| Technical Cause | Domain Exception | UI Presentation Message | UI Presentation Type |
| :--- | :--- | :--- | :--- |
| No internet / DNS fail | `NetworkUnavailableException` | "You're offline. Check your connection." | Bottom Snackbar or Screen Banner |
| Socket read timeout | `NetworkTimeoutException` | "Connection is taking too long." | Non-intrusive Snackbar + Retry action |
| HTTP 500/502/503/504 (All Providers Exhausted) | `ProviderUnavailableException` | "Music service is temporarily unavailable." | Snackbar with retry or empty-state |
| HTTP 429 | `RateLimitedException` | "Too many requests. Please wait a moment." | Transient Toast / Snackbar |
| Definitive Absence (All Providers Exhausted) | `ContentNotFoundException` | "This track or album is no longer available."| Persistent empty-state illustration |

---

## 22. User Experience Rules

Sonara strictly adheres to the following UX resilience guardrails:

1. **NEVER blank an existing screen** because a background refresh or pagination failed.
2. **NEVER display raw technical exceptions** (e.g. `java.net.ConnectException: Connection refused`) in the user interface.
3. **NEVER interrupt active audio playback** unless the buffer is 100% empty and network cannot be reached.
4. **NEVER freeze or block the UI thread** during network I/O or token resolution.
5. **NEVER show blocking full-screen error dialogs** for transient network glitches.
6. **NEVER lose user input** (e.g. entered search query) when a network error occurs.
7. **NEVER auto-fire network request storms** when recovering from offline mode.
8. **NEVER discard cached content** when an API server returns a 5xx error.
9. **NEVER show an "Offline" banner** if only a single non-essential image or lyrics fetch failed while all other network calls are succeeding.
10. **NEVER trap the user on an error screen** without a clear "Retry" button or navigation escape path.

---

## 23. Observability & Diagnostics

During development and QA, the networking stack must emit structured diagnostic log events to facilitate failure isolation:

```text
[NET-REQ]  ID=req-102 | GET /v1/search?q=arijit | Provider=YouTubeMusic
[NET-TIME] ID=req-102 | Duration=342ms | Status=200 OK | Payload=14.2KB
[NET-CANC] ID=req-101 | Reason=UserQuerySuperseded ("ari" -> "arij")
[NET-FALL] ID=req-103 | Provider=YouTubeMusic FAILED (503) -> Falling back to JioSaavn
[NET-RETR] ID=req-104 | Attempt=1/2 | Delay=1420ms | Cause=SocketTimeoutException
[STREAM-R] TrackId=trk-882 | 403 Detected -> StreamResolverPort Refresh SUCCESS (Duration=180ms)
[NET-CONN] NetworkStatusChanged: CONNECTED (Type=WIFI, Validated=TRUE)
```

*(Diagnostic logging is compiled out or strictly stripped in production release builds).*

---

## 24. Comprehensive Behavior Matrix

| # | Situation | Current State | Trigger | System Reaction | Recovery Path | UI Representation |
|---|:---|:---|:---|:---|:---|:---|
| **1** | Healthy Network | Idle / Browsing | User taps Album | Emits network request; parses JSON; updates cache. | Immediate | Shimmer placeholder $\to$ smooth content pop. |
| **2** | Degraded Network | Browsing | User taps Artist | Request takes >3s; maintains active loading indicator. | Bounded wait $\to$ success | Subtle linear loading indicator at top of screen. |
| **3** | Request Timeout | Browsing | Timeout reached | Cancels socket; maps to `NetworkTimeoutException`. | 1 auto-retry with backoff | Snackbar: "Connection slow. [Retry]" |
| **4** | Wi-Fi Disconnects | Browsing | User scrolls Home | Request fails immediately with `NetworkUnavailableException`. | Reconnect trigger | Shows cached data + top banner: "Offline". |
| **5** | Network Reconnects | Stalled Screen | Link re-validated | Fires connectivity event; revalidates active screen silently. | Automatic | "Offline" banner disappears smoothly. |
| **6** | Provider 503/404 | Searching | Query executed | Primary provider returns 503 or provider-specific 404; Repo catches error. | Transparent fallback | Swaps to Secondary Provider; user sees results. |
| **7** | All Providers Fail | Searching | Query executed | All providers in acyclic chain return errors or timeout. | Terminal failure | Empty state: "Service unavailable. [Try Again]" |
| **8** | Provider 429 | Browsing | High activity | Provider throttles client; backoff timer engaged. | Bounded backoff | Snackbar: "Rate limited. Retrying shortly..." |
| **9** | Corrupted JSON | Catalog fetch | Malformed body | Parser throws `JsonParseException`; rejects payload. | Discard payload | Falls back to cached data or clean error view. |
| **10**| Cache Hit (Fresh) | Opening Home | App launch | Reads Room/Disk cache; returns instantly (<10ms). | Local immediate | Zero shimmer; instant UI render. |
| **11**| Cache Hit (Stale) | Opening Home | App launch | Renders stale cache immediately; triggers background fetch. | Auto-revalidate | Instant render $\to$ subtle update when fresh data arrives. |
| **12**| Refresh Fails | Pull-to-refresh | User pulls Home | Network fails during manual refresh. | Retain cache | Pull spinner dismisses $\to$ Snackbar: "Couldn't refresh". |
| **13**| Fast Typing | Search Screen | Typing "arijit" | Cancels requests for "a", "ar", "ari", "arij". | Structured cancel | Zero flicker; only "arijit" results render. |
| **14**| Stale Out-of-Order | Search Screen | Slow "a" returns | Generation ID check detects response is obsolete. | Discard response | Older response ignored; UI stays on latest query. |
| **15**| Audio Playing | Playing Track | Wi-Fi drops | ExoPlayer continues rendering from available pre-buffered audio. | Buffer playback | Music continues while buffer lasts; no UI interruption. |
| **16**| Buffer Runs Out | Playing Track | Buffer empty | ExoPlayer buffer exhausts; enters `STATE_BUFFERING`. | Bounded stall wait | Play button turns into buffering spinner. |
| **17**| Reconnect Audio | Stalled Audio | Wi-Fi returns | Service detects validated link within stall window $\to$ resumes buffer fill. | Auto-resume | Spinner disappears $\to$ music resumes playing. |
| **18**| Stream Token 403 | Playing Track | URL expires | `RefreshingDataSource` catches 403 on loader thread. | Transparent refresh | Blocks loader $\to$ fetches new URL $\to$ resumes stream. |
| **19**| Artwork Fails | Track Info | Image 404/Offline | Coil fails to resolve remote URL. | Fallback vector | Elegant gradient placeholder with music note icon. |
| **20**| App Backgrounded | Active Request | User switches app | Non-service coroutines cancelled; playback service stays. | Lifecycle cancel | UI paused; audio stream continues uninterrupted. |

---

## 25. State Transition Examples

### Example A: User Searches While Network is Slow
1. **User Action**: Types "Coldplay" into the search bar.
2. **Internal Behavior**: 300ms debounce elapses. `SearchViewModel` launches coroutine calling `SearchRepository.search("Coldplay")`. Request takes 4.5 seconds due to high network latency.
3. **UI State**: Search bar displays a subtle circular progress indicator. If previous search results were on screen, they remain visible at 70% opacity.
4. **Outcome**: After 4.5 seconds, payload arrives. Previous results are replaced atomically with Coldplay albums and tracks. Opacity returns to 100%.

### Example B: User Searches, Then Immediately Changes Query
1. **User Action**: Types "Taylor", pauses for 350ms, then immediately types "Swift".
2. **Internal Behavior**: Request for "Taylor" fires at 300ms. At 350ms, the user types "Swift". `SearchViewModel` cancels the coroutine for "Taylor" (`Job.cancel()`), triggering `OkHttp.Call.cancel()`.
3. **Outcome**: Network socket for "Taylor" is aborted. Debounce starts for "Taylor Swift". Only "Taylor Swift" results are ever parsed or rendered.

### Example C: User Browsing Artist Page and Wi-Fi Disappears
1. **User Action**: User is viewing the Queen artist page; internet connection is lost. User taps "Albums" tab.
2. **Internal Behavior**: `CatalogRepository` attempts network call, which fails immediately with `UnknownHostException`.
3. **UI State**: The UI does not crash or blank the screen. It checks local Room/Memory cache. Since the album list was cached, it renders the list immediately and displays a non-intrusive bottom snackbar: "You're offline. Showing saved data."

### Example D: User Has No Cached Data and Loses Connectivity
1. **User Action**: First-time opening of an unvisited Artist page while in Airplane Mode.
2. **Internal Behavior**: Network call fails fast. Cache check yields a MISS.
3. **UI State**: Displays a dedicated offline illustration, "No internet connection", and a prominent "Retry" button.

### Example E: User Listening to Music and Wi-Fi Disappears
1. **User Action**: Walking out of Wi-Fi range while listening to a 4-minute track.
2. **Internal Behavior**: Wi-Fi interface drops. ExoPlayer continues rendering audio already buffered in memory.
3. **Outcome**: Audio continues playing uninterrupted while the buffer lasts. The MiniPlayer and Notification remain in `PLAYING` state.

### Example F: Playback Buffer Runs Out
1. **Context**: Continued from Example E; cellular data is disabled, and the buffered audio is fully consumed.
2. **Internal Behavior**: Buffer exhausts. ExoPlayer transitions to `STATE_BUFFERING`. A bounded stall-recovery timer (recommended default: 60s, implementation-tunable) starts in `SonaraPlaybackService`.
3. **UI State**: Play/Pause button transforms into a subtle buffering spinner. Notification indicates buffering.
4. **Outcome**: If network remains offline after the stall timeout expires, player enters `STATE_PAUSED` and releases audio focus gracefully.

### Example G: Network Returns While Playback Stalled
1. **Context**: Continued from Example F; user enters a cellular coverage area before the stall-recovery timeout expires.
2. **Internal Behavior**: `ConnectivityManager` reports `NET_CAPABILITY_VALIDATED`. `SonaraPlaybackService` detects active stalled state, re-initiates HTTP audio buffer read.
3. **Outcome**: Buffer fills with sufficient audio data. Audio resumes playing automatically. Buffering spinner reverts to Pause icon.

### Example H: Stream URL Returns HTTP 403 Mid-Playback
1. **Context**: Long-running playback session; provider audio token expires during playback.
2. **Internal Behavior**: ExoPlayer loader thread receives HTTP 403 Forbidden. `AuthenticatingDataSource` intercepts error, calls `StreamResolverPort.resolveStreamUrl()`, obtains new authenticated URL, updates `DataSpec`, and retries.
3. **Outcome**: Transparent token refresh completes on loader thread; audio stream resumes without unnecessary user-visible disruption or UI state tearing.

### Example I: Primary Provider Becomes Unavailable
1. **User Action**: User taps a song to play. Primary provider (YouTube Music) returns HTTP 503.
2. **Internal Behavior**: `StreamResolver` catches 503, records provider failure metric, and immediately dispatches resolution to Secondary Provider (JioSaavn) via `StreamResolverPort`.
3. **Outcome**: Song stream resolves successfully from secondary provider. Audio starts after a brief provider fallback delay. User notices no error.

### Example J: Device Switches Wi-Fi → Mobile Data
1. **Context**: User leaves home while streaming.
2. **Internal Behavior**: Wi-Fi drops; cellular connects. Active TCP sockets receive a reset (`ECONNRESET`).
3. **Outcome**: ExoPlayer's `LoadErrorHandlingPolicy` catches socket error and retries over the new cellular interface within 500ms. Audio buffer absorbs the handover gap; user experiences continuous playback when buffer coverage permits.

### Example K: User Backgrounds App During a Failed Request
1. **Context**: User triggers Album search on a failing network, then immediately presses Home button.
2. **Internal Behavior**: `collectAsStateWithLifecycle()` disconnects UI flow observation. The ViewModel's request is not automatically destroyed merely by `onStop()`, but UI state emission is suspended, and screen-scoped coroutines are cancelled.
3. **Outcome**: Background resource waste is prevented. If the screen is popped, the ViewModel clears and aborts the in-flight socket.

### Example L: User Returns to App After Network Recovery
1. **Context**: User opens app after returning from offline mode.
2. **Internal Behavior**: `Activity.onStart()` reconnects. Screen detects network is now healthy and local data is stale (exceeding the stale cache threshold, e.g. >15 min default).
3. **Outcome**: Silent background revalidation runs. Fresh catalog items update on screen with a smooth Compose transition without shifting scroll position.

---

## 26. Automatic vs User-Initiated Recovery

| Operation | Automatic Recovery? | Rationale | User Action Required? |
| :--- | :---: | :--- | :--- |
| **Active Playback Buffering** | **YES** | User is actively listening; audio should resume as soon as the pipe opens. | No |
| **Stream Token Refresh (403)**| **YES** | Transparent to the user when recovery succeeds; no user action required. | No |
| **Visible Image / Artwork** | **YES** | Passive visual element; loading when ready improves UI quality. | No |
| **Stale Screen Background Sync**| **YES** | Silent data freshness check; does not alter navigation or scroll. | No |
| **Search Query Execution** | **NO** | User context may have changed; auto-firing queries causes unexpected UI shifts. | **Yes** (Tap Search/Retry) |
| **Full Screen Error Reload** | **NO** | Auto-reloading empty error screens can cause jarring layout flashes. | **Yes** (Tap Retry Button) |
| **Mutating Library (Like/Unlike)**| **YES** | Local Room mutation already succeeded; remote sync (if any) retries safely. | No |
| **Next Track After Hard Offline**| **NO** | If entire queue cannot resolve, player stops to avoid draining battery. | **Yes** (Tap Next/Play) |

---

## 27. Idempotency & Request Deduplication

### 27.1 Duplicate In-Flight Protection
If multiple UI components request the same catalog entity simultaneously (e.g. MiniPlayer and ExpandedPlayer both requesting track metadata for `trk-99`):
- `CatalogRepository` tracks active in-flight requests in an internal concurrent map: `Map<String, Deferred<Result<Track>>>`.
- The second caller receives the existing `Deferred` awaiter instead of firing a second HTTP call.

### 27.2 Out-of-Order Execution Guard
- Every asynchronous state-emitting pipeline in ViewModels maintains a local `requestSequenceNumber`.
- Upon coroutine completion, if `responseSequenceNumber < currentSequenceNumber`, the result is discarded.

---

## 28. Performance & Resource Rules

1. **Thundering Herd Prevention**: All retry intervals must incorporate randomized jitter (Section 12.2).
2. **Connection Pool Hygiene**: HTTP clients must enforce connection pooling with idle timeouts (~30s) to avoid leaking open sockets during network flaps.
3. **Bandwidth Preservation**: When network is degraded, prefetching of upcoming tracks or high-res artwork is throttled or deferred until connectivity improves.
4. **Background Resource Hygiene**: UI state collection pauses in the background via `collectAsStateWithLifecycle()`, and screen-scoped jobs are cancelled when their UI lifecycle ends. ViewModels prune non-essential background execution cooperatively.
5. **Reconnection Throttling**: Background revalidations upon network reconnect must be coalesced, deduplicated, and bounded to avoid saturating bandwidth.

---

## 29. Security & Privacy

1. **No Token Logging**: Diagnostic logging and crash reports must strictly sanitize and mask auth headers, stream tokens, and session cookies.
2. **HTTPS Only**: All provider network communication must strictly use TLS 1.2+ / HTTPS. Cleartext HTTP traffic is disabled in the network security config.
3. **Ephemeral Stream URLs**: Stream URLs containing signed authentication parameters must reside in transient memory only and are NEVER written to disk caches or Room databases.

---

## 30. Implementation-Tunable Decisions

The following parameters are explicitly classified as **Implementation-Tunable** (not immutable architecture):

| Parameter | Recommended Initial Default | Tuning Scope |
| :--- | :--- | :--- |
| **Search Debounce Delay** | `300ms` | Configurable in `SearchViewModel` (`200ms`–`500ms`). |
| **Search Request Timeout** | `6000ms` | Configurable in HTTP client interceptor. |
| **Catalog Request Timeout**| `10000ms`| Configurable in HTTP client interceptor. |
| **Max Auto-Retry Attempts**| `2` | Configurable in Repository retry policy. |
| **Retry Base Delay** | `1000ms` | Configurable in backoff calculator. |
| **Playback Stall Timeout** | `60000ms` (60s) | Bounded recovery limit in `SonaraPlaybackService`. |
| **ExoPlayer Min Buffer** | `15000ms` (15s) | Configurable in `DefaultLoadControl`. |
| **ExoPlayer Max Buffer** | `50000ms` (50s) | Configurable in `DefaultLoadControl`. |
| **Stale Cache Expiry Window**| `15 minutes` | Configurable in repository caching policy. |

---

## 31. Behavioral Testing & Chaos Scenarios

These behavioral scenarios define the acceptance criteria for Sonara's network resilience:

- [ ] **Scenario 1 (Flight Mode Cut)**: Disconnect Wi-Fi while streaming. Verify playback continues uninterrupted for the duration of available pre-buffered audio.
- [ ] **Scenario 2 (Stall & Reconnect)**: Exhaust playback buffer offline, then reconnect Wi-Fi. Verify playback automatically resumes within the bounded stall-recovery window.
- [ ] **Scenario 3 (Typing Frenzy)**: Rapidly type a 20-character query in 2 seconds. Verify exactly 1 network request completes and UI renders correct final results.
- [ ] **Scenario 4 (Stale Overwrite Prevention)**: Inject artificial 5-second latency on query "A", then type "B" (100ms latency). Verify "A" never overwrites "B".
- [ ] **Scenario 5 (Primary Outage & Acyclic Fallback)**: Simulate HTTP 503 on primary provider. Verify seamless fallback to secondary provider with bounded acyclic resolution.
- [ ] **Scenario 6 (Pull-to-Refresh Offline)**: Disconnect network and perform pull-to-refresh on Home screen. Verify existing items remain visible and a transient snackbar appears.
- [ ] **Scenario 7 (403 Token Expiry)**: Return HTTP 403 on active audio stream. Verify custom `DataSource` refreshes token via `StreamResolverPort` transparently without throwing unhandled playback errors.
- [ ] **Scenario 8 (Interface Handover)**: Switch from Wi-Fi to Cellular while streaming. Verify playback does not crash and recovers with minimal disruption.
- [ ] **Scenario 9 (Background Cancellation)**: Trigger slow catalog load and pop navigation destination. Verify HTTP socket cancels immediately.
- [ ] **Scenario 10 (Corrupted Cache)**: Inject invalid JSON into local cache. Verify system purges entry and fetches fresh remote data without crashing.

---

## 32. Final Behavioral Contracts

### Network Failure Contract
Network failures must fail fast, throw typed domain exceptions, and never block UI threads or blank populated screens.

### Slow Network Contract
Slow requests must display immediate loading feedback promptly after user action, preserve interactive access to cached data, and respect strict cancellation when superseded.

### Retry Contract
Retries must be bounded (max 2), exponential, jittered, and restricted strictly to idempotent GET calls and transient errors.

### Reconnection Contract
Reconnection must auto-resume passive operations (stalled audio, image loading, stale revalidation) in a deduplicated, coalesced manner, and require user interaction for active operations (search queries, error reloads).

### Cache Degradation Contract
Cached data must be rendered whenever network is unavailable. Stale data must never be discarded until a fresh replacement successfully arrives. Only validated domain payloads may update persistent cache.

### Provider Failure Contract
Upstream provider 5xx, 429 errors, or provider-specific 404s must trigger transparent, bounded, acyclic secondary provider fallback in Repositories before bubbling errors to ViewModels.

### Search Cancellation Contract
Every new keystroke must cancel the preceding search coroutine and abort in-flight HTTP sockets. Out-of-order responses must be rejected.

### Playback Network Contract
Active streaming must leverage ExoPlayer buffers to absorb transient network drops (duration depending on runtime buffer fill), auto-resume if reconnected within the bounded stall-recovery window (recommended default: 60s, tunable), and refresh expired tokens via `StreamResolverPort` transparently to minimize audible interruption.

### Artwork Network Contract
Image failures must degrade gracefully to deterministic vector/gradient placeholders without altering container dimensions or triggering layout shifts.

### Lyrics Network Contract
Lyrics must be served from local cache when offline. Synced line highlighting must run independently of network state via local playback clock.

### Offline Capability Contract
Personal library (Liked songs, History, Settings) must remain 100% operational offline backed by Room and DataStore.

### Background/Foreground Contract
UI state collection pauses when backgrounded, cancelling screen-scoped jobs while allowing ViewModels to prune non-essential work cooperatively. Active playback continues uninterrupted as a Foreground Service.

### User Experience Contract
Sonara must never expose raw technical stack traces, never freeze during network delays, and never surprise the user with unexpected automatic actions.

### Observability Contract
All network failures, retries, fallbacks, token refreshes, and socket cancellations must emit structured diagnostic logs in development builds.

---

## 33. Phase 4C Decision Register

| Decision | Status | Final Behavioral Rule | Rationale | Implementation Notes |
| :--- | :---: | :--- | :--- | :--- |
| **Network Model** | ✅ DECIDED | Fail-fast on requests; `NetworkCallback` for UI/reconnect only. | `NET_CAPABILITY_VALIDATED` does not guarantee server reachability. | Use `ConnectivityManager` reactive Flow. |
| **Search Cancellation** | ✅ DECIDED | Structured coroutine cancellation + generation ID check. | Prevents out-of-order stale search result overwrites. | Debounce 300ms + `switchMap` / `collectLatest`. |
| **Provider Fallback** | ✅ DECIDED | Transparent, bounded, acyclic fallback on 5xx/429/timeouts/provider-404s. | Isolates provider fragility and prevents infinite retry loops. | Orchestrated in `CatalogRepository` / `SearchRepository`. |
| **Retry Policy** | ✅ DECIDED | Max 2 retries, exponential backoff, randomized jitter for GETs. | Prevents server hammering and battery drain. | Handled via OkHttp Interceptor or Coroutine retry. |
| **Stale Screen Policy** | ✅ DECIDED | Stale-while-revalidate; retain cache on network failure. | Eliminates blank screens on failed refreshes. | Single Data Class UI state model. |
| **Playback Reconnect** | ✅ DECIDED | Bounded stall recovery (recommended initial default: 60s, implementation-tunable). | Preserves continuous playback while avoiding endless recovery loops. | Managed by `SonaraPlaybackService`. |
| **Token Refresh (403)** | ✅ DECIDED | Transparent background loader refresh via `StreamResolverPort`. | Prevents unrecoverable playback stops on token expiry. | Custom `AuthenticatingDataSource` in ExoPlayer. |
| **Negative Caching** | ✅ DECIDED | Strictly Forbidden for network errors and 404s; cache requires valid domain models. | Prevents permanent broken media states (Web legacy). | Only cache validated successful domain payloads. |
| **Artwork Fallback** | ✅ DECIDED | Fixed-size gradient/vector placeholder; zero layout shift. | Prevents UI jumping on image failures. | Coil placeholder & error drawables. |
| **Offline Library** | ✅ DECIDED | 100% offline functionality for Liked Songs & History in Room. | Personal user library must never require internet. | SQLite single source of truth. |
| **Tunable Constants** | ⏸ DEFERRED | Timeouts, buffer sizes, retry counts, debounce intervals, staleness window. | Implementation-level optimization parameters. | Tunable in Gradle build config / constants. |

---

## 34. Frozen Architecture Compatibility

- **Phase 4A**: Maintained 100% Android-native Kotlin and Compose principles.
- **Phase 4B-1**: Playback service boundaries, `StreamResolverPort`, and paused session recovery remain completely intact.
- **Phase 4B-2**: UDF, `UiText` error presentation, and isolated high-frequency position observation are strictly respected.
- **Phase 4B-3**: Repositories remain the single source of truth; Room and DataStore persistence boundaries are preserved.

**Result**: Zero architectural contradictions. Phase 4C perfectly bridges architecture and implementation behavior.

---

## 35. Risks & Explicit Future Decisions

| Risk | Impact | Mitigation |
| :--- | :--- | :--- |
| **Aggressive Upstream Rate Limiting** | Medium | Strict fallback to secondary provider and local caching of metadata. |
| **Rapid Network Flapping** | Low | Exponential backoff jitter and 60s playback stall recovery limit socket thrashing. |
| **Cellular Data Usage** | Low | High-frequency prefetching throttled on metered connections (Post-MVP setting). |

---

## 36. Sources & Evidence

- **Android Developers**: [Monitor Connectivity Status and Connection Metering](https://developer.android.com/training/monitoring-device-state/connectivity-status-and-metering)
- **Android Media3**: [ExoPlayer LoadErrorHandlingPolicy & Buffering Strategy](https://developer.android.com/media/media3/exoplayer/network-stacks)
- **Square OkHttp**: [Call Cancellation & Connection Pooling Architecture](https://square.github.io/okhttp/calls/)
- **Kotlin Coroutines**: [Structured Concurrency & Cooperative Cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- **Sonara Web Historical Audit**: Legacy negative caching analysis and playback dropout remediation findings.
