from __future__ import annotations

import gzip
import hashlib
import hmac
import io
import json
import os
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Final

from .schema import ContributorBatch, SchemaError
from .storage import BatchStore, IdempotencyConflict


MAX_BODY_BYTES: Final = 2_000_000
MAX_CLOCK_SKEW_SECONDS: Final = 300
DEFAULT_RATE_LIMIT_REQUESTS_PER_MINUTE: Final = 60


class RequestTooLarge(ValueError):
    """Raised before or during body read when the configured limit is exceeded."""


class RateLimiter:
    """Small process-local sliding-window limiter for the single ingest worker."""

    def __init__(self, requests_per_minute: int) -> None:
        if requests_per_minute <= 0:
            raise ValueError("requests_per_minute must be positive")
        self.limit = requests_per_minute
        self.window_seconds = 60.0
        self._requests: defaultdict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            requests = self._requests[key]
            while requests and now - requests[0] >= self.window_seconds:
                requests.popleft()
            if len(requests) >= self.limit:
                return False
            requests.append(now)
            return True


def _parse_request_timestamp(value: str) -> datetime:
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return timestamp.astimezone(timezone.utc)


def verify_signature(secret: str, timestamp: str, body: bytes, provided: str) -> bool:
    if not provided.startswith("sha256="):
        return False
    candidate = provided[len("sha256="):]
    if len(candidate) != hashlib.sha256().digest_size * 2:
        return False
    expected = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(candidate, expected)


class ContributorRequestHandler(BaseHTTPRequestHandler):
    server_version = "CanopyContributor/0.1"

    @property
    def store(self) -> BatchStore:
        return self.server.store  # type: ignore[attr-defined]

    @property
    def shared_secret(self) -> str:
        return self.server.shared_secret  # type: ignore[attr-defined]

    @property
    def rate_limiter(self) -> RateLimiter | None:
        return getattr(self.server, "rate_limiter", None)

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            if self.path == "/ready":
                if self.store.ready():
                    self._respond(HTTPStatus.OK, {"status": "ready", "service": "canopy-contributor-ingestion"})
                else:
                    self._respond(HTTPStatus.SERVICE_UNAVAILABLE, {"status": "not_ready"})
                return
            self._respond(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._respond(HTTPStatus.OK, {"status": "ok", "service": "canopy-contributor-ingestion"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/contributor/batches":
            self._respond(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        try:
            if self.rate_limiter is not None and not self.rate_limiter.allow(self._client_key()):
                self._respond(HTTPStatus.TOO_MANY_REQUESTS, {"error": "rate_limited"}, retry_after="60")
                return
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json":
                self._respond(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "content_type_must_be_json"})
                return
            raw_body = self._read_body()
            timestamp = self.headers.get("X-Canopy-Timestamp", "")
            signature = self.headers.get("X-Canopy-Signature", "")
            if not timestamp or not signature or not verify_signature(self.shared_secret, timestamp, raw_body, signature):
                self._respond(HTTPStatus.UNAUTHORIZED, {"error": "invalid_signature"})
                return
            if abs((datetime.now(timezone.utc) - _parse_request_timestamp(timestamp)).total_seconds()) > MAX_CLOCK_SKEW_SECONDS:
                self._respond(HTTPStatus.UNAUTHORIZED, {"error": "stale_timestamp"})
                return
            encoding = self.headers.get("Content-Encoding", "").lower().strip()
            if encoding not in {"", "identity", "gzip"}:
                self._respond(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "unsupported_content_encoding"})
                return
            payload = self._decode_payload(raw_body, encoding)
            batch = ContributorBatch.from_dict(json.loads(payload))
            receipt = self.store.store(batch, payload)
            self._respond(HTTPStatus.OK, {
                "receipt_id": receipt.receipt_id,
                "batch_id": receipt.batch_id,
                "accepted_events": receipt.accepted_events,
            })
        except RequestTooLarge:
            self._respond(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "payload_too_large"})
        except (SchemaError, json.JSONDecodeError, UnicodeDecodeError, gzip.BadGzipFile, EOFError, ValueError) as error:
            self._respond(HTTPStatus.BAD_REQUEST, {"error": "invalid_batch", "message": str(error)})
        except IdempotencyConflict as error:
            self._respond(HTTPStatus.CONFLICT, {"error": "idempotency_conflict", "message": str(error)})
        except Exception:
            self._respond(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal_error"})

    def _read_body(self) -> bytes:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding:
            raise ValueError("chunked transfer encoding is not supported")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ValueError("invalid Content-Length") from error
        if length <= 0:
            raise ValueError("invalid Content-Length")
        if length > MAX_BODY_BYTES:
            # Drain a bounded amount before replying. Without this, a client
            # still streaming an oversized body can observe a connection reset
            # instead of the intended 413 response. The bound preserves the
            # request-size guarantee under malicious Content-Length values.
            self.rfile.read(min(length, MAX_BODY_BYTES + 1))
            raise RequestTooLarge
        body = self.rfile.read(length)
        if len(body) != length:
            raise ValueError("request body ended before Content-Length")
        return body

    def _client_key(self) -> str:
        # The service is only reachable through the internal proxy/tunnel. Caddy
        # and Cloudflare preserve the originating address in these headers.
        forwarded = self.headers.get("CF-Connecting-IP") or self.headers.get("X-Forwarded-For")
        if forwarded:
            return forwarded.split(",", 1)[0].strip()[:128] or "forwarded-empty"
        return self.client_address[0]

    @staticmethod
    def _decode_payload(raw_body: bytes, encoding: str) -> bytes:
        if encoding != "gzip":
            if len(raw_body) > MAX_BODY_BYTES:
                raise RequestTooLarge
            return raw_body
        with gzip.GzipFile(fileobj=io.BytesIO(raw_body), mode="rb") as compressed:
            payload = compressed.read(MAX_BODY_BYTES + 1)
        if len(payload) > MAX_BODY_BYTES:
            raise RequestTooLarge
        return payload

    def _respond(self, status: HTTPStatus, payload: dict[str, object], retry_after: str | None = None) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if retry_after is not None:
            self.send_header("Retry-After", retry_after)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        # Never log request bodies, prompts, or responses.
        return


def main() -> None:
    root = os.environ.get("CANOPY_CONTRIBUTOR_ROOT", "/data/canopy/contributor_pipeline")
    secret = os.environ.get("CANOPY_CONTRIBUTOR_SHARED_SECRET", "")
    if len(secret) < 32:
        raise SystemExit("CANOPY_CONTRIBUTOR_SHARED_SECRET must contain at least 32 characters")
    host = os.environ.get("CANOPY_CONTRIBUTOR_HOST", "127.0.0.1")
    port = int(os.environ.get("CANOPY_CONTRIBUTOR_PORT", "8791"))
    rate_limit = int(os.environ.get("CANOPY_RATE_LIMIT_REQUESTS_PER_MINUTE", str(DEFAULT_RATE_LIMIT_REQUESTS_PER_MINUTE)))
    server = ThreadingHTTPServer((host, port), ContributorRequestHandler)
    server.store = BatchStore(root)  # type: ignore[attr-defined]
    server.shared_secret = secret  # type: ignore[attr-defined]
    server.rate_limiter = RateLimiter(rate_limit)  # type: ignore[attr-defined]
    print(f"Canopy contributor ingestion listening on http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
