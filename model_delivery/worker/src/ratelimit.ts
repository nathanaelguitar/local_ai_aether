/**
 * D1-backed rate limits for the contributor beta.
 *
 * Consistency note: D1 is an SQLite replica set with read replicas per region.
 * Writes replicate within ~150 ms. Under concurrent requests from the same
 * install, two manifest issuances could slip through within that window.
 * At beta scale this is acceptable; promote to a Durable Object counter if
 * stricter enforcement is needed.
 *
 * IP-level manifest limits (60/hour) should be enforced via Cloudflare WAF
 * rate-limiting rules in the dashboard, which operate at the edge before
 * the Worker is invoked.
 */

import { sha256 } from "./r2sign";
import type { D1Database } from "@cloudflare/workers-types";

/** 5 token registrations per IP per hour. */
const REGISTRATION_LIMIT = 5;
const REGISTRATION_WINDOW_HOURS = 1;

/** 24 manifest issuances per install per model version per 24 hours. */
const MANIFEST_LIMIT = 24;
const MANIFEST_WINDOW_HOURS = 24;

function hoursAgo(h: number): string {
  return new Date(Date.now() - h * 3_600_000).toISOString().replace(/\.\d{3}Z$/, "Z");
}

export async function checkRegistrationRateLimit(
  db: D1Database,
  ip: string,
): Promise<{ allowed: boolean; retryAfterSeconds?: number }> {
  const ipHash = await sha256(ip);
  const since = hoursAgo(REGISTRATION_WINDOW_HOURS);

  const row = await db
    .prepare(
      "SELECT COUNT(*) as cnt FROM registration_attempts WHERE ip_hash = ? AND attempted_at >= ?",
    )
    .bind(ipHash, since)
    .first<{ cnt: number }>();

  const count = row?.cnt ?? 0;
  if (count >= REGISTRATION_LIMIT) {
    return { allowed: false, retryAfterSeconds: 3600 };
  }

  await db
    .prepare(
      "INSERT INTO registration_attempts (ip_hash, attempted_at) VALUES (?, ?)",
    )
    .bind(ipHash, new Date().toISOString().replace(/\.\d{3}Z$/, "Z"))
    .run();

  return { allowed: true };
}

export async function checkManifestRateLimit(
  db: D1Database,
  tokenHash: string,
  modelVersion: string,
): Promise<{ allowed: boolean; retryAfterSeconds?: number }> {
  const since = hoursAgo(MANIFEST_WINDOW_HOURS);

  const row = await db
    .prepare(
      "SELECT COUNT(*) as cnt FROM manifest_issuances WHERE token_hash = ? AND model_version = ? AND issued_at >= ?",
    )
    .bind(tokenHash, modelVersion, since)
    .first<{ cnt: number }>();

  const count = row?.cnt ?? 0;
  if (count >= MANIFEST_LIMIT) {
    return { allowed: false, retryAfterSeconds: 86400 };
  }

  await db
    .prepare(
      "INSERT INTO manifest_issuances (token_hash, model_version, issued_at) VALUES (?, ?, ?)",
    )
    .bind(tokenHash, modelVersion, new Date().toISOString().replace(/\.\d{3}Z$/, "Z"))
    .run();

  return { allowed: true };
}
