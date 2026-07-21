# Canopy Model Delivery API

Base URL: `https://model-api.canopychat.app`

All request and response bodies are `application/json`.

---

## Security design

- The Hugging Face read token (`CANOPY_DELIVERY_HF_TOKEN`) lives **only** in server-side environment secrets — never in iOS source, `Info.plist`, Git, or logs.
- The server **never proxies GGUF bytes**. It returns a short-lived signed Cloudflare R2 URL; iOS downloads the file directly from R2.
- Range requests and resume are natively supported by R2 signed URLs — iOS uses `URLSessionDownloadTask` with range headers against the signed URL, not against this API.
- Signed URLs expire after 15 minutes (`url_expires_at`). Clients re-call `/v1/model-manifest` to get a fresh URL before attempting a new session or resuming a stalled download.

---

## Auth flow

### First launch

```
iOS                           model-api.canopychat.app
 |                                      |
 |--POST /v1/tokens ------------------>|
 |  body: {"install_id": "<uuid>"}     |
 |                                      |
 |<-201 {"token":"<uuid>","install_id":"<uuid>"}
 |                                      |
 |  [store token in iOS Keychain]       |
```

### Every manifest call

```
iOS                           model-api.canopychat.app          Cloudflare R2
 |                                      |                             |
 |--GET /v1/model-manifest ----------->|                             |
 |  Authorization: Bearer <token>       |                             |
 |                                      |-- generate presigned URL -->|
 |<-200 manifest JSON                   |                             |
 |  (includes download_url, expiry)     |                             |
 |                                      |                             |
 |--GET <download_url> (Range: bytes=N-)---------------------------------------->|
 |<-206 GGUF bytes (direct from R2, server not involved) ----------------------->|
```

### URL refresh (resuming a stalled download)

Re-call `GET /v1/model-manifest` with the same Bearer token. The server generates a new presigned URL. The client then resumes with `Range: bytes=<already_received>-` against the new URL.

---

## Endpoints

### `GET /health` · `GET /ready`

No authentication required. Returns 503 until the GGUF has been synced to R2 (`manifest_meta.json` present).

**Response 200**
```json
{"status": "ok"}
```

**Response 503** (sync not yet complete)
```json
{"status": "initializing", "detail": "run sync first"}
```

---

### `POST /v1/tokens`

Register a new per-install token. No authentication required.

**Request body**
```json
{
  "install_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `install_id` | string (UUID4) | No | Client-generated stable install identifier. Server generates one if omitted. |

**Response 201**
```json
{
  "token": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "install_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `token` | string (UUID4) | Bearer token. Store in iOS Keychain. Never log or transmit except in `Authorization` header. |
| `install_id` | string (UUID4) | Echoed back (or server-generated if omitted). |

---

### `GET /v1/model-manifest`

### `GET /v1/model-manifest/refresh`

Both paths are identical — the `/refresh` alias exists for client-side clarity when retrying an expired URL.

**Request headers**

```
Authorization: Bearer <token>
```

**Response 200**
```json
{
  "version": "1.1.2",
  "filename": "canopy-1.1.2.Q4_K_M.gguf",
  "size_bytes": 1274818816,
  "sha256": "a3f1c2...<64 hex chars>",
  "download_url": "https://<account_id>.r2.cloudflarestorage.com/...<signed>",
  "url_expires_at": "2026-07-20T13:15:00Z"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | string | Semantic version of the model. If this changes, the client should re-download. |
| `filename` | string | Canonical filename. |
| `size_bytes` | integer | Total byte size. Use for progress display and integrity pre-check. |
| `sha256` | string | Lowercase hex SHA-256 of the full GGUF. Verify after download. |
| `download_url` | string | Presigned R2 GET URL. Valid for `url_expires_at`. Supports `Range` requests. |
| `url_expires_at` | string (ISO 8601 UTC) | When `download_url` expires. Refresh before this time if download is not complete. |

**Error responses**

| Status | Body | Meaning |
|--------|------|---------|
| 401 | — | Missing or malformed `Authorization` header. |
| 403 | `{"error":"invalid_token"}` | Token not registered. |
| 503 | `{"error":"service_unavailable","detail":"model not synced"}` | Sync has not been run. |

---

## iOS integration notes

1. Generate a UUID on first launch and call `POST /v1/tokens`. Store the returned `token` in iOS Keychain under a stable key (e.g., `com.canopychat.delivery.token`).

2. Before starting a model download, call `GET /v1/model-manifest`. Check `version` against the locally cached version — only download if changed.

3. Download the GGUF directly from `download_url` using `URLSessionDownloadTask`. Do not include the Bearer token in this request — it is not needed and must not be leaked to R2 logs.

4. After download, verify SHA-256 of the file against `sha256` from the manifest before loading into llama.cpp.

5. For resume: persist the partial download across app restarts (iOS `URLSessionDownloadTask` supports this). Before resuming, call `/v1/model-manifest/refresh` to get a fresh URL, then resume with `Range: bytes=<offset>-`.

6. URL refresh cadence: check `url_expires_at` every few minutes during a download. Refresh when less than 2 minutes remain.

---

## Operational runbook

### First-time setup

```bash
cd model_delivery
cp .env.example .env
# Fill in all REPLACE_ME values

# Create the data directory on the host
sudo mkdir -p /data/canopy/model_delivery
sudo chown 10002:10002 /data/canopy/model_delivery

# Sync GGUF from HF to R2 (one-time, ~1.3 GB transfer)
docker-compose run --rm \
  -e CANOPY_DELIVERY_HF_TOKEN=<hf_token> \
  delivery python -m canopy_delivery.sync

# Start the service
docker-compose --profile tunnel up -d
```

### Update model to a new version

```bash
# 1. Update env vars in .env:
#    CANOPY_DELIVERY_MODEL_FILENAME=canopy-1.2.0.Q4_K_M.gguf
#    CANOPY_DELIVERY_MODEL_VERSION=1.2.0

# 2. Remove old metadata so sync runs again
rm /data/canopy/model_delivery/manifest_meta.json

# 3. Re-sync
docker-compose run --rm -e CANOPY_DELIVERY_HF_TOKEN=<hf_token> delivery python -m canopy_delivery.sync

# 4. Restart server (picks up new manifest_meta.json)
docker-compose restart delivery
```

### Force re-sync (re-upload to R2)

```bash
rm /data/canopy/model_delivery/manifest_meta.json
docker-compose run --rm -e CANOPY_DELIVERY_HF_TOKEN=<hf_token> delivery python -m canopy_delivery.sync
```
