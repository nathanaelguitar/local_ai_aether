/**
 * D1-backed per-IP throttle on Checkout Session creation. This is abuse /
 * card-testing protection, not the capacity limit — see capacity.ts for
 * that. Same replication-lag caveat as the model-delivery Worker: acceptable
 * at beta scale, promote to a Durable Object counter if stricter enforcement
 * is ever needed.
 */

async function hmacSha256(keyMaterial: string, input: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(keyMaterial),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const digest = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(input));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function checkRequestRateLimit(
  db: D1Database,
  ip: string,
  salt: string,
  attemptType: "checkout" | "status",
  limit: number,
  windowSeconds: number,
): Promise<{ allowed: boolean; retryAfterSeconds?: number }> {
  const ipHash = await hmacSha256(salt, ip);
  const since = new Date(Date.now() - windowSeconds * 1000).toISOString();
  const now = new Date().toISOString();

  await db.prepare("DELETE FROM checkout_attempts WHERE attempted_at < ?").bind(since).run();

  const result = await db
    .prepare(
      `INSERT INTO checkout_attempts (ip_hash, attempt_type, attempted_at)
       SELECT ?, ?, ?
       WHERE (
         SELECT COUNT(*) FROM checkout_attempts
         WHERE ip_hash = ? AND attempt_type = ? AND attempted_at >= ?
       ) < ?`,
    )
    .bind(ipHash, attemptType, now, ipHash, attemptType, since, limit)
    .run();

  return (result.meta.changes ?? 0) === 1
    ? { allowed: true }
    : { allowed: false, retryAfterSeconds: windowSeconds };
}
