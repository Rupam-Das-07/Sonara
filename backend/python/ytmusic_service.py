#!/usr/bin/env python3
"""
YouTube Music API Service using ytmusicapi
Provides enhanced YouTube Music functionality for the music streaming app
"""

# STRICT MONKEY-PATCHING
# MUST BE EXECUTED BEFORE ANY OTHER IMPORTS
import gevent.monkey
gevent.monkey.patch_all()

from flask import Flask, request, jsonify
from flask_cors import CORS
import ytmusicapi
import ytmusicapi.parsers.watch as watch_parser
import ytmusicapi.mixins.watch as watch_mixin
import os
import json
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# --- Compatibility Patch for ytmusicapi ---
# Monkey patch removed. It is incompatible with the current ytmusicapi version
# which no longer expects the `tab_id` parameter. `ytmusicapi` natively handles
# this logic safely now.
# ------------------------------------------

app = Flask(__name__)
CORS(app)

# Initialize YTMusic (unauthenticated for basic features)
import time
_init_start = time.time()
print(f"[DIAGNOSTIC] {_init_start} - Global YTMusic() init Started")
yt = ytmusicapi.YTMusic()
print(f"[DIAGNOSTIC] {time.time()} - Global YTMusic() init Completed in {time.time() - _init_start:.2f}s")

CATEGORY_QUERIES = {
    "Evergreen Hits": "evergreen hits songs",
    "90s Classics": "90s Bollywood hits",
    "Romantic Vibes": "romantic songs",
    "Party Anthems": "party songs",
    "Chill & Relax": "chill songs",
    "Workout Energy": "workout songs",
    "Top Trending": "trending songs",
    "Soulful Voices": "soulful songs",
    "Indie Discoveries": "indie songs",
    "Monsoon Melodies": "monsoon songs",
    "Road Trip": "road trip songs",
    "Feel Good": "feel good songs",
    "Heartbreak Songs": "heartbreak songs",
    "Festival Specials": "festival songs",
    "New Releases": "new release songs",
    "Retro Gold": "retro songs",
    "Unplugged & Acoustic": "unplugged acoustic songs",
    "Folk & Regional": "folk songs",
    "International Chartbusters": "international hits songs"
}


@app.route('/', methods=['GET'])
def root_index():
    """Root index endpoint explaining available services and status."""
    return jsonify({
        'service': 'ytmusic-api',
        'status': 'healthy',
        'port': 5000,
        'endpoints': [
            '/health',
            '/api/search?q=<query>',
            '/api/artist-image?name=<name>',
            '/api/artist-catalog-deep?artistName=<name>&browseId=<id>',
            '/api/song/<video_id>',
            '/api/artist/<browse_id>',
            '/api/album/<browse_id>',
            '/api/lyrics/<video_id>',
            '/api/related/<video_id>',
            '/api/watch-playlist/<video_id>',
            '/api/popular-artists',
            '/api/artist-top-songs?browseId=&artistName=',
            '/api/top-songs-by-language?language=',
            '/api/top-songs-by-category?category='
        ]
    })


@app.route('/favicon.ico', methods=['GET'])
def favicon():
    """Favicon endpoint returning 204 No Content to prevent browser 404 logs."""
    return '', 204


@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({'status': 'healthy', 'service': 'ytmusic-api'})


@app.route('/search', methods=['GET'])
@app.route('/api/v1/search', methods=['GET'])
@app.route('/api/search', methods=['GET'])
def search_music():
    """Search for music using YouTube Music"""
    try:
        import gevent
        with gevent.Timeout(8):
            query = request.args.get('q')
            if not query:
                return jsonify({'error': 'Search query is required'}), 400

            # Search for songs
            results = yt.search(query, filter='songs', limit=20)

            formatted_results = []
            for result in results:
                if 'videoId' in result:
                    mapped = normalize_yt_track(result, is_search=True)
                    if mapped:
                        formatted_results.append(mapped)

            return jsonify(formatted_results)

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/search",
            "operation": "search",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Search error: {str(e)}")
        return jsonify({'error': 'Failed to search songs'}), 500


from functools import lru_cache

# Return None on failure so the Node layer can trigger its own fallback chain (JioSaavn, etc.)
# Previously this was a placeholder URL that poisoned the Node-side cache.
ARTIST_IMAGE_PLACEHOLDER = None

