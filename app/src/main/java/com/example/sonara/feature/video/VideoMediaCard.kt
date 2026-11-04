package com.example.sonara.feature.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.sonara.BuildConfig
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.video.VideoEmbeddingDenialReason
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.evaluateVideoEmbeddingEligibility
import kotlinx.coroutines.delay

private val playerDocumentOrigin: String
    get() = "https://${BuildConfig.APPLICATION_ID}"

private sealed interface VideoMediaLoadState {
    data object Loading : VideoMediaLoadState
    data class Ready(val status: VideoStatus) : VideoMediaLoadState
    data class Denied(val message: String) : VideoMediaLoadState
}

/**
 * Reusable official YouTube media surface for the expanded-player media slot.
 *
 * This component owns verification, the official iframe player, its controller boundary, and
 * WebView cleanup. The native bridge is an origin-restricted WebMessageListener rather than a
 * generic JavaScript interface.
 */
@Composable
fun VideoMediaCard(
    videoId: String,
    backendClient: SonaraBackendClient,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    initialPositionMs: Long = 0L,
    autoPlay: Boolean = true,
    onCanvasTap: (() -> Unit)? = null,
    onControllerAvailable: (YouTubePlayerController?) -> Unit = {},
    onPlayerStateChanged: (YouTubePlayerState) -> Unit = {}
) {
    val colors = SonaraTheme.colors
    var loadState by remember(videoId) {
        mutableStateOf<VideoMediaLoadState>(VideoMediaLoadState.Loading)
    }
    var retryToken by remember(videoId) { mutableIntStateOf(0) }

    LaunchedEffect(videoId, retryToken) {
        loadState = VideoMediaLoadState.Loading
        val result = backendClient.getVideoStatus(videoId)
        val eligibility = evaluateVideoEmbeddingEligibility(result)
        loadState = if (eligibility.isAllowed) {
            VideoMediaLoadState.Ready(result.getOrThrow())
        } else {
            VideoMediaLoadState.Denied(videoDenialMessage(eligibility.denialReason))
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (val state = loadState) {
            VideoMediaLoadState.Loading -> CircularProgressIndicator(color = colors.accent)
            is VideoMediaLoadState.Denied -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.message,
                        style = SonaraTheme.typography.body,
                        color = colors.primaryText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.TextButton(onClick = { retryToken++ }) {
                        Text(text = "Retry", color = colors.accent)
                    }
                }
            }
            is VideoMediaLoadState.Ready -> YouTubeMediaWebView(
                videoId = state.status.videoId,
                initialPositionMs = initialPositionMs,
                autoPlay = autoPlay,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                onCanvasTap = onCanvasTap,
                onControllerAvailable = onControllerAvailable,
                onPlayerStateChanged = onPlayerStateChanged
            )
        }
    }
}

