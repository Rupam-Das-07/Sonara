import hashlib
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import youtube_audio_api as api


class AutoStreamBridgeTest(unittest.TestCase):
    def test_descriptor_is_cached_with_its_exact_url_and_replaced_on_fallback(self):
        for fid, codec, mime, rate in [('251', 'opus', 'audio/webm', 137), ('140', 'aac', 'audio/mp4', 130)]:
            url = f'https://r3.googlevideo.com/videoplayback?itag={fid}'
            descriptor = {'identity': hashlib.sha256(url.encode()).hexdigest(), 'formatId': fid,
                'codec': codec, 'mimeType': mime, 'isAudioOnly': True, 'bitrateKbps': rate}
            with patch.object(api, 'get_audio_info', return_value={'audio_url': url,
                    'codec': codec, 'format': mime, 'nominalBitrateKbps': rate, 'selectedStream': descriptor}):
                response = api._resolve_and_cache_stream('auto-fixture')
            self.assertEqual(response['selectedStream'], descriptor)
            self.assertEqual(api._stream_url_cache.get('auto-fixture')['selectedStream'], descriptor)
            self.assertIn(f'itag%3D{fid}', response['audio_url'])

    def test_bound_proxy_must_not_stream_a_fallback_under_the_old_descriptor(self):
        url = 'https://r3.googlevideo.com/videoplayback?itag=251'
        expired = Mock(status_code=403)
        with patch.object(api, 'fetch_stream_with_safe_redirects', return_value=(expired, None)), \
                patch.object(api, '_resolve_and_cache_stream') as extract:
            response = api.app.test_client().get('/stream-youtube-audio', query_string={
                'video_id': 'fwWTFuxHYvo', 'audio_url': url,
                'stream_identity': hashlib.sha256(url.encode()).hexdigest()})
        self.assertEqual(response.status_code, 410)
        expired.close.assert_called_once()
        extract.assert_not_called()


if __name__ == '__main__':
    unittest.main()
