"""Focused tests for the album-detail artist formatter.

The service imports its runtime dependencies at module import time and creates a
global ``YTMusic`` instance.  These tests install small stdlib-only fakes before each
import so ``get_album_info()`` can be exercised without Flask, gevent, ytmusicapi, or
a network connection.  The harness mirrors
``test_new_releases_thumbnail_formatter.py``.

Background: ytmusicapi's ``get_album()`` returns an ``artists`` list of ``{"name",
"id"}`` objects and no scalar ``artist`` key, so formatting the response with
``album.get('artist', '')`` yielded an empty ``artist`` string even when valid artist
data was present.  ``_format_album_artist()`` derives the string from ``artists``
while still honouring a non-empty scalar ``artist`` when upstream or a fixture
provides one.

Run with::

    python -m unittest backend.python.tests.test_album_artist_formatter -v
"""

import importlib
import os
import sys
import types
import unittest


_BACKEND_PYTHON = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
_MISSING = object()
_FAKE_MODULE_NAMES = (
    "gevent",
    "gevent.monkey",
    "flask",
    "flask_cors",
    "ytmusicapi",
    "ytmusicapi.parsers",
    "ytmusicapi.parsers.watch",
    "ytmusicapi.mixins",
    "ytmusicapi.mixins.watch",
    "ytmusic_service",
)

# The schema the endpoint must keep returning, in order.
_EXPECTED_KEYS = ["id", "title", "artist", "year", "thumbnailUrl", "description", "tracks"]


class _FakeTimeout(Exception):
    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


class _FakeFlask:
    def __init__(self, *args, **kwargs):
        pass

    def route(self, *args, **kwargs):
        def decorator(function):
            return function

        return decorator


class _FakeYTMusic:
    def __init__(self):
        self.album = {}
        self.get_album_calls = 0
        # The shelf endpoint is exercised only to prove both endpoints format the
        # same upstream thumbnail shape identically.
        self.explore = {"new_releases": []}
        self.get_explore_calls = 0

    def get_album(self, browse_id):
        self.get_album_calls += 1
        return self.album

    def get_explore(self):
        self.get_explore_calls += 1
        return self.explore


def _install_fakes():
    gevent = types.ModuleType("gevent")
    gevent.Timeout = _FakeTimeout
    gevent_monkey = types.ModuleType("gevent.monkey")
    gevent_monkey.patch_all = lambda *args, **kwargs: None
    gevent.monkey = gevent_monkey
    sys.modules["gevent"] = gevent
    sys.modules["gevent.monkey"] = gevent_monkey

    flask = types.ModuleType("flask")
    flask.Flask = _FakeFlask
    flask.request = types.SimpleNamespace()
    flask.jsonify = lambda value=None, **kwargs: value if value is not None else kwargs
    sys.modules["flask"] = flask

    flask_cors = types.ModuleType("flask_cors")
    flask_cors.CORS = lambda *args, **kwargs: None
    sys.modules["flask_cors"] = flask_cors

    ytmusicapi = types.ModuleType("ytmusicapi")
    ytmusicapi.__path__ = []
    ytmusicapi.YTMusic = _FakeYTMusic
    parsers = types.ModuleType("ytmusicapi.parsers")
    parsers.__path__ = []
    parser_watch = types.ModuleType("ytmusicapi.parsers.watch")
    mixins = types.ModuleType("ytmusicapi.mixins")
    mixins.__path__ = []
    mixin_watch = types.ModuleType("ytmusicapi.mixins.watch")
    ytmusicapi.parsers = parsers
    ytmusicapi.mixins = mixins
    parsers.watch = parser_watch
    mixins.watch = mixin_watch
    sys.modules["ytmusicapi"] = ytmusicapi
    sys.modules["ytmusicapi.parsers"] = parsers
    sys.modules["ytmusicapi.parsers.watch"] = parser_watch
    sys.modules["ytmusicapi.mixins"] = mixins
    sys.modules["ytmusicapi.mixins.watch"] = mixin_watch


