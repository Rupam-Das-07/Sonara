package com.example.sonara.data.remote.extractor

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

internal fun extractionInfo(
    includeStreams: Boolean = true,
    videoId: String = "dQw4w9WgXcQ"
): StreamInfo = StreamInfo(
    0,
    "https://www.youtube.com/watch?v=$videoId",
    "https://youtu.be/$videoId",
    StreamType.VIDEO_STREAM,
    videoId,
    "Synthetic title",
    0
).apply {
    uploaderName = "Synthetic uploader"
    duration = 213
    thumbnails = listOf(
        Image(
            "https://example.invalid/thumb.jpg",
            360,
            640,
            Image.ResolutionLevel.MEDIUM
        )
    )
    if (includeStreams) {
        videoStreams = listOf(
            VideoStream.Builder()
                .setId("18")
                .setContent("https://example.invalid/video", true)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setResolution("360p")
                .build()
        )
        audioStreams = listOf(
            AudioStream.Builder()
                .setId("140")
                .setContent("https://example.invalid/audio", true)
                .setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.DASH)
                .setAverageBitrate(128_000)
                .build()
        )
    }
}

internal class NoOpDownloader : Downloader() {
    override fun execute(request: Request): Response =
        Response(200, "OK", emptyMap(), "", request.url())
}

internal fun validVideoStatus(videoId: String = "dQw4w9WgXcQ") =
    com.example.sonara.domain.model.video.VideoStatus(
        videoId,
        com.example.sonara.domain.model.video.MadeForKidsStatus.NOT_MADE_FOR_KIDS,
        com.example.sonara.domain.model.video.EmbeddabilityStatus.EMBEDDABLE
    )
