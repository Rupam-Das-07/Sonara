"""
youtube_service.py
------------------
Pure helper module — no Flask, no HTTP.
Encapsulates all yt-dlp logic for extracting YouTube audio info.

Responsibilities:
  - Validate the URL is a proper YouTube link
  - Fetch video metadata (title, thumbnail)
  - Locate the best audio-only stream URL (no download)
"""

import yt_dlp
import gevent
import json
import re
import time
from urllib.parse import urlparse, parse_qs
from gevent.event import AsyncResult
from gevent.lock import Semaphore

# ─────────────────────────────────────────────────────────────────────────────
# Preserve raw byte ranges (initRange and indexRange) for DASH SegmentBase
# ─────────────────────────────────────────────────────────────────────────────
_orig_extract_formats_and_subtitles = None
try:
    import yt_dlp.extractor.youtube as yt_extractor
    _orig_extract_formats_and_subtitles = getattr(yt_extractor.YoutubeIE, "_extract_formats_and_subtitles", None)
    if _orig_extract_formats_and_subtitles:
        def _extract_formats_with_ranges(self, *args, **kwargs):
            res = _orig_extract_formats_and_subtitles(self, *args, **kwargs)
            try:
                # player_responses is typically 2nd positional parameter or kwarg
                player_responses = kwargs.get("player_responses")
                if player_responses is None and len(args) >= 2:
                    player_responses = args[1]
                if player_responses and isinstance(player_responses, (list, tuple)):
                    range_map = {}
                    for pr in player_responses:
                        if not isinstance(pr, dict):
                            continue
                        sd = pr.get("streamingData")
                        if not isinstance(sd, dict):
                            continue
                        for fmt in sd.get("adaptiveFormats", []):
                            if not isinstance(fmt, dict):
                                continue
                            itag = str(fmt.get("itag") or "")
                            if itag:
                                range_map[itag] = {
                                    "initRange": fmt.get("initRange"),
                                    "indexRange": fmt.get("indexRange"),
                                }
                    if range_map:
                        def _attach_ranges(item):
                            if isinstance(item, dict):
                                fid = str(item.get("format_id", "")).split("-")[0].strip()
                                if fid in range_map:
                                    item["initRange"] = range_map[fid].get("initRange")
                                    item["indexRange"] = range_map[fid].get("indexRange")

                        if isinstance(res, (tuple, list)):
                            # Check if res is (formats_list, subtitles_dict) or [fmt1, fmt2, ...]
                            if len(res) == 2 and isinstance(res[0], (list, tuple)) and isinstance(res[1], dict):
                                for f in res[0]:
                                    _attach_ranges(f)
                            else:
                                for f in res:
                                    _attach_ranges(f)
                            return res
                        elif hasattr(res, "__iter__"):
                            def _wrap_gen():
                                for item in res:
                                    _attach_ranges(item)
                                    yield item
                            return _wrap_gen()
            except Exception:
                # Range hook failures must never disrupt extraction
                pass
            return res

        yt_extractor.YoutubeIE._extract_formats_and_subtitles = _extract_formats_with_ranges
except Exception:
    pass


def _restore_extract_formats_hook():
    """Restore original yt-dlp extractor method if hooked."""
    global _orig_extract_formats_and_subtitles
    try:
        import yt_dlp.extractor.youtube as yt_extractor
        if _orig_extract_formats_and_subtitles:
            yt_extractor.YoutubeIE._extract_formats_and_subtitles = _orig_extract_formats_and_subtitles
    except Exception:
        pass

_in_flight_extractions = {}

# ─────────────────────────────────────────────────────────────────────────────
# Bounded, reusable YoutubeDL instance pool (Stage C-1A, Part A)
#
# yt-dlp is used as an in-process library. Previously a brand-new
# ``yt_dlp.YoutubeDL`` was constructed for EVERY extraction, re-paying per-instance
# setup each time — most importantly yt-dlp's lazily-built request director and
# its HTTP handlers / connection pool. yt-dlp owns its networking stack, so a
# hand-rolled ``requests.Session`` would NOT be reused by it; the correct unit of
# reuse is the YoutubeDL instance itself. Reusing an open instance across
# sequential extractions amortizes that setup and permits upstream connection reuse.
#
# A single shared instance is NOT safe here: the service runs under gevent and a
# YoutubeDL carries mutable per-extraction state, so two greenlets must never drive
# the same instance concurrently. We therefore keep a SMALL, bounded pool with
# checkout/checkin semantics (exactly one greenlet owns an instance at a time),
# keyed by a stable signature of ``ydl_opts`` (the module uses two distinct opts
# sets: audio extraction and flat search). When every pooled instance is busy and
# the cap is reached, we fall back to a transient instance that is closed after use
# — i.e. exactly the previous behaviour — so callers never block. A failed
# extraction DISCARDS its instance (it is closed and never returned to the pool),
# so an instance left in an edge state can never poison a later extraction.
#
# Memory is bounded by the hard cap below. Only yt-dlp process state is retained;
# no audio bytes and nothing on disk. All existing yt-dlp options, extraction
# semantics, retry behaviour, stream-URL parsing and expiry handling are preserved.
_YDL_POOL_MAX_INSTANCES = 4           # hard cap on retained reusable instances
_ydl_pool = {}                         # opts-signature -> list[idle YoutubeDL]
_ydl_live_count = 0                    # pooled instances alive (idle + checked-out)
_ydl_pool_lock = Semaphore()           # guards _ydl_pool and _ydl_live_count