private fun videoDenialMessage(reason: VideoEmbeddingDenialReason?): String = when (reason) {
    VideoEmbeddingDenialReason.MADE_FOR_KIDS_REQUIRES_POLICY_APPROVAL ->
        "This video is marked Made for Kids and is unavailable until Sonara's child-directed playback policy is approved."
    VideoEmbeddingDenialReason.NOT_EMBEDDABLE ->
        "This video is not available for embedded playback."
    VideoEmbeddingDenialReason.UNKNOWN_MADE_FOR_KIDS_STATUS,
    VideoEmbeddingDenialReason.UNKNOWN_EMBEDDABILITY_STATUS,
    VideoEmbeddingDenialReason.INVALID_VIDEO_ID,
    VideoEmbeddingDenialReason.VERIFICATION_FAILED,
    null ->
        "This video could not be verified for embedded playback."
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeMediaWebView(
    videoId: String,
    initialPositionMs: Long,
    autoPlay: Boolean,
    modifier: Modifier,
    cornerRadius: Dp,
    onCanvasTap: (() -> Unit)? = null,
    onControllerAvailable: (YouTubePlayerController?) -> Unit,
    onPlayerStateChanged: (YouTubePlayerState) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webViewError by remember(videoId) { mutableStateOf(false) }
    var generation by remember(videoId) { mutableLongStateOf(1L) }
    var hasStartedPlaying by remember(videoId, generation) { mutableStateOf(false) }
    var loadTimedOut by remember(videoId, generation) { mutableStateOf(false) }
    val initialPos = remember(videoId) { initialPositionMs.coerceAtLeast(0L) }
    val stateHolder = remember(videoId) {
        mutableStateOf(
            YouTubePlayerState(
                videoId = videoId,
                generation = generation,
                currentPositionMs = initialPos.takeIf { it > 0L }
            )
        )
    }
    val controller = remember(videoId) {
        YouTubePlayerController(
            videoId = videoId,
            generation = generation,
            expectedOrigin = playerDocumentOrigin,
            initialPositionMs = initialPos,
            onStateChanged = {
                stateHolder.value = it
                if (it.playbackState == YouTubePlaybackState.PLAYING) {
                    hasStartedPlaying = true
                }
                onPlayerStateChanged(it)
            }
        )
    }

    LaunchedEffect(controller, autoPlay) {
        if (autoPlay) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(videoId, generation) {
        loadTimedOut = false
        delay(15_000L)
        if (!hasStartedPlaying) {
            loadTimedOut = true
        }
    }

    val webView = remember(videoId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webViewError = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        webViewError = true
                        controller.reportCommunicationFailure(error?.description?.toString().orEmpty())
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
    }
    val transport = remember(webView) { WebViewYouTubePlayerTransport() }

    DisposableEffect(webView, lifecycleOwner, controller, transport) {
        onControllerAvailable(controller)
        onPlayerStateChanged(controller.state)
        var wasPlayingBeforePause = false
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlayingBeforePause = controller.state.playbackState == YouTubePlaybackState.PLAYING ||
                        controller.state.playbackState == YouTubePlaybackState.BUFFERING
                    webView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    if (wasPlayingBeforePause) {
                        controller.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        controller.attachTransport(transport)

        val messageListenerSupported = WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER
        )
        if (messageListenerSupported) {
            WebViewCompat.addWebMessageListener(
                webView,
                PLAYER_BRIDGE_NAME,
                setOf(playerDocumentOrigin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        transport.bindReplyProxy(replyProxy)
                        controller.handleMessage(
                            rawMessage = message.data ?: "",
                            sourceOrigin = sourceOrigin.toString(),
                            isMainFrame = isMainFrame
                        )
                    }
                }
            )
        } else {
            controller.reportCommunicationFailure("WebMessageListener is not supported")
        }
        webView.loadDataWithBaseURL(
            playerDocumentOrigin,
            youtubeEmbedDocument(videoId, stateHolder.value.generation, initialPos, autoPlay),
            "text/html",
            "UTF-8",
            null
        )

        onDispose {
            onControllerAvailable(null)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if (messageListenerSupported) {
                WebViewCompat.removeWebMessageListener(webView, PLAYER_BRIDGE_NAME)
            }
            controller.dispose()
            transport.dispose()
            webView.stopLoading()
            webView.onPause()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (onCanvasTap != null) {
                    onCanvasTap()
                } else if (hasStartedPlaying && stateHolder.value.playbackState != YouTubePlaybackState.UNKNOWN) {
                    if (stateHolder.value.playbackState == YouTubePlaybackState.PLAYING) {
                        controller.pause()
                    } else {
                        controller.play()
                    }
                }
            }
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = {}
        )

        // Loading Mask: Solid background with indicator covers the WebView until the first frame is playing,
        // completely suppressing YouTube's unstarted poster, native title header, and center play button.
        if (!hasStartedPlaying && !webViewError && stateHolder.value.lastError == null && !loadTimedOut) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = SonaraTheme.colors.accent)
            }
        }

        val errorMessage = when {
            webViewError -> "The YouTube player could not be loaded."
            stateHolder.value.lastError != null -> {
                val code = stateHolder.value.lastError?.code
                if (code == 100 || code == 101 || code == 150) {
                    "This video is restricted and cannot be played in the embedded player."
                } else {
                    "YouTube player encountered an error (code $code)."
                }
            }
            loadTimedOut && !hasStartedPlaying -> "Playback timed out while initializing the player."
            else -> null
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SonaraTheme.colors.background.copy(alpha = 0.96f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    style = SonaraTheme.typography.body,
                    color = SonaraTheme.colors.primaryText
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        webViewError = false
                        loadTimedOut = false
                        hasStartedPlaying = false
                        generation += 1L
                        val retryPos = stateHolder.value.currentPositionMs ?: initialPos
                        controller.replaceVideo(videoId, generation, initialPositionMs = retryPos)
                        webView.loadDataWithBaseURL(
                            playerDocumentOrigin,
                            youtubeEmbedDocument(videoId, generation, initialPositionMs = retryPos, autoPlay = autoPlay),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                ) {
                    Text(text = "Retry", color = SonaraTheme.colors.accent)
                }
            }
        }
    }
}

