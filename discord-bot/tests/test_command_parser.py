import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bot.command_parser import build_remote_payload, parse_command_input


class CommandParserTests(unittest.TestCase):
    def test_parse_command_input_parses_prefix_command(self) -> None:
        parsed = parse_command_input('!say "hello world"', '!')
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(parsed.name, 'say')
        self.assertEqual(parsed.args[0], 'hello world')
        self.assertTrue(parsed.is_remote)

    def test_build_remote_payload_validates_show_attachment(self) -> None:
        result = build_remote_payload(
            'show',
            ['10'],
            {
                'url': 'https://cdn.example.com/a.png',
                'name': 'a.png',
                'size': 1024,
                'content_type': 'image/png',
            },
            8_000_000,
        )

        self.assertIn('payload', result)
        self.assertEqual(result['payload']['seconds'], 10)
        self.assertEqual(result['payload']['imageContentType'], 'image/png')

    def test_build_remote_payload_rejects_invalid_volume(self) -> None:
        result = build_remote_payload('volume', ['101'], None, 8_000_000)
        self.assertIn('error', result)

    def test_build_remote_payload_permstatus(self) -> None:
        result = build_remote_payload('permstatus', [], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload'], {})

    def test_build_remote_payload_parentpin(self) -> None:
        result = build_remote_payload('parentpin', ['1255'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['pin'], '1255')

    def test_build_remote_payload_shield_default_status(self) -> None:
        result = build_remote_payload('shield', [], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['action'], 'status')

    def test_build_remote_payload_playaudio(self) -> None:
        result = build_remote_payload('playaudio', ['https://cdn.example.com/a.mp3', '3'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['url'], 'https://cdn.example.com/a.mp3')
        self.assertEqual(result['payload']['repeat'], 3)

    def test_build_remote_payload_sayurdu(self) -> None:
        result = build_remote_payload('sayurdu', ['aap', 'kaise', 'hain'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['text'], 'aap kaise hain')

    def test_build_remote_payload_unlockapp(self) -> None:
        result = build_remote_payload('unlockapp', ['com.example.app'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['packageName'], 'com.example.app')

    def test_build_remote_payload_filestat(self) -> None:
        result = build_remote_payload('filestat', ['/storage/emulated/0/test.txt'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['path'], '/storage/emulated/0/test.txt')

    def test_build_remote_payload_torchpattern(self) -> None:
        result = build_remote_payload('torchpattern', ['4', '200', '300'], None, 8_000_000)
        self.assertIn('payload', result)
        self.assertEqual(result['payload']['repeats'], 4)
        self.assertEqual(result['payload']['on_ms'], 200)
        self.assertEqual(result['payload']['off_ms'], 300)


if __name__ == '__main__':
    unittest.main()
