# Sonara — Track Switching Pipeline Implementation Report

## 1. Executive summary

Implemented the targeted playback corrections for DEFECT-TRK-01, DEFECT-TRK-02, DEFECT-TRK-03, and RISK-TRK-05 without introducing another playback engine or changing the Media3 service boundary.

- New manual selections and non-preloaded Next/Previous transitions pause the current item before stream resolution.
- Contextual track lists now reach `PlayerViewModel` from Search, Library, Home modules/detail lists, and user playlists.
- The existing preloaded Next fast path remains gapless and does not pause or re-resolve.
- Controller-reported playback request failures carry a request generation and are reconciled only against the matching local transition.
- Unexpected resolver exceptions now clean up the authoritative transition and leave a retryable error state.

## 2. Confirmed root causes

### DEFECT-TRK-01

`PlayerViewModel.playTrack()` updated `transitionalTrack` and started asynchronous resolution, but did not pause the current Media3 item first. The same omission existed in the ViewModel fallback branches for Next and Previous. Consequently, the old item could continue playing while the UI presented the new target.

### DEFECT-TRK-02

`MainActivity.handlePlayTrack()` accepted only a `Track` and `PlaybackOrigin`, and called `playerViewModel.playTrack(track, origin = origin)` with an empty context queue. `PlaybackQueueEngine.setContext()` therefore cleared the prior upcoming queue whenever a track was selected from a list.

### DEFECT-TRK-03

ViewModel fallback Next/Previous resolution and service-side automatic/remote advancement did not share the same pause-before-resolution ordering. `SonaraPlaybackService.advanceToNext()` already paused; `advanceToPrevious()` did not.

### RISK-TRK-05

`MediaControllerClient.playTrack()` is intentionally a fire-and-forget Player command. The existing state projection did not retain which transition generation had produced a controller/player error. The ViewModel could therefore clear or retain transitional state without reliably distinguishing a stale failure from the active request.

## 3. Files and functions modified

### Playback coordination

- `app/src/main/java/com/example/sonara/feature/player/PlayerViewModel.kt`
  - `LocalPlayerState`
  - controller-state reconciliation collector
  - `playTrack()`
  - `skipToNext()` and `skipToPrevious()` fallback branches
  - `executeTrackPlay()` and new authoritative failure cleanup
- `app/src/main/java/com/example/sonara/playback/client/MediaControllerClient.kt`
  - `MediaControllerState.requestFailureGeneration`
  - `playMediaItem()` request tracking
  - connection/player error publication
  - state projection preservation
- `app/src/main/java/com/example/sonara/playback/service/SonaraPlaybackService.kt`
  - `advanceToPrevious()` fallback ordering

### Context propagation

- `app/src/main/java/com/example/sonara/MainActivity.kt`
- `app/src/main/java/com/example/sonara/feature/shell/SonaraAppRoot.kt`
- `app/src/main/java/com/example/sonara/feature/home/HomeScreen.kt`
- `app/src/main/java/com/example/sonara/feature/home/components/HomeModules.kt`
- `app/src/main/java/com/example/sonara/feature/home/screens/ArtistDetailScreen.kt`
- `app/src/main/java/com/example/sonara/feature/home/screens/CuratedPlaylistDetailScreen.kt`
- `app/src/main/java/com/example/sonara/feature/search/SearchScreen.kt`
- `app/src/main/java/com/example/sonara/feature/library/LibraryScreen.kt`
- `app/src/main/java/com/example/sonara/feature/playlist/UserPlaylistDetailScreen.kt`

### Regression tests

- `app/src/test/java/com/example/sonara/feature/player/PlayerViewModelTest.kt`

## 4. New track-selection sequence

For a manual selection or a non-preloaded fallback:

1. `TransitionManager.nextGeneration()` creates the authoritative request generation.
2. The current player is paused immediately.
3. Queue context and current logical track are updated.
4. Transitional UI state records the target and generation.
5. Earlier resolver work is cancelled.
6. Local-download or remote stream resolution runs asynchronously.
7. The result is checked against the current generation before dispatch.
8. The Media3 item is dispatched through the existing `MediaControllerClient` and service.
9. Controller confirmation clears transitional presentation state; matching request errors leave a retryable target/error state.
10. Stale results, stale errors, and stale callbacks cannot replace or clear a newer selection.

