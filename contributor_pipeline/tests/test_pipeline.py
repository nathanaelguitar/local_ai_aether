from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from canopy_contributor.process import process, redact
from canopy_contributor.schema import ContributorBatch, SchemaError
from canopy_contributor.server import ContributorRequestHandler, verify_signature
from canopy_contributor.storage import BatchStore, IdempotencyConflict


def batch_payload(*events: dict[str, object]) -> dict[str, object]:
    return {
        "schema_version": 1,
        "batch_id": str(uuid4()),
        "installation_id": str(uuid4()),
        "sent_at": "2026-07-18T22:00:00Z",
        "consent_for_model_improvement": True,
        "events": list(events),
    }


def event(event_type: str = "responseGenerated", **overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "id": str(uuid4()),
        "type": event_type,
        "timestamp": "2026-07-18T22:00:00Z",
        "channel": "beta",
        "appVersion": "1.1.1 (20)",
        "modelVersion": "1.1.1",
        "promptVersion": "p1",
        "conversationID": str(uuid4()),
        "messageID": str(uuid4()),
        "prompt": "What is 2 + 2?",
        "response": "4",
        "metadata": {},
    }
    value.update(overrides)
    return value


class SchemaTests(unittest.TestCase):
    def test_explicit_consent_is_required(self) -> None:
        payload = batch_payload(event())
        payload["consent_for_model_improvement"] = False
        with self.assertRaises(SchemaError):
            ContributorBatch.from_dict(payload)

    def test_current_ios_camel_case_event_is_accepted(self) -> None:
        parsed = ContributorBatch.from_dict(batch_payload(event()))
        self.assertEqual(parsed.events[0].app_version, "1.1.1 (20)")
        self.assertEqual(parsed.events[0].model_version, "1.1.1")


class StorageTests(unittest.TestCase):
    def test_same_batch_is_idempotent_but_changed_content_conflicts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            payload = batch_payload(event())
            raw = json.dumps(payload).encode()
            store = BatchStore(directory)
            batch = ContributorBatch.from_dict(payload)
            first = store.store(batch, raw)
            second = store.store(batch, raw)
            self.assertEqual(first.receipt_id, second.receipt_id)
            with self.assertRaises(IdempotencyConflict):
                store.store(batch, raw + b" ")


class ProcessorTests(unittest.TestCase):
    def test_failure_with_correction_becomes_training_candidate_and_redacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = event(prompt="Email me at test@example.com", response="Wrong answer")
            correction = event(
                "userCorrection",
                messageID=generated["messageID"],
                prompt=None,
                response=None,
                userCorrection="The correct answer is 4.",
            )
            payload = batch_payload(generated, correction)
            raw_dir = root / "raw" / "2026" / "07" / "18"
            raw_dir.mkdir(parents=True)
            with gzip.open(raw_dir / "test.json.gz", "wt", encoding="utf-8") as handle:
                json.dump(payload, handle)
            summary = process(root)
            self.assertEqual(summary.training, 1)
            training = next((root / "training").glob("**/*.jsonl")).read_text(encoding="utf-8")
            self.assertIn("[REDACTED_EMAIL]", training)
            self.assertNotIn("test@example.com", training)

    def test_redactor_handles_common_values(self) -> None:
        cleaned, count = redact("email a@b.com, call +1 555 123 4567, 192.168.1.4")
        self.assertEqual(count, 3)
        self.assertNotIn("a@b.com", cleaned or "")


class SignatureTests(unittest.TestCase):
    def test_signature_matches_exact_body(self) -> None:
        secret = "a" * 32
        timestamp = "2026-07-18T22:00:00Z"
        body = b'{"schema_version":1}'
        value = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
        self.assertTrue(verify_signature(secret, timestamp, body, f"sha256={value}"))
        self.assertFalse(verify_signature(secret, timestamp, body + b" ", f"sha256={value}"))


class IngestionServerTests(unittest.TestCase):
    def test_authenticated_batch_is_stored_and_retried_idempotently(self) -> None:
        from http.server import ThreadingHTTPServer

        with tempfile.TemporaryDirectory() as directory:
            secret = "s" * 32
            server = ThreadingHTTPServer(("127.0.0.1", 0), ContributorRequestHandler)
            server.store = BatchStore(directory)  # type: ignore[attr-defined]
            server.shared_secret = secret  # type: ignore[attr-defined]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                payload = json.dumps(batch_payload(event())).encode()
                timestamp = "2026-07-18T22:00:00Z"
                # Use current time so the handler's replay guard accepts it.
                from datetime import datetime, timezone

                timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
                signature = hmac.new(secret.encode(), timestamp.encode() + b"." + payload, hashlib.sha256).hexdigest()
                url = f"http://127.0.0.1:{server.server_port}/v1/contributor/batches"
                headers = {
                    "Content-Type": "application/json",
                    "X-Canopy-Timestamp": timestamp,
                    "X-Canopy-Signature": f"sha256={signature}",
                }
                request = Request(url, data=payload, headers=headers, method="POST")
                with urlopen(request, timeout=2) as response:
                    first = json.loads(response.read())
                with urlopen(request, timeout=2) as response:
                    second = json.loads(response.read())
                self.assertEqual(first["receipt_id"], second["receipt_id"])
                self.assertEqual(len(list(Path(directory, "raw").glob("**/*.json.gz"))), 1)
            finally:
                server.shutdown()
                thread.join(timeout=2)
                server.server_close()
