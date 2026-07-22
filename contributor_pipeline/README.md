# Canopy Contributor Pipeline

This is the isolated, opt-in data path for the **Canopy Contributor Beta**. It accepts selected, consented text interactions, stores the original batch immutably, and turns it into reviewable training and evaluation candidates. It is separate from Canopy inference and from the public marketing website.

The service does not make the production app collect data. The iOS integration is a separate release-controlled step after consent and privacy copy are approved.

## Data flow

```text
Contributor Beta app
  -> Cloudflare Tunnel -> internal Caddy -> authenticated ingest
  -> raw -> bronze -> silver -> gold/{training,eval}
                         \-> quarantine
```

Only the Cloudflare Tunnel has an external route. The DGX has no inbound router port and the Compose stack publishes no host port.

## Local development

Requires Python 3.11+ and has no third-party runtime dependencies.

```sh
cd contributor_pipeline
export PYTHONPATH=src
export CANOPY_CONTRIBUTOR_SHARED_SECRET="replace-with-a-random-32-byte-or-longer-secret"
export CANOPY_CONTRIBUTOR_ROOT="$PWD/.data"
python3 -m canopy_contributor.server
```

The local service listens on `127.0.0.1:8791` by default. It requires authenticated HMAC uploads, validates the complete batch before storing it, rejects replayed timestamps outside five minutes, bounds both compressed and decompressed request bodies at 2 MB, and applies an in-process request rate limit.

```sh
PYTHONPATH=src python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m canopy_contributor.process --root "$CANOPY_CONTRIBUTOR_ROOT"
PYTHONPATH=src python3 -m canopy_contributor.cleanup --root "$CANOPY_CONTRIBUTOR_ROOT"
```

The curator uses a durable SQLite ledger and deterministic per-batch JSONL files. Running it repeatedly or after a container restart does not duplicate outputs.

## Storage layout

The production host root is `/data/canopy/contributor_pipeline`, configurable as `CANOPY_HOST_STORAGE_ROOT`. The persistent Docker volume is bind-backed beneath that host path. The service creates:

```text
raw/         immutable compressed accepted uploads
quarantine/  malformed or PII-suspect records awaiting review
bronze/      schema-valid interaction records
silver/      redacted and deduplicated records
gold/        training candidates and frozen evaluation sets
processed/   SQLite ledger, receipts, and cross-process locks
deleted/      content-free deletion tombstones
logs/        content-free retention audit records
backups/     reserved for encrypted operator backups
```

Raw, bronze, silver, and quarantine records are never direct training inputs. Gold candidates require human review before export.

## Upload contract

`POST /v1/contributor/batches` accepts JSON or gzip-compressed JSON.

Required headers:

```text
Content-Type: application/json
X-Canopy-Timestamp: 2026-07-18T22:00:00Z
X-Canopy-Signature: sha256=<hex-hmac-of-timestamp-dot-raw-request-body>
```

The HMAC key is beta-only secret material provisioned outside the repository. The response contains a stable `receipt_id`; the client deletes its local batch only after receiving that receipt. Reusing a `batch_id` with different content returns a conflict.

The body remains versioned and compatible with the existing iOS contract:

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

See [`docs/IOS_INTEGRATION.md`](docs/IOS_INTEGRATION.md) for the app-side signal rules.

## Container deployment

Copy `.env.example` to an untracked local `.env`, replace the placeholders, and follow [`docs/OPERATIONS.md`](docs/OPERATIONS.md). Do not create or commit a populated `.env` in this repository.

The default startup is:

```sh
docker compose --env-file .env up --build -d
```

To include the outbound-only Cloudflare Tunnel connector:

```sh
docker compose --env-file .env --profile tunnel up --build -d
```

The public hostname is configured as `CANOPY_CONTRIBUTOR_DOMAIN=contributor-api.canopychat.app`. Replace the domain only in the local `.env` and in the Cloudflare Tunnel public-hostname configuration; do not alter the Canopy marketing site route.

The stock Caddy image is used only as an internal HTTP reverse proxy for request limits, security headers, and upstream health checks. Application-level rate limiting remains enabled because stock Caddy has no rate-limit module. Add a Cloudflare WAF/rate-limit rule before accepting public beta traffic.

The public iOS endpoint is `https://model-api.canopychat.app/v1/contributor/batches`. The Worker validates the existing installation bearer token, applies D1-backed install/IP limits, signs the exact request bytes with its private `CONTRIBUTOR_INGEST_HMAC_SECRET`, and forwards them to the tunnel. The DGX tunnel endpoint rejects unsigned direct requests. The iOS client needs no HMAC header and must never receive the DGX secret.

## Deletion and retention

Use the authenticated local deletion CLI, never a public HTTP endpoint:

```sh
PYTHONPATH=src python3 -m canopy_contributor.deletion \
  --root /data/canopy/contributor_pipeline \
  --installation-id INSTALLATION-UUID
```

The default retention policy is raw 30 days after successful processing, quarantine 7 days unless marked approved, and bronze/silver 90 days. Gold training candidates and frozen evaluations are retained until explicitly deleted. Change the four periods with the `CANOPY_*_RETENTION_DAYS` environment variables and run the cleanup command or let the scheduled retention service run.

Regex redaction catches only common email, telephone, payment-card, and IP-address patterns. It is an automated guardrail, not a sufficient PII detector; candidates with replacements are quarantined by default for human review.
