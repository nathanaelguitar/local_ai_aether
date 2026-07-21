import { sha256 } from "./r2sign";
import type { D1Database } from "@cloudflare/workers-types";
import type { InstallTokenRow } from "./types";

/** 32 cryptographically random bytes encoded as 64 lowercase hex characters. */
function generateToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");
}

export async function registerToken(
  db: D1Database,
  installId: string,
): Promise<string> {
  const token = generateToken();
  const tokenHash = await sha256(token);
  const now = new Date().toISOString().replace(/\.\d{3}Z$/, "Z");

  await db
    .prepare(
      "INSERT INTO install_tokens (token_hash, install_id, created_at, revoked) VALUES (?, ?, ?, 0)",
    )
    .bind(tokenHash, installId, now)
    .run();

  return token;
}

export async function validateToken(
  db: D1Database,
  rawToken: string,
): Promise<InstallTokenRow | null> {
  const tokenHash = await sha256(rawToken);
  const row = await db
    .prepare("SELECT * FROM install_tokens WHERE token_hash = ? AND revoked = 0")
    .bind(tokenHash)
    .first<InstallTokenRow>();
  return row ?? null;
}

export async function revokeByInstallId(
  db: D1Database,
  installId: string,
): Promise<number> {
  const result = await db
    .prepare("UPDATE install_tokens SET revoked = 1 WHERE install_id = ? AND revoked = 0")
    .bind(installId)
    .run();
  return result.meta.changes ?? 0;
}

export function extractBearerToken(request: Request): string | null {
  const auth = request.headers.get("Authorization") ?? "";
  return auth.startsWith("Bearer ") ? auth.slice(7).trim() : null;
}
