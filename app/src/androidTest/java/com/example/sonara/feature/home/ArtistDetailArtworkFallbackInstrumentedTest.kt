package com.example.sonara.feature.home

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.feature.home.screens.ArtistDetailScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * FA-008 — Artist Detail must retain its monogram when a portrait is absent or fails.
 *
 * The error case uses a deterministic Coil Fetcher that receives a nonblank URL and throws from
 * fetch(). Waiting for the fetch count proves the request reached Coil's error path before the
 * fallback assertion is made; this is not a null-URL sanitization test.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ArtistDetailArtworkFallbackInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var imageLoader: ImageLoader

    @Before
    fun setUp() {
        imageLoader = ImageLoader.Builder(context).build()
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
    }

    @Test
    fun missingImageUrlShowsArtistMonogram() {
        setArtist(FeaturedArtist(id = "hans-zimmer", name = "Hans Zimmer"))

        composeRule.onNodeWithText("HZ", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun failedNonblankImageRequestLeavesArtistMonogramVisible() {
        val failingFactory = FailingFetcherFactory()
        imageLoader.shutdown()
        imageLoader = ImageLoader.Builder(context)
            .components { add(failingFactory) }
            .build()

        val directResult = runBlocking {
            imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(FAILED_IMAGE_URL)
                    .build()
            )
        }
        assertTrue(
            "the deterministic nonblank request must reach Coil's error result",
            directResult is ErrorResult
        )
        val directError = directResult as ErrorResult
        assertTrue(
            "the ErrorResult must come from the deterministic custom fetcher",
            directError.throwable.message?.contains("FA-008 deterministic image failure") == true
        )
        assertTrue("the custom Coil fetcher must be selected for the direct request", failingFactory.createCount.get() > 0)
        assertTrue("the custom Coil fetcher must execute the direct request", failingFactory.fetchCount.get() > 0)
        assertEquals(Uri.parse(FAILED_IMAGE_URL), failingFactory.createdData)
        assertEquals(Uri.parse(FAILED_IMAGE_URL), failingFactory.fetchedData)

        val uiCreateCountBaseline = failingFactory.createCount.get()
        val uiFetchCountBaseline = failingFactory.fetchCount.get()
        setArtist(
            FeaturedArtist(
                id = "hans-zimmer",
                name = "Hans Zimmer",
                imageUrl = FAILED_IMAGE_URL
            )
        )

        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                failingFactory.fetchCount.get() > uiFetchCountBaseline
            }
        } catch (error: AssertionError) {
            throw AssertionError(
                "Timed out waiting for the UI's deterministic Coil fetcher invocation " +
                    "(createCount=${failingFactory.createCount.get()}, " +
                    "fetchCount=${failingFactory.fetchCount.get()})",
                error
            )
        }
        composeRule.waitForIdle()
        assertTrue(
            "the UI request must select the custom Coil fetcher",
            failingFactory.createCount.get() > uiCreateCountBaseline
        )
        // Compose semantics verifies that the fallback node is laid out/displayed. This project
        // has no screenshot or pixel-inspection dependency, so this is not an occlusion proof.
        composeRule.onNodeWithText("HZ", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(Uri.parse(FAILED_IMAGE_URL), failingFactory.fetchedData)
    }

    private fun setArtist(artist: FeaturedArtist) {
        composeRule.setContent {
            SonaraTheme(darkTheme = false) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalImageLoader provides imageLoader
                ) {
                    ArtistDetailScreen(
                        artist = artist,
                        tracksModule = HomeModule.Hidden,
                        onBack = {},
                        onPlayTrack = {},
                        onToggleLike = { _, _ -> },
                        isLiked = { false }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val context: Context
            get() = ApplicationProvider.getApplicationContext()

        const val FAILED_IMAGE_URL = "https://images.example.invalid/fa-008-failure"
    }

    // Coil 2.7.0 maps String request data to android.net.Uri before selecting a Fetcher.Factory.
    private class FailingFetcherFactory : Fetcher.Factory<Uri> {
        val createCount = AtomicInteger()
        val fetchCount = AtomicInteger()

        @Volatile
        var createdData: Uri? = null

        @Volatile
        var fetchedData: Uri? = null

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.toString() != FAILED_IMAGE_URL) return null
            createCount.incrementAndGet()
            createdData = data
            return FailingFetcher {
                fetchedData = data
                fetchCount.incrementAndGet()
            }
        }
    }

    private class FailingFetcher(
        private val onFetch: () -> Unit
    ) : Fetcher {
        override suspend fun fetch(): FetchResult? {
            onFetch()
            error("FA-008 deterministic image failure")
        }
    }
}
