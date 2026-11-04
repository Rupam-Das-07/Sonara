package com.example.sonara.feature.video

/**
 * Playback states exposed by the official YouTube IFrame player.
 *
 * UNKNOWN is intentional: the controller never invents a state before YouTube reports one.
 */
enum class YouTubePlaybackState {
    UNKNOWN,
    PAUSED,
    PLAYING,
    BUFFERING,
    ENDED
}

enum class YouTubePlayerLifecycle {
    NOT_READY,
    READY,
    DISPOSED
}

data class YouTubePlayerError(
    val code: Int? = null,
    val message: String? = null,
    val communication: Boolean = false
)

data class YouTubePlayerState(
    val videoId: String,
    val generation: Long,
    val lifecycle: YouTubePlayerLifecycle = YouTubePlayerLifecycle.NOT_READY,
    val playbackState: YouTubePlaybackState = YouTubePlaybackState.UNKNOWN,
    val currentPositionMs: Long? = null,
    val durationMs: Long? = null,
    val lastError: YouTubePlayerError? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false
)

sealed interface YouTubePlayerCommand {
    data object Play : YouTubePlayerCommand
    data object Pause : YouTubePlayerCommand
    data class SeekTo(val positionMs: Long) : YouTubePlayerCommand
    data class SetVolume(val volumePercent: Int) : YouTubePlayerCommand
    data class SetMuted(val muted: Boolean) : YouTubePlayerCommand
}

interface YouTubePlayerTransport {
    /** Returns true when the command was accepted for dispatch to the WebView. */
    fun dispatch(command: YouTubePlayerCommand): Boolean

    fun dispose()
}

enum class YouTubeCommandQueueReason {
    NOT_READY,
    TRANSPORT_UNAVAILABLE
}

sealed interface YouTubeCommandResult {
    data object Sent : YouTubeCommandResult
    data class Queued(val reason: YouTubeCommandQueueReason) : YouTubeCommandResult
    data class Rejected(val reason: String) : YouTubeCommandResult
    data object IgnoredAfterDisposal : YouTubeCommandResult
}

sealed interface YouTubeMessageHandlingResult {
    data class Accepted(val message: YouTubePlayerMessage) : YouTubeMessageHandlingResult
    data class Rejected(val reason: String) : YouTubeMessageHandlingResult
    data object IgnoredStale : YouTubeMessageHandlingResult
    data object IgnoredBeforeReady : YouTubeMessageHandlingResult
    data object IgnoredAfterDisposal : YouTubeMessageHandlingResult
}

sealed interface YouTubePlayerMessage {
    val videoId: String
    val generation: Long

    data class Ready(
        override val videoId: String,
        override val generation: Long,
        val durationMs: Long?,
        val isLive: Boolean = false
    ) : YouTubePlayerMessage

    data class StateChanged(
        override val videoId: String,
        override val generation: Long,
        val state: YouTubePlaybackState
    ) : YouTubePlayerMessage

    data class Position(
        override val videoId: String,
        override val generation: Long,
        val currentPositionMs: Long,
        val durationMs: Long?,
        val isLive: Boolean = false
    ) : YouTubePlayerMessage

    data class Error(
        override val videoId: String,
        override val generation: Long,
        val code: Int?,
        val message: String?
    ) : YouTubePlayerMessage
}

/**
 * Transport-independent controller for one active YouTube player instance.
 *
 * Commands issued before readiness are coalesced by intent and dispatched after the single ready
 * event. A command result only describes dispatch/queueing; playback confirmation arrives later
 * through [handleMessage] as a state or position event.
 */
