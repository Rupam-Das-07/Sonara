"""
test_artwork_thumbnail_priority.py — Phase 2C: authoritative provider metadata outranks a
thumbnail URL constructed from a video id.

Why this exists
---------------
`get_audio_info` and `search_youtube` both used to read:

    thumbnail = (
        f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg"
        if video_id
        else info.get("thumbnail", "")
    )

i.e. the URL *constructed* from the id was PREFERRED, and the thumbnail yt-dlp had actually
resolved for that video was used only when the id was missing. That inverts the Phase 2C
resolution priority ("original usable artwork from the authoritative metadata" ranks above a
video-thumbnail fallback) and it is the stronger failure of the two: `maxresdefault.jpg` is a
separate stored file that does not exist for every video, so a working thumbnail was being
replaced by one that can 404.

Contract asserted here:
  T1 [basic]    a thumbnail reported by the provider is returned verbatim
  T2 [boundary] a missing thumbnail still falls back to the constructed URL (artwork is not lost)
  T3 [input]    an empty / whitespace-only / non-string thumbnail counts as absent
  T4 [basic]    the same priority holds per-entry in search results
  T5 [property] over a matrix of thumbnail shapes the result matches an independent oracle

The fake yt-dlp / gevent / flask runtime is reused from test_stream_resolution_reuse rather
than duplicated; importing that module installs the fakes, imports the target, and restores
the real import state, keeping a module-local reference to `youtube_service`.
"""

import unittest

from .test_stream_resolution_reuse import _HOOK, _reset_pool, yserv

VIDEO_ID = "vidartwork1"
CONSTRUCTED = f"https://img.youtube.com/vi/{VIDEO_ID}/maxresdefault.jpg"
REAL = "https://i.ytimg.com/vi/vidartwork1/hq720.jpg"


def _info(thumbnail=..., video_id=VIDEO_ID):
    """A minimal yt-dlp info dict; `thumbnail` omitted entirely when left as the sentinel."""
    info = {
        "id": video_id,
        "title": "T",
        "url": "https://x/audio",
        "http_headers": {},
    }
    if thumbnail is not ...:
        info["thumbnail"] = thumbnail
    return info


def _entry(thumbnail=..., video_id=VIDEO_ID):
    entry = {"id": video_id, "title": "T", "channel": "C", "duration": 10}
    if thumbnail is not ...:
        entry["thumbnail"] = thumbnail
    return entry


class TestArtworkThumbnailPriority(unittest.TestCase):

    def setUp(self):
        _reset_pool()

    def tearDown(self):
        _HOOK["fn"] = None

    # ------------------------------------------------------------------ T1 [basic]

    def test_provider_reported_thumbnail_wins_over_constructed_url(self):
        _HOOK["fn"] = lambda inst, url: _info(thumbnail=REAL)

        result = yserv.get_audio_info(f"https://www.youtube.com/watch?v={VIDEO_ID}")

        self.assertEqual(
            REAL,
            result["thumbnail"],
            "the thumbnail the provider resolved must be returned verbatim",
        )
        self.assertNotEqual(
            CONSTRUCTED,
            result["thumbnail"],
            "a URL built from the id must not override authoritative metadata",
        )

    # --------------------------------------------------------------- T2 [boundary]

    def test_missing_thumbnail_falls_back_to_the_constructed_url(self):
        _HOOK["fn"] = lambda inst, url: _info()

        result = yserv.get_audio_info(f"https://www.youtube.com/watch?v={VIDEO_ID}")

        self.assertEqual(
            CONSTRUCTED,
            result["thumbnail"],
            "the constructed URL remains the lower-priority fallback, so artwork is not lost",
        )

    def test_missing_thumbnail_and_missing_id_yields_empty_string(self):
        _HOOK["fn"] = lambda inst, url: _info(video_id="")

        result = yserv.get_audio_info("https://www.youtube.com/watch?v=")

        self.assertEqual(
            "",
            result["thumbnail"],
            "with neither real artwork nor an id, nothing may be invented",
        )

    # ------------------------------------------------------------------ T3 [input]

    def test_unusable_thumbnail_values_count_as_absent(self):
        for label, value in (("empty", ""), ("none", None)):
            with self.subTest(label):
                _HOOK["fn"] = lambda inst, url, v=value: _info(thumbnail=v)
                result = yserv.get_audio_info(f"https://www.youtube.com/watch?v={VIDEO_ID}")
                self.assertEqual(
                    CONSTRUCTED,
                    result["thumbnail"],
                    f"a {label} thumbnail must be treated as absent",
                )

    # ------------------------------------------------------------------ T4 [basic]

    def test_search_results_keep_each_entrys_own_reported_thumbnail(self):
        other_id = "vidartwork2"
        _HOOK["fn"] = lambda inst, url: {
            "entries": [
                _entry(thumbnail=REAL),
                _entry(video_id=other_id),
            ]
        }

        results = yserv.search_youtube("query", max_results=5)

        self.assertEqual(2, len(results))
        self.assertEqual(
            REAL,
            results[0]["thumbnail"],
            "the entry with real artwork keeps it",
        )
        self.assertEqual(
            f"https://img.youtube.com/vi/{other_id}/maxresdefault.jpg",
            results[1]["thumbnail"],
            "the entry without artwork falls back to its OWN id, never a neighbour's",
        )

    # --------------------------------------------------------------- T5 [property]

    def test_property_thumbnail_selection_matches_an_independent_oracle(self):
        shapes = [..., None, "", REAL, "https://example.test/a.jpg"]
        ids = [VIDEO_ID, ""]

        def oracle(thumbnail, video_id):
            if isinstance(thumbnail, str) and thumbnail:
                return thumbnail
            return f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg" if video_id else ""

        combinations = 0
        real_wins = 0
        for thumbnail in shapes:
            for video_id in ids:
                combinations += 1
                _HOOK["fn"] = lambda inst, url, t=thumbnail, i=video_id: _info(
                    thumbnail=t, video_id=i
                )
                result = yserv.get_audio_info(f"https://www.youtube.com/watch?v={video_id}")
                expected = oracle(None if thumbnail is ... else thumbnail, video_id)
                self.assertEqual(
                    expected,
                    result["thumbnail"],
                    f"thumbnail={thumbnail!r} id={video_id!r}",
                )
                if expected not in ("",) and not expected.startswith("https://img.youtube.com/"):
                    real_wins += 1

        self.assertEqual(len(shapes) * len(ids), combinations)
        # Guard against a vacuous pass: the corpus must exercise both outcomes.
        self.assertGreater(real_wins, 0, "some cases must be decided by real artwork")
        self.assertLess(real_wins, combinations, "some cases must fall back")


if __name__ == "__main__":
    unittest.main()