@lru_cache(maxsize=500)
def _fetch_cached_artist_image(original_name):
    try:
        import gevent
        image_url = None
        with gevent.Timeout(4, False):
            results = yt.search(original_name, filter='artists', limit=3)
            if results:
                for r in results:
                    thumbnails = r.get('thumbnails', [])
                    if thumbnails:
                        url = thumbnails[-1].get('url')
                        if url:
                            image_url = url
                            break
        if image_url is None:
            # We either timed out or found nothing
            logger.debug(f"Artist image fetch finished without url for '{original_name}'")
        return image_url

    except Exception as e:
        logger.error(f"Artist image fetch error for '{original_name}': {e}")
        return None


@lru_cache(maxsize=500)
def _fetch_cached_artist_image_by_id(artist_id):
    try:
        import gevent
        image_url = None
        with gevent.Timeout(4, False):
            result = yt.get_artist(artist_id)
            if result:
                thumbnails = result.get('thumbnails', [])
                if thumbnails:
                    url = thumbnails[-1].get('url')
                    if url:
                        image_url = url
        return image_url

    except Exception as e:
        logger.error(f"Artist image fetch error for ID '{artist_id}': {e}")
        return None


@app.route('/api/artist-image', methods=['GET'])
def get_artist_image():
    """Fetch an artist's thumbnail image using YTMusic artist search or exact ID lookup.
    
    Query params: id (optional), name (optional) — at least one is required.
    Returns: { name, image }
    """
    artist_id = request.args.get('id', '').strip()
    name = request.args.get('name', '').strip()
    
    if not name and not artist_id:
        return jsonify({'error': 'name or id is required'}), 400

    if artist_id:
        image_url = _fetch_cached_artist_image_by_id(artist_id)
    else:
        # Fallback to string search if no ID
        image_url = _fetch_cached_artist_image(name.lower())
        
    return jsonify({'name': name or artist_id, 'image': image_url})


@app.route('/api/charts/artists', methods=['GET'])
def get_chart_artists():
    """Fetch top artists from YouTube Music Global Chart.
    
    Query params: country (optional, default: None for Global)
    Returns: { artists: [ { title, browseId, subscribers, rank, thumbnails } ] }
    """
    try:
        import gevent
        country = request.args.get('country', '').strip() or None
        with gevent.Timeout(8):
            charts = yt.get_charts(country=country) if country else yt.get_charts()
            raw_artists = charts.get('artists')
            if isinstance(raw_artists, dict):
                artists_data = raw_artists.get('items', [])
            elif isinstance(raw_artists, list):
                artists_data = raw_artists
            else:
                artists_data = []
            return jsonify({'artists': artists_data})
    except Exception as e:
        logger.error(f"Error fetching chart artists: {e}")
        return jsonify({'error': str(e), 'artists': []}), 500


@app.route('/api/song/<video_id>', methods=['GET'])
def get_song_details(video_id):
    """Get detailed song information"""
    try:
        import gevent
        with gevent.Timeout(8):
            # Get song details
            song = yt.get_song(video_id)

            if not song:
                return jsonify({'error': 'Song not found'}), 404

            formatted_song = normalize_yt_track(song, is_search=True, description=song.get('description', ''), views=song.get('views', 0), likes=song.get('likes', 0))

            return jsonify(formatted_song)

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/song/<video_id>",
            "operation": "get_song",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Song details error: {str(e)}")
        return jsonify({'error': 'Failed to fetch song details'}), 500


@app.route('/api/artist/<browse_id>', methods=['GET'])
def get_artist_info(browse_id):
    """Get artist information and releases"""
    try:
        import gevent
        with gevent.Timeout(8):
            artist = yt.get_artist(browse_id)

            if not artist:
                return jsonify({'error': 'Artist not found'}), 404

            formatted_artist = {
                'id': browse_id,
                'name': artist.get('name', ''),
                'description': artist.get('description', ''),
                'thumbnailUrl': artist.get('thumbnails', [{}])[-1].get('url', '') if artist.get('thumbnails') else '',
                'subscriberCount': artist.get('subscriberCount', 0),
                'viewCount': artist.get('viewCount', 0),
                'songs': artist.get('songs', []),
                'albums': artist.get('albums', []),
                'singles': artist.get('singles', []),
                'videos': artist.get('videos', [])
            }

            return jsonify(formatted_artist)

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/artist/<browse_id>",
            "operation": "get_artist",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Artist info error: {str(e)}")
        return jsonify({'error': 'Failed to fetch artist information'}), 500


