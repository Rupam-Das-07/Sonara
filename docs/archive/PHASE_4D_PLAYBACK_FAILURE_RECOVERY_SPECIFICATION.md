# SONARA ANDROID — PHASE 4D PLAYBACK FAILURE, RECOVERY & TRANSITION SPECIFICATION

## 1. Executive Summary

This document establishes the **Playback Failure, Recovery & Transition Specification** for Sonara Android. While Phase 4B-1 established **where** playback responsibilities live (Media3, `SonaraPlaybackService`, `ExoPlayer`, `MediaController`, `StreamResolverPort`, `ContinuityEngine`) and Phase 4C defined **how** the system behaves under adverse network conditions, Phase 4D defines **what Sonara does when playback encounters failures, state transitions, concurrent user inputs, hardware interruptions, and edge-case lifecycles**.

Audio playback is inherently an asynchronous, distributed state machine spanning the Android framework, background services, hardware audio pipelines, external streaming providers, and the UI layer. Audio does not start instantly when a user taps play; tracks do not disappear instantly when skipped; and stream failures must not cause crashes, queue corruptions, or unexpected audio blaring.

Phase 4D eliminates all behavioral ambiguity for the implementation phase by establishing concrete rules for:
- Deterministic user intent handling under high-frequency inputs (rapid skip, play/pause spam, seek/next races) via direction-neutral transition versioning.
- Resilient recovery from stream expiration, provider outages, and decoding errors with bounded retries per load attempt.
- Clean transition and backstack management during skipped or failed tracks.
- Strict suppression of automatic background recovery when a user has explicitly paused.
- Audio focus, output routing (Bluetooth/Headphones), and system interruptions.
- Proactive session snapshot persistence and predictable cold-start/process-restoration playback invariants.

---

## 2. Frozen Architecture Dependencies

Phase 4D is a situational behavioral specification that builds strictly upon the frozen architectural dependencies:

1. **`PHASE_4A_ARCHITECTURE_DISCOVERY.md`**: Android-first, Kotlin, Jetpack Compose, Material 3 foundation.
2. **`PHASE_4B_1_PLAYBACK_ARCHITECTURE.md`**: `SonaraPlaybackService` (`MediaSessionService`) owns `ExoPlayer` and playback queue; `MediaController` is the client bridge; `StreamResolverPort` owns stream resolution; `ContinuityEngine` handles endless queueing; mid-stream HTTP 403 refresh runs via custom `DataSource`; restored sessions enter `PAUSED`.
3. **`PHASE_4B_2_STATE_UI_ARCHITECTURE.md`**: Layered UDF; ViewModels project state without Android resources; state-driven UI effects; high-frequency playback position isolated from root screen recomposition.
4. **`PHASE_4B_3_DATA_PERSISTENCE_NETWORKING_ARCHITECTURE.md`**: Repositories are single sources of truth; Proto DataStore stores session snapshots; Room stores Liked Songs and History; no manual negative caching.
5. **`PHASE_4C_NETWORKING_RESILIENCE_SPECIFICATION.md`**: Fail-fast networking; runtime-dependent buffer duration; bounded stall recovery; acyclic provider fallback; coalesced reconnection.

**Invariant**: Phase 4D does NOT modify, reopen, or duplicate any architectural boundaries from Phases 4A–4C.

---

## 3. Tool / MCP / Skill Usage

| Tool / Resource | Classification | Usage in Phase 4D |
| :--- | :--- | :--- |
| **Android & Media3 Docs (`search_web`)** | ✅ REQUIRED | Verified official Android guidance on `AudioManager` audio focus handling, `MediaSession` callback synchronization, `Player.Listener` state transitions, and `ACTION_AUDIO_BECOMING_NOISY`. |
| **`ponytail` (Mental Model)** | ✅ REQUIRED | Maintained architectural simplicity: avoided redundant sub-state machines; prioritized deterministic intent versioning over complex retry heuristics. |
| **Context7 / Serena** | ⏸ RESERVED | Reserved for code generation and refactoring in the implementation phase. |
| **Reticle / Playwright** | ⏸ RESERVED | Reserved for end-to-end integration and chaos testing during verification. |

---

## 4. Playback Behavior Model

Playback is modeled conceptually across five asynchronous stages:

```text
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  USER INTENT │ ──► │  TRANSITION  │ ──► │  RESOLUTION  │ ──► │ PLAYER STATE │ ──► │ AUDIO OUTPUT │
│ (Play/Skip)  │     │ (Versioned)  │     │(StreamResolver│    │ (ExoPlayer)  │     │  (Hardware)  │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
       │                    │                    │                    │                    │
       ▼                    ▼                    ▼                    ▼                    ▼
   Superseded?          Cancelled?           Fallback /           Buffering /         Audio Focus /
 (Newer Intent)      (Stale Version)         Exhausted?             Stall?            Noisy Route?
```

### 4.1 Behavioral States vs Player States
To prevent optimistic UI bugs, Sonara explicitly differentiates:
- **User Intent**: What the user requested (e.g., `PLAY`, `PAUSE`, `SKIP_TO_NEXT`, `SKIP_TO_PREVIOUS`, `SEEK`).
- **Transition State**: The active lifecycle of fulfilling an intent (e.g., `RESOLVING_STREAM`, `PREFETCHING_METADATA`, `ACQUIRING_FOCUS`).
- **Player State (ExoPlayer)**: The authoritative engine state (`STATE_IDLE`, `STATE_BUFFERING`, `STATE_READY`, `STATE_ENDED`).
- **Playback State**: The combined playback truth (`PLAYING`, `PAUSED`, `BUFFERING`, `ERROR`).

---

## 5. Play Behavior

When the user requests `Play`:

```text
User Taps Play
      │
      ├────────────────────────────────────────┬────────────────────────────────────────┐
      ▼                                        ▼                                        ▼
[Current Track Valid]                  [No Current Track]                     [Restored Session]
      │                                        │                                        │
Stream URL in Memory?                 Queue has Items?                       Stream URL Fresh?
  ├── YES ──► ExoPlayer.play()          ├── YES ──► Resolve Head Item          ├── YES ──► ExoPlayer.play()
  └── NO  ──► Resolve Stream URL        └── NO  ──► No-op (Prompt Library)     └── NO  ──► Resolve Stream URL
```

### 5.1 Play Scenarios
1. **Play with Valid Current Track & Valid Stream**:
   - `MediaController.play()` is invoked.
   - ExoPlayer transitions promptly from `PAUSED` to `PLAYING` (or `BUFFERING` if buffer needs replenishing).
   - Audio begins rendering. UI reflects `PLAYING` once ExoPlayer confirms `isPlaying == true`.
2. **Play with Valid Current Track & Expired/Null Stream**:
   - UI reflects transitional loading state on the play button (subtle spinner).
   - `SonaraPlaybackService` calls `StreamResolverPort.resolveStreamUrl(trackId)`.
   - On success: Stream is loaded into ExoPlayer; playback begins.
   - On failure: Transition fails; UI displays non-blocking error; previous paused track remains selected in queue.
3. **Play with Empty Queue**:
   - System performs a no-op without crashing or entering error state. UI displays a helpful prompt ("Select a song to play").
4. **Play After Process Restoration**:
   - As mandated by Phase 4B-1 and Phase 4B-3, restored sessions initialize strictly in `STATE_PAUSED`.
   - Explicit user tap on Play initiates fresh stream resolution via `StreamResolverPort` at the restored track and position offset.
5. **Play While Another Transition is Active**:
   - The newer Play intent is attached to the in-flight transition generation. Once resolved, playback starts automatically.
6. **No Optimistic Play State**:
   - The UI MUST NOT render an active playing equalizer or advance the progress bar until ExoPlayer confirms `playWhenReady == true` and `playbackState == STATE_READY`.

---

## 6. Pause Behavior

When the user requests `Pause`:

```text
User Taps Pause
      │
      ├────────────────────────────────────────┬────────────────────────────────────────┐
      ▼                                        ▼                                        ▼
[While PLAYING]                        [While BUFFERING]                      [While RESOLVING]
      │                                        │                                        │
ExoPlayer.pause()                      ExoPlayer.pause()                      Transition Version Bumped
Audio stops promptly                   Stall policy timer cancelled           In-flight resolution aborted
Position preserved                     Auto-resume cancelled                  Player remains PAUSED
```

