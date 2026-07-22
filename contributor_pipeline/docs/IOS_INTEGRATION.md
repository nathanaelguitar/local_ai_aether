# iOS contributor integration contract

This document is intentionally implementation-oriented. It does not authorize a production build to collect data; only the `Contributor Beta` configuration may use it.

## Consent and installation identity

1. Show a dedicated contributor disclosure before the tester enters the Contributor Beta.
2. The Contributor Beta may enable collection by default only after that disclosure is acknowledged. Production builds must remain collection-disabled. A tester can stop collection at any time in Settings; withdrawal deletes unsent batches immediately.
3. Generate one random UUID with Keychain persistence as `installation_id`. Never use IDFV, advertising ID, email, Apple Account, phone number, or hardware identifier.
4. On withdrawal, immediately stop candidate creation and delete all pending local batches. The server-side deletion workflow is a separate, authenticated support endpoint and must be implemented before accepting production-like beta data.

## Candidate policy

Collect only these text events after consent:

| Signal | Event type | Required payload |
| --- | --- | --- |
| Generated answer | `responseGenerated` | prompt, response, model/prompt/app versions, latency metadata |
| Thumbs down | `responseRated` | `metadata.rating = negative` |
| Thumbs up | `responseRated` | `metadata.rating = positive` |
| Regenerate | `responseRegenerated` | message ID |
| Explicit web-search request | `webSearchRequested` | prompt, derived query, whether search was enabled, request source, and outcome |
| Web search attempt | `webSearchPerformed` | prompt, derived query, result outcome, and source count |
| Correction | `userCorrection` | `user_correction` text and message ID |
| Empty/truncated answer | `responseEmpty` / `responseTruncated` | message ID and diagnostic metadata |
| Inference/tool/schema failure | `inferenceFailed` / `toolFailed` / `outputValidationFailed` | message ID and non-sensitive diagnostics |

Do not put attachment bytes, image OCR, file contents, address-book data, exact location, email addresses, or raw hardware identifiers in `metadata`.

The local candidate scorer queues 100% of explicit failures and corrections plus a deterministic 2% sample of `responseGenerated` interactions without a failure signal. It should batch at 50 retained events or 24 hours, whichever comes first.

## Wire format

Use the envelope defined in the module README. The body may use camelCase event keys for compatibility with the current `AetherTelemetryEvent`; the service normalizes them.

For each iOS upload:

1. Serialize a body whose `schema_version` is `1` and `consent_for_model_improvement` is `true`.
2. `POST` the exact body to `https://model-api.canopychat.app/v1/contributor/batches` with `Content-Type: application/json` and `Authorization: Bearer <per-install token>`.
3. Obtain that opaque per-install token from the existing private-model registration service and keep it in the Keychain. Never put a DGX shared secret, Hugging Face token, or Cloudflare API credential in the app.
4. Preserve the local batch until a 2xx response returns the matching `batch_id` and `receipt_id`.
5. Retry transient failures with exponential backoff and jitter. On 401/403, refresh the per-install token once, then retry the immutable batch.

The Cloudflare Worker authenticates the app token, applies rate limits, and adds the timestamp/HMAC headers expected by the DGX ingestion service. The Worker-to-DGX HMAC secret exists only in Worker and DGX secrets; it is never distributed in TestFlight.
