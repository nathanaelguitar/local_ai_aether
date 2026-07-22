# Prompt: Finish CanopyChat Contributor Telemetry on the DGX + Cloudflare

You are working in the `local_ai_aether` repository on the DGX Spark. Your task is to finish the **Contributor Beta telemetry path** so selected, disclosed model-improvement interactions from the iOS Contributor build safely reach the DGX pipeline.

Do not modify the public production app into a telemetry build. Production must continue to send no conversation or model-improvement data.

## Current facts

- The iOS app has a Contributor build channel and already records selected candidate events locally: thumbs-down feedback, corrections, regeneration/resend, tool/inference/validation failures, truncated/empty outputs, web-search signals, and a 2% deterministic successful-response control sample.
- The existing DGX pipeline is in `contributor_pipeline/`.
  - Ingest path: `POST /v1/contributor/batches`
  - Health paths: `/health`, `/ready`
  - It validates JSON or gzip payloads, stores immutable raw batches, returns idempotent receipts, and runs raw -> bronze -> silver -> gold/eval processing.
  - It expects these headers today:

    ```text
    Content-Type: application/json
    X-Canopy-Timestamp: ISO-8601 UTC
    X-Canopy-Signature: sha256=<HMAC-SHA256(timestamp + "." + raw_body)>
    ```

  - The shared HMAC value must remain a server-side secret.
- The existing Cloudflare model Worker is in `model_delivery/worker/`, live at `https://model-api.canopychat.app`.
  - `POST /v1/tokens` already registers a random iOS install UUID and returns an opaque bearer token.
  - The Worker stores only token hashes in D1 and already validates bearer tokens for model manifests.
- The intended DGX hostname is `contributor-api.canopychat.app`, but it is currently not publicly resolvable.
- Do **not** put a shared HMAC secret in an iOS plist, Xcode build setting, TestFlight build, or repository. A TestFlight app can be reverse-engineered; a shared client secret would let an attacker inject fake fine-tuning data.

## Required architecture

```text
Contributor iPhone
  POST https://model-api.canopychat.app/v1/contributor/batches
  Authorization: Bearer <existing per-install token>
        |
        | Cloudflare Worker validates D1 token + applies rate limits
        | Worker adds a fresh timestamp and HMAC using a Worker secret
        v
Cloudflare Tunnel -> contributor-api.canopychat.app
        v
DGX contributor_pipeline /v1/contributor/batches
        v
raw -> bronze -> silver -> gold/{training,eval}
```

The iPhone must only know its opaque per-install bearer token. The DGX HMAC secret must exist only on the DGX container environment and in the Cloudflare Worker secret store.

## Implement

### 1. Deploy the DGX ingest stack

1. In `contributor_pipeline/`, create an untracked `.env` from `.env.example`.
2. Generate a fresh independent 32-byte-or-longer secret for `CANOPY_CONTRIBUTOR_SHARED_SECRET`.
3. Use persistent DGX storage at `/data/canopy/contributor_pipeline`.
4. Start the full stack, including the outbound Cloudflare Tunnel:

   ```bash
   docker compose --env-file .env --profile tunnel up --build -d
   ```

5. Configure the Cloudflare Tunnel public hostname:

   ```text
   contributor-api.canopychat.app
   -> http://caddy:8080
   ```

   Do not expose an inbound DGX router port. Only `cloudflared` is externally connected.

6. Verify from outside the DGX that:

   ```bash
   curl --fail-with-body https://contributor-api.canopychat.app/ready
   ```

   returns `200` and the readiness JSON. A direct unauthenticated POST to `/v1/contributor/batches` must return `401`.

### 2. Extend the existing Cloudflare model Worker

In `model_delivery/worker/`, add:

```text
POST /v1/contributor/batches
Authorization: Bearer <install token>
Content-Type: application/json
Body: existing ContributorBatch schema (the exact raw bytes are significant)
```

Required behavior:

1. Require a valid, non-revoked existing installation token from D1. Do not create a second client identity or accept anonymous uploads.
2. Enforce request size before forwarding: maximum 2 MB compressed/raw request body, matching the DGX service.
3. Accept only `application/json` and, if supporting it, gzip exactly as the DGX contract does. Preserve the received body bytes exactly when forwarding.
4. Apply meaningful beta abuse protection:
   - at least 30 upload attempts per install per 24 hours;
   - a separate source-IP limit appropriate for a small beta (for example 60/hour);
   - return `429` with `Retry-After`;
   - permit normal retry/backoff and a 24-hour batching cadence.
