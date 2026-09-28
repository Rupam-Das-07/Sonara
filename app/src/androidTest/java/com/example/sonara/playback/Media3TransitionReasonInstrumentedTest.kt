package com.example.sonara.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3TransitionReasonInstrumentedTest {

    private lateinit var player: ExoPlayer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun reasonToString(reason: Int): String {
        return when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO (0)"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK (1)"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT (2)"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED (3)"
            else -> "UNKNOWN ($reason)"
        }
    }

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.release()
        }
    }

    @Test
    fun verifyTransitionReasonOnSetMediaItem() {
        var observedMediaId: String? = null
        var observedReason: Int? = null
        val latch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    observedMediaId = mediaItem?.mediaId
                    observedReason = reason
                    println("INSTRUMENTED RUNTIME: onMediaItemTransition mediaId=${mediaItem?.mediaId}, reason=${reasonToString(reason)}")
                    latch.countDown()
                }
            })

            val item = MediaItem.Builder().setMediaId("test_track_1").setUri("asset:///silent.mp3").build()
            player.setMediaItem(item)
            player.prepare()
        }

        latch.await(3, TimeUnit.SECONDS)
        assertNotNull("Must observe mediaId", observedMediaId)
        assertEquals("test_track_1", observedMediaId)
        println("VERIFIED RUNTIME REASON FOR setMediaItem(): ${reasonToString(observedReason ?: -1)}")
        assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, observedReason)
    }

    @Test
    fun verifyTransitionReasonOnSeekToNextMediaItem() {
        val transitions = mutableListOf<Pair<String?, Int>>()
        val latch = CountDownLatch(2)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions.add(Pair(mediaItem?.mediaId, reason))
                    println("INSTRUMENTED RUNTIME: onMediaItemTransition mediaId=${mediaItem?.mediaId}, reason=${reasonToString(reason)}")
                    latch.countDown()
                }
            })

            val item1 = MediaItem.Builder().setMediaId("item_1").setUri("asset:///silent1.mp3").build()
            val item2 = MediaItem.Builder().setMediaId("item_2").setUri("asset:///silent2.mp3").build()
            player.setMediaItem(item1)
            player.addMediaItem(item2)
            player.prepare()
            player.seekToNextMediaItem()
        }

        latch.await(3, TimeUnit.SECONDS)
        println("ALL OBSERVED TRANSITIONS: ${transitions.map { "${it.first} -> ${reasonToString(it.second)}" }}")
    }
}
