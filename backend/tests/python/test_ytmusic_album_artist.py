"""Focused offline tests for album artist formatting in ytmusic_service."""

import importlib
import os
import sys
import types
import unittest


PYTHON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "python"))
sys.path.insert(0, PYTHON_DIR)


class _FakeTimeout(Exception):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class _FakeFlask:
    def __init__(self, *args, **kwargs):
        pass

    def route(self, *args, **kwargs):
        def decorator(function):
            return function

        return decorator


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
)


def _install_import_fakes():
    gevent = types.ModuleType("gevent")
    gevent.Timeout = _FakeTimeout
    gevent_monkey = types.ModuleType("gevent.monkey")
    gevent_monkey.patch_all = lambda: None
    gevent.monkey = gevent_monkey
    sys.modules["gevent"] = gevent
    sys.modules["gevent.monkey"] = gevent_monkey

    flask = types.ModuleType("flask")
    flask.Flask = _FakeFlask
    flask.request = types.SimpleNamespace()
    flask.jsonify = lambda payload: payload
    sys.modules["flask"] = flask

    flask_cors = types.ModuleType("flask_cors")
    flask_cors.CORS = lambda *args, **kwargs: None
    sys.modules["flask_cors"] = flask_cors

    ytmusicapi = types.ModuleType("ytmusicapi")
    ytmusicapi.__path__ = []
    ytmusicapi.YTMusic = type("YTMusic", (), {})
    parsers = types.ModuleType("ytmusicapi.parsers")
    parsers.__path__ = []
    watch_parser = types.ModuleType("ytmusicapi.parsers.watch")
    mixins = types.ModuleType("ytmusicapi.mixins")
    mixins.__path__ = []
    watch_mixin = types.ModuleType("ytmusicapi.mixins.watch")
    ytmusicapi.parsers = parsers
    ytmusicapi.mixins = mixins
    parsers.watch = watch_parser
    mixins.watch = watch_mixin
    sys.modules["ytmusicapi"] = ytmusicapi
    sys.modules["ytmusicapi.parsers"] = parsers
    sys.modules["ytmusicapi.parsers.watch"] = watch_parser
    sys.modules["ytmusicapi.mixins"] = mixins
    sys.modules["ytmusicapi.mixins.watch"] = watch_mixin


_missing = object()
_original_modules = {
    name: sys.modules.get(name, _missing) for name in _FAKE_MODULE_NAMES
}
_install_import_fakes()
service = importlib.import_module("ytmusic_service")
for _name, _module in _original_modules.items():
    if _module is _missing:
        sys.modules.pop(_name, None)
    else:
        sys.modules[_name] = _module


class _FakeYTMusic:
    def __init__(self, album=None, explore=None):
        self.album = album
        self.explore = explore or {}

    def get_album(self, browse_id):
        return self.album

    def get_explore(self):
        return self.explore


def _album(**overrides):
    value = {
        "title": "Album title",
        "year": "2026",
        "thumbnails": [{"url": "https://img.example/album.jpg"}],
        "description": "Album description",
        "tracks": [{"videoId": "track-1", "title": "Track one"}],
    }
    value.update(overrides)
    return value


class AlbumArtistFormattingTests(unittest.TestCase):
    def get_album(self, album):
        service.yt = _FakeYTMusic(album)
        return service.get_album_info("album-123")

    def test_single_artist_array_is_formatted_as_a_string(self):
        response = self.get_album(_album(artists=[{"name": "Solo Artist", "id": "a1"}]))

        self.assertEqual(response["artist"], "Solo Artist")
        self.assertIsInstance(response["artist"], str)
        self.assertEqual(response["tracks"], [{"videoId": "track-1", "title": "Track one"}])

    def test_multiple_artist_array_preserves_all_valid_names(self):
        response = self.get_album(
            _album(artists=[{"name": "Artist One"}, {"name": "Artist Two"}])
        )

        self.assertEqual(response["artist"], "Artist One, Artist Two")
        self.assertIsInstance(response["artist"], str)

    def test_missing_or_empty_artist_array_returns_an_empty_string(self):
        for artists in (None, [], "not-an-array"):
            with self.subTest(artists=artists):
                album = _album()
                if artists is not None:
                    album["artists"] = artists
                response = self.get_album(album)
                self.assertEqual(response["artist"], "")
                self.assertIsInstance(response["artist"], str)

    def test_malformed_entries_are_ignored_without_affecting_other_fields(self):
        response = self.get_album(
            _album(
                artists=[
                    None,
                    {},
                    {"name": ""},
                    {"name": 42},
                    {"name": "  Valid Artist  "},
                ]
            )
        )

        self.assertEqual(response["artist"], "Valid Artist")
        self.assertIsInstance(response["artist"], str)
        self.assertEqual(response["title"], "Album title")
        self.assertEqual(response["year"], "2026")
        self.assertEqual(response["thumbnailUrl"], "https://img.example/album.jpg")
        self.assertEqual(response["description"], "Album description")
        self.assertEqual(response["tracks"], [{"videoId": "track-1", "title": "Track one"}])

    def test_valid_scalar_artist_takes_precedence_for_compatibility(self):
        response = self.get_album(
            _album(
                artist="Legacy Scalar Artist",
                artists=[{"name": "Array Artist"}],
            )
        )

        self.assertEqual(response["artist"], "Legacy Scalar Artist")
        self.assertIsInstance(response["artist"], str)

    def test_empty_scalar_artist_falls_back_to_valid_artist_array(self):
        response = self.get_album(
            _album(
                artist="",
                artists=[{"name": "Array Artist"}],
            )
        )

        self.assertEqual(response["artist"], "Array Artist")
        self.assertIsInstance(response["artist"], str)