class YouTubePlayerController(
    videoId: String,
    generation: Long,
    private val expectedOrigin: String,
    initialPositionMs: Long = 0L,
    private val onStateChanged: (YouTubePlayerState) -> Unit = {}
) {
    private var activeVideoId = videoId
    private var activeGeneration = generation
    private var ready = false
    private var disposed = false
    private var transport: YouTubePlayerTransport? = null
    private val pendingCommands = ArrayDeque<YouTubePlayerCommand>()

    var state: YouTubePlayerState = YouTubePlayerState(
        videoId = videoId,
        generation = generation,
        currentPositionMs = initialPositionMs.coerceAtLeast(0L).takeIf { it > 0L }
    )
        private set

    fun attachTransport(newTransport: YouTubePlayerTransport) {
        if (disposed) {
            newTransport.dispose()
            return
        }
        transport?.dispose()
        transport = newTransport
        flushPendingCommands()
    }

    fun replaceVideo(videoId: String, generation: Long, initialPositionMs: Long = 0L) {
        if (disposed) return
        pendingCommands.clear()
        activeVideoId = videoId
        activeGeneration = generation
        ready = false
        publish(
            YouTubePlayerState(
                videoId = videoId,
                generation = generation,
                currentPositionMs = initialPositionMs.coerceAtLeast(0L).takeIf { it > 0L }
            )
        )
    }

    fun play(): YouTubeCommandResult = issue(YouTubePlayerCommand.Play)

    fun pause(): YouTubeCommandResult = issue(YouTubePlayerCommand.Pause)

    fun seekTo(positionMs: Long): YouTubeCommandResult {
        if (positionMs < 0L) return YouTubeCommandResult.Rejected("Position cannot be negative")
        val durationMs = state.durationMs
        if (!state.isLive && durationMs != null && positionMs > durationMs) {
            return YouTubeCommandResult.Rejected("Position cannot exceed the known duration")
        }
        return issue(YouTubePlayerCommand.SeekTo(positionMs))
    }

    fun setVolume(volume: Float): YouTubeCommandResult {
        val clampedPercent = (volume.coerceIn(0f, 1f) * 100).toInt()
        return issue(YouTubePlayerCommand.SetVolume(clampedPercent))
    }

    fun setMuted(muted: Boolean): YouTubeCommandResult = issue(YouTubePlayerCommand.SetMuted(muted))

    fun handleMessage(
        rawMessage: String,
        sourceOrigin: String,
        isMainFrame: Boolean
    ): YouTubeMessageHandlingResult {
        if (disposed) return YouTubeMessageHandlingResult.IgnoredAfterDisposal
        if (!isMainFrame) return YouTubeMessageHandlingResult.Rejected("Message was not from the main frame")
        if (sourceOrigin != expectedOrigin) {
            return YouTubeMessageHandlingResult.Rejected("Unexpected message origin")
        }

        val parsed = YouTubePlayerMessageParser.parse(rawMessage)
        if (parsed is YouTubePlayerParseResult.Failure) {
            publishError(YouTubePlayerError(message = parsed.reason, communication = true))
            return YouTubeMessageHandlingResult.Rejected(parsed.reason)
        }
        val message = (parsed as YouTubePlayerParseResult.Success).message
        if (message.videoId != activeVideoId || message.generation != activeGeneration) {
            return YouTubeMessageHandlingResult.IgnoredStale
        }

        return when (message) {
            is YouTubePlayerMessage.Ready -> {
                val wasReady = ready
                ready = true
                publish(
                    state.copy(
                        lifecycle = YouTubePlayerLifecycle.READY,
                        durationMs = if (message.isLive) null else (message.durationMs ?: state.durationMs),
                        isLive = message.isLive,
                        lastError = null
                    )
                )
                if (!wasReady) flushPendingCommands()
                YouTubeMessageHandlingResult.Accepted(message)
            }

            is YouTubePlayerMessage.StateChanged -> {
                if (!ready) return YouTubeMessageHandlingResult.IgnoredBeforeReady
                publish(state.copy(playbackState = message.state, lastError = null))
                YouTubeMessageHandlingResult.Accepted(message)
            }

            is YouTubePlayerMessage.Position -> {
                if (!ready) return YouTubeMessageHandlingResult.IgnoredBeforeReady
                publish(
                    state.copy(
                        currentPositionMs = message.currentPositionMs,
                        durationMs = if (message.isLive) null else (message.durationMs ?: state.durationMs),
                        isLive = message.isLive,
                        lastError = null
                    )
                )
                YouTubeMessageHandlingResult.Accepted(message)
            }

            is YouTubePlayerMessage.Error -> {
                publishError(YouTubePlayerError(code = message.code, message = message.message))
                YouTubeMessageHandlingResult.Accepted(message)
            }
        }
    }

    fun reportCommunicationFailure(message: String): Boolean {
        if (disposed) return false
        publishError(YouTubePlayerError(message = message, communication = true))
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        ready = false
        pendingCommands.clear()
        transport?.dispose()
        transport = null
        publish(state.copy(lifecycle = YouTubePlayerLifecycle.DISPOSED))
    }

    private fun issue(command: YouTubePlayerCommand): YouTubeCommandResult {
        if (disposed) return YouTubeCommandResult.IgnoredAfterDisposal

        coalescePending(command)
        if (!ready) return YouTubeCommandResult.Queued(YouTubeCommandQueueReason.NOT_READY)

        val currentTransport = transport
            ?: return YouTubeCommandResult.Queued(YouTubeCommandQueueReason.TRANSPORT_UNAVAILABLE)
        pendingCommands.remove(command)
        return if (currentTransport.dispatch(command)) {
            YouTubeCommandResult.Sent
        } else {
            coalescePending(command)
            YouTubeCommandResult.Queued(YouTubeCommandQueueReason.TRANSPORT_UNAVAILABLE)
        }
    }

    private fun coalescePending(command: YouTubePlayerCommand) {
        when (command) {
            YouTubePlayerCommand.Play,
            YouTubePlayerCommand.Pause -> pendingCommands.removeAll {
                it is YouTubePlayerCommand.Play || it is YouTubePlayerCommand.Pause
            }
            is YouTubePlayerCommand.SeekTo -> pendingCommands.removeAll {
                it is YouTubePlayerCommand.SeekTo
            }
            is YouTubePlayerCommand.SetVolume -> pendingCommands.removeAll {
                it is YouTubePlayerCommand.SetVolume
            }
            is YouTubePlayerCommand.SetMuted -> pendingCommands.removeAll {
                it is YouTubePlayerCommand.SetMuted
            }
        }
        pendingCommands.addLast(command)
    }

    private fun flushPendingCommands() {
        if (disposed || !ready) return
        val currentTransport = transport ?: return
        while (pendingCommands.isNotEmpty()) {
            val command = pendingCommands.first()
            if (!currentTransport.dispatch(command)) return
            pendingCommands.removeFirst()
        }
    }

    private fun publishError(error: YouTubePlayerError) {
        publish(state.copy(lastError = error))
    }

    private fun publish(newState: YouTubePlayerState) {
        state = newState
        onStateChanged(newState)
    }
}

