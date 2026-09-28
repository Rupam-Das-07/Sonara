package com.example.sonara.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sonara.SonaraApp
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.feature.player.PlayerViewModel
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.controller.PlaybackQueueEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SonaraPlaybackPipelineInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app get() = context.applicationContext as SonaraApp

    private val trackA = Track(id = "trk_A", title = "Track Alpha", artist = "Artist A", durationMs = 180000L)
    private val trackB = Track(id = "trk_B", title = "Track Beta", artist = "Artist B", durationMs = 200000L)
    private val trackC = Track(id = "trk_C", title = "Track Gamma", artist = "Artist C", durationMs = 220000L)
    private val trackD = Track(id = "trk_D", title = "Track Delta", artist = "Artist D", durationMs = 240000L)

    private lateinit var client: MediaControllerClient
    private lateinit var queueEngine: PlaybackQueueEngine

    @Before
    fun setup() {
        queueEngine = app.container.playbackQueueEngine
        client = app.container.mediaControllerClient

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            client.connect()
            // Wait for controller to connect
            val scope = CoroutineScope(Dispatchers.Main)
            client.controllerState.onEach { state ->
                if (state.isConnected) {
                    latch.countDown()
                }
            }.launchIn(scope)
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun testRealDeviceStateTearingMeasurement() {
        println("=== ON-DEVICE STATE TEARING MEASUREMENT ON REAL DEVICE ===")
        val emissions = mutableListOf<Pair<Long, String>>()
        val timestamps = mutableMapOf<String, Long>()

        val testResolver = object : StreamResolverPort {
            override fun clearCache() {}
            override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> {
                return Result.success(StreamInfo(
                    trackId = trackId,
                    streamUrl = "asset:///silent.mp3",
                    expiresAt = System.currentTimeMillis() + 3600_000L,
                    format = "audio/mp4"
                ))
            }
        }

        var measuredRollbackDurationMs = 0L

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD))

            val viewModel = PlayerViewModel(
                client = client,
                discoveryRepository = app.container.discoveryRepository,
                streamResolverPort = testResolver,
                queueEngine = queueEngine
            )

            val scope = CoroutineScope(Dispatchers.Main)
            viewModel.uiState.onEach { state ->
                val now = System.currentTimeMillis()
                emissions.add(Pair(now, state.trackId))
                println("[ON-DEVICE t=${now}ms] uiState emitted: trackId='${state.trackId}' title='${state.trackTitle}' buffering=${state.isBuffering}")
            }.launchIn(scope)

            // Start playing Track A
            timestamps["T0_START"] = System.currentTimeMillis()
            client.playTrack(trackA, "asset:///silent.mp3")
        }

        Thread.sleep(500) // Let Track A settle on device

        println("\n>>> PRESSING NEXT ON REAL RUNNING PIPELINE <<<")
        val nextLatch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = PlayerViewModel(
                client = client,
                discoveryRepository = app.container.discoveryRepository,
                streamResolverPort = testResolver,
                queueEngine = queueEngine
            )

            val scope = CoroutineScope(Dispatchers.Main)
            viewModel.uiState.onEach { state ->
                val now = System.currentTimeMillis()
                emissions.add(Pair(now, state.trackId))
                println("[ON-DEVICE t=${now}ms] Next uiState emitted: trackId='${state.trackId}'")
                if (state.trackId == "trk_B" && emissions.any { it.second == "trk_A" }) {
                    nextLatch.countDown()
                }
            }.launchIn(scope)

            timestamps["T1_NEXT_PRESSED"] = System.currentTimeMillis()
            viewModel.skipToNext()
        }

        nextLatch.await(5, TimeUnit.SECONDS)
        Thread.sleep(400) // Allow all callbacks to settle

        println("\n--- COMPLETE ON-DEVICE EMISSION TIMELINE ---")
        val tFirst = emissions.firstOrNull()?.first ?: 0L
        for ((time, trackId) in emissions) {
            println("  +${time - tFirst}ms : $trackId")
        }

        val trackIdsOnly = emissions.map { it.second }.filter { it.isNotBlank() }
        println("Track ID sequence: $trackIdsOnly")
    }

    @Test
    fun testRealDeviceRapidSkipX3() {
        println("\n=== TEST REAL DEVICE: RAPID SKIP X3 (A -> B -> C -> D) ===")
        val emissions = mutableListOf<Pair<Long, String>>()

        val testResolver = object : StreamResolverPort {
            override fun clearCache() {}
            override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> {
                // Realistic 80ms resolution delay
                Thread.sleep(80)
                return Result.success(StreamInfo(
                    trackId = trackId,
                    streamUrl = "asset:///silent.mp3",
                    expiresAt = System.currentTimeMillis() + 3600_000L,
                    format = "audio/mp4"
                ))
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD))

            val viewModel = PlayerViewModel(
                client = client,
                discoveryRepository = app.container.discoveryRepository,
                streamResolverPort = testResolver,
                queueEngine = queueEngine
            )

            val scope = CoroutineScope(Dispatchers.Main)
            viewModel.uiState.onEach { state ->
                val now = System.currentTimeMillis()
                emissions.add(Pair(now, state.trackId))
                println("[RAPID X3 t=${now}ms] uiState: trackId='${state.trackId}' buffering=${state.isBuffering}")
            }.launchIn(scope)

            // Start on Track A
            client.playTrack(trackA, "asset:///silent.mp3")
        }

        Thread.sleep(400) // Settle on A

        // Perform 3 rapid skips
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = PlayerViewModel(
                client = client,
                discoveryRepository = app.container.discoveryRepository,
                streamResolverPort = testResolver,
                queueEngine = queueEngine
            )

            println("\n>>> FIRING RAPID NEXT #1 (-> B) <<<")
            viewModel.skipToNext()

            println(">>> FIRING RAPID NEXT #2 (-> C) <<<")
            viewModel.skipToNext()

            println(">>> FIRING RAPID NEXT #3 (-> D) <<<")
            viewModel.skipToNext()
        }

        Thread.sleep(1200) // Allow all in-flight resolutions and IPC to complete

        println("\n--- FINAL STATE AFTER RAPID SKIP X3 ---")
        println("queueEngine.currentTrack = ${queueEngine.currentTrack?.title} (ID: ${queueEngine.currentTrack?.id})")
        println("queueEngine.upcomingQueue = ${queueEngine.upcomingQueue.map { it.title }}")
        println("queueEngine.sessionBackStack = ${queueEngine.sessionBackStack.map { it.title }}")
        println("client.controllerState.currentMediaItem = ${client.controllerState.value.currentMediaItem?.mediaId}")

        val trackSeq = emissions.map { it.second }.filter { it.isNotBlank() }
        println("Complete rapid skip trackId sequence: $trackSeq")
    }
}
