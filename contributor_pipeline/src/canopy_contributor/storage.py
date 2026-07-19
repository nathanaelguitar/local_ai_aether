from __future__ import annotations

import gzip
import hashlib
import json
import os
import tempfile
import threading
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

from .schema import ContributorBatch


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


class BatchStore:
    """Writes raw contributor batches once and keeps a durable idempotency receipt."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        self._lock = threading.Lock()
        for directory in ("raw", "receipts", "bronze", "silver", "training", "eval", "quarantine"):
            (self.root / directory).mkdir(parents=True, exist_ok=True)

    def store(self, batch: ContributorBatch, payload: bytes) -> Receipt:
        digest = hashlib.sha256(payload).hexdigest()
        receipt_path = self.root / "receipts" / f"{batch.batch_id}.json"
        with self._lock:
            if receipt_path.exists():
                receipt = Receipt(**json.loads(receipt_path.read_text(encoding="utf-8")))
                if receipt.payload_sha256 != digest:
                    raise IdempotencyConflict("batch_id was already accepted with different content")
                return receipt

            now = datetime.now(timezone.utc)
            raw_directory = self.root / "raw" / now.strftime("%Y/%m/%d")
            raw_directory.mkdir(parents=True, exist_ok=True)
            raw_path = raw_directory / f"{batch.batch_id}.json.gz"
            self._atomic_write_bytes(raw_path, gzip.compress(payload, mtime=0))
            receipt = Receipt(
                receipt_id=f"rcpt_{batch.batch_id}",
                batch_id=batch.batch_id,
                accepted_events=len(batch.events),
                payload_sha256=digest,
                received_at=now.isoformat().replace("+00:00", "Z"),
                raw_path=str(raw_path.relative_to(self.root)),
            )
            self._atomic_write_text(receipt_path, json.dumps(asdict(receipt), sort_keys=True))
            return receipt

    @staticmethod
    def _atomic_write_bytes(path: Path, payload: bytes) -> None:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
            temp_path = Path(temporary.name)
        os.replace(temp_path, path)

    @staticmethod
    def _atomic_write_text(path: Path, text: str) -> None:
        BatchStore._atomic_write_bytes(path, text.encode("utf-8"))
