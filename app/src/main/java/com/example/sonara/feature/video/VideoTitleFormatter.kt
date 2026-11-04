package com.example.sonara.feature.video

import com.example.sonara.core.util.MediaTitleFormatter

/** Compatibility facade for the shared pure title formatter. */
object VideoTitleFormatter {
    fun format(title: String, artist: String? = null): String =
        MediaTitleFormatter.format(title, artist)
}
