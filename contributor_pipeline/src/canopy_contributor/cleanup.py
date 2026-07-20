"""Retention cleanup for the contributor pipeline.

Only non-gold datasets are eligible for automatic cleanup. Every removal is
recorded by path hash and dataset in the local audit log; no conversation
content is written to that log.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .storage import BatchStore, file_lock


@dataclass(frozen=True)
class RetentionPolicy:
    raw_days: int = 30
    quarantine_days: int = 7
    bronze_days: int = 90
    silver_days: int = 90

    @classmethod
    def from_environment(cls) -> "RetentionPolicy":
        values = {
            "raw_days": int(os.environ.get("CANOPY_RAW_RETENTION_DAYS", "30")),
            "quarantine_days": int(os.environ.get("CANOPY_QUARANTINE_RETENTION_DAYS", "7")),
            "bronze_days": int(os.environ.get("CANOPY_BRONZE_RETENTION_DAYS", "90")),
            "silver_days": int(os.environ.get("CANOPY_SILVER_RETENTION_DAYS", "90")),
        }
        if any(value < 0 for value in values.values()):
            raise ValueError("retention periods must be non-negative")
        return cls(**values)


def _secure_unlink(path: Path) -> bool:
    try:
        path.unlink()
        return True
    except FileNotFoundError:
        return False


def _is_approved(path: Path) -> bool:
    """A reviewer can preserve a quarantine file with a sidecar marker."""

    return path.with_name(path.name + ".approved").exists()


def _audit(root: Path, dataset: str, path: Path, reason: str) -> None:
    record = {
        "deleted_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "dataset": dataset,
        "path_sha256": hashlib.sha256(str(path.relative_to(root)).encode("utf-8")).hexdigest(),
        "reason": reason,
    }
    log_path = root / "logs" / "retention.jsonl"
    with file_lock(root / "processed" / "locks" / "retention.lock"):
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        log_path.chmod(0o600)


def cleanup(root: str | Path, policy: RetentionPolicy | None = None, *, now: datetime | None = None) -> dict[str, int]:
    """Remove expired raw/bronze/silver/quarantine files and preserve gold."""

    root = Path(root)
    store = BatchStore(root)
    policy = policy or RetentionPolicy.from_environment()
    now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    removed = {"raw": 0, "quarantine": 0, "bronze": 0, "silver": 0}

    # Raw retention starts only after the ledger records successful processing.
    raw_cutoff = now - timedelta(days=policy.raw_days)
    for path in sorted((root / "raw").glob("**/*.json.gz")):
        raw_key = str(path.relative_to(root))
        completed_at = store.ledger.completed_at(raw_key)
        if completed_at is None or completed_at > raw_cutoff:
            continue
        if _secure_unlink(path):
            removed["raw"] += 1
            _audit(root, "raw", path, "processed_retention")

    for dataset, days in (
        ("quarantine", policy.quarantine_days),
        ("bronze", policy.bronze_days),
        ("silver", policy.silver_days),
    ):
        cutoff = now - timedelta(days=days)
        for path in sorted((root / dataset).glob("**/*.jsonl")):
            if dataset == "quarantine" and _is_approved(path):
                continue
            try:
                modified = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
            except FileNotFoundError:
                continue
            if modified > cutoff:
                continue
            if _secure_unlink(path):
                removed[dataset] += 1
                _audit(root, dataset, path, "retention")
    return removed


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply contributor-pipeline retention policy")
    parser.add_argument("--root", default=os.environ.get("CANOPY_CONTRIBUTOR_ROOT", "/data/canopy/contributor_pipeline"))
    args = parser.parse_args()
    print(json.dumps(cleanup(args.root), sort_keys=True))


if __name__ == "__main__":
    main()
