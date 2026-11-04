"""
test_stream_resolution_reuse.py — Stage C-1A, Part A (+ regression guards for the
untouched caching/expiry layer).

HERMETIC by necessity: the sandbox has neither the backend's Python dependencies
(yt-dlp, gevent, flask, requests) nor network access to install them, so this suite
injects lightweight fakes for those modules into sys.modules BEFORE importing the
targets. The fakes let us drive youtube_service's reusable-YoutubeDL pool and
youtube_audio_api's cache/expiry logic deterministically, with NO network and NO
real extraction. Run with stdlib unittest:

    cd backend/python/tests && python3 -m unittest test_stream_resolution_reuse -v

Covers the required Python items:
  1. reusable extraction path is used        (TestYoutubeDLPool.test_reuse_*)
  2. stream cache returns before extraction   (TestAudioApiCacheAndExpiry.test_cache_short_circuits_extraction)
  3. in-flight dedup still works              (TestYoutubeDLPool.test_in_flight_dedup_short_circuits)
  4. extraction failure does not poison state (TestYoutubeDLPool.test_failure_does_not_poison_pool)
  5. existing expiry handling remains correct (TestAudioApiCacheAndExpiry.test_expiry_*)
  6. concurrent extraction behavior is safe   (TestYoutubeDLPool.test_pool_checkout_checkin_invariants)
"""

import os
import sys
import time
import types
import unittest

# Make backend/python importable regardless of the working directory.
_ORIGINAL_SYS_PATH = sys.path[:]
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))


# ─────────────────────────────────────────────────────────────────────────────
# Fake runtime dependencies (installed before importing the targets)
# ─────────────────────────────────────────────────────────────────────────────

# Test-controllable extraction hook: fn(instance, url) -> info dict (or raises).
_HOOK = {"fn": None}


class _FakeDownloadError(Exception):
    """Stand-in for yt_dlp.utils.DownloadError (NOT a RuntimeError, matching real yt-dlp)."""


class _FakeYoutubeDL:
    construct_count = 0

    def __init__(self, opts):
        _FakeYoutubeDL.construct_count += 1
        self.opts = opts
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False

    def extract_info(self, url, download=False):
        fn = _HOOK["fn"]
        if fn is None:
            return {"id": "vid", "title": "T", "url": "https://x/audio", "http_headers": {}}
        return fn(self, url)

    def close(self):
        self.closed = True


class _FakeTimeout(Exception):
    """Usable both as `with gevent.Timeout(n):` and as `except gevent.Timeout:`.

    The context manager never fires in tests (extraction hooks are synchronous),
    so real timeouts are not simulated here — the timeout *code path* is unchanged.
    """

    def __init__(self, seconds=None):
        super().__init__(seconds)
        self.seconds = seconds

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class _FakeAsyncResult:
    def __init__(self):
        self._value = None
        self._exc = None

    def set(self, value):
        self._value = value

    def set_exception(self, exc):
        self._exc = exc

    def get(self, timeout=None):
        if self._exc is not None:
            raise self._exc
        return self._value


class _FakeSemaphore:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def acquire(self, *a, **k):
        return True

    def release(self):
        pass


class _FakeLogger:
    def info(self, *a, **k):
        pass

    warning = info
    error = info
    debug = info
    exception = info


class _FakeFlask:
    def __init__(self, *a, **k):
        self.logger = _FakeLogger()

    def _decorator(self, *a, **k):
        def deco(fn):
            return fn
        return deco

    get = _decorator
    post = _decorator
    route = _decorator


