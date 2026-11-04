package com.example.sonara.data.remote.backend

import android.util.Log
import com.example.sonara.BuildConfig
import com.example.sonara.core.error.SonaraException
import com.example.sonara.data.remote.mapper.ArtworkUrlUpgrader
import com.example.sonara.domain.discovery.FeaturedArtistImage
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.ArtistCredit
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
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.HighResAudioRepresentation
import com.example.sonara.domain.model.video.HighResByteRange
import com.example.sonara.domain.model.video.HighResVideoDiscovery
import com.example.sonara.domain.model.video.HighResVideoRepresentation
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.isValidYouTubeVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Applies the trusted-image contract at the editorial roster response boundary.
 * Invalid or absent artwork intentionally becomes null so the Home tile keeps its monogram.
 */
internal fun sanitizeFeaturedArtistImageUrl(rawUrl: String?): String? =
    FeaturedArtistImage.trustedHttpsUrl(rawUrl)


/**
 * Values supported by the video-status response's flat JSON object.
 * Keeping the value type prevents a numeric or boolean videoId from being coerced into a string.
 */
private sealed interface SimpleJsonValue {
    data class StringValue(val value: String) : SimpleJsonValue
    data class BooleanValue(val value: Boolean) : SimpleJsonValue
    data class NumberValue(val raw: String) : SimpleJsonValue
    data object NullValue : SimpleJsonValue
}

private fun SimpleJsonValue?.asStringOrNull(): String? =
    (this as? SimpleJsonValue.StringValue)?.value

/**
 * Strict, pure-Kotlin parser for the flat JSON object returned by video status.
 * It rejects nested objects/arrays, duplicate keys, trailing commas, malformed literals/numbers,
 * invalid escapes, and unsupported value syntax. It avoids Android SDK `org.json` stubs during
 * host JVM unit tests.
 */
private fun parseSimpleJsonObject(json: String): Map<String, SimpleJsonValue> {
    val trimmed = json.trim()
    if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') {
        throw SonaraException.ParsingException("Malformed JSON: must be enclosed in braces")
    }

    val content = trimmed.substring(1, trimmed.length - 1)
    val result = linkedMapOf<String, SimpleJsonValue>()
    var i = 0

    fun fail(message: String): Nothing = throw SonaraException.ParsingException(message)

    fun skipWhitespace() {
        while (i < content.length && content[i].isWhitespace()) i++
    }

    fun parseString(): String {
        if (i >= content.length || content[i] != '"') fail("Expected string at position $i")
        i++
        val value = StringBuilder()
        while (i < content.length) {
            when (val character = content[i++]) {
                '"' -> return value.toString()
                '\\' -> {
                    if (i >= content.length) fail("Unterminated escape sequence")
                    when (val escape = content[i++]) {
                        '"', '\\', '/' -> value.append(escape)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (i + 4 > content.length) fail("Invalid unicode escape")
                            val hex = content.substring(i, i + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                fail("Invalid unicode escape")
                            }
                            value.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("Invalid escape sequence")
                    }
                }
                else -> {
                    if (character.code < 0x20) fail("Unescaped control character in string")
                    value.append(character)
                }
            }
        }
        fail("Unterminated string")
    }

    fun isValueDelimiter(index: Int): Boolean =
        index >= content.length || content[index].isWhitespace() || content[index] == ','

    fun parseLiteral(literal: String, value: SimpleJsonValue): SimpleJsonValue {
        if (!content.startsWith(literal, i)) fail("Invalid literal at position $i")
        i += literal.length
        if (!isValueDelimiter(i)) fail("Invalid literal boundary at position $i")
        return value
    }

    fun parseNumber(): SimpleJsonValue.NumberValue {
        val start = i
        while (!isValueDelimiter(i)) {
            if (content[i] == '{' || content[i] == '[' || content[i] == '}' || content[i] == ']') {
                fail("Nested JSON values are not supported")
            }
            i++
        }
        val raw = content.substring(start, i)
        val numberPattern = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")
        if (!numberPattern.matches(raw)) fail("Invalid number at position $start")
        return SimpleJsonValue.NumberValue(raw)
    }

    skipWhitespace()
    if (i >= content.length) return result

    while (true) {
        skipWhitespace()
        if (i >= content.length) fail("Trailing comma or missing member")
        val key = parseString()
        if (result.containsKey(key)) fail("Duplicate object key: $key")
        skipWhitespace()
        if (i >= content.length || content[i] != ':') fail("Expected ':' after key at position $i")
        i++
        skipWhitespace()
        if (i >= content.length) fail("Expected value after ':'")

        val value = when {
            content[i] == '"' -> SimpleJsonValue.StringValue(parseString())
            content[i] == '{' || content[i] == '[' -> fail("Nested JSON values are not supported")
            content.startsWith("null", i) -> parseLiteral("null", SimpleJsonValue.NullValue)
            content.startsWith("true", i) -> parseLiteral("true", SimpleJsonValue.BooleanValue(true))
            content.startsWith("false", i) -> parseLiteral("false", SimpleJsonValue.BooleanValue(false))
            else -> parseNumber()
        }
        result[key] = value

        skipWhitespace()
        if (i >= content.length) return result
        if (content[i] != ',') fail("Expected ',' or end of object at position $i")
        i++
        skipWhitespace()
        if (i >= content.length) fail("Trailing comma")
    }
}

