package com.example.sonara.playback.checkpoint

import android.util.Log
import androidx.media3.common.Player
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.client.MediaControllerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single authoritative history writer for Sonara Android.
 *
 * SESSION SEMANTICS (one qualified listening session == one `playback_history` row):
 *
 *  - A track merely becoming the current media item is NOT a listening event, so nothing
 *    is written when playback starts.
 *  - A session QUALIFIES after [qualificationThresholdMs] of ACTUAL playing time (paused
 *    time never counts). At that moment exactly one row is INSERTED with completed=false
 *    and its row id is retained as the session identity.
 *  - If the track later reaches [Player.STATE_ENDED], that same row is UPDATED to
 *    completed=true. A completed listen is never a second row.
 *  - Natural completion is ALSO signalled by a gapless AUTOMATIC media-item transition.
 *    With next-track prefetching, ExoPlayer moves from A straight into B while
 *    playbackState remains [Player.STATE_READY] and [Player.STATE_ENDED] is never
 *    emitted, so the service additionally calls [onAutoTransitionTo] for
 *    `MEDIA_ITEM_TRANSITION_REASON_AUTO`. The two signals are mutually idempotent:
 *    whichever arrives first closes the session and clears its row id, so the other
 *    finds no in-flight row and can never complete the same session twice.
 *  - Skipping away, or stopping, after qualification keeps the row as completed=false.
 *    Skipping or stopping before qualification writes nothing at all.
 *  - Replaying the same track is a NEW session: a session closed by STATE_ENDED can never
 *    be resumed, so the next playing state opens a fresh session even for an identical
 *    mediaId. Sessions are never deduplicated on trackId.
 *
 * UI components are strictly forbidden from writing history independently. This is the
 * ONLY production component that writes `playback_history`; it is owned, started and
 * stopped by [com.example.sonara.playback.service.SonaraPlaybackService] and is driven by
 * that service's authoritative ExoPlayer state, so it keeps measuring correctly while the
 * UI is gone (background, lock screen, auto-advance).
 */
