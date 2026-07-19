# CanopyChat Contributor Pipeline

This is the isolated, opt-in data path for the **CanopyChat Contributor Beta**. It is deliberately separate from inference and from the public app. It accepts selected, consented text interactions, stores the original batch immutably, and turns it into reviewable training and evaluation candidates on the DGX.

It does **not** make the production app collect data. The iOS contributor integration is intentionally a separate step after the consent screen and privacy copy are approved.

## What it does

```text
Contributor beta app
  -> authenticated HTTPS batch upload
  -> immutable /raw batch on DGX
  -> schema validation + basic PII redaction + dedupe
  -> /bronze, /silver, /training, /eval, /quarantine JSONL datasets
```

The service only accepts an opaque installation identifier, selected prompt/response text, model/app metadata, and observable failure signals. It rejects batches without explicit model-improvement consent. Attachments, contact data, and device identifiers are not part of the protocol.

## Local development

Requires Python 3.11+ and has no third-party runtime dependencies.

```sh
cd contributor_pipeline
export PYTHONPATH=src
export CANOPY_CONTRIBUTOR_SHARED_SECRET="replace-with-a-random-32-byte-or-longer-secret"
export CANOPY_CONTRIBUTOR_ROOT="$PWD/.data"
python -m canopy_contributor.server
```

The service listens on `127.0.0.1:8791` by default. Use a TLS reverse proxy or tunnel in front of it for any device testing; do not expose the DGX service directly to the public internet.

```sh
PYTHONPATH=src python -m unittest discover -s tests -v
PYTHONPATH=src python -m canopy_contributor.process --root "$CANOPY_CONTRIBUTOR_ROOT"
```

## Upload contract

`POST /v1/contributor/batches` accepts either JSON or gzip-compressed JSON.

Required headers:

```text
Content-Type: application/json
X-Canopy-Timestamp: 2026-07-18T22:00:00Z
X-Canopy-Signature: sha256=<hex-hmac-of-timestamp-dot-raw-request-body>
```

The HMAC key is a beta-only secret provisioned outside the repository. The response contains a stable `receipt_id`; the client must delete a local batch only after receiving that receipt.

The body is versioned and idempotent:

```json
{
  "schema_version": 1,
  "batch_id": "0C1C7C0D-0041-4564-A9F4-53F75A0F1D46",
  "installation_id": "63A7F1EE-BAA6-4A24-84DF-7C0E90FBABAA",
  "sent_at": "2026-07-18T22:00:00Z",
  "consent_for_model_improvement": true,
  "events": []
}
```

See [`docs/IOS_INTEGRATION.md`](docs/IOS_INTEGRATION.md) for the app-side contract and signal rules.

## Data lifecycle

- `raw/` holds compressed, validated original batches. Treat it as restricted access.
- `bronze/` holds parsed, schema-valid interaction candidates.
- `silver/` holds redacted, deduplicated candidates.
- `training/` is **not** populated unless an interaction contains an explicit tester correction. Review these before any fine-tuning run.
- `eval/` holds deterministic control samples and frozen evaluation candidates.
- `quarantine/` holds malformed, orphaned, or potentially unsafe records for review.

The redactor is intentionally conservative and only catches common email, telephone, payment-card, and IP-address patterns. It is a guardrail, not a substitute for human review or a mature PII detector.

## Running it on the DGX

Use a dedicated Unix account and a restricted directory such as `/data/canopy-contributor`; set the root path through `CANOPY_CONTRIBUTOR_ROOT`. Put HTTPS authentication and rate limiting at the reverse proxy. Keep the service on a private network where possible.

This module is not a cloud deployment script. Domain, TLS, secret provisioning, retention period, and a deletion-request workflow must be decided before accepting real contributor data.
