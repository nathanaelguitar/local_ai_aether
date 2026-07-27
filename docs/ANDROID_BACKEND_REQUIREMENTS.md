# Android backend requirements

What the server side must provide for the Android port to be fully functional, and
what still needs an x86_64 build host. The Android client code for all of this is
already written; this doc is the contract it expects.

## 1. Private model delivery (production path)

Android now mirrors iOS's delivery flow exactly (`core/ModelDelivery.kt` +
`ModelStore` in `inference/InferenceEngine.kt`). The app talks to the same Worker
iOS uses (`docs/MODEL_DELIVERY_API.md`); defaults are compiled in via
`BuildConfig`, overridable in `gradle.properties`:

| Gradle property | BuildConfig field | Production default |
|---|---|---|
| `CANOPY_MODEL_MANIFEST_ENDPOINT` | `MODEL_MANIFEST_ENDPOINT` | `https://model-api.canopychat.app/v1/model-manifest` |
| `CANOPY_MODEL_REGISTRATION_ENDPOINT` | `MODEL_REGISTRATION_ENDPOINT` | `https://model-api.canopychat.app/v1/tokens` |
| `CANOPY_BETA_TELEMETRY_ENDPOINT` | `BETA_TELEMETRY_ENDPOINT` | `https://model-api.canopychat.app/v1/contributor/batches` |
| `CANOPY_BUILD_CHANNEL` | `BUILD_CHANNEL` | `production` (`contributor`/`beta` enables telemetry) |

Backend requirements (unchanged from iOS):

- `POST /v1/tokens` accepts `{"install_id": "<uuid>"}` and returns
  `{"installation_token": "…"}` (any field name of `installation_token`/`token` works;
  tokens < 24 chars are rejected client-side).
- `GET /v1/model-manifest` with `Authorization: Bearer <token>`,
  `X-Canopy-Installation-ID`, `X-Canopy-App-Version` returns the **flat** manifest
  (`version`, `filename`, `download_url`, `size_bytes`, `sha256`, `url_expires_at`)
  or the nested schema — the client normalizes both. Signed URLs must support
  **HTTP Range** requests (the downloader resumes from `.partial` files) and point
  at `https` hosts that are not the `undefined.r2.cloudflarestorage.com` misconfig
  (the client validates this).
- 401/403 from the manifest endpoint causes the client to drop its token,
  re-register, and retry once — the Worker should treat a re-registration as the
  same logical install (idempotent on `install_id`).
- Optional `role: "projector"` entry: when the manifest serves a private mmproj,
  Android downloads and verifies it too; otherwise it falls back to the public
  projector URL (same as iOS).

## 2. Contributor telemetry batches

`core/BetaTelemetry.kt` posts to `/v1/contributor/batches` with the same wire format
as iOS:

```json
{
  "schema_version": 1,
  "batch_id": "<uuid>",
  "installation_id": "<uuid>",
  "sent_at": "<ISO-8601>",
  "consent_for_model_improvement": true,
  "events": [ { "id", "type", "timestamp", "channel", "appVersion",
                "modelVersion", "conversationID", "messageID",
                "prompt", "response", "metadata" } ]
}
```

- Auth: same installation bearer token; on 401/403 the client refreshes the token
  and retries **once with the same `batch_id`** — the Worker must be idempotent on
  `batch_id`.
- The client only deletes a queued batch after receiving
  `{"receipt_id": "…", "batch_id": "<same uuid>"}` with a 2xx status. Any other
  outcome triggers exponential backoff (2^n s, capped at 300 s, 8 attempts).
- Production builds never send anything (`BUILD_CHANNEL != contributor` gates every
  code path); the endpoint only needs to handle contributor installs.

## 3. Native library rebuild (x86_64 host needed once)

This machine is aarch64 and Google ships no linux-arm64 NDK, so the APK built here
uses `-Pcanopy.skipNative` and **does not contain `libcanopy_llama.so`** — on-device
inference reports itself unavailable and the UI offers the Backend provider (the
same graceful path iOS uses for an unloaded runtime).

To ship on-device inference, rebuild on any x86_64 host with the NDK:

```bash
cd android && ./gradlew :app:assembleRelease   # NDK 26.3.11579264 as declared
```

The round-2 change that the .so picks up: `canopy_llama.cpp` now takes a
`TokenCallback` jobject and calls `onToken(String)` per decoded piece (UTF-8-safe —
pieces are only emitted on valid-sequence boundaries, and the callback runs on the
JNI thread that owns the call). Without rebuilding, the Kotlin side still compiles
because the callback parameter is nullable and `LlamaCppRuntime.isAvailable` stays
false.

## 4. Backend inference endpoint (unchanged)

`BackendInferenceEngine` posts OpenAI-style chat completions to the user-configured
endpoint (`apiEndpoint`, default `http://10.0.2.2:8787` for emulators). No changes
from round 1; this path does not stream (`"stream": false`), so the streaming
preview appears only for on-device generation — same as iOS.
