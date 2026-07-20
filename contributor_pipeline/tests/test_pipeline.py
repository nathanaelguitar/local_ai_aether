from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import os
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from canopy_contributor.deletion import delete_data
from canopy_contributor.cleanup import RetentionPolicy, cleanup
from canopy_contributor.process import process, redact
from canopy_contributor.schema import ContributorBatch, SchemaError
from canopy_contributor.server import ContributorRequestHandler, MAX_BODY_BYTES, RateLimiter, verify_signature
from canopy_contributor.storage import BatchStore, IdempotencyConflict


def batch_payload(*events: dict[str, object], batch_id: str | None = None, installation_id: str | None = None) -> dict[str, object]:
    return {
        "schema_version": 1,
        "batch_id": batch_id or str(uuid4()),
        "installation_id": installation_id or str(uuid4()),
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


def write_raw(root: Path, payload: dict[str, object], name: str = "batch.json.gz") -> None:
    raw_dir = root / "raw" / "2026" / "07" / "18"
    raw_dir.mkdir(parents=True, exist_ok=True)
    with gzip.open(raw_dir / name, "wt", encoding="utf-8") as handle:
        json.dump(payload, handle)


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
    def test_required_persistent_storage_layout_is_created(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            BatchStore(directory)
            self.assertEqual(
                {path.name for path in Path(directory).iterdir()},
                {"raw", "quarantine", "bronze", "silver", "gold", "processed", "deleted", "logs", "backups"},
            )
            self.assertTrue((Path(directory) / "processed" / "ledger.sqlite3").exists())
            self.assertTrue((Path(directory) / "processed" / "receipts").is_dir())

    def test_same_batch_is_idempotent_but_changed_content_conflicts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            payload = batch_payload(event())
            raw = json.dumps(payload).encode()
            store = BatchStore(directory)
            batch = ContributorBatch.from_dict(payload)
            first = store.store(batch, raw)
            second = BatchStore(directory).store(batch, raw)
            self.assertEqual(first.receipt_id, second.receipt_id)
            with self.assertRaises(IdempotencyConflict):
                store.store(batch, raw + b" ")

    def test_cross_process_style_concurrent_writes_create_one_raw_batch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            payload = batch_payload(event())
            raw = json.dumps(payload).encode()
            batch = ContributorBatch.from_dict(payload)
            receipts: list[str] = []

            def write() -> None:
                receipts.append(BatchStore(directory).store(batch, raw).receipt_id)

            threads = [threading.Thread(target=write) for _ in range(4)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            self.assertEqual(set(receipts), {f"rcpt_{batch.batch_id}"})
            self.assertEqual(len(list(Path(directory, "raw").glob("**/*.json.gz"))), 1)


class ProcessorTests(unittest.TestCase):
    def test_failure_with_correction_becomes_training_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = event(response="Wrong answer")
            correction = event(
                "userCorrection",
                messageID=generated["messageID"],
                prompt=None,
                response=None,
                userCorrection="The correct answer is 4.",
            )
            payload = batch_payload(generated, correction)
            write_raw(root, payload)
            summary = process(root)
            self.assertEqual(summary.training, 1)
            training = next((root / "gold" / "training").glob("**/*.jsonl")).read_text(encoding="utf-8")
            self.assertIn("candidate_corrections", str(next((root / "gold" / "training").glob("**/*.jsonl"))))
            self.assertIn("The correct answer is 4.", training)

    def test_redaction_threshold_quarantines_candidate_and_reports_patterns(self) -> None:
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
            write_raw(root, batch_payload(generated, correction))
            summary = process(root, redaction_threshold=0)
            self.assertEqual(summary.training, 0)
            self.assertEqual(summary.quarantine, 1)
            review = next((root / "quarantine").glob("**/*.jsonl")).read_text(encoding="utf-8")
            self.assertIn("redaction_threshold_exceeded", review)
            self.assertIn("email", review)
            self.assertNotIn("test@example.com", review)

    def test_processing_ledger_prevents_duplicate_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_raw(root, batch_payload(event()))
            first = process(root, control_rate_percent=100)
            second = process(root, control_rate_percent=100)
            self.assertEqual(first.silver, 1)
            self.assertEqual(second.silver, 0)
            self.assertEqual(second.skipped, 1)
            rows = [
                line
                for path in (root / "silver").glob("**/*.jsonl")
                for line in path.read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual(len(rows), 1)

    def test_controls_route_to_eval_and_malformed_raw_is_quarantined(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_raw(root, batch_payload(event()))
            eval_summary = process(root, control_rate_percent=100)
            self.assertEqual(eval_summary.eval, 1)

            invalid_path = root / "raw" / "2026" / "07" / "18" / "invalid.json.gz"
            with gzip.open(invalid_path, "wb") as handle:
                handle.write(b"not-json")
            invalid_summary = process(root)
            self.assertEqual(invalid_summary.quarantine, 1)
            invalid_records = list((root / "quarantine" / "invalid_batches").glob("*.jsonl"))
            self.assertEqual(len(invalid_records), 1)

            malformed_path = root / "raw" / "2026" / "07" / "18" / "malformed.json.gz"
            malformed_path.write_bytes(b"not-a-gzip-stream")
            malformed_summary = process(root)
            self.assertEqual(malformed_summary.quarantine, 1)
            self.assertEqual(len(list((root / "quarantine" / "invalid_batches").glob("*.jsonl"))), 2)

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
        self.assertFalse(verify_signature(secret, timestamp, body, value))


class IngestionServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.secret = "s" * 32
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ContributorRequestHandler)
        self.server.store = BatchStore(self.directory.name)  # type: ignore[attr-defined]
        self.server.shared_secret = self.secret  # type: ignore[attr-defined]
        self.server.rate_limiter = RateLimiter(100)  # type: ignore[attr-defined]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.thread.join(timeout=2)
        self.server.server_close()
        self.directory.cleanup()

    def request(self, body: bytes, *, timestamp: str | None = None, encoding: str | None = None) -> tuple[int, dict[str, object]]:
        timestamp = timestamp or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        signature = hmac.new(self.secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
        headers = {
            "Content-Type": "application/json",
            "X-Canopy-Timestamp": timestamp,
            "X-Canopy-Signature": f"sha256={signature}",
        }
        if encoding:
            headers["Content-Encoding"] = encoding
        request = Request(
            f"http://127.0.0.1:{self.server.server_port}/v1/contributor/batches",
            data=body,
            headers=headers,
            method="POST",
        )
        with urlopen(request, timeout=2) as response:
            return response.status, json.loads(response.read())

    def test_authenticated_batch_is_stored_and_retried_idempotently(self) -> None:
        payload = json.dumps(batch_payload(event())).encode()
        first_status, first = self.request(payload)
        second_status, second = self.request(payload, timestamp=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
        self.assertEqual(first_status, second_status, 200)
        self.assertEqual(first["receipt_id"], second["receipt_id"])
        self.assertEqual(len(list(Path(self.directory.name, "raw").glob("**/*.json.gz"))), 1)

    def test_gzip_body_is_accepted_and_replay_window_is_enforced(self) -> None:
        payload = json.dumps(batch_payload(event())).encode()
        compressed = gzip.compress(payload)
        status, _ = self.request(compressed, encoding="gzip")
        self.assertEqual(status, 200)
        stale = (datetime.now(timezone.utc) - timedelta(minutes=6)).isoformat().replace("+00:00", "Z")
        with self.assertRaises(HTTPError) as error:
            self.request(payload, timestamp=stale)
        self.assertEqual(error.exception.code, 401)

    def test_request_size_is_rejected_before_body_processing(self) -> None:
        body = b"x" * (MAX_BODY_BYTES + 1)
        with self.assertRaises(HTTPError) as error:
            self.request(body)
        self.assertEqual(error.exception.code, 413)

    def test_unauthorized_upload_is_rejected(self) -> None:
        payload = json.dumps(batch_payload(event())).encode()
        request = Request(
            f"http://127.0.0.1:{self.server.server_port}/v1/contributor/batches",
            data=payload,
            headers={"Content-Type": "application/json", "Content-Length": str(len(payload))},
            method="POST",
        )
        with self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=2)
        self.assertEqual(error.exception.code, 401)
        self.assertEqual(len(list(Path(self.directory.name, "raw").glob("**/*.json.gz"))), 0)

    def test_malformed_gzip_is_rejected_without_ingestion(self) -> None:
        timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        body = b"not-a-gzip-stream"
        signature = hmac.new(self.secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
        request = Request(
            f"http://127.0.0.1:{self.server.server_port}/v1/contributor/batches",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Content-Encoding": "gzip",
                "X-Canopy-Timestamp": timestamp,
                "X-Canopy-Signature": f"sha256={signature}",
            },
            method="POST",
        )
        with self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=2)
        self.assertEqual(error.exception.code, 400)

    def test_rate_limit_rejects_excess_requests(self) -> None:
        self.server.rate_limiter = RateLimiter(1)  # type: ignore[attr-defined]
        payload = json.dumps(batch_payload(event())).encode()
        self.request(payload)
        with self.assertRaises(HTTPError) as error:
            self.request(json.dumps(batch_payload(event())).encode())
        self.assertEqual(error.exception.code, 429)

    def test_readiness_check_uses_persistent_store(self) -> None:
        request = Request(f"http://127.0.0.1:{self.server.server_port}/ready", method="GET")
        with urlopen(request, timeout=2) as response:
            self.assertEqual(response.status, 200)


class DeletionTests(unittest.TestCase):
    def test_authenticated_installation_deletion_removes_derived_data_and_keeps_tombstone_content_free(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installation_id = str(uuid4())
            generated = event(response="Wrong")
            correction = event(
                "userCorrection",
                messageID=generated["messageID"],
                prompt=None,
                response=None,
                userCorrection="Correct response",
            )
            payload = batch_payload(generated, correction, installation_id=installation_id)
            raw = json.dumps(payload).encode()
            store = BatchStore(root)
            batch = ContributorBatch.from_dict(payload)
            receipt = store.store(batch, raw)
            process(root)
            token = "d" * 32
            old = os.environ.get("CANOPY_CONTRIBUTOR_ADMIN_TOKEN")
            os.environ["CANOPY_CONTRIBUTOR_ADMIN_TOKEN"] = token
            try:
                result = delete_data(root, admin_token=token, installation_id=installation_id)
            finally:
                if old is None:
                    os.environ.pop("CANOPY_CONTRIBUTOR_ADMIN_TOKEN", None)
                else:
                    os.environ["CANOPY_CONTRIBUTOR_ADMIN_TOKEN"] = old
            self.assertEqual(result["matched_batches"], 1)
            self.assertFalse((root / receipt.raw_path).exists())
            self.assertFalse((root / "processed" / "receipts" / f"{batch.batch_id}.json").exists())
            tombstone = (root / "deleted" / "tombstones.jsonl").read_text(encoding="utf-8")
            self.assertNotIn("Correct response", tombstone)
            self.assertNotIn("Wrong", tombstone)

            for dataset in ("raw", "bronze", "silver", "gold", "quarantine"):
                self.assertEqual(list((root / dataset).glob("**/*.jsonl" if dataset != "raw" else "**/*.json.gz")), [])

    def test_installation_deletion_still_resolves_after_raw_retention(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installation_id = str(uuid4())
            payload = batch_payload(event(response="A response"), installation_id=installation_id)
            batch = ContributorBatch.from_dict(payload)
            BatchStore(root).store(batch, json.dumps(payload).encode())
            process(root, control_rate_percent=100)
            cleanup(root, RetentionPolicy(raw_days=0, quarantine_days=90, bronze_days=90, silver_days=90))
            self.assertEqual(list((root / "raw").glob("**/*.json.gz")), [])
            token = "e" * 32
            old = os.environ.get("CANOPY_CONTRIBUTOR_ADMIN_TOKEN")
            os.environ["CANOPY_CONTRIBUTOR_ADMIN_TOKEN"] = token
            try:
                result = delete_data(root, admin_token=token, installation_id=installation_id)
            finally:
                if old is None:
                    os.environ.pop("CANOPY_CONTRIBUTOR_ADMIN_TOKEN", None)
                else:
                    os.environ["CANOPY_CONTRIBUTOR_ADMIN_TOKEN"] = old
            self.assertEqual(result["matched_batches"], 1)
            self.assertEqual(list((root / "silver").glob("**/*.jsonl")), [])


class RetentionTests(unittest.TestCase):
    def test_retention_removes_expired_processed_data_but_preserves_gold(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = batch_payload(event())
            batch = ContributorBatch.from_dict(payload)
            BatchStore(root).store(batch, json.dumps(payload).encode())
            process(root, control_rate_percent=100)
            old = (datetime.now(timezone.utc) - timedelta(days=100)).timestamp()
            for path in root.glob("**/*.jsonl"):
                if "logs" not in path.parts:
                    os.utime(path, (old, old))
            summary = cleanup(root, RetentionPolicy(raw_days=0, quarantine_days=0, bronze_days=0, silver_days=0))
            self.assertEqual(summary["bronze"], 1)
            self.assertEqual(summary["silver"], 1)
            self.assertEqual(summary["raw"], 1)
            self.assertTrue(list((root / "gold" / "eval").glob("**/*.jsonl")))
            self.assertTrue((root / "logs" / "retention.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