### 6.1 Pause Scenarios & Guarantees
1. **Pause During `PLAYING`**:
   - ExoPlayer halts audio output promptly.
   - Authoritative playback position is preserved.
   - Foreground notification remains visible with Play action.
2. **Pause During `BUFFERING` (Network Stall)**:
   - ExoPlayer transitions to `PAUSED`.
   - Any active bounded stall-recovery policy timer (Section 12) is **immediately cancelled**.
   - **Crucial Rule**: Network recovery while paused MUST NOT auto-resume audio. User intent (`PAUSED`) overrides network recovery.
3. **Pause During `RESOLVING`**:
   - Current transition version is incremented, effectively invalidating the in-flight stream resolution.
   - When the background network request completes later, its result is discarded (Section 29).
   - UI immediately shows `PAUSED`.
4. **Rapid Play $\to$ Pause**:
   - If user taps Play then immediately taps Pause during resolution:
   - The Pause intent is authoritative. Even if the stream resolves, ExoPlayer is instructed not to play (`playWhenReady = false`).

---

## 7. Next Behavior

When the user requests `Next`:

```text
User Taps Next
      │
      ▼
Increment Transition Version (V_curr -> V_next)
      │
      ├────────────────────────────────────────────────────────────────────────┐
      ▼                                                                        ▼
[Next Track Exists in Queue]                                        [Queue at Final Item]
      │                                                                        │
Move Current Track to BackStack                                      Trigger ContinuityEngine
Abort any in-flight resolution for old track                         Prefetch next candidate
Resolve Stream for New Track (V_next)                                If unavailable -> Stop gracefully
```

### 7.1 Next Scenarios
1. **Normal Next**:
   - Authoritative queue index advances to `currentIndex + 1`.
   - Previous track is pushed onto the playback `BackStack` (Section 20).
   - ExoPlayer loads new track; stream resolution begins under the new transition version.
2. **Next While Previous Track is Resolving / Buffering**:
   - The pending transition for the previous track is invalidated immediately via transition generation increment.
   - Underlying in-flight stream network requests for the old track are cancelled.
   - Queue index advances cleanly.
3. **Rapid Next Spam (e.g. multiple rapid skip taps)**:
   - Every tap increments the direction-neutral transition version `transitionGenerationId`.
   - Queue index advances by the corresponding number of items synchronously.
   - Only the final targeted track initiates stream resolution and player loading. Intermediate tracks are skipped without wasting network bandwidth.
   - Intermediate tracks are appended to the `BackStack` in chronological skip order.
4. **Next at End of Queue**:
   - If `RepeatMode == ALL`: Loops to queue index 0.
   - If `RepeatMode == OFF`:
     - Checks `ContinuityEngine` for dynamically generated tracks (Section 16).
     - If continuity returns tracks: Appends to queue and plays.
     - If continuity is exhausted/disabled: Playback ends gracefully (`STATE_ENDED`), player pauses at end of track.

---

## 8. Previous Behavior

When the user requests `Previous`:

```text
User Taps Previous
      │
      ▼
Increment Transition Version (V_curr -> V_prev)
Evaluate Authoritative Current Playback Position & BackStack
      │
      ├────────────────────────────────────────┬────────────────────────────────────────┐
      ▼                                        ▼                                        ▼
[Position > Threshold (~3.0s)]           [Position <= Threshold & BackStack Pop]  [Position <= Threshold & BackStack Empty]
      │                                        │                                        │
Seek to 0:00                             Pop previous track from BackStack        Seek to 0:00
Resume playback                          Advance queue index backwards            Restart current track
```

### 8.1 Previous Scenarios & Rules
1. **Position Threshold Rule (Recommended Initial Default: 3000ms, Implementation-Tunable)**:
   - If authoritative current playback position is **greater than the threshold (e.g. >3.0s)**: Tapping Previous rewinds the current track to `0:00` and continues playback. It does NOT skip to the previous song.
   - If authoritative current playback position is **less than or equal to the threshold (e.g. $\le$3.0s)**: Tapping Previous pops the most recently played track from `BackStack` and transitions to it.
2. **Previous with Empty BackStack**:
   - If `BackStack` is empty and position $\le$ threshold: Rewinds current track to `0:00`.
3. **Previous During Active In-Flight Transition**:
   - Previous is an authoritative transition that increments `transitionGenerationId`.
   - The pending forward transition is invalidated. Any late result from the previous forward resolution is discarded upon arrival.
   - The backward transition initiates under the new `transitionGenerationId`.
4. **Rapid Previous Spam**:
   - Uses direction-neutral `transitionGenerationId` versioning. Pops multiple items from `BackStack` synchronously; resolves and plays only the final targeted track.

---

## 9. Rapid Control Input & Concurrency

To ensure the system never enters an inconsistent, deadlocked, or corrupted state during chaotic user inputs:

### 9.1 The Final Intent Wins Principle
> **Invariant**: The user's most recent valid intent is authoritative. Intermediate asynchronous operations MUST be superseded and discarded before committing to the player.

### 9.2 Transition Generation Protocol (Direction-Neutral)
Every state transition request (Play, Pause, Next, Previous, Seek) increments a monotonic `transitionGenerationId`:

```text
UI Action (Next)     ────► Service assigns transitionId = 101
UI Action (Previous) ────► Service assigns transitionId = 102 (101 is now OBSOLETE)
Network resolves 101 ────► Service compares (101 < 102) ──► DISCARDED silently
Network resolves 102 ────► Service compares (102 == 102) ─► COMMITTED to ExoPlayer
```

### 9.3 Interaction Combinations

| Sequence | System Action | Final Guaranteed State |
| :--- | :--- | :--- |
| **Play $\to$ Pause (Rapid)** | Play initiates resolution; Pause increments version and sets `playWhenReady = false`. | `STATE_PAUSED` at current track. |
| **Next $\to$ Previous (Rapid)** | Next advances index; Previous increments version, evaluates the authoritative playback position at the moment the intent is processed, and pops back to original track if within threshold. | Original track loads/plays under new version. |
| **Next $\to$ Next $\to$ Next** | Skips 3 queue positions; cancels streams 1 & 2; resolves stream 3. | Track 3 plays; Tracks 1 & 2 in BackStack. |
| **Play $\to$ Next $\to$ Pause** | Play starts resolving; Next skips; Pause ensures target track resolves into `PAUSED` state. | Target track resolved, placed in `STATE_PAUSED`. |
| **Seek $\to$ Next (Rapid)** | Seek command is superseded; Next transition version cancels seek; loads next track from `0:00`. | Next track plays from `0:00`. |

---

## 10. Stream Resolution Failure

When `StreamResolverPort` fails to resolve a playable audio URL:

```text
Stream Resolution Fails (Provider A)
           │
           ▼
Alternate Provider Available? (Acyclic Chain)
     ├── YES ──► Attempt Provider B (Section 11.2, Phase 4C)
     └── NO  ──► All Providers Exhausted
                       │
                       ▼
            Is Current Track or Future Next Track?
                 ├── CURRENT TRACK ──► Show Error Banner + Pause / Wait for User
                 └── NEXT (AUTO)   ──► Bounded Skip to Next Candidate (Max 3 skips)
```

### 10.1 Failure Scenarios
1. **Current Track Resolution Fails (Manual Play)**:
   - Primary provider fails (e.g. 503 or 404).
   - System executes acyclic fallback to secondary provider via `StreamResolverPort`.
   - If secondary provider succeeds: Audio plays smoothly after brief fallback delay.
   - If all providers fail:
     - Player transitions to `STATE_IDLE` / `STATE_PAUSED`.
     - Queue index remains on the selected track (track is NOT removed from queue).
     - UI displays non-intrusive Snackbar: *"Unable to stream track. Service unavailable. [Retry]"*.
2. **Next Track Resolution Fails During Automatic Playback Transition**:
   - System tries fallback providers.
   - If all fail: System attempts automatic failure skip to the *subsequent* track in queue (Section 15).
   - Bounded skip limit applies: **Maximum 3 consecutive automatic skips** (`MAX_CONSECUTIVE_AUTO_SKIPS = 3`, implementation-tunable). If 3 consecutive tracks fail, playback halts completely to prevent infinite battery drain and error thrashing.

---

## 11. Stream URL Expiration Mid-Playback

When a signed stream token expires during active listening (HTTP 403 Forbidden / 410 Gone):

