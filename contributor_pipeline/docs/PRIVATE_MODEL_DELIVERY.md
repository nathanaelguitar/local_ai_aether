# Private model delivery: iOS handoff

The authoritative service contract and deployment runbook are in
[`docs/MODEL_DELIVERY_API.md`](../../docs/MODEL_DELIVERY_API.md). This document
records the iOS-side guarantees and the exact build settings needed to enable it.

## Security boundary

- The Hugging Face read token stays only in the DGX sync environment. It must
  never be placed in iOS source, `Info.plist`, Xcode Cloud, Git, telemetry, or a
  client-visible manifest.
- GGUF bytes are mirrored from the private Hugging Face repository into private
  R2 storage. The delivery API returns only a short-lived R2 signed URL.
- The contributor app stores an opaque per-install bearer token in the Keychain.
  It is not a Hugging Face credential and is never sent to R2.
- The app writes a resumable `.partial` download, checks the exact byte count and
  SHA-256 from the manifest, and only then atomically activates the model.

## Contributor Beta build settings

Set these only for the `AetherChatBeta` / Contributor Beta configuration after
the Cloudflare Tunnel is live:

```text
AETHER_MODEL_REGISTRATION_ENDPOINT = https://model-api.canopychat.app/v1/tokens
AETHER_MODEL_MANIFEST_ENDPOINT = https://model-api.canopychat.app/v1/model-manifest
```

The endpoints are not secrets. Leave both settings blank in the production
configuration. An absent, non-HTTPS, or incomplete pair disables private model
delivery rather than falling back to an unverified private-model download.

## Wire compatibility implemented in iOS

Registration uses:

```json
POST /v1/tokens
{ "install_id": "random-keychain-persisted-uuid" }
```

The app accepts the service response `{ "token": "..." }` and stores that
opaque token in Keychain. A manifest call uses `Authorization: Bearer <token>`.

The deployed service's flat response:

```json
{
  "version": "1.1.2",
  "filename": "canopy-1.1.2.Q4_K_M.gguf",
  "size_bytes": 1274818816,
  "sha256": "64-character-lowercase-hex",
  "download_url": "https://...signed-r2-url...",
  "url_expires_at": "2026-07-20T13:15:00Z"
}
```

is normalized internally into a versioned manifest. The client also accepts the
earlier nested form to keep the app transport implementation forwards-compatible.
On an expired signed URL (`401`/`403`), it requests a fresh manifest and resumes
with `Range: bytes=<partial-size>-`.

## Remaining enrollment hardening

An endpoint that issues a token to any caller is a delivery mechanism, not a
strong authorization boundary: a determined person with the beta binary can
register an installation. Before broad external distribution, bind
`POST /v1/tokens` to a real contributor enrollment check (for example a
server-issued invite credential plus App Attest), add token revocation/rate
limits, and avoid logging bearer values. This does not require exposing any
Hugging Face credential to the app.
