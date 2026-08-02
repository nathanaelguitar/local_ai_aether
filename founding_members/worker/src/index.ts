import { createFoundingMemberCheckout, isCheckoutConfigurationValid } from "./checkout";
import { recordWebhookEventOutcome } from "./db";
import { checkRequestRateLimit } from "./ratelimit";
import { lookupCheckoutSessionStatus } from "./sessionStatus";
import { newStripeClient } from "./stripeClient";
import type { Env } from "./types";
import { fulfillWebhookEvent, verifyStripeWebhook } from "./webhook";

const MAX_CHECKOUT_BODY_BYTES = 1_024;
const MAX_WEBHOOK_BODY_BYTES = 1_048_576;
const CHECKOUT_RATE_LIMIT = 10;
const STATUS_RATE_LIMIT = 60;
const RATE_LIMIT_WINDOW_SECONDS = 60 * 60;

function siteOrigin(env: Env): string {
  try {
    return new URL(env.PUBLIC_SITE_URL).origin;
  } catch {
    return "https://canopychat.app";
  }
}

const LOCAL_TEST_ORIGINS = new Set([
  "http://127.0.0.1:8765",
  "http://localhost:8765",
]);

export function allowedBrowserOrigin(request: Request, env: Env): string | null {
  const origin = request.headers.get("Origin");
  if (origin === siteOrigin(env)) return origin;
  if (env.ENVIRONMENT === "test" && origin && LOCAL_TEST_ORIGINS.has(origin)) {
    return origin;
  }
  return null;
}

function corsHeaders(env: Env, request?: Request): HeadersInit {
  const origin = request ? allowedBrowserOrigin(request, env) : null;
  return {
    "Access-Control-Allow-Origin": origin ?? siteOrigin(env),
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    Vary: "Origin",
  };
}

function json(
  body: unknown,
  status: number,
  env: Env,
  extra?: HeadersInit,
  request?: Request,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...corsHeaders(env, request),
      ...extra,
    },
  });
}

function clientIp(request: Request): string {
  // Cloudflare replaces this header at the edge. Do not trust caller-provided
  // X-Forwarded-For for an abuse-control identity.
  return request.headers.get("CF-Connecting-IP")?.trim() || "local-development";
}

function isUsCheckoutRequest(request: Request, env: Env): boolean {
  const rawCountry = request.cf?.country;
  const country = typeof rawCountry === "string" ? rawCountry.toUpperCase() : null;
  if (country === "US") return true;

  // Wrangler's local runtime doesn't populate request.cf. This exception is
  // unavailable on deployed hostnames and never bypasses the browser-origin
  // check above.
  const hostname = new URL(request.url).hostname;
  return (
    env.ENVIRONMENT === "test" &&
    (hostname === "localhost" || hostname === "127.0.0.1")
  );
}

function contentLengthExceeds(request: Request, limit: number): boolean {
  const raw = request.headers.get("Content-Length");
  if (!raw) return false;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > limit;
}

function log(
  level: "info" | "warn" | "error",
  event: string,
  fields: Record<string, unknown> = {},
) {
  console[level](JSON.stringify({ level, event, ...fields, ts: new Date().toISOString() }));
}

async function handleHealth(env: Env): Promise<Response> {
  if (!isCheckoutConfigurationValid(env)) {
    return json({ status: "misconfigured" }, 503, env);
  }

  try {
    await env.DB.prepare("SELECT 1 AS healthy").first();
    return json({ status: "ok", environment: env.ENVIRONMENT }, 200, env);
  } catch {
    return json({ status: "database_unavailable" }, 503, env);
  }
}

async function parseEmptyCheckoutBody(request: Request): Promise<boolean> {
  if (!request.headers.get("Content-Type")?.toLowerCase().startsWith("application/json")) {
    return false;
  }
  if (contentLengthExceeds(request, MAX_CHECKOUT_BODY_BYTES)) return false;

  try {
    const rawBody = await request.text();
    if (new TextEncoder().encode(rawBody).byteLength > MAX_CHECKOUT_BODY_BYTES) return false;
    const body = JSON.parse(rawBody) as unknown;
    return (
      typeof body === "object" &&
      body !== null &&
      !Array.isArray(body) &&
      Object.keys(body).length === 0
    );
  } catch {
    return false;
  }
}

async function handleCreateCheckout(request: Request, env: Env): Promise<Response> {
  const browserOrigin = allowedBrowserOrigin(request, env);
  if (!browserOrigin) {
    return json({ error: "origin_not_allowed" }, 403, env);
  }
  if (!isCheckoutConfigurationValid(env)) {
    return json({ error: "service_misconfigured" }, 503, env, undefined, request);
  }
  if (!(await parseEmptyCheckoutBody(request))) {
    return json({ error: "invalid_request" }, 400, env, undefined, request);
  }
  if (!isUsCheckoutRequest(request, env)) {
    log("info", "checkout_country_not_supported", {
      country: request.cf?.country ?? "unknown",
    });
    return json({ error: "country_not_supported" }, 403, env, undefined, request);
  }

  const rateLimit = await checkRequestRateLimit(
    env.DB,
    clientIp(request),
    env.RATE_LIMIT_SALT,
    "checkout",
    CHECKOUT_RATE_LIMIT,
    RATE_LIMIT_WINDOW_SECONDS,
  );
  if (!rateLimit.allowed) {
    log("warn", "checkout_rate_limited");
    return json(
      { error: "rate_limited" },
      429,
      env,
      { "Retry-After": String(rateLimit.retryAfterSeconds) },
      request,
    );
  }

  // There is no authentication on the static site. A future verified account
  // ID connects here; it must never be accepted from this request body.
  let result: Awaited<ReturnType<typeof createFoundingMemberCheckout>>;
  try {
    result = await createFoundingMemberCheckout(newStripeClient(env), env, {
      internalUserId: null,
      returnOrigin: browserOrigin,
    });
  } catch {
    log("error", "checkout_internal_failure");
    return json({ error: "checkout_unavailable" }, 503, env, undefined, request);
  }

  if (!result.ok) {
    if (result.reason === "capacity_reached") {
      log("info", "checkout_capacity_reached");
      return json({ error: "capacity_reached" }, 409, env, undefined, request);
    }
    if (result.reason === "misconfigured") {
      return json({ error: "service_misconfigured" }, 503, env, undefined, request);
    }
    log("error", "checkout_session_creation_failed", { reason: result.detail });
    return json({ error: "checkout_unavailable" }, 502, env, undefined, request);
  }

  log("info", "checkout_session_created");
  return json({ url: result.url }, 200, env, undefined, request);
}