```text
ExoPlayer Loader Thread receives HTTP 403
                  │
                  ▼
RefreshingDataSource intercepts error
                  │
                  ▼
Blocks loader thread & calls StreamResolverPort.resolveStreamUrl(trackId)
                  │
         ┌────────┴────────┐
         ▼                 ▼
   [REFRESH OK]      [REFRESH FAILED]
         │                 │
Update DataSpec &    Fallback Provider / Fail Load
Retry read at        ExoPlayer surfaces error
target byte-offset   Player transitions to PAUSED
```

### 11.1 Expiration Rules & Scope Distinction
To ensure architectural precision, Sonara explicitly differentiates:
- **Application Session**: The overall process lifecycle.
- **Track Playback Session**: The duration of a single track's active playback.
- **Stream Load Attempt**: An individual network read/open operation for a media chunk or stream.

1. **Transparent Background Refresh**:
   - Handled inside `RefreshingDataSource` on ExoPlayer's loader thread (established in Phase 4B-1 and Phase 4C).
   - The UI layer and ViewModel are NOT notified of temporary 403 glitches unless the refresh attempt fails completely.
2. **Position Preservation**:
   - The refreshed `DataSpec` resumes streaming at the exact byte-offset where the 403 occurred.
   - Playback continues with minimal interruption.
3. **Bounded Refresh Limit Per Stream Load Attempt**:
   - For each individual stream-load attempt encountering an eligible authorization failure (HTTP 403/410), **at most one transparent token-refresh retry** is permitted.
   - If that refreshed attempt also fails with an authorization error, that particular load attempt is treated as failed.
   - This prevents infinite refresh loops while ensuring future independent stream-load attempts (e.g. playing the track later or skipping back) can perform their own bounded refresh retry.

---

## 12. Buffering & Stall Recovery Behavior

Buffering occurs when ExoPlayer's internal read buffer empties faster than network throughput.

```text
Audio Playing ────► Buffer Empty ────► ExoPlayer STATE_BUFFERING ────► Higher-Level Stall Recovery Policy Starts
                                                                                │
                             ┌──────────────────────────────────────────────────┴────────────────┐
                             ▼                                                                   ▼
                 Network Returns within Timeout                                      Policy Timeout Expires
                             │                                                                   │
                 Buffer reaches min threshold                                        Player enters STATE_PAUSED
                 ExoPlayer resumes audio                                             Audio focus held temporarily
                 UI spinner reverts to Pause                                         UI: "Playback paused (network)"
```

### 12.1 Separation of Buffer Machinery vs Recovery Policy
1. **ExoPlayer Native Buffering (`LoadControl`)**:
   - ExoPlayer remains fully responsible for low-level audio buffer management, read-ahead buffering, and standard transport retries via `LoadErrorHandlingPolicy`.
2. **Sonara Higher-Level Bounded Stall Recovery Policy**:
   - When ExoPlayer enters prolonged `STATE_BUFFERING` due to an unreplenished buffer, Sonara maintains a higher-level stall recovery policy (recommended default timeout: **60 seconds**, implementation-tunable).
   - The exact timer mechanism (coroutine delay, Handler, etc.) is an implementation detail and does not duplicate ExoPlayer's internal state machine.
   - If network connectivity is restored within the policy window and buffer refills: ExoPlayer resumes audio playback automatically; UI spinner reverts to Pause.
   - If the policy timeout expires before buffer recovery: `SonaraPlaybackService` transitions player to `STATE_PAUSED` at the current offset and releases active audio focus.
3. **Explicit User Pause During Buffering**:
   - Explicit user pause cancels the stall recovery policy timer immediately. Audio will not auto-play even if the buffer refills later.

---

## 13. Playback Error Classification

| Error Category | Technical Manifestation | Automated Recovery? | Player Action | UI Feedback |
| :--- | :--- | :---: | :--- | :--- |
| **Transient Transport Error** | `SocketTimeoutException`, `ECONNRESET` | **YES** | ExoPlayer load policy retries over active link. | Buffering spinner |
| **Stream Token Expiry** | HTTP 403 / 410 on audio read | **YES** | `RefreshingDataSource` refreshes URL once per load attempt. | None (Transparent) |
| **Provider Outage** | HTTP 500/502/503/504 | **YES** | Acyclic fallback to alternate provider. | Transient fallback note if slow |
| **Provider-Specific 404** | HTTP 404 from Provider A | **YES** | Fallback to Provider B via `StreamResolverPort`. | None if B succeeds |
| **Definitive Content Absence** | Track unplayable across all providers | **NO** | Pause or skip to next queue item (bounded). | Snackbar: "Track unavailable" |
| **Malformed Media / Codec Fail**| `ParserException`, `DecoderInitializationException` | **NO** | Mark track unplayable; skip or pause. | Snackbar: "Unsupported format" |
| **Audio Focus Interruption** | `AUDIOFOCUS_LOSS` | **NO** | Pause playback; abandon focus. | Notification updates to Paused |
| **Hardware Disconnect** | `ACTION_AUDIO_BECOMING_NOISY` | **NO** | Pause playback immediately. | Play button displays Pause icon |

---

## 14. Current Track Failure Lifecycle

Playback failure handling is governed by whether playback was established, covering all possible failure points without undefined intervals:

```text
Track Failure Occurs
        │
        ├────────────────────────────────────────────────────────────────────────┐
        ▼                                                                        ▼
[Initial Playback Phase]                                            [Established Playback Phase]
(Before playback is established, e.g. <5s, tunable)                 (During active listening, e.g. >=5s, tunable)
        │                                                                        │
        ├───────────────────────────┬───────────────────────────┐                ▼
        ▼                           ▼                           ▼           Enter Bounded Stall Recovery
[Manual User Tap]           [Auto Progression]          [Restored State]         │
        │                           │                           │           ┌────┴───────────────────────────┐
Halt in STATE_PAUSED        Attempt Bounded Skip        Halt in STATE_PAUSED│                                │
Show error banner           to Next Track               Show retry option   ▼                                ▼
Preserve queue position     (Count 1/3)                                [Network Returns]                [Policy Exhausted]
                                                                        Resume smoothly                  Pause at last offset
                                                                                                         Notify user with retry
```

### 14.1 Complete Failure Lifecycle Rules
1. **Initial Playback Phase (Recommended Default: <5 seconds rendered, Implementation-Tunable)**:
   - If initiated by explicit user selection or session restoration: System halts in `STATE_PAUSED` on the selected track and displays an informative error Snackbar with a retry action.
   - If reached during automatic queue progression: System attempts a bounded auto-advance to the next candidate track (Section 15).
2. **Established Playback Phase (Recommended Default: $\ge$5 seconds rendered, Implementation-Tunable)**:
   - System first attempts transparent token refresh and bounded stall recovery (Section 11 & 12).
   - If recovery is exhausted: Playback halts in `STATE_PAUSED` at the last valid timestamp.
   - **Crucial Rule**: The system does NOT automatically skip away from a song the user was actively engaged with. It alerts the user: *"Playback interrupted. [Retry]"*.
3. **Queue Integrity**:
   - Failed tracks are NEVER deleted from the user's queue or playlist. They remain visible with an unobtrusive error indicator.

---

## 15. Next Track Failure

When Track A finishes and the automatic transition to Track B fails:

```text
Track A Ends ────► Transition to Track B ────► Stream Resolution Fails
                                                       │
                                  ┌────────────────────┴────────────────────┐
                                  ▼                                         ▼
                       Provider Fallback OK                      All Providers Fail for B
                                  │                                         │
                         Play Track B smoothly                  Consecutive Failure Count < 3?
                                                                 ├── YES ──► Skip to Track C (Count++)
                                                                 └── NO  ──► STOP Playback (Pause & Alert)
```

### 15.1 Bounded Auto-Skip Protocol
- **Consecutive Skip Limit**: Maximum **3 tracks** (`MAX_CONSECUTIVE_AUTO_SKIPS = 3`, implementation-tunable).
- If Track B fails $	o$ tries Track C $	o$ fails $	o$ tries Track D $	o$ fails:
  - System halts playback immediately.
  - Enters `STATE_PAUSED`.
  - Displays persistent notification/snackbar: *"Multiple tracks unavailable. Playback stopped."*
  - Resets failure counter upon any subsequent manual user interaction.

---