@app.route('/api/album/<browse_id>', methods=['GET'])
def get_album_info(browse_id):
    """Get album information and tracks"""
    try:
        import gevent
        with gevent.Timeout(8):
            album = yt.get_album(browse_id)

            if not album:
                return jsonify({'error': 'Album not found'}), 404

            formatted_album = {
                'id': browse_id,
                'title': album.get('title', ''),
                'artist': album.get('artist', ''),
                'year': album.get('year', ''),
                'thumbnailUrl': album.get('thumbnails', [{}])[-1].get('url', '') if album.get('thumbnails') else '',
                'description': album.get('description', ''),
                'tracks': album.get('tracks', [])
            }

            return jsonify(formatted_album)

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/album/<browse_id>",
            "operation": "get_album",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Album info error: {str(e)}")
        return jsonify({'error': 'Failed to fetch album information'}), 500


@app.route('/api/lyrics/<video_id>', methods=['GET'])
def get_lyrics(video_id):
    """Get song lyrics (resolves browseId via watch playlist when needed)."""
    try:
        import gevent
        with gevent.Timeout(8):
            lyrics_browse_id = None
            if video_id.startswith('MPLY') or video_id.startswith('FEmusic_'):
                lyrics_browse_id = video_id
            else:
                try:
                    watch_playlist = yt.get_watch_playlist(videoId=video_id)
                    lyrics_browse_id = watch_playlist.get('lyrics')
                except Exception as e:
                    logger.warning(f"Watch playlist lookup for lyrics failed: {e}")

            if not lyrics_browse_id:
                return jsonify({'error': 'Lyrics not available for this track'}), 404

            lyrics = yt.get_lyrics(lyrics_browse_id)

            if not lyrics:
                return jsonify({'error': 'Lyrics not found'}), 404

            return jsonify({
                'videoId': video_id,
                'lyrics': lyrics.get('lyrics', ''),
                'source': lyrics.get('source', ''),
                'hasTimestamps': lyrics.get('hasTimestamps', False)
            })

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/lyrics/<video_id>",
            "operation": "get_lyrics",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Lyrics error: {str(e)}")
        return jsonify({'error': 'Failed to fetch lyrics'}), 500


@app.route('/api/related/<video_id>', methods=['GET'])
def get_related_songs(video_id):
    """Get related songs"""
    try:
        import gevent
        with gevent.Timeout(8):
            # First fetch the watch playlist to get the correct related browseId
            watch_playlist = yt.get_watch_playlist(videoId=video_id)
            related_browse_id = watch_playlist.get('related')
            
            if not related_browse_id:
                logger.info(f"[RelatedSongs] No related browseId available for video {video_id}")
                return jsonify({'error': 'No related songs found for this video'}), 404

            related = yt.get_song_related(related_browse_id)

            formatted_results = []
            for item in related:
                if 'contents' in item:
                    for song in item['contents']:
                        if 'videoId' in song:
                            mapped = normalize_yt_track(song, is_search=True)
                            if mapped:
                                formatted_results.append(mapped)
                elif 'videoId' in item:
                    mapped = normalize_yt_track(item, is_search=True)
                    if mapped:
                        formatted_results.append(mapped)

            return jsonify(formatted_results)

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/related/<video_id>",
            "operation": "get_related_songs",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Related songs error: {str(e)}")
        return jsonify({'error': f'Failed to fetch related songs: {str(e)}'}), 500


