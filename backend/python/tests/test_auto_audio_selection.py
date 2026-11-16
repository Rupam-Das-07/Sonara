"""Offline fixtures exercise the installed yt-dlp selector, never live YouTube."""
import copy
import hashlib
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import yt_dlp
import youtube_service as service


def fmt(fid, codec, video, abr=None, protocol='https'):
    return {'format_id': fid, 'url': f'https://r3.googlevideo.com/videoplayback?itag={fid}',
            'ext': 'webm' if codec == 'opus' else 'mp4', 'acodec': codec, 'vcodec': video,
            'abr': abr, 'tbr': abr if video == 'none' else 999, 'protocol': protocol,
            'http_headers': {'User-Agent': f'format-{fid}'}}


class AutoAudioSelectionTest(unittest.TestCase):
    def resolve(self, formats, android_inventory=None):
        def extract(opts, url):
            inventory = android_inventory if opts.get('extractor_args', {}).get('youtube', {}).get('player_client') == ['android'] and android_inventory is not None else formats
            with yt_dlp.YoutubeDL({**opts, 'quiet': True, 'no_warnings': True}) as ydl:
                return ydl.process_ie_result({'id': 'fwWTFuxHYvo', 'title': 'Candyland',
                    'formats': copy.deepcopy(inventory)}, download=False)
        with patch.object(service, '_extract_with_retry', side_effect=extract):
            return service.get_audio_info('https://www.youtube.com/watch?v=fwWTFuxHYvo')

    def test_android_inventory_restriction_does_not_hide_available_audio(self):
        muxed = fmt('18', 'mp4a.40.2', 'avc1')
        audio = fmt('251', 'opus', 'none', 130.462)
        result = self.resolve([muxed, audio], android_inventory=[muxed])
        self.assertEqual(result['audio_url'], audio['url'])
        self.assertEqual(result['nominalBitrateKbps'], 130)

    def test_muxed_first_or_last_never_outranks_available_audio(self):
        muxed = fmt('18', 'mp4a.40.2', 'avc1', 500)
        audio = fmt('251', 'opus', 'none', 137)
        for formats in ([muxed, audio], [audio, muxed]):
            result = self.resolve(formats)
            self.assertEqual(result['audio_url'], audio['url'])
            self.assertEqual(result['http_headers']['User-Agent'], 'format-251')

    def test_no_audio_only_format_preserves_honest_muxed_fallback(self):
        result = self.resolve([fmt('18', 'mp4a.40.2', 'avc1', 500)])
        self.assertIsNone(result['nominalBitrateKbps'])
        self.assertFalse(result['selectedStream']['isAudioOnly'])
        self.assertEqual(result['selectedStream']['mimeType'], 'video/mp4')

    def test_audio_without_abr_is_selected_without_using_muxed_total_bitrate(self):
        for abr in (None, 0, -1):
            audio = fmt('251', 'opus', 'none', abr)
            result = self.resolve([fmt('18', 'mp4a.40.2', 'avc1', 500), audio])
            self.assertEqual(result['audio_url'], audio['url'])
            self.assertIsNone(result['nominalBitrateKbps'])

    def test_existing_direct_proxy_does_not_select_an_hls_manifest(self):
        result = self.resolve([fmt('18', 'mp4a.40.2', 'avc1'), fmt('hls', 'opus', 'none', 300, 'm3u8_native')])
        self.assertEqual(result['audio_url'], fmt('18', 'mp4a.40.2', 'avc1')['url'])

    def test_fallback_descriptor_uses_its_own_url_codec_container_and_bitrate(self):
        first = self.resolve([fmt('251', 'opus', 'none', 137)])
        fallback = fmt('140', 'mp4a.40.2', 'none', 129.546)
        second = self.resolve([fallback])
        self.assertNotEqual(first['selectedStream']['identity'], second['selectedStream']['identity'])
        self.assertEqual(second['selectedStream'], {
            'identity': hashlib.sha256(fallback['url'].encode()).hexdigest(), 'formatId': '140',
            'codec': 'aac', 'mimeType': 'audio/mp4', 'isAudioOnly': True, 'bitrateKbps': 130,
        })


if __name__ == '__main__':
    unittest.main()