/**
 * Parses the backend video-status response and verifies that it describes the
 * video requested by the caller.
 */
internal fun parseVideoStatus(json: String, requestedVideoId: String): VideoStatus {
    try {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw SonaraException.ParsingException("Malformed video status response: not a JSON object")
        }

        val fields = parseSimpleJsonObject(trimmed)
        if (!fields.containsKey("videoId")) {
            throw SonaraException.ParsingException("Backend returned an invalid videoId in video status")
        }

        val returnedVideoId = fields["videoId"].asStringOrNull()
            ?: throw SonaraException.ParsingException("Backend returned a non-string videoId")
        if (returnedVideoId.isBlank() || returnedVideoId != requestedVideoId) {
            throw SonaraException.ParsingException("Backend returned an invalid videoId in video status")
        }

        return VideoStatus(
            videoId = returnedVideoId,
            madeForKidsStatus = MadeForKidsStatus.fromWireValue(fields["madeForKidsStatus"].asStringOrNull()),
            embeddabilityStatus = EmbeddabilityStatus.fromWireValue(fields["embeddabilityStatus"].asStringOrNull())
        )
    } catch (e: SonaraException) {
        throw e
    } catch (e: Exception) {
        throw SonaraException.ParsingException("Malformed video status response", e)
    }
}

/**
 * Lightweight, pure-Kotlin recursive-descent JSON parser.
 * Avoids Android SDK `org.json` stubs during host JVM unit tests.
 */
internal sealed interface JsonNode {
    data class JsonObject(val fields: Map<String, JsonNode>) : JsonNode
    data class JsonArray(val items: List<JsonNode>) : JsonNode
    data class JsonString(val value: String) : JsonNode
    data class JsonNumber(val raw: String) : JsonNode {
        fun toInt(): Int = raw.toDouble().toInt()
        fun toLong(): Long = raw.toDouble().toLong()
        fun toDouble(): Double = raw.toDouble()
    }
    data class JsonBoolean(val value: Boolean) : JsonNode
    data object JsonNull : JsonNode
}

internal class SimpleJsonTreeParser(private val src: String) {
    private var pos = 0

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun fail(msg: String): Nothing =
        throw SonaraException.ParsingException("JSON parse error at pos $pos: $msg")

    fun parse(): JsonNode {
        skipWhitespace()
        val res = parseValue()
        skipWhitespace()
        if (pos < src.length) fail("Unexpected character '${src[pos]}' after JSON payload")
        return res
    }