@app.route('/api/watch-playlist/<video_id>', methods=['GET'])
def get_watch_playlist(video_id):
    """Get watch playlist (radio continuation) for a given video ID"""
    try:
        import gevent
        with gevent.Timeout(8):
            # Note: get_watch_playlist also accepts a 'playlistId', but for radio we just pass videoId
            # and it automatically generates an endless radio station based on that seed.
            playlist = yt.get_watch_playlist(videoId=video_id)
            
            # get_watch_playlist returns a dict containing 'tracks' and 'playlistId'
            tracks = playlist.get('tracks', [])
            
            formatted_results = []
            for song in tracks:
                if 'videoId' in song:
                    mapped = normalize_yt_track(song, is_search=True)
                    if mapped:
                        formatted_results.append(mapped)

            return jsonify({
                'playlistId': playlist.get('playlistId'),
                'tracks': formatted_results
            })

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/watch-playlist/<video_id>",
            "operation": "get_watch_playlist",
            "threshold": 8,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Watch playlist error: {str(e)}")
        return jsonify({'error': f'Failed to fetch watch playlist: {str(e)}'}), 500



@app.route('/api/popular-artists', methods=['GET'])
def get_popular_artists():
    """Return a static list of popular artists from a JSON file or fallback list."""
    try:
        json_path = os.path.join(os.path.dirname(
            __file__), 'popular_artists.json')
        if os.path.exists(json_path):
            with open(json_path, 'r', encoding='utf-8') as f:
                artists = json.load(f)
            return jsonify(artists)
        return jsonify(POPULAR_ARTISTS)
    except Exception as e:
        logger.error(f"Popular artists error: {str(e)}")
        return jsonify(POPULAR_ARTISTS)


@app.route('/api/artist-top-songs', methods=['GET'])
def get_artist_top_songs():
    """Return top songs for a given artist browseId or name, with fallback to search if no official channel. Also return an artist image."""
    try:
        browse_id = request.args.get('browseId')
        artist_name = request.args.get('artistName')
        top_songs = []
        artist_image = None
        # Try browseId first if provided
        if browse_id:
            try:
                artist_info = yt.get_artist(browse_id)
                # Get artist image from thumbnails if available
                thumbnails = artist_info.get('thumbnails', [])
                if thumbnails:
                    artist_image = thumbnails[-1].get('url', None)
                for song in artist_info.get('songs', {}).get('results', [])[:10]:
                    mapped = normalize_yt_track(song, is_search=False)
                    if mapped:
                        top_songs.append(mapped)
            except Exception as e:
                # Log and fallback to search
                logger.warning(
                    f"BrowseId failed, falling back to search: {str(e)}")
        # If no songs found, fallback to search by artist name
        if not top_songs and artist_name:
            try:
                search_results = yt.search(
                    artist_name, filter="songs", limit=10)
                for idx, result in enumerate(search_results):
                    if 'videoId' in result:
                        if artist_image is None and idx == 0:
                            # Use the first song's album art as artist image
                            artist_image = result.get(
                                'thumbnails', [{}])[-1].get('url', None)
                        mapped = normalize_yt_track(result, is_search=True)
                        if mapped:
                            top_songs.append(mapped)
            except Exception as e:
                logger.error(f"Artist search fallback error: {str(e)}")
        if not artist_image:
            # Default fallback image
            artist_image = "https://upload.wikimedia.org/wikipedia/commons/9/99/Sample_User_Icon.png"
        if not top_songs:
            return jsonify({'error': 'No songs found for this artist', 'artistImage': artist_image}), 404
        return jsonify({'artistImage': artist_image, 'songs': top_songs})
    except Exception as e:
        logger.error(f"Artist top songs error: {str(e)}")
        return jsonify({'error': 'Failed to fetch artist top songs'}), 500


@app.route('/api/top-songs-by-language', methods=['GET'])
def get_top_songs_by_language():
    """Return top songs for a given language using ytmusicapi search."""
    try:
        language = request.args.get('language')
        if not language:
            return jsonify({'error': 'language is required'}), 400
        query = f"{language} songs"
        results = yt.search(query, filter='songs', limit=12)
        songs = []
        for result in results:
            if 'videoId' in result:
                mapped = normalize_yt_track(result, is_search=True)
                if mapped:
                    songs.append(mapped)
        return jsonify({'songs': songs})
    except Exception as e:
        logger.error(f"Top songs by language error: {str(e)}")
        return jsonify({'error': 'Failed to fetch top songs by language'}), 500


