"""Pure helpers for safe YouTube Music artist-image resolution."""

import re
import unicodedata


_BROWSE_ID_RE = re.compile(r"^UC[A-Za-z0-9_-]{22}$")


def normalize_artist_name(value):
    """Return the exact-match key for a display name, or ``None``."""
    if not isinstance(value, str):
        return None
    normalized = unicodedata.normalize("NFC", value)
    normalized = " ".join(normalized.split()).casefold()
    return normalized or None


def is_valid_browse_id(value):
    """YTMusic artist channel IDs are opaque, but have a strict UC shape."""
    return isinstance(value, str) and bool(_BROWSE_ID_RE.fullmatch(value))


def thumbnail_url(value):
    """Extract a usable URL from a ytmusicapi thumbnail container."""
    if not isinstance(value, dict):
        return None
    thumbnails = value.get("thumbnails")
    if not isinstance(thumbnails, list):
        return None
    for thumbnail in reversed(thumbnails):
        if not isinstance(thumbnail, dict):
            continue
        url = thumbnail.get("url")
        if isinstance(url, str) and url:
            return url
    return None


def exact_artist_image(results, requested_name):
    """Resolve only an unambiguous exact-name result.

    Search can return both a real artist and a tribute/channel result with the
    same display name.  A result without a valid opaque browse ID is not safe
    to associate with a portrait, and distinct IDs for one exact name are
    deliberately rejected rather than selecting the first result.
    """
    target = normalize_artist_name(requested_name)
    if target is None or not isinstance(results, list):
        return None

    matches = []
    for result in results:
        if not isinstance(result, dict):
            continue
        result_name = result.get("artist", result.get("name"))
        if normalize_artist_name(result_name) != target:
            continue
        browse_id = result.get("browseId")
        if not is_valid_browse_id(browse_id):
            continue
        matches.append((browse_id, thumbnail_url(result)))

    browse_ids = {browse_id for browse_id, _ in matches}
    if len(browse_ids) != 1:
        return None
    for _, image_url in matches:
        if image_url:
            return image_url
    return None
