from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any
from uuid import UUID


MAX_EVENTS_PER_BATCH = 100
MAX_TEXT_LENGTH = 20_000
ALLOWED_EVENT_TYPES = {
    "responseGenerated",
    "responseRated",
    "responseRegenerated",
    "messageResent",
    "searchSuggested",
    "searchChosen",
    "webSearchRequested",
    "webSearchPerformed",
    "issueReported",
    "responseTruncated",
    "responseEmpty",
    "inferenceFailed",
    "toolFailed",
    "outputValidationFailed",
    "userCorrection",
}


class SchemaError(ValueError):
    """A batch is structurally unsafe or incompatible with the ingestion protocol."""


def _required_string(value: Any, field: str, max_length: int = 256) -> str:
    if not isinstance(value, str) or not value.strip():
        raise SchemaError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise SchemaError(f"{field} exceeds {max_length} characters")
    return value.strip()


def _optional_text(value: Any, field: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise SchemaError(f"{field} must be a string when present")
    if len(value) > MAX_TEXT_LENGTH:
        raise SchemaError(f"{field} exceeds {MAX_TEXT_LENGTH} characters")
    return value


def _parse_uuid(value: Any, field: str) -> str:
    text = _required_string(value, field, 64)
    try:
        return str(UUID(text))
    except ValueError as error:
        raise SchemaError(f"{field} must be a UUID") from error


def _parse_timestamp(value: Any, field: str) -> str:
    text = _required_string(value, field, 64)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise SchemaError(f"{field} must be ISO-8601") from error
    if parsed.tzinfo is None:
        raise SchemaError(f"{field} must include a timezone")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _metadata(value: Any) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise SchemaError("metadata must be an object")
    if len(value) > 40:
        raise SchemaError("metadata contains too many fields")
    output: dict[str, str] = {}
    for key, item in value.items():
        if not isinstance(key, str) or not isinstance(item, str):
            raise SchemaError("metadata keys and values must be strings")
        if len(key) > 64 or len(item) > 1_024:
            raise SchemaError("metadata field exceeds its size limit")
        output[key] = item
    return output


@dataclass(frozen=True)
class ContributorEvent:
    id: str
    type: str
    timestamp: str
    channel: str
    app_version: str
    model_version: str | None
    prompt_version: str | None
    conversation_id: str | None
    message_id: str | None
    prompt: str | None
    response: str | None
    user_correction: str | None
    metadata: dict[str, str]

    @classmethod
    def from_dict(cls, value: Any) -> "ContributorEvent":
        if not isinstance(value, dict):
            raise SchemaError("each event must be an object")
        event_type = _required_string(value.get("type"), "event.type", 64)
        if event_type not in ALLOWED_EVENT_TYPES:
            raise SchemaError(f"unsupported event type: {event_type}")
        conversation_id = value.get("conversation_id", value.get("conversationID"))
        message_id = value.get("message_id", value.get("messageID"))
        return cls(
            id=_parse_uuid(value.get("id"), "event.id"),
            type=event_type,
            timestamp=_parse_timestamp(value.get("timestamp"), "event.timestamp"),
            channel=_required_string(value.get("channel"), "event.channel", 32),
            app_version=_required_string(value.get("app_version", value.get("appVersion")), "event.app_version", 128),
            model_version=_optional_text(value.get("model_version", value.get("modelVersion")), "event.model_version"),
            prompt_version=_optional_text(value.get("prompt_version", value.get("promptVersion")), "event.prompt_version"),
            conversation_id=_parse_uuid(conversation_id, "event.conversation_id") if conversation_id else None,
            message_id=_parse_uuid(message_id, "event.message_id") if message_id else None,
            prompt=_optional_text(value.get("prompt"), "event.prompt"),
            response=_optional_text(value.get("response"), "event.response"),
            user_correction=_optional_text(value.get("user_correction", value.get("userCorrection")), "event.user_correction"),
            metadata=_metadata(value.get("metadata")),
        )


@dataclass(frozen=True)
class ContributorBatch:
    schema_version: int
    batch_id: str
    installation_id: str
    sent_at: str
    consent_for_model_improvement: bool
    events: tuple[ContributorEvent, ...]

    @classmethod
    def from_dict(cls, value: Any) -> "ContributorBatch":
        if not isinstance(value, dict):
            raise SchemaError("batch body must be an object")
        if value.get("schema_version") != 1:
            raise SchemaError("unsupported schema_version")
        if value.get("consent_for_model_improvement") is not True:
            raise SchemaError("explicit model-improvement consent is required")
        events = value.get("events")
        if not isinstance(events, list) or not events:
            raise SchemaError("events must be a non-empty array")
        if len(events) > MAX_EVENTS_PER_BATCH:
            raise SchemaError(f"batch may contain at most {MAX_EVENTS_PER_BATCH} events")
        return cls(
            schema_version=1,
            batch_id=_parse_uuid(value.get("batch_id"), "batch_id"),
            installation_id=_parse_uuid(value.get("installation_id"), "installation_id"),
            sent_at=_parse_timestamp(value.get("sent_at"), "sent_at"),
            consent_for_model_improvement=True,
            events=tuple(ContributorEvent.from_dict(event) for event in events),
        )

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)
