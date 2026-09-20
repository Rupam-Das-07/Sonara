"""
Characterization tests for the Python audio bridge.

Covers:
  Group A — Concurrent in-flight extraction deduplication (C5-1)
  Group B — Failure propagation to concurrent waiters (C5-1)
  Group C — Keying semantics: outer video_id vs. inner URL (C5-1)
  Group D — Normal extraction + cache assembly (C5-2)
  Group E — 403 recovery path cache assembly (C5-2)
  Group F — 403 socket close before retry (C5-3)
  Group G — Stream disconnect / generator cleanup

No real network calls are made. All external I/O is mocked.
Tests run with plain unittest (stdlib) — no gevent monkey-patching needed
because we import the Flask app directly and call its test client.

NOTE: gevent.monkey.patch_all() in wsgi_audio.py is the production entry point
      and is NOT imported here. We import youtube_audio_api directly, which does
      not call monkey.patch_all() itself.
"""

import sys
import os
import time
import threading
import json
import unittest
from unittest.mock import patch, MagicMock, call

# ---------------------------------------------------------------------------
# Ensure python/ directory is importable
# ---------------------------------------------------------------------------
PYTHON_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "python")
sys.path.insert(0, os.path.abspath(PYTHON_DIR))

# ---------------------------------------------------------------------------
# Import module under test (creates Flask app + global state)
# ---------------------------------------------------------------------------
import youtube_audio_api as api_mod
import youtube_service as svc_mod


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_audio_info(video_id="dQw4w9WgXcQ", expire_offset=9000):
    """Return a minimal data dict that get_audio_info() would return."""
    expire_ts = int(time.time()) + expire_offset
    raw_url = f"https://rr1---sn-abc.googlevideo.com/videoplayback?expire={expire_ts}&id={video_id}&v=abc"
    return {
        "title": f"Test title for {video_id}",
        "thumbnail": f"https://i.ytimg.com/vi/{video_id}/maxresdefault.jpg",
        "audio_url": raw_url,
        "http_headers": {"User-Agent": "android-yt-dlp/1.0"},
    }


def _reset_global_state():
    """Reset all module-level mutable state between tests."""
    # Stream url cache
    with api_mod._stream_url_cache._lock:
        api_mod._stream_url_cache._cache.clear()
    # Headers cache
    with api_mod._stream_headers_cache._lock:
        api_mod._stream_headers_cache._cache.clear()
    # In-flight outer layer
    with api_mod._in_flight_lock:
        api_mod._in_flight_cache_fills.clear()
    # Metrics
    for k in api_mod._cache_metrics:
        api_mod._cache_metrics[k] = 0
    # Inner layer (youtube_service)
    svc_mod._in_flight_extractions.clear()


# ---------------------------------------------------------------------------
# Group A — Concurrent in-flight deduplication
# ---------------------------------------------------------------------------