def _opts_signature(ydl_opts):
    """Stable key for an options dict so identical configurations share instances."""
    try:
        return json.dumps(ydl_opts, sort_keys=True, default=str)
    except Exception:
        return repr(sorted(ydl_opts.items()))


def _acquire_ydl(ydl_opts):
    """Check out a reusable YoutubeDL for *ydl_opts*, or construct one.

    Returns ``(ydl, pooled)``. A ``pooled=False`` instance is transient (the cap
    was reached) and MUST be closed by the caller rather than returned to the pool.
    Construction happens outside the lock; a failed construction restores the count.
    """
    global _ydl_live_count
    sig = _opts_signature(ydl_opts)
    with _ydl_pool_lock:
        idle = _ydl_pool.get(sig)
        if idle:
            return idle.pop(), True
        pooled = _ydl_live_count < _YDL_POOL_MAX_INSTANCES
        if pooled:
            _ydl_live_count += 1
    try:
        ydl = yt_dlp.YoutubeDL(ydl_opts)
    except Exception:
        if pooled:
            with _ydl_pool_lock:
                _ydl_live_count -= 1
        raise
    ydl._sonara_sig = sig
    return ydl, pooled


def _release_ydl(ydl, pooled):
    """Return a healthy instance to the pool for reuse (or close a transient one)."""
    if not pooled:
        _close_ydl_quietly(ydl)
        return
    sig = getattr(ydl, "_sonara_sig", None)
    with _ydl_pool_lock:
        _ydl_pool.setdefault(sig, []).append(ydl)


def _discard_ydl(ydl, pooled):
    """Drop an instance after an error so it is never reused; keeps the pool clean."""
    global _ydl_live_count
    if pooled:
        with _ydl_pool_lock:
            _ydl_live_count -= 1
    _close_ydl_quietly(ydl)


def _close_ydl_quietly(ydl):
    """Best-effort close; never raises."""
    try:
        ydl.close()
    except Exception:
        pass


def _extract_with_retry(ydl_opts, url):
    """Executes yt_dlp extract_info with a 15-second timeout and up to 2 retries on DownloadError.

    Uses the bounded, reusable YoutubeDL pool above. The observable behaviour —
    the 15s timeout, retry/backoff on DownloadError, timeout/error wrapping into
    RuntimeError — is IDENTICAL to the previous per-call implementation; only the
    YoutubeDL instance lifecycle changed (checkout/checkin instead of construct/close
    per attempt). Any failing attempt discards its instance so it is never reused.
    """
    max_retries = 2
    backoff = 1.0

    for attempt in range(max_retries + 1):
        ydl, pooled = _acquire_ydl(ydl_opts)
        try:
            try:
                with gevent.Timeout(15):
                    info = ydl.extract_info(url, download=False)
            except gevent.Timeout:
                raise RuntimeError("yt-dlp extraction timed out after 15 seconds")
        except yt_dlp.utils.DownloadError as exc:
            _discard_ydl(ydl, pooled)
            if attempt < max_retries:
                gevent.sleep(backoff)
                backoff *= 2
                continue
            raise RuntimeError(f"Video is unavailable: {exc}") from exc
        except RuntimeError:
            _discard_ydl(ydl, pooled)
            raise
        except Exception as exc:
            _discard_ydl(ydl, pooled)
            raise RuntimeError(f"Failed to extract info: {exc}") from exc
        else:
            _release_ydl(ydl, pooled)
            return info



