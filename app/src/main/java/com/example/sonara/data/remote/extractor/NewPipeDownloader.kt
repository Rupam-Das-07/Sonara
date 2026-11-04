package com.example.sonara.data.remote.extractor

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Small NewPipe Downloader backed by the JDK HTTP stack already used by Sonara's backend client.
 * It returns HTTP failures as NewPipe responses and lets transport failures propagate as IO errors.
 */
class NewPipeDownloader(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    }
) : Downloader() {

    override fun execute(request: Request): Response {
        var connection: HttpURLConnection? = null
        return try {
            val urlString = request.url()
            val host = runCatching { URL(urlString).host }.getOrNull()
            val hasCallerUserAgent = request.headers().keys.any {
                it.equals(HEADER_USER_AGENT, ignoreCase = true)
            }

            connection = connectionFactory(urlString).apply {
                requestMethod = request.httpMethod()
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                doInput = true

                // Preserve all caller-provided headers with their original names and values
                request.headers().forEach { (name, values) ->
                    values.forEach { value -> setRequestProperty(name, value) }
                }

                // If targeting an approved SoundCloud/sndcdn domain and caller did not provide
                // a User-Agent, supply a stable desktop browser User-Agent so SoundCloud does
                // not redirect mobile/Dalvik User-Agents to m.soundcloud.com (which lacks SPA asset scripts).
                if (!hasCallerUserAgent && isSoundcloudHost(host)) {
                    setRequestProperty(HEADER_USER_AGENT, SOUNDCLOUD_DESKTOP_USER_AGENT)
                }
            }

            val requestBody = request.dataToSend()
            if (requestBody != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(requestBody) }
            }

            val responseCode = connection.responseCode
            val responseBody = when {
                request.httpMethod().equals("HEAD", ignoreCase = true) -> ""
                responseCode >= HttpURLConnection.HTTP_BAD_REQUEST ->
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        .orEmpty()
                else -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            Response(
                responseCode,
                connection.responseMessage.orEmpty(),
                connection.headerFields.entries
                    .mapNotNull { (name, values) ->
                        if (name != null && values != null) name to values else null
                    }
                    .toMap(),
                responseBody,
                connection.url.toString()
            )
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000

        const val HEADER_USER_AGENT = "User-Agent"

        /**
         * Stable desktop browser User-Agent used for SoundCloud landing and asset requests.
         * Prevents SoundCloud from returning HTTP 307 redirect to m.soundcloud.com.
         */
        const val SOUNDCLOUD_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Case-insensitive, boundary-safe host matcher for approved SoundCloud domains.
         * Matches soundcloud.com, sndcdn.com, and legitimate subdomains (e.g., api-v2.soundcloud.com,
         * a-v2.sndcdn.com), while rejecting lookalikes (e.g., not-soundcloud.com, soundcloud.com.evil.org).
         */
        fun isSoundcloudHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val normalized = host.lowercase(Locale.ROOT)
            return normalized == "soundcloud.com" ||
                normalized.endsWith(".soundcloud.com") ||
                normalized == "sndcdn.com" ||
                normalized.endsWith(".sndcdn.com")
        }
    }
}
