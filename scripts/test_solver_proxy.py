import contextlib
import io
import os
from pathlib import Path
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from isolate_solver import isolated_command
from solver_proxy import ProxyServer, allowed_hosts, connect_public


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.sendall(self.request.recv(4096))


class ProxyTests(unittest.TestCase):
    def test_resolution_rejects_private_and_mixed_addresses_before_connecting(self):
        for addresses in (["127.0.0.1"], ["10.1.2.3"], ["169.254.169.254"],
                          ["::1"], ["fc00::1"], ["8.8.8.8", "192.168.1.2"]):
            records = [(socket.AF_INET6 if ":" in addr else socket.AF_INET,
                        socket.SOCK_STREAM, 6, "", (addr, 443)) for addr in addresses]
            with self.subTest(addresses=addresses), patch("socket.getaddrinfo", return_value=records), \
                    patch("socket.socket") as connection:
                with self.assertRaisesRegex(ValueError, "Non-public"):
                    connect_public("api.anthropic.com")
                connection.assert_not_called()

    def test_connect_uses_checked_numeric_address(self):
        with patch("socket.getaddrinfo", return_value=[
                (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))]) as resolve, \
                patch("socket.socket") as connection:
            connect_public("api.anthropic.com")
            resolve.assert_called_once()
            connection.return_value.connect.assert_called_once_with(("8.8.8.8", 443))

    def test_unsupported_agent_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "supports Claude"):
            allowed_hosts("pi")

    @unittest.skipUnless(shutil.which("bwrap"), "Linux bubblewrap required")
    def test_namespace_egress_and_real_proxy_requests(self):
        # A local echo fixture substitutes for an approved remote endpoint only
        # in this test. Direct access to that SAME host listener must fail.
        with tempfile.TemporaryDirectory() as tmp, \
                socketserver.ThreadingTCPServer(("127.0.0.1", 0), Echo) as echo:
            echo_thread = threading.Thread(target=echo.serve_forever, daemon=True)
            echo_thread.start()
            root = Path(tmp)
            repo = root / "repo"
            repo.mkdir()
            (repo / "private-key").write_text("host-only")
            host_port = echo.server_address[1]
            probe = r'''
import os, socket, sys
from pathlib import Path
from urllib.parse import urlparse
proxy=urlparse(os.environ['HTTPS_PROXY'])
def connect_request(authority, method='CONNECT'):
    s=socket.create_connection((proxy.hostname,proxy.port),timeout=2)
    s.sendall((method+' '+authority+' HTTP/1.1\r\nHost: '+authority+'\r\n\r\n').encode())
    header=b''
    while not header.endswith(b'\r\n\r\n'):
        part=s.recv(1)
        assert part
        header+=part
    return s,header
for authority, method in [('github.com:443','CONNECT'),('raw.githubusercontent.com:443','CONNECT'),
 ('api.anthropic.com.evil.invalid:443','CONNECT'),('api.anthropic.com:80','CONNECT'),
 ('user@api.anthropic.com:443','CONNECT'),('127.0.0.1:443','CONNECT'),
 ('169.254.169.254:443','CONNECT'),('[::1]:443','CONNECT'),
 ('http://api.anthropic.com/private?key=do-not-log','GET')]:
    s,header=connect_request(authority,method)
    assert b'403' in header,(authority,header)
    s.close()
s,header=connect_request('api.anthropic.com:443')
assert b'200' in header,header
s.sendall(b'approved-tunnel')
assert s.recv(4096)==b'approved-tunnel'
s.close()
for address in [('127.0.0.1',int(sys.argv[1])),('1.1.1.1',443),('169.254.169.254',80)]:
    try:
        s=socket.create_connection(address,timeout=.3)
    except OSError:
        pass
    else:
        s.close()
        raise AssertionError('direct egress succeeded: '+str(address))
assert not Path('private-key').exists()
assert not Path('/home/user/workspace/repos/rama-demo-gallery/README.md').exists()
assert 'CHALLENGE_KEY' not in os.environ
print('approved tunnel works; origin, private, metadata, host-loopback and direct internet blocked')
'''
            audit = io.StringIO()
            with ProxyServer(root / "provider.sock", allowed_hosts("claude")) as proxy, \
                    patch("solver_proxy.connect_public", side_effect=lambda _: socket.create_connection(echo.server_address)), \
                    contextlib.redirect_stderr(audit):
                proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
                proxy_thread.start()
                try:
                    args, env = isolated_command(repo, "demo", "claude",
                        ["python3", "-c", probe, str(host_port)], root, strict=True)
                    self.assertNotIn("--share-net", args)
                    result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn("approved tunnel works", result.stdout)
                finally:
                    proxy.shutdown()
                    echo.shutdown()
            self.assertIn("allowed destination: api.anthropic.com:443", audit.getvalue())
            self.assertIn("denied destination: 'github.com:443'", audit.getvalue())
            self.assertNotIn("do-not-log", audit.getvalue())


if __name__ == "__main__":
    unittest.main()
