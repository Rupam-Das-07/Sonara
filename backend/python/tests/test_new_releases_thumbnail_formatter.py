"""Focused tests for the New Releases thumbnail formatter.

The service imports its runtime dependencies at module import time and creates a
global ``YTMusic`` instance.  These tests install small stdlib-only fakes before
each import so the endpoint can be exercised without Flask, gevent, ytmusicapi,
or a network connection.

Run with::

    python -m unittest backend.python.tests.test_new_releases_thumbnail_formatter -v
"""

import importlib
import os
import sys
import types
import unittest


_BACKEND_PYTHON = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
_MISSING = object()
_MISSING_THUMBNAILS = object()
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
        self.explore = {"new_releases": []}
        self.get_explore_calls = 0

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


class NewReleasesThumbnailFormatterTests(unittest.TestCase):
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

    def _get_new_releases(self, thumbnails):
        item = {
            "browseId": "album-1",
            "audioPlaylistId": "playlist-1",
            "title": "A Release",
            "type": "Album",
            "artists": [{"name": "An Artist", "id": "artist-1"}],
            "year": "2026",
            "isExplicit": False,
        }
        if thumbnails is not _MISSING_THUMBNAILS:
            item["thumbnails"] = thumbnails
        self.service.yt.explore = {"new_releases": [item]}
        return self.service.get_new_releases()

    def test_valid_item_uses_final_thumbnail_and_preserves_schema_order(self):
        payload = self._get_new_releases(
            [
                {"url": "https://img.example/small.jpg"},
                {"url": "https://img.example/final.jpg"},
            ]
        )

        self.assertEqual(list(payload), ["success", "containers"])
        self.assertTrue(payload["success"])
        self.assertEqual(len(payload["containers"]), 1)
        container = payload["containers"][0]
        self.assertEqual(
            list(container),
            [
                "browseId",
                "audioPlaylistId",
                "title",
                "type",
                "artist",
                "artists",
                "year",
                "thumbnailUrl",
                "isExplicit",
            ],
        )
        self.assertEqual(container["thumbnailUrl"], "https://img.example/final.jpg")
        self.assertEqual(container["artist"], "An Artist")
        self.assertEqual(container["artists"], [{"name": "An Artist", "id": "artist-1"}])
        self.assertEqual(self.service.yt.get_explore_calls, 1)

    def test_malformed_thumbnail_containers_and_entries_skip_artwork_without_throwing(self):
        malformed_thumbnails = [
            _MISSING_THUMBNAILS,
            None,
            {},
            "not-a-thumbnail-list",
            17,
            [],
            [None],
            ["not-a-dict"],
            [17],
            [{}],
            [{"url": None}],
            [{"url": 42}],
            [{"url": ""}],
            [None, "not-a-dict", 17, {}, {"url": None}, {"url": 42}, {"url": ""}],
        ]

        for thumbnails in malformed_thumbnails:
            with self.subTest(thumbnails=thumbnails):
                payload = self._get_new_releases(thumbnails)
                self.assertTrue(payload["success"])
                self.assertEqual(len(payload["containers"]), 1)
                self.assertEqual(payload["containers"][0]["thumbnailUrl"], "")

    def test_mixed_entries_choose_last_valid_url_and_ignore_later_invalid_entries(self):
        payload = self._get_new_releases(
            [
                {"url": "https://img.example/first.jpg"},
                None,
                "not-a-dict",
                23,
                {"url": None},
                {"url": ""},
                {"url": "https://img.example/last-valid.jpg"},
                {"url": 23},
                {},
            ]
        )

        self.assertEqual(payload["containers"][0]["thumbnailUrl"], "https://img.example/last-valid.jpg")

    def test_malformed_shelf_entries_do_not_drop_a_valid_item_or_change_request_budget(self):
        valid_item = {
            "browseId": "album-valid",
            "title": "Valid Release",
            "thumbnails": {"url": "https://img.example/not-a-list"},
        }
        self.service.yt.explore = {
            "new_releases": [None, "bad shelf entry", 99, {}, valid_item]
        }

        payload = self.service.get_new_releases()

        self.assertEqual(payload["success"], True)
        self.assertEqual(len(payload["containers"]), 1)
        self.assertEqual(payload["containers"][0]["browseId"], "album-valid")
        self.assertEqual(payload["containers"][0]["thumbnailUrl"], "")
        self.assertEqual(self.service.yt.get_explore_calls, 1)

    def test_thumbnail_helper_is_explicit_about_non_list_inputs(self):
        self.assertEqual(self.service._format_thumbnail_url(None), "")
        self.assertEqual(self.service._format_thumbnail_url({"url": "https://img.example/wrong.jpg"}), "")
        self.assertEqual(self.service._format_thumbnail_url("https://img.example/wrong.jpg"), "")
        self.assertEqual(self.service._format_thumbnail_url(17), "")


if __name__ == "__main__":
    unittest.main()