async function handleWebhook(request: Request, env: Env): Promise<Response> {
  if (!isCheckoutConfigurationValid(env)) {
    return json({ error: "service_misconfigured" }, 503, env);
  }
  if (contentLengthExceeds(request, MAX_WEBHOOK_BODY_BYTES)) {
    return json({ error: "payload_too_large" }, 413, env);
  }

  const rawBody = await request.text();
  if (new TextEncoder().encode(rawBody).byteLength > MAX_WEBHOOK_BODY_BYTES) {
    return json({ error: "payload_too_large" }, 413, env);
  }

  const stripe = newStripeClient(env);
  const verified = await verifyStripeWebhook(
    stripe,
    rawBody,
    request.headers.get("Stripe-Signature"),
    env.STRIPE_WEBHOOK_SECRET,
  );
  if (!verified.ok) {
    log("warn", "webhook_signature_rejected", { reason: verified.reason });
    return json({ error: verified.reason }, 400, env);
  }

  try {
    const result = await fulfillWebhookEvent(env, verified.event);
    log("info", "webhook_processed", {
      event_id: verified.event.id,
      event_type: verified.event.type,
      outcome: result.outcome,
    });
    return json({ received: true }, 200, env);
  } catch {
    // A processing_failed event remains retryable. If even this best-effort
    // write fails, the 500 still causes Stripe to redeliver the event.
    try {
      await recordWebhookEventOutcome(
        env.DB,
        verified.event.id,
        verified.event.type,
        "processing_failed",
      );
    } catch {
      // Intentionally empty: do not hide the original retry signal.
    }
    log("error", "webhook_processing_failed", {
      event_id: verified.event.id,
      event_type: verified.event.type,
    });
    return json({ error: "processing_failed" }, 500, env);
  }
}

async function handleCheckoutSessionStatus(request: Request, env: Env): Promise<Response> {
  if (!allowedBrowserOrigin(request, env)) {
    return json({ error: "origin_not_allowed" }, 403, env);
  }
  if (!isCheckoutConfigurationValid(env)) {
    return json({ error: "service_misconfigured" }, 503, env, undefined, request);
  }

  const rateLimit = await checkRequestRateLimit(
    env.DB,
    clientIp(request),
    env.RATE_LIMIT_SALT,
    "status",
    STATUS_RATE_LIMIT,
    RATE_LIMIT_WINDOW_SECONDS,
  );
  if (!rateLimit.allowed) {
    return json(
      { error: "rate_limited" },
      429,
      env,
      { "Retry-After": String(rateLimit.retryAfterSeconds) },
      request,
    );
  }

  const sessionId = new URL(request.url).searchParams.get("session_id")?.trim();
  if (!sessionId || sessionId.length > 255 || !/^cs_(?:test_)?[A-Za-z0-9_]+$/.test(sessionId)) {
    return json({ error: "invalid_session_id" }, 400, env, undefined, request);
  }

  const status = await lookupCheckoutSessionStatus(
    newStripeClient(env),
    env,
    sessionId,
  );
  return json(status, 200, env, undefined, request);
}

function methodNotAllowed(env: Env, allow: string): Response {
  return json({ error: "method_not_allowed" }, 405, env, { Allow: allow });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    const method = request.method.toUpperCase();

    if (method === "OPTIONS") {
      const isFrontendRoute = pathname === "/v1/checkout" || pathname === "/v1/checkout-session";
      if (!isFrontendRoute || !allowedBrowserOrigin(request, env)) {
        return json({ error: "origin_not_allowed" }, 403, env);
      }
      return new Response(null, { status: 204, headers: corsHeaders(env, request) });
    }

    if (pathname === "/health") {
      return method === "GET" ? handleHealth(env) : methodNotAllowed(env, "GET");
    }
    if (pathname === "/v1/checkout") {
      return method === "POST"
        ? handleCreateCheckout(request, env)
        : methodNotAllowed(env, "POST");
    }
    if (pathname === "/v1/webhook") {
      return method === "POST" ? handleWebhook(request, env) : methodNotAllowed(env, "POST");
    }
    if (pathname === "/v1/checkout-session") {
      return method === "GET"
        ? handleCheckoutSessionStatus(request, env)
        : methodNotAllowed(env, "GET");
    }

    return json({ error: "not_found" }, 404, env);
  },
} satisfies ExportedHandler<Env>;
