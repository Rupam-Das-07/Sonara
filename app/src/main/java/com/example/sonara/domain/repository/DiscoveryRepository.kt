package com.example.sonara.domain.repository

import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track

/**
 * Domain interface for Home-screen discovery.
 *
 * Each call maps 1:1 to an independently fault-tolerant sonara-backend endpoint so
 * that a single failing module self-hides rather than taking down the whole Home
 * screen. All track lists are pre-filtered server- and client-side to guarantee
 * every entry is genuinely playable (videoId-backed) — no fabricated or
 * placeholder content ever crosses this boundary.
 *
 * Corrected Phase 5C Home IA consumers:
 *   Console Hero      — (session snapshot; not sourced here)
 *   Featured Artists  — [getFeaturedArtists]
 *   Curated Playlists — [getPlaylists] + [getPlaylistDetail]
 *   Quick Picks       — [getQuickPicks]
 *   Because You Listen To … — [getRelatedTracks]
 */
interface DiscoveryRepository {

    /** Editorial featured-artists roster. */
    suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>>

    /** Resolves catalog for a specific artist. */
    suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog>

    /** Curated playlist tiles (summaries only; tracks fetched on demand). */
    suspend fun getPlaylists(): Result<List<PlaylistSummary>>

    /** Resolves one curated playlist and its playable tracks (inline expand). */
    suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail>

    /**
     * Server-curated Quick Picks — guaranteed-playable tracks.
     * @param refresh when true, bypasses the server session cache and rotates
     *                the fallback query so the set changes.
     */
    suspend fun getQuickPicks(refresh: Boolean = false): Result<List<Track>>

    /**
     * Seed-based "Because You Listened To …" recommendations.
     * Provider unavailability surfaces as a failure (module self-hides) rather
     * than an empty success, so the section never masquerades as "no results".
     */
    suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>>
}