## 16. Queue Exhaustion & Continuity Engine

As established in Phase 4B-1, Sonara provides continuous listening via `ContinuityEngine`.

```text
Queue Playing ────► Pre-fetch Threshold Reached (e.g. 30s before end of last track, tunable)
                           │
                           ▼
                  Call ContinuityEngine.generateRecommendations(lastTrackId)
                           │
                  ┌────────┴────────┐
                  ▼                 ▼
            [SUCCESS]         [FAILURE / EMPTY]
                  │                 │
            Append items to   Track finishes to STATE_ENDED
            Playback Queue    Player enters STATE_PAUSED
```

### 16.1 Continuity Rules
1. **Proactive Generation**:
   - `ContinuityEngine` is invoked **before** the final track ends (recommended default: **30 seconds remaining**, implementation-tunable).
   - Generation MUST NOT wait for `STATE_ENDED`.
2. **User Modifies Queue While Continuity is Generating**:
   - If the user manually adds tracks or reorders the queue while `ContinuityEngine` is in-flight:
   - The continuity candidates are appended *after* all user-added tracks, preserving user intent precedence.
3. **Continuity Disabled / Repeat Mode Active**:
   - If `RepeatMode == ONE`: Current track loops indefinitely; continuity is not invoked.
   - If `RepeatMode == ALL`: Queue loops to index 0; continuity is not invoked.
   - If `Continuity == OFF` and `RepeatMode == OFF`: Playback finishes the last track and halts gracefully at `STATE_ENDED`.

---

## 17. Continuity Failure & Recovery

When `ContinuityEngine` fails, times out, or returns 0 candidates:

1. **Graceful Queue Termination**:
   - The active track finishes playing completely from local buffer.
   - Upon track completion, ExoPlayer transitions to `STATE_ENDED`.
   - `SonaraPlaybackService` pauses the session and updates the notification to show `PAUSED` at end of track.
2. **Manual Retry Option**:
   - UI presents a clean empty-queue state with a "Start Radio / Autoplay" button allowing the user to re-trigger continuity manually.
3. **No Phantom Re-Triggers**:
   - The service will NOT loop or hammer the recommendation API if continuity fails once for a given track session.

---

## 18. Queue Mutation During Active Playback

Users frequently edit playlists and queues while music is playing:

```text
User Mutates Queue (Add / Remove / Reorder / Clear)
                   │
                   ▼
       Is Target the Currently Playing Track?
         ├── NO  ──► Update Queue snapshot in memory; ExoPlayer timeline updates smoothly.
         └── YES ──► Handle specific mutation type below:
```

### 18.1 Mutation Specifics

| Mutation Action | Playback Effect | BackStack Effect |
| :--- | :--- | :--- |
| **Add Track to Next / End** | Zero interruption to active audio. Queue expands. | None. |
| **Remove Upcoming Track** | Zero interruption. Timeline updates. | Removed track will not play. |
| **Remove Currently Playing Track** | Active audio continues playing until user skips OR track finishes, then transitions to next available item. | Current track pushed to BackStack on skip. |
| **Reorder Queue** | Zero interruption. Current track index is updated to reflect new position. | Queue order reflects new list. |
| **Clear Queue (Except Current)** | Current track continues playing. Upcoming items removed. | BackStack preserved. |
| **Clear Entire Queue** | Audio stops immediately (`ExoPlayer.stop()`). Player enters `STATE_IDLE`. | BackStack cleared; UI resets. |

---

## 19. Shuffle & Repeat Behavior

### 19.1 Shuffle Modes (`ON` / `OFF`)
- **Toggling Shuffle ON during playback**:
  - The currently playing track **remains playing without interruption**.
  - All remaining upcoming tracks in the queue are shuffled into a randomized sequence.
  - The `BackStack` remains intact in true historical order.
- **Toggling Shuffle OFF**:
  - The current track continues playing.
  - Remaining tracks revert to their original canonical album/playlist order.

### 19.2 Repeat Modes (`OFF` / `ALL` / `ONE`)
- **`RepeatMode.ONE`**:
  - When track finishes, ExoPlayer seeks to `0:00` and restarts automatically.
  - BackStack is NOT appended with duplicates during automatic repeat loops.
- **`RepeatMode.ALL`**:
  - When the final queue track finishes, queue advances to index 0.
- **`RepeatMode.OFF`**:
  - Normal queue progression with `ContinuityEngine` fallback.

---

## 20. BackStack / Playback History Semantics

The `BackStack` maintains an authoritative chronological history of tracks played in the active session:

```text
[Track A Plays] ──► User/Auto Next ──► [Push Track A to BackStack] ──► [Track B Plays]
                                                    │
                                        User Taps Previous (within threshold)
                                                    │
                                                    ▼
                                       [Pop Track A from BackStack] ──► [Track A Plays]
```

### 20.1 BackStack Invariants
1. **Commit Timing**: A track is pushed to `BackStack` ONLY when a forward transition succeeds and begins playing the next track, or when explicitly skipped by the user.
2. **Failed Tracks Excluded**: A track that completely failed stream resolution and was auto-skipped does NOT enter the `BackStack`.
3. **Size Limit**: Bounded in memory to **50 items** (recommended default, implementation-tunable) to prevent memory growth during long listening sessions.

---

## 21. Audio Focus & System Interruptions

Sonara strictly complies with Android's `AudioManager` audio focus contracts:

```text
Audio Focus Change Event
           │
           ├──────────────────────────────┬──────────────────────────────┬──────────────────────────────┐
           ▼                              ▼                              ▼                              ▼
[AUDIOFOCUS_LOSS]              [AUDIOFOCUS_LOSS_TRANSIENT]    [LOSS_TRANSIENT_CAN_DUCK]      [AUDIOFOCUS_GAIN]
(Another music app plays)      (Phone call / Alarm rings)     (Navigation prompt / Ping)     (Interruption ended)
           │                              │                              │                              │
Pause playback immediately     Pause playback                 Lower volume to ~20%           Restore 100% volume OR
Release audio focus            Hold focus temporarily         (Duck audio smoothly)          Resume playback (if paused
Notification updates           Resume when focus returns      Restore on completion          due to transient loss)
```

### 21.1 Audio Focus Rules
1. **Permanent Loss (`AUDIOFOCUS_LOSS`)**:
   - ExoPlayer pauses playback immediately.
   - Audio focus is abandoned.
   - User must explicitly tap Play to reclaim focus and resume listening.
2. **Transient Loss (`AUDIOFOCUS_LOSS_TRANSIENT`)**:
   - Example: Incoming phone call or VoIP ring.
   - Playback pauses immediately.
   - If focus returns (`AUDIOFOCUS_GAIN`), playback resumes automatically if the track was playing prior to interruption and user has not paused in the interim.
3. **Transient Loss with Ducking (`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`)**:
   - Example: Navigation prompt or messaging chime.
   - Media3 / ExoPlayer automatically reduces audio volume smoothly to ~20%.
   - Restores full volume as soon as the prompt finishes.
4. **Delayed Focus Gain**:
   - If focus is requested while telephony is active, playback waits in `PAUSED` until focus is granted.

---

## 22. Bluetooth & Audio Output Route Changes

### 22.1 Becoming Noisy (`ACTION_AUDIO_BECOMING_NOISY`)
> **Core Rule**: Disconnecting headphones or Bluetooth audio MUST NEVER blast audio through the device's loud internal speaker.

- When wired headphones are unplugged OR a Bluetooth A2DP device disconnects:
  - Android broadcasts `AudioManager.ACTION_AUDIO_BECOMING_NOISY`.
  - `SonaraPlaybackService` catches this broadcast and **pauses playback immediately**.
  - Notification and UI reflect `PAUSED` state.

### 22.2 Bluetooth Reconnection
- When a Bluetooth device reconnects:
  - Playback **remains PAUSED**.
  - Audio DOES NOT start automatically merely because headphones reconnected, preventing embarrassing surprises.
  - If the user presses the physical "Play" button on the Bluetooth headset, the `MediaSession` receives `KEY_MEDIA_PLAY` and starts playback.

---

## 23. MediaSession & External Controls

All playback control inputs—whether from Compose UI, System Notification, Lock Screen, Wear OS, Android Auto, or Bluetooth headsets—route through the single `MediaSession` boundary:

