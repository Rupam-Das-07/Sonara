package com.example.sonara.domain.model

import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.data.remote.backend.TrackMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured artist credits: codec, persistence round-trip and mapper sanitisation.
 *
 * These credits exist so Featured Artists affinity can attribute a listen to a
 * VERIFIED artist identity instead of parsing the flat `Track.artist` display string.
 * The tests below therefore pin two things especially hard:
 *
 *  1. A legacy/unknown value decodes to an EMPTY list — "no verified identity" — and
 *     never to a fabricated credit. Phase 7's identity gate depends on this.
 *  2. Separator control characters cannot be injected to forge extra credits.
 */
class ArtistCreditTest {

    private val zimmerPair = listOf(
        ArtistCredit("Hans Zimmer", "UC_hz"),
        ArtistCredit("Benjamin Wallfisch", "UC_bw")
    )

    // ─── codec round-trip ─────────────────────────────────────────────────────

    @Test
    fun `a multi-artist credit list round-trips with order and ids intact`() {
        val decoded = ArtistCreditCodec.decode(ArtistCreditCodec.encode(zimmerPair))
        assertEquals(zimmerPair, decoded)
    }

    @Test
    fun `playback metadata with empty credits preserves existing structured credits`() {
        val existing = TrackEntity.fromDomain(
            Track(
                id = "track",
                title = "Interstellar",
                artist = "Hans Zimmer, Benjamin Wallfisch",
                artists = zimmerPair
            )
        )
        val playbackCallbackTrack = Track(
            id = "track",
            title = "Interstellar",
            artist = "Hans Zimmer, Benjamin Wallfisch",
            artists = emptyList()
        )

        val merged = TrackEntity.fromDomainPreservingCredits(playbackCallbackTrack, existing)

        assertEquals(existing.artistCredits, merged.artistCredits)
        assertEquals(zimmerPair, merged.toDomain().artists)
    }

    @Test
    fun `a single act whose name contains separators stays one credit`() {
        // The whole reason structure is carried: "AC/DC" must never look like two artists.
        for (name in listOf("AC/DC", "Simon & Garfunkel", "Sleeping With Sirens", "Hall and Oates")) {
            val one = listOf(ArtistCredit(name, "UC_x"))
            assertEquals("$name must survive as exactly one credit", one, ArtistCreditCodec.decode(ArtistCreditCodec.encode(one)))
        }
    }

    @Test
    fun `an empty list and an empty column are the same thing`() {
        assertEquals("", ArtistCreditCodec.encode(emptyList()))
        assertEquals(emptyList<ArtistCredit>(), ArtistCreditCodec.decode(""))
        assertEquals(emptyList<ArtistCredit>(), ArtistCreditCodec.decode(null))
    }

    @Test
    fun `a credit with no id round-trips as a name-only credit`() {
        val nameOnly = listOf(ArtistCredit("Lorne Balfe", ""))
        assertEquals(nameOnly, ArtistCreditCodec.decode(ArtistCreditCodec.encode(nameOnly)))
        // A bare record with no unit separator is still a usable name-only credit.
        assertEquals(nameOnly, ArtistCreditCodec.decode("Lorne Balfe"))
    }

    @Test
    fun `blank names are dropped on encode`() {
        val encoded = ArtistCreditCodec.encode(
            listOf(ArtistCredit("   ", "UC_a"), ArtistCredit("", ""), ArtistCredit("Hans Zimmer", "UC_hz"))
        )
        assertEquals(listOf(ArtistCredit("Hans Zimmer", "UC_hz")), ArtistCreditCodec.decode(encoded))
        assertEquals("", ArtistCreditCodec.encode(listOf(ArtistCredit("", ""), ArtistCredit("  ", ""))))
    }

    @Test
    fun `separator injection cannot forge extra credits`() {
        val hostile = listOf(ArtistCredit("Evil\u001EForged\u001FUC_bad", "UC_ok"))
        val decoded = ArtistCreditCodec.decode(ArtistCreditCodec.encode(hostile))
        assertEquals("a hostile name must not become two credits", 1, decoded.size)
        assertEquals("EvilForgedUC_bad", decoded[0].name)
        assertEquals("UC_ok", decoded[0].id)
    }

