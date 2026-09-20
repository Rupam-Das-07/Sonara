package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.DiscoveryRepository

/**
 * Thin pass-through to [SonaraBackendClient]'s discovery endpoints.
 *
 * Mirrors [SearchRepositoryImpl]'s construction pattern (self-constructs a default
 * client) so Home discovery stays fully decoupled from Search. All fault tolerance,
 * HTTP→exception mapping, and videoId-required track filtering live in the client;
 * this layer only adapts the domain interface. No secrets, no provider calls.
 */
class DiscoveryRepositoryImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient()
) : DiscoveryRepository {

    override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> =
        backendClient.getFeaturedArtists()

    override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
        backendClient.getArtistCatalog(artistId, artistName)

    override suspend fun getPlaylists(): Result<List<PlaylistSummary>> =
        backendClient.getPlaylists()

    override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
        backendClient.getPlaylistDetail(playlistId)

    override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> =
        backendClient.getQuickPicks(refresh)

    override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> =
        backendClient.getRelatedTracks(seedVideoId)
}
