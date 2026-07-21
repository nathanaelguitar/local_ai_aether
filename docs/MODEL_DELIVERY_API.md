# Canopy Model Delivery API

Base URL: `https://model-api.canopychat.app`

All request and response bodies are `application/json`. No CORS headers are
emitted — native iOS does not need them.

---

## Architecture

```
Private HF repo (nathanaelguitar/canopy-1.1.2)
  │
  └─ one-time DGX sync job (only consumer of HF token)
       │ writes versioned GGUF + manifests/current.json
       ▼
  Private Cloudflare R2 bucket (never public, never listable)
       │
       └─ Cloudflare Worker (model-api.canopychat.app)
            │ reads manifests/current.json via R2 binding
            │ generates short-lived SigV4 presigned GET URL
            │ validates install tokens via D1
            ▼
       iOS app downloads GGUF directly from R2 (signed URL)
       — DGX, Worker, and Caddy never proxy model bytes —
```

After the initial sync, phones can download and resume even if the DGX is off.

---

## Security

- **HF token**: DGX only, in `CANOPY_DELIVERY_HF_TOKEN` env var. Never in
  Worker secrets, iOS, Git, or logs.
- **R2 S3 credentials**: Worker secrets only (`wrangler secret put`). Read-only
  on the specific bucket; never returned to clients.
- **Bearer tokens**: 256 bits random; only the SHA-256 hash is stored in D1.
  Raw values are never logged.
- **Signed URLs**: Not logged. Expire after 15 minutes.
- **Admin secret**: Worker secret (`ADMIN_SECRET`). Never committed.

---

## Auth flow

### First launch

```
iOS                               model-api.canopychat.app (CF Worker)
 │                                          │
 │── POST /v1/tokens ──────────────────────▶│
 │   {"install_id": "<uuid>"}               │ ── INSERT token_hash into D1
 │                                          │
 │◀─ 201 {"token":"<64-hex>","install_id":…}│
 │                                          │
 │  [store token in iOS Keychain]           │
```

### Every manifest call

```
iOS                     CF Worker                  Cloudflare R2
 │                         │                            │
 │─ GET /v1/model-manifest ▶│                            │
 │  Authorization: Bearer …  │── GET manifests/current.json ──▶│
 │                         │◀── {version, key, sha256, …} ──── │
 │                         │── generate SigV4 presigned URL ───▶│ (signing only)
 │◀─ 200 manifest JSON ──── │                            │
 │  (includes download_url)  │                            │
 │                         │                            │
 │── GET <download_url> ───────────────────────────────▶│
 │◀── 200/206 GGUF bytes (direct from R2, Worker not involved) ─│
```

### URL refresh (stalled or resumed download)

Re-call `GET /v1/model-manifest` (or `/refresh`). Get a fresh presigned URL.
Resume with `Range: bytes=<already_received>-` against the new URL.

---

## Endpoints

### `GET /health` · `GET /ready`

No auth. Returns 503 if `manifests/current.json` is not present in R2.

**200**
```json
{"status": "ok", "version": "1.1.2"}
```

**503**
```json
{"status": "initializing", "detail": "Run the DGX sync job first."}
```

---

### `POST /v1/tokens`

Register a new per-install token.

**Request**
```json
{"install_id": "550e8400-e29b-41d4-a716-446655440000"}
```

`install_id` is optional — the Worker generates one if omitted.

