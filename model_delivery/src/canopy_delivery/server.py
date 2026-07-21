"""Canopy model delivery HTTP server."""

from __future__ import annotations

import json
import logging
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .r2 import presign_r2_get
from .tokens import TokenRegistry

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

_HOST = os.environ.get("CANOPY_DELIVERY_HOST", "0.0.0.0")
_PORT = int(os.environ.get("CANOPY_DELIVERY_PORT", "8792"))
_DATA_ROOT = Path(os.environ.get("CANOPY_DELIVERY_ROOT", "/data/canopy/model_delivery"))
_URL_TTL = int(os.environ.get("CANOPY_DELIVERY_URL_TTL_SECONDS", "900"))

_R2_ACCOUNT_ID = os.environ.get("CANOPY_DELIVERY_R2_ACCOUNT_ID", "")
_R2_ACCESS_KEY = os.environ.get("CANOPY_DELIVERY_R2_ACCESS_KEY_ID", "")
_R2_SECRET_KEY = os.environ.get("CANOPY_DELIVERY_R2_SECRET_ACCESS_KEY", "")
_R2_BUCKET = os.environ.get("CANOPY_DELIVERY_R2_BUCKET", "")

_registry: TokenRegistry | None = None
_registry_lock = threading.Lock()


def _get_registry() -> TokenRegistry:
    global _registry
    if _registry is None:
        with _registry_lock:
            if _registry is None:
                _registry = TokenRegistry(_DATA_ROOT / "tokens.db")
    return _registry


def _load_meta() -> dict | None:
    path = _DATA_ROOT / "manifest_meta.json"
    if not path.exists():
        return None
    return json.loads(path.read_text())


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: object) -> None:
        log.info("%s - %s", self.address_string(), fmt % args)

    def _json(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _bearer(self) -> str | None:
        auth = self.headers.get("Authorization", "")
        return auth[7:] if auth.startswith("Bearer ") else None

    def do_GET(self) -> None:
        if self.path in ("/health", "/ready"):
            self._health()
        elif self.path in ("/v1/model-manifest", "/v1/model-manifest/refresh"):
            self._manifest()
        else:
            self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path == "/v1/tokens":
            self._register_token()
        else:
            self._json(404, {"error": "not_found"})

    def _health(self) -> None:
        meta = _load_meta()
        if meta is None:
            self._json(503, {"status": "initializing", "detail": "run sync first"})
        else:
            self._json(200, {"status": "ok"})

    def _register_token(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body: dict = {}
        if length:
            try:
                body = json.loads(self.rfile.read(length))
            except json.JSONDecodeError:
                self._json(400, {"error": "invalid_json"})
                return
        token, install_id = _get_registry().register(body.get("install_id", ""))
        self._json(201, {"token": token, "install_id": install_id})

    def _manifest(self) -> None:
        token = self._bearer()
        if not token:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Bearer realm="canopy-delivery"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if not _get_registry().validate(token):
            self._json(403, {"error": "invalid_token"})
            return

        meta = _load_meta()
        if meta is None:
            self._json(503, {"error": "service_unavailable", "detail": "model not synced"})
            return

        try:
            download_url, url_expires_at = presign_r2_get(
                account_id=_R2_ACCOUNT_ID,
                access_key=_R2_ACCESS_KEY,
                secret_key=_R2_SECRET_KEY,
                bucket=_R2_BUCKET,
                key=meta["filename"],
                expires_seconds=_URL_TTL,
            )
        except Exception as exc:
            log.error("presign error: %s", exc)
            self._json(500, {"error": "presign_failed"})
            return

        self._json(200, {
            "version": meta["version"],
            "filename": meta["filename"],
            "size_bytes": meta["size_bytes"],
            "sha256": meta["sha256"],
            "download_url": download_url,
            "url_expires_at": url_expires_at,
        })


def main() -> None:
    _DATA_ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((_HOST, _PORT), _Handler)
    log.info("canopy-delivery listening on %s:%d", _HOST, _PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