def _install_fakes():
    # yt_dlp
    yt = types.ModuleType("yt_dlp")
    yt.YoutubeDL = _FakeYoutubeDL
    yt_utils = types.ModuleType("yt_dlp.utils")
    yt_utils.DownloadError = _FakeDownloadError
    yt.utils = yt_utils
    sys.modules["yt_dlp"] = yt
    sys.modules["yt_dlp.utils"] = yt_utils

    # gevent (+ event, lock, monkey)
    gevent = types.ModuleType("gevent")
    gevent.Timeout = _FakeTimeout
    gevent.sleep = lambda *a, **k: None
    gevent_event = types.ModuleType("gevent.event")
    gevent_event.AsyncResult = _FakeAsyncResult
    gevent_lock = types.ModuleType("gevent.lock")
    gevent_lock.Semaphore = _FakeSemaphore
    gevent_lock.BoundedSemaphore = _FakeSemaphore
    gevent_monkey = types.ModuleType("gevent.monkey")
    gevent_monkey.patch_all = lambda *a, **k: None
    gevent.event = gevent_event
    gevent.lock = gevent_lock
    gevent.monkey = gevent_monkey
    sys.modules["gevent"] = gevent
    sys.modules["gevent.event"] = gevent_event
    sys.modules["gevent.lock"] = gevent_lock
    sys.modules["gevent.monkey"] = gevent_monkey

    # flask / flask_cors / requests
    flask = types.ModuleType("flask")
    flask.Flask = _FakeFlask
    flask.request = types.SimpleNamespace()
    flask.jsonify = lambda *a, **k: (a[0] if len(a) == 1 else k)
    flask.Response = object
    flask.stream_with_context = lambda x: x
    sys.modules["flask"] = flask
    flask_cors = types.ModuleType("flask_cors")
    flask_cors.CORS = lambda *a, **k: None
    sys.modules["flask_cors"] = flask_cors
    sys.modules["requests"] = types.ModuleType("requests")


_MISSING = object()
_ISOLATED_MODULE_NAMES = (
    "yt_dlp",
    "yt_dlp.utils",
    "gevent",
    "gevent.event",
    "gevent.lock",
    "gevent.monkey",
    "flask",
    "flask_cors",
    "requests",
    "youtube_service",
    "youtube_audio_api",
)
_ORIGINAL_MODULES = {
    name: sys.modules.get(name, _MISSING)
    for name in _ISOLATED_MODULE_NAMES
}
_FAKE_MODULES = {}


def _restore_import_state():
    """Restore all import state changed while loading the fake-backed targets."""
    sys.path[:] = _ORIGINAL_SYS_PATH
    for name, module in _ORIGINAL_MODULES.items():
        if module is _MISSING:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = module


try:
    _install_fakes()
    _FAKE_MODULES = {
        name: sys.modules.get(name, _MISSING)
        for name in _ISOLATED_MODULE_NAMES
    }

    # Import targets AFTER fakes are in place. The suite keeps these references locally,
    # while later test modules receive the original dependency/module state.
    import youtube_service as yserv          # noqa: E402
    import youtube_audio_api as api          # noqa: E402

    DOWNLOAD_ERROR = sys.modules["yt_dlp"].utils.DownloadError
finally:
    _restore_import_state()


class TestImportIsolation(unittest.TestCase):
    """The fake runtime must not leak into later unittest modules."""

    def test_fake_imports_are_restored_after_module_load(self):
        for name, module in _FAKE_MODULES.items():
            self.assertIsNot(sys.modules.get(name), module, f"fake module leaked: {name}")


def _reset_pool():
    """Reset youtube_service pool + in-flight state and the fake counters."""
    yserv._ydl_pool.clear()
    yserv._ydl_live_count = 0
    yserv._in_flight_extractions.clear()
    _FakeYoutubeDL.construct_count = 0
    _HOOK["fn"] = None


# ─────────────────────────────────────────────────────────────────────────────
# Part A — reusable YoutubeDL pool
# ─────────────────────────────────────────────────────────────────────────────