class AlbumArtistFormatterTests(unittest.TestCase):
    def setUp(self):
        self._original_modules = {
            name: sys.modules.get(name, _MISSING) for name in _FAKE_MODULE_NAMES
        }
        self._original_sys_path = sys.path[:]
        _install_fakes()
        sys.modules.pop("ytmusic_service", None)
        sys.path.insert(0, _BACKEND_PYTHON)
        self.service = importlib.import_module("ytmusic_service")

    def tearDown(self):
        sys.path[:] = self._original_sys_path
        for name, module in self._original_modules.items():
            if module is _MISSING:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    # -- helpers ---------------------------------------------------------

    def _album_payload(self, **overrides):
        """An upstream-shaped get_album() response; `artists` mirrors ytmusicapi."""
        album = {
            "title": "Revival",
            "type": "Album",
            "thumbnails": [
                {"url": "https://img.example/small.jpg"},
                {"url": "https://img.example/final.jpg"},
            ],
            "description": "Revival is the ninth studio album.",
            "artists": [{"name": "Eminem", "id": "UCedvOgsKFzcK3hA5taf3KoQ"}],
            "year": "2017",
            "tracks": [{"videoId": "iKLU7z_xdYQ", "title": "Walk On Water"}],
        }
        album.update(overrides)
        return album

    def _format(self, **overrides):
        self.service.yt.album = self._album_payload(**overrides)
        return self.service.get_album_info("MPREb_album1")

    def _assert_schema_and_unrelated_fields(self, payload):
        self.assertEqual(list(payload), _EXPECTED_KEYS)
        self.assertIsInstance(payload["artist"], str)
        self.assertEqual(payload["id"], "MPREb_album1")
        self.assertEqual(payload["title"], "Revival")
        self.assertEqual(payload["year"], "2017")
        self.assertEqual(payload["thumbnailUrl"], "https://img.example/final.jpg")
        self.assertEqual(payload["description"], "Revival is the ninth studio album.")
        self.assertEqual(payload["tracks"], [{"videoId": "iKLU7z_xdYQ", "title": "Walk On Water"}])

    # -- 1. one artist ---------------------------------------------------

    def test_single_artist_from_artists_array(self):
        payload = self._format()
        self.assertEqual(payload["artist"], "Eminem")
        self._assert_schema_and_unrelated_fields(payload)

    # -- 2. multiple artists ---------------------------------------------

    def test_multiple_artists_are_joined_in_order(self):
        payload = self._format(artists=[
            {"name": "Eminem", "id": "a1"},
            {"name": "Beyoncé", "id": "a2"},
            {"name": "Skylar Grey", "id": "a3"},
        ])
        self.assertEqual(payload["artist"], "Eminem, Beyoncé, Skylar Grey")
        self._assert_schema_and_unrelated_fields(payload)

    # -- 3. missing or empty artists -------------------------------------

    def test_missing_empty_or_non_list_artists_yield_empty_string(self):
        for label, overrides in [
            ("missing", {"artists": _MISSING}),
            ("empty list", {"artists": []}),
            ("none", {"artists": None}),
            ("dict", {"artists": {"name": "Eminem"}}),
            ("string", {"artists": "Eminem"}),
        ]:
            with self.subTest(label):
                album = self._album_payload()
                if overrides["artists"] is _MISSING:
                    album.pop("artists")
                else:
                    album["artists"] = overrides["artists"]
                self.service.yt.album = album
                payload = self.service.get_album_info("MPREb_album1")

                self.assertEqual(payload["artist"], "", f"{label} must yield an empty string")
                self.assertIsInstance(payload["artist"], str)
                self._assert_schema_and_unrelated_fields(payload)

    # -- 4. malformed entries and missing names --------------------------

    def test_malformed_artist_entries_are_skipped_without_raising(self):
        payload = self._format(artists=[
            None,
            "Eminem",                      # not a dict
            42,
            [],
            {},                            # dict with no name
            {"id": "no-name"},             # missing name
            {"name": None},                # non-string name
            {"name": 7},
            {"name": ""},                  # blank
            {"name": "   "},               # whitespace only
            {"name": "  Royce da 5'9\"  "},  # valid, padded
            {"name": "Eminem"},
        ])
        self.assertEqual(payload["artist"], "Royce da 5'9\", Eminem")
        self._assert_schema_and_unrelated_fields(payload)

    def test_all_entries_malformed_yields_empty_string(self):
        payload = self._format(artists=[None, {}, {"id": "x"}, {"name": "  "}, 5])
        self.assertEqual(payload["artist"], "")
        self.assertIsInstance(payload["artist"], str)

    # -- 5. scalar artist fallback ---------------------------------------

    def test_non_empty_scalar_artist_takes_precedence(self):
        payload = self._format(artist="Legacy Scalar Artist")
        self.assertEqual(payload["artist"], "Legacy Scalar Artist")
        self._assert_schema_and_unrelated_fields(payload)

    def test_padded_scalar_artist_is_trimmed(self):
        payload = self._format(artist="  Eminem  ")
        self.assertEqual(payload["artist"], "Eminem")

    def test_padded_scalar_and_array_artist_forms_have_same_value(self):
        scalar = self._format(artist="  Eminem  ", artists=[])
        array = self._format(artist=None, artists=[{"name": "  Eminem  "}])
        self.assertEqual(scalar["artist"], "Eminem")
        self.assertEqual(array["artist"], "Eminem")

    def test_scalar_internal_whitespace_is_preserved(self):
        payload = self._format(artist="  Royce da 5'9\"  ")
        self.assertEqual(payload["artist"], "Royce da 5'9\"")

    def test_normal_scalar_and_list_names_are_exact(self):
        scalar = self._format(artist="Beyoncé", artists=[])
        listed = self._format(artist=None, artists=[
            {"name": "Beyoncé"},
            {"name": "Skylar Grey"},
        ])
        self.assertEqual(scalar["artist"], "Beyoncé")
        self.assertEqual(listed["artist"], "Beyoncé, Skylar Grey")

    def test_scalar_artist_wins_over_artists_array_when_both_present(self):
        payload = self._format(artist="Scalar Wins", artists=[{"name": "Array Loses"}])
        self.assertEqual(payload["artist"], "Scalar Wins")

    def test_blank_or_invalid_scalar_artist_falls_through_to_artists_array(self):
        for label, scalar in [
            ("empty", ""),
            ("whitespace", "   "),
            ("none", None),
            ("non-string", 42),
        ]:
            with self.subTest(label):
                payload = self._format(artist=scalar, artists=[{"name": "Eminem"}])
                self.assertEqual(payload["artist"], "Eminem",
                                 f"a {label} scalar must not suppress the artists array")

    # -- regression: native-script names survive unchanged ---------------

    def test_native_script_artist_names_are_preserved(self):
        payload = self._format(artists=[
            {"name": "अरिजीत सिंह", "id": "a1"},
            {"name": "শ্রেয়া ঘোষাল", "id": "a2"},
        ])
        self.assertEqual(payload["artist"], "अरिजीत सिंह, শ্রেয়া ঘোষাল")

    # -- the endpoint's other contracts stay intact ----------------------

    def test_missing_album_still_returns_404_tuple(self):
        self.service.yt.album = None
        payload, status = self.service.get_album_info("MPREb_missing")
        self.assertEqual(status, 404)
        self.assertEqual(payload, {"error": "Album not found"})

    def test_formatter_is_callable_directly_and_always_returns_a_string(self):
        fmt = self.service._format_album_artist
        for album in [
            {},
            None,
            [],
            {"artists": []},
            {"artists": None},
            {"artist": ""},
            {"artist": "   "},
            {"artist": "X"},
            {"artists": [{"name": "A"}, {"name": "B"}]},
            {"artist": "  padded  "},
        ]:
            with self.subTest(repr(album)):
                self.assertIsInstance(fmt(album), str)

    def test_whitespace_only_scalar_does_not_fabricate_an_artist(self):
        self.assertEqual(self.service._format_album_artist({"artist": "   "}), "")

    # -- album thumbnailUrl: malformed upstream shapes --------------------
    #
    # The album endpoint formatted thumbnailUrl with an inline expression
    # (album.get('thumbnails', [{}])[-1].get('url', '')), which raises on several
    # malformed shapes. The exception is swallowed by the handler's outer except and
    # returned as HTTP 500, so the ENTIRE album is lost over artwork metadata — and
    # downstream that marks the native source failed and suppresses caching. The
    # shelf formatter already had _format_thumbnail_url() for exactly this.
    #
    # These assert the endpoint's status and thumbnailUrl, not the helper in
    # isolation, and check that unrelated album fields survive.

    def _call_album(self, **overrides):
        """Invoke the endpoint and normalise to (payload, status)."""
        self.service.yt.album = self._album_payload(**overrides)
        result = self.service.get_album_info("MPREb_album1")
        if isinstance(result, tuple):
            return result[0], result[1]
        return result, 200

    def _assert_album_survived(self, payload, status):
        """A malformed thumbnail must not cost us the album."""
        self.assertEqual(status, 200)
        self.assertEqual(list(payload), _EXPECTED_KEYS)
        self.assertEqual(payload["id"], "MPREb_album1")
        self.assertEqual(payload["title"], "Revival")
        self.assertEqual(payload["artist"], "Eminem")
        self.assertEqual(payload["year"], "2017")
        self.assertEqual(payload["description"], "Revival is the ninth studio album.")
        self.assertEqual(payload["tracks"], [{"videoId": "iKLU7z_xdYQ", "title": "Walk On Water"}])
        self.assertIsInstance(payload["thumbnailUrl"], str)

    def test_album_thumbnail_normal_list_uses_the_last_entry(self):
        payload, status = self._call_album()
        self.assertEqual(status, 200)
        self.assertEqual(payload["thumbnailUrl"], "https://img.example/final.jpg")
        self._assert_schema_and_unrelated_fields(payload)

    def test_album_thumbnail_missing_key_yields_empty_string(self):
        album = self._album_payload()
        album.pop("thumbnails")
        self.service.yt.album = album
        payload = self.service.get_album_info("MPREb_album1")
        self.assertNotIsInstance(payload, tuple, "a missing thumbnail must not produce an error status")
        self.assertEqual(payload["thumbnailUrl"], "")
        self._assert_album_survived(payload, 200)

    def test_album_thumbnail_empty_list_yields_empty_string(self):
        payload, status = self._call_album(thumbnails=[])
        self.assertEqual(payload["thumbnailUrl"], "")
        self._assert_album_survived(payload, status)

    def test_album_thumbnail_falls_back_to_an_earlier_valid_entry(self):
        # Matches the shelf formatter: scan backwards for the last USABLE url rather
        # than blindly taking the final element.
        payload, status = self._call_album(thumbnails=[
            {"url": "https://img.example/valid.jpg"},
            {"width": 120},
        ])
        self.assertEqual(payload["thumbnailUrl"], "https://img.example/valid.jpg")
        self._assert_album_survived(payload, status)

    def test_album_thumbnail_malformed_shapes_never_lose_the_album(self):
        for label, thumbnails in [
            ("list of strings", ["https://img.example/a.jpg"]),
            ("string instead of list", "https://img.example/a.jpg"),
            ("dict instead of list", {"url": "https://img.example/a.jpg"}),
            ("list containing None", [{"url": "https://img.example/a.jpg"}, None]),
            ("list of ints", [1, 2, 3]),
            ("non-string url", [{"url": 42}]),
            ("blank url", [{"url": ""}]),
            ("nested list", [[{"url": "https://img.example/a.jpg"}]]),
        ]:
            with self.subTest(label):
                payload, status = self._call_album(thumbnails=thumbnails)
                self.assertEqual(status, 200, f"{label} must not become an error status")
                self._assert_album_survived(payload, status)

    def test_album_thumbnail_recovers_a_valid_url_from_a_partly_malformed_list(self):
        payload, status = self._call_album(thumbnails=[
            None,
            "not-a-dict",
            {"url": "https://img.example/good.jpg"},
            {"nope": 1},
        ])
        self.assertEqual(payload["thumbnailUrl"], "https://img.example/good.jpg")
        self._assert_album_survived(payload, status)

    def test_album_and_shelf_thumbnail_formatting_agree(self):
        """The two endpoints must not disagree on the same upstream shape."""
        shapes = [
            [{"url": "https://img.example/a.jpg"}, {"url": "https://img.example/b.jpg"}],
            [{"url": "https://img.example/a.jpg"}, {"width": 1}],
            [],
            ["https://img.example/a.jpg"],
            "https://img.example/a.jpg",
            {"url": "https://img.example/a.jpg"},
            [{"url": "https://img.example/a.jpg"}, None],
        ]
        for thumbnails in shapes:
            with self.subTest(repr(thumbnails)[:40]):
                album_payload, album_status = self._call_album(thumbnails=thumbnails)
                self.assertEqual(album_status, 200)

                self.service.yt.explore = {"new_releases": [{
                    "browseId": "MPREb_album1",
                    "title": "Revival",
                    "type": "Single",
                    "artists": [{"name": "Eminem", "id": "a1"}],
                    "thumbnails": thumbnails,
                }]}
                shelf = self.service.get_new_releases()
                shelf_url = shelf["containers"][0]["thumbnailUrl"]

                self.assertEqual(
                    album_payload["thumbnailUrl"], shelf_url,
                    f"album and shelf disagree for {thumbnails!r}",
                )


if __name__ == "__main__":
    unittest.main()
