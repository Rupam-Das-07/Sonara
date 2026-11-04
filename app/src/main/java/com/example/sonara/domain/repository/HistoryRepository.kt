package com.example.sonara.domain.repository

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain boundary for playback history logging and reactive retrieval.
 */
interface HistoryRepository {
    fun getRecentHistory(limit: Int = 50): Flow<List<HistoryItem>>

    fun getRecentHistoryBySource(
        sourceSection: PlaybackOrigin,
        limit: Int = 50
    ): Flow<List<HistoryItem>> = getRecentHistory(limit).map { items ->
        items.filter { it.sourceSection == sourceSection }
    }

    /**
     * Newest-first history excluding rows stamped after [newestAllowed], filtered BEFORE [limit]
     * is applied, so rows recorded while the device clock was ahead cannot crowd genuine listens
     * out of a bounded sample (I-2).
     *
     * The default body exists only to keep fakes source-compatible: because it filters AFTER the
     * limit it does not provide that guarantee, exactly like [getRecentHistoryBySource]'s default.
     * Real implementations must override it with a query that filters first.
     */
    fun getRecentHistoryUpTo(newestAllowed: Long, limit: Int): Flow<List<HistoryItem>> =
        getRecentHistory(limit).map { items -> items.filter { it.listenedAt <= newestAllowed } }

    /**
     * Newest-first history restricted to [origins] and excluding rows stamped after
     * [newestAllowed], with BOTH restrictions applied BEFORE [limit] (I-5).
     *
     * [getRecentHistoryUpTo] bounds the sample by time only, while provenance eligibility is
     * enforced later, in memory. A device that autoplays radio heavily therefore writes enough
     * `RADIO_AUTOPLAY` rows into the newest slots to fill every sample slot, and eligible
     * Songs-search listening that is genuinely older is crowded out of the sample — and is
     * therefore never seen by the policy that would have accepted it. This method is the
     * provenance-aware sibling that keeps a bounded sample a sample of USABLE rows.
     *
     * [origins] is supplied by the CALLER and is expected to be the authoritative policy
     * (`ArtistAffinityCalculator.ELIGIBLE_ORIGINS`); this layer deliberately does not define or
     * duplicate the eligibility list. An empty [origins] yields no rows rather than every row,
     * so a misconfigured caller fails closed instead of silently restoring the defect.
     *
     * The default body exists only to keep fakes source-compatible: because it filters AFTER the
     * limit it does NOT provide that guarantee, exactly like the defaults above. Real
     * implementations must override it with a query that filters before limiting.
     */
    fun getRecentHistoryUpToForOrigins(
        newestAllowed: Long,
        origins: Collection<PlaybackOrigin>,
        limit: Int = 50
    ): Flow<List<HistoryItem>> = getRecentHistoryUpTo(newestAllowed, limit)
        .map { items -> items.filter { it.sourceSection in origins } }

    /**
     * Records a playback-history row and returns its row id (the session identity).
     *
     * The id lets the authoritative history writer later mark this exact row completed
     * via [markHistoryCompleted] instead of inserting a second row for the same listen.
     */
    suspend fun recordHistory(track: Track, completed: Boolean = false): Long

    /**
     * Records a history row with its initiating surface and playback-session identity.
     *
     * This is intentionally mandatory: implementations must explicitly persist or otherwise
     * handle both provenance values instead of silently routing the call through the legacy API.
     * The default arguments keep existing call sites that only provide the track source-compatible.
     */
    suspend fun recordHistoryWithProvenance(
        track: Track,
        completed: Boolean = false,
        sourceSection: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN,
        playbackSessionId: String = ""
    ): Long

    /** Marks a previously recorded history row as a completed listen. */
    suspend fun markHistoryCompleted(historyId: Long)

    suspend fun clearHistory()
}
