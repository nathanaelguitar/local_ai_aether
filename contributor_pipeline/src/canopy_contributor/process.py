from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from .schema import ContributorBatch, ContributorEvent, SchemaError
from .storage import BatchStore


EMAIL = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
PHONE = re.compile(r"(?<!\w)(?:\+?\d[\s().-]?){7,15}\d(?!\w)")
CARD = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)")
IP_ADDRESS = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

_REDACTION_RULES: tuple[tuple[str, re.Pattern[str], str], ...] = (
    ("email", EMAIL, "[REDACTED_EMAIL]"),
    ("phone", PHONE, "[REDACTED_PHONE]"),
    ("payment_number", CARD, "[REDACTED_PAYMENT_NUMBER]"),
    ("ip_address", IP_ADDRESS, "[REDACTED_IP]"),
)


@dataclass
class ProcessSummary:
    batches: int = 0
    bronze: int = 0
    silver: int = 0
    training: int = 0
    eval: int = 0
    quarantine: int = 0
    skipped: int = 0


def redact_with_report(text: str | None) -> tuple[str | None, dict[str, int]]:
    if text is None:
        return None, {}
    report: dict[str, int] = {}
    for name, pattern, replacement in _REDACTION_RULES:
        text, count = pattern.subn(replacement, text)
        if count:
            report[name] = report.get(name, 0) + count
    return text, report


def redact(text: str | None) -> tuple[str | None, int]:
    """Compatibility helper returning the redacted text and total replacements."""

    cleaned, report = redact_with_report(text)
    return cleaned, sum(report.values())


def _merge_reports(*reports: dict[str, int]) -> dict[str, int]:
    merged: dict[str, int] = {}
    for report in reports:
        for name, count in report.items():
            merged[name] = merged.get(name, 0) + count
    return dict(sorted(merged.items()))


def _redact_metadata(metadata: dict[str, str]) -> tuple[dict[str, str], dict[str, int]]:
    cleaned: dict[str, str] = {}
    reports: list[dict[str, int]] = []
    for key, value in metadata.items():
        redacted, report = redact_with_report(value)
        cleaned[key] = redacted or ""
        reports.append(report)
    return cleaned, _merge_reports(*reports)


def _has_truthy(metadata: dict[str, str], key: str) -> bool:
    return metadata.get(key, "").lower() in {"1", "true", "yes"}


def _failure_score(events: Iterable[ContributorEvent], response: str | None) -> tuple[int, list[str], str | None]:
    score = 0
    reasons: list[str] = []
    correction: str | None = None
    for event in events:
        if event.type == "responseRated" and event.metadata.get("rating") == "negative":
            score += 100
            reasons.append("thumbs_down")
        elif event.type == "responseRegenerated":
            score += 70
            reasons.append("regenerated")
        elif event.type == "userCorrection":
            score += 100
            reasons.append("user_correction")
            correction = event.user_correction or event.metadata.get("user_correction")
        elif event.type in {"responseTruncated", "responseEmpty", "inferenceFailed", "toolFailed", "outputValidationFailed"}:
            score += 100
            reasons.append(event.type)
        if _has_truthy(event.metadata, "truncated"):
            score += 100
            reasons.append("truncated")
        if _has_truthy(event.metadata, "validation_failed"):
            score += 100
            reasons.append("output_validation_failed")
    if not response:
        score += 100
        reasons.append("empty_response")
    return min(score, 100), sorted(set(reasons)), correction


def _control_sample(interaction_id: str, rate_percent: int) -> bool:
    return int(hashlib.sha256(interaction_id.encode("utf-8")).hexdigest()[:8], 16) % 100 < rate_percent


def _write_jsonl(path: Path, records: Iterable[dict[str, object]]) -> int:
    """Write a deterministic per-batch file so retries replace, rather than append."""

    items = list(records)
    if not items:
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    with temporary.open("w", encoding="utf-8") as handle:
        for item in items:
            handle.write(json.dumps(item, sort_keys=True, ensure_ascii=False))
            handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.chmod(0o600)
    os.replace(temporary, path)
    path.chmod(0o600)
    return len(items)


def _output_path(root: Path, dataset: str, day: Path, batch_id: str, suffix: str) -> Path:
    return root / dataset / day / f"{batch_id}.{suffix}.jsonl"


def _invalid_output_path(root: Path, raw_path: Path) -> Path:
    digest = hashlib.sha256(str(raw_path).encode("utf-8")).hexdigest()[:16]
    return root / "quarantine" / "invalid_batches" / f"{digest}.jsonl"


