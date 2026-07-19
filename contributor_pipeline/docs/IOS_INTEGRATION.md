# iOS contributor integration contract

This document is intentionally implementation-oriented. It does not authorize a production build to collect data; only the `Contributor Beta` configuration may use it.

## Consent and installation identity

1. Show a dedicated contributor disclosure before enabling collection.
2. Default consent to **off**. Do not generate or upload any batch until the tester explicitly enables it.
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
| Correction | `userCorrection` | `user_correction` text and message ID |
| Empty/truncated answer | `responseEmpty` / `responseTruncated` | message ID and diagnostic metadata |
| Inference/tool/schema failure | `inferenceFailed` / `toolFailed` / `outputValidationFailed` | message ID and non-sensitive diagnostics |

Do not put attachment bytes, image OCR, file contents, address-book data, exact location, email addresses, or raw hardware identifiers in `metadata`.

The local candidate scorer queues 100% of explicit failures and corrections plus a deterministic 2% sample of `responseGenerated` interactions without a failure signal. It should batch at 50 retained events or 24 hours, whichever comes first.

## Wire format

Use the envelope defined in the module README. The body may use camelCase event keys for compatibility with the current `AetherTelemetryEvent`; the service normalizes them.

For each upload:

1. Serialize a body whose `schema_version` is `1` and `consent_for_model_improvement` is `true`.
2. Optionally gzip it; sign the exact bytes that are sent.
3. Set `X-Canopy-Timestamp` to UTC ISO-8601 time.
4. Set `X-Canopy-Signature` to `sha256=` plus `HMAC-SHA256(secret, timestamp + "." + body)`.
5. Preserve the local batch until a 200 response returns the matching `batch_id` and `receipt_id`.
6. Retry with exponential backoff and jitter. Treat 401/409/422 as permanent failures requiring a local diagnostic, not endless retries.

The shared secret must not be a universal secret embedded in a public release. Before external distribution, replace it with per-contributor short-lived upload credentials minted by an authenticated enrollment service.
