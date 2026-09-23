#!/usr/bin/env python3
"""Exact-host CONNECT proxy and in-namespace loopback bridge for solver CLIs.

No wildcard hosts, arbitrary proxy URLs, plaintext HTTP forwarding, or private
destination IPs. TLS remains end-to-end; this is not a response-content filter.
"""
import ipaddress
import os
import re
import select
import socket
import socketserver
import subprocess
import sys
import threading


PROVIDER_HOSTS = {
    "claude": {"api.anthropic.com"},
    "opencode": {"openrouter.ai"},
}
DOCUMENTATION_HOSTS = {"redplanetlabs.com"}
DEPENDENCY_HOSTS = {"nexus.redplanetlabs.com", "repo.maven.apache.org", "repo.clojars.org"}


def allowed_hosts(agent):
    if agent not in PROVIDER_HOSTS:
        raise ValueError("Strict network mode currently supports Claude and OpenCode/OpenRouter only")
    return PROVIDER_HOSTS[agent] | DOCUMENTATION_HOSTS | DEPENDENCY_HOSTS


def connect_public(host):
    addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    # Check every answer, then connect to the checked numeric address: do not
    # resolve again in create_connection (DNS rebinding / private-IP bypass).
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise ValueError("Non-public proxy destination")
    last_error = None
    for family, kind, proto, _, address in addresses:
        connection = socket.socket(family, kind, proto)
        try:
            connection.settimeout(15)
            connection.connect(address)
            return connection
        except OSError as exc:
            connection.close()
            last_error = exc
    raise last_error


def relay(left, right):
    while True:
        ready, _, _ = select.select([left, right], [], [], 300)
        if not ready:
            return
        for source in ready:
            data = source.recv(65536)
            if not data:
                return
            (right if source is left else left).sendall(data)


class ConnectHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(30)
        authority = "<malformed>"
        try:
            # Unbuffered header read avoids consuming tunneled TLS bytes.
            header = bytearray()
            while not header.endswith(b"\r\n\r\n"):
                byte = self.request.recv(1)
                if not byte or len(header) >= 16384:
                    return
                header.extend(byte)
            request = bytes(header).split(b"\r\n", 1)[0].decode("ascii").split()
            if len(request) != 3:
                raise ValueError("Malformed proxy request")
            method, requested_authority, version = request
            host, separator, port = requested_authority.rpartition(":")
            authority = (requested_authority if re.fullmatch(r"[a-zA-Z0-9.-]+:[0-9]+", requested_authority)
                         else "<invalid authority>")
            if (method != "CONNECT" or version not in ("HTTP/1.0", "HTTP/1.1")
                    or separator != ":" or port != "443"
                    or host not in self.server.allowed_hosts):
                raise ValueError("Destination not allowed")
            with connect_public(host) as upstream:
                print(f"solver proxy allowed destination: {host}:443 -> {upstream.getpeername()[0]}",
                      file=sys.stderr, flush=True)
                self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(self.request, upstream)
        except (ValueError, UnicodeError):
            # Log only the bounded authority, never headers/tokens/paths.
            print(f"solver proxy denied destination: {authority[:200]!r}", file=sys.stderr, flush=True)
            self.request.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        except OSError:
            # Connection failures fail closed. Do not leak upstream diagnostics.
            print(f"solver proxy connection closed/failed: {authority[:200]!r}", file=sys.stderr, flush=True)
            return


class ProxyServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, path, hosts):
        self.allowed_hosts = frozenset(hosts)
        super().__init__(str(path), ConnectHandler)


class BridgeHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as upstream:
                upstream.connect(self.server.socket_path)
                relay(self.request, upstream)
        except OSError:
            return


class BridgeServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    block_on_close = False


def bridge_command(socket_path, command):
    with BridgeServer(("127.0.0.1", 0), BridgeHandler) as server:
        server.socket_path = socket_path
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        proxy = f"http://127.0.0.1:{server.server_address[1]}"
        env = dict(os.environ)
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
            env[key] = proxy
        # Only loopback IN THIS namespace bypasses the proxy (OpenCode's own
        # local server and solver-owned nREPL). Host loopback is unreachable.
        env["NO_PROXY"] = env["no_proxy"] = "localhost,127.0.0.1,::1"
        try:
            return subprocess.call(command, env=env, close_fds=True)
        finally:
            server.shutdown()


if __name__ == "__main__":
    if len(sys.argv) < 4 or sys.argv[1] != "--bridge":
        raise SystemExit("usage: solver_proxy.py --bridge SOCKET COMMAND [ARG ...]")
    raise SystemExit(bridge_command(sys.argv[2], sys.argv[3:]))
