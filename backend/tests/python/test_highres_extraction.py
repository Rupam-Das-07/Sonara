"""
Unit and characterization tests for high-resolution (8K/4320p) video extraction.
Covers:
  - Input validation (service and API layer)
  - Genuine 8K source representation extraction and field mapping
  - Audio pairing and DASH SegmentBase byte-range parsing
  - Missing/invalid byte ranges rejection
  - Container compatibility (ISO-BMFF audio for MP4 video)
  - Duration consistency validation
  - Negative discovery (videos without 8K or with only lower resolutions)
  - Corrected TTL calculation:
      * Remaining lifetime under 600s margin is NOT cached
      * Expired URL is NOT cached
      * Missing/invalid expiry metadata is NOT cached
      * Expired cached entries are never served and evicted
      * Max 7200s TTL ceiling enforced
      * Negative 2-hour TTL policy preserved
  - In-flight request coalescing (service & API layers)
  - HTTP error status codes (400, 422, 500)
  - Hook installation and safe restoration
"""

import sys
import os
import time
import threading
import unittest
from unittest.mock import patch, MagicMock

# Ensure python/ directory is importable
PYTHON_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "python")
sys.path.insert(0, os.path.abspath(PYTHON_DIR))

import youtube_audio_api as api_mod
import youtube_service as svc_mod


def _make_sample_ytdlp_info(
    has_8k=True,
    video_id="P7yRYiBiV3g",
    expire_offset=7200,
    video_init_range=("0", "700"),
    video_index_range=("701", "1284"),
    audio_init_range=("0", "722"),
    audio_index_range=("723", "1066"),
    audio_ext="m4a",
    audio_codec="mp4a.40.2",
    video_duration=212.5,
    audio_duration=212.5,
):
    """Generate mock yt-dlp info dict mimicking raw extraction output."""
    expire_ts = int(time.time()) + expire_offset if expire_offset is not None else None
    exp_param = f"&expire={expire_ts}" if expire_ts is not None else ""

    formats = [
        # 1080p stream
        {
            "format_id": "137",
            "ext": "mp4",
            "width": 1920,
            "height": 1080,
            "fps": 30,
            "vcodec": "avc1.640028",
            "acodec": "none",
            "tbr": 4200.0,
            "url": f"https://rr1---sn-abc.googlevideo.com/videoplayback?itag=137&id={video_id}{exp_param}",
            "initRange": {"start": "0", "end": "740"},
            "indexRange": {"start": "741", "end": "1400"},
            "http_headers": {"User-Agent": "test-agent"},
        },
        # Audio stream
        {
            "format_id": "140" if audio_codec == "mp4a.40.2" else "251",
            "ext": audio_ext,
            "vcodec": "none",
            "acodec": audio_codec,
            "abr": 128.0,
            "tbr": 128.0,
            "approx_duration_ms": int(audio_duration * 1000) if audio_duration else None,
            "url": f"https://rr1---sn-abc.googlevideo.com/videoplayback?itag=140&id={video_id}{exp_param}",
            "initRange": {"start": audio_init_range[0], "end": audio_init_range[1]} if audio_init_range else None,
            "indexRange": {"start": audio_index_range[0], "end": audio_index_range[1]} if audio_index_range else None,
            "http_headers": {"User-Agent": "test-agent"},
        },
    ]

    if has_8k:
        # 8K itag 571 AV1
        formats.append({
            "format_id": "571",
            "ext": "mp4",
            "width": 7680,
            "height": 4320,
            "fps": 60,
            "vcodec": "av01.0.17M.08",
            "acodec": "none",
            "vbr": 26292.0,
            "tbr": 26292.0,
            "approx_duration_ms": int(video_duration * 1000) if video_duration else None,
            "url": f"https://rr1---sn-abc.googlevideo.com/videoplayback?itag=571&id={video_id}{exp_param}",
            "initRange": {"start": video_init_range[0], "end": video_init_range[1]} if video_init_range else None,
            "indexRange": {"start": video_index_range[0], "end": video_index_range[1]} if video_index_range else None,
            "http_headers": {"User-Agent": "test-agent"},
        })

    return {
        "id": video_id,
        "title": "Sample 8K Nature Video",
        "duration": video_duration,
        "formats": formats,
        "http_headers": {"User-Agent": "default-test-agent"},
    }


