# Private model delivery contract

This document is the contract between the CanopyChat contributor iOS build and
the private model-delivery service. It is deliberately separate from contributor
telemetry: a model download must never expose a Hugging Face credential to the
app, and telemetry consent must not control whether a contributor can install the
model assigned to their build.

## Security boundary

- The Hugging Face read token lives only on the delivery service or DGX sync job.
  Never place it in the iOS app, Xcode build settings, Git, or a client-visible
  manifest.
- The service mirrors the approved private GGUF files to private object storage.
  The service returns short-lived HTTPS object URLs; it must not proxy multi-GB
  files through the API process.
- The current installation token is an opaque contributor-install credential, not
  an account identifier and not a Hugging Face token. It is stored in the iOS
  Keychain with `AfterFirstUnlockThisDeviceOnly` accessibility.
- This endpoint is compiled into the Contributor Beta configuration only. A
  production configuration leaves both endpoint settings blank and therefore
  continues using its production model path.

## iOS build settings

Set these **Contributor Beta** build settings after the service is deployed:

```text
AETHER_MODEL_REGISTRATION_ENDPOINT = https://models.canopychat.app/v1/contributor/installations
AETHER_MODEL_MANIFEST_ENDPOINT = https://models.canopychat.app/v1/model-manifest
```

Do not configure credentials in either setting. The iOS client treats an empty,
non-HTTPS, or missing pair as disabled.

## 1. Register an installation

```http
POST /v1/contributor/installations
Content-Type: application/json
```

```json
{
  "schema_version": 1,
  "installation_id": "random-keychain-persisted-uuid",
  "app_version": "1.1.1 (18)",
  "build_channel": "contributor"
}
```

Return a random opaque credential with at least 24 characters:

```json
{ "installation_token": "opaque-random-install-token" }
```

The service must be prepared to return the same or a rotated token when an
existing installation registers again. Future versions should bind enrollment to
TestFlight/invite validation and App Attest; do not use device identifiers.

## 2. Request the assigned immutable model manifest

```http
GET /v1/model-manifest
Authorization: Bearer <installation_token>
X-Canopy-Installation-ID: <random-installation-uuid>
X-Canopy-App-Version: 1.1.1 (18)
```

Successful response (`200`):

```json
{
  "schema_version": 1,
  "model": {
    "id": "canopy",
    "version": "1.1.2",
    "files": [
      {
        "role": "model",
        "filename": "canopy-1.1.2.Q4_K_M.gguf",
        "download_url": "https://private-bucket.example/...short-lived-signature...",
        "size_bytes": 1876543210,
        "sha256": "lowercase-64-character-sha256-hex",
        "expires_at": "2026-07-21T00:00:00Z"
      },
      {
        "role": "projector",
        "filename": "canopy-1.1.2.mmproj-Q8_0.gguf",
        "download_url": "https://private-bucket.example/...short-lived-signature...",
        "size_bytes": 123456789,
        "sha256": "lowercase-64-character-sha256-hex",
        "expires_at": "2026-07-21T00:00:00Z"
      }
    ]
  }
}
```

`role: "model"` is required. `role: "projector"` is optional only while the
current Canopy projector remains compatible; publish it whenever the model needs
a matching projector. Model IDs and versions must be immutable. Do not swap a
different file under the same `(id, version)`.

## Download behavior

- Every `download_url` must be HTTPS and refer to exactly the filename, byte
  count, and SHA-256 advertised in the manifest.
- Object storage must support `Range: bytes=<offset>-`. The iOS client writes a
  `.partial` file, resumes after app suspension, validates the exact byte count
  and SHA-256, then atomically activates the file.
- A download URL may expire. On `401` or `403`, the app obtains a fresh manifest
  and resumes the same immutable file. The fresh manifest must preserve the same
  model ID/version and file identity for an in-progress download.
- Return `401` or `403` from the manifest endpoint for invalid/revoked
  credentials. The app deletes its credential and registers once more.
- Signed URLs should be long enough for a normal Wi-Fi download, but expiration
  is safe because the client refreshes and resumes.

## Operational flow

```text
Private Hugging Face repository
  -> DGX/service sync (HF token stays here)
  -> private object storage
  -> authenticated manifest with signed URLs
  -> contributor iPhone verifies SHA-256 before activation
```

Log only request IDs, installation-token hashes, model IDs/versions, status
codes, and byte counts. Never log GGUF URLs with signatures, Hugging Face
credentials, request Authorization headers, prompts, or conversation data.