The preloaded Next path still uses `peekNextMediaId()` plus `seekToNextMediaItem()` and remains gapless.

## 5. Queue-context propagation

The new context callback carries `(Track, PlaybackOrigin, List<Track>)` through `MainActivity` and `SonaraAppRoot`. The queue engine already derives the selected position by track identity and retains the entries after that identity.

Contexts now include:

- Search song results currently loaded in the active result state.
- Liked songs, downloads, and history lists currently loaded by Library.
- Home quick-picks and recommendation modules.
- Loaded artist detail tracks.
- Loaded curated playlist detail tracks.
- User playlist tracks, including the currently displayed reorder list.

The existing available in-memory list is passed as-is; no pagination completeness is fabricated. Context-free calls such as offline replay and Play Next retain their existing behavior.

## 6. Skip-path consistency

Manual Next and Previous now pause before resolving a non-preloaded target. Service-side Previous now follows the same ordering as service-side Next. Repeat-current and the preloaded gapless path remain direct player operations and are not unnecessarily paused or re-resolved.

The shared queue engine remains responsible for repeat, shuffle, backstack, duplicate prevention, and recommendation decisions.

## 7. Asynchronous failure handling

`MediaControllerClient` records the generation associated with the latest dispatched media request and publishes `requestFailureGeneration` with connection/player errors. `PlayerViewModel` only applies a controller failure to the local transition when the generations match. A stale failure is ignored for presentation and cannot clear a newer transition or its preload-consume guard.

Legacy controller errors without a generation still release the preload-consume guard for compatibility with the existing controller contract, while they do not overwrite a newer transition's error state.

Unexpected non-cancellation exceptions escaping stream resolution are handled by generation-aware cleanup. Cancellation remains silent and is treated as supersession rather than a user-facing failure.

## 8. Tests added or modified

Added focused `PlayerViewModelTest` coverage for:

- Pausing the old track before a deferred new stream resolves.
- Preserving the remaining queue when selecting the middle item of a context.
- Keeping a matching controller request failure retryable and visible.
- Preventing a stale controller failure from overwriting a newer selection.

Existing preload-consume, queue, controller pending-play, rapid-switching, and network-reliability tests were retained.

## 9. Verification results

Passed:

- `:app:compileDebugKotlin`
- `:app:compileDebugUnitTestKotlin`
- Focused `PlayerViewModelTest`, `PlaybackQueueEngineTest`, and `MediaControllerClientPendingPlayTest`
- Focused `PlayerViewModelTest` and `PointTwoPreloadConsumeTest`
- `:app:assembleDebug`

The broader playback unit-test selection executed 369 tests. Five existing `MediaControllerClientAutoQualityTest` assertions failed in unrelated video-quality behavior (`applyVideoQuality`/capability-selection expectations). No video-quality code was changed in this task, and the focused playback tests passed.

## 10. Physical-device verification

Not performed. `adb` is unavailable in the execution environment (`adb: command not found`), so no APK installation or Redmi device verification was possible.

## 11. Remaining limitations and excluded findings

- Context is the currently loaded list supplied by each screen; unloaded remote pages are not represented as a fabricated complete queue.
- Controller failure observation depends on Media3 reporting the rejected/failed request through the existing controller/player error channel; no redundant event bus was introduced.
- DEFECT-TRK-04 notification artwork null flash was intentionally not changed.
- Video playback, DASH, quality selection, artwork caching, and unrelated backend behavior were intentionally not changed.

## 12. Source-level resolution status

- DEFECT-TRK-01: resolved at source level by immediate pause-before-resolution and generation checks.
- DEFECT-TRK-02: resolved at source level for all inspected contextual entry points using the currently available lists.
- DEFECT-TRK-03: resolved at source level for ViewModel/service non-preloaded fallback paths while preserving preload behavior.
- RISK-TRK-05: addressed at source level with generation-tagged controller failure observation and stale-failure protection.

Physical-device behavior remains unverified.