```text
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│ Compose In-App  │   │ System Notif /  │   │ Bluetooth /     │   │ Android Auto /  │
│ UI Controllers  │   │ Lock Screen     │   │ Headset Buttons │   │ External Bridge │
└────────┬────────┘   └────────┬────────┘   └────────┬────────┘   └────────┬────────┘
         │                     │                     │                     │
         └─────────────────────┼─────────────────────┴─────────────────────┘
                               ▼
                   MediaSession Callback Boundary
                               │
                               ▼
                    SonaraPlaybackService
                               │
                               ▼
                    Authoritative ExoPlayer
```

**Invariant**: Notification actions invoke the exact same methods (`play()`, `pause()`, `seekToNext()`, `seekToPrevious()`) as in-app buttons. There is zero duplicated or divergent playback logic.

---

## 24. Background Playback & UI Synchronization

1. **Foreground Service Lifecycle**:
   - `SonaraPlaybackService` runs as a Foreground Service with `android:foregroundServiceType="mediaPlayback"`.
   - It is never paused, throttled, or killed merely because the user minimizes the app or turns off the screen.
2. **UI State Disconnect in Background**:
   - In accordance with Phase 4B-2 and Phase 4C, Compose UI flow collection stops in background (`collectAsStateWithLifecycle`).
   - High-frequency position updates stop emitting to the UI layer while backgrounded, saving CPU and battery.
3. **UI State Resynchronization on Foregrounding**:
   - When the user opens the app, Compose UI reconnects to `MediaController` flows and synchronizes state promptly without UI tearing.

---

## 25. App & Service Lifecycle Boundaries

```text
┌─────────────────────────────┐        ┌─────────────────────────────┐
│       UI / Activity         │        │    SonaraPlaybackService    │
├─────────────────────────────┤        ├─────────────────────────────┤
│ • Destroyed on rotate/exit  │ ◄────► │ • Survives UI destruction   │
│ • Recreated dynamically     │ (IPC)  │ • Hosts ExoPlayer instance  │
│ • Observes MediaController  │        │ • Holds Foreground Notif    │
└─────────────────────────────┘        └─────────────────────────────┘
```

1. **Activity Rotation / Recreation**:
   - Activity teardown does NOT touch `SonaraPlaybackService`.
   - Audio continues uninterrupted.
2. **User Swipes App from Recents**:
   - If audio is `PLAYING`: Service remains alive as Foreground Service; notification remains visible.
   - If audio is `PAUSED`: Android OS may reclaim the service; state snapshot is already persisted in Proto DataStore.
3. **Explicit Service Termination**:
   - User swipes away notification while paused OR taps "Stop/Close":
   - Service stops foreground state, ensures snapshot is persisted, releases ExoPlayer, and terminates cleanly (`stopSelf()`).

---

## 26. Process Death & Proactive Snapshot Persistence

Android process termination via the Low Memory Killer (LMK) does not provide an opportunity to execute asynchronous writes. Therefore, session state is persisted **proactively during normal operation**:

```text
NORMAL OPERATION
       │
       ▼
PROACTIVE SNAPSHOT PERSISTENCE (Track Change, State Change, Queue Mutation, Position Checkpoint)
       │
       ▼
PROCESS TERMINATION BY OS (LMK)
       │
       ▼
NO ASYNCHRONOUS WRITE ASSUMED AT DEATH
       │
       ▼
NEXT APPLICATION LAUNCH
       │
       ▼
RESTORE MOST RECENT VALID SNAPSHOT (Queue, Track, Position)
       │
       ▼
INITIAL RESTORED STATE = STATE_PAUSED (Mandatory)
       │
       ▼
EXPLICIT USER PLAY (Lazy Stream Resolution)
```

### 26.1 Process Restoration Rules
1. **Proactive Persistence**:
   - Snapshots are persisted through the established persistence triggers defined by Phase 4B-3, including track transitions, queue mutations, playback mode/state changes, and throttled position checkpoints.
   - Process death assumes no final write capability. Restoration reflects the most recently persisted valid snapshot, not an exact guaranteed state at the instant of termination.
2. **Restored State is Strictly PAUSED**:
   - Audio MUST NOT begin playing automatically upon app launch.
3. **Queue & Offset Restored**:
   - Restored UI displays the track title, artist, artwork, and scrub position from the persisted snapshot.
4. **Lazy Stream Re-Resolution**:
   - Expired stream URLs are NOT resolved during app startup. Resolution occurs on-demand when the user explicitly taps Play.

---

## 27. User Intent vs Automatic Recovery

| Scenario | System State | User Action | Authoritative Outcome |
| :--- | :--- | :--- | :--- |
| **Network returns while paused** | Player paused; network reconnects | None | Remains **PAUSED**. Does NOT auto-resume. |
| **User pauses during stall** | Player buffering; recovery policy running | User taps Pause | Remains **PAUSED**. Recovery policy timer cancelled. |
| **User skips during token refresh** | Loader thread refreshing 403 URL | User taps Next | Token refresh discarded; skips to Next track under new version. |
| **User selects track during shuffle**| Queue in shuffle mode | User taps Track #5 in playlist | Track #5 plays immediately; remaining queue reshuffles. |
| **Headphones disconnect while loading**| Stream resolving for new song | Unplug jack | Track finishes loading into **PAUSED** state. |

---

## 28. Canonical Recovery Sequences

### Sequence A: Stream Resolution Fails $	o$ Provider Fallback $	o$ Success
1. User taps Track A.
2. `StreamResolverPort` requests URL from Primary Provider (YouTube Music).
3. Primary Provider returns HTTP 503 Service Unavailable.
4. `StreamResolverPort` catches 503, immediately calls Secondary Provider (JioSaavn).
5. Secondary Provider returns valid stream URL.
6. ExoPlayer loads stream URL and begins playback. User experiences only a brief provider fallback delay.

### Sequence B: Mid-Stream Token Expiration $	o$ Transparent Refresh $	o$ Success
1. Track A is playing.
2. CDN token expires; ExoPlayer loader receives HTTP 403 Forbidden.
3. `RefreshingDataSource` intercepts 403 on loader thread, blocks loader, and calls `StreamResolverPort.resolveStreamUrl(trackA)`.
4. Fresh authenticated URL obtained promptly.
5. `DataSpec` updated with new URL; loader retries read at target byte-offset.
6. Playback continues uninterrupted with no UI state tearing.

### Sequence C: Buffer Drains Offline $	o$ Reconnect $	o$ Auto-Resume
1. Wi-Fi drops while Track A is playing.
2. Pre-buffered audio plays until exhausted.
3. ExoPlayer enters `STATE_BUFFERING`; Play/Pause button shows buffering spinner; higher-level stall recovery policy timer starts (60s default).
4. User enters cellular coverage before policy timer expires.
5. `ConnectivityManager` validates WAN link; `SonaraPlaybackService` triggers buffer reload.
6. Buffer reaches minimum threshold; audio resumes playing automatically; spinner reverts to Pause icon.

### Sequence D: Current Track Fails Across All Providers $	o$ Bounded Halt
1. User taps Track B.
2. Primary and Secondary providers both fail with 404 / 500.
3. System catches terminal `ProviderUnavailableException`.
4. Player halts in `STATE_PAUSED` at Track B; queue position is preserved.
5. UI displays Snackbar: *"Unable to stream track. Tap to retry."*

### Sequence E: Next Track Fails During Progression $	o$ Bounded Skip
1. Track A finishes naturally. System initiates transition to Track B.
2. Track B resolution fails across all providers.
3. Consecutive failure count is 1 (<3).
4. System automatically attempts transition to Track C.
5. Track C resolves successfully and starts playing.
6. UI displays brief toast: *"Track B unavailable. Playing Track C."*

### Sequence F: Consecutive Next Tracks Fail $	o$ Bounded Auto-Skip Stop
1. Track A finishes; Tracks B, C, and D all fail resolution consecutively.
2. Consecutive failure count reaches 3 (`MAX_CONSECUTIVE_AUTO_SKIPS`).
3. System stops auto-skipping to prevent battery drain.
4. Player halts in `STATE_PAUSED`; audio focus is released.
5. UI displays persistent error state: *"Multiple tracks unavailable. Check your connection."*

