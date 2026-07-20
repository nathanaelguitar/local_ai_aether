"""Authenticated, local-only deletion workflow for contributor data."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .schema import ContributorBatch
from .storage import BatchStore, Receipt, file_lock


UUID_PATTERN = re.compile(r"^[0-9a-fA-F-]{36}$")
RECEIPT_PATTERN = re.compile(r"^rcpt_[0-9a-fA-F-]{36}$")


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _authenticate(provided: str, expected: str) -> None:
    if len(expected) < 32 or not hmac.compare_digest(provided, expected):
        raise PermissionError("invalid administrator credential")


def _receipt(path: Path) -> Receipt | None:
    try:
        return Receipt(**json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return None


def _resolve_batches(root: Path, selector_type: str, selector: str) -> tuple[set[str], set[str]]:
    receipts: list[tuple[Path, Receipt]] = []
    for receipt_path in sorted((root / "processed" / "receipts").glob("*.json")):
        parsed = _receipt(receipt_path)
        if parsed:
            receipts.append((receipt_path, parsed))

    selected: list[tuple[Path, Receipt]] = []
    if selector_type == "batch_id":
        selected = [(path, item) for path, item in receipts if item.batch_id == selector]
        if not selected:
            raw_matches = (root / "raw").glob(f"**/{selector}.json.gz")
            return {selector}, {str(path.relative_to(root)) for path in raw_matches}
    elif selector_type == "receipt_id":
        selected = [(path, item) for path, item in receipts if item.receipt_id == selector]
    else:
        for receipt_path, item in receipts:
            if item.installation_id == selector:
                selected.append((receipt_path, item))
                continue
            raw_path = root / item.raw_path
            try:
                with gzip.open(raw_path, "rt", encoding="utf-8") as handle:
                    batch = ContributorBatch.from_dict(json.load(handle))
            except (OSError, ValueError, json.JSONDecodeError):
                continue
            if batch.installation_id == selector:
                selected.append((receipt_path, item))

    return (
        {item.batch_id for _, item in selected},
        {item.raw_path for _, item in selected},
    )


def _secure_unlink(path: Path) -> bool:
    try:
        size = path.stat().st_size
        if path.is_file() and size:
            with path.open("r+b", buffering=0) as handle:
                remaining = size
                zeroes = b"\x00" * min(1024 * 1024, size)
                while remaining:
                    chunk = zeroes if remaining >= len(zeroes) else b"\x00" * remaining
                    handle.write(chunk)
                    remaining -= len(chunk)
                handle.flush()
                os.fsync(handle.fileno())
        path.unlink()
        return True
    except FileNotFoundError:
        return False


def _record_matches(record: Any, batch_ids: set[str], installation_id: str | None) -> bool:
    if not isinstance(record, dict):
        return False
    if batch_ids and record.get("batch_id") in batch_ids:
        return True
    return installation_id is not None and record.get("installation_id") == installation_id


def _remove_derived(root: Path, batch_ids: set[str], installation_id: str | None) -> int:
    removed = 0
    for dataset in ("bronze", "silver", "gold", "quarantine"):
        for path in sorted((root / dataset).glob("**/*.jsonl")):
            retained: list[str] = []
            changed = False
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
            except OSError:
                continue
            for line in lines:
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    retained.append(line)
                    continue
                if _record_matches(record, batch_ids, installation_id):
                    removed += 1
                    changed = True
                else:
                    retained.append(line)
            if not changed:
                continue
            if retained:
                temporary = path.with_name(f".{path.name}.delete-{os.getpid()}")
                temporary.write_text("\n".join(retained) + "\n", encoding="utf-8")
                temporary.chmod(0o600)
                os.replace(temporary, path)
                path.chmod(0o600)
            else:
                _secure_unlink(path)
    return removed


def delete_data(
    root: str | Path,
    *,
    admin_token: str,
    installation_id: str | None = None,
    batch_id: str | None = None,
    receipt_id: str | None = None,
) -> dict[str, object]:
    """Delete matching raw and derived data and append a non-content tombstone."""

    expected = os.environ.get("CANOPY_CONTRIBUTOR_ADMIN_TOKEN", "")
    _authenticate(admin_token, expected)
    selectors = [("installation_id", installation_id), ("batch_id", batch_id), ("receipt_id", receipt_id)]
    selected = [(kind, value) for kind, value in selectors if value]
    if len(selected) != 1:
        raise ValueError("exactly one deletion selector is required")
    selector_type, selector = selected[0]
    assert selector is not None
    root = Path(root)
    BatchStore(root)
    if selector_type in {"installation_id", "batch_id"} and not UUID_PATTERN.fullmatch(selector):
        raise ValueError(f"{selector_type} must be a UUID")
    if selector_type == "receipt_id" and not RECEIPT_PATTERN.fullmatch(selector):
        raise ValueError("receipt_id has an unsafe format")
    batch_ids, raw_paths = _resolve_batches(root, selector_type, selector)
    installation_selector = selector if selector_type == "installation_id" else None

    with file_lock(root / "processed" / "locks" / "deletion.lock"):
        derived_records = _remove_derived(root, batch_ids, installation_selector)
        raw_files = 0
        receipt_files = 0
        for raw_path in raw_paths:
            if _secure_unlink(root / raw_path):
                raw_files += 1
        for batch in batch_ids:
            receipt_path = root / "processed" / "receipts" / f"{batch}.json"
            if _secure_unlink(receipt_path):
                receipt_files += 1
        BatchStore(root).ledger.delete_batches(batch_ids, raw_paths)
        tombstone = {
            "deletion_id": f"del_{uuid.uuid4()}",
            "deleted_at": _utc_now(),
            "selector_type": selector_type,
            "selector_sha256": hashlib.sha256(selector.encode("utf-8")).hexdigest(),
            "matched_batches": len(batch_ids),
            "removed_raw_files": raw_files,
            "removed_receipt_files": receipt_files,
            "removed_derived_records": derived_records,
        }
        tombstone_path = root / "deleted" / "tombstones.jsonl"
        with tombstone_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(tombstone, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        tombstone_path.chmod(0o600)
    return tombstone


def main() -> None:
    parser = argparse.ArgumentParser(description="Delete Canopy contributor data (local admin tool only)")
    parser.add_argument("--root", default=os.environ.get("CANOPY_CONTRIBUTOR_ROOT", "/data/canopy/contributor_pipeline"))
    parser.add_argument("--token", default=os.environ.get("CANOPY_CONTRIBUTOR_ADMIN_TOKEN"))
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument("--installation-id")
    selector.add_argument("--batch-id")
    selector.add_argument("--receipt-id")
    args = parser.parse_args()
    if not args.token:
        raise SystemExit("provide --token or CANOPY_CONTRIBUTOR_ADMIN_TOKEN")
    try:
        result = delete_data(
            args.root,
            admin_token=args.token,
            installation_id=args.installation_id,
            batch_id=args.batch_id,
            receipt_id=args.receipt_id,
        )
    except (PermissionError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