@app.route('/api/top-songs-by-category', methods=['GET'])
def get_top_songs_by_category():
    """Return top songs for a given category from static file if available, else fallback to dynamic search."""
    try:
        category = request.args.get('category')
        if not category:
            return jsonify({'error': 'category is required'}), 400
        # Try to load from static file first
        static_path = os.path.join(os.path.dirname(
            __file__), 'category_playlists.json')
        if os.path.exists(static_path):
            with open(static_path, 'r', encoding='utf-8') as f:
                playlists = json.load(f)
            if category in playlists and playlists[category]:
                return jsonify({'songs': playlists[category]})
        # Fallback to dynamic search if not found
        query = CATEGORY_QUERIES.get(category, category)
        results = yt.search(query, filter='songs', limit=12)
        songs = []
        for result in results:
            if 'videoId' in result:
                mapped = normalize_yt_track(result, is_search=True)
                if mapped:
                    songs.append(mapped)
        return jsonify({'songs': songs})
    except Exception as e:
        logger.error(f"Top songs by category error: {str(e)}")
        return jsonify({'error': 'Failed to fetch top songs by category'}), 500





def _resolve_artist_browse_id(artist_name, yt_client):
    try:
        artist_search = yt_client.search(artist_name, filter='artists', limit=3)
        if artist_search:
            target_lower = artist_name.lower().strip()
            best = None
            for candidate in artist_search:
                cname = candidate.get('artist', candidate.get('name', '')).lower().strip()
                if cname == target_lower:
                    best = candidate
                    break
            if not best:
                best = artist_search[0]
            browse_id = best.get('browseId')
            thumbs = best.get('thumbnails', [])
            artist_image = thumbs[-1].get('url', '') if thumbs else None
            return browse_id, artist_image
    except Exception as e:
        logger.warning(f"Artist search resolution failed: {str(e)}")
    return None, None

def normalize_yt_track(track, source='youtube_music', is_search=False, **extra_kwargs):
    vid = track.get('videoId')
    if not vid:
        return None
        
    artists_list = track.get('artists', [])
    
    # ALWAYS preserve all artists, even for search results (do not slice)
    artist_str = ', '.join([a.get('name', '') for a in artists_list]) if artists_list else ''
        
    album_info = track.get('album')
    album_name = album_info.get('name', '') if album_info else ''
    thumbs = track.get('thumbnails', [])
    thumb_url = thumbs[-1].get('url', '') if thumbs else ''

    # Get duration_seconds or fallback to length (used in watch_playlist)
    duration_secs = track.get('duration_seconds', track.get('length', 0))

    base = {
        'id': vid,
        'videoId': vid,
        'title': track.get('title', ''),
        'artist': artist_str,
        'artists': [{'name': a.get('name', ''), 'id': a.get('id', '')} for a in artists_list],
        'album': album_name,
        'albumArt': thumb_url,
        'thumbnailUrl': thumb_url,
        'url': f"https://www.youtube.com/watch?v={vid}",
        'duration': track.get('duration', ''),
        'duration_seconds': duration_secs,
        'year': track.get('year', ''),
        'source': source,
        'artworkSources': {
            'ytmusic': thumb_url,
            'youtube': f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg" if vid else ''
        }
    }
    base.update(extra_kwargs)
    return base

def _fetch_official_playlist_catalog(browse_id, artist_image, yt_client):
    """Fetch official artist catalog with a hard timeout to prevent ECONNRESET.
    
    yt.get_artist() and yt.get_playlist() can hang inside gevent, killing the
    greenlet before Flask writes an HTTP response. Using gevent.Timeout
    ensures we always return valid data (even if empty) without leaking threads.
    """
    import gevent

    tracks = []
    artist_description = ''
    subscriber_count = ''
    img = artist_image
    
    try:
        with gevent.Timeout(20, False):
            artist_info = yt_client.get_artist(browse_id)
            if not img:
                thumbs = artist_info.get('thumbnails', [])
                if thumbs:
                    img = thumbs[-1].get('url', '')
            artist_description = artist_info.get('description', '')
            subscriber_count = artist_info.get('subscribers', '')

            songs_section = artist_info.get('songs', {})
            songs_playlist_id = songs_section.get('browseId')

            if songs_playlist_id:
                playlist_data = yt_client.get_playlist(songs_playlist_id, limit=100)
                for idx, track in enumerate(playlist_data.get('tracks', [])):
                    mapped = normalize_yt_track(track, 'official_playlist', idx)
                    if mapped:
                        tracks.append(mapped)
            else:
                for idx, song in enumerate(songs_section.get('results', [])):
                    mapped = normalize_yt_track(song, 'artist_page', idx)
                    if mapped:
                        tracks.append(mapped)
                        
        return tracks, img, artist_description, subscriber_count

    except Exception as e:
        logger.error(f"[CatalogError] _fetch_official_playlist_catalog failed for {browse_id}: {str(e)}")
        return [], artist_image, '', ''

