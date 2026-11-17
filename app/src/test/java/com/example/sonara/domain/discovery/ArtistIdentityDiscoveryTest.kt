package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistIdentityDiscoveryTest {
    private val now = 1_760_000_000_000L

    private fun plays(name: String, count: Int) = List(count) {
        HistoryItem(
            track = Track(id = "$name-$it", title = "Song", artist = name),
            listenedAt = now,
            // Phase 2D: identity cases presume eligible listening.
            sourceSection = PlaybackOrigin.SEARCH_SONGS
        )
    }

    private fun artist(id: String, name: String) = FeaturedArtist(
        id = id, name = name, genre = "Editorial", imageUrl = "portrait:$id", browseId = "browse:$id"
    )

    @Test
    fun `native editorial names match opaque IDs and retain every metadata field`() {
        val untouched = artist("opaque:0", "Other")
        val hindi = artist("opaque:1", "अरिजीत सिंह")
        val bengali = artist("opaque:2", "অরিজিৎ সিং")
        val roster = listOf(untouched, hindi, bengali)
        val history = plays(hindi.name, 3) + plays(bengali.name, 4)
        val expected = listOf(bengali, hindi, untouched)
        assertEquals(expected, ArtistAffinityCalculator.personalize(roster, history, now))
        assertEquals(expected, FeaturedArtistSelector.select(roster, history, now))
        val expanded = FeaturedArtistSelector.select(roster, history + plays("Personal", 3), now)
        assertEquals(listOf(bengali, hindi), expanded.take(2))
        assertEquals(roster.toSet(), expanded.filter { it.id.startsWith("opaque:") }.toSet())
        assertEquals(listOf(untouched, hindi, bengali), roster)
    }

    @Test
    fun `Hindi and Bengali can both enter as distinct personal artists`() {
        val history = plays("अरिजीत सिंह", 3) + plays("অরিজিৎ সিং", 3)
        val result = FeaturedArtistSelector.select(emptyList(), history, now)
        assertEquals(listOf("अरिजीत सिंह", "অরিজিৎ সিং"), result.map { it.name })
        assertEquals(listOf("अरिजीत-सिंह", "অরিজিৎ-সিং"), result.map { it.id })
        assertTrue(result.all { it.genre.isEmpty() && it.imageUrl == null && it.browseId == null })
    }

    @Test
    fun `canonical nukta equivalents combine while vowel and nukta differences stay separate`() {
        val history = plays("क़", 1) + plays("क़", 2) + plays("क", 3) + plays("कि", 3)
        val scores = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertEquals(setOf("क़", "क", "कि"), scores.keys)
        assertTrue(scores.values.all { it == 3f })
        val result = FeaturedArtistSelector.select(emptyList(), history, now)
        assertEquals(listOf("क़", "क", "कि"), result.map { it.name })
        assertEquals(3, result.map { it.id }.toSet().size)
    }

    @Test
    fun `lossy Latin slug collisions never combine affinity or membership thresholds`() {
        for ((left, right) in listOf("Beyoncé" to "Beyonce", "AC/DC" to "AC-DC", "AB" to "A-B")) {
            val thin = plays(left, 1) + plays(right, 1)
            assertTrue(ArtistAffinityCalculator.calculateAffinity(thin, now).isEmpty())
            assertTrue(FeaturedArtistSelector.select(emptyList(), thin, now).isEmpty())
            val belowMembership = plays(left, 2) + plays(right, 2)
            assertEquals(2, ArtistAffinityCalculator.calculateAffinity(belowMembership, now).size)
            assertTrue(FeaturedArtistSelector.select(emptyList(), belowMembership, now).isEmpty())

            val unplayed = artist(ArtistIdentity.slugify(left), left)
            val listened = artist("backend:right", right)
            val roster = listOf(unplayed, listened)
            assertEquals(listOf(listened, unplayed), ArtistAffinityCalculator.personalize(roster, plays(right, 3), now))
        }
    }

    @Test
    fun `Rahman aliases independently qualify then rank in no expansion and expanded paths`() {
        val rahman = artist("opaque:rahman", "A.R. Rahman")
        val other = artist("other", "Other")
        val roster = listOf(other, rahman)
        val belowAffinity = plays("AR Rahman", 1) + plays("A.R. Rahman", 1)
        assertTrue(ArtistAffinityCalculator.calculateAffinity(belowAffinity, now).isEmpty())
        assertEquals(roster, ArtistAffinityCalculator.personalize(roster, belowAffinity, now))

        val history = plays("AR Rahman", 2) + plays("A.R. Rahman", 2) + plays("Other", 3)
        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertEquals(setOf("ar-rahman", "a.r.-rahman", "other"), affinity.keys)
        assertEquals(4f, ArtistAffinityCalculator.lookupAffinity(affinity, "a-r-rahman")!!, 0f)
        assertEquals(listOf(rahman, other), FeaturedArtistSelector.select(roster, history, now))
        val expanded = FeaturedArtistSelector.select(roster, history + plays("Personal", 3), now)
        assertEquals(listOf("A.R. Rahman", "Other", "Personal"), expanded.map { it.name })

        val noMembership = FeaturedArtistSelector.select(emptyList(), history.take(4), now)
        assertTrue("two aliases with two events each cannot jointly pass membership", noMembership.isEmpty())
        val personal = FeaturedArtistSelector.select(emptyList(), plays("AR Rahman", 3) + plays("A.R. Rahman", 3), now)
        assertEquals(1, personal.size)
        assertEquals("ar-rahman", personal.single().id)
    }

    @Test
    fun `empty or invalid names never match including empty affinity map keys`() {
        for (invalid in listOf("", " ", "!!!", "\u093F", "A\u200BB")) {
            assertNull(ArtistAffinityCalculator.identityKey(invalid))
            assertNull(ArtistAffinityCalculator.lookupAffinity(mapOf("" to 50f, invalid to 20f), ""))
            assertNull(ArtistAffinityCalculator.lookupAffinity(mapOf("" to 50f), invalid))
            assertTrue(ArtistAffinityCalculator.calculateAffinity(plays(invalid, 5), now).isEmpty())
        }
        val nameless = artist("अरिजीत-सिंह", "")
        val blankId = artist("", "अरिजीत सिंह")
        val roster = listOf(nameless, blankId)
        assertEquals(listOf(blankId, nameless), FeaturedArtistSelector.select(roster, plays(blankId.name, 3), now))
        val onlyNameless = FeaturedArtistSelector.select(listOf(nameless), plays(blankId.name, 3), now)
        assertEquals(2, onlyNameless.size)
        assertEquals(nameless, onlyNameless.last())
        assertTrue(onlyNameless.first().id.startsWith("artist-name-"))
    }

    @Test
    fun `authoritative IDs are never treated as names`() {
        val misleading = artist("arijit-singh", "Unrelated")
        val genuine = artist("provider:123", "Arijit Singh")
        val result = FeaturedArtistSelector.select(listOf(misleading, genuine), plays("Arijit Singh", 3), now)
        assertEquals(listOf(genuine, misleading), result)
        assertNull(ArtistAffinityCalculator.lookupAffinity(mapOf("a-b" to 4f), "AB"))
        assertNull(ArtistAffinityCalculator.lookupAffinity(mapOf("ac/dc" to 4f), "AC-DC"))
    }

    @Test
    fun `ambiguous editorial names get no speculative score or personal duplicate`() {
        val other = artist("other", "Other")
        val first = artist("provider:first", "क़")
        val second = artist("provider:second", "क़")
        val roster = listOf(other, first, second)
        val history = plays("क़", 20)
        assertEquals(roster, ArtistAffinityCalculator.personalize(roster, history, now))
        assertEquals(roster, FeaturedArtistSelector.select(roster, history, now))
        val expanded = FeaturedArtistSelector.select(roster, history + plays("Personal", 3), now)
        assertEquals(listOf("Personal", "Other", "क़", "क़"), expanded.map { it.name })
        assertEquals(roster, expanded.drop(1))
    }

    @Test
    fun `colliding personal and editorial slugs get unique deterministic local IDs`() {
        for ((left, right) in listOf("Beyoncé" to "Beyonce", "AC/DC" to "AC-DC")) {
            val editorial = artist(ArtistIdentity.slugify(left), left)
            val history = plays(right, 3)
            val result = FeaturedArtistSelector.select(listOf(editorial), history, now)
            assertEquals(editorial, result.last())
            val local = result.first()
            assertEquals(right, local.name)
            assertTrue(local.id.startsWith("artist-name-"))
            assertEquals(2, result.map { it.id }.toSet().size)
            assertEquals(result, FeaturedArtistSelector.select(listOf(editorial), history, now))

            val bothPersonal = FeaturedArtistSelector.select(emptyList(), plays(left, 3) + history, now)
            assertEquals(listOf(left, right), bothPersonal.map { it.name })
            assertTrue(bothPersonal.all { it.id.startsWith("artist-name-") })
            assertEquals(2, bothPersonal.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `generated fallback collisions cannot replace editorial IDs`() {
        val roster = listOf(artist("beyonce", "Beyoncé"), artist("artist-name-6265796f6e6365", "Other"))
        val result = FeaturedArtistSelector.select(roster, plays("Beyonce", 3), now)
        assertEquals("artist-name-6265796f6e6365-local", result.first().id)
        assertEquals(roster, result.drop(1))
    }

    @Test
    fun `native expansion preserves stable ties cap and editorial metadata`() {
        val roster = (1..8).map { artist("backend:$it", "Editorial $it") }
        val history = plays("अरिजीत सिंह", 3) + plays("অরিজিৎ সিং", 3) + plays("कि", 3) + plays("क़", 3)
        val result = FeaturedArtistSelector.select(roster, history, now)
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals(listOf("अरिजीत सिंह", "অরিজিৎ সিং", "कि", "क़"), result.take(4).map { it.name })
        assertEquals(roster.take(4), result.drop(4))
        assertEquals(result, FeaturedArtistSelector.select(roster, history, now))
    }
}