### Sequence G: All Queue Candidates Fail $	o$ Skip Budget Exhaustion $	o$ Graceful Stop
1. Playback is active in a queue containing tracks [X, Y, Z].
2. Track X ends; auto-advance attempts Track Y $	o$ all providers fail for Y (Skip 1/3).
3. Auto-advance attempts Track Z $	o$ all providers fail for Z (Skip 2/3).
4. Queue is now exhausted; `ContinuityEngine` recommendation prefetch also fails or returns 0 playable candidates.
5. Skip budget and queue candidates are both completely exhausted.
6. `SonaraPlaybackService` transitions player to `STATE_PAUSED`, releases audio focus, and resets failure counters.
7. User queue is preserved intact in memory and persistent storage (no tracks deleted).
8. UI displays persistent empty/exhausted queue message: *"Playback stopped. All upcoming tracks unavailable."*

---

## 29. Stale Async Result Protection

To guarantee that slow background tasks never overwrite newer user decisions:

```text
   Time ──────►
   [User taps Song A] ────► Resolve A (Request #1, Version=1) ──────────────────────► [A Resolves Late] ──► DISCARDED (v1 < v2)
         │
   [User taps Song B] ────► Resolve B (Request #2, Version=2) ──► [B Resolves Fast] ──► COMMITTED (v2 == v2) ──► PLAYS SONG B
```

### 29.1 Async Protection Rules (Direction-Neutral)
1. **Transition Versioning**: Every new playback request (Play, Pause, Next, Previous, Seek) increments `transitionGenerationId`.
2. **Commit Verification**: Before any async worker (stream resolution, token refresh, metadata fetch, continuity prefetch) mutates player state or queue index, it verifies that its `generationId == currentGenerationId`. If stale, the result is discarded immediately.
3. **Socket Teardown**: Superseded requests explicitly invoke `Job.cancel()` and `Call.cancel()` on underlying HTTP sockets.

---

## 30. Concurrency & Race Scenarios

| # | Concurrency Scenario | Starting State | Competing Events | Stale Operation | Authoritative Operation | Final Playback State | Final Queue State | User Action Needed? |
|---|:---|:---|:---|:---|:---|:---|:---|:---:|
| **1** | **Next $\times$ 3 Spam** | `PLAYING` | 3 rapid Next taps | Versions 1 & 2 stream resolution | Version 3 stream resolution | `PLAYING` (Track + 3) | Index + 3; intermediate tracks in BackStack | No |
| **2** | **Next $\to$ Previous (Rapid)** | `PLAYING` | Next tap followed by Previous tap | Forward transition | Backward transition (evaluated at authoritative position) | `PLAYING` (Original Track) | Restored to original index | No |
| **3** | **Pause while Stream Resolving** | `RESOLVING` | User taps Pause during resolution | In-flight stream start (`playWhenReady=true`) | Pause intent (`playWhenReady=false`) | `STATE_PAUSED` | Position preserved at selected track | No |
| **4** | **Queue Clear while Track Loading** | `RESOLVING` | User clears entire queue | In-flight track load & resolve | Queue clear command (`ExoPlayer.stop()`) | `STATE_IDLE` | Queue empty; BackStack cleared | **YES** (Select music) |
| **5** | **Shuffle Toggle during Next Transition** | `RESOLVING` | User toggles Shuffle while Next resolves | Pre-shuffle order | New shuffled order (current resolving track pinned) | `PLAYING` (Target track) | Up Next queue reshuffled | No |
| **6** | **Process Death during Token Refresh** | `PLAYING` | OS kills process during mid-stream 403 | In-flight token refresh call | Process termination & fresh launch restore | `STATE_PAUSED` | Restored from last proactive snapshot | **YES** (Tap Play) |
| **7** | **Continuity arrives after User Adds Songs** | `PLAYING` | Continuity returns while user manually appends songs | Obsolete queue tail index | User queue mutation priority | `PLAYING` | Continuity items placed *after* user songs | No |
| **8** | **Seek $\to$ Next (Rapid)** | `PLAYING` | User drags seekbar then taps Next | In-flight seek offset command | Next track transition (Version bumped) | `PLAYING` (Next Track) | Index + 1; plays from `0:00` | No |
| **9** | **Audio Focus Loss during Buffering** | `BUFFERING` | Phone rings while stalled | Auto-resume on buffer fill | Focus loss pause (`AUDIOFOCUS_LOSS_TRANSIENT`) | `STATE_PAUSED` | Stall policy paused; resumes post-call | No |
| **10**| **Continuity arrives after Queue Cleared** | `BUFFERING` | Continuity returns after user tapped Clear Queue | Stale continuity batch append | Empty queue state | `STATE_IDLE` | Queue remains empty; batch discarded | **YES** (Select music) |
| **11**| **RepeatMode Toggle during Track Load** | `RESOLVING` | User sets `RepeatMode.ONE` while track loads | Default repeat progression | `RepeatMode.ONE` applied to loading track | `PLAYING` (Target track) | Current track set to loop | No |
| **12**| **Bluetooth Disconnect during Stream Resolve** | `RESOLVING` | BT unplugs while track resolves | Audio output routing to speakers | `ACTION_AUDIO_BECOMING_NOISY` pause | `STATE_PAUSED` | Track resolves into `PAUSED` state | **YES** (Tap Play) |

---

## 31. User Experience Rules

1. **NEVER play audio without user intent or active playback progression.**
2. **NEVER leave the Play/Pause button stuck in a permanent loading/buffering state.**
3. **NEVER wipe or corrupt the user's queue because a single song failed to load.**
4. **NEVER auto-resume audio playback when network reconnects if the user explicitly paused.**
5. **NEVER play audio through device loud-speakers when headphones are disconnected.**
6. **NEVER skip more than 3 consecutive tracks automatically without stopping and alerting the user.**
7. **NEVER lose playback history or BackStack order during rapid control inputs.**
8. **NEVER show raw technical exception class names in UI error dialogs.**
9. **NEVER auto-start playback upon application cold start or process restoration.**
10. **NEVER block the main Android UI thread during stream resolution or token refreshing.**

---

## 32. Observability & Diagnostics

During development and QA, the playback engine emits structured diagnostic logs:

```text
[PLAY-INT]  Intent=PLAY | TrackId=trk-101 | Generation=42
[PLAY-TRANS] Transition=START | From=trk-100 | To=trk-101 | Version=42
[STREAM-RES] TrackId=trk-101 | Provider=YouTubeMusic | Status=SUCCESS
[PLAY-STATE] ExoPlayer StateChanged: STATE_READY | playWhenReady=true | isPlaying=true
[STALL-POL]  Playback Stalled -> Bounded Stall Recovery Policy STARTED (Timeout=60s)
[STALL-RES]  Network Validated -> Resuming Buffer Read (Policy Active)
[AUTH-403]   HTTP 403 Intercepted -> StreamResolverPort Refresh SUCCESS (LoadAttempt=1)
[AUTO-SKIP]  Track trk-102 FAILED -> Auto-skipping to trk-103 (ConsecutiveCount=1/3)
[AFOCUS-CHG] AudioFocus Loss: AUDIOFOCUS_LOSS_TRANSIENT -> Pausing Playback
[NOISY-EVT]  ACTION_AUDIO_BECOMING_NOISY Received -> Pausing Playback Immediately
```

*(Diagnostic logs are stripped in production release builds).*

---

## 33. Comprehensive Playback Behavior Matrix