sealed interface YouTubePlayerParseResult {
    data class Success(val message: YouTubePlayerMessage) : YouTubePlayerParseResult
    data class Failure(val reason: String) : YouTubePlayerParseResult
}

/** Strict parser for the small JSON protocol emitted by the controlled player document. */
object YouTubePlayerMessageParser {
    fun parse(rawMessage: String): YouTubePlayerParseResult = runCatching {
        val values = StrictJsonObjectParser(rawMessage).parse()
        val type = values.string("type") ?: return failure("Missing string field: type")
        val videoId = values.string("videoId") ?: return failure("Missing string field: videoId")
        val generation = values.long("generation") ?: return failure("Missing integer field: generation")
        if (generation < 0L) return failure("generation must be non-negative")
        if (!isValidVideoId(videoId)) return failure("Invalid videoId")

        when (type) {
            "ready" -> {
                values.requireKeysWithOptional(
                    required = setOf("type", "videoId", "generation", "durationMs"),
                    optional = setOf("isLive")
                )
                val duration = values.optionalNonNegativeLong("durationMs")
                val isLive = values.optionalBoolean("isLive") ?: false
                YouTubePlayerParseResult.Success(
                    YouTubePlayerMessage.Ready(videoId, generation, duration, isLive)
                )
            }
            "state" -> {
                values.requireKeys("type", "videoId", "generation", "state")
                val state = when (values.string("state")) {
                    "PAUSED" -> YouTubePlaybackState.PAUSED
                    "PLAYING" -> YouTubePlaybackState.PLAYING
                    "BUFFERING" -> YouTubePlaybackState.BUFFERING
                    "ENDED" -> YouTubePlaybackState.ENDED
                    else -> return failure("Unknown player state")
                }
                YouTubePlayerParseResult.Success(
                    YouTubePlayerMessage.StateChanged(videoId, generation, state)
                )
            }
            "position" -> {
                values.requireKeysWithOptional(
                    required = setOf("type", "videoId", "generation", "currentPositionMs", "durationMs"),
                    optional = setOf("isLive")
                )
                val position = values.long("currentPositionMs")
                    ?: return failure("currentPositionMs must be an integer")
                if (position < 0L) return failure("currentPositionMs must be non-negative")
                val isLive = values.optionalBoolean("isLive") ?: false
                val duration = values.optionalNonNegativeLong("durationMs")
                if (!isLive && duration != null && position > duration) {
                    return failure("currentPositionMs cannot exceed durationMs")
                }
                YouTubePlayerParseResult.Success(
                    YouTubePlayerMessage.Position(videoId, generation, position, duration, isLive)
                )
            }
            "error" -> {
                values.requireKeys("type", "videoId", "generation", "code", "message")
                val code = values.optionalInt("code")
                if (code != null && code !in 0..1000) return failure("Invalid player error code")
                val message = values.optionalString("message")
                YouTubePlayerParseResult.Success(
                    YouTubePlayerMessage.Error(videoId, generation, code, message)
                )
            }
            else -> failure("Unknown message type")
        }
    }.getOrElse { error ->
        YouTubePlayerParseResult.Failure(error.message ?: "Malformed player message")
    }

    private fun failure(reason: String): YouTubePlayerParseResult.Failure =
        YouTubePlayerParseResult.Failure(reason)

    private fun isValidVideoId(value: String): Boolean =
        value.length == 11 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

private class StrictJsonObjectParser(private val source: String) {
    private var index = 0