5. Never log prompt text, response text, bearer tokens, raw Authorization values, HMAC values, or full request bodies. Logs may contain only content-free request IDs, installation-token hashes/opaque short hashes, status, and timing.
6. Add a Worker secret named `CONTRIBUTOR_INGEST_HMAC_SECRET`. Its value must exactly equal `CANOPY_CONTRIBUTOR_SHARED_SECRET` on the DGX. Store it with `wrangler secret put`; do not put it in `wrangler.toml`, source, test fixtures, or git.
7. Add a Worker secret named `CONTRIBUTOR_INGEST_ORIGIN`, with the full HTTPS ingest URL:

   ```text
   https://contributor-api.canopychat.app/v1/contributor/batches
   ```

8. On each accepted iPhone request, the Worker must:
   - generate a new UTC ISO-8601 timestamp;
   - compute `HMAC-SHA256(CONTRIBUTOR_INGEST_HMAC_SECRET, timestamp + "." + exact_raw_body)`;
   - `fetch` the configured DGX origin with `Content-Type`, `X-Canopy-Timestamp`, and `X-Canopy-Signature` headers;
   - return the DGX response status/body to the iPhone unchanged enough for the iOS client to decode `{ receipt_id, batch_id, accepted_events }`;
   - map origin/network failure to a content-free `502` or `503` response.
9. Do not make the DGX HMAC endpoint publicly writable without this Worker. It is okay for the tunnel hostname to be reachable, but it must reject any request without the valid Worker-generated HMAC.
10. Add a minimal health route only if useful, such as `GET /v1/contributor/health`, which must not disclose secrets or stored data.

### 3. Worker implementation details

- Reuse the Worker’s existing token-validation and D1 mechanisms. Do not weaken model-delivery authorization.
- Add a D1 migration/table(s) for telemetry rate counters if needed; keep counters keyed by token hash / UTC day rather than raw token.
- Use Web Crypto (`crypto.subtle`) for HMAC signing; no third-party dependency is needed.
- Add unit tests for:
  - no/malformed/revoked bearer token -> `401` or `403`;
  - valid token -> origin sees a valid HMAC and body bytes unchanged;
  - upstream receipt is relayed;
  - rate limiting -> `429` + `Retry-After`;
  - origin failure -> `502`/`503`;
  - no secret or request content appears in logged error data.

### 4. Prove end-to-end delivery with synthetic data

After deployment, use a synthetic test record only. Do not use a real user prompt.

1. Register one disposable install ID through the existing Worker token endpoint.
2. Send one valid schema-version-1 contributor batch through the new Worker route using that bearer token.
3. Verify:
   - iPhone-facing response is `200` with matching `batch_id` and non-empty `receipt_id`;
   - exactly one gzip raw file is created on the DGX;
   - repeating the identical batch returns the same receipt (idempotency);
   - the curator produces the expected bronze/silver and control/eval/training output for the synthetic event;
   - a direct unsigned request to the tunnel remains rejected;
   - content-free logs do not contain the synthetic prompt/response.
4. Delete the synthetic batch using the pipeline’s authenticated local deletion command, then verify derived artifacts and raw data are removed while its content-free tombstone remains.

### 5. Report back with exact handoff values

Do not reveal secrets. Report only:

- the live public Worker telemetry URL (expected: `https://model-api.canopychat.app/v1/contributor/batches`);
- confirmation that `https://contributor-api.canopychat.app/ready` is reachable;
- the deployed git commit(s);
- test commands and sanitized results;
- any changes to the request or receipt JSON schema;
- whether the iOS client needs any header beyond `Authorization: Bearer <token>` and `Content-Type: application/json`.

## Definition of done

The work is done only when all of these are true:

- the DGX container stack is running with persistent storage and a healthy tunnel;
- the public contributor hostname resolves and reports ready;
- a valid token can send a batch through the Worker and receive a receipt;
- the DGX raw/bronze/silver pipeline receives and processes it;
- an invalid token/direct unsigned attempt is rejected;
- no reusable ingestion secret exists in the iOS build;
- tests pass;
- all changes are committed and pushed to `main`.

Stop and report the exact blocker rather than weakening authentication or placing secrets in the app.
