from __future__ import annotations

import gzip
import hashlib
import json
import os
import sqlite3
import tempfile
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

import fcntl

from .schema import ContributorBatch


STORAGE_DIRECTORIES = (
    "raw",
    "quarantine",
    "bronze",
    "silver",
    "gold",
    "processed",
    "deleted",
    "logs",
    "backups",
)


class IdempotencyConflict(ValueError):
    pass


@dataclass(frozen=True)
class Receipt:
    receipt_id: str
    batch_id: str
    accepted_events: int
    payload_sha256: str
    received_at: str
    raw_path: str
    installation_id: str = ""


class BatchStore:
    """Writes raw contributor batches once and keeps a durable idempotency receipt."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        for directory in STORAGE_DIRECTORIES:
            path = self.root / directory
            path.mkdir(parents=True, exist_ok=True)
            path.chmod(0o700)
        for directory in ("receipts", "locks"):
            path = self.root / "processed" / directory
            path.mkdir(parents=True, exist_ok=True)
            path.chmod(0o700)
        self.ledger = ProcessingLedger(self.root)

    def store(self, batch: ContributorBatch, payload: bytes) -> Receipt:
        digest = hashlib.sha256(payload).hexdigest()
        receipt_path = self.root / "processed" / "receipts" / f"{batch.batch_id}.json"
        with file_lock(self.root / "processed" / "locks" / "ingestion.lock"):
            if receipt_path.exists():
                receipt = Receipt(**json.loads(receipt_path.read_text(encoding="utf-8")))
                if receipt.payload_sha256 != digest:
                    raise IdempotencyConflict("batch_id was already accepted with different content")
                return receipt

            now = datetime.now(timezone.utc)
            raw_directory = self.root / "raw" / now.strftime("%Y/%m/%d")
            raw_directory.mkdir(parents=True, exist_ok=True)
            raw_directory.chmod(0o700)
            raw_path = raw_directory / f"{batch.batch_id}.json.gz"
            if raw_path.exists():
                try:
                    with gzip.open(raw_path, "rb") as existing:
                        existing_digest = hashlib.sha256(existing.read()).hexdigest()
                except (OSError, gzip.BadGzipFile) as error:
                    raise IdempotencyConflict("batch_id has an unreadable immutable raw file") from error
                if existing_digest != digest:
                    raise IdempotencyConflict("batch_id was already written with different content")
            else:
                self._atomic_write_bytes(raw_path, gzip.compress(payload, mtime=0))
            receipt = Receipt(
                receipt_id=f"rcpt_{batch.batch_id}",
                batch_id=batch.batch_id,
                accepted_events=len(batch.events),
                payload_sha256=digest,
                received_at=now.isoformat().replace("+00:00", "Z"),
                raw_path=str(raw_path.relative_to(self.root)),
                installation_id=batch.installation_id,
            )
            self._atomic_write_text(receipt_path, json.dumps(asdict(receipt), sort_keys=True))
            return receipt

    @staticmethod
    def _atomic_write_bytes(path: Path, payload: bytes) -> None:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False, mode="wb") as temporary:
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
            temp_path = Path(temporary.name)
        temp_path.chmod(0o600)
        os.replace(temp_path, path)
        path.chmod(0o600)

    @staticmethod
    def _atomic_write_text(path: Path, text: str) -> None:
        BatchStore._atomic_write_bytes(path, text.encode("utf-8"))

    def ready(self) -> bool:
        """Check that the persistent store and SQLite ledger are available."""

        try:
            with self.ledger._connection() as connection:
                connection.execute("SELECT 1").fetchone()
            return all((self.root / directory).is_dir() for directory in STORAGE_DIRECTORIES)
        except (OSError, sqlite3.Error):
            return False


@contextmanager
def file_lock(path: Path) -> Iterator[None]:
    """Coordinate writers across worker processes on the same data volume."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.touch(mode=0o600, exist_ok=True)
    path.chmod(0o600)
    with path.open("r+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class ProcessingLedger:
    """SQLite ledger for crash-safe curator idempotency and global fingerprints."""

    STALE_PROCESSING_SECONDS = 60 * 60

    def __init__(self, root: str | Path) -> None:
        self.path = Path(root) / "processed" / "ledger.sqlite3"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.touch(mode=0o600, exist_ok=True)
        self.path.chmod(0o600)
        with file_lock(self.path.parent / "locks" / "ledger-init.lock"):
            with self._connection() as connection:
                connection.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS processed_batches (
                        raw_path TEXT PRIMARY KEY,
                        batch_id TEXT,
                        status TEXT NOT NULL,
                        started_at REAL NOT NULL,
                        finished_at TEXT
                    );
                    CREATE INDEX IF NOT EXISTS processed_batches_batch_id
                        ON processed_batches(batch_id);
                    CREATE TABLE IF NOT EXISTS emitted_records (
                        fingerprint TEXT PRIMARY KEY,
                        batch_id TEXT NOT NULL,
                        dataset TEXT NOT NULL,
                        emitted_at TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS emitted_records_batch_id
                        ON emitted_records(batch_id);
                    CREATE TABLE IF NOT EXISTS request_replays (
                        signature_hash TEXT PRIMARY KEY,
                        seen_at REAL NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS request_replays_seen_at
                        ON request_replays(seen_at);
                    """
                )

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.execute("PRAGMA busy_timeout=30000")
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
        try:
            yield connection
        finally:
            connection.close()

    def claim(self, raw_path: str) -> bool:
        now = datetime.now(timezone.utc).timestamp()
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT status, started_at FROM processed_batches WHERE raw_path = ?",
                (raw_path,),
            ).fetchone()
            if row and row[0] == "completed":
                connection.rollback()
                return False
            if row and row[0] == "processing" and now - float(row[1]) < self.STALE_PROCESSING_SECONDS:
                connection.rollback()
                return False
            connection.execute(
                """
                INSERT INTO processed_batches(raw_path, status, started_at)
                VALUES (?, 'processing', ?)
                ON CONFLICT(raw_path) DO UPDATE SET
                    status = 'processing', started_at = excluded.started_at,
                    finished_at = NULL
                """,
                (raw_path, now),
            )
            connection.commit()
            return True

    def set_batch_id(self, raw_path: str, batch_id: str) -> None:
        with self._connection() as connection:
            connection.execute(
                "UPDATE processed_batches SET batch_id = ? WHERE raw_path = ?",
                (batch_id, raw_path),
            )

    def complete(self, raw_path: str) -> None:
        finished = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        with self._connection() as connection:
            connection.execute(
                "UPDATE processed_batches SET status = 'completed', finished_at = ? WHERE raw_path = ?",
                (finished, raw_path),
            )

    def release(self, raw_path: str) -> None:
        with self._connection() as connection:
            connection.execute("DELETE FROM processed_batches WHERE raw_path = ?", (raw_path,))

    def completed_at(self, raw_path: str) -> datetime | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT status, finished_at FROM processed_batches WHERE raw_path = ?",
                (raw_path,),
            ).fetchone()
        if not row or row[0] != "completed" or not row[1]:
            return None
        try:
            return datetime.fromisoformat(str(row[1]).replace("Z", "+00:00")).astimezone(timezone.utc)
        except ValueError:
            return None

    def reserve_fingerprint(self, fingerprint: str, batch_id: str, dataset: str) -> bool:
        emitted = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        with self._connection() as connection:
            cursor = connection.execute(
                """
                INSERT OR IGNORE INTO emitted_records(fingerprint, batch_id, dataset, emitted_at)
                VALUES (?, ?, ?, ?)
                """,
                (fingerprint, batch_id, dataset, emitted),
            )
            return cursor.rowcount == 1

    def claim_request_signature(self, signature: str, *, now: float | None = None) -> bool:
        """Reject an exact signed-request replay within the five-minute window.

        Only a hash of the HMAC header is retained. The signature itself is not
        content, but hashing it also avoids retaining reusable authentication
        material in the persistent ledger.
        """

        current = now if now is not None else datetime.now(timezone.utc).timestamp()
        signature_hash = hashlib.sha256(signature.encode("ascii", "strict")).hexdigest()
        cutoff = current - 300
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM request_replays WHERE seen_at < ?", (cutoff,))
            cursor = connection.execute(
                "INSERT OR IGNORE INTO request_replays(signature_hash, seen_at) VALUES (?, ?)",
                (signature_hash, current),
            )
            connection.commit()
            return cursor.rowcount == 1

    def delete_batches(self, batch_ids: set[str], raw_paths: set[str]) -> None:
        if not batch_ids and not raw_paths:
            return
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if batch_ids:
                placeholders = ",".join("?" for _ in batch_ids)
                values = tuple(batch_ids)
                connection.execute(f"DELETE FROM emitted_records WHERE batch_id IN ({placeholders})", values)
                connection.execute(f"DELETE FROM processed_batches WHERE batch_id IN ({placeholders})", values)
            if raw_paths:
                placeholders = ",".join("?" for _ in raw_paths)
                connection.execute(
                    f"DELETE FROM processed_batches WHERE raw_path IN ({placeholders})",
                    tuple(raw_paths),
                )
            connection.commit()
