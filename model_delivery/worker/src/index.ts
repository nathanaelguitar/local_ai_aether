import {
  extractBearerToken,
  registerToken,
  revokeByInstallId,
  validateToken,
} from "./auth";
import { buildManifestResponse, fetchManifestMeta } from "./manifest";
import { checkManifestRateLimit, checkRegistrationRateLimit } from "./ratelimit";
import { sha256 } from "./r2sign";
import type { Env } from "./types";

// ── Helpers ──────────────────────────────────────────────────────────────────

function json(body: unknown, status = 200, extra?: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      ...extra,
    },
  });
}

function clientIp(request: Request): string {
  return request.headers.get("CF-Connecting-IP") ?? "unknown";
}

/** Structured log — never includes raw bearer values, signed URLs, or secrets. */
function log(level: "info" | "warn" | "error", event: string, fields: Record<string, unknown> = {}) {
  console[level](JSON.stringify({ level, event, ...fields, ts: new Date().toISOString() }));
}

// ── Route handlers ────────────────────────────────────────────────────────────

async function handleHealth(env: Env): Promise<Response> {
  const meta = await fetchManifestMeta(env.MODEL_BUCKET);
  if (!meta) {
    return json({ status: "initializing", detail: "Run the DGX sync job first." }, 503);
  }
  return json({ status: "ok", version: meta.version });
}

async function handleTokenRegistration(request: Request, env: Env): Promise<Response> {
  let body: { install_id?: string } = {};
  try {
    const text = await request.text();
    if (text) body = JSON.parse(text);
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const installId = (body.install_id ?? "").trim() || crypto.randomUUID();
  const ip = clientIp(request);
  const rl = await checkRegistrationRateLimit(env.DB, ip);

  if (!rl.allowed) {
    log("warn", "registration_rate_limited", { install_id: installId });
    return json({ error: "rate_limited" }, 429, { "Retry-After": String(rl.retryAfterSeconds) });
  }

  const token = await registerToken(env.DB, installId);
  log("info", "token_registered", { install_id: installId });

  // Return both "token" (backward compat with existing iOS) and "installation_token"
  // (the preferred key the updated iOS decoder checks first).
  return json({ token, installation_token: token, install_id: installId }, 201);
}

async function handleManifest(request: Request, env: Env): Promise<Response> {
  const rawToken = extractBearerToken(request);
  if (!rawToken) {
    return new Response(null, {
      status: 401,
      headers: { "WWW-Authenticate": 'Bearer realm="canopy-delivery"' },
    });
  }

  const tokenRow = await validateToken(env.DB, rawToken);
  if (!tokenRow) {
    log("warn", "invalid_token");
    return json({ error: "invalid_token" }, 403);
  }

  const meta = await fetchManifestMeta(env.MODEL_BUCKET);
  if (!meta) {
    return json({ error: "service_unavailable", detail: "Model not synced yet." }, 503);
  }

  const tokenHash = await sha256(rawToken);
  const rl = await checkManifestRateLimit(env.DB, tokenHash, meta.version);
  if (!rl.allowed) {
    log("warn", "manifest_rate_limited", { install_id: tokenRow.install_id });
    return json({ error: "rate_limited" }, 429, { "Retry-After": String(rl.retryAfterSeconds) });
  }

  log("info", "manifest_issued", {
    install_id: tokenRow.install_id,
    version: meta.version,
    app_version: request.headers.get("X-Canopy-App-Version") ?? "unknown",
  });

  return buildManifestResponse(meta, env);
}

async function handleRevoke(request: Request, env: Env): Promise<Response> {
  // Admin-only: require Bearer <ADMIN_SECRET>
  const rawToken = extractBearerToken(request);
  if (!rawToken || rawToken !== env.ADMIN_SECRET) {
    return json({ error: "forbidden" }, 403);
  }

  let body: { install_id?: string } = {};
  try {
    const text = await request.text();
    if (text) body = JSON.parse(text);
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const installId = (body.install_id ?? "").trim();
  if (!installId) {
    return json({ error: "install_id required" }, 400);
  }

  const changed = await revokeByInstallId(env.DB, installId);
  log("info", "tokens_revoked", { install_id: installId, count: changed });
  return json({ revoked: changed });
}

// ── Entry point ───────────────────────────────────────────────────────────────

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const { pathname } = url;
    const method = request.method.toUpperCase();

    if (pathname === "/health" || pathname === "/ready") {
      return handleHealth(env);
    }

    if (pathname === "/v1/tokens" && method === "POST") {
      return handleTokenRegistration(request, env);
    }

    if (
      (pathname === "/v1/model-manifest" || pathname === "/v1/model-manifest/refresh") &&
      method === "GET"
    ) {
      return handleManifest(request, env);
    }

    if (pathname === "/admin/tokens/revoke" && method === "POST") {
      return handleRevoke(request, env);
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;