class NewReleaseShelfArtistFormattingTests(unittest.TestCase):
    def get_new_releases(self, artists):
        service.yt = _FakeYTMusic(explore={
            "new_releases": [{
                "browseId": "MPREb_artist-shape",
                "audioPlaylistId": "OLAK5uy_artist-shape",
                "title": "Artist Shape Release",
                "type": "Single",
                "artists": artists,
                "year": "2026",
                "thumbnails": [{"url": "https://img.example/release.jpg"}],
                "isExplicit": False,
            }]
        })
        return service.get_new_releases()

    def assert_container_shape(self, response):
        self.assertTrue(response["success"])
        self.assertEqual(len(response["containers"]), 1)
        self.assertEqual(
            set(response["containers"][0]),
            {
                "browseId", "audioPlaylistId", "title", "type", "artist",
                "artists", "year", "thumbnailUrl", "isExplicit",
            },
        )

    def test_valid_artist_dictionaries_preserve_names_and_ids(self):
        response = self.get_new_releases([
            {"name": "Artist One", "id": "a1"},
            {"name": "Artist Two", "id": "a2"},
        ])

        self.assert_container_shape(response)
        container = response["containers"][0]
        self.assertEqual(container["artist"], "Artist One, Artist Two")
        self.assertEqual(container["artists"], [
            {"name": "Artist One", "id": "a1"},
            {"name": "Artist Two", "id": "a2"},
        ])

    def test_missing_or_empty_artists_returns_empty_artist_fields(self):
        for artists in (None, []):
            with self.subTest(artists=artists):
                response = self.get_new_releases(artists)
                self.assert_container_shape(response)
                container = response["containers"][0]
                self.assertEqual(container["artist"], "")
                self.assertEqual(container["artists"], [])

    def test_malformed_entries_are_ignored_while_valid_entries_survive(self):
        response = self.get_new_releases([
            None,
            {"name": "Valid Artist", "id": "valid-id"},
            "not-a-dictionary",
            {"name": "Second Artist", "id": None},
        ])

        self.assert_container_shape(response)
        container = response["containers"][0]
        self.assertEqual(container["artist"], "Valid Artist, Second Artist")
        self.assertEqual(container["artists"], [
            {"name": "Valid Artist", "id": "valid-id"},
            {"name": "Second Artist", "id": ""},
        ])

    def test_only_malformed_entries_do_not_fail_the_shelf(self):
        response = self.get_new_releases([None, "bad", 42, [], {"unknown": True}])

        self.assert_container_shape(response)
        container = response["containers"][0]
        self.assertEqual(container["artist"], "")
        self.assertEqual(container["artists"], [])

    def test_missing_or_invalid_names_and_ids_are_ignored_or_normalized(self):
        response = self.get_new_releases([
            {"name": None, "id": "id-only"},
            {"name": "Name Only", "id": 123},
            {"name": "   ", "id": "   "},
        ])

        self.assert_container_shape(response)
        container = response["containers"][0]
        self.assertEqual(container["artist"], "Name Only")
        self.assertEqual(container["artists"], [
            {"name": "", "id": "id-only"},
            {"name": "Name Only", "id": ""},
        ])


if __name__ == "__main__":
    unittest.main()
