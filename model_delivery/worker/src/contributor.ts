import { sha256 } from "./r2sign";
import type { D1Database } from "@cloudflare/workers-types";

export const CONTRIBUTOR_MAX_BODY_BYTES = 2_000_000;
const CONTRIBUTOR_INSTALL_LIMIT = 30;
const CONTRIBUTOR_INSTALL_WINDOW_SECONDS = 24 * 60 * 60;
const CONTRIBUTOR_IP_LIMIT = 60;
const CONTRIBUTOR_IP_WINDOW_SECONDS = 60 * 60;

export async function claimContributorInstallation(
  db: D1Database,
  identityHash: string,
  maxInstallations: number,
): Promise<boolean> {
  if (!Number.isInteger(maxInstallations) || maxInstallations < 1) {
    throw new Error("invalid_contributor_capacity");
  }

  const existing = await db
    .prepare("SELECT token_hash FROM contributor_installations WHERE token_hash = ?")
    .bind(identityHash)
    .first<{ token_hash: string }>();
  if (existing) return true;

  const result = await db
    .prepare(
      `INSERT OR IGNORE INTO contributor_installations (token_hash, first_seen_at)
       SELECT ?, ?
       WHERE (SELECT COUNT(*) FROM contributor_installations) < ?`,
    )
    .bind(identityHash, isoNow(), maxInstallations)
    .run();
  return (result.meta.changes ?? 0) === 1;
}

function isoNow(): string {
  return new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
}

function isoAgo(seconds: number): string {
  return new Date(Date.now() - seconds * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
}

export async function checkContributorUploadRateLimit(
  db: D1Database,
  tokenHash: string,
  ip: string,
): Promise<{ allowed: boolean; retryAfterSeconds?: number }> {
  const ipHash = await sha256(ip);
  const installSince = isoAgo(CONTRIBUTOR_INSTALL_WINDOW_SECONDS);
  const ipSince = isoAgo(CONTRIBUTOR_IP_WINDOW_SECONDS);
  const counts = await db
    .prepare(
      `SELECT
         (SELECT COUNT(*) FROM contributor_upload_attempts
           WHERE token_hash = ? AND attempted_at >= ?) AS install_count,
         (SELECT COUNT(*) FROM contributor_upload_attempts
           WHERE ip_hash = ? AND attempted_at >= ?) AS ip_count`,
    )
    .bind(tokenHash, installSince, ipHash, ipSince)
    .first<{ install_count: number; ip_count: number }>();

  if ((counts?.install_count ?? 0) >= CONTRIBUTOR_INSTALL_LIMIT) {
    return { allowed: false, retryAfterSeconds: CONTRIBUTOR_INSTALL_WINDOW_SECONDS };
  }
  if ((counts?.ip_count ?? 0) >= CONTRIBUTOR_IP_LIMIT) {
    return { allowed: false, retryAfterSeconds: CONTRIBUTOR_IP_WINDOW_SECONDS };
  }

  await db
    .prepare(
      "INSERT INTO contributor_upload_attempts (token_hash, ip_hash, attempted_at) VALUES (?, ?, ?)",
    )
    .bind(tokenHash, ipHash, isoNow())
    .run();
  return { allowed: true };
}

export async function signContributorBody(
  secret: string,
  timestamp: string,
  body: Uint8Array,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const message = new Uint8Array([
    ...new TextEncoder().encode(`${timestamp}.`),
    ...body,
  ]);
  const signature = await crypto.subtle.sign("HMAC", key, message);
  return [...new Uint8Array(signature)].map(byte => byte.toString(16).padStart(2, "0")).join("");
}

export async function readBodyWithLimit(request: Request, maxBytes: number): Promise<Uint8Array> {
  const contentLength = request.headers.get("Content-Length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength)) throw new Error("invalid_content_length");
    if (Number(contentLength) > maxBytes) throw new PayloadTooLargeError();
  }

  if (!request.body) throw new Error("missing_body");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      total += result.value.byteLength;
      if (total > maxBytes) throw new PayloadTooLargeError();
      chunks.push(result.value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

export class PayloadTooLargeError extends Error {
  constructor() {
    super("payload_too_large");
    this.name = "PayloadTooLargeError";
  }
}