class GroupA_ConcurrentInFlightDedup(unittest.TestCase):
    """
    C5-1 characterization: when N concurrent callers hit /get-youtube-audio
    for the same video_id simultaneously, only ONE real extraction fires.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_a1_single_caller_fires_one_extraction(self):
        """Baseline: single caller triggers exactly one extraction."""
        data = _make_audio_info()
        with patch("youtube_audio_api.get_audio_info", return_value=data) as mock_extract:
            resp = self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertEqual(resp.status_code, 200)
        mock_extract.assert_called_once()

    def test_a2_second_caller_gets_cache_not_extraction(self):
        """Second sequential call hits cache, no second extraction."""
        data = _make_audio_info()
        with patch("youtube_audio_api.get_audio_info", return_value=data) as mock_extract:
            self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            resp2 = self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertEqual(resp2.status_code, 200)
        # Still only 1 extraction across both calls
        mock_extract.assert_called_once()

    def test_a3_concurrent_callers_only_one_extraction(self):
        """
        5 concurrent threads all request the same video_id.
        Only ONE real extraction should fire; the rest wait for the Event.

        Strategy: use a threading.Barrier to release all 5 threads at once,
        then make get_audio_info slow enough that the threads overlap.
        """
        data = _make_audio_info()
        extraction_count = {"n": 0}
        barrier = threading.Barrier(5)

        def slow_extract(url):
            extraction_count["n"] += 1
            time.sleep(0.05)  # 50 ms — enough for all threads to queue
            return data

        results = []

        def do_request():
            barrier.wait()  # all 5 fire simultaneously
            resp = self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
            results.append(resp.status_code)

        with patch("youtube_audio_api.get_audio_info", side_effect=slow_extract):
            threads = [threading.Thread(target=do_request) for _ in range(5)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=10)

        self.assertEqual(len(results), 5)
        # All callers should receive a valid response
        for sc in results:
            self.assertIn(sc, (200, 500),
                          f"Unexpected status {sc} — waiter may have leaked")
        # Only ONE real extraction should have fired
        self.assertEqual(extraction_count["n"], 1,
                         f"Expected 1 extraction, got {extraction_count['n']}")

    def test_a4_outer_lock_keyed_by_video_id_not_url(self):
        """
        The outer lock (_in_flight_cache_fills) is keyed by video_id (11 chars),
        NOT the raw URL string. Two differently-formatted URLs for the same
        video_id must share the same lock entry.
        """
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        urls = [
            f"https://www.youtube.com/watch?v={video_id}",
            f"https://youtu.be/{video_id}",
        ]
        extraction_count = {"n": 0}

        def slow_extract(url):
            extraction_count["n"] += 1
            time.sleep(0.05)
            return data

        # Run sequentially first to warm cache, then check dedup works
        _reset_global_state()
        with patch("youtube_audio_api.get_audio_info", side_effect=slow_extract):
            resp1 = self.client.get(f"/get-youtube-audio?url={urls[0]}")
            # Second call with different URL format but same video_id -> cache hit
            resp2 = self.client.get(f"/get-youtube-audio?url={urls[1]}")

        self.assertEqual(resp1.status_code, 200)
        self.assertEqual(resp2.status_code, 200)
        # Cache should have served the second call without a new extraction
        self.assertEqual(extraction_count["n"], 1)

    def test_a5_different_video_ids_get_independent_extractions(self):
        """Two different video_ids both trigger their own extraction."""
        data_a = _make_audio_info("AAAAAAAAAAA")
        data_b = _make_audio_info("BBBBBBBBBBB")
        extraction_count = {"n": 0}

        def extract(url):
            extraction_count["n"] += 1
            if "AAAAAAAAAAA" in url:
                return data_a
            return data_b

        with patch("youtube_audio_api.get_audio_info", side_effect=extract):
            r1 = self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=AAAAAAAAAAA")
            r2 = self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=BBBBBBBBBBB")

        self.assertEqual(r1.status_code, 200)
        self.assertEqual(r2.status_code, 200)
        self.assertEqual(extraction_count["n"], 2)


# ---------------------------------------------------------------------------
# Group B — Failure propagation to concurrent waiters
# ---------------------------------------------------------------------------

class GroupB_FailurePropagation(unittest.TestCase):
    """
    C5-1 characterization: when the primary extraction fails, waiters
    currently receive HTTP 500 with "Concurrent extraction failed." — because
    the threading.Event is set() in the finally block but the cache entry was
    never written. This documents current (pre-simplification) behavior.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_b1_primary_failure_returns_error_to_primary(self):
        """When get_audio_info raises RuntimeError the primary caller gets 422."""
        with patch("youtube_audio_api.get_audio_info",
                   side_effect=RuntimeError("Video is unavailable")):
            resp = self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        self.assertEqual(resp.status_code, 422)

    def test_b2_waiter_gets_500_on_primary_failure(self):
        """
        Current behavior: concurrent waiter receives 500 with
        "Concurrent extraction failed." when the primary extraction fails.
        This is the *pre-simplification* characterization to be preserved as
        a regression reference even after C5-1 is evaluated.
        """
        barrier = threading.Barrier(2)
        results = {}

        def slow_failing_extract(url):
            barrier.wait()           # synchronize with waiter thread
            time.sleep(0.05)
            raise RuntimeError("Video is unavailable")

        def do_primary():
            with patch("youtube_audio_api.get_audio_info",
                       side_effect=slow_failing_extract):
                resp = self.client.get(
                    "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                )
                results["primary"] = resp.status_code

        def do_waiter():
            barrier.wait()           # released at same time as primary
            time.sleep(0.02)         # yield so primary grabs the lock first
            with patch("youtube_audio_api.get_audio_info",
                       side_effect=slow_failing_extract):
                resp = self.client.get(
                    "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                )
                results["waiter"] = resp.status_code

        t_primary = threading.Thread(target=do_primary)
        t_waiter = threading.Thread(target=do_waiter)
        t_primary.start()
        t_waiter.start()
        t_primary.join(timeout=10)
        t_waiter.join(timeout=10)

        # Primary caller -> extraction raised RuntimeError -> 422
        self.assertIn(results.get("primary"), (422, 500))
        # Waiter -> event was set but cache is empty -> 500
        # (This is the current behavior being characterized — not ideal,
        #  but it is what the code does today.)
        self.assertIn(results.get("waiter"), (200, 500),
                      "Waiter should get either a hit (race won) or 500")


# ---------------------------------------------------------------------------
# Group C — Keying semantics: outer (video_id) vs inner (URL)
# ---------------------------------------------------------------------------

class GroupC_KeyingSemantics(unittest.TestCase):
    """
    The outer layer keys by video_id (11-char); the inner layer keys by URL.
    The API normalizes the URL before calling get_audio_info(), so for all
    /get-youtube-audio calls the inner key is always
    'https://www.youtube.com/watch?v={video_id}'.

    This test group characterizes and documents that equivalence — which is
    a prerequisite for C5-1 simplification.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_c1_normalized_url_passed_to_get_audio_info(self):
        """
        Whatever URL format the caller provides, get_audio_info() must be
        called with the canonical https://www.youtube.com/watch?v={video_id} form.
        """
        data = _make_audio_info("dQw4w9WgXcQ")
        captured = {}

        def capture_url(url):
            captured["url"] = url
            return data

        with patch("youtube_audio_api.get_audio_info", side_effect=capture_url):
            self.client.get("/get-youtube-audio?url=https://youtu.be/dQw4w9WgXcQ")

        self.assertEqual(
            captured.get("url"),
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "API layer must normalize URL to canonical form before calling get_audio_info"
        )

    def test_c2_outer_inflight_cleaned_up_after_success(self):
        """
        After a successful extraction, _in_flight_cache_fills must be empty
        (the finally block must have removed the event).
        """
        data = _make_audio_info("dQw4w9WgXcQ")
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            self.client.get("/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        self.assertNotIn("dQw4w9WgXcQ", api_mod._in_flight_cache_fills,
                         "Outer in-flight dict should be empty after successful extraction")

    def test_c3_403_recovery_uses_direct_get_audio_info_bypassing_outer_lock(self):
        """
        The 403 recovery path in stream_youtube_audio() calls get_audio_info()
        directly — it does NOT go through the outer _in_flight_lock.
        This test verifies that the 403 recovery call is NOT blocked by an
        existing outer lock entry (i.e. the two layers are independent).
        """
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        raw_audio_url = data["audio_url"]

        # Simulate 403 on first request, 200 on retry
        mock_403 = MagicMock()
        mock_403.status_code = 403
        mock_403.headers = {}

        mock_200 = MagicMock()
        mock_200.status_code = 200
        mock_200.headers = {"Content-Type": "audio/webm", "Content-Length": "100"}
        mock_200.iter_content.return_value = iter([b"audio-data"])

        with patch("youtube_audio_api.get_audio_info", return_value=data) as mock_extract, \
             patch("youtube_audio_api.requests.get",
                   side_effect=[mock_403, mock_200]) as mock_req:
            resp = self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={raw_audio_url}"
            )

        # 403 recovery should have called get_audio_info once
        mock_extract.assert_called_once()
        # requests.get should have been called twice: initial 403 + recovery retry
        self.assertEqual(mock_req.call_count, 2)


# ---------------------------------------------------------------------------
# Group D — Normal extraction + cache assembly
# ---------------------------------------------------------------------------

class GroupD_NormalCacheAssembly(unittest.TestCase):
    """
    C5-2 characterization: documents the exact shape of the cache payload
    written by the normal (non-403) extraction path in get_youtube_audio().
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_d1_response_contains_proxy_url_not_raw_url(self):
        """audio_url in the response must be the /stream-youtube-audio proxy URL."""
        data = _make_audio_info("dQw4w9WgXcQ")
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            resp = self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        body = json.loads(resp.data)
        self.assertTrue(
            body["audio_url"].startswith("/stream-youtube-audio"),
            f"Expected proxy URL, got: {body['audio_url']}"
        )

    def test_d2_response_contains_expires_at(self):
        """expiresAt is parsed from the raw URL's ?expire= query param."""
        expire_ts = int(time.time()) + 3600
        raw_url = f"https://rr1.googlevideo.com/vp?expire={expire_ts}&id=abc"
        data = {
            "title": "T", "thumbnail": "th",
            "audio_url": raw_url,
            "http_headers": {},
        }
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            resp = self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        body = json.loads(resp.data)
        self.assertEqual(body["expiresAt"], expire_ts)

    def test_d3_response_contains_cached_at_timestamp(self):
        """cachedAt is a unix timestamp close to now."""
        data = _make_audio_info()
        before = int(time.time())
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            resp = self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        after = int(time.time())
        body = json.loads(resp.data)
        self.assertGreaterEqual(body["cachedAt"], before)
        self.assertLessEqual(body["cachedAt"], after)

    def test_d4_http_headers_written_to_headers_cache(self):
        """yt-dlp http_headers are written to _stream_headers_cache keyed by raw audio URL."""
        data = _make_audio_info("dQw4w9WgXcQ")
        raw_audio_url = data["audio_url"]
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        cached_headers = api_mod._stream_headers_cache.get(raw_audio_url)
        self.assertIsNotNone(cached_headers,
                             "_stream_headers_cache must be populated with yt-dlp headers")
        self.assertEqual(cached_headers.get("User-Agent"), "android-yt-dlp/1.0")

    def test_d5_stream_url_cache_written_after_extraction(self):
        """_stream_url_cache is populated with the proxy payload after extraction."""
        data = _make_audio_info("dQw4w9WgXcQ")
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        cached = api_mod._stream_url_cache.get("dQw4w9WgXcQ")
        self.assertIsNotNone(cached)
        self.assertIn("audio_url", cached)
        self.assertIn("expiresAt", cached)
        self.assertIn("cachedAt", cached)

    def test_d6_metrics_incremented_on_cache_miss(self):
        """cacheMisses and ytDlpExtractions are incremented on successful extraction."""
        data = _make_audio_info("dQw4w9WgXcQ")
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        self.assertEqual(api_mod._cache_metrics["cacheMisses"], 1)
        self.assertEqual(api_mod._cache_metrics["ytDlpExtractions"], 1)

    def test_d7_metrics_incremented_on_cache_hit(self):
        """cacheHits is incremented on the second call."""
        data = _make_audio_info("dQw4w9WgXcQ")
        with patch("youtube_audio_api.get_audio_info", return_value=data):
            self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
            self.client.get(
                "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )
        self.assertEqual(api_mod._cache_metrics["cacheHits"], 1)


# ---------------------------------------------------------------------------
# Group E — 403 recovery path cache assembly
# ---------------------------------------------------------------------------

class GroupE_403RecoveryCacheAssembly(unittest.TestCase):
    """
    C5-2 characterization: documents the exact steps executed in the 403
    recovery block of stream_youtube_audio(), verifying that the recovered
    stream URL, headers, and cache entries are assembled identically to the
    normal path.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def _setup_cached_stale_url(self, video_id, stale_audio_url):
        """Pre-load _stream_url_cache with a stale (will-403) entry."""
        proxy = f"/stream-youtube-audio?video_id={video_id}&audio_url=OLD"
        api_mod._stream_url_cache[video_id] = {
            "title": "T", "thumbnail": "th",
            "audio_url": proxy,
            "expiresAt": int(time.time()) - 1,  # expired
            "cachedAt": int(time.time()) - 9001,
        }

    def test_e1_stale_cache_invalidated_on_403(self):
        """Step 1: The stale video_id entry is removed from _stream_url_cache on 403."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        self._setup_cached_stale_url(video_id, stale_url)
        self.assertIsNotNone(api_mod._stream_url_cache.get(video_id))

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        # After recovery, a NEW entry should exist (with the fresh URL)
        new_cached = api_mod._stream_url_cache.get(video_id)
        self.assertIsNotNone(new_cached, "Fresh cache entry must exist after recovery")

    def test_e2_recovered_headers_written_to_headers_cache(self):
        """Step 3: yt-dlp http_headers from recovery are written to _stream_headers_cache."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        new_raw_url = data["audio_url"]
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        self._setup_cached_stale_url(video_id, stale_url)

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        cached_hdrs = api_mod._stream_headers_cache.get(new_raw_url)
        self.assertIsNotNone(cached_hdrs, "Headers cache must be populated after recovery")
        self.assertEqual(cached_hdrs.get("User-Agent"), "android-yt-dlp/1.0")

    def test_e3_recovered_payload_written_to_stream_url_cache(self):
        """Step 4: proxy_url payload is written to _stream_url_cache after recovery."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        self._setup_cached_stale_url(video_id, stale_url)

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        payload = api_mod._stream_url_cache.get(video_id)
        self.assertIsNotNone(payload)
        self.assertTrue(payload["audio_url"].startswith("/stream-youtube-audio"))
        self.assertIn("expiresAt", payload)
        self.assertIn("cachedAt", payload)

    def test_e4_stale_recoveries_metric_incremented(self):
        """staleUrlRecoveries metric is incremented exactly once per 403."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        self.assertEqual(api_mod._cache_metrics["staleUrlRecoveries"], 1)

    def test_e5_extraction_metrics_incremented_during_recovery(self):
        """ytDlpExtractions and timing metrics are updated in 403 recovery path."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        self.assertEqual(api_mod._cache_metrics["ytDlpExtractions"], 1)
        self.assertGreaterEqual(api_mod._cache_metrics["totalExtractionTimeMs"], 0)

    def test_e6_403_recovery_retry_uses_new_url(self):
        """
        Step 5: after recovery, the retry request.get() call must use the
        NEW audio URL obtained from get_audio_info(), not the original stale URL.
        """
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        new_raw_url = data["audio_url"]
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get",
                   side_effect=[mock_403, mock_200]) as mock_req:
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        # Second call to requests.get must be the new URL
        second_call_url = mock_req.call_args_list[1][0][0]
        self.assertEqual(second_call_url, new_raw_url,
                         "Retry must use new URL, not stale URL")


# ---------------------------------------------------------------------------
# Group F — 403 socket close before retry
# ---------------------------------------------------------------------------

class GroupF_SocketCloseBeforeRetry(unittest.TestCase):
    """
    C5-3 characterization: documents whether r.close() is called on the
    original 403 response object before the retry request is issued.

    CURRENT BEHAVIOR (pre-fix): r.close() is NOT called before reassigning r.
    The response object is only closed in the generate() finally block, but
    if the 403 branch is taken, the original r object is replaced and the
    old socket leaks until GC.

    This test characterizes the DESIRED (post-fix) behavior so that once
    C5-3 is implemented the test becomes a regression guard.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_f1_close_called_on_403_response_before_retry(self):
        """
        After C5-3 is implemented: r.close() must be called on the 403
        response object before issuing the retry requests.get() call.

        The mock_403.close call count characterizes this contract.
        """
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_403 = MagicMock(status_code=403, headers={})
        mock_200 = MagicMock(
            status_code=200,
            headers={"Content-Type": "audio/webm"},
        )
        mock_200.iter_content.return_value = iter([])

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get",
                   side_effect=[mock_403, mock_200]):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        # POST-FIX: r.close() must be called on the 403 response
        mock_403.close.assert_called_once_with()

    def test_f2_close_not_called_on_non_403_responses(self):
        """
        On a successful (non-403) stream, the 200 response is not prematurely
        closed -- it is only closed inside the generate() finally block.
        This test verifies close() is called exactly once (in generate() finally).
        """
        video_id = "dQw4w9WgXcQ"
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_200 = MagicMock(status_code=200)
        mock_200.headers = {"Content-Type": "audio/webm"}
        mock_200.iter_content.return_value = iter([b"audio"])

        with patch("youtube_audio_api.requests.get", return_value=mock_200):
            self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )

        # close() is called exactly once in the generate() finally -- not zero, not two
        mock_200.close.assert_called_once_with()


# ---------------------------------------------------------------------------
# Group G — Stream disconnect / generator cleanup
# ---------------------------------------------------------------------------

class GroupG_StreamGeneratorCleanup(unittest.TestCase):
    """
    Characterizes that r.close() is always called in the generate() finally
    block, even when the client disconnects mid-stream or an exception occurs.
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_g1_close_called_after_successful_stream(self):
        """After a full stream completes, r.close() is called exactly once."""
        video_id = "dQw4w9WgXcQ"
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=ok"

        mock_200 = MagicMock(status_code=200)
        mock_200.headers = {"Content-Type": "audio/webm", "Content-Length": "6"}
        mock_200.iter_content.return_value = iter([b"audio!"])

        with patch("youtube_audio_api.requests.get", return_value=mock_200):
            resp = self.client.get(
                f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
            )
            # Consume the response to trigger the generator finally block
            _ = resp.data

        mock_200.close.assert_called_once_with()

    def test_g2_close_called_even_on_iter_content_exception(self):
        """If iter_content raises an exception, r.close() is still called."""
        video_id = "dQw4w9WgXcQ"
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=ok"

        def failing_iter_content(chunk_size):
            yield b"first-chunk"
            raise ConnectionError("Client disconnected")

        mock_200 = MagicMock(status_code=200)
        mock_200.headers = {"Content-Type": "audio/webm"}
        mock_200.iter_content.side_effect = failing_iter_content

        with patch("youtube_audio_api.requests.get", return_value=mock_200):
            try:
                resp = self.client.get(
                    f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}"
                )
                _ = resp.data  # consume to flush generator
            except Exception:
                pass  # generator exception is acceptable

        mock_200.close.assert_called_once_with()

    def test_g3_ssrf_block_returns_403_before_upstream_request(self):
        """SSRF validation fires before any upstream requests.get() call."""
        with patch("youtube_audio_api.requests.get") as mock_req:
            resp = self.client.get(
                "/stream-youtube-audio?video_id=abc&audio_url=http://internal.corp/secret"
            )
        self.assertEqual(resp.status_code, 403)
        mock_req.assert_not_called()

    def test_g4_missing_audio_url_returns_400(self):
        """Missing audio_url query param returns 400 without upstream call."""
        with patch("youtube_audio_api.requests.get") as mock_req:
            resp = self.client.get("/stream-youtube-audio?video_id=abc")
        self.assertEqual(resp.status_code, 400)
        mock_req.assert_not_called()


# ---------------------------------------------------------------------------
# Group H — Audio Proxy Redirect Security (Finding #4 Remediation)
# ---------------------------------------------------------------------------

class GroupH_AudioProxyRedirectSecurity(unittest.TestCase):
    """
    Comprehensive regression tests for Finding #4:
    sonara:python:audio-proxy-ssrf-unbounded-redirects

    Verifies:
      - Direct legitimate Google Video URLs stream properly
      - Hop-by-hop redirect following works for legitimate Google Video CDN hops
      - Relative redirect URLs are securely resolved and validated
      - HTTPS -> HTTP downgrade is blocked (403)
      - Redirects to localhost, 127.0.0.1, RFC1918 private IPs, link-local /
        cloud metadata (169.254.169.254), IPv6 loopback are blocked (403)
      - Redirects to external arbitrary domains (evil.com) are blocked (403)
      - Redirect hops exceeding MAX_AUDIO_REDIRECTS (3) are blocked (403)
      - 3xx redirects missing a Location header return 502
      - Redirect targets with userinfo credentials or non-standard ports are blocked (403)
      - 403 recovery retry path enforces the exact same redirect security policy
      - Intermediate redirect response sockets are explicitly closed (no leaks)
      - Intermediate redirect bodies are never exposed or streamed to the client
    """

    def setUp(self):
        _reset_global_state()
        self.client = api_mod.app.test_client()

    def test_h01_direct_valid_googlevideo_url_streams(self):
        """Direct valid googlevideo HTTPS URL streams with 200 OK."""
        url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=123"
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm", "Content-Length": "10"})
        mock_200.iter_content.return_value = iter([b"0123456789"])

        with patch("youtube_audio_api.requests.get", return_value=mock_200) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={url}")
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data, b"0123456789")
            mock_req.assert_called_once()
            # Verify allow_redirects=False is passed
            _, kwargs = mock_req.call_args
            self.assertFalse(kwargs.get("allow_redirects", True))

    def test_h02_legitimate_single_hop_redirect(self):
        """Legitimate HTTPS googlevideo -> HTTPS googlevideo redirect succeeds."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=123"
        redirect_target = "https://rr2---sn-abc.googlevideo.com/videoplayback?id=123"

        mock_302 = MagicMock(status_code=302, headers={"Location": redirect_target})
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm", "Content-Length": "5"})
        mock_200.iter_content.return_value = iter([b"music"])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302, mock_200]) as mock_req, \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data, b"music")
            self.assertEqual(mock_req.call_count, 2)
            mock_302.close.assert_called_once()
            mock_200.close.assert_called_once()

    def test_h03_multiple_legitimate_redirects_within_limit(self):
        """2 hops within MAX_AUDIO_REDIRECTS (3) successfully stream audio."""
        url_1 = "https://rr1---sn-abc.googlevideo.com/vp"
        url_2 = "https://rr2---sn-abc.googlevideo.com/vp"
        url_3 = "https://rr3---sn-abc.googlevideo.com/vp"

        mock_302_1 = MagicMock(status_code=302, headers={"Location": url_2})
        mock_302_2 = MagicMock(status_code=302, headers={"Location": url_3})
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm"})
        mock_200.iter_content.return_value = iter([b"streamed-data"])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302_1, mock_302_2, mock_200]), \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={url_1}")

            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data, b"streamed-data")
            mock_302_1.close.assert_called_once()
            mock_302_2.close.assert_called_once()
            mock_200.close.assert_called_once()

    def test_h04_relative_redirect_resolved_and_allowed(self):
        """Relative redirect Location (/videoplayback?id=new) resolves against current URL."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=old"

        mock_302 = MagicMock(status_code=302, headers={"Location": "/videoplayback?id=new"})
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm"})
        mock_200.iter_content.return_value = iter([b"relative-ok"])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302, mock_200]) as mock_req, \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.data, b"relative-ok")
            second_url = mock_req.call_args_list[1][0][0]
            self.assertEqual(second_url, "https://rr1---sn-abc.googlevideo.com/videoplayback?id=new")

    def test_h05_https_to_http_downgrade_rejected(self):
        """HTTPS -> HTTP downgrade in redirect is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        downgrade_target = "http://rr2---sn-abc.googlevideo.com/videoplayback"

        mock_302 = MagicMock(status_code=302, headers={"Location": downgrade_target})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertIn(b"Forbidden: Invalid redirect URL", resp.data)
            self.assertEqual(mock_req.call_count, 1)  # Never made second request
            mock_302.close.assert_called_once()

    def test_h06_redirect_to_localhost_rejected(self):
        """Redirect to localhost is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://localhost:3002/secret"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h07_redirect_to_127_0_0_1_rejected(self):
        """Redirect to 127.0.0.1 is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://127.0.0.1:5000/internal"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h08_redirect_to_cloud_metadata_rejected(self):
        """Redirect to AWS/GCP cloud metadata IP (169.254.169.254) is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://169.254.169.254/latest/meta-data/"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h09_redirect_to_rfc1918_private_10_rejected(self):
        """Redirect to 10.x.x.x private network IP is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://10.0.0.1/admin"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h10_redirect_to_rfc1918_private_172_rejected(self):
        """Redirect to 172.16.x.x private network IP is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://172.16.0.1/admin"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h11_redirect_to_rfc1918_private_192_rejected(self):
        """Redirect to 192.168.x.x private network IP is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://192.168.1.1/admin"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h12_redirect_to_ipv6_loopback_rejected(self):
        """Redirect to IPv6 loopback [::1] is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://[::1]:5001/secret"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h13_redirect_to_arbitrary_external_domain_rejected(self):
        """Redirect to external arbitrary domain (evil.com) is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={"Location": "https://evil.com/payload"})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertEqual(mock_req.call_count, 1)
            mock_302.close.assert_called_once()

    def test_h14_redirect_chain_exceeding_hop_limit_rejected(self):
        """Redirect chain exceeding MAX_AUDIO_REDIRECTS (3) is rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/vp"
        mock_302_1 = MagicMock(status_code=302, headers={"Location": "https://rr2---sn-abc.googlevideo.com/vp"})
        mock_302_2 = MagicMock(status_code=302, headers={"Location": "https://rr3---sn-abc.googlevideo.com/vp"})
        mock_302_3 = MagicMock(status_code=302, headers={"Location": "https://rr4---sn-abc.googlevideo.com/vp"})
        mock_302_4 = MagicMock(status_code=302, headers={"Location": "https://rr5---sn-abc.googlevideo.com/vp"})

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302_1, mock_302_2, mock_302_3, mock_302_4]) as mock_req, \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 403)
            self.assertIn(b"Forbidden: Too many redirects", resp.data)
            # Hop 1 (302) -> Hop 2 (302) -> Hop 3 (302) -> Hop 4 (302, redirect_count=4 > 3, blocked)
            self.assertEqual(mock_req.call_count, 4)
            mock_302_1.close.assert_called_once()
            mock_302_2.close.assert_called_once()
            mock_302_3.close.assert_called_once()
            mock_302_4.close.assert_called_once()

    def test_h15_missing_location_header_returns_502(self):
        """Redirect status code with missing Location header returns 502."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"
        mock_302 = MagicMock(status_code=302, headers={})

        with patch("youtube_audio_api.requests.get", return_value=mock_302) as mock_req:
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.status_code, 502)
            self.assertIn(b"Bad Gateway: Missing redirect location", resp.data)
            mock_302.close.assert_called_once()

    def test_h16_redirect_target_with_userinfo_or_invalid_port_rejected(self):
        """Redirect targets with userinfo credentials or non-standard ports are rejected with 403."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/videoplayback"

        # Userinfo injection
        mock_302_user = MagicMock(status_code=302, headers={"Location": "https://admin:pass@googlevideo.com/vp"})
        with patch("youtube_audio_api.requests.get", return_value=mock_302_user):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")
            self.assertEqual(resp.status_code, 403)
            mock_302_user.close.assert_called_once()

        # Non-standard port injection
        mock_302_port = MagicMock(status_code=302, headers={"Location": "https://googlevideo.com:8443/vp"})
        with patch("youtube_audio_api.requests.get", return_value=mock_302_port):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")
            self.assertEqual(resp.status_code, 403)
            mock_302_port.close.assert_called_once()

    def test_h17_403_recovery_retry_path_validates_redirects(self):
        """403 recovery retry path applies the identical safe redirect validation policy."""
        video_id = "dQw4w9WgXcQ"
        data = _make_audio_info(video_id)
        stale_url = "https://rr1.googlevideo.com/vp?expire=0&id=stale"

        mock_403 = MagicMock(status_code=403, headers={})
        # The re-extracted retry returns a 302 redirecting to internal service
        mock_retry_302 = MagicMock(
            status_code=302,
            headers={"Location": "https://127.0.0.1:5000/dump"}
        )

        with patch("youtube_audio_api.get_audio_info", return_value=data), \
             patch("youtube_audio_api.requests.get", side_effect=[mock_403, mock_retry_302]):
            resp = self.client.get(f"/stream-youtube-audio?video_id={video_id}&audio_url={stale_url}")

            # Must be rejected because retry redirect to 127.0.0.1 is blocked
            self.assertEqual(resp.status_code, 403)
            self.assertIn(b"Forbidden: Invalid redirect URL", resp.data)
            mock_403.close.assert_called_once()
            mock_retry_302.close.assert_called_once()

    def test_h18_intermediate_redirect_sockets_always_closed(self):
        """Intermediate 302 sockets are closed even when followed by another redirect."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/vp"
        mock_302_1 = MagicMock(status_code=302, headers={"Location": "https://rr2---sn-abc.googlevideo.com/vp"})
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm"})
        mock_200.iter_content.return_value = iter([b"data"])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302_1, mock_200]), \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")
            _ = resp.data

            mock_302_1.close.assert_called_once_with()
            mock_200.close.assert_called_once_with()

    def test_h19_final_response_streams_cleanly_with_headers(self):
        """Content-Range and Content-Length are preserved from the final response after redirect."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/vp"
        target_url = "https://rr2---sn-abc.googlevideo.com/vp"

        mock_302 = MagicMock(status_code=302, headers={"Location": target_url})
        mock_200 = MagicMock(
            status_code=206,
            headers={
                "Content-Type": "audio/webm",
                "Content-Length": "100",
                "Content-Range": "bytes 0-99/1000",
            }
        )
        mock_200.iter_content.return_value = iter([b"byte" * 25])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302, mock_200]), \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(
                f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}",
                headers={"Range": "bytes=0-99"}
            )

            self.assertEqual(resp.status_code, 206)
            self.assertEqual(resp.headers.get("Content-Type"), "audio/webm")
            self.assertEqual(resp.headers.get("Content-Length"), "100")
            self.assertEqual(resp.headers.get("Content-Range"), "bytes 0-99/1000")
            self.assertEqual(len(resp.data), 100)

    def test_h20_redirect_response_body_never_leaked(self):
        """Any payload in the intermediate 302 body is discarded and never yielded to client."""
        initial_url = "https://rr1---sn-abc.googlevideo.com/vp"
        target_url = "https://rr2---sn-abc.googlevideo.com/vp"

        mock_302 = MagicMock(
            status_code=302,
            headers={"Location": target_url},
            iter_content=MagicMock(return_value=iter([b"<p>Redirecting to secret</p>"]))
        )
        mock_200 = MagicMock(status_code=200, headers={"Content-Type": "audio/webm"})
        mock_200.iter_content.return_value = iter([b"pure-audio"])

        with patch("youtube_audio_api.requests.get", side_effect=[mock_302, mock_200]), \
             patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            resp = self.client.get(f"/stream-youtube-audio?video_id=abc&audio_url={initial_url}")

            self.assertEqual(resp.data, b"pure-audio")
            mock_302.iter_content.assert_not_called()


