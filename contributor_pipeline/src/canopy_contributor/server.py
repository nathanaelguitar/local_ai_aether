from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import os
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Final

from .schema import ContributorBatch, SchemaError
from .storage import BatchStore, IdempotencyConflict


MAX_BODY_BYTES: Final = 2_000_000
MAX_CLOCK_SKEW_SECONDS: Final = 300


def _parse_request_timestamp(value: str) -> datetime:
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return timestamp.astimezone(timezone.utc)


def verify_signature(secret: str, timestamp: str, body: bytes, provided: str) -> bool:
    expected = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(provided.removeprefix("sha256="), expected)


class ContributorRequestHandler(BaseHTTPRequestHandler):
    server_version = "CanopyContributor/0.1"

    @property
    def store(self) -> BatchStore:
        return self.server.store  # type: ignore[attr-defined]

    @property
    def shared_secret(self) -> str:
        return self.server.shared_secret  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._respond(HTTPStatus.OK, {"status": "ok", "service": "canopy-contributor-ingestion"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/contributor/batches":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        try:
            raw_body = self._read_body()
            timestamp = self.headers.get("X-Canopy-Timestamp", "")
            signature = self.headers.get("X-Canopy-Signature", "")
            if not timestamp or not signature or not verify_signature(self.shared_secret, timestamp, raw_body, signature):
                self._respond(HTTPStatus.UNAUTHORIZED, {"error": "invalid_signature"})
                return
            if abs((datetime.now(timezone.utc) - _parse_request_timestamp(timestamp)).total_seconds()) > MAX_CLOCK_SKEW_SECONDS:
                self._respond(HTTPStatus.UNAUTHORIZED, {"error": "stale_timestamp"})
                return
            payload = gzip.decompress(raw_body) if self.headers.get("Content-Encoding", "").lower() == "gzip" else raw_body
            if len(payload) > MAX_BODY_BYTES:
                self._respond(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "payload_too_large"})
                return
            batch = ContributorBatch.from_dict(json.loads(payload))
            receipt = self.store.store(batch, payload)
            self._respond(HTTPStatus.OK, {
                "receipt_id": receipt.receipt_id,
                "batch_id": receipt.batch_id,
                "accepted_events": receipt.accepted_events,
            })
        except (SchemaError, json.JSONDecodeError, UnicodeDecodeError, gzip.BadGzipFile, ValueError) as error:
            self._respond(HTTPStatus.BAD_REQUEST, {"error": "invalid_batch", "message": str(error)})
        except IdempotencyConflict as error:
            self._respond(HTTPStatus.CONFLICT, {"error": "idempotency_conflict", "message": str(error)})
        except Exception:
            self._respond(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal_error"})

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("invalid Content-Length")
        return self.rfile.read(length)

    def _respond(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        # Never log request bodies, prompts, or responses.
        return


def main() -> None:
    root = os.environ.get("CANOPY_CONTRIBUTOR_ROOT", "/data/canopy-contributor")
    secret = os.environ.get("CANOPY_CONTRIBUTOR_SHARED_SECRET", "")
    if len(secret) < 32:
        raise SystemExit("CANOPY_CONTRIBUTOR_SHARED_SECRET must contain at least 32 characters")
    host = os.environ.get("CANOPY_CONTRIBUTOR_HOST", "127.0.0.1")
    port = int(os.environ.get("CANOPY_CONTRIBUTOR_PORT", "8791"))
    server = ThreadingHTTPServer((host, port), ContributorRequestHandler)
    server.store = BatchStore(root)  # type: ignore[attr-defined]
    server.shared_secret = secret  # type: ignore[attr-defined]
    print(f"Canopy contributor ingestion listening on http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