| # | Situation | Current State | Trigger | System Reaction | Recovery Path | Queue Effect | Final State | UI Representation |
|---|:---|:---|:---|:---|:---|:---|:---|:---|
| **1** | Normal Play | `PAUSED` | User taps Play | Verifies stream URL $	o$ calls `ExoPlayer.play()`. | Immediate | None | `PLAYING` | Play icon morphs to Pause. |
| **2** | Normal Pause | `PLAYING` | User taps Pause | Calls `ExoPlayer.pause()`; preserves offset. | Immediate | None | `PAUSED` | Pause icon morphs to Play. |
| **3** | Normal Next | `PLAYING` | User taps Next | Current track pushed to BackStack; loads next track under new version. | Normal resolve | Index + 1 | `PLAYING` | Artwork/title cross-fade to new track. |
| **4** | Normal Previous | `PLAYING` (>threshold) | User taps Previous | Seeks to `0:00`; continues playback. | Immediate | None | `PLAYING` | Progress bar snaps to `0:00`. |
| **5** | Normal Previous | `PLAYING` ($\le$threshold)| User taps Previous | Pops BackStack $	o$ transitions to previous track under new version. | Normal resolve | Index - 1 | `PLAYING` | Swaps to previous song. |
| **6** | Rapid Next $	imes$ 3 | `PLAYING` | Rapid skip taps | Version bumped to 3; skips 3 tracks; resolves 3rd track. | Direct load | Index + 3 | `PLAYING` | UI updates directly to track 3. |
| **7** | Stream 403 Expiry | `PLAYING` | Token expires | `RefreshingDataSource` refreshes URL on loader thread (max 1/load attempt). | Transparent | None | `PLAYING` | Zero UI interruption. |
| **8** | Stream Resolv Fail | `RESOLVING` | 503 on Provider A | Catches 503 $	o$ falls back to Provider B acyclically. | Fallback | None | `PLAYING` | Brief delay $	o$ music starts. |
| **9** | All Providers Fail | `RESOLVING` | Terminal 404/500 | Catches error $	o$ halts transition. | Manual Retry | None | `PAUSED` | Snackbar: "Track unavailable. [Retry]". |
| **10**| Network Loss (Buffer) | `PLAYING` | Wi-Fi drops | ExoPlayer renders remaining buffered audio. | Buffer run | None | `PLAYING` | No UI change while buffer lasts. |
| **11**| Buffer Empty | `PLAYING` | Buffer empty | Enters `STATE_BUFFERING`; starts stall recovery policy timer. | Bounded policy | None | `BUFFERING` | Buffering spinner over Play button. |
| **12**| Stall Reconnect | `BUFFERING` | Network returns | Detects WAN link $	o$ fills buffer $	o$ resumes playback. | Auto-resume | None | `PLAYING` | Spinner reverts to Pause; audio plays. |
| **13**| Stall Timeout | `BUFFERING` | Policy expires | Releases focus $	o$ transitions to `STATE_PAUSED`. | User action | None | `PAUSED` | Snackbar: "Playback paused (network)". |
| **14**| Pause During Stall | `BUFFERING` | User taps Pause | Pauses player; cancels stall recovery policy timer immediately. | User action | None | `PAUSED` | Pause icon; will not auto-play on WAN. |
| **15**| Next Track Fails | `PLAYING` (Track A)| Track B unavail | B fails $	o$ auto-skips to Track C (count 1/3). | Auto-skip | Index + 2 | `PLAYING` | Toast: "Track B unavailable. Playing C". |
| **16**| 3 Tracks Fail | `PLAYING` | 3 fails in a row | Max auto-skips reached $	o$ halts playback. | User action | Stays on 3rd | `PAUSED` | Error: "Multiple tracks unavailable". |
| **17**| Queue Exhaustion | `PLAYING` (Last) | Threshold reached | `ContinuityEngine` prefetches recommended tracks before `STATE_ENDED`. | Proactive | Appends items| `PLAYING` | Queue smoothly extends. |
| **18**| Continuity Fails | `PLAYING` (Last) | Recommendation 0| Current track completes $	o$ enters `STATE_ENDED`. | Graceful stop | None | `PAUSED` | Play button resets to start state. |
| **19**| Shuffle Toggle | `PLAYING` | Toggle Shuffle | Current track kept; remaining upcoming queue shuffled. | Immediate | Reshuffled | `PLAYING` | Up Next queue updates order. |
| **20**| Repeat One | `PLAYING` | Track finishes | ExoPlayer seeks to `0:00` and restarts track. | Auto-loop | None | `PLAYING` | Repeats current track smoothly. |
| **21**| Audio Focus Loss | `PLAYING` | Spotify opens | `AUDIOFOCUS_LOSS` $	o$ pauses playback immediately. | User action | None | `PAUSED` | Notification updates to Paused. |
| **22**| Phone Call | `PLAYING` | Call incoming | `AUDIOFOCUS_LOSS_TRANSIENT` $	o$ pauses playback. | Auto on hangup| None | `PAUSED` $	o$ `PLAY`| Pauses during call; resumes after. |
| **23**| Headphone Unplug | `PLAYING` | Jack removed | `ACTION_AUDIO_BECOMING_NOISY` $	o$ pauses audio. | User action | None | `PAUSED` | Audio stops; Play button shows Play. |
| **24**| Bluetooth Connect| `PAUSED` | BT connects | Service routes audio to BT; remains `PAUSED`. | User action | None | `PAUSED` | Remains paused until user taps Play. |
| **25**| Process Death | `PLAYING` | OS kills app | Snapshot already proactively persisted. Restores in `PAUSED`. | User Play | Restored | `PAUSED` | UI shows saved track at persisted offset. |
| **26**| Stale Async Commit| `RESOLVING` (A) | User taps B | Version bumped; A completes $	o$ discarded silently. | Superseded | Stays on B | `PLAYING` (B)| Track B plays; Track A never renders. |

---

## 34. Scenario-Based Acceptance Tests

- [ ] **Scenario 1 (Rapid Skip Frenzy)**: Tap Next multiple times in rapid succession. Verify only the final targeted track resolves/plays, intermediate stream requests are cancelled, and intermediate skipped tracks appear in BackStack.
- [ ] **Scenario 2 (Next $	o$ Previous Snap)**: Tap Next, then immediately tap Previous. Verify system evaluates authoritative position, cancels forward transition via version increment, and returns smoothly to original track.
- [ ] **Scenario 3 (Token Expiry Mid-Song)**: Mock HTTP 403 during active playback. Verify custom `RefreshingDataSource` executes at most one refresh via `StreamResolverPort` and playback resumes at target offset.
- [ ] **Scenario 4 (Network Stall & Reconnect)**: Cut network while streaming. Verify audio plays until buffer drains, enters `STATE_BUFFERING`, and automatically resumes when network returns within the stall recovery policy window.
- [ ] **Scenario 5 (User Pauses During Buffer Stall)**: Cut network $	o$ wait for buffering spinner $	o$ tap Pause $	o$ restore network. Verify player **remains PAUSED** and does not auto-play.
- [ ] **Scenario 6 (Provider Outage Fallback)**: Inject 503 on primary provider. Verify `StreamResolverPort` seamlessly falls back to secondary provider promptly.
- [ ] **Scenario 7 (Consecutive Unplayable Tracks)**: Mark next 3 tracks unplayable. Verify system auto-skips max 3 times, then halts gracefully in `STATE_PAUSED` with an informative error message.
- [ ] **Scenario 8 (Queue Mutation Under Load)**: Remove the currently playing song from the playlist. Verify audio continues until the track ends, then transitions to the next available item.
- [ ] **Scenario 9 (Becoming Noisy Unplug)**: Disconnect Bluetooth headset while playing. Verify playback pauses immediately and never plays over device speaker.
- [ ] **Scenario 10 (Process Restoration & Proactive Snapshot)**: Verify session snapshot is persisted proactively during normal operation $	o$ terminate app process $	o$ restart app. Verify app restores queue, track, and position from the most recently persisted valid snapshot in `STATE_PAUSED` (zero automatic stream resolution until explicit user Play; exact state at instant of LMK not guaranteed).

---

## 35. Automatic vs User-Initiated Recovery

| Event / Failure | Automatic Recovery? | Bounded Limits | User Action Required? | Rationale |
| :--- | :---: | :--- | :---: | :--- |
| **Transient Socket Reset** | **YES** | ExoPlayer load retry policy | No | Passive transport glitch; retry is transparent. |
| **Stream Token Expiry (403)** | **YES** | Max 1 refresh attempt per stream load attempt | No | Infrastructure maintenance; transparent to user. |
| **Stalled Buffer Read** | **YES** | Bounded stall recovery policy (60s default) | No | User is actively listening; auto-resume is expected. |
| **Primary Provider Outage** | **YES** | Acyclic fallback (Primary $	o$ Secondary) | No | Repository abstracts provider fragility. |
| **Next Track Failure** | **YES** | Max 3 consecutive auto-skips | No | Keeps music playing without infinite skip storm. |
| **Transient Focus Loss (Call)**| **YES** | While call is active | No | Standard Android media behavior on call finish. |
| **Permanent Focus Loss** | **NO** | N/A | **YES** (Tap Play) | Another audio app took ownership of hardware. |
| **Becoming Noisy (Unplug)** | **NO** | N/A | **YES** (Tap Play) | Prevents embarrassing public audio playback. |
| **Process Death Restart** | **NO** | N/A | **YES** (Tap Play) | Auto-play on launch violates Android UX guidelines. |
| **Explicit User Pause** | **NO** | N/A | **YES** (Tap Play) | User intent is absolute; overrides auto-resume. |