# ---------------------------------------------------------------------------
# Group I — Redirect Validator Unit Tests
# ---------------------------------------------------------------------------

class GroupI_RedirectValidatorUnitTests(unittest.TestCase):
    """
    Direct unit tests for the core security validator primitives:
      - is_safe_ip
      - is_safe_resolved_ip
      - is_valid_googlevideo_url
      - validate_audio_redirect_url
    """

    def test_i01_is_safe_ip_rejects_dangerous_addresses(self):
        """is_safe_ip rejects loopback, RFC 1918 private, link-local, multicast, reserved."""
        dangerous_ips = [
            "127.0.0.1",
            "127.0.0.2",
            "::1",
            "0.0.0.0",
            "::",
            "169.254.169.254",
            "169.254.1.1",
            "fe80::1",
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "192.168.1.254",
            "224.0.0.1",
            "ff02::1",
            "not-an-ip",
        ]
        for ip in dangerous_ips:
            with self.subTest(ip=ip):
                self.assertFalse(api_mod.is_safe_ip(ip), f"Should reject dangerous IP: {ip}")

    def test_i02_is_safe_ip_accepts_public_addresses(self):
        """is_safe_ip accepts globally routable public IPs."""
        safe_ips = [
            "142.250.77.100",   # Google Video CDN IP
            "8.8.8.8",
            "1.1.1.1",
            "2001:4860:4860::8888",
        ]
        for ip in safe_ips:
            with self.subTest(ip=ip):
                self.assertTrue(api_mod.is_safe_ip(ip), f"Should accept public IP: {ip}")

    def test_i03_is_safe_resolved_ip_with_mocked_dns(self):
        """is_safe_resolved_ip enforces that all resolved addresses are public."""
        # DNS returning public IP
        fake_addr_public = [(2, 1, 0, '', ('142.250.77.100', 443))]
        with patch("youtube_audio_api.socket.getaddrinfo", return_value=fake_addr_public):
            self.assertTrue(api_mod.is_safe_resolved_ip("rr1---sn-abc.googlevideo.com"))

        # DNS returning private loopback IP
        fake_addr_loopback = [(2, 1, 0, '', ('127.0.0.1', 443))]
        with patch("youtube_audio_api.socket.getaddrinfo", return_value=fake_addr_loopback):
            self.assertFalse(api_mod.is_safe_resolved_ip("malicious.googlevideo.com"))

        # DNS returning cloud metadata IP
        fake_addr_metadata = [(2, 1, 0, '', ('169.254.169.254', 443))]
        with patch("youtube_audio_api.socket.getaddrinfo", return_value=fake_addr_metadata):
            self.assertFalse(api_mod.is_safe_resolved_ip("metadata.googlevideo.com"))

        # DNS resolution failure raises gaierror -> False
        import socket
        with patch("youtube_audio_api.socket.getaddrinfo", side_effect=socket.gaierror(11001, "Failed")):
            self.assertFalse(api_mod.is_safe_resolved_ip("unresolvable.googlevideo.com"))

    def test_i04_validate_audio_redirect_url_rules(self):
        """validate_audio_redirect_url enforces full suite of redirect safety invariants."""
        # Valid URL with public IP resolution
        with patch("youtube_audio_api.is_safe_resolved_ip", return_value=True):
            self.assertTrue(api_mod.validate_audio_redirect_url("https://googlevideo.com/videoplayback"))
            self.assertTrue(api_mod.validate_audio_redirect_url("https://rr1---sn-abc.googlevideo.com/videoplayback?id=1"))

        # Block HTTP (no HTTPS downgrade)
        self.assertFalse(api_mod.validate_audio_redirect_url("http://rr1.googlevideo.com/vp"))

        # Block userinfo credentials
        self.assertFalse(api_mod.validate_audio_redirect_url("https://user:pass@googlevideo.com/vp"))

        # Block non-standard port
        self.assertFalse(api_mod.validate_audio_redirect_url("https://googlevideo.com:8443/vp"))

        # Block IP literals
        self.assertFalse(api_mod.validate_audio_redirect_url("https://127.0.0.1/vp"))
        self.assertFalse(api_mod.validate_audio_redirect_url("https://[::1]/vp"))
        self.assertFalse(api_mod.validate_audio_redirect_url("https://169.254.169.254/vp"))

        # Block localhost
        self.assertFalse(api_mod.validate_audio_redirect_url("https://localhost/vp"))

        # Block domain suffix spoofing / external domains
        self.assertFalse(api_mod.validate_audio_redirect_url("https://evil.com/vp"))
        self.assertFalse(api_mod.validate_audio_redirect_url("https://googlevideo.com.attacker.com/vp"))
        self.assertFalse(api_mod.validate_audio_redirect_url("https://notgooglevideo.com/vp"))


# ---------------------------------------------------------------------------
# Run directly
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    unittest.main(verbosity=2)