    @Test
    fun `decoding a malformed column never throws and skips unusable records`() {
        assertEquals(
            listOf(ArtistCredit("Hans Zimmer", "UC_hz")),
            ArtistCreditCodec.decode("\u001EHans Zimmer\u001FUC_hz\u001E")
        )
        assertEquals(
            listOf(ArtistCredit("A", "1"), ArtistCredit("B", "2")),
            ArtistCreditCodec.decode("A\u001F1\u001E\u001EB\u001F2")
        )
        assertEquals(
            "a record with no name is skipped, not kept with a blank name",
            listOf(ArtistCredit("Real", "UC_r")),
            ArtistCreditCodec.decode("\u001FUC_orphan\u001EReal\u001FUC_r")
        )
        assertEquals(listOf(ArtistCredit("A", "1")), ArtistCreditCodec.decode("A\u001F1\u001Fjunk"))
        assertEquals(emptyList<ArtistCredit>(), ArtistCreditCodec.decode("\u001E\u001E"))
    }

    @Test
    fun `native-script and accented names survive unchanged`() {
        val unicode = listOf(
            ArtistCredit("अरिजीत सिंह", "UC_a"),
            ArtistCredit("শ্রেয়া ঘোষাল", "UC_b"),
            ArtistCredit("Beyoncé", "UC_c"),
            ArtistCredit("宇多田ヒカル", "UC_d")
        )
        assertEquals(unicode, ArtistCreditCodec.decode(ArtistCreditCodec.encode(unicode)))
    }

    @Test
    fun `padding is trimmed and encoding is deterministic`() {
        assertEquals(
            listOf(ArtistCredit("Hans Zimmer", "UC_hz")),
            ArtistCreditCodec.decode(ArtistCreditCodec.encode(listOf(ArtistCredit("  Hans Zimmer  ", "  UC_hz  "))))
        )
        assertEquals(ArtistCreditCodec.encode(zimmerPair), ArtistCreditCodec.encode(zimmerPair))
    }

    // ─── persistence round-trip ───────────────────────────────────────────────

    @Test
    fun `TrackEntity preserves structured credits through a save and load`() {
        val track = Track(
            id = "vid1",
            title = "Time",
            artist = "Hans Zimmer & Benjamin Wallfisch",
            album = "Inception",
            durationMs = 240_000L,
            artworkUrl = null,
            artists = zimmerPair
        )
        val restored = TrackEntity.fromDomain(track).toDomain()

        assertEquals(zimmerPair, restored.artists)
        assertEquals("the flat display string is untouched", track.artist, restored.artist)
        assertEquals(track.id, restored.id)
        assertEquals(track.title, restored.title)
        assertEquals(track.album, restored.album)
        assertEquals(track.durationMs, restored.durationMs)
    }

    @Test
    fun `a pre-migration row decodes to no verified identity`() {
        // Exactly what MIGRATION_6_7's DEFAULT '' produces for every legacy row.
        val legacy = TrackEntity(
            id = "vid-legacy",
            title = "Old Song",
            artist = "Hans Zimmer & Benjamin Wallfisch"
        )
        assertEquals("", legacy.artistCredits)
        assertTrue(
            "a legacy row must carry NO credits, so it can never satisfy an identity gate",
            legacy.toDomain().artists.isEmpty()
        )
    }

    @Test
    fun `a track with no structured credits round-trips as empty, not as a parsed string`() {
        val flatOnly = Track(id = "vid2", title = "T", artist = "Hans Zimmer & Benjamin Wallfisch")
        val restored = TrackEntity.fromDomain(flatOnly).toDomain()
        assertTrue(restored.artists.isEmpty())
    }

    // ─── mapper sanitisation ──────────────────────────────────────────────────

    @Test
    fun `TrackMapper defaults to no credits and drops unusable entries`() {
        val withoutCredits = TrackMapper.map(
            videoId = "vid3", title = "T", artist = "A", album = "", durationSec = 10, rawArtworkUrl = null
        )
        assertTrue("omitting the argument must mean no credits", withoutCredits!!.artists.isEmpty())

        val sanitised = TrackMapper.map(
            videoId = "vid4", title = "T", artist = "A", album = "", durationSec = 10, rawArtworkUrl = null,
            artists = listOf(
                ArtistCredit("   ", "UC_blank"),
                ArtistCredit("  Hans Zimmer  ", "  UC_hz  "),
                ArtistCredit("Lorne Balfe", "")
            )
        )
        assertEquals(
            listOf(ArtistCredit("Hans Zimmer", "UC_hz"), ArtistCredit("Lorne Balfe", "")),
            sanitised!!.artists
        )
    }
}