class TestYoutubeDLPool(unittest.TestCase):
    def setUp(self):
        _reset_pool()

    def test_reuse_amortizes_construction(self):
        """Item 1: sequential extractions reuse ONE pooled instance (not one per call)."""
        for i in range(5):
            res = yserv.get_audio_info(f"https://www.youtube.com/watch?v=vid{i:08d}")
            self.assertEqual(res["title"], "T")
            self.assertEqual(res["audio_url"], "https://x/audio")
        self.assertEqual(
            _FakeYoutubeDL.construct_count, 1,
            "5 sequential extractions must reuse a single pooled YoutubeDL instance",
        )

    def test_pool_checkout_checkin_invariants(self):
        """Item 6: concurrency safety — outstanding checkouts are distinct; released
        instances are reused; the pool is hard-bounded with transient overflow."""
        opts = {"format": "bestaudio/best", "quiet": True}

        y1, p1 = yserv._acquire_ydl(opts)
        y2, p2 = yserv._acquire_ydl(opts)
        self.assertIsNot(y1, y2, "two concurrent checkouts must never share one instance")
        self.assertTrue(p1 and p2)

        # Releasing y1 makes it available; the next checkout reuses that exact instance.
        yserv._release_ydl(y1, p1)
        y3, p3 = yserv._acquire_ydl(opts)
        self.assertIs(y3, y1, "a released instance must be reused by the next checkout")

        # Bounded: once the cap of live pooled instances is reached, overflow is transient.
        _reset_pool()
        held = []
        for _ in range(yserv._YDL_POOL_MAX_INSTANCES):
            y, p = yserv._acquire_ydl(opts)
            self.assertTrue(p, "instances within the cap are pooled")
            held.append(y)
        y_over, p_over = yserv._acquire_ydl(opts)
        self.assertFalse(p_over, "beyond the cap, instances must be transient (bounded pool)")

    def test_failure_does_not_poison_pool(self):
        """Item 4: failed extractions discard their instance; the pool stays clean and
        a later extraction succeeds on a fresh instance (never a poisoned reuse)."""
        state = {"fail": True}

        def hook(_inst, _url):
            if state["fail"]:
                raise DOWNLOAD_ERROR("video unavailable")
            return {"id": "v", "title": "T", "url": "https://x/audio", "http_headers": {}}

        _HOOK["fn"] = hook

        with self.assertRaises(RuntimeError):
            yserv.get_audio_info("https://www.youtube.com/watch?v=vidfailpois")

        # 3 attempts (1 + 2 retries), each on its own instance, all discarded.
        self.assertEqual(_FakeYoutubeDL.construct_count, 3)
        self.assertEqual(sum(len(v) for v in yserv._ydl_pool.values()), 0,
                         "no idle instances must remain after failures")
        self.assertEqual(yserv._ydl_live_count, 0, "live pooled count must return to zero")

        # Now succeed: a FRESH instance is built (count -> 4) and the result is correct.
        state["fail"] = False
        res = yserv.get_audio_info("https://www.youtube.com/watch?v=vidokeyokey")
        self.assertEqual(res["title"], "T")
        self.assertEqual(_FakeYoutubeDL.construct_count, 4,
                         "the post-failure success must build a fresh (unpoisoned) instance")

    def test_retry_on_downloaderror_then_success(self):
        """Retry/backoff behaviour preserved: 2 DownloadErrors then success within one call."""
        attempts = {"n": 0}

        def hook(_inst, _url):
            attempts["n"] += 1
            if attempts["n"] < 3:
                raise DOWNLOAD_ERROR("temporary")
            return {"id": "v", "title": "T", "url": "https://x/audio", "http_headers": {}}

        _HOOK["fn"] = hook
        res = yserv.get_audio_info("https://www.youtube.com/watch?v=vidretry000")
        self.assertEqual(res["title"], "T")
        self.assertEqual(attempts["n"], 3, "two retries then success")
        self.assertEqual(_FakeYoutubeDL.construct_count, 3,
                         "each attempt uses a fresh, discarded instance")

    def test_timeout_wraps_to_runtimeerror_without_retry(self):
        """Timeout path preserved: a gevent.Timeout surfaces as Runtime 'timed out' and is not retried."""
        calls = {"n": 0}

        def hook(_inst, _url):
            calls["n"] += 1
            raise _FakeTimeout(15)  # mimic the inner `with gevent.Timeout(15):` firing

        _HOOK["fn"] = hook
        with self.assertRaises(RuntimeError) as ctx:
            yserv.get_audio_info("https://www.youtube.com/watch?v=vidtimeout0")
        self.assertIn("timed out", str(ctx.exception))
        self.assertEqual(calls["n"], 1, "a timeout must NOT be retried")

    def test_in_flight_dedup_short_circuits(self):
        """Item 3: a concurrent caller for the same URL returns the shared in-flight
        result WITHOUT triggering a second extraction."""
        url = "https://www.youtube.com/watch?v=vidflight00"
        shared = {"title": "shared", "thumbnail": "", "audio_url": "u", "http_headers": {}}
        ar = _FAKE_MODULES["gevent"].event.AsyncResult()
        ar.set(shared)
        yserv._in_flight_extractions[url] = ar

        _FakeYoutubeDL.construct_count = 0
        res = yserv.get_audio_info(url)
        self.assertEqual(res["title"], "shared", "must return the shared in-flight result")
        self.assertEqual(_FakeYoutubeDL.construct_count, 0,
                         "in-flight dedup must not start a new extraction")


