#!/usr/bin/env python3
"""Evaluator-only Atlas server; never serves the repository or Git directory.

Local source links show this checkout, including unshipped work. Solver launches
must use the runner's strict network isolation while this Portal is available.
"""
import argparse
import hashlib
import html
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ATLAS = ROOT / "docs/atlas"


def source_file(path):
    relative = Path(path)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Invalid source path")
    if not relative.parts or relative.parts[0] != "challenges":
        raise ValueError("Not a challenge source")
    file = ROOT / relative
    if file.resolve() != file.absolute() or file.suffix not in {".clj", ".md"}:
        raise ValueError("Invalid source file")
    if not file.is_file():
        raise ValueError("Source not found")
    return file


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ATLAS), **kwargs)

    def list_directory(self, path):
        self.send_error(404)

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if path.startswith("/source/"):
            try:
                file = source_file(path.removeprefix("/source/"))
                data = file.read_bytes()
            except (ValueError, OSError):
                self.send_error(404)
                return
            title = html.escape(str(file.relative_to(ROOT)))
            lines = "\n".join(
                f'<span id="L{i}"><a href="#L{i}">{i:4}</a> {html.escape(line)}</span>'
                for i, line in enumerate(data.decode().splitlines(), 1))
            body = (f'<!doctype html><meta charset="utf-8"><title>{title}</title>'
                    '<meta name="viewport" content="width=device-width,initial-scale=1">'
                    '<style>body{margin:2rem;color:#252b2c;background:#fafaf7}'
                    'pre{overflow:auto;line-height:1.6}span:target{background:#fff0b3}'
                    'a{color:#526b64}h1{font:1.2rem sans-serif}</style>'
                    f'<a href="/">Atlas</a><h1>{title}</h1>'
                    '<p>Local checkout source · not a shipped revision</p>'
                    f'<p>SHA-256: <code>{hashlib.sha256(data).hexdigest()}</code></p>'
                    f'<pre>{lines}</pre>').encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        file = ATLAS / path.lstrip("/")
        if ".." in Path(path).parts or not file.resolve().is_relative_to(ATLAS):
            self.send_error(404)
            return
        super().do_GET()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
