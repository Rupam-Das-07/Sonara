package com.example.sonara.feature.home

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.sonara.data.remote.backend.SonaraBackendClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device portrait verification. Run only with a local Node gateway reachable
 * through the debug build's configured topology (normally adb reverse tcp:3002 tcp:3002).
 * Both requests bypass Coil caches and must report NETWORK, not MEMORY_CACHE or DISK.
 */
@RunWith(AndroidJUnit4::class)
class FeaturedArtistBitmapInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun hansZimmerAndColdplayAreFreshNetworkBitmaps() = runBlocking {
        val client = SonaraBackendClient()
        val loader = ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

        for (name in listOf("Hans Zimmer", "Coldplay")) {
            val imageUrl = client.getArtistImage(name).getOrThrow()
            assertTrue("$name must resolve to a trusted image", !imageUrl.isNullOrBlank())

            val result = loader.execute(
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
            )

            val success = result as? SuccessResult
            assertTrue("$name must decode successfully", success != null)
            assertEquals("$name must be fetched fresh", coil.decode.DataSource.NETWORK, success?.dataSource)
            val drawable = requireNotNull(success).drawable
            assertTrue("$name must decode as a bitmap", drawable is BitmapDrawable)
            assertTrue("$name bitmap must have positive width", drawable.intrinsicWidth > 0)
            assertTrue("$name bitmap must have positive height", drawable.intrinsicHeight > 0)
        }
    }
}
