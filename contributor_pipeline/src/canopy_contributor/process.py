from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from .schema import ContributorBatch, ContributorEvent, SchemaError


EMAIL = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
PHONE = re.compile(r"(?<!\w)(?:\+?\d[\s().-]?){7,15}\d(?!\w)")
CARD = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)")
IP_ADDRESS = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")


@dataclass
class ProcessSummary:
    batches: int = 0
    bronze: int = 0
    silver: int = 0
    training: int = 0
    eval: int = 0
    quarantine: int = 0


def redact(text: str | None) -> tuple[str | None, int]:
    if text is None:
        return None, 0
    replacements = 0

    def replace(pattern: re.Pattern[str], replacement: str, value: str) -> str:
        nonlocal replacements
        value, count = pattern.subn(replacement, value)
        replacements += count
        return value

    text = replace(EMAIL, "[REDACTED_EMAIL]", text)
    text = replace(PHONE, "[REDACTED_PHONE]", text)
    text = replace(CARD, "[REDACTED_PAYMENT_NUMBER]", text)
    text = replace(IP_ADDRESS, "[REDACTED_IP]", text)
    return text, replacements


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
    items = list(records)
    if not items:
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for item in items:
            handle.write(json.dumps(item, sort_keys=True, ensure_ascii=False))
            handle.write("\n")
    return len(items)


def process(root: str | Path, control_rate_percent: int = 2) -> ProcessSummary:
    root = Path(root)
    summary = ProcessSummary()
    seen: set[str] = set()
    for path in sorted((root / "raw").glob("**/*.json.gz")):
        summary.batches += 1
        try:
            with gzip.open(path, "rt", encoding="utf-8") as handle:
                batch = ContributorBatch.from_dict(json.load(handle))
        except (OSError, json.JSONDecodeError, SchemaError) as error:
            summary.quarantine += _write_jsonl(root / "quarantine" / "invalid_batches.jsonl", [{"raw_path": str(path), "reason": str(error)}])
            continue

        by_message: dict[str, list[ContributorEvent]] = defaultdict(list)
        generated: list[ContributorEvent] = []
        for event in batch.events:
            if event.message_id:
                by_message[event.message_id].append(event)
            if event.type == "responseGenerated":
                generated.append(event)

        relative_raw_path = path.relative_to(root / "raw")
        day = Path(*relative_raw_path.parts[:3])
        for event in generated:
            events = by_message.get(event.message_id or "", [event])
            score, reasons, correction = _failure_score(events, event.response)
            bronze = {
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
            summary.bronze += _write_jsonl(root / "bronze" / day / "interactions.jsonl", [bronze])
            prompt, prompt_redactions = redact(event.prompt)
            response, response_redactions = redact(event.response)
            correction, correction_redactions = redact(correction)
            fingerprint = hashlib.sha256(f"{prompt or ''}\0{response or ''}".encode("utf-8")).hexdigest()
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            silver = bronze | {
                "prompt": prompt,
                "model_output": response,
                "user_correction": correction,
                "redaction_count": prompt_redactions + response_redactions + correction_redactions,
                "fingerprint": fingerprint,
            }
            summary.silver += _write_jsonl(root / "silver" / day / "interactions.jsonl", [silver])
            if correction and score >= 50:
                summary.training += _write_jsonl(root / "training" / day / "candidate_corrections.jsonl", [silver])
            elif score == 0 and _control_sample(event.id, control_rate_percent):
                summary.eval += _write_jsonl(root / "eval" / day / "control_samples.jsonl", [silver])
            elif score >= 50:
                summary.quarantine += _write_jsonl(root / "quarantine" / day / "failure_review.jsonl", [silver])

        generated_ids = {event.id for event in generated}
        orphan_events = [event for event in batch.events if event.type != "responseGenerated" and event.id not in generated_ids and not event.message_id]
        if orphan_events:
            summary.quarantine += _write_jsonl(root / "quarantine" / day / "orphan_events.jsonl", [asdict(event) for event in orphan_events])
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Curate CanopyChat contributor batches into local datasets")
    parser.add_argument("--root", required=True, help="CANOPY_CONTRIBUTOR_ROOT used by the ingestion service")
    parser.add_argument("--control-rate-percent", type=int, default=2)
    args = parser.parse_args()
    if not 0 <= args.control_rate_percent <= 100:
        raise SystemExit("--control-rate-percent must be between 0 and 100")
    print(json.dumps(asdict(process(args.root, args.control_rate_percent)), sort_keys=True))


if __name__ == "__main__":
    main()
