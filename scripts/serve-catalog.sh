#!/usr/bin/env bash
# Serve the locally built catalog to the emulator (http://10.0.2.2:8787/).
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

DIST="${1:-$ORBIT_ROOT/curator/dist}"
[ -f "$DIST/version.json" ] || { orbit_log "no version.json in $DIST - run scripts/curator.sh first"; exit 1; }

orbit_log "serving $DIST on http://0.0.0.0:$ORBIT_CATALOG_PORT (emulator: $ORBIT_CATALOG_URL)"
# Stock http.server replies HTTP/1.0; some Windows security filters drop the tail
# of large responses. HTTP/1.1 with Content-Length and threading is reliable.
exec "$PYTHON" - "$DIST" "$ORBIT_CATALOG_PORT" <<'PY'
import functools
import http.server
import socketserver
import sys

directory, port = sys.argv[1], int(sys.argv[2])


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


with Server(("0.0.0.0", port), functools.partial(Handler, directory=directory)) as server:
    server.serve_forever()
PY
