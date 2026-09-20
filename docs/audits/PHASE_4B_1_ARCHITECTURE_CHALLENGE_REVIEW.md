# SONARA ANDROID — PHASE 4B-1 ARCHITECTURE CHALLENGE REVIEW

## 1. Executive Verdict

**ARCHITECTURE REVIEW VERDICT**
───────────────────────────
Phase 4B-1: **APPROVE WITH CORRECTIONS**
Fundamental architectural flaw: **NO**
Corrections required before 4B-2: **YES** (Targeted)
Phase 4B-2 readiness: **READY** (Once corrections are applied)

The core playback architecture proposed in Phase 4B-1 is fundamentally sound and correctly applies modern AndroidX Media3 capabilities. The isolation of `ExoPlayer` into a `MediaSessionService` and the client-side placement of `MediaController` correctly prevents the primary failure modes observed in the Sonara Web legacy codebase.

However, a critical architectural defect was found regarding the `ContinuityEngine` boundary that would cause mandatory playback gaps (silence) between the user's queue and auto-generated tracks. This must be corrected. Additionally, a few boundaries require stricter qualification.

---

## 2. MCP / Skill / Tool Selection

### Tools Utilized
- **`search_web` (Android Developer Docs)**: Used to verify Media3 capabilities, specifically regarding URL refresh on background loader threads (intercepting HTTP 403 via `DataSource`) and queue position detection (`onMediaItemTransition`).
- **`ponytail` (Mental Model)**: Guided the review process to challenge over-engineered abstractions (e.g., verifying that relying on ExoPlayer's `Timeline` is better than building a parallel custom Queue object).

### Tools Not Utilized
- **`context7`**: Previously encountered schema validation errors when queried. Authoritative web searches were faster and provided guaranteed up-to-date Media3 documentation.
- **`serena` / `android-cli` / `Code Refactoring`**: Not applicable, as this is a pure architectural review with no source code to analyze or modify.

---

## 3. Phase 4A Compatibility Check

The Phase 4B-1 proposal was checked against the frozen Phase 4A baseline.

- **Layer Boundaries**: Compatible. The service boundary and client UI boundary map cleanly to Pragmatic Layered UDF.
- **Domain Independence**: Compatible. Domain engines remain pure Kotlin.
- **State Ownership**: Compatible. Playback state is strictly separated from library/history state.
- **Process Model**: Compatible. Single process structure is maintained.

**Result**: Phase 4B-1 is fully compatible with Phase 4A. No conflicts found.

---

## 4. Decision-by-Decision Challenge Review

### A. EXOPLAYER TIMELINE AS THE SOLE QUEUE AUTHORITY
**Status**: 🟢 ACCEPT
**Analysis**: Relying entirely on ExoPlayer's `Timeline` is the correct decision. `MediaController` provides native `addMediaItem`, `removeMediaItem`, and `moveMediaItem` commands. Metadata associated with tracks can be stored in `MediaItem.RequestMetadata.extras` or `MediaItem.LocalConfiguration`. Building a parallel "Sonara Queue" object in memory would immediately introduce a dual-source-of-truth problem and race conditions (exactly as happened in Sonara Web).

### B. MEDIACONTROLLER + PLAYERVIEWMODEL STATE BOUNDARY
**Status**: 🟡 ACCEPT WITH CLARIFICATION
**Analysis**: The boundary is correct. `MediaController` is a client-side proxy, and `PlayerViewModel` converts its callbacks into `StateFlow`.
**Clarification Required**: Media3's `Player.Listener` can fire multiple rapid callbacks for a single logical event (e.g., `onMediaItemTransition` followed immediately by `onPlaybackStateChanged`). `PlayerViewModel` must conflate these state updates so that Compose does not suffer tearing or unnecessary recomposition. The architectural contract must specify that `PlayerUiState` emissions are conflated/batched.

### C. STREAM RESOLUTION + STREAM REFRESH ARCHITECTURE
**Status**: 🟢 ACCEPT
**Analysis**: The proposed `RefreshingDataSource` intercepting HTTP 403/410 errors is architecturally sound and supported by Media3. Because ExoPlayer's `DataSource.open()` is called on a background Loader thread, blocking to fetch a new URL via `StreamResolverPort` and retrying the request with the new URL is safe, effective, and completely transparent to the UI.

### D. CONTINUITY ENGINE → PLAYBACK SERVICE BOUNDARY
**Status**: 🔴 CHANGE REQUIRED
**Analysis**: Phase 4B-1 proposed invoking the `ContinuityEngine` when the queue reaches *exhaustion* (i.e., `STATE_ENDED`).
**Defect**: If the player waits for `STATE_ENDED` to query the engine, audio focus is dropped, playback stops, and there is a mandatory gap of silence while the engine computes and the new stream resolves.
**Correction**: Prefetching is NOT an optimization; it is a structural requirement for continuous playback. The orchestration layer must detect when the *last track in the queue begins playing* (via `onMediaItemTransition` where `currentMediaItemIndex == mediaItemCount - 1`) and invoke `ContinuityEngine` at that time.

### E. PROCESS-DEATH / SESSION RECOVERY CONTRACT
**Status**: 🟡 ACCEPT WITH CLARIFICATION
**Analysis**: The decision to recover the current track, queue, position, and modes is correct, and deferring the serialization format to Phase 4B-3 is appropriate.
**Clarification Required**: The architectural contract must explicitly mandate that upon process-death restoration, the restored state is ALWAYS `PAUSED`. If the app was killed while playing in the background, restarting the process and auto-resuming playback without user intent violates Android background execution guidelines and user expectations.

### F. IMPLEMENTATION POLICY LEAKAGE
**Status**: 🟢 ACCEPT
**Analysis**: Phase 4B-1 successfully avoided specifying implementation details (such as JSON vs DataStore, precise ViewModel scopes, or precise HTTP clients), correctly marking them as tunable or deferred to Phase 4B-2/4B-3.

---

## 5. Findings Table

| Area | Status | Severity | Finding | Required Action |
| :--- | :--- | :--- | :--- | :--- |
| Continuity Trigger | 🔴 CHANGE REQ | High | Triggering continuity at queue exhaustion causes playback gap. | Change trigger to *start of last track*. |
| State Boundary | 🟡 CLARIFICATION | Low | Multiple listener callbacks can cause UI state tearing. | Add state conflation contract. |
| Session Recovery | 🟡 CLARIFICATION | Medium | Auto-resuming playback on process restart violates platform norms. | Mandate restoration to PAUSED state. |
| Queue Authority | 🟢 ACCEPT | None | ExoPlayer Timeline is sufficient for MVP queue needs. | None. |
| Stream Refresh | 🟢 ACCEPT | None | Background blocking `DataSource` wrapper is supported by Media3. | None. |

---

## 6. Architectural Changes Required

Before proceeding to Phase 4B-2, the following targeted corrections must be applied to the Phase 4B-1 document (or treated as amended):

1. **Section 11.3 (Queue Exhaustion)**: Must be renamed/rewritten to "Continuity Prefetch Trigger". It must state that orchestration detects when the *last item in the queue begins playing* (not when the queue empties) to invoke the `ContinuityPort`.
2. **Section 9.2 (State Derivation Rules)**: Must add a rule that `PlayerViewModel` conflates synchronous `Player.Listener` callbacks to prevent emitting torn state to Compose.
3. **Section 19.3 (Session Snapshot)**: Must add an explicit rule: "Upon restoration from a session snapshot, the initial playback state must always be PAUSED, regardless of the state at the time of process death."

---

## 7. Decisions That Should Remain Frozen

The following major decisions have survived the challenge review and should be considered **FROZEN**:
- `ExoPlayer` ownership by `MediaSessionService`.
- Client-side placement of `MediaController`.
- Single queue authority via `Timeline`.
- Rapid skip cancellation model (Last-writer-wins).
- Audio focus delegation to Media3.
- Stream refresh via background `DataSource` wrapper.

---

## 8. Deferred Decisions

- **Phase 4B-2**: Precise `StateFlow` hierarchy, exact `PlaybackClockPort` high-frequency mechanism, Compose UI architecture.
- **Phase 4B-3**: Session snapshot serialization format, Media Resumption API opt-in, SQLite schemas.
- **Phase 5**: Implementation details, Kotlin class creation, Gradle setup.

---

## 9. Final Recommendation

**APPROVE PHASE 4B-1 WITH TARGETED CORRECTIONS**

The Phase 4B-1 document provides a highly robust, Android-native foundation for Sonara. Once the Continuity prefetch timing and session recovery paused-state rules are amended, the playback boundary will be fully secured. The project is ready to move to Phase 4B-2 (State & UI Architecture).