private const val PLAYER_BRIDGE_NAME = "sonaraPlayerBridge"

private class WebViewYouTubePlayerTransport : YouTubePlayerTransport {
    private var replyProxy: JavaScriptReplyProxy? = null
    private var disposed = false

    fun bindReplyProxy(proxy: JavaScriptReplyProxy) {
        if (!disposed) replyProxy = proxy
    }

    override fun dispatch(command: YouTubePlayerCommand): Boolean {
        if (disposed) return false
        val proxy = replyProxy ?: return false
        val payload = when (command) {
            YouTubePlayerCommand.Play -> "{\"type\":\"play\"}"
            YouTubePlayerCommand.Pause -> "{\"type\":\"pause\"}"
            is YouTubePlayerCommand.SeekTo ->
                "{\"type\":\"seek\",\"positionMs\":${command.positionMs}}"
            is YouTubePlayerCommand.SetVolume ->
                "{\"type\":\"volume\",\"volume\":${command.volumePercent}}"
            is YouTubePlayerCommand.SetMuted ->
                "{\"type\":\"mute\",\"muted\":${command.muted}}"
        }
        proxy.postMessage(payload)
        return true
    }

    override fun dispose() {
        disposed = true
        replyProxy = null
    }
}

internal fun youtubeEmbedDocument(
    videoId: String,
    generation: Long,
    initialPositionMs: Long = 0L,
    autoPlay: Boolean = true
): String {
    val safeVideoId = videoId.takeIf {
        it.length == 11 && it.all { character -> character.isLetterOrDigit() || character == '-' || character == '_' }
    } ?: return """
        <!doctype html>
        <html><body style="background:#000;color:#fff">Invalid YouTube video ID</body></html>
    """.trimIndent()
    val safeInitialPositionMs = initialPositionMs.coerceAtLeast(0L)
    val startSeconds = safeInitialPositionMs / 1000L
    val startParam = if (startSeconds > 0L) "&start=$startSeconds" else ""
    val autoPlayParam = if (autoPlay) "&autoplay=1" else "&autoplay=0"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <style>
            html, body { margin: 0; padding: 0; height: 100%; background: #000; overflow: hidden; }
            .player { position: relative; width: 100%; height: 100%; }
            iframe {
              position: absolute;
              inset: 0;
              width: 100%;
              height: 100%;
              border: 0;
              pointer-events: none;
            }
            #loading-scrim {
              position: absolute;
              inset: 0;
              background: #000;
              z-index: 10;
              pointer-events: none;
              transition: opacity 0.25s ease-out;
            }
            #loading-scrim.hidden {
              opacity: 0;
              display: none;
            }
          </style>
        </head>
        <body>
          <div class="player">
            <iframe
              id="player"
              src="https://www.youtube.com/embed/$safeVideoId?enablejsapi=1${autoPlayParam}&controls=0&fs=0&disablekb=1&playsinline=1&rel=0&iv_load_policy=3${startParam}&origin=$playerDocumentOrigin"
              title="YouTube video player"
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
              referrerpolicy="origin"
              allowfullscreen>
            </iframe>
            <div id="loading-scrim"></div>
          </div>
          <script>
            var player = null;
            var positionTimer = null;
            var playerGeneration = $generation;
            var activeVideoId = ${jsonStringLiteral(safeVideoId)};
            var initialPositionMs = $safeInitialPositionMs;
            var initialSeekDone = false;

            function emit(payload) {
              if (window.$PLAYER_BRIDGE_NAME && window.$PLAYER_BRIDGE_NAME.postMessage) {
                window.$PLAYER_BRIDGE_NAME.postMessage(JSON.stringify(payload));
              }
            }

            function finiteNumber(value) {
              return typeof value === 'number' && isFinite(value);
            }

            function isLiveBroadcast() {
              try {
                if (player && typeof player.getVideoData === 'function') {
                  var data = player.getVideoData();
                  return !!(data && data.isLive);
                }
              } catch (e) {}
              return false;
            }

            function durationMs() {
              if (isLiveBroadcast()) return null;
              if (!player || !finiteNumber(player.getDuration())) return null;
              var duration = Math.round(player.getDuration() * 1000);
              return duration > 0 ? duration : null;
            }

            function positionMs() {
              if (!player || !finiteNumber(player.getCurrentTime())) return null;
              var position = Math.round(player.getCurrentTime() * 1000);
              return position >= 0 ? position : null;
            }

            function emitPosition() {
              var position = positionMs();
              if (position === null) return;
              var live = isLiveBroadcast();
              emit({
                type: 'position',
                videoId: activeVideoId,
                generation: playerGeneration,
                currentPositionMs: position,
                durationMs: live ? null : durationMs(),
                isLive: live
              });
            }

            function stopPositionTimer() {
              if (positionTimer !== null) {
                clearInterval(positionTimer);
                positionTimer = null;
              }
            }

            function startPositionTimer() {
              // The IFrame API exposes getCurrentTime(), not a continuous position event. Keep the
              // fallback bounded to one timer and run it only while the player is active.
              stopPositionTimer();
              positionTimer = setInterval(emitPosition, 500);
              emitPosition();
            }

            function onPlayerReady() {
              if (!initialSeekDone && initialPositionMs > 0) {
                initialSeekDone = true;
                try {
                  player.seekTo(initialPositionMs / 1000, true);
                } catch (e) {}
              }
              var live = isLiveBroadcast();
              emit({
                type: 'ready',
                videoId: activeVideoId,
                generation: playerGeneration,
                durationMs: live ? null : durationMs(),
                isLive: live
              });
              emitPosition();
            }

            function onPlayerStateChange(event) {
              var states = { 0: 'ENDED', 1: 'PLAYING', 2: 'PAUSED', 3: 'BUFFERING' };
              var state = states[event.data];
              if (event.data === 1) {
                var scrim = document.getElementById('loading-scrim');
                if (scrim) {
                  scrim.className = 'hidden';
                }
              }
              if (!state) return;
              emit({
                type: 'state',
                videoId: activeVideoId,
                generation: playerGeneration,
                state: state
              });
              if (state === 'PLAYING' || state === 'BUFFERING') {
                startPositionTimer();
              } else {
                stopPositionTimer();
                emitPosition();
              }
            }

            function onPlayerError(event) {
              var scrim = document.getElementById('loading-scrim');
              if (scrim) {
                scrim.className = 'hidden';
              }
              emit({
                type: 'error',
                videoId: activeVideoId,
                generation: playerGeneration,
                code: finiteNumber(event.data) ? event.data : null,
                message: null
              });
            }

            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                events: {
                  onReady: onPlayerReady,
                  onStateChange: onPlayerStateChange,
                  onError: onPlayerError
                }
              });
            }

            window.$PLAYER_BRIDGE_NAME.onmessage = function(event) {
              var command;
              try {
                command = JSON.parse(event.data);
              } catch (error) {
                return;
              }
              if (!command || typeof command.type !== 'string' || !player) return;
              if (command.type === 'play') {
                try {
                  player.playVideo();
                } catch (e) {
                  emit({
                    type: 'error',
                    videoId: activeVideoId,
                    generation: playerGeneration,
                    code: null,
                    message: e && e.message ? e.message : 'playVideo failed'
                  });
                }
              } else if (command.type === 'pause') {
                try {
                  player.pauseVideo();
                } catch (e) {}
              } else if (
                command.type === 'seek' &&
                finiteNumber(command.positionMs) &&
                command.positionMs >= 0
              ) {
                var targetMs = Math.round(command.positionMs);
                player.seekTo(targetMs / 1000, true);
                emit({
                  type: 'position',
                  videoId: activeVideoId,
                  generation: playerGeneration,
                  currentPositionMs: targetMs,
                  durationMs: durationMs()
                });
              } else if (
                command.type === 'volume' &&
                finiteNumber(command.volume)
              ) {
                var vol = Math.min(100, Math.max(0, Math.round(command.volume)));
                player.setVolume(vol);
                if (vol > 0 && typeof player.isMuted === 'function' && player.isMuted()) {
                  player.unMute();
                }
              } else if (
                command.type === 'mute' &&
                typeof command.muted === 'boolean'
              ) {
                if (command.muted) {
                  player.mute();
                } else {
                  player.unMute();
                }
              }
            };

            window.addEventListener('beforeunload', stopPositionTimer);
          </script>
          <script src="https://www.youtube.com/iframe_api"></script>
        </body>
        </html>
    """.trimIndent()
}

private fun jsonStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
