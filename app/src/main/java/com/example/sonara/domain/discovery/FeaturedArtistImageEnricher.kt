package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Best-effort portrait enrichment for a selected shelf. It never changes membership,
 * ordering, identity, or editorial metadata, and cancellation remains structured.
 *
 * Independent lookups run CONCURRENTLY (FA-002). They were previously awaited one at a time, so
 * a slow or unreachable portrait endpoint cost one full read timeout *per* missing portrait, and
 * the whole shelf — including artists that already carry editorial metadata — was withheld for
 * the sum of them. Concurrency needs no explicit limiter or dispatcher: the only artists that can
 * lack a portrait are the locally-derived ones, already capped at
 * [FeaturedArtistSelector.MAX_PERSONAL_SLOTS], and the repository performs its own IO hop and
 * in-flight deduplication per name.
 *
 * [awaitAll] restores the input order regardless of completion order. Per-artist skip, failure
 * and cancellation semantics are deliberately unchanged: the body below is the same logic that
 * ran sequentially, so an ordinary failure still yields the artist unmodified (the tile renders
 * its monogram) and a [CancellationException] still propagates instead of becoming a miss.
 */
internal suspend fun enrichFeaturedArtistImages(
    artists: List<FeaturedArtist>,
    resolveImage: suspend (artistName: String) -> Result<String?>
): List<FeaturedArtist> = coroutineScope {
    artists.map { artist ->
        async {
            if (!artist.browseId.isNullOrBlank() || !artist.imageUrl.isNullOrBlank()) {
                artist
            } else {
                val imageUrl = try {
                    resolveImage(artist.name).fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            null
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!imageUrl.isNullOrBlank()) artist.copy(imageUrl = imageUrl) else artist
            }
        }
    }.awaitAll()
}