class PlaybackHistoryCoordinator(
    private val playbackState: StateFlow<MediaControllerState>,
    private val historyRepository: HistoryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val qualificationThresholdMs: Long = 15000L, // Recommended initial default, product-tunable
    /**
     * Resolves the provenance of a track id at the moment it qualifies (Phase 2B).
     *
     * Supplied by the service from [com.example.sonara.playback.controller.PlaybackQueueEngine],
     * which owns the queue context. Defaults to LEGACY_UNKNOWN so every existing
     * construction site — and every test fake — stays source-compatible and fails closed.
     */
    private val originFor: (String?) -> PlaybackOrigin = { PlaybackOrigin.LEGACY_UNKNOWN },
    /**
     * Mints a playback session id. Injected so tests can make it deterministic; the
     * default is a random UUID, which is unique per genuine session without encoding
     * the track, queue index or a timestamp.
     */
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    /** Optional checkpoint store used only to recover an active identity across recreation. */
    private val settingsRepository: SettingsRepository? = null,
    /** Called for terminal/automatic close, never for service stop/process recreation. */
    private val onSessionClosed: ((trackId: String, sessionId: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PlaybackHistory"
        private const val TICK_INTERVAL_MS = 1000L
    }

    // ── Session state ────────────────────────────────────────────────────────────
    // The minimum needed to express the state machine. `sessionOpen` is what makes a
    // same-track replay a new session: it is cleared when a session ends, so an
    // identical mediaId arriving afterwards cannot be mistaken for the old session.
    //
    // These fields are @Volatile because [onAutoTransitionTo] is invoked synchronously
    // from the player's callback thread, while the collector and ticker run on this
    // coordinator's scope. The annotation only guarantees visibility — it does not make
    // `activePlayTimeMs += ...` atomic, and does not need to: the ticker remains the
    // sole accumulator, every other mutation is a whole-value reset.

    /** Track id of the session currently being measured, or null when none is open. */
    @Volatile
    private var sessionTrackId: String? = null

    /** True while a session is open (started and not yet ended/closed). */
    @Volatile
    private var sessionOpen: Boolean = false

    /** ACTUAL playing time accumulated for the open session. */
    @Volatile
    private var activePlayTimeMs: Long = 0L

    /** Row id of this session's inserted history row; null until it qualifies. */
    @Volatile
    private var historyRowId: Long? = null

    /**
     * Identity of the open session (Phase 2B), or "" when none is open.
     *
     * Minted exactly once in [openSession] and never recomputed, so it is inherently
     * stable across retries, reconnections, repeated Media3 callbacks and repeated
     * history writes — none of those reopen a session. A genuine new session, including
     * a deliberate replay of the same track, goes through [openSession] again and
     * therefore gets a different id. Written only from the same places that already
     * mutate the session fields, so it needs no additional synchronisation beyond their
     * @Volatile visibility.
     */
    @Volatile
    private var sessionId: String = ""

    private var restorationLoaded = false
    private var restoredTrackId: String? = null
    private var restoredSessionId: String? = null
    private var restorationConsumed = false

    // ── Lifecycle ────────────────────────────────────────────────────────────────
    // Child jobs are tracked individually so stop() can halt observation WITHOUT
    // cancelling the injected (application-scoped) parent scope. Cancelling the parent
    // was permanent and left a restarted coordinator silently dead.

    private var collectorJob: Job? = null
    private var tickerJob: Job? = null

    /** Idempotent: starting an already-running coordinator is a no-op. */
    fun start() {
        if (collectorJob?.isActive == true || tickerJob?.isActive == true) {
            return
        }

        // Observe track transitions and completion.
        collectorJob = scope.launch {
            playbackState.collect { state ->
                onControllerState(state)
            }
        }

        // Ticker accumulating ACTUAL play time toward qualification.
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                onTick(playbackState.value)
            }
        }
    }

    /** Stops observation but leaves the coordinator restartable via [start]. */
    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        tickerJob?.cancel()
        tickerJob = null
        // An abrupt stop is not a completion: any qualified row stays completed=false.
        // Clearing local state also prevents a stale row id leaking into a later session.
        closeSession()
    }

    /** Current identity for checkpointing, or null when this track is not an open session. */
    fun sessionIdFor(trackId: String): String? =
        sessionId.takeIf { sessionOpen && sessionTrackId == trackId && it.isNotBlank() }

    // ── Automatic (gapless) transition signal ────────────────────────────────────

    /**
     * Natural completion via an AUTOMATIC media-item transition.
     *
     * Must be called by the owning service for
     * `Player.MEDIA_ITEM_TRANSITION_REASON_AUTO` only — an ordinary user skip is NOT a
     * completion and must keep its qualified row at completed=false.
     *
     * Why this exists: a gapless auto-advance never produces [Player.STATE_ENDED]
     * (playbackState stays [Player.STATE_READY] throughout), so without this signal the
     * successor's state snapshot would simply open a new session and the finished
     * track's row id would be discarded, leaving it permanently completed=false.
     *
     * Why it is synchronous: the predecessor's row id is captured and the session closed
     * on the caller's thread, BEFORE the service publishes the successor's state
     * snapshot. The asynchronous collector can therefore never open the successor
     * session — and wipe [historyRowId] — ahead of the capture. Only the identity of the
     * session that is in flight at this instant is ever completed; the successor is
     * opened afterwards with no row id and must still earn its own
     * [qualificationThresholdMs] of actual playing time.
     *
     * The database write itself is dispatched to [scope] because this runs on the
     * player's callback thread, which must not block.
     *
     * @param newTrackId media id of the successor item, or null/empty if unknown.
     */
    fun onAutoTransitionTo(newTrackId: String?) {
        // Not observing (never started, or stopped): there is no session in flight to
        // complete, and opening one here would leave stale state for a later start().
        if (collectorJob == null && tickerJob == null) {
            return
        }

        // 1. Capture the predecessor's identity before any state is discarded.
        //    A closed session (already completed by STATE_ENDED, or stopped) has no row
        //    to complete, which is what makes the two completion signals idempotent.
        val rowToComplete = if (sessionOpen) historyRowId else null

        // 2. Clear the completed session, then open the successor.
        closeSession()
        if (!newTrackId.isNullOrEmpty()) {
            openSession(newTrackId)
        }

        // 3. Mark exactly that row completed. Nothing is inserted here.
        if (rowToComplete != null) {
            scope.launch { completeQualifiedRow(rowToComplete, "auto_transition") }
        }
    }

    // ── State machine ────────────────────────────────────────────────────────────

    private suspend fun onControllerState(state: MediaControllerState) {
        ensureRestorationLoaded()
        val currentId = state.currentMediaItem?.mediaId

        if (state.playbackState == Player.STATE_ENDED) {
            handleSessionEnded(currentId)
            return
        }

        if (currentId.isNullOrEmpty()) {
            return
        }

        // A new session starts when nothing is open, or when the media item changed.
        if (!sessionOpen || currentId != sessionTrackId) {
            if (sessionOpen) closeSession(clearPersistedIdentity = true)
            openSession(currentId)
        }
    }

    private suspend fun ensureRestorationLoaded() {
        if (restorationLoaded) return
        val snapshot = settingsRepository?.getPlaybackSession()
        restoredTrackId = snapshot?.lastTrackId
        restoredSessionId = snapshot?.playbackSessionId
        restorationLoaded = true
    }

    /**
     * Natural completion. The open session's row (if it qualified) is marked completed,
     * and the session is closed first so repeated STATE_ENDED emissions cannot mark twice.
     */
    private suspend fun handleSessionEnded(currentId: String?) {
        val endedTrackId = sessionTrackId
        val rowToComplete = historyRowId

        closeSession(clearPersistedIdentity = true)

        val sessionMatches = (endedTrackId != null) &&
            ((currentId == null) || (currentId == endedTrackId))

        if ((rowToComplete != null) && sessionMatches) {
            completeQualifiedRow(rowToComplete, "state_ended:$endedTrackId")
        }
    }

    /**
     * The single place a history row is marked completed — shared by the
     * [Player.STATE_ENDED] path and the automatic-transition path, so there is exactly
     * one completion write in the codebase.
     *
     * Both callers capture [rowId] from the session that finished and close that session
     * before reaching here, so a given session's row is marked at most once no matter
     * which completion signal (or both) the player delivers.
     */
    private suspend fun completeQualifiedRow(rowId: Long, reason: String) {
        historyRepository.markHistoryCompleted(rowId)
        Log.i(TAG, "History session completed (row=$rowId, via=$reason)")
    }

    private fun openSession(trackId: String) {
        sessionTrackId = trackId
        sessionOpen = true
        activePlayTimeMs = 0L
        historyRowId = null
        // Adopt a persisted id only for the first active state of the matching track. A
        // different track, a terminal close, or a replay consumes the candidate and mints.
        // Missing/legacy ids remain unsupported and therefore fail closed to a new UUID.
        val persistedId = if (!restorationConsumed && restoredTrackId == trackId) {
            restoredSessionId
        } else {
            null
        }
        restorationConsumed = true
        sessionId = persistedId?.takeIf { it.isNotBlank() } ?: newSessionId()
    }

    private fun closeSession(clearPersistedIdentity: Boolean = false) {
        val closedTrackId = sessionTrackId
        val closedSessionId = sessionId
        sessionTrackId = null
        sessionOpen = false
        activePlayTimeMs = 0L
        historyRowId = null
        sessionId = ""
        if (clearPersistedIdentity && !closedSessionId.isNullOrBlank()) {
            closedTrackId?.let { onSessionClosed?.invoke(it, closedSessionId) }
        }
    }

    /**
     * One tick of ACTUAL playing time. Accumulates only while genuinely playing, and
     * stops contributing entirely once the session has qualified, so a long listen can
     * never produce repeated inserts.
     */
    private suspend fun onTick(state: MediaControllerState) {
        if (!sessionOpen) return
        if (historyRowId != null) return          // already qualified — never insert again
        if (!state.isPlaying) return              // paused time does not count
        if (state.currentMediaItem?.mediaId != sessionTrackId) return // stale emission guard

        activePlayTimeMs += TICK_INTERVAL_MS
        if (activePlayTimeMs >= qualificationThresholdMs) {
            recordQualifiedSession(state)
        }
    }

    /**
     * Inserts the single row for this qualified session and retains its id.
     *
     * The session is re-checked after the suspending insert: if playback moved on while
     * we were writing, the id is intentionally dropped so completion can never be applied
     * to the wrong row. The already-written row then correctly remains completed=false —
     * exactly the "qualified, then skipped" outcome. This keeps the write path free of
     * extra synchronisation.
     */
    private suspend fun recordQualifiedSession(state: MediaControllerState) {
        val mediaItem = state.currentMediaItem ?: return
        val trackIdAtInsert = sessionTrackId ?: return
        if (mediaItem.mediaId != trackIdAtInsert) return

        val metadata = mediaItem.mediaMetadata
        val track = Track(
            id = mediaItem.mediaId,
            title = metadata.title?.toString() ?: "Unknown Title",
            artist = metadata.artist?.toString() ?: "Sonara Music",
            album = metadata.albumTitle?.toString() ?: "",
            artworkUrl = metadata.artworkUri?.toString(),
            durationMs = state.durationMs
        )

        // Provenance is resolved for THIS session's track, captured before the suspending
        // write so a queue change mid-insert cannot restamp it. `originFor` fails closed
        // to LEGACY_UNKNOWN when the track is no longer the current one.
        //
        // `track.artists` is necessarily empty here: Media3 metadata carries no structured
        // credits. That cannot erase anything, because the repository upserts through
        // TrackEntity.fromDomainPreservingCredits, which keeps the credits already stored
        // for this track id.
        val originAtInsert = originFor(trackIdAtInsert)
        val sessionIdAtInsert = sessionId

        val rowId = historyRepository.recordHistoryWithProvenance(
            track = track,
            completed = false,
            sourceSection = originAtInsert,
            playbackSessionId = sessionIdAtInsert
        )

        if (sessionOpen && (sessionTrackId == trackIdAtInsert) && (historyRowId == null)) {
            historyRowId = rowId
            Log.i(
                TAG,
                "History session qualified for: ${track.title} " +
                    "(row=$rowId, origin=$originAtInsert, session=$sessionIdAtInsert)"
            )
        } else {
            Log.d(TAG, "Qualified row $rowId written for a session that already moved on")
        }
    }
}