    fun parse(): JsonObject {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (peek() == '}') {
            index++
            finish()
            return JsonObject(values)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            if (values.containsKey(key)) error("Duplicate field: $key")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    finish()
                    return JsonObject(values)
                }
                else -> error("Expected ',' or '}'")
            }
        }
    }

    private fun parseValue(): JsonValue {
        return when (val character = peek()) {
            '"' -> JsonValue.StringValue(parseString())
            'n' -> {
                expectLiteral("null")
                JsonValue.NullValue
            }
            't' -> {
                expectLiteral("true")
                JsonValue.BooleanValue(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonValue.BooleanValue(false)
            }
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
            '[', '{' -> error("Nested JSON values are not allowed")
            else -> error("Unexpected JSON value: $character")
        }
    }

    private fun parseNumber(): String {
        val start = index
        if (peek() == '-') index++
        if (peek() == '0') {
            index++
        } else {
            require(peek()?.let { it in '1'..'9' } == true) { "Invalid number" }
            while (peek()?.let { it in '0'..'9' } == true) index++
        }
        if (peek() == '.') {
            index++
            require(peek()?.let { it in '0'..'9' } == true) { "Invalid number fraction" }
            while (peek()?.let { it in '0'..'9' } == true) index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            require(peek()?.let { it in '0'..'9' } == true) { "Invalid number exponent" }
            while (peek()?.let { it in '0'..'9' } == true) index++
        }
        return source.substring(start, index)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = peek() ?: error("Unterminated string")
            index++
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    when (val escaped = peek() ?: error("Unterminated escape")) {
                        '"', '\\', '/' -> {
                            result.append(escaped)
                            index++
                        }
                        'b' -> {
                            result.append('\b')
                            index++
                        }
                        'f' -> {
                            result.append('\u000C')
                            index++
                        }
                        'n' -> {
                            result.append('\n')
                            index++
                        }
                        'r' -> {
                            result.append('\r')
                            index++
                        }
                        't' -> {
                            result.append('\t')
                            index++
                        }
                        'u' -> {
                            index++
                            val hex = source.substring(index, index + 4)
                            require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                "Invalid unicode escape"
                            }
                            result.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> error("Invalid escape")
                    }
                }
                else -> {
                    require(character.code >= 0x20) { "Control character in string" }
                    result.append(character)
                }
            }
        }
    }

    private fun expectLiteral(literal: String) {
        require(source.startsWith(literal, index)) { "Expected $literal" }
        index += literal.length
    }

    private fun expect(expected: Char) {
        require(peek() == expected) { "Expected '$expected'" }
        index++
    }

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) index++
    }

    private fun finish() {
        skipWhitespace()
        require(index == source.length) { "Trailing JSON data" }
    }

    private fun peek(): Char? = source.getOrNull(index)
}

private sealed interface JsonValue {
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private class JsonObject(private val values: Map<String, JsonValue>) {
    fun string(key: String): String? = (values[key] as? JsonValue.StringValue)?.value

    fun optionalString(key: String): String? {
        val value = values[key] ?: return null
        return when (value) {
            JsonValue.NullValue -> null
            is JsonValue.StringValue -> value.value
            else -> error("$key must be a string or null")
        }
    }

    fun long(key: String): Long? = (values[key] as? JsonValue.NumberValue)?.value?.toLongOrNull()

    fun optionalInt(key: String): Int? {
        val value = values[key] ?: return null
        return when (value) {
            JsonValue.NullValue -> null
            is JsonValue.NumberValue -> value.value.toIntOrNull()
                ?: error("$key must be an integer")
            else -> error("$key must be an integer or null")
        }
    }

    fun optionalNonNegativeLong(key: String): Long? {
        val value = values[key] ?: return null
        return when (value) {
            JsonValue.NullValue -> null
            is JsonValue.NumberValue -> value.value.toLongOrNull()?.also {
                require(it >= 0L) { "$key must be non-negative" }
            } ?: error("$key must be an integer")
            else -> error("$key must be an integer or null")
        }
    }

    fun optionalBoolean(key: String): Boolean? {
        val value = values[key] ?: return null
        return when (value) {
            JsonValue.NullValue -> null
            is JsonValue.BooleanValue -> value.value
            else -> error("$key must be a boolean or null")
        }
    }

    fun requireKeys(vararg allowed: String) {
        val allowedSet = allowed.toSet()
        val unexpected = values.keys.firstOrNull { it !in allowedSet }
        require(unexpected == null) { "Unexpected field: $unexpected" }
        allowed.forEach { key -> require(values.containsKey(key)) { "Missing field: $key" } }
    }

    fun requireKeysWithOptional(required: Set<String>, optional: Set<String> = emptySet()) {
        val allowedSet = required + optional
        val unexpected = values.keys.firstOrNull { it !in allowedSet }
        require(unexpected == null) { "Unexpected field: $unexpected" }
        required.forEach { key -> require(values.containsKey(key)) { "Missing field: $key" } }
    }
}
