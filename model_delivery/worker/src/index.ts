import {
  extractBearerToken,
  registerToken,
  revokeByInstallId,
  validateToken,
} from "./auth";
import { buildManifestResponse, fetchManifestMeta } from "./manifest";
import { checkManifestRateLimit, checkRegistrationRateLimit } from "./ratelimit";
import { sha256 } from "./r2sign";
import {
  checkContributorUploadRateLimit,
  claimContributorInstallation,
  CONTRIBUTOR_MAX_BODY_BYTES,
  PayloadTooLargeError,
  readBodyWithLimit,
  signContributorBody,
} from "./contributor";
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
  const direct = request.headers.get("CF-Connecting-IP");
  if (direct) return direct;
  const forwarded = request.headers.get("X-Forwarded-For");
  const firstForwarded = forwarded?.split(",", 1)[0];
  return firstForwarded?.trim() || "unknown";
}

/** Structured log — never includes raw bearer values, signed URLs, or secrets. */
function log(level: "info" | "warn" | "error", event: string, fields: Record<string, unknown> = {}) {
  console[level](JSON.stringify({ level, event, ...fields, ts: new Date().toISOString() }));
}

// ── Route handlers ────────────────────────────────────────────────────────────

async function handleHealth(env: Env): Promise<Response> {
  if (!isDeliveryConfigurationValid(env)) {
    return json({ status: "misconfigured", detail: "R2 delivery configuration is incomplete." }, 503);
  }
  const meta = await fetchManifestMeta(env.MODEL_BUCKET);
  if (!meta) {
    return json({ status: "initializing", detail: "Run the DGX sync job first." }, 503);
  }
  return json({ status: "ok", version: meta.version });
}

function isDeliveryConfigurationValid(env: Env): boolean {
  return /^[a-f0-9]{32}$/i.test((env.R2_ACCOUNT_ID ?? "").trim()) &&
    Boolean(env.R2_ACCESS_KEY_ID?.trim()) &&
    Boolean(env.R2_SECRET_ACCESS_KEY?.trim()) &&
    Boolean(env.R2_BUCKET_NAME?.trim());
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
  if (!isDeliveryConfigurationValid(env)) {
    return json({ error: "service_misconfigured" }, 503);
  }
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

async function handleContributorBatch(request: Request, env: Env): Promise<Response> {
  const requestId = crypto.randomUUID();
  const rawToken = extractBearerToken(request);
  if (!rawToken) {
    return new Response(null, {
      status: 401,
      headers: { "WWW-Authenticate": 'Bearer realm="canopy-contributor"' },
    });
  }

  const tokenRow = await validateToken(env.DB, rawToken);
  if (!tokenRow) {
    log("warn", "contributor_invalid_token", { request_id: requestId });
    return json({ error: "invalid_token" }, 403);
  }

  const [mediaType = ""] = (request.headers.get("Content-Type") ?? "").split(";", 1);
  const contentType = mediaType.trim().toLowerCase();
  if (contentType !== "application/json") {
    return json({ error: "content_type_must_be_json" }, 415);
  }
  const contentEncoding = request.headers.get("Content-Encoding")?.trim() ?? "";
  if (contentEncoding && !["gzip", "identity"].includes(contentEncoding.toLowerCase())) {
    return json({ error: "unsupported_content_encoding" }, 415);
  }

  const tokenHash = await sha256(rawToken);
  const rate = await checkContributorUploadRateLimit(env.DB, tokenHash, clientIp(request));
  if (!rate.allowed) {
    log("warn", "contributor_rate_limited", {
      request_id: requestId,
      install_hash: (await sha256(tokenRow.install_id)).slice(0, 16),
    });
    return json({ error: "rate_limited" }, 429, {
      "Retry-After": String(rate.retryAfterSeconds ?? 3600),
    });
  }

  let body: Uint8Array;
  try {
    body = await readBodyWithLimit(request, CONTRIBUTOR_MAX_BODY_BYTES);
  } catch (error) {
    if (error instanceof PayloadTooLargeError) {
      return json({ error: "payload_too_large" }, 413);
    }
    return json({ error: "invalid_request_body" }, 400);
  }

  const maxInstallations = Number.parseInt(env.CONTRIBUTOR_BETA_MAX_INSTALLATIONS, 10);
  // Count stable installation identities, not bearer tokens, so token rotation
  // cannot bypass the beta ceiling.
  const installationHash = await sha256(tokenRow.install_id);
  const admitted = await claimContributorInstallation(env.DB, installationHash, maxInstallations);
  if (!admitted) {
    log("warn", "contributor_beta_capacity_reached", { request_id: requestId });
    return json({ error: "beta_capacity_reached" }, 429, { "Retry-After": "86400" });
  }

  const timestamp = new Date().toISOString();
  const signature = await signContributorBody(env.CONTRIBUTOR_INGEST_HMAC_SECRET, timestamp, body);
  const headers = new Headers({
    "Content-Type": "application/json",
    "X-Canopy-Timestamp": timestamp,
    "X-Canopy-Signature": `sha256=${signature}`,
    "X-Canopy-Request-ID": requestId,
  });
  if (contentEncoding) headers.set("Content-Encoding", contentEncoding);

  let upstream: Response;
  try {
    upstream = await fetch(env.CONTRIBUTOR_INGEST_ORIGIN, {
      method: "POST",
      headers,
      body,
    });
  } catch {
    log("error", "contributor_origin_unreachable", { request_id: requestId });
    return json({ error: "contributor_service_unavailable" }, 503);
  }

  log("info", "contributor_batch_relayed", {
    request_id: requestId,
    status: upstream.status,
    install_hash: (await sha256(tokenRow.install_id)).slice(0, 16),
  });
  const responseHeaders = new Headers();
  for (const name of ["Content-Type", "Cache-Control", "Retry-After"]) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
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

    if (pathname === "/v1/contributor/batches" && method === "POST") {
      return handleContributorBatch(request, env);
    }

    if (pathname === "/v1/contributor/health" && method === "GET") {
      return json({ status: "ok", service: "canopy-contributor-relay" });
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;
