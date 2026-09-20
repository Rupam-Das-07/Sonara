package com.example.sonara.data.remote.providers.lrclib

import android.util.Log
import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.lyrics.LrcParser
import com.example.sonara.domain.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Remote provider fetching synced and plain lyrics from LRCLIB (https://lrclib.net).
 * Implements multi-strategy candidate pooling and precision scoring across slow, medium, and fast mix tracks (Audit 02 §3.1).
 */
class LrclibLyricsProvider {

    companion object {
        private const val TAG = "LrclibLyricsProvider"
        private const val BASE_URL = "https://lrclib.net/api"
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics> = withContext(Dispatchers.IO) {
        val cleanTitleStr = cleanTitle(title)
        val cleanArtistStr = cleanArtist(artist)

        if (cleanTitleStr.isBlank()) {
            return@withContext Result.success(Lyrics.Unavailable("No title provided"))
        }

        try {
            val targetDurationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
            val candidates = mutableListOf<JSONObject>()
            val seenSignatures = mutableSetOf<String>()

            fun addCandidate(item: JSONObject) {
                val id = item.optInt("id", 0).toString()
                val trackName = item.optString("trackName", "")
                val artistName = item.optString("artistName", "")
                val dur = item.optDouble("duration", 0.0).toString()
                val signature = if (id != "0") id else "$trackName|$artistName|$dur"
                if (seenSignatures.add(signature)) {
                    candidates.add(item)
                }
            }

            // Strategy 1: Exact match with /get
            val exactObj = fetchJson("$BASE_URL/get?track_name=" + URLEncoder.encode(cleanTitleStr, "UTF-8") +
                    (if (cleanArtistStr.isNotBlank()) "&artist_name=" + URLEncoder.encode(cleanArtistStr, "UTF-8") else "") +
                    (if (durationMs > 0) "&duration=" + (durationMs / 1000) else ""))
            if (exactObj != null) {
                addCandidate(exactObj)
            }

            // Strategy 2: Search by track_name
            val trackNameResults = fetchJsonArray("$BASE_URL/search?track_name=" + URLEncoder.encode(cleanTitleStr, "UTF-8"))
            for (i in 0 until trackNameResults.length()) {
                addCandidate(trackNameResults.getJSONObject(i))
            }

            // Strategy 3: General query with cleanTitle and cleanArtist
            if (cleanArtistStr.isNotBlank()) {
                val combinedResults = fetchJsonArray("$BASE_URL/search?q=" + URLEncoder.encode("$cleanTitleStr $cleanArtistStr", "UTF-8"))
                for (i in 0 until combinedResults.length()) {
                    addCandidate(combinedResults.getJSONObject(i))
                }
            }

            // Strategy 4: Fallback search with title only
            val titleResults = fetchJsonArray("$BASE_URL/search?q=" + URLEncoder.encode(cleanTitleStr, "UTF-8"))
            for (i in 0 until titleResults.length()) {
                addCandidate(titleResults.getJSONObject(i))
            }

            // Strategy 5: If title has repetition / sub-parts (e.g. "Oo Antava Mawa..Oo Oo Antava"), try base prefix query
            val basePrefix = cleanTitleStr.split(Regex("\\.\\.+|\\s+-\\s+|/")).firstOrNull()?.trim() ?: ""
            if (basePrefix.isNotBlank() && basePrefix != cleanTitleStr && basePrefix.length >= 3) {
                val prefixResults = fetchJsonArray("$BASE_URL/search?q=" + URLEncoder.encode(basePrefix, "UTF-8"))
                for (i in 0 until prefixResults.length()) {
                    addCandidate(prefixResults.getJSONObject(i))
                }
            }

            // Filter synced and plain candidates
            val syncedCandidates = candidates.filter { it.optString("syncedLyrics", "").isNotBlank() }
            val plainCandidates = candidates.filter { it.optString("plainLyrics", "").isNotBlank() }

            if (syncedCandidates.isNotEmpty()) {
                val bestSynced = syncedCandidates.minByOrNull { item ->
                    scoreCandidate(item, targetDurationSec, cleanArtistStr)
                } ?: syncedCandidates.first()
                return@withContext Result.success(parseLyricsJson(bestSynced, cleanTitleStr, cleanArtistStr))
            }

            if (plainCandidates.isNotEmpty()) {
                val bestPlain = plainCandidates.first()
                return@withContext Result.success(parseLyricsJson(bestPlain, cleanTitleStr, cleanArtistStr))
            }

            Result.success(Lyrics.Unavailable("No lyrics found for this track"))
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Lyrics request timeout: ${e.message}")
            Result.failure(SonaraException.NetworkException("Lyrics request timed out", e))
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Lyrics network error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Network error fetching lyrics", e))
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics error: ${e.message}")
            Result.failure(SonaraException.ParsingException("Failed to parse lyrics response", e))
        }
    }

    private fun scoreCandidate(item: JSONObject, targetDurationSec: Double, targetArtist: String): Double {
        val synced = item.optString("syncedLyrics", "").trim()
        if (synced.isBlank()) return 999999.0

        val dur = item.optDouble("duration", 0.0)
        val durDiff = if (targetDurationSec > 0.0 && dur > 0.0) {
            kotlin.math.abs(dur - targetDurationSec)
        } else {
            50.0
        }
        var score = durDiff * 3.0

        // Artist proximity bonus
        if (targetArtist.isNotBlank()) {
            val candidateArtist = item.optString("artistName", "").lowercase()
            val targetTokens = targetArtist.split(Regex("[,&;/]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE))
                .map { it.trim().lowercase() }
                .filter { it.length > 2 }
            if (targetTokens.any { candidateArtist.contains(it) }) {
                score -= 15.0
            }
        }

        // Dialogue / Video Intro Delay Check
        val firstTs = extractFirstTimestampSec(synced)
        if (firstTs > 30.0) {
            score += 25.0
        } else if (firstTs > 20.0 && durDiff > 4.0) {
            score += 15.0
        }

        // Completeness bonus (rewards well-formatted full tracks over truncated 5-line submissions)
        val lineCount = synced.lineSequence().count { it.isNotBlank() }
        if (lineCount >= 30) {
            score -= 5.0
        } else if (lineCount < 10) {
            score += 20.0
        }

        return score
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        return try {
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "SonaraAndroid/1.0")
            }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJsonArray(urlStr: String): JSONArray {
        return try {
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "SonaraAndroid/1.0")
            }
            if (connection.responseCode !in 200..299) return JSONArray()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun extractFirstTimestampSec(syncedLyrics: String): Double {
        val line = syncedLyrics.lineSequence().firstOrNull { it.trim().isNotBlank() } ?: return 0.0
        val match = Regex("\\[(\\d+):(\\d+\\.?\\d*)\\]").find(line) ?: return 0.0
        val min = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val sec = match.groupValues[2].toDoubleOrNull() ?: 0.0
        return (min * 60) + sec
    }

    private fun parseLyricsJson(json: JSONObject, title: String = "", artist: String = ""): Lyrics {
        val isInstrumental = json.optBoolean("instrumental", false)
        if (isInstrumental) return Lyrics.Instrumental

        val syncedRaw = json.optString("syncedLyrics", "").trim()
        val plainRaw = json.optString("plainLyrics", "").trim()

        if (syncedRaw.isNotBlank()) {
            val lines = LrcParser.parse(syncedRaw)
            if (lines.isNotEmpty()) {
                val parsed = Lyrics.SyncedLyrics(lines)
                return com.example.sonara.domain.lyrics.LyricsOffsetCalibrator.calibrate(parsed, title, artist)
            }
        }

        if (plainRaw.isNotBlank()) {
            return Lyrics.PlainLyrics(plainRaw)
        }

        return Lyrics.Unavailable("No lyrics available")
    }

    private fun cleanTitle(title: String): String {
        return try {
            title
                .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "") // Emojis
                .replace(Regex("\\s*[\\(\\[][^\\)\\]]*(?:official|video|audio|lyric|hd|hq|4k|full|song|mv|music\\s*video|explicit|from\\s*[\"'].*[\"']|from\\s+[^\"'\\)\\]]+|remix|mix|mashup|version)[\\)\\]]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*[|\\-\u2013\u2014]\\s*.*?(?:official|music|video|records|vevo|channel|lyrics?|remix|mix)\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\(\\s*\\b(?:feat\\.?|ft\\.?)\\b.*?\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\b(?:feat\\.?|ft\\.?)\\b.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\|.*$"), "")
                .replace(Regex("\\s*[|\\-\u2013\u2014]\\s*$"), "")
                .replace(Regex("\\s*[\\(\\[][^\\)\\]]+[\\)\\]]\\s*$"), "")
                .replace(Regex("\\.\\.+.*$"), "") // Ellipses / repetitions
                .replace(Regex("\\($"), "")
                .trim()
        } catch (_: Exception) {
            title.trim()
        }
    }

    private fun cleanArtist(artist: String): String {
        return try {
            val firstArtist = artist.split(Regex("[,&]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE)).firstOrNull() ?: artist
            firstArtist.replace(Regex("\\s+(official|vevo|topic)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } catch (_: Exception) {
            artist.trim()
        }
    }
}