# ─────────────────────────────────────────────────────────────────────────────
# Regression guards for the UNCHANGED caching / expiry layer (youtube_audio_api)
# ─────────────────────────────────────────────────────────────────────────────

class TestAudioApiCacheAndExpiry(unittest.TestCase):
    def setUp(self):
        _reset_pool()
        self._orig_get_audio_info = api.get_audio_info

    def tearDown(self):
        api.get_audio_info = self._orig_get_audio_info

    def test_cache_short_circuits_extraction(self):
        """Item 2: once resolved, the stream-URL cache holds the payload so the next
        request is served from cache (the endpoint checks the cache before extracting)."""
        calls = {"n": 0}

        def fake_get_audio_info(url):
            calls["n"] += 1
            return {
                "title": "Song",
                "thumbnail": "thumb",
                "audio_url": "https://r3.googlevideo.com/videoplayback?expire=9999999999",
                "http_headers": {},
            }

        api.get_audio_info = fake_get_audio_info
        vid = "vidcacheAAA"

        payload = api._resolve_and_cache_stream(vid)
        self.assertEqual(calls["n"], 1, "first resolve calls extraction once")

        cached = api._stream_url_cache.get(vid)
        self.assertIsNotNone(cached, "payload must be cached for subsequent requests")
        self.assertEqual(cached["audio_url"], payload["audio_url"],
                         "the cached payload is what a repeat request returns before any extraction")

    def test_expiry_parsed_from_url(self):
        """Item 5: expiresAt is taken from the googlevideo `expire=` param when present."""
        def fake(url):
            return {
                "title": "S",
                "thumbnail": "",
                "audio_url": "https://r3.googlevideo.com/videoplayback?expire=9999999999&id=x",
                "http_headers": {},
            }

        api.get_audio_info = fake
        payload = api._resolve_and_cache_stream("videxpireAA")
        self.assertEqual(payload["expiresAt"], 9999999999)

    def test_expiry_fallback_when_absent(self):
        """Item 5: when no expire param is present, expiry falls back to ~now + 9000s (bounded)."""
        def fake(url):
            return {"title": "S", "thumbnail": "", "audio_url": "https://r3.googlevideo.com/vp?id=x", "http_headers": {}}

        api.get_audio_info = fake
        before = int(time.time())
        payload = api._resolve_and_cache_stream("vidfallbckA")
        self.assertGreaterEqual(payload["expiresAt"], before + 9000 - 5)
        self.assertLessEqual(payload["expiresAt"], before + 9000 + 5)

    def test_ttlcache_expired_entry_not_returned(self):
        """Item 5: the TTL cache never returns an entry past its expiry."""
        cache = api.ThreadSafeTTLCache(maxsize=10, ttl_seconds=9000.0)
        cache["k"] = {"v": 1}
        self.assertEqual(cache.get("k"), {"v": 1})
        # Force the stored expiry into the past, deterministically.
        with cache._lock:
            value, _old = cache._cache["k"]
            cache._cache["k"] = (value, time.time() - 1)
        self.assertIsNone(cache.get("k"), "an expired entry must not be returned")

    def test_ttlcache_is_bounded(self):
        """Item 5 (bound): the TTL cache evicts the oldest entry beyond maxsize."""
        cache = api.ThreadSafeTTLCache(maxsize=2, ttl_seconds=9000.0)
        cache["a"] = {"v": 1}
        cache["b"] = {"v": 2}
        cache["c"] = {"v": 3}
        self.assertLessEqual(len(cache._cache), 2, "cache must stay within maxsize")
        self.assertIsNone(cache.get("a"), "oldest entry must be evicted")
        self.assertIsNotNone(cache.get("c"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