---

## 36. Implementation-Tunable Decisions

The following parameters are explicitly designated as **Implementation-Tunable** (not hardcoded architecture):

| Parameter | Recommended Initial Default | Tuning Scope / File |
| :--- | :--- | :--- |
| **Previous Button Threshold** | `3000ms` (3.0s) | Configurable in `PlaybackController` (`2000ms`–`5000ms`). |
| **Playback Stall Timeout** | `60000ms` (60s) | Configurable in `SonaraPlaybackService` recovery policy. |
| **Max Consecutive Auto-Skips** | `3 tracks` | Configurable in `QueueManager` (`2`–`5` tracks). |
| **BackStack Maximum Size** | `50 tracks` | Configurable in memory cache policy. |
| **Continuity Prefetch Trigger**| `30000ms` (30s before end) | Configurable in `ContinuityEngine` trigger. |
| **Initial Failure Phase Threshold**| `5000ms` (5.0s) | Configurable in `PlaybackController` failure handler. |
| **ExoPlayer Min Buffer Threshold**| `15000ms` (15s) | Configurable in `DefaultLoadControl`. |
| **ExoPlayer Max Buffer Threshold**| `50000ms` (50s) | Configurable in `DefaultLoadControl`. |
| **Transition Debounce Window** | `100ms` | Configurable in `MediaController` input handler. |

---

## 37. Final Playback Contracts

### Play Contract
`Play` requests must verify stream validity, resolve missing URLs on-demand via `StreamResolverPort`, and only reflect active playback state in the UI once confirmed by ExoPlayer.

### Pause Contract
`Pause` requests must halt audio promptly, preserve authoritative playback position, and cancel any active higher-level stall recovery policy timers to prevent unwanted automatic playback.

### Next / Skip Contract
`Next` commands must push the current track to `BackStack`, increment the direction-neutral transition generation ID to supersede in-flight operations, and load the new track cleanly without corrupting queue state.

### Previous Contract
`Previous` commands must evaluate authoritative playback position at the moment the intent is processed, rewinding to `0:00` if position > threshold, or popping the previous track from `BackStack` if position $\le$ threshold, while incrementing transition generation ID to invalidate any in-flight forward transition.

### Stale Result Protection Contract
All asynchronous operations (resolution, token refresh, prefetch) must verify their generation version before committing. Stale results must be discarded silently.

### Stream Expiration Contract
Expired stream URLs (HTTP 403) must be refreshed transparently on ExoPlayer's loader thread via `RefreshingDataSource` with at most one refresh attempt per stream load attempt.

### Buffering & Stall Contract
ExoPlayer manages buffer loading; prolonged buffer exhaustion initiates Sonara's higher-level bounded stall recovery policy (60s default). Audio resumes automatically if network returns within the window, and transitions to `STATE_PAUSED` if it expires.

### Audio Focus & Hardware Contract
Sonara must pause immediately on permanent focus loss or `ACTION_AUDIO_BECOMING_NOISY` (headphone disconnect), duck on transient notifications, and never auto-play on Bluetooth connection.

### Continuity Contract
`ContinuityEngine` prefetching must trigger *before* `STATE_ENDED`. If continuity fails, playback halts gracefully at end-of-track in `STATE_PAUSED`.

### Lifecycle & Restoration Contract
Restored sessions after app launch or process death must initialize in `STATE_PAUSED` from the most recently persisted valid snapshot, requiring explicit user initiation to play.

---

## 38. Phase 4D Decision Register

| Decision | Status | Final Behavioral Rule | Rationale | Implementation Notes |
| :--- | :---: | :--- | :--- | :--- |
| **Playback State Authority** | ✅ DECIDED | ExoPlayer inside `SonaraPlaybackService` is single truth; no optimistic UI play state. | Prevents out-of-sync UI states during slow stream loads. | UI observes `MediaController` state flows. |
| **Transition Versioning** | ✅ DECIDED | Direction-neutral monotonic `transitionGenerationId` on every user control event. | Prevents slow out-of-order async responses from committing stale state. | Simple atomic integer or counter in Service. |
| **Pause Recovery Suppression** | ✅ DECIDED | User pause cancels all stall-recovery policy timers and suppresses auto-resume. | User intent is absolute; network recovery must not surprise user. | Flag `userExplicitlyPaused = true`. |
| **Consecutive Skip Limit** | ✅ DECIDED | Max 3 consecutive automated failure skips before stopping. | Prevents endless skip loops, battery drain, and provider rate-limiting. | Tracked in `QueueManager` (tunable). |
| **Previous Threshold** | ✅ DECIDED | 3.0 seconds threshold (rewind vs backstack pop). | Standard industry media player behavior (Spotify/Apple Music). | Implementation-tunable parameter. |
| **Becoming Noisy Protection** | ✅ DECIDED | Pause immediately on `ACTION_AUDIO_BECOMING_NOISY`. | Prevents sudden blasting of audio through phone speakers. | BroadcastReceiver registered in Service. |
| **Process Restoration** | ✅ DECIDED | Proactive snapshot persistence; restores queue and position strictly in `STATE_PAUSED`. | Autoplay on startup violates Android UX standards; LMK gives no death write opportunity. | Loaded from Proto DataStore snapshot. |
| **Continuity Trigger** | ✅ DECIDED | Proactive prefetch before end of track; never wait for `STATE_ENDED`. | Prevents audible gaps between songs during continuous playback. | Triggered via player position listener. |
| **Token Refresh Limit** | ✅ DECIDED | Max 1 transparent token refresh per stream load attempt. | Prevents infinite retry loops while allowing future load attempts to refresh. | Handled in `RefreshingDataSource`. |
| **Stall Policy Separation** | ✅ DECIDED | ExoPlayer owns buffer machinery; Sonara maintains higher-level bounded stall policy. | Clear architectural boundary without duplicating ExoPlayer state machine. | Tunable 60s default. |
| **Tunable Constants** | ⏸ DEFERRED | Timeouts, retry counts, buffer sizes, debounce windows. | Implementation-level optimization parameters. | Tunable in Gradle build config / constants. |

---

## 39. Frozen Architecture Compatibility

- **Phase 4A**: 100% Android-native Kotlin and Compose principles maintained.
- **Phase 4B-1**: Single `MediaSessionService` authority, `ExoPlayer` ownership, `StreamResolverPort` boundary, and `ContinuityEngine` interaction are fully respected.
- **Phase 4B-2**: UDF state flow, presentation error mapping via `UiText`, and isolated high-frequency scrub position tracking remain completely intact.
- **Phase 4B-3**: Single source of truth in Repositories, Room Liked/History persistence, and proactive Proto DataStore session persistence are preserved without deviation.
- **Phase 4C**: Bounded stall recovery, acyclic provider fallback, and fail-fast networking are operationalized without duplication.

**Result**: Zero architectural contradictions. Phase 4D completes the behavioral blueprint for all playback failure, recovery, and transition flows.

---

## 40. Risks & Explicit Future Decisions

| Risk | Impact | Mitigation |
| :--- | :--- | :--- |
| **Rapid Network Handover Glitches** | Low | ExoPlayer `DefaultLoadErrorHandlingPolicy` and bounded stall recovery policy absorb socket resets. |
| **Unreliable Upstream Stream Endpoints** | Medium | Acyclic provider fallback (`StreamResolverPort`) and 3-track skip limit protect user experience. |
| **Aggressive OS Memory Reclaim (LMK)** | Low | Proactive Proto DataStore session snapshots ensure valid state recovery in `STATE_PAUSED`. |

---

## 41. Sources & Evidence

- **Android Developers**: [Media3 ExoPlayer Architecture & LoadControl](https://developer.android.com/media/media3/exoplayer)
- **Android Developers**: [Managing Audio Focus & Becoming Noisy](https://developer.android.com/guide/topics/media-apps/audio-focus)
- **Android Developers**: [MediaSessionService & MediaController Lifecycle](https://developer.android.com/media/media3/session)
- **Kotlin Coroutines**: [Structured Concurrency & Job Cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- **Sonara Architecture**: Phases 4A, 4B-1, 4B-2, 4B-3, and 4C Decision Registers.
