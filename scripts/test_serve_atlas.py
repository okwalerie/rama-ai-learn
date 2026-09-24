"""Check evaluator source rendering and repository escape rejection."""
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen

import serve_atlas as atlas


class AtlasServerTests(unittest.TestCase):
    def test_source_readback_and_denied_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            public = root / "docs/atlas"
            public.mkdir(parents=True)
            (public / "index.html").write_text("atlas fixture")
            source = root / "challenges/demo/test-resources/module.clj"
            source.parent.mkdir(parents=True)
            source.write_text('<script>alert("answer")</script>\n')
            (root / "private-key").write_text("do not expose")
            (public / "escape.md").symlink_to(root / "private-key")
            (source.parent / "escape.md").symlink_to(root / "private-key")
            with patch.object(atlas, "ROOT", root), patch.object(atlas, "ATLAS", public):
                server = atlas.ThreadingHTTPServer(("127.0.0.1", 0), atlas.Handler)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                base = f"http://127.0.0.1:{server.server_port}"
                try:
                    with urlopen(base + "/") as response:
                        self.assertEqual(response.read(), b"atlas fixture")
                    with urlopen(base + "/source/challenges/demo/test-resources/module.clj") as response:
                        body = response.read().decode()
                        self.assertIn('&lt;script&gt;', body)
                        self.assertNotIn('<script>', body)
                        self.assertIn('id="L1"', body)
                        self.assertIn('SHA-256:', body)
                        self.assertIn('not a shipped revision', body)
                        self.assertEqual(response.headers['Cache-Control'], 'no-store')
                    for path in ["/escape.md", "/../private-key", "/%2e%2e/private-key",
                                 "/source/../private-key", "/source/.git/config",
                                 "/source/challenges/demo/test-resources/escape.md",
                                 "/source/challenges/demo/test-resources/"]:
                        with self.subTest(path=path), self.assertRaises(HTTPError) as error:
                            urlopen(base + path)
                        self.assertEqual(error.exception.code, 404)
                finally:
                    server.shutdown()
                    thread.join()
                    server.server_close()


if __name__ == "__main__":
    unittest.main()
