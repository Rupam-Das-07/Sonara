package com.example.sonara.data.remote.backend

import android.util.Log
import com.example.sonara.core.error.SonaraException
import com.example.sonara.data.remote.mapper.ArtworkUrlUpgrader
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportMatchStatus
import com.example.sonara.domain.model.ImportMatchTier
import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP client for the independent Sonara backend (sonara-backend on port 3002).
 *
 * Uses HttpURLConnection + org.json — consistent with existing project conventions.
 * All secrets remain server-side. This client never contains provider credentials.
 *
 * The backend exposes a clean /api/v1/ namespace:
 *   /api/v1/search          — ranked search via SearchQualityEngine
 *   /api/v1/stream/resolve  — stream URL resolution via Python yt-dlp service
 *   /api/v1/stream/play     — controlled stream proxy (byte-range safe)
 *
 * Android MUST NEVER call the Python services (:5000/:5001) directly.
 *
 * Responsibilities:
 *  - URL construction and query encoding
 *  - Connection configuration and timeouts
 *  - HTTP status → SonaraException mapping
 *  - JSON response parsing
 *  - Mapping raw JSON into typed domain models
 */
open class SonaraBackendClient(
    private val baseUrl: String = SonaraBackendConfig.BASE_URL
) {

    companion object {
        private const val TAG = "SonaraBackendClient"
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Search (Mode 1: Official Songs & Mode 2: YouTube Videos)
    // -------------------------------------------------------------------------

    /**
     * Search official music catalog (Mode 1: Songs).
     *
     * The server runs SearchQualityEngine (4-stage deterministic ranking) and
     * returns pre-ranked CanonicalTrack DTOs. Android preserves that order.
     *
     * @param query Raw search string. Blank queries are rejected locally.
     * @return Ordered list of [Track] objects, or a [SonaraException] on failure.
     */
    open suspend fun searchSongs(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val primaryUrl = "$baseUrl/api/v1/search?q=$encodedQuery"
        val fallbackUrl = "$baseUrl/api/search?q=$encodedQuery"

        try {
            val json = try {
                get(primaryUrl, timeoutMs = SonaraBackendConfig.SEARCH_TIMEOUT_MS)
            } catch (e: SonaraException.NotFoundException) {
                Log.d(TAG, "Primary search endpoint 404, falling back to /api/search")
                get(fallbackUrl, timeoutMs = SonaraBackendConfig.SEARCH_TIMEOUT_MS)
            }

            val array = JSONArray(json)
            val tracks = mutableListOf<Track>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                mapTrack(item)?.let { tracks.add(it) }
            }
            Result.success(tracks)
        } catch (e: SonaraException) {
            Log.w(TAG, "searchSongs failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "searchSongs unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error during searchSongs", e))
        }
    }

    /**
     * Backward-compatible alias for Mode 1 (Songs).
     */
    open suspend fun search(query: String): Result<List<Track>> = searchSongs(query)

    /**
     * Search YouTube videos (Mode 2: Videos).
     *
     * Queries the backend yt-dlp service for arbitrary YouTube videos matching query.
     *
     * @param query Raw search string. Blank queries are rejected locally.
     * @param limit Maximum results to return (default 10).
     * @return List of normalized [Track] objects containing valid videoId.
     */
    open suspend fun searchVideos(query: String, limit: Int = 10): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val primaryUrl = "$baseUrl/api/v1/search/videos?q=$encodedQuery&limit=$limit"
        val fallbackUrl = "$baseUrl/search-youtube?q=$encodedQuery&limit=$limit"

        try {
            val json = try {
                get(primaryUrl, timeoutMs = SonaraBackendConfig.SEARCH_TIMEOUT_MS)
            } catch (e: SonaraException.NotFoundException) {
                Log.d(TAG, "Primary video search endpoint 404, falling back to /search-youtube")
                get(fallbackUrl, timeoutMs = SonaraBackendConfig.SEARCH_TIMEOUT_MS)
            }

            val tracks = mutableListOf<Track>()
            val trimmedJson = json.trim()
            if (trimmedJson.startsWith("{")) {
                val obj = JSONObject(trimmedJson)
                val items = obj.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    mapVideoTrack(item)?.let { tracks.add(it) }
                }
            } else if (trimmedJson.startsWith("[")) {
                val array = JSONArray(trimmedJson)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    mapVideoTrack(item)?.let { tracks.add(it) }
                }
            }
            Result.success(tracks)
        } catch (e: SonaraException) {
            Log.w(TAG, "searchVideos failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "searchVideos unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error during searchVideos", e))
        }
    }

    /**
     * Real-time search-as-you-type autocomplete / related-search suggestions.
     *
     * Queries the Sonara backend /api/v1/search/suggestions endpoint with upstream
     * search-engine fallback for network resilience.
     *
     * @param query Partial search string typed by user.
     * @return List of [SearchSuggestion] candidates. Never throws; returns empty on failure.
     */
    open suspend fun getSuggestions(query: String): Result<List<SearchSuggestion>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
        val primaryUrl = "$baseUrl/api/v1/search/suggestions?q=$encodedQuery"

        try {
            val json = get(primaryUrl, timeoutMs = 4000)
            val suggestions = mutableListOf<SearchSuggestion>()
            val trimmedJson = json.trim()
            if (trimmedJson.startsWith("{")) {
                val obj = JSONObject(trimmedJson)
                val items = obj.optJSONArray("suggestions") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val s = items.getString(i).trim()
                    if (s.isNotEmpty()) {
                        suggestions.add(SearchSuggestion(query = s))
                    }
                }
            } else if (trimmedJson.startsWith("[")) {
                val array = JSONArray(trimmedJson)
                val rawItems = if (array.length() > 1 && array.optJSONArray(1) != null) {
                    array.getJSONArray(1)
                } else array
                for (i in 0 until rawItems.length()) {
                    val s = rawItems.getString(i).trim()
                    if (s.isNotEmpty()) {
                        suggestions.add(SearchSuggestion(query = s))
                    }
                }
            }
            Result.success(suggestions)
        } catch (e: Exception) {
            Log.d(TAG, "Backend suggestion endpoint unreachable (${e.message}), attempting resilient direct suggest")
            try {
                val directUrl = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encodedQuery"
                val rawJson = get(directUrl, timeoutMs = 3000)
                val array = JSONArray(rawJson)
                val suggestions = mutableListOf<SearchSuggestion>()
                val items = array.optJSONArray(1) ?: JSONArray()
                for (i in 0 until items.length()) {
                    val s = items.getString(i).trim()
                    if (s.isNotEmpty()) {
                        suggestions.add(SearchSuggestion(query = s))
                    }
                }
                Result.success(suggestions)
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Direct suggest fallback failed: ${fallbackError.message}")
                Result.success(emptyList())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Discovery (Home screen)
    //
    // These feed the Home modules. Each call is independently fault-tolerant: a
    // failure returns Result.failure so only that module self-hides, rather than
    // taking down the whole screen. Every track array is mapped through the same
    // [mapTrack] path as search, so the "videoId required / every track playable"
    // rule is enforced identically across search, playlists, quick picks and
    // recommendations.
    // -------------------------------------------------------------------------

    /**
     * Editorial Featured Artists roster.
     * GET /api/v1/artists/featured -> { featured: [ { id, name, genre, imageUrl|null } ] }
     *
     * A null imageUrl is expected and valid — the client renders a monogram.
     * Entries missing a stable id or name are skipped defensively.
     */
    suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/artists/featured"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("featured") ?: JSONArray()
            val artists = mutableListOf<FeaturedArtist>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                artists.add(
                    FeaturedArtist(
                        id = id,
                        name = name,
                        genre = item.optString("genre"),
                        imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                        browseId = item.optString("browseId").takeIf { it.isNotBlank() }
                    )
                )
            }
            Result.success(artists)
        } catch (e: SonaraException) {
            Log.w(TAG, "getFeaturedArtists failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getFeaturedArtists unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching featured artists", e))
        }
    }

    /**
     * Resolves deep catalog for a specific artist.
     * GET /api/v1/artists/{encodedBrowseId}?name={encodedName} -> { tracks: [ TrackDTO ], playlists: [ PlaylistSummaryDTO ] }
     * Falls back gracefully to search query if deep catalog is empty or unavailable.
     */
    suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = withContext(Dispatchers.IO) {
        val queryName = artistName.takeIf { it.isNotBlank() } ?: artistId
        if (queryName.isBlank()) {
            return@withContext Result.success(ArtistCatalog())
        }
        val encodedId = URLEncoder.encode(artistId.takeIf { it.isNotBlank() } ?: queryName, "UTF-8")
        val encodedName = URLEncoder.encode(queryName, "UTF-8")
        val url = "$baseUrl/api/v1/artists/$encodedId?name=$encodedName"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            val root = JSONObject(json)
            val trackArray = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = parseTrackArray(trackArray)

            val playlistArray = root.optJSONArray("playlists") ?: JSONArray()
            val playlists = mutableListOf<PlaylistSummary>()
            for (i in 0 until playlistArray.length()) {
                val item = playlistArray.getJSONObject(i)
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                playlists.add(
                    PlaylistSummary(
                        id = id,
                        name = item.optString("name").takeIf { it.isNotBlank() } ?: "Untitled",
                        description = item.optString("description"),
                        coverImage = item.optString("coverImage").takeIf { it.isNotBlank() },
                        size = item.optInt("size", 0)
                    )
                )
            }

            if (tracks.isNotEmpty()) {
                Result.success(ArtistCatalog(tracks = tracks, playlists = playlists))
            } else {
                search(queryName).map { ArtistCatalog(tracks = it, playlists = playlists) }
            }
        } catch (e: Exception) {
            search(queryName).map { ArtistCatalog(tracks = it, playlists = emptyList()) }
        }
    }

    /**
     * Curated playlist catalog (tiles only; tracks fetched on demand).
     * GET /api/v1/playlists -> { playlists: [ { id, name, description, coverImage|null, size } ] }
     */
    suspend fun getPlaylists(): Result<List<PlaylistSummary>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/playlists"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("playlists") ?: JSONArray()
            val playlists = mutableListOf<PlaylistSummary>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                playlists.add(
                    PlaylistSummary(
                        id = id,
                        name = item.optString("name").takeIf { it.isNotBlank() } ?: "Untitled",
                        description = item.optString("description"),
                        coverImage = item.optString("coverImage").takeIf { it.isNotBlank() },
                        size = item.optInt("size", 0)
                    )
                )
            }
            Result.success(playlists)
        } catch (e: SonaraException) {
            Log.w(TAG, "getPlaylists failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylists unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching playlists", e))
        }
    }

    /**
     * Resolves one curated playlist and its playable tracks.
     * GET /api/v1/playlists/{id} -> { id, name, description, coverImage|null, tracks: [ TrackDTO ] }
     *
     * Uses READ_TIMEOUT_MS because generation may run upstream provider calls.
     */
    suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = withContext(Dispatchers.IO) {
        if (playlistId.isBlank()) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot resolve playlist for blank id")
            )
        }
        val encodedId = URLEncoder.encode(playlistId, "UTF-8")
        val url = "$baseUrl/api/v1/playlists/$encodedId"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            val obj = JSONObject(json)
            Result.success(
                PlaylistDetail(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: playlistId,
                    name = obj.optString("name"),
                    description = obj.optString("description"),
                    coverImage = obj.optString("coverImage").takeIf { it.isNotBlank() },
                    tracks = parseTrackArray(obj.optJSONArray("tracks") ?: JSONArray())
                )
            )
        } catch (e: SonaraException) {
            Log.w(TAG, "getPlaylistDetail failed for $playlistId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylistDetail unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching playlist", e))
        }
    }

    /**
     * Server-curated Quick Picks — genuinely playable tracks.
     * GET /api/v1/quickpicks[?refresh=1] -> { quickPicks: [ TrackDTO ] }
     *
     * @param refresh When true, bypasses the server session cache and rotates the
     *                fallback query so the set changes.
     */
    suspend fun getQuickPicks(refresh: Boolean = false): Result<List<Track>> = withContext(Dispatchers.IO) {
        val url = if (refresh) "$baseUrl/api/v1/quickpicks?refresh=1" else "$baseUrl/api/v1/quickpicks"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("quickPicks") ?: JSONArray()
            Result.success(parseTrackArray(array))
        } catch (e: SonaraException) {
            Log.w(TAG, "getQuickPicks failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getQuickPicks unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching quick picks", e))
        }
    }

    /**
     * Seed-based "Because You Listened To …" recommendations.
     * GET /api/v1/recommendations/related/{videoId} -> { success, tracks: [ TrackDTO ], ... }
     *
     * The route returns HTTP 502 when the provider is unavailable, which [get]
     * maps to a NetworkException — so an unavailable provider fails (module
     * self-hides) rather than looking like an empty result.
     */
    suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (seedVideoId.isBlank()) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot fetch recommendations for blank videoId")
            )
        }
        val encodedId = URLEncoder.encode(seedVideoId, "UTF-8")
        val url = "$baseUrl/api/v1/recommendations/related/$encodedId"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("tracks") ?: JSONArray()
            Result.success(parseTrackArray(array))
        } catch (e: SonaraException) {
            Log.w(TAG, "getRelatedTracks failed for $seedVideoId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getRelatedTracks unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching recommendations", e))
        }
    }

    /**
     * Maps a JSON array of TrackDTOs into domain [Track]s, dropping any entry
     * whose videoId is blank (unplayable). Same rule the search path applies.
     */
    private fun parseTrackArray(array: JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until array.length()) {
            mapTrack(array.getJSONObject(i))?.let { tracks.add(it) }
        }
        return tracks
    }

    // -------------------------------------------------------------------------
    // Stream resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a YouTube videoId to a playable server-proxied stream URL.
     *
     * Flow: Android → Node /api/v1/stream/resolve → Python/yt-dlp → /api/v1/stream/play URL
     * The returned URL is for /api/v1/stream/play on the same backend server,
     * which forwards bytes from Googlevideo with the correct headers.
     *
     * Stream URLs are EPHEMERAL — never persist them to Room or DataStore.
     *
     * @param videoId YouTube video ID (e.g. "dQw4w9WgXcQ").
     * @return [StreamInfo] with an absolute proxy URL, or a [SonaraException] on failure.
     */
    open suspend fun getStreamUrl(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        title: String = "",
        artist: String = "",
        durationSeconds: Int = 0
    ): Result<StreamInfo> = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        if (cleanVideoId.isBlank()) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot resolve stream for blank videoId")
            )
        }

        // The Python service expects a full YouTube watch URL
        val ytUrl = "https://www.youtube.com/watch?v=$cleanVideoId"
        val encodedYtUrl = URLEncoder.encode(ytUrl, "UTF-8")
        val qualityParam = when (quality) {
            AudioQuality.HIGH, AudioQuality.VERY_HIGH -> "HIGH"
            else -> "STANDARD"
        }

        val params = StringBuilder("url=$encodedYtUrl&video_id=${URLEncoder.encode(cleanVideoId, "UTF-8")}&quality=$qualityParam")
        if (title.isNotBlank()) {
            params.append("&title=${URLEncoder.encode(title, "UTF-8")}")
        }
        if (artist.isNotBlank()) {
            params.append("&artist=${URLEncoder.encode(artist, "UTF-8")}")
        }
        if (durationSeconds > 0) {
            params.append("&duration=$durationSeconds")
        }

        val primaryUrl = "$baseUrl/api/v1/stream/resolve?$params"
        val fallbackUrl = "$baseUrl/get-youtube-audio?url=$encodedYtUrl"

        try {
            val json = try {
                get(primaryUrl, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            } catch (e: SonaraException.NotFoundException) {
                Log.d(TAG, "Primary stream resolve 404, falling back to /get-youtube-audio")
                get(fallbackUrl, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            }
            val obj = JSONObject(json)

            val rawAudioUrl = obj.optString("audio_url", "")
            if (rawAudioUrl.isBlank()) {
                return@withContext Result.failure(
                    SonaraException.ProviderUnavailableException("Backend returned empty audio_url for $cleanVideoId")
                )
            }

            // audio_url may be a relative path ("/api/v1/stream/play?...") or direct URL
            val absoluteStreamUrl = if (rawAudioUrl.startsWith("http")) {
                rawAudioUrl
            } else {
                "$baseUrl$rawAudioUrl"
            }

            val expiresAt = obj.optLong("expiresAt", 0L)
            val format = obj.optString("format", "audio/webm")
            val codec = obj.optString("codec", "opus")
            val bitrate = obj.optInt("bitrate", 160)
            val qualityTier = obj.optString("qualityTier", "STANDARD")
            val provider = obj.optString("provider", "youtube")

            Result.success(
                StreamInfo(
                    trackId = cleanVideoId,
                    streamUrl = absoluteStreamUrl,
                    expiresAt = expiresAt,
                    format = format,
                    codec = codec,
                    bitrateKbps = bitrate,
                    qualityTier = qualityTier,
                    provider = provider
                )
            )
        } catch (e: SonaraException) {
            Log.w(TAG, "Stream resolution failed for $cleanVideoId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Stream resolution unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error resolving stream", e))
        }
    }

    // -------------------------------------------------------------------------
    // Internal HTTP primitives
    // -------------------------------------------------------------------------

    /**
     * Executes a GET request and returns the response body as a String.
     * Maps HTTP error statuses into [SonaraException] subtypes.
     *
     * @throws SonaraException on HTTP or I/O failures.
     */
    @Throws(SonaraException::class, IOException::class)
    private fun get(url: String, timeoutMs: Int = SonaraBackendConfig.CONNECT_TIMEOUT_MS): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = SonaraBackendConfig.CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SonaraAndroid/1.0")
            }

            val code = connection.responseCode
            when {
                code in 200..299 -> {
                    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                code == 400 -> throw SonaraException.ParsingException("Bad request to backend (400)")
                code == 404 -> throw SonaraException.NotFoundException("Resource not found on backend (404)")
                code == 429 -> throw SonaraException.ProviderUnavailableException("Backend rate limit hit (429), please wait")
                code == 422 -> throw SonaraException.ProviderUnavailableException("Track unavailable or no audio stream (422)")
                code in 500..503 -> throw SonaraException.NetworkException("Backend server error ($code)")
                else -> throw SonaraException.NetworkException("Unexpected HTTP status: $code")
            }
        } catch (e: SocketTimeoutException) {
            throw SonaraException.NetworkException("Request timed out: $url", e)
        } catch (e: SonaraException) {
            throw e  // re-throw typed exceptions unchanged
        } catch (e: IOException) {
            throw SonaraException.NetworkException("Network I/O error: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Executes a synchronous HTTP POST and returns the raw response body string.
     *
     * @throws SonaraException on HTTP or I/O failures.
     */
    @Throws(SonaraException::class, IOException::class)
    protected open fun post(url: String, jsonBody: String, timeoutMs: Int = SonaraBackendConfig.CONNECT_TIMEOUT_MS): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = SonaraBackendConfig.CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SonaraAndroid/1.0")
            }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }

            val code = connection.responseCode
            when {
                code in 200..299 -> {
                    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                code == 400 -> throw SonaraException.ParsingException("Bad request to backend (400)")
                code == 404 -> throw SonaraException.NotFoundException("Resource not found on backend (404)")
                code == 413 -> throw SonaraException.ParsingException("Import payload too large (413)")
                code == 429 -> throw SonaraException.ProviderUnavailableException("Backend rate limit hit (429), please wait")
                code in 500..503 -> throw SonaraException.NetworkException("Backend server error ($code)")
                else -> throw SonaraException.NetworkException("Unexpected HTTP status: $code")
            }
        } catch (e: SocketTimeoutException) {
            throw SonaraException.NetworkException("Request timed out: $url", e)
        } catch (e: SonaraException) {
            throw e
        } catch (e: IOException) {
            throw SonaraException.NetworkException("Network I/O error: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Matches a single chunk of imported tracks via POST /api/v1/import/match.
     * Raw Spotify URIs are strictly stripped and never transmitted.
     */
    open suspend fun matchImportChunk(
        importId: String,
        chunkIndex: Int,
        tracks: List<ImportedTrack>
    ): Result<List<ImportMatchItem>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/import/match"
        try {
            val jsonBody = JSONObject().apply {
                put("importId", importId)
                put("chunkIndex", chunkIndex)
                val tracksArray = JSONArray()
                tracks.forEach { t ->
                    tracksArray.put(JSONObject().apply {
                        put("sourceOrder", t.sourceOrder)
                        put("title", t.title)
                        put("artist", t.artist)
                        if (!t.album.isNullOrBlank()) put("album", t.album)
                        if (t.durationMs != null && t.durationMs > 0) put("durationMs", t.durationMs)
                        if (!t.isrc.isNullOrBlank()) put("isrc", t.isrc)
                        put("isLocalFile", t.isLocalFile)
                        put("isEpisode", t.isEpisode)
                        // Explicitly omitting spotifyUri!
                    })
                }
                put("tracks", tracksArray)
            }.toString()

            val responseText = post(url, jsonBody, timeoutMs = 30000)
            val responseObj = JSONObject(responseText)
            val resultsArray = responseObj.optJSONArray("results") ?: JSONArray()
            val items = mutableListOf<ImportMatchItem>()

            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val sourceOrder = item.optInt("sourceOrder", i)
                val statusStr = item.optString("status")
                val tierStr = item.optString("tier")
                val confidence = item.optDouble("confidence", 0.0)
                val reason = item.optString("reason").takeIf { it.isNotBlank() && it != "null" }

                val resolvedTrack = item.optJSONObject("resolvedTrack")?.let { mapTrack(it) }

                val alternativesArray = item.optJSONArray("alternatives")
                val alternatives = mutableListOf<Track>()
                if (alternativesArray != null) {
                    for (j in 0 until alternativesArray.length()) {
                        mapTrack(alternativesArray.getJSONObject(j))?.let { alternatives.add(it) }
                    }
                }

                items.add(
                    ImportMatchItem(
                        sourceOrder = sourceOrder,
                        status = ImportMatchStatus.fromString(statusStr),
                        tier = ImportMatchTier.fromString(tierStr),
                        confidence = confidence,
                        resolvedTrack = resolvedTrack,
                        alternatives = alternatives,
                        reason = reason
                    )
                )
            }

            Result.success(items)
        } catch (e: SonaraException) {
            Log.w(TAG, "matchImportChunk failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "matchImportChunk unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error during matchImportChunk", e))
        }
    }

    // -------------------------------------------------------------------------
    // JSON → Domain mapping
    // -------------------------------------------------------------------------

    /**
     * Parses a single CanonicalTrack JSON object and delegates to [TrackMapper].
     *
     * Field precedence for artwork:
     *  - `artwork.url` (post-processed by server's ArtworkResolver)
     *  - last element of `thumbnails` array
     *  - legacy `albumArt` / `thumbnailUrl` flat fields
     */
    private fun mapTrack(json: JSONObject): Track? {
        val videoId = (json.optString("id") ?: "").ifBlank {
            json.optString("videoId") ?: ""
        }

        val rawArtworkUrl = resolveArtworkUrl(json)

        return TrackMapper.map(
            videoId = videoId.ifBlank { null },
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            durationSec = json.optInt("duration"),
            rawArtworkUrl = rawArtworkUrl
        )
    }

    /**
     * Extracts the best available artwork URL from the raw JSON object.
     * Prefers `artworkUrl` (from sonara-backend DTO), then `artwork.url`, then last thumbnail.
     */
    private fun resolveArtworkUrl(json: JSONObject): String? {
        // 1. Direct artworkUrl field (TrackDTO from sonara-backend)
        val directArtworkUrl = json.optString("artworkUrl")?.takeIf { it.isNotBlank() }
        if (directArtworkUrl != null) return directArtworkUrl

        // 2. artwork.url — (post-processed by server's ArtworkResolver)
        val artworkObj = json.optJSONObject("artwork")
        val artworkUrl = artworkObj?.optString("url")?.takeIf { it.isNotBlank() }
        if (artworkUrl != null) return artworkUrl

        // 3. thumbnails array — last element tends to be the largest
        val thumbnails = json.optJSONArray("thumbnails")
        if (thumbnails != null && thumbnails.length() > 0) {
            val last = thumbnails.getJSONObject(thumbnails.length() - 1)
            val thumbUrl = last.optString("url")?.takeIf { it.isNotBlank() }
            if (thumbUrl != null) return thumbUrl
        }

        // 4. Legacy flat fields
        return json.optString("albumArt")?.takeIf { it.isNotBlank() }
            ?: json.optString("thumbnailUrl")?.takeIf { it.isNotBlank() }
    }

    /**
     * Parses a YouTube Video search result JSON and normalizes it to [Track].
     */
    private fun mapVideoTrack(json: JSONObject): Track? {
        val rawId = (json.optString("videoId") ?: "").ifBlank {
            json.optString("id") ?: ""
        }
        val videoId = rawId.removePrefix("yt-").trim().ifBlank { return null }

        val title = json.optString("title").ifBlank { "Unknown Title" }
        val artist = json.optString("artist").ifBlank {
            json.optString("channelTitle").ifBlank {
                json.optJSONObject("uploader")?.optString("name") ?: "YouTube"
            }
        }
        val album = json.optString("album").ifBlank { "YouTube" }
        val durationSec = json.optInt("duration", 0)
        val artworkUrl = resolveArtworkUrl(json) ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

        return TrackMapper.map(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            rawArtworkUrl = artworkUrl
        )
    }
}