    private fun parseValue(): JsonNode {
        skipWhitespace()
        if (pos >= src.length) fail("Unexpected end of input")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            in '0'..'9', '-' -> parseNumber()
            else -> fail("Unexpected token starting with '$c'")
        }
    }

    private fun parseObject(): JsonNode.JsonObject {
        pos++ // skip '{'
        val map = linkedMapOf<String, JsonNode>()
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return JsonNode.JsonObject(map)
        }
        while (pos < src.length) {
            skipWhitespace()
            if (pos >= src.length || src[pos] != '"') fail("Expected string key in object")
            val key = (parseString() as JsonNode.JsonString).value
            skipWhitespace()
            if (pos >= src.length || src[pos] != ':') fail("Expected ':' after key '$key'")
            pos++ // skip ':'
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            if (pos >= src.length) fail("Unclosed object")
            if (src[pos] == '}') {
                pos++
                return JsonNode.JsonObject(map)
            } else if (src[pos] == ',') {
                pos++
            } else {
                fail("Expected ',' or '}' in object, got '${src[pos]}'")
            }
        }
        fail("Unterminated object")
    }

    private fun parseArray(): JsonNode.JsonArray {
        pos++ // skip '['
        val list = mutableListOf<JsonNode>()
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return JsonNode.JsonArray(list)
        }
        while (pos < src.length) {
            list.add(parseValue())
            skipWhitespace()
            if (pos >= src.length) fail("Unclosed array")
            if (src[pos] == ']') {
                pos++
                return JsonNode.JsonArray(list)
            } else if (src[pos] == ',') {
                pos++
            } else {
                fail("Expected ',' or ']' in array, got '${src[pos]}'")
            }
        }
        fail("Unterminated array")
    }

    private fun parseString(): JsonNode.JsonString {
        pos++ // skip '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            when (val c = src[pos++]) {
                '"' -> return JsonNode.JsonString(sb.toString())
                '\\' -> {
                    if (pos >= src.length) fail("Unterminated string escape")
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) fail("Invalid unicode escape")
                            val hex = src.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("Unknown escape '\\$esc'")
                    }
                }
                else -> sb.append(c)
            }
        }
        fail("Unterminated string")
    }

    private fun parseBoolean(): JsonNode.JsonBoolean {
        return if (src.startsWith("true", pos)) {
            pos += 4
            JsonNode.JsonBoolean(true)
        } else if (src.startsWith("false", pos)) {
            pos += 5
            JsonNode.JsonBoolean(false)
        } else {
            fail("Expected boolean literal")
        }
    }

    private fun parseNull(): JsonNode.JsonNull {
        if (src.startsWith("null", pos)) {
            pos += 4
            return JsonNode.JsonNull
        }
        fail("Expected null literal")
    }

    private fun parseNumber(): JsonNode.JsonNumber {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) {
            pos++
        }
        val raw = src.substring(start, pos)
        return JsonNode.JsonNumber(raw)
    }
}

/**
 * Parses and strictly validates high-resolution discovery response from the backend.
 * Rejects mismatched IDs, incomplete representations, or malformed contracts.
 */