def process(
    root: str | Path,
    control_rate_percent: int = 2,
    redaction_threshold: int = 0,
) -> ProcessSummary:
    if not 0 <= control_rate_percent <= 100:
        raise ValueError("control_rate_percent must be between 0 and 100")
    if redaction_threshold < 0:
        raise ValueError("redaction_threshold must be non-negative")

    root = Path(root)
    store = BatchStore(root)
    ledger = store.ledger
    summary = ProcessSummary()
    for path in sorted((root / "raw").glob("**/*.json.gz")):
        summary.batches += 1
        raw_key = str(path.relative_to(root))
        if not ledger.claim(raw_key):
            summary.skipped += 1
            continue
        try:
            try:
                with gzip.open(path, "rt", encoding="utf-8") as handle:
                    batch = ContributorBatch.from_dict(json.load(handle))
            except (OSError, json.JSONDecodeError, SchemaError) as error:
                summary.quarantine += _write_jsonl(
                    _invalid_output_path(root, path),
                    [{"raw_path": raw_key, "reason": str(error)}],
                )
                ledger.complete(raw_key)
                continue

            ledger.set_batch_id(raw_key, batch.batch_id)
            relative_raw_path = path.relative_to(root / "raw")
            day = Path(*relative_raw_path.parts[:3])
            by_message: dict[str, list[ContributorEvent]] = defaultdict(list)
            generated: list[ContributorEvent] = []
            for event in batch.events:
                if event.message_id:
                    by_message[event.message_id].append(event)
                if event.type == "responseGenerated":
                    generated.append(event)

            bronze_records: list[dict[str, object]] = []
            silver_records: list[dict[str, object]] = []
            training_records: list[dict[str, object]] = []
            eval_records: list[dict[str, object]] = []
            quarantine_records: list[dict[str, object]] = []
            for event in generated:
                events = by_message.get(event.message_id or "", [event])
                score, reasons, correction = _failure_score(events, event.response)
                bronze = {
                    "batch_id": batch.batch_id,
                    "interaction_id": event.id,
                    "installation_id": batch.installation_id,
                    "timestamp": event.timestamp,
                    "prompt": event.prompt,
                    "model_output": event.response,
                    "model_version": event.model_version,
                    "prompt_version": event.prompt_version,
                    "app_version": event.app_version,
                    "failure_score": score,
                    "failure_reasons": reasons,
                    "user_correction": correction,
                    "event_ids": [item.id for item in events],
                    "metadata": event.metadata,
                }
                bronze_records.append(bronze)

                prompt, prompt_report = redact_with_report(event.prompt)
                response, response_report = redact_with_report(event.response)
                clean_correction, correction_report = redact_with_report(correction)
                clean_metadata, metadata_report = _redact_metadata(event.metadata)
                redaction_report = _merge_reports(prompt_report, response_report, correction_report, metadata_report)
                redaction_count = sum(redaction_report.values())
                fingerprint = hashlib.sha256(f"{prompt or ''}\0{response or ''}".encode("utf-8")).hexdigest()
                silver = bronze | {
                    "prompt": prompt,
                    "model_output": response,
                    "user_correction": clean_correction,
                    "metadata": clean_metadata,
                    "redaction_count": redaction_count,
                    "redaction_report": redaction_report,
                    "redaction_is_automated_guardrail_only": True,
                    "fingerprint": fingerprint,
                }
                if not ledger.reserve_fingerprint(fingerprint, batch.batch_id, "silver"):
                    continue
                silver_records.append(silver)
                if redaction_count > redaction_threshold:
                    quarantine_records.append(silver | {
                        "quarantine_reason": "redaction_threshold_exceeded",
                        "redaction_threshold": redaction_threshold,
                    })
                elif clean_correction and score >= 50:
                    training_records.append(silver)
                elif score == 0 and _control_sample(event.id, control_rate_percent):
                    eval_records.append(silver)
                elif score >= 50:
                    quarantine_records.append(silver | {"quarantine_reason": "failure_requires_review"})

            generated_message_ids = {event.message_id for event in generated if event.message_id}
            orphan_events = [
                {
                    "batch_id": batch.batch_id,
                    "installation_id": batch.installation_id,
                    "orphan_event": asdict(event),
                }
                for event in batch.events
                if event.type != "responseGenerated"
                and (not event.message_id or event.message_id not in generated_message_ids)
            ]
            if orphan_events:
                quarantine_records.extend({"batch_id": batch.batch_id, "orphan_event": item} for item in orphan_events)

            summary.bronze += _write_jsonl(_output_path(root, "bronze", day, batch.batch_id, "interactions"), bronze_records)
            summary.silver += _write_jsonl(_output_path(root, "silver", day, batch.batch_id, "interactions"), silver_records)
            summary.training += _write_jsonl(_output_path(root / "gold", "training", day, batch.batch_id, "candidate_corrections"), training_records)
            summary.eval += _write_jsonl(_output_path(root / "gold", "eval", day, batch.batch_id, "control_samples"), eval_records)
            summary.quarantine += _write_jsonl(_output_path(root, "quarantine", day, batch.batch_id, "review"), quarantine_records)
            ledger.complete(raw_key)
        except Exception:
            ledger.release(raw_key)
            raise
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Curate CanopyChat contributor batches into local datasets")
    parser.add_argument("--root", default=os.environ.get("CANOPY_CONTRIBUTOR_ROOT", "/data/canopy/contributor_pipeline"))
    parser.add_argument("--control-rate-percent", type=int, default=2)
    parser.add_argument(
        "--redaction-threshold",
        type=int,
        default=int(os.environ.get("CANOPY_REDACTION_THRESHOLD", "0")),
        help="Maximum automated PII replacements allowed before quarantine (default: 0)",
    )
    args = parser.parse_args()
    if not 0 <= args.control_rate_percent <= 100:
        raise SystemExit("--control-rate-percent must be between 0 and 100")
    if args.redaction_threshold < 0:
        raise SystemExit("--redaction-threshold must be non-negative")
    print(json.dumps(asdict(process(args.root, args.control_rate_percent, args.redaction_threshold)), sort_keys=True))


if __name__ == "__main__":
    main()
