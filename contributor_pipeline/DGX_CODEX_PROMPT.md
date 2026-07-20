# Prompt for Codex on the DGX Spark

You are working on the CanopyChat Contributor Beta data-ingestion service. The iOS app sends explicit-consent, text-only contributor batches to `POST /v1/contributor/batches`. The repository already contains a Python standard-library implementation in `contributor_pipeline/`.

Your objective is to make this service safe and operational on this DGX Spark without changing the iOS wire contract.

## Non-negotiable product policy

- This service is for the **Contributor Beta only**. Production CanopyChat conversations must never be ingested.
- The app sends selected failure interactions, corrections, regenerations, inference/tool/schema failures, and a small success-control sample.
- Do not add attachment bytes, images, complete chat exports, contacts, location, advertising identifiers, or account identity collection.
- Raw batches are sensitive. Do not log request bodies, prompts, model outputs, secrets, or HMAC signatures.
- Never feed raw uploads directly into training. Preserve the raw layer, then validate, redact, deduplicate, quarantine, and require review before training export.

## Existing protocol — do not break it

Endpoint:

```text
POST /v1/contributor/batches
Content-Type: application/json
X-Canopy-Timestamp: ISO-8601 UTC
X-Canopy-Signature: sha256=<HMAC-SHA256(secret, timestamp + "." + exact-request-body)>
```

JSON envelope:

```json
{
  "schema_version": 1,
  "batch_id": "UUID",
  "installation_id": "random opaque UUID",
  "sent_at": "2026-07-18T22:00:00Z",
  "consent_for_model_improvement": true,
  "events": []
}
```

Responses must preserve the current receipt shape:

```json
{
  "receipt_id": "rcpt_<batch UUID>",
  "batch_id": "UUID",
  "accepted_events": 3
}
```

The service currently accepts JSON and gzip JSON, validates schema version 1, has idempotent batch IDs, and stores JSONL datasets under `raw`, `bronze`, `silver`, `gold`, and `quarantine`, with receipts and SQLite under `processed`.

## Required work

1. Inspect and test the existing `contributor_pipeline` module before editing it.
2. Containerize it with a small, reproducible Docker image:
   - Python 3.11+ slim base
   - run as a non-root user
   - no secrets baked into images or committed
   - persistent bind-backed named volume at `/data/canopy/contributor_pipeline`
   - health check for `GET /health`
3. Add a `docker-compose.yml` suitable for a single DGX Spark:
   - no host port publishing; access is through an outbound Cloudflare Tunnel and internal Caddy
   - a separate scheduled curator service/job that runs `python -m canopy_contributor.process` every 5–15 minutes
   - a scheduled retention service with configurable raw/quarantine/bronze/silver periods
   - `.env.example`, never `.env`
   - resource/log rotation limits where practical
4. Add an internal Caddy reverse proxy behind a Cloudflare Tunnel for `contributor-api.canopychat.app`:
   - HTTPS only externally at Cloudflare
   - request body limit no larger than 2 MB
   - rate limiting if the available Caddy setup supports it; otherwise document the limitation and use an upstream firewall/tunnel
   - do not expose the DGX directly on the public internet without the proxy/tunnel
5. Improve the server only where necessary:
   - retain constant-time HMAC verification and 5-minute replay protection
   - add request-size enforcement before reading the whole body
   - add atomic cross-process-safe idempotency (file locks or SQLite are acceptable)
   - store immutable raw files with restrictive permissions
   - persist a processing ledger so the curator is idempotent and does not append duplicate rows on every run
   - add a conservative PII redaction report and quarantine records that exceed a configurable redaction threshold
   - do not invent a claim that regex-only redaction is sufficient
6. Add an authenticated admin-only deletion tool, not a public endpoint:
   - delete data by opaque `installation_id` or a batch/receipt ID
   - record an auditable deletion tombstone without retaining prompt/response text
   - remove derived records as well as raw records where feasible
7. Add operational documentation:
   - first startup on the DGX
   - generating and rotating `CANOPY_CONTRIBUTOR_SHARED_SECRET`
   - volume backup/restore
   - retention settings and secure deletion
   - how to use Caddy or a tunnel
   - how to run tests and inspect quarantine/gold candidates
8. Add tests for Docker-independent code, idempotency across restarts, replay rejection, gzip bodies, PII/quarantine behavior, processing-ledger idempotency, and deletion tooling.

## Credentials and enrollment

The initial local integration may use `CANOPY_CONTRIBUTOR_SHARED_SECRET`, but **do not recommend shipping one universal secret in an external TestFlight app**. Design the service so it can later validate per-contributor, short-lived upload tokens. Document the proposed token-enrollment interface without changing the existing iOS protocol yet.

## Acceptance criteria

- `docker compose up --build` brings up the internal ingest endpoint, curator, retention service, and Caddy; the optional tunnel connector provides the only external route.
- A signed test batch produces exactly one raw batch and stable receipt across retries.
- The same batch is not duplicated by repeated curator runs.
- Failure-with-correction candidates reach `gold/training/`; random controls reach `gold/eval/`; unsafe/malformed records reach `quarantine/`.
- No prompts, responses, secrets, or HMAC signatures appear in logs.
- The app-facing endpoint contract above remains compatible.

Make changes incrementally, run tests, and report the exact files changed plus any needed DGX-specific values (domain, tunnel choice, and storage path) that require the owner’s decision.
