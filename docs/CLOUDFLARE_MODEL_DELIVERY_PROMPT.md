# Prompt for Codex on the DGX: move Canopy model delivery to Cloudflare

You are working in the CanopyChat repository. Replace the long-running,
DGX-hosted model-delivery control plane with a Cloudflare-native control plane.
Keep the existing private Hugging Face repository private. The DGX is used only
to sync a released GGUF to R2; it must not be required when an iPhone downloads
or resumes a model.

## Objective

Implement this architecture:

```text
Private Hugging Face repo
  -> one-time DGX sync job (HF token only here)
  -> private Cloudflare R2 bucket
  -> Cloudflare Worker + D1 token/rate-limit registry
  -> short-lived signed R2 URL
  -> contributor iPhone downloads directly from R2
```

Do not make the Hugging Face repo or R2 bucket public. Do not proxy GGUF bytes
through a Worker, the DGX, Oracle, or Caddy.

## Existing iOS wire contract — do not break it

Base URL: `https://model-api.canopychat.app`

```http
POST /v1/tokens
Content-Type: application/json

{ "install_id": "random-uuid" }
```

Successful registration response:

```json
{ "token": "opaque-random-bearer-token", "install_id": "random-uuid" }
```

Manifest request:

```http
GET /v1/model-manifest
Authorization: Bearer <token>
```

Successful manifest response:

```json
{
  "version": "1.1.2",
  "filename": "canopy-1.1.2.Q4_K_M.gguf",
  "size_bytes": 1274818816,
  "sha256": "64-character-lowercase-sha256-hex",
  "download_url": "https://...short-lived-R2-presigned-GET-url...",
  "url_expires_at": "2026-07-21T00:00:00Z"
}
```

`GET /v1/model-manifest/refresh` must behave identically. The iOS app persists
the GGUF locally, verifies its SHA-256, uses it across app launches, refreshes
the manifest approximately every 12 hours, and asks for a new URL only when a
download is incomplete or a new model version is advertised. Do not assume an
app restart causes a model re-download.

The app uses HTTP Range requests against `download_url`, so signed R2 URLs must
permit Range GETs. A URL may expire after 15 minutes; the app obtains a fresh
manifest and resumes from its existing `.partial` file.

## Cloudflare implementation requirements

1. Create a Worker project under `model_delivery/worker/` using TypeScript and
   Wrangler. Bind it to `model-api.canopychat.app`.
2. Use a private R2 bucket. Store model files immutably at a versioned key such
   as `models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf`. Store current released
   metadata separately as `manifests/current.json`.
3. Keep or adapt the existing DGX Python sync job so it downloads the private HF
   GGUF once, calculates SHA-256, uploads to the immutable R2 key, and only then
   atomically updates `manifests/current.json`. The HF token must be used only by
   this sync job, never by the Worker or iOS app.
4. The Worker must generate a direct S3-compatible SigV4 presigned **GET** URL
   for that one R2 object. Store any R2 S3 signing credentials only as Worker
   secrets (`wrangler secret put`); do not commit them. Do not return bucket-list
   permissions, API credentials, or a permanent object URL to the app.
5. Use D1 for installation records and token hashes. Generate at least 256 bits
   of random token entropy; store only a SHA-256/HMAC hash of each token. Never
   log bearer values, signed URLs, raw Authorization headers, or HF/R2 secrets.
6. Add a migration and tests with Miniflare/Wrangler. Include tests for token
   registration, invalid/revoked token, manifest schema, signed-URL expiry,
   immutable version behavior, and rate limits.

## Rate limits for this beta

Do not impose a blunt "10 model downloads per app launch" limit. The iOS app
does not re-download on restart. Use these server-side limits instead:

| Action | Limit | Key |
| --- | --- | --- |
| Token registration | 5/hour | source IP, plus install ID where present |
| Manifest issuance | 10/24 hours per model version | installation token |
| Manifest issuance | 60/hour | source IP |
| Active signed URLs | 3/24 hours per model version | installation token |

Return `429` with `Retry-After` for a rate-limit response. The limits should
permit ordinary retries, a 15-minute signed-URL refresh during a slow download,
and a bad-network recovery, while blocking scripted mass issuance. Use
Cloudflare WAF/rate limiting at the edge for IP-level protection and D1/Worker
state for per-install limits. Explain any consistency tradeoff if you choose KV
instead of a Durable Object for counters.

## Scope and security posture

- Contributor beta only. Production must keep model-delivery endpoints blank
  unless explicitly enabled later.
- The present token-registration endpoint may remain low-friction for this small
  beta, but structure it so an invite credential and App Attest verification can
  be added later without changing the manifest response.
- Add a revocation flag for an install/token and an admin script or protected
  route to revoke it. Do not create public admin endpoints.
- Use TLS through Cloudflare, strict CORS only if needed (native iOS does not
  need browser CORS), security headers, and content-free structured logs.
- Update `docs/MODEL_DELIVERY_API.md` with Cloudflare deployment steps, Worker
  secret setup, D1 migration, R2 bucket setup, sync/release steps, rollback, and
  cost/rate-limit behavior.

## Acceptance criteria

- After the one-time DGX sync finishes, phones can receive manifests and
  download/resume models when the DGX is turned off.
- Model bytes never transit the DGX, Oracle, Worker, or Caddy during tester
  download.
- The R2 bucket is private and unlistable to users.
- A regular contributor can download a model once and restart the app offline
  without another GGUF download.
- Releasing `1.1.3` does not overwrite `1.1.2`; rollback is a metadata pointer
  change.
- No secrets or bearer/signed URLs appear in Git or logs.

Make incremental changes, run the Worker tests, and report the exact commands
the owner must run in the Cloudflare dashboard/CLI and on the DGX for initial
deployment.
