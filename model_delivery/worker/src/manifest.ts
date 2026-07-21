import { presignR2Get } from "./r2sign";
import type { Env, ManifestMeta } from "./types";

const SIGNED_URL_TTL_SECONDS = 900; // 15 minutes

export async function fetchManifestMeta(bucket: R2Bucket): Promise<ManifestMeta | null> {
  const obj = await bucket.get("manifests/current.json");
  if (!obj) return null;
  const text = await obj.text();
  return JSON.parse(text) as ManifestMeta;
}

export async function buildManifestResponse(
  meta: ManifestMeta,
  env: Env,
): Promise<Response> {
  const { url: downloadUrl, expiresAt: urlExpiresAt } = await presignR2Get({
    accountId: env.R2_ACCOUNT_ID,
    accessKey: env.R2_ACCESS_KEY_ID,
    secretKey: env.R2_SECRET_ACCESS_KEY,
    bucket: env.R2_BUCKET_NAME,
    key: meta.key,
    expiresSeconds: SIGNED_URL_TTL_SECONDS,
  });

  const body = JSON.stringify({
    version: meta.version,
    filename: meta.filename,
    size_bytes: meta.size_bytes,
    sha256: meta.sha256,
    download_url: downloadUrl,
    url_expires_at: urlExpiresAt,
  });

  return new Response(body, {
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}
