# DGX Codex Prompt: Deploy the Model Delivery Fix

The live CanopyChat model-delivery Worker is still issuing malformed signed URLs:

```text
https://undefined.r2.cloudflarestorage.com/...
```

The iOS app consequently fails with:

> CanopyChat could not download the private model: A TLS error caused the secure connection to fail.

The code fix is already pushed to `main` in commit `7e6b989`.

## Instructions

From the repository:

1. Pull the latest `main` branch.
2. Change into `model_delivery/worker`.
3. Confirm Wrangler is authenticated to the correct Cloudflare account.
4. Verify that these Worker secrets exist:

   - `R2_ACCOUNT_ID`
   - `R2_ACCESS_KEY_ID`
   - `R2_SECRET_ACCESS_KEY`
   - `R2_BUCKET_NAME`

5. If `R2_ACCOUNT_ID` is missing or incorrect, set it with:

   ```bash
   npx wrangler secret put R2_ACCOUNT_ID
   ```

   Enter the real 32-character Cloudflare account ID. Never print or commit secrets.

6. Deploy the Worker:

   ```bash
   npx wrangler deploy
   ```

7. Confirm that `model-api.canopychat.app` is attached to this Worker.
8. Verify the deployment:

   ```bash
   npx wrangler deployments list
   ```

9. Test the live health endpoint:

   ```bash
   curl -sS https://model-api.canopychat.app/health
   ```

10. Register a temporary test installation and request `/v1/model-manifest`. Inspect only the hostname of `download_url`; never print the full signed URL or its query parameters.

## Acceptance criteria

- `/health` returns `status: "ok"`.
- The manifest version is `1.1.2`.
- `download_url` uses:

  ```text
  <32-character-account-id>.r2.cloudflarestorage.com
  ```

- The hostname does not contain `undefined`.
- A range request for the first 1 KB of the signed URL returns HTTP `200` or `206`.
- No Cloudflare secrets or signed URLs are exposed in logs, commits, or chat output.

No new TestFlight build is required for this server-side correction. Once the Worker is deployed correctly, the existing contributor build can retry model delivery.
