package com.example.sonara.data.remote.extractor

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

class NewPipeDownloaderTest {
    @Test
    fun `downloader preserves successful response data`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            val body = "fixture-body".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val response = NewPipeDownloader().execute(
                Request.newBuilder().get("http://127.0.0.1:${server.address.port}/ok").build()
            )

            assertEquals(200, response.responseCode())
            assertEquals("fixture-body", response.responseBody())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http failure remains an explicit response and transport failure throws`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/missing") { exchange ->
            val body = "missing".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val response = NewPipeDownloader().execute(
                Request.newBuilder().get("http://127.0.0.1:${server.address.port}/missing").build()
            )
            assertEquals(503, response.responseCode())
            assertEquals("missing", response.responseBody())

            val transportFailure = runCatching {
                NewPipeDownloader(connectTimeoutMs = 100, readTimeoutMs = 100).execute(
                    Request.newBuilder().get("http://127.0.0.1:1/unreachable").build()
                )
            }
            assertTrue(transportFailure.isFailure)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `isSoundcloudHost accurately identifies soundcloud and sndcdn domains and subdomains`() {
        assertTrue(NewPipeDownloader.isSoundcloudHost("soundcloud.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("api.soundcloud.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("api-v2.soundcloud.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("m.soundcloud.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("sndcdn.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("a-v2.sndcdn.com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("cf-hls-media.sndcdn.com"))
    }

    @Test
    fun `isSoundcloudHost is case-insensitive`() {
        assertTrue(NewPipeDownloader.isSoundcloudHost("SOUNDCLOUD.COM"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("SndCdn.Com"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("API-V2.SOUNDCLOUD.COM"))
        assertTrue(NewPipeDownloader.isSoundcloudHost("A-V2.SNDCDN.COM"))
    }

    @Test
    fun `isSoundcloudHost rejects lookalikes, attacker domains, and unrelated domains`() {
        assertFalse(NewPipeDownloader.isSoundcloudHost("not-soundcloud.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("soundcloud.com.evil.org"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("fakesndcdn.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("sndcdn.com.attacker.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("youtube.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("googlevideo.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost("example.com"))
        assertFalse(NewPipeDownloader.isSoundcloudHost(""))
        assertFalse(NewPipeDownloader.isSoundcloudHost("   "))
        assertFalse(NewPipeDownloader.isSoundcloudHost(null))
    }

    @Test
    fun `execute sets fallback desktop User-Agent on soundcloud and sndcdn requests when none provided`() {
        val capturedHeaders = mutableMapOf<String, String>()
        val downloader = NewPipeDownloader(connectionFactory = { url ->
            createFakeConnection(url, capturedHeaders)
        })

        // Test soundcloud.com request
        downloader.execute(Request.newBuilder().get("https://soundcloud.com/discover").build())
        assertEquals(
            NewPipeDownloader.SOUNDCLOUD_DESKTOP_USER_AGENT,
            capturedHeaders[NewPipeDownloader.HEADER_USER_AGENT]
        )

        capturedHeaders.clear()
        // Test sndcdn.com asset request
        downloader.execute(Request.newBuilder().get("https://a-v2.sndcdn.com/assets/55-app.js").build())
        assertEquals(
            NewPipeDownloader.SOUNDCLOUD_DESKTOP_USER_AGENT,
            capturedHeaders[NewPipeDownloader.HEADER_USER_AGENT]
        )
    }

    @Test
    fun `execute preserves caller-provided User-Agent exactly`() {
        val capturedHeaders = mutableMapOf<String, String>()
        val downloader = NewPipeDownloader(connectionFactory = { url ->
            createFakeConnection(url, capturedHeaders)
        })

        val request = Request.newBuilder()
            .get("https://soundcloud.com/discover")
            .headers(mapOf("User-Agent" to listOf("CustomClient/1.0")))
            .build()

        downloader.execute(request)
        assertEquals("CustomClient/1.0", capturedHeaders["User-Agent"])
    }

    @Test
    fun `execute preserves caller-provided User-Agent case-insensitively`() {
        val capturedHeaders = mutableMapOf<String, String>()
        val downloader = NewPipeDownloader(connectionFactory = { url ->
            createFakeConnection(url, capturedHeaders)
        })

        val request = Request.newBuilder()
            .get("https://soundcloud.com/discover")
            .headers(mapOf("user-agent" to listOf("CustomLower/2.0")))
            .build()

        downloader.execute(request)
        assertEquals("CustomLower/2.0", capturedHeaders["user-agent"])
        assertFalse(capturedHeaders.containsKey("User-Agent"))
    }

    @Test
    fun `execute leaves non-SoundCloud requests unchanged without User-Agent override`() {
        val capturedHeaders = mutableMapOf<String, String>()
        val downloader = NewPipeDownloader(connectionFactory = { url ->
            createFakeConnection(url, capturedHeaders)
        })

        val request = Request.newBuilder()
            .get("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .build()

        downloader.execute(request)
        assertFalse(capturedHeaders.containsKey(NewPipeDownloader.HEADER_USER_AGENT))
        assertFalse(capturedHeaders.containsKey("user-agent"))
    }

    @Test
    fun `execute preserves all other request headers alongside fallback User-Agent`() {
        val capturedHeaders = mutableMapOf<String, String>()
        val downloader = NewPipeDownloader(connectionFactory = { url ->
            createFakeConnection(url, capturedHeaders)
        })

        val request = Request.newBuilder()
            .get("https://a-v2.sndcdn.com/assets/55-test.js")
            .headers(
                mapOf(
                    "Range" to listOf("bytes=0-50000"),
                    "Accept" to listOf("application/javascript")
                )
            )
            .build()

        downloader.execute(request)
        assertEquals("bytes=0-50000", capturedHeaders["Range"])
        assertEquals("application/javascript", capturedHeaders["Accept"])
        assertEquals(
            NewPipeDownloader.SOUNDCLOUD_DESKTOP_USER_AGENT,
            capturedHeaders[NewPipeDownloader.HEADER_USER_AGENT]
        )
    }

    private fun createFakeConnection(
        url: String,
        capturedHeaders: MutableMap<String, String>
    ): HttpURLConnection {
        return object : HttpURLConnection(URL(url)) {
            override fun setRequestProperty(key: String, value: String) {
                capturedHeaders[key] = value
            }
            override fun getResponseCode(): Int = 200
            override fun getResponseMessage(): String = "OK"
            override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
            override fun disconnect() {}
            override fun usingProxy(): Boolean = false
            override fun connect() {}
        }
    }
}