internal fun parseHighResDiscovery(
    json: String,
    requestedVideoId: String,
    baseUrl: String
): HighResVideoDiscovery {
    val trimmed = json.trim()
    val root = try {
        SimpleJsonTreeParser(trimmed).parse() as? JsonNode.JsonObject
            ?: throw SonaraException.ParsingException("High-res response must be a JSON object")
    } catch (e: SonaraException) {
        throw e
    } catch (e: Exception) {
        throw SonaraException.ParsingException("Malformed high-res discovery JSON: ${e.message}", e)
    }

    val fields = root.fields
    val returnedVideoId = (fields["videoId"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Missing videoId in high-res discovery")

    if (returnedVideoId.isBlank() || returnedVideoId != requestedVideoId) {
        throw SonaraException.ParsingException(
            "Backend returned mismatched videoId '$returnedVideoId' (requested '$requestedVideoId')"
        )
    }

    val hasHighRes = (fields["hasHighRes"] as? JsonNode.JsonBoolean)?.value
        ?: throw SonaraException.ParsingException("Missing hasHighRes boolean flag")

    val extractedAt = (fields["extractedAt"] as? JsonNode.JsonNumber)?.toLong()
        ?: (System.currentTimeMillis() / 1000L)
    val expiresAt = (fields["expiresAt"] as? JsonNode.JsonNumber)?.toLong()
        ?: (extractedAt + 7200L)

    // Negative discovery
    if (!hasHighRes) {
        return HighResVideoDiscovery.Unavailable(
            videoId = returnedVideoId,
            extractedAt = extractedAt,
            expiresAt = expiresAt
        )
    }

    // Positive discovery
    val sessionId = (fields["sessionId"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Positive high-res response missing sessionId")
    if (sessionId.isBlank()) {
        throw SonaraException.ParsingException("Positive high-res response has empty sessionId")
    }

    val videoObj = (fields["video"] as? JsonNode.JsonObject)?.fields
        ?: throw SonaraException.ParsingException("Positive high-res response missing video object")
    val audioObj = (fields["audio"] as? JsonNode.JsonObject)?.fields
        ?: throw SonaraException.ParsingException("Positive high-res response missing audio object")

    fun parseByteRange(rangeValue: JsonNode?, label: String): HighResByteRange {
        val rObj = (rangeValue as? JsonNode.JsonObject)?.fields
            ?: throw SonaraException.ParsingException("Missing or invalid $label byte range")
        val start = (rObj["start"] as? JsonNode.JsonNumber)?.toLong()
            ?: throw SonaraException.ParsingException("$label missing start offset")
        val end = (rObj["end"] as? JsonNode.JsonNumber)?.toLong()
            ?: throw SonaraException.ParsingException("$label missing end offset")
        return HighResByteRange(start, end)
    }

    // Parse video representation
    val vWidth = (videoObj["width"] as? JsonNode.JsonNumber)?.toInt()
        ?: throw SonaraException.ParsingException("Video representation missing width")
    val vHeight = (videoObj["height"] as? JsonNode.JsonNumber)?.toInt()
        ?: throw SonaraException.ParsingException("Video representation missing height")
    val vFps = (videoObj["fps"] as? JsonNode.JsonNumber)?.toInt()
        ?: throw SonaraException.ParsingException("Video representation missing fps")
    val vItag = (videoObj["itag"] as? JsonNode.JsonNumber)?.toInt() ?: 571
    val vCodec = (videoObj["codec"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Video representation missing codec")
    val vMime = (videoObj["mimeType"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Video representation missing mimeType")
    val vBitrate = (videoObj["bitrate"] as? JsonNode.JsonNumber)?.toLong()
        ?: throw SonaraException.ParsingException("Video representation missing bitrate")
    val vDuration = (videoObj["duration"] as? JsonNode.JsonNumber)?.toDouble()
        ?: throw SonaraException.ParsingException("Video representation missing duration")
    val vInit = parseByteRange(videoObj["initRange"], "video.initRange")
    val vIndex = parseByteRange(videoObj["indexRange"], "video.indexRange")
    val vStreamUrl = validateAndResolveProxyUrl(
        rawUrl = (videoObj["streamUrl"] as? JsonNode.JsonString)?.value,
        baseUrl = baseUrl,
        expectedTrack = "video",
        expectedSessionId = sessionId
    )

    val videoRep = try {
        HighResVideoRepresentation(
            itag = vItag,
            width = vWidth,
            height = vHeight,
            fps = vFps,
            codec = vCodec,
            mimeType = vMime,
            bitrate = vBitrate,
            duration = vDuration,
            initRange = vInit,
            indexRange = vIndex,
            streamProxyUrl = vStreamUrl
        )
    } catch (e: IllegalArgumentException) {
        throw SonaraException.ParsingException("Invalid video representation: ${e.message}", e)
    }

    // Parse audio representation
    val aItag = (audioObj["itag"] as? JsonNode.JsonNumber)?.toInt() ?: 140
    val aCodec = (audioObj["codec"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Audio representation missing codec")
    val aMime = (audioObj["mimeType"] as? JsonNode.JsonString)?.value
        ?: throw SonaraException.ParsingException("Audio representation missing mimeType")
    val aBitrate = (audioObj["bitrate"] as? JsonNode.JsonNumber)?.toLong()
        ?: throw SonaraException.ParsingException("Audio representation missing bitrate")
    val aDuration = (audioObj["duration"] as? JsonNode.JsonNumber)?.toDouble()
        ?: throw SonaraException.ParsingException("Audio representation missing duration")
    val aInit = parseByteRange(audioObj["initRange"], "audio.initRange")
    val aIndex = parseByteRange(audioObj["indexRange"], "audio.indexRange")
    val aStreamUrl = validateAndResolveProxyUrl(
        rawUrl = (audioObj["streamUrl"] as? JsonNode.JsonString)?.value,
        baseUrl = baseUrl,
        expectedTrack = "audio",
        expectedSessionId = sessionId
    )

    val audioRep = try {
        HighResAudioRepresentation(
            itag = aItag,
            codec = aCodec,
            mimeType = aMime,
            bitrate = aBitrate,
            duration = aDuration,
            initRange = aInit,
            indexRange = aIndex,
            streamProxyUrl = aStreamUrl
        )
    } catch (e: IllegalArgumentException) {
        throw SonaraException.ParsingException("Invalid audio representation: ${e.message}", e)
    }

    return try {
        HighResVideoDiscovery.Available(
            videoId = returnedVideoId,
            sessionId = sessionId,
            extractedAt = extractedAt,
            expiresAt = expiresAt,
            video = videoRep,
            audio = audioRep
        )
    } catch (e: IllegalArgumentException) {
        throw SonaraException.ParsingException("Invalid high-res discovery payload: ${e.message}", e)
    }
}

/**
 * Strictly validates and resolves proxy URLs from high-res discovery responses.
 *
 * Requirements:
 * - Resolves relative proxy paths (/api/v1/stream/video?...) against baseUrl.
 * - Rejects any raw googlevideo.com or third-party domains.
 * - For absolute URLs, host and port must strictly match the configured backend baseUrl.
 * - Requires HTTPS in production (non-debug). Allows HTTP only for localhost, emulator loopback (10.0.2.2),
 *   or the configured dev backend origin when BuildConfig.DEBUG is true.
 * - Restricts route path strictly to "/api/v1/stream/video".
 * - Validates presence and non-blankness of required query parameters: sessionId and track.
 * - Validates track parameter matches expectedTrack ("video" or "audio").
 * - If expectedSessionId is provided, validates sessionId matches.
 * - Never includes the full URL or bearer sessionId in thrown exception messages or logs.
 */
internal fun validateAndResolveProxyUrl(
    rawUrl: String?,
    baseUrl: String,
    expectedTrack: String,
    expectedSessionId: String? = null,
    isDebug: Boolean = BuildConfig.DEBUG
): String {
    val trimmed = rawUrl?.trim()
        ?: throw SonaraException.ParsingException("Missing proxy stream URL")
    if (trimmed.isEmpty()) {
        throw SonaraException.ParsingException("Blank proxy stream URL")
    }

    val resolved = if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
        "${baseUrl.trimEnd('/')}$trimmed"
    } else {
        trimmed
    }

    val resolvedUri = try {
        URI(resolved)
    } catch (e: Exception) {
        throw SonaraException.ParsingException("Malformed proxy URI")
    }

    val baseUri = try {
        URI(baseUrl)
    } catch (e: Exception) {
        throw SonaraException.ParsingException("Malformed backend baseUrl")
    }

    val host = resolvedUri.host?.lowercase()
        ?: throw SonaraException.ParsingException("Proxy URL missing host")

    // Reject raw googlevideo or upstream streaming domains
    if (host.contains("googlevideo.com") || host.contains("youtube.com")) {
        throw SonaraException.ParsingException("Direct upstream streaming URLs are forbidden for high-res proxy")
    }

    val baseHost = baseUri.host?.lowercase()
        ?: throw SonaraException.ParsingException("Backend baseUrl missing host")

    // Host comparison: must match configured backend host
    if (host != baseHost) {
        throw SonaraException.ParsingException("Proxy URL host does not match backend host")
    }

    // Port check: normalized comparison
    val defaultResolvedPort = if (resolvedUri.scheme?.equals("https", ignoreCase = true) == true) 443 else 80
    val defaultBasePort = if (baseUri.scheme?.equals("https", ignoreCase = true) == true) 443 else 80
    val resolvedPort = if (resolvedUri.port != -1) resolvedUri.port else defaultResolvedPort
    val basePort = if (baseUri.port != -1) baseUri.port else defaultBasePort
    if (resolvedPort != basePort) {
        throw SonaraException.ParsingException("Proxy URL port does not match backend port")
    }

    // Scheme check
    val scheme = resolvedUri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw SonaraException.ParsingException("Invalid proxy URL scheme: $scheme")
    }

    if (!isDebug) {
        if (scheme != "https") {
            throw SonaraException.ParsingException("HTTPS is required for proxy URL in release builds")
        }
    } else {
        if (scheme == "http") {
            val isLoopbackOrLocal = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
            val isConfiguredDevHost = host == baseHost
            if (!isLoopbackOrLocal && !isConfiguredDevHost) {
                throw SonaraException.ParsingException("Insecure HTTP proxy URL rejected for non-local host")
            }
        }
    }

    // Path check
    if (resolvedUri.path != "/api/v1/stream/video") {
        throw SonaraException.ParsingException("Invalid proxy URL path: ${resolvedUri.path}, expected /api/v1/stream/video")
    }

    // Query parameters check
    val query = resolvedUri.rawQuery
        ?: throw SonaraException.ParsingException("Missing query parameters in proxy URL")

    var foundSessionId: String? = null
    var foundTrack: String? = null

    for (part in query.split("&")) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        if (eq > 0) {
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1)
            when (key) {
                "sessionId" -> foundSessionId = value
                "track" -> foundTrack = value
            }
        }
    }

    if (foundSessionId.isNullOrBlank()) {
        throw SonaraException.ParsingException("Missing sessionId parameter in proxy URL")
    }
    if (expectedSessionId != null && foundSessionId != expectedSessionId) {
        throw SonaraException.ParsingException("Proxy URL sessionId does not match discovery session")
    }
    if (foundTrack != expectedTrack) {
        throw SonaraException.ParsingException("Proxy URL track '$foundTrack' does not match expected '$expectedTrack'")
    }

    return resolved
}

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
    open suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/artists/featured"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("featured") ?: JSONArray()
            val artists = mutableListOf<FeaturedArtist>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optionalString("id") ?: continue
                val name = item.optionalString("name") ?: continue
                artists.add(
                    FeaturedArtist(
                        id = id,
                        name = name,
                        genre = item.optionalString("genre").orEmpty(),
                        imageUrl = sanitizeFeaturedArtistImageUrl(item.optionalString("imageUrl")),
                        browseId = item.optionalString("browseId")
                    )
                )
            }
            Result.success(artists)
        } catch (e: CancellationException) {
            throw e
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
    open suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = withContext(Dispatchers.IO) {
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
     * Resolves a portrait for a locally derived artist.
     * GET /api/v1/artists/image?name={name} -> { imageUrl|null }
     */
    open suspend fun getArtistImage(artistName: String): Result<String?> = withContext(Dispatchers.IO) {
        if (artistName.isBlank()) return@withContext Result.success(null)
        val encodedName = URLEncoder.encode(artistName, "UTF-8")
        val url = "$baseUrl/api/v1/artists/image?name=$encodedName"
        if (BuildConfig.VERIFY_PORTRAITS) {
            Log.i(TAG, "ARTIST_IMAGE_REQUEST name=$artistName path=/api/v1/artists/image")
        }
        try {
            val root = JSONObject(get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS))
            val imageUrl = FeaturedArtistImage.trustedHttpsUrl(root.optionalString("imageUrl"))
            if (BuildConfig.VERIFY_PORTRAITS) {
                Log.i(TAG, "ARTIST_IMAGE_RESPONSE name=$artistName trusted=${imageUrl != null}")
            }
            Result.success(imageUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SonaraException) {
            if (BuildConfig.VERIFY_PORTRAITS) {
                Log.e(TAG, "ARTIST_IMAGE_FAILURE name=$artistName message=${e.message}")
            }
            Log.w(TAG, "getArtistImage failed for $artistName: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            if (BuildConfig.VERIFY_PORTRAITS) {
                Log.e(TAG, "ARTIST_IMAGE_FAILURE name=$artistName message=${e.message}")
            }
            Log.e(TAG, "getArtistImage unexpected error for $artistName: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching artist image", e))
        }
    }

    /** JSONObject.optString returns the literal string "null" for JSON null. */
    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null
        else optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    /**
     * Curated playlist catalog (tiles only; tracks fetched on demand).
     * GET /api/v1/playlists -> { playlists: [ { id, name, description, coverImage|null, size } ] }
     */
    open suspend fun getPlaylists(): Result<List<PlaylistSummary>> = withContext(Dispatchers.IO) {
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
    open suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = withContext(Dispatchers.IO) {
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
    open suspend fun getQuickPicks(refresh: Boolean = false): Result<List<Track>> = withContext(Dispatchers.IO) {
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
    open suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
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
     * Seed-based radio continuation.
     * GET /api/v1/recommendations/radio/{videoId} -> { success, tracks: [ TrackDTO ], ... }
     */
    open suspend fun getRadioTracks(seedVideoId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (seedVideoId.isBlank()) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot fetch radio recommendations for blank videoId")
            )
        }
        val encodedId = URLEncoder.encode(seedVideoId, "UTF-8")
        val url = "$baseUrl/api/v1/recommendations/radio/$encodedId"
        try {
            val json = get(url, timeoutMs = SonaraBackendConfig.DISCOVERY_TIMEOUT_MS)
            val array = JSONObject(json).optJSONArray("tracks") ?: JSONArray()
            Result.success(parseTrackArray(array))
        } catch (e: SonaraException) {
            Log.w(TAG, "getRadioTracks failed for $seedVideoId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getRadioTracks unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching radio recommendations", e))
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
                code == 410 -> throw SonaraException.NotFoundException("Resource expired on backend (410)")
                code == 429 -> throw SonaraException.ProviderUnavailableException("Backend rate limit hit (429), please wait")
                code == 422 -> throw SonaraException.ProviderUnavailableException("Track unavailable or no audio stream (422)")
                code in 500..504 -> throw SonaraException.NetworkException("Backend server error ($code)")
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

    /**
     * Reads the backend's policy metadata for one YouTube video.
     * GET /api/v1/videos/{videoId}/status ->
     * { videoId, madeForKidsStatus, embeddabilityStatus }
     *
     * The returned videoId must match the requested id exactly. A malformed or
     * mismatched response is a parsing failure rather than usable metadata.
     */
    open suspend fun getVideoStatus(videoId: String): Result<VideoStatus> = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        if (!isValidYouTubeVideoId(cleanVideoId)) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot fetch video status for invalid videoId")
            )
        }

        val encodedVideoId = URLEncoder.encode(cleanVideoId, "UTF-8")
        val url = "$baseUrl/api/v1/videos/$encodedVideoId/status"
        try {
            Result.success(parseVideoStatus(get(url, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS), cleanVideoId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SonaraException) {
            Log.w(TAG, "getVideoStatus failed for $cleanVideoId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getVideoStatus unexpected error: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching video status", e))
        }
    }

    /**
     * Queries backend high-resolution 8K discovery endpoint:
     * GET /api/v1/videos/{videoId}/highres
     *
     * Returns genuine 4320p stream representation and session metadata if available,
     * or [HighResVideoDiscovery.Unavailable] if the video does not expose genuine 8K.
     *
     * Logs diagnostic categories without exposing bearer session IDs or proxy URLs.
     */
    open suspend fun getVideoHighRes(videoId: String): Result<HighResVideoDiscovery> = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        if (!isValidYouTubeVideoId(cleanVideoId)) {
            return@withContext Result.failure(
                SonaraException.NotFoundException("Cannot fetch high-resolution video for invalid videoId")
            )
        }

        val encodedVideoId = URLEncoder.encode(cleanVideoId, "UTF-8")
        val url = "$baseUrl/api/v1/videos/$encodedVideoId/highres"
        try {
            val responseBody = get(url, timeoutMs = SonaraBackendConfig.READ_TIMEOUT_MS)
            Result.success(parseHighResDiscovery(responseBody, cleanVideoId, baseUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SonaraException) {
            Log.w(TAG, "getVideoHighRes failed for $cleanVideoId: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "getVideoHighRes unexpected error for $cleanVideoId: ${e.message}")
            Result.failure(SonaraException.NetworkException("Unexpected error fetching high-resolution video", e))
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
            rawArtworkUrl = rawArtworkUrl,
            artists = parseArtistCredits(json)
        )
    }

    /**
     * Reads TrackDTO.artists — the structured per-artist credits — used for artist
     * IDENTITY decisions (Featured Artists affinity attribution).
     *
     * Absent, non-array and malformed payloads all yield an empty list, which callers
     * must read as "no verified identity" rather than falling back to parsing the flat
     * `artist` display string. Entries without a usable name are dropped; a missing
     * `id` becomes "" (a real credit that simply has no authoritative identifier).
     */
    private fun parseArtistCredits(json: JSONObject): List<ArtistCredit> {
        val array = json.optJSONArray("artists") ?: return emptyList()
        val credits = mutableListOf<ArtistCredit>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val name = entry.optString("name").trim()
            if (name.isEmpty()) continue
            credits.add(ArtistCredit(name = name, id = entry.optString("id").trim()))
        }
        return credits
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
        // No artwork is better than invented artwork: a URL built from the video id is an
        // unverified guess at an asset that may not exist (`maxresdefault.jpg` is absent for
        // many videos). Leaving this null lets the surface show its existing glyph fallback.
        val artworkUrl = resolveArtworkUrl(json)

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