**201 Response**
```json
{
  "token": "a3f1c2...64hex...d9e",
  "installation_token": "a3f1c2...64hex...d9e",
  "install_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

Both `token` and `installation_token` carry the same value for backward and
forward compatibility with different iOS decoder versions. Store in Keychain.

**Rate limit**: 5 registrations/hour per source IP. Returns `429` with
`Retry-After: 3600` when exceeded.

---

### `GET /v1/model-manifest`
### `GET /v1/model-manifest/refresh`

Both paths behave identically.

**Request headers**
```
Authorization: Bearer <64-hex-token>
X-Canopy-Installation-ID: <install-uuid>   (informational, for structured logs)
X-Canopy-App-Version: 1.2.3 (4)           (informational)
```

**200 Response**
```json
{
  "version": "1.1.2",
  "filename": "canopy-1.1.2.Q4_K_M.gguf",
  "size_bytes": 1274818816,
  "sha256": "a3f1c2...64-char-lowercase-hex...",
  "download_url": "https://<account>.r2.cloudflarestorage.com/...?X-Amz-Expires=900&...",
  "url_expires_at": "2026-07-21T13:15:00Z"
}
```

| Field | Description |
|-------|-------------|
| `version` | Semantic model version. If changed from cached, re-download. |
| `filename` | Canonical filename. Verify after download. |
| `size_bytes` | Total size in bytes. |
| `sha256` | Lowercase hex SHA-256 of the full GGUF. Verify after download. |
| `download_url` | Presigned R2 GET URL. Valid for 15 min. Supports `Range`. |
| `url_expires_at` | ISO 8601 UTC expiry of `download_url`. |

**Rate limit**: 24 manifest issuances per install per model version per 24 h.

**Error responses**

| Status | Body | Meaning |
|--------|------|---------|
| 401 | — | Missing or malformed Authorization header. |
| 403 | `{"error":"invalid_token"}` | Token not registered or revoked. |
| 429 | `{"error":"rate_limited"}` + `Retry-After` | Rate limit exceeded. |
| 503 | `{"error":"service_unavailable"}` | DGX sync not run yet. |

---

### `POST /admin/tokens/revoke`

Revoke all tokens for an install. Requires `Authorization: Bearer <ADMIN_SECRET>`.

**Request**
```json
{"install_id": "550e8400-e29b-41d4-a716-446655440000"}
```

**200**
```json
{"revoked": 1}
```

---

## iOS integration notes

1. Generate a UUID on first launch. Call `POST /v1/tokens`. Store `token` in
   iOS Keychain under `app.canopychat.model-delivery / installation-token`.

2. Before downloading, call `GET /v1/model-manifest`. Compare `version` with
   cached version — only download if changed.

3. Download from `download_url` directly using `URLSessionDataTask`. Do **not**
   include the Bearer token in this request.

4. After download, verify SHA-256 against `sha256` before passing to llama.cpp.

5. During slow downloads: check `url_expires_at` periodically. If < 2 minutes
   remain, call `/v1/model-manifest/refresh` for a fresh URL and resume with
   `Range: bytes=<offset>-`.

6. On 401/403 from manifest: clear Keychain token, re-register, retry once.

---

## Cloudflare deployment steps

### 1. Create R2 bucket

```bash
wrangler r2 bucket create canopy-model-files
```

In the Cloudflare dashboard under R2 → canopy-model-files → Settings:
- **Public access**: Disabled (keep private)
- **CORS**: None needed

### 2. Create R2 S3 API token

Dashboard → R2 → Manage R2 API Tokens → Create API Token:
- Permissions: **Object Read & Write** on bucket `canopy-model-files` only
- Copy Access Key ID and Secret Access Key

### 3. Create D1 database

```bash
cd model_delivery/worker
wrangler d1 create canopy-model-delivery
# Copy the database_id into wrangler.toml [[d1_databases]] database_id field
wrangler d1 migrations apply canopy-model-delivery --remote
```

### 4. Set Worker secrets

```bash
wrangler secret put R2_ACCOUNT_ID        # your CF account ID
wrangler secret put R2_ACCESS_KEY_ID     # from step 2
wrangler secret put R2_SECRET_ACCESS_KEY # from step 2
wrangler secret put R2_BUCKET_NAME       # canopy-model-files
wrangler secret put ADMIN_SECRET         # openssl rand -hex 32
```

### 5. Edit wrangler.toml

Fill in `database_id` (from step 3) and both `bucket_name` fields.

### 6. Deploy Worker

```bash
wrangler deploy
```

In the Cloudflare dashboard → Workers & Pages → canopy-model-delivery → Triggers:
- Add Custom Domain: `model-api.canopychat.app`

### 7. Run DGX sync (one-time per release)

```bash
cd model_delivery
export CANOPY_DELIVERY_HF_TOKEN=hf_...
export CANOPY_DELIVERY_R2_ACCOUNT_ID=...
export CANOPY_DELIVERY_R2_ACCESS_KEY_ID=...
export CANOPY_DELIVERY_R2_SECRET_ACCESS_KEY=...
export CANOPY_DELIVERY_R2_BUCKET=canopy-model-files
export CANOPY_DELIVERY_MODEL_VERSION=1.1.2
export CANOPY_DELIVERY_MODEL_FILENAME=canopy-1.1.2.Q4_K_M.gguf
export CANOPY_DELIVERY_HF_REPO=nathanaelguitar/canopy-1.1.2

python -m canopy_delivery.sync
```

This downloads from HF (~1.3 GB), uploads to R2 at
`models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf`, then atomically writes
`manifests/current.json`. **After this the DGX is no longer needed for model
delivery.**

### 8. Verify

```bash
curl https://model-api.canopychat.app/health
# {"status":"ok","version":"1.1.2"}
```

### 9. Configure Cloudflare WAF rate limit (dashboard)

Security → WAF → Rate Limiting Rules → Create Rule:
- Name: `manifest-ip-rate-limit`
- Matches: `http.request.uri.path contains "/v1/model-manifest"`
- Rate: 60 requests / 1 hour / IP
- Action: Block (429)

---

## Releasing a new model version (e.g. 1.1.3)

```bash
export CANOPY_DELIVERY_MODEL_VERSION=1.1.3
export CANOPY_DELIVERY_MODEL_FILENAME=canopy-1.1.3.Q4_K_M.gguf
export CANOPY_DELIVERY_HF_REPO=nathanaelguitar/canopy-1.1.3

# Remove skip-guard so sync runs again
rm /data/canopy/model_delivery/manifest_meta.json

python -m canopy_delivery.sync
```

The old object at `models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf` is never
overwritten. **Rollback** = re-run sync pointed at 1.1.2, or manually PUT the
old `manifests/current.json` content via the Cloudflare dashboard.

---

## Rate-limit behavior and consistency note

| Limit | Key | Enforcement |
|-------|-----|-------------|
| 5 registrations/hour | Source IP (hashed in D1) | Worker + D1 |
| 24 manifests/24 h per version | Install token hash | Worker + D1 |
| 60 manifests/hour | Source IP | Cloudflare WAF rule |

D1 is replicated SQLite; writes propagate within ~150 ms across regions. Two
concurrent manifest requests could each read a count of N-1 and both be
allowed within that window. At beta scale (< 100 testers) this is acceptable.
For stricter enforcement, replace D1 counters with a Durable Object.

---

## Worker tests

```bash
cd model_delivery/worker
npm install
npm test
```

Covers: token registration, invalid/revoked token, manifest schema, signed-URL
expiry parameters, immutable version behavior, rate limits, and unknown routes.