def get_audio_info(url: str) -> dict:
    """
    Given a YouTube video URL, return a dict with:
      - title     : video title
      - thumbnail : highest-resolution thumbnail URL
      - audio_url : direct URL to the best audio-only stream (no download)

    Raises:
      ValueError  – if the URL is invalid or not a YouTube URL
      RuntimeError – if the video is unavailable or has no audio streams
    """

    # ── 1. Basic URL sanity check ────────────────────────────────────────────
    if not url or not isinstance(url, str):
        raise ValueError("URL must be a non-empty string.")

    url = url.strip()
    if "youtube.com" not in url and "youtu.be" not in url:
        raise ValueError("URL does not appear to be a YouTube link.")

    # ── In-Flight Deduplication ──────────────────────────────────────────────
    if url in _in_flight_extractions:
        result_event = _in_flight_extractions[url]
        try:
            # Wait up to 30 seconds for the primary extraction to finish
            return result_event.get(timeout=30)
        except gevent.Timeout:
            raise RuntimeError("Timed out waiting for shared in-flight extraction to complete")

    result_event = AsyncResult()
    _in_flight_extractions[url] = result_event

    try:
        # ── 2. Extract info using yt-dlp ─────────────────────────────────────────
        ydl_opts = {
            "format": "bestaudio/best",
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            # Do NOT require login or cookies, use Android API bypass instead
            "extract_flat": False,
            "extractor_args": {
                "youtube": {
                    "player_client": ["android"],
                    "client": ["android"]
                }
            },
            # Use Node.js (already in the production container) for YouTube
            # player JS evaluation. yt-dlp-ejs must be installed.
            "js_runtimes": {"node": {}},
        }

        try:
            info = _extract_with_retry(ydl_opts, url)
        except Exception as exc:
            raise RuntimeError(f"Failed to extract audio: {exc}") from exc

        if not info:
            raise RuntimeError("yt-dlp returned no info for this video.")

        # ── 3. Extract metadata ──────────────────────────────────────────────────
        title = info.get("title") or "Unknown Title"

        video_id = info.get("id", "")
        # Authoritative provider metadata outranks a constructed URL. yt-dlp already reports a
        # thumbnail it resolved for this exact video; `maxresdefault.jpg` is only a guess and is
        # absent for many videos, so it stays a fallback rather than the preference.
        thumbnail = info.get("thumbnail") or (
            f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg" if video_id else ""
        )

        # ── 4. Get the audio stream URL ──────────────────────────────────────────
        audio_url = info.get("url")

        if not audio_url:
            # Sometimes yt-dlp returns the URL inside requested_formats
            formats = info.get("requested_formats") or []
            for fmt in formats:
                if fmt.get("acodec") and fmt["acodec"] != "none":
                    audio_url = fmt.get("url")
                    break

        if not audio_url:
            raise RuntimeError("No audio streams found for this video.")

        # ── 5. Get the HTTP headers yt-dlp recommends for this stream ──────────
        # YouTube validates the User-Agent against the client type used.
        # e.g. ANDROID_VR streams require a specific User-Agent.
        http_headers = info.get("http_headers") or {}

        result_dict = {
            "title": title,
            "thumbnail": thumbnail,
            "audio_url": audio_url,
            "http_headers": http_headers,
        }
        
        result_event.set(result_dict)
        return result_dict

    except Exception as exc:
        result_event.set_exception(exc)
        raise
    finally:
        _in_flight_extractions.pop(url, None)


def search_youtube(query: str, max_results: int = 10) -> list:
    """
    Search YouTube using yt-dlp and return a list of normalized result dicts.
    """
    if not query or not isinstance(query, str):
        raise ValueError("Query must be a non-empty string.")

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,       # Don't download, just get metadata
        "noplaylist": True,
    }

    try:
        search_url = f"ytsearch{max_results}:{query} music"
        info = _extract_with_retry(ydl_opts, search_url)

        entries = info.get("entries") or []

        results = []
        for entry in entries[:max_results]:
            video_id = entry.get("id", "")
            # Same rule as get_audio_info: the thumbnail the provider reported wins over a
            # URL constructed from the id.
            thumbnail = entry.get("thumbnail") or (
                f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg" if video_id else ""
            )

            results.append({
                "videoId": video_id,
                "title": entry.get("title") or "Unknown Title",
                "channelTitle": entry.get("channel") or entry.get("uploader") or "Unknown Artist",
                "thumbnail": thumbnail,
                "duration": entry.get("duration") or 0,
            })

        return results
    except Exception as exc:
        raise RuntimeError(f"Search failed: {exc}") from exc


def _parse_range(r):
    """
    Parse a byte range dict ({'start': ..., 'end': ...}).
    Returns a normalized {'start': int, 'end': int} dict only if both start
    and end are valid integers, start >= 0, and end > start.
    Otherwise returns None.
    """
    if not isinstance(r, dict):
        return None
    try:
        start = int(r["start"])
        end = int(r["end"])
        if start >= 0 and end > start:
            return {"start": start, "end": end}
    except (KeyError, ValueError, TypeError):
        pass
    return None