def _supplement_with_deep_search(artist_name, tracks, yt_client):
    if len(tracks) >= 20:
        return tracks
        
    try:
        search_results = yt_client.search(artist_name, filter='songs', limit=40)
        existing_ids = {t['videoId'] for t in tracks}
        for idx, result in enumerate(search_results):
            vid = result.get('videoId')
            if not vid or vid in existing_ids:
                continue
            existing_ids.add(vid)
            mapped = normalize_yt_track(result, 'search_fallback', 1000 + idx, is_search=True)
            if mapped:
                tracks.append(mapped)
    except Exception as e:
        logger.warning(f"Search supplement failed: {str(e)}")
        
    return tracks

@app.route('/api/artist-catalog-deep', methods=['GET'])
def get_artist_catalog_deep():
    """
    Deep artist catalog endpoint.
    Resolves an artist's browseId, fetches their full official Songs playlist
    (up to 100 tracks), and returns raw tracks + artist metadata.
    Falls back to deep search if no official profile exists.
    """
    try:
        import gevent
        with gevent.Timeout(20):
            artist_name = request.args.get('artistName', '')
            browse_id = request.args.get('browseId')
            artist_image = None
            
            if not artist_name and not browse_id:
                return jsonify({'error': 'artistName or browseId is required'}), 400

            if not browse_id and artist_name:
                browse_id, artist_image = _resolve_artist_browse_id(artist_name, yt)
            elif browse_id and not artist_name:
                # We have ID but no name, we'll discover name from the catalog response
                pass
                
            tracks = []
            artist_description = ''
            subscriber_count = ''
            
            if browse_id:
                tracks, artist_image, artist_description, subscriber_count = _fetch_official_playlist_catalog(
                    browse_id, artist_image, yt
                )

            tracks = _supplement_with_deep_search(artist_name, tracks, yt)

            logger.info(f"Artist catalog for '{artist_name}': {len(tracks)} raw tracks")

            return jsonify({
                'artistName': artist_name,
                'browseId': browse_id,
                'artistImage': artist_image or '',
                'description': artist_description,
                'subscriberCount': subscriber_count,
                'tracks': tracks,
                'totalRawTracks': len(tracks),
            })

    except gevent.Timeout:
        logger.error(json.dumps({
            "event": "timeout",
            "endpoint": "/api/artist-catalog-deep",
            "operation": "get_artist_catalog_deep",
            "threshold": 20,
            "exception": "gevent.Timeout"
        }))
        return jsonify({'error': 'Upstream request timed out'}), 504
    except Exception as e:
        logger.error(f"Artist catalog deep error: {str(e)}")
        return jsonify({'error': 'Failed to fetch artist catalog'}), 500





if __name__ == '__main__':
    import gevent
    from gevent.pywsgi import WSGIServer
    import time
    start_time = time.time()
    print(f"[DIAGNOSTIC] {time.time()} - Python YTMusic Service Startup Started")

    port = int(os.environ.get('PORT', 5000))
    
    print(f"[INFO] YTMusic API Service (Production WSGI) running on http://127.0.0.1:{port}")
    http_server = WSGIServer(('127.0.0.1', port), app)

    print(f"[DIAGNOSTIC] {time.time()} - Python YTMusic Service Startup Completed (Ready in {time.time() - start_time:.2f}s)")

    def shutdown():
        print("Shutting down YTMusic WSGI server gracefully...")
        http_server.stop()
        
    gevent.signal_handler(gevent.signal.SIGTERM, shutdown)
    gevent.signal_handler(gevent.signal.SIGINT, shutdown)
    
    http_server.serve_forever()
