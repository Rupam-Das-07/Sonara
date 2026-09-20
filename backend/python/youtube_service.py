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
from gevent.event import AsyncResult

_in_flight_extractions = {}

def _extract_with_retry(ydl_opts, url):
    """Executes yt_dlp extract_info with a 15-second timeout and up to 2 retries on DownloadError."""
    max_retries = 2
    backoff = 1.0

    for attempt in range(max_retries + 1):
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                try:
                    with gevent.Timeout(15):
                        return ydl.extract_info(url, download=False)
                except gevent.Timeout:
                    raise RuntimeError("yt-dlp extraction timed out after 15 seconds")
        except yt_dlp.utils.DownloadError as exc:
            if attempt < max_retries:
                gevent.sleep(backoff)
                backoff *= 2
                continue
            raise RuntimeError(f"Video is unavailable: {exc}") from exc
        except RuntimeError:
            raise
        except Exception as exc:
            raise RuntimeError(f"Failed to extract info: {exc}") from exc



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
            }
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
        thumbnail = (
            f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg"
            if video_id
            else info.get("thumbnail", "")
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
            thumbnail = (
                f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg"
                if video_id
                else entry.get("thumbnail", "")
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