def _validate_range_pair(init_range, index_range) -> bool:
    """
    Validates that both initRange and indexRange are valid and structurally sound
    for DASH SegmentBase indexing.
    """
    if not init_range or not index_range:
        return False
    # In ISO-BMFF DASH SegmentBase, initialization box precedes index box
    if index_range["start"] < init_range["end"]:
        return False
    return True


def _extract_url_expire(url: str) -> int:
    """
    Extract unix expiration timestamp from googlevideo URL.
    Returns 0 if missing, not numeric, or invalid. Never invents expiry.
    """
    if not url or not isinstance(url, str):
        return 0
    try:
        parsed = urlparse(url)
        qs = parse_qs(parsed.query)
        exp_vals = qs.get("expire")
        if exp_vals and exp_vals[0].isdigit():
            return int(exp_vals[0])
    except Exception:
        pass
    return 0


def get_video_highres_info(video_id: str) -> dict:
    """
    Given an 11-character YouTube video ID, extract high-resolution (8K/4320p)
    video and matching audio stream metadata using yt-dlp.

    Returns:
      {
        "hasHighRes": True/False,
        "videoId": video_id,
        "extractedAt": int,
        "expiresAt": int,
        "video": dict or None,
        "audio": dict or None,
      }
    """
    if not video_id or not isinstance(video_id, str):
        raise ValueError("video_id must be a non-empty string.")

    video_id = video_id.strip()
    if not re.fullmatch(r"[0-9A-Za-z_-]{11}", video_id):
        raise ValueError(f"Invalid video_id format: '{video_id}'. Must be exactly 11 characters [0-9A-Za-z_-].")

    url = f"https://www.youtube.com/watch?v={video_id}"
    in_flight_key = f"highres:{video_id}"

    # In-flight deduplication (gevent level)
    if in_flight_key in _in_flight_extractions:
        result_event = _in_flight_extractions[in_flight_key]
        try:
            return result_event.get(timeout=30)
        except gevent.Timeout:
            raise RuntimeError("Timed out waiting for shared in-flight highres extraction to complete")

    result_event = AsyncResult()
    _in_flight_extractions[in_flight_key] = result_event

    try:
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "extract_flat": False,
            "extractor_args": {
                "youtube": {
                    "player_client": ["visionos", "web", "android"]
                }
            },
            "js_runtimes": {"node": {}},
        }

        try:
            info = _extract_with_retry(ydl_opts, url)
        except Exception as exc:
            raise RuntimeError(f"Failed to extract high-resolution info: {exc}") from exc

        if not info:
            raise RuntimeError("yt-dlp returned no info for this video.")

        formats = info.get("formats") or []
        now = int(time.time())

        # 1. Filter genuine 8K video candidates (strictly 7680x4320 AV1 MP4 with valid byte ranges)
        candidates_8k = []
        for f in formats:
            try:
                h = int(f.get("height") or 0)
                w = int(f.get("width") or 0)
            except (ValueError, TypeError):
                continue
            if h != 4320 or w != 7680:
                continue

            v_url = f.get("url")
            if not v_url:
                continue

            vcodec = str(f.get("vcodec") or "")
            if not vcodec.startswith("av01"):
                continue

            ext = str(f.get("ext") or "").lower()
            container = str(f.get("container") or "").lower()
            if ext != "mp4" and "mp4" not in container:
                continue

            init_r = _parse_range(f.get("initRange"))
            index_r = _parse_range(f.get("indexRange"))
            if not _validate_range_pair(init_r, index_r):
                continue

            candidates_8k.append({
                "format": f,
                "initRange": init_r,
                "indexRange": index_r,
            })

        # 2. Filter matching audio candidates (ISO-BMFF audio compatible with MP4 video, with valid byte ranges)
        audio_candidates = []
        for f in formats:
            if f.get("vcodec") != "none":
                continue
            acodec = str(f.get("acodec") or "")
            if not acodec or acodec == "none":
                continue
            a_url = f.get("url")
            if not a_url:
                continue

            ext = str(f.get("ext") or "").lower()
            container = str(f.get("container") or "").lower()
            is_mp4_audio = ext in ("m4a", "mp4") or "mp4" in container or acodec.startswith("mp4a")
            if not is_mp4_audio:
                continue

            init_r = _parse_range(f.get("initRange"))
            index_r = _parse_range(f.get("indexRange"))
            if not _validate_range_pair(init_r, index_r):
                continue

            audio_candidates.append({
                "format": f,
                "initRange": init_r,
                "indexRange": index_r,
            })

        # If either genuine 8K video or compatible audio is absent, negative discovery
        if not candidates_8k or not audio_candidates:
            result_dict = {
                "hasHighRes": False,
                "videoId": video_id,
                "extractedAt": now,
                "expiresAt": now + 7200,
                "video": None,
                "audio": None,
            }
            result_event.set(result_dict)
            return result_dict

        # Sort video: prefer SDR itag 571, then HDR itag 702, then highest bitrate
        def _8k_sort_key(c):
            f = c["format"]
            fid = str(f.get("format_id", ""))
            if fid == "571":
                return (0, -(f.get("tbr") or 0))
            if fid == "702":
                return (1, -(f.get("tbr") or 0))
            return (2, -(f.get("tbr") or 0))

        candidates_8k.sort(key=_8k_sort_key)
        best_v = candidates_8k[0]
        v = best_v["format"]

        # Sort audio: prefer itag 140 (AAC 128k), then highest abr
        def _audio_sort_key(c):
            f = c["format"]
            fid = str(f.get("format_id", ""))
            if fid == "140":
                return (0, -(f.get("abr") or 0))
            return (1, -(f.get("abr") or 0))

        audio_candidates.sort(key=_audio_sort_key)
        best_a = audio_candidates[0]
        a = best_a["format"]

        # 3. Check durations
        duration = float(info.get("duration") or 0.0)
        v_dur = float(v.get("approx_duration_ms") or 0.0) / 1000.0 if v.get("approx_duration_ms") else duration
        a_dur = float(a.get("approx_duration_ms") or 0.0) / 1000.0 if a.get("approx_duration_ms") else duration

        if v_dur > 0 and a_dur > 0 and abs(v_dur - a_dur) > max(10.0, 0.1 * v_dur):
            # Significant duration mismatch indicates incompatible or damaged stream pairing
            result_dict = {
                "hasHighRes": False,
                "videoId": video_id,
                "extractedAt": now,
                "expiresAt": now + 7200,
                "video": None,
                "audio": None,
            }
            result_event.set(result_dict)
            return result_dict

        # 4. Extract expiration conservative timestamps (earliest of video and audio)
        v_exp = _extract_url_expire(v.get("url", ""))
        a_exp = _extract_url_expire(a.get("url", ""))
        if v_exp > 0 and a_exp > 0:
            expires_at = min(v_exp, a_exp)
        elif v_exp > 0:
            expires_at = v_exp
        else:
            expires_at = 0

        # Build video DTO
        v_fps = v.get("fps") or 60
        v_codec = v.get("vcodec") or "av01.0.17M.08"
        v_ext = v.get("ext") or "mp4"
        v_bitrate = int((v.get("vbr") or v.get("tbr") or 0) * 1000)
        clean_v_mime = "video/mp4" if v_ext in ("mp4", "m4v") else f"video/{v_ext}"
        video_dto = {
            "itag": int(v.get("format_id", 571)),
            "width": int(v.get("width", 7680)),
            "height": int(v.get("height", 4320)),
            "fps": int(v_fps),
            "codec": v_codec,
            "mimeType": clean_v_mime,
            "bitrate": v_bitrate,
            "duration": duration,
            "initRange": best_v["initRange"],
            "indexRange": best_v["indexRange"],
            "streamUrl": v.get("url", ""),
            "httpHeaders": v.get("http_headers") or info.get("http_headers") or {},
        }

        # Build audio DTO
        a_codec = a.get("acodec") or "mp4a.40.2"
        a_ext = a.get("ext") or "m4a"
        a_bitrate = int((a.get("abr") or a.get("tbr") or 0) * 1000)
        clean_a_mime = "audio/mp4" if a_ext in ("m4a", "mp4") or "mp4a" in a_codec else f"audio/{a_ext}"
        audio_dto = {
            "itag": int(a.get("format_id", 140)),
            "codec": a_codec,
            "mimeType": clean_a_mime,
            "bitrate": a_bitrate,
            "duration": duration,
            "initRange": best_a["initRange"],
            "indexRange": best_a["indexRange"],
            "streamUrl": a.get("url", ""),
            "httpHeaders": a.get("http_headers") or info.get("http_headers") or {},
        }

        result_dict = {
            "hasHighRes": True,
            "videoId": video_id,
            "extractedAt": now,
            "expiresAt": expires_at,
            "video": video_dto,
            "audio": audio_dto,
        }

        result_event.set(result_dict)
        return result_dict

    except Exception as exc:
        result_event.set_exception(exc)
        raise
    finally:
        _in_flight_extractions.pop(in_flight_key, None)