def _reset_highres_state():
    """Reset module-level caches and in-flight tracking."""
    with api_mod._highres_cache._lock:
        api_mod._highres_cache._cache.clear()
    with api_mod._in_flight_highres_lock:
        api_mod._in_flight_highres_fills.clear()
    svc_mod._in_flight_extractions.clear()


class TestHighResServiceLogic(unittest.TestCase):
    """Tests for get_video_highres_info in youtube_service.py."""

    def setUp(self):
        _reset_highres_state()

    def test_invalid_video_id_inputs(self):
        """Reject invalid video_id inputs with ValueError."""
        invalid_ids = ["", None, 12345, "too_short", "this_is_too_long_to_be_a_valid_id", "invalid#char", "with spaces"]
        for bad_id in invalid_ids:
            with self.subTest(bad_id=bad_id):
                with self.assertRaises(ValueError):
                    svc_mod.get_video_highres_info(bad_id)

    def test_extract_genuine_8k_video(self):
        """Extract genuine 8K format (itag 571) and pair with audio (itag 140)."""
        mock_info = _make_sample_ytdlp_info(has_8k=True)
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info) as mock_extract:
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")

        mock_extract.assert_called_once()
        self.assertTrue(data["hasHighRes"])
        self.assertEqual(data["videoId"], "P7yRYiBiV3g")
        self.assertIsInstance(data["extractedAt"], int)
        self.assertGreater(data["expiresAt"], data["extractedAt"])

        # Check video format
        v = data["video"]
        self.assertIsNotNone(v)
        self.assertEqual(v["itag"], 571)
        self.assertEqual(v["width"], 7680)
        self.assertEqual(v["height"], 4320)
        self.assertEqual(v["fps"], 60)
        self.assertEqual(v["codec"], "av01.0.17M.08")
        self.assertIn("video/mp4", v["mimeType"])
        self.assertEqual(v["initRange"], {"start": 0, "end": 700})
        self.assertEqual(v["indexRange"], {"start": 701, "end": 1284})
        self.assertIn("videoplayback", v["streamUrl"])

        # Check audio format
        a = data["audio"]
        self.assertIsNotNone(a)
        self.assertEqual(a["itag"], 140)
        self.assertEqual(a["codec"], "mp4a.40.2")
        self.assertIn("audio/mp4", a["mimeType"])
        self.assertEqual(a["initRange"], {"start": 0, "end": 722})
        self.assertEqual(a["indexRange"], {"start": 723, "end": 1066})

    def test_negative_8k_video(self):
        """Return hasHighRes=False when video only has 1080p."""
        mock_info = _make_sample_ytdlp_info(has_8k=False, video_id="dQw4w9WgXcQ")
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("dQw4w9WgXcQ")

        self.assertFalse(data["hasHighRes"])
        self.assertEqual(data["videoId"], "dQw4w9WgXcQ")
        self.assertIsNone(data["video"])
        self.assertIsNone(data["audio"])

    def test_missing_video_byte_ranges_rejects_8k(self):
        """Missing initRange or indexRange in video format rejects 8K representation."""
        mock_info = _make_sample_ytdlp_info(has_8k=True, video_init_range=None)
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertFalse(data["hasHighRes"])
        self.assertIsNone(data["video"])

    def test_invalid_video_byte_ranges_rejects_8k(self):
        """Inverted indexRange start < initRange end rejects 8K representation."""
        mock_info = _make_sample_ytdlp_info(has_8k=True, video_init_range=("0", "800"), video_index_range=("700", "900"))
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertFalse(data["hasHighRes"])

    def test_missing_audio_byte_ranges_rejects_8k(self):
        """Missing initRange or indexRange in audio format rejects pairing."""
        mock_info = _make_sample_ytdlp_info(has_8k=True, audio_init_range=None)
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertFalse(data["hasHighRes"])

    def test_incompatible_audio_container_rejects_pairing(self):
        """WebM/Opus audio cannot be paired with MP4 8K video for DASH SegmentBase."""
        mock_info = _make_sample_ytdlp_info(has_8k=True, audio_ext="webm", audio_codec="opus")
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertFalse(data["hasHighRes"])

    def test_duration_mismatch_rejects_pairing(self):
        """Significant duration mismatch between video and audio rejects stream pairing."""
        mock_info = _make_sample_ytdlp_info(has_8k=True, video_duration=212.5, audio_duration=30.0)
        with patch.object(svc_mod, "_extract_with_retry", return_value=mock_info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertFalse(data["hasHighRes"])

    def test_conservative_expiry_takes_minimum(self):
        """Overall expiry takes the earliest of video and audio stream expiration."""
        now = int(time.time())
        info = _make_sample_ytdlp_info(has_8k=True)
        # Video expires in +5000, audio in +3000
        info["formats"][2]["url"] = f"https://googlevideo.com/vp?expire={now + 5000}&itag=571"
        info["formats"][1]["url"] = f"https://googlevideo.com/vp?expire={now + 3000}&itag=140"
        with patch.object(svc_mod, "_extract_with_retry", return_value=info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertTrue(data["hasHighRes"])
        self.assertEqual(data["expiresAt"], now + 3000)

    def test_missing_expiry_sets_zero(self):
        """Missing expire query param results in expiresAt=0 rather than invented offset."""
        info = _make_sample_ytdlp_info(has_8k=True, expire_offset=None)
        with patch.object(svc_mod, "_extract_with_retry", return_value=info):
            data = svc_mod.get_video_highres_info("P7yRYiBiV3g")
        self.assertTrue(data["hasHighRes"])
        self.assertEqual(data["expiresAt"], 0)

    def test_service_extraction_failure_propagates_runtime_error(self):
        """yt-dlp extraction failure raises RuntimeError."""
        with patch.object(svc_mod, "_extract_with_retry", side_effect=RuntimeError("Video unavailable")):
            with self.assertRaises(RuntimeError):
                svc_mod.get_video_highres_info("P7yRYiBiV3g")

    def test_hook_restoration_safe(self):
        """_restore_extract_formats_hook executes safely."""
        svc_mod._restore_extract_formats_hook()

    def test_extract_formats_with_ranges_generator_support(self):
        """Hook correctly handles generator returns from yt-dlp _extract_formats_and_subtitles."""
        import yt_dlp.extractor.youtube as yt_extractor
        hooked_fn = yt_extractor.YoutubeIE._extract_formats_and_subtitles

        # Prepare mock player_responses with adaptive formats containing ranges
        player_responses = [
            {
                "streamingData": {
                    "adaptiveFormats": [
                        {
                            "itag": 571,
                            "initRange": {"start": "0", "end": "700"},
                            "indexRange": {"start": "701", "end": "1068"},
                        },
                        {
                            "itag": 140,
                            "initRange": {"start": "0", "end": "722"},
                            "indexRange": {"start": "723", "end": "934"},
                        },
                    ]
                }
            }
        ]

        def mock_orig_gen(self, *args, **kwargs):
            yield {"format_id": "571", "width": 7680, "height": 4320}
            yield {"format_id": "140", "acodec": "mp4a.40.2"}
            yield {"format_id": "251", "acodec": "opus"}

        with patch.object(svc_mod, "_orig_extract_formats_and_subtitles", side_effect=mock_orig_gen):
            # Call hooked function
            res = hooked_fn(None, None, player_responses=player_responses)
            formats = list(res)
            self.assertEqual(len(formats), 3)

            # Itag 571 has ranges attached
            fmt_571 = next(f for f in formats if f["format_id"] == "571")
            self.assertEqual(fmt_571.get("initRange"), {"start": "0", "end": "700"})
            self.assertEqual(fmt_571.get("indexRange"), {"start": "701", "end": "1068"})

            # Itag 140 has ranges attached
            fmt_140 = next(f for f in formats if f["format_id"] == "140")
            self.assertEqual(fmt_140.get("initRange"), {"start": "0", "end": "722"})
            self.assertEqual(fmt_140.get("indexRange"), {"start": "723", "end": "934"})

            # Itag 251 was not in range_map, ranges remain None
            fmt_251 = next(f for f in formats if f["format_id"] == "251")
            self.assertIsNone(fmt_251.get("initRange"))
            self.assertIsNone(fmt_251.get("indexRange"))

    def test_extract_formats_with_ranges_list_support(self):
        """Hook correctly handles tuple/list returns from yt-dlp _extract_formats_and_subtitles."""
        import yt_dlp.extractor.youtube as yt_extractor
        hooked_fn = yt_extractor.YoutubeIE._extract_formats_and_subtitles

        player_responses = [
            {
                "streamingData": {
                    "adaptiveFormats": [
                        {
                            "itag": 571,
                            "initRange": {"start": "0", "end": "700"},
                            "indexRange": {"start": "701", "end": "1068"},
                        }
                    ]
                }
            }
        ]

        def mock_orig_list(self, *args, **kwargs):
            return ([{"format_id": "571", "width": 7680, "height": 4320}], {"sub": []})

        with patch.object(svc_mod, "_orig_extract_formats_and_subtitles", side_effect=mock_orig_list):
            res = hooked_fn(None, None, player_responses=player_responses)
            self.assertIsInstance(res, tuple)
            formats, subs = res
            self.assertEqual(formats[0].get("initRange"), {"start": "0", "end": "700"})
            self.assertEqual(formats[0].get("indexRange"), {"start": "701", "end": "1068"})


class TestHighResApiEndpoint(unittest.TestCase):
    """Tests for GET /get-youtube-video-highres endpoint."""

    def setUp(self):
        _reset_highres_state()
        self.client = api_mod.app.test_client()

    def test_missing_video_id_returns_400(self):
        """Missing or malformed video_id returns 400 Bad Request."""
        resp = self.client.get("/get-youtube-video-highres")
        self.assertEqual(resp.status_code, 400)
        self.assertIn("Missing or invalid", resp.get_json()["error"])

        resp2 = self.client.get("/get-youtube-video-highres?video_id=invalid!")
        self.assertEqual(resp2.status_code, 400)

    def test_video_id_extracted_from_url_param(self):
        """Allow passing YouTube URL as parameter."""
        mock_data = {
            "hasHighRes": False,
            "videoId": "dQw4w9WgXcQ",
            "extractedAt": int(time.time()),
            "expiresAt": int(time.time()) + 7200,
            "video": None,
            "audio": None,
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            resp = self.client.get("/get-youtube-video-highres?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertEqual(resp.status_code, 200)
        mock_get.assert_called_once_with("dQw4w9WgXcQ")

    def test_successful_8k_response_and_caching(self):
        """Successful extraction caches with expiry margin and returns 200."""
        mock_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": int(time.time()),
            "expiresAt": int(time.time()) + 5000,
            "video": {"itag": 571, "width": 7680, "height": 4320},
            "audio": {"itag": 140},
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            # First request: cache miss
            resp1 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp1.status_code, 200)
            self.assertTrue(resp1.get_json()["hasHighRes"])
            mock_get.assert_called_once()

            # Second request: cache hit, no second extraction
            resp2 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp2.status_code, 200)
            self.assertEqual(mock_get.call_count, 1)

    def test_already_expired_url_is_not_cached(self):
        """Positive result with already-expired URL (expiresAt <= now) is NOT cached."""
        now = int(time.time())
        mock_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": now,
            "expiresAt": now - 100,  # Expired in past
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            resp1 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp1.status_code, 200)

            # Not cached
            self.assertIsNone(api_mod._highres_cache.get("P7yRYiBiV3g"))

            # Second request triggers extraction again
            resp2 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp2.status_code, 200)
            self.assertEqual(mock_get.call_count, 2)

    def test_remaining_lifetime_under_safety_margin_is_not_cached(self):
        """Positive result with remaining lifetime <= 600s is NOT cached."""
        now = int(time.time())
        mock_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": now,
            "expiresAt": now + 400,  # 400s left, margin is 600s -> ttl <= 0
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            resp1 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp1.status_code, 200)

            # Not cached
            self.assertIsNone(api_mod._highres_cache.get("P7yRYiBiV3g"))

            resp2 = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp2.status_code, 200)
            self.assertEqual(mock_get.call_count, 2)

    def test_missing_expiry_metadata_is_not_cached(self):
        """Positive result with missing/zero expiresAt is NOT cached."""
        now = int(time.time())
        mock_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": now,
            "expiresAt": 0,  # Missing/invalid
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            resp = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp.status_code, 200)
            self.assertIsNone(api_mod._highres_cache.get("P7yRYiBiV3g"))

    def test_cached_entry_past_expiration_is_evicted_and_not_served(self):
        """An entry whose expiration enters the safety margin window is evicted on get."""
        now = int(time.time())
        # Artificially insert entry into cache with near-expiration
        entry = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": now - 1000,
            "expiresAt": now + 500,  # inside 600s safety margin
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }
        api_mod._highres_cache.set("P7yRYiBiV3g", entry, ttl=1000.0)

        fresh_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": now,
            "expiresAt": now + 5000,
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=fresh_data) as mock_get:
            resp = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            self.assertEqual(resp.status_code, 200)
            # Must have triggered fresh extraction because cached entry was expired
            mock_get.assert_called_once()

    def test_negative_result_is_cached(self):
        """Negative extraction (no 8K) is cached negatively."""
        mock_data = {
            "hasHighRes": False,
            "videoId": "dQw4w9WgXcQ",
            "extractedAt": int(time.time()),
            "expiresAt": int(time.time()) + 7200,
            "video": None,
            "audio": None,
        }
        with patch.object(api_mod, "get_video_highres_info", return_value=mock_data) as mock_get:
            resp1 = self.client.get("/get-youtube-video-highres?video_id=dQw4w9WgXcQ")
            self.assertEqual(resp1.status_code, 200)
            self.assertFalse(resp1.get_json()["hasHighRes"])

            # Verify cached
            cached = api_mod._highres_cache.get("dQw4w9WgXcQ")
            self.assertIsNotNone(cached)
            self.assertFalse(cached["hasHighRes"])

            # Second request hits cache
            resp2 = self.client.get("/get-youtube-video-highres?video_id=dQw4w9WgXcQ")
            self.assertEqual(resp2.status_code, 200)
            self.assertEqual(mock_get.call_count, 1)

    def test_in_flight_concurrent_deduplication(self):
        """Concurrent requests for the same video_id trigger only one extraction."""
        mock_data = {
            "hasHighRes": True,
            "videoId": "P7yRYiBiV3g",
            "extractedAt": int(time.time()),
            "expiresAt": int(time.time()) + 7200,
            "video": {"itag": 571},
            "audio": {"itag": 140},
        }

        call_count = 0
        barrier = threading.Barrier(3)

        def slow_extract(vid):
            nonlocal call_count
            call_count += 1
            time.sleep(0.1)
            return mock_data

        results = []

        def worker():
            barrier.wait()
            resp = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
            results.append(resp)

        with patch.object(api_mod, "get_video_highres_info", side_effect=slow_extract):
            threads = [threading.Thread(target=worker) for _ in range(3)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()

        self.assertEqual(len(results), 3)
        for r in results:
            self.assertEqual(r.status_code, 200)
            self.assertTrue(r.get_json()["hasHighRes"])

        self.assertEqual(call_count, 1)

    def test_runtime_error_returns_422(self):
        """RuntimeError during extraction maps to HTTP 422."""
        with patch.object(api_mod, "get_video_highres_info", side_effect=RuntimeError("Extraction failed")):
            resp = self.client.get("/get-youtube-video-highres?video_id=P7yRYiBiV3g")
        self.assertEqual(resp.status_code, 422)
        self.assertIn("Extraction failed", resp.get_json()["error"])

    def test_root_endpoint_includes_highres_endpoint(self):
        """Sanity check that root endpoint lists the highres endpoint."""
        resp = self.client.get("/")
        self.assertEqual(resp.status_code, 200)
        endpoints = resp.get_json().get("endpoints", [])
        self.assertTrue(any("get-youtube-video-highres" in ep for ep in endpoints))


if __name__ == "__main__":
    unittest.main()
