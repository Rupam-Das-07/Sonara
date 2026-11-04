"""Hermetic tests for exact, ambiguity-safe artist image matching."""

import unittest

try:
    from backend.python.artist_image import exact_artist_image, is_valid_browse_id, normalize_artist_name
except ModuleNotFoundError:
    from artist_image import exact_artist_image, is_valid_browse_id, normalize_artist_name


def _id(suffix):
    return "UC" + suffix.ljust(22, "0")[:22]


class ArtistImageHelperTests(unittest.TestCase):
    def test_exact_name_does_not_choose_a_tribute_result(self):
        result = exact_artist_image([
            {"artist": "Hans Zimmer", "browseId": _id("real"),
             "thumbnails": [{"url": "https://img.example/real.jpg"}]},
            {"artist": "Hans Zimmer Tribute", "browseId": _id("tribute"),
             "thumbnails": [{"url": "https://img.example/tribute.jpg"}]},
        ], "Hans Zimmer")
        self.assertEqual(result, "https://img.example/real.jpg")

    def test_exact_name_with_distinct_tribute_id_is_ambiguous(self):
        self.assertIsNone(exact_artist_image([
            {"artist": "Hans Zimmer", "browseId": _id("real"),
             "thumbnails": [{"url": "https://img.example/real.jpg"}]},
            {"artist": "Hans Zimmer", "browseId": _id("tribute"),
             "thumbnails": [{"url": "https://img.example/tribute.jpg"}]},
        ], "Hans Zimmer"))

    def test_nfc_and_casefold_match_native_text_exactly(self):
        decomposed = "Cafe\u0301 演奏者"
        composed = "Café 演奏者"
        self.assertEqual(normalize_artist_name(decomposed), normalize_artist_name(composed))
        self.assertEqual(exact_artist_image([
            {"name": composed, "browseId": _id("native"),
             "thumbnails": [{"url": "https://img.example/native.jpg"}]},
        ], decomposed), "https://img.example/native.jpg")

    def test_distinct_exact_name_browse_ids_are_ambiguous(self):
        self.assertIsNone(exact_artist_image([
            {"artist": "Alex", "browseId": _id("one"), "thumbnails": [{"url": "https://img/1"}]},
            {"artist": "Alex", "browseId": _id("two"), "thumbnails": [{"url": "https://img/2"}]},
        ], "alex"))

    def test_invalid_ids_and_malformed_shapes_fail_closed(self):
        self.assertFalse(is_valid_browse_id("UCshort"))
        self.assertIsNone(exact_artist_image([
            {"artist": "Alex", "browseId": "not-a-uc-id", "thumbnails": [{"url": "https://img/1"}]},
            {"artist": "Alex", "browseId": _id("valid"), "thumbnails": "not-a-list"},
        ], "Alex"))


if __name__ == "__main__":
    unittest.main()
