import type Stripe from "stripe";
import {
  attachCheckoutSessionToReservation,
  checkoutExpiresAt,
  configuredCapacity,
  releaseCheckoutReservation,
  reserveCheckoutCapacity,
} from "./capacity";
import type { Env } from "./types";

export interface CreateCheckoutInput {
  /** Only trusted when it came from server-side authentication — never from an unauthenticated request body. */
  internalUserId: string | null;
  /** Exact browser origin after server-side allowlist validation. */
  returnOrigin?: string;
}

export type CreateCheckoutResult =
  | { ok: true; url: string }
  | { ok: false; reason: "capacity_reached" }
  | { ok: false; reason: "misconfigured" }
  | { ok: false; reason: "stripe_error"; detail: string };

function integrationIdentifier(): string {
  const alphabet = "abcdefghijklmnopqrstuvwxyz";
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  const suffix = Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join("");
  return `canopy_founding_${suffix}`;
}

export function isCheckoutConfigurationValid(env: Env): boolean {
  const capacity = configuredCapacity(env.FOUNDING_MEMBER_CAPACITY);
  let siteUrl: URL;
  try {
    siteUrl = new URL(env.PUBLIC_SITE_URL);
  } catch {
    return false;
  }

  const environmentMatchesKey =
    (env.ENVIRONMENT === "test" &&
      /^(?:sk|rk)_test_/.test(env.STRIPE_SECRET_KEY ?? "")) ||
    (env.ENVIRONMENT === "live" &&
      env.LIVE_PAYMENTS_ENABLED === "true" &&
      /^(?:sk|rk)_live_/.test(env.STRIPE_SECRET_KEY ?? ""));

  return Boolean(
    Boolean(env.STRIPE_SECRET_KEY?.trim()) &&
    Boolean(env.STRIPE_WEBHOOK_SECRET?.startsWith("whsec_")) &&
    Boolean(env.STRIPE_FOUNDING_MEMBER_PRICE_ID?.startsWith("price_")) &&
    Boolean(env.RATE_LIMIT_SALT?.trim()) &&
    Boolean(env.OFFER_VERSION?.trim()) &&
    environmentMatchesKey &&
    capacity &&
    siteUrl.protocol === "https:" &&
    siteUrl.origin === env.PUBLIC_SITE_URL
  );
}

function checkoutReturnOrigin(env: Env, requestedOrigin?: string): string {
  const configuredOrigin = new URL(env.PUBLIC_SITE_URL).origin;
  if (
    env.ENVIRONMENT === "test" &&
    (requestedOrigin === "http://127.0.0.1:8765" ||
      requestedOrigin === "http://localhost:8765")
  ) {
    return requestedOrigin;
  }
  return configuredOrigin;
}

/**
 * Creates a Stripe Checkout Session for the Founding Member one-time offer.
 *
 * The browser never supplies price, currency, quantity, or product details —
 * all of it is server-controlled. The only thing the caller can influence is
 * internalUserId, and only when it was already authenticated server-side.
 */
export async function createFoundingMemberCheckout(
  stripe: Stripe,
  env: Env,
  input: CreateCheckoutInput,
): Promise<CreateCheckoutResult> {
  if (!isCheckoutConfigurationValid(env)) {
    return { ok: false, reason: "misconfigured" };
  }

  const limit = configuredCapacity(env.FOUNDING_MEMBER_CAPACITY);
  if (!limit) {
    return { ok: false, reason: "misconfigured" };
  }

  const checkoutReferenceId = crypto.randomUUID();
  const expiresAt = checkoutExpiresAt();
  const reserved = await reserveCheckoutCapacity(
    env.DB,
    limit,
    checkoutReferenceId,
    expiresAt,
  );
  if (!reserved) {
    return { ok: false, reason: "capacity_reached" };
  }

  const returnOrigin = checkoutReturnOrigin(env, input.returnOrigin);
  const successUrl = `${returnOrigin}/founding-success.html?session_id={CHECKOUT_SESSION_ID}`;
  const cancelUrl = `${returnOrigin}/founding.html?checkout=cancelled`;

  try {
    const session = await stripe.checkout.sessions.create(
      {
        mode: "payment",
        // The beta is limited to US purchasers, and CanopyChat remains the
        // merchant of record. This must be explicit because some Stripe
        // sandboxes enable Managed Payments by default.
        managed_payments: { enabled: false },
        integration_identifier: integrationIdentifier(),
        line_items: [{ price: env.STRIPE_FOUNDING_MEMBER_PRICE_ID, quantity: 1 }],
        billing_address_collection: "required",
        expires_at: expiresAt,
        success_url: successUrl,
        cancel_url: cancelUrl,
        custom_text: {
          submit: {
            message:
              "Founding Membership payments are final and non-refundable, except where required by law. This one-time payment does not begin a subscription.",
          },
        },
        // A Customer record alone carries no billing authorization — it does
        // not save a payment method or permit any future charge. It just
        // gives us a stable Stripe identity if this person is invited to a
        // separate, explicitly-authorized subscription flow later.
        customer_creation: "always",
        client_reference_id: input.internalUserId ?? checkoutReferenceId,
        metadata: {
          purchase_type: "founding_member",
          offer_version: env.OFFER_VERSION,
          checkout_reference_id: checkoutReferenceId,
          price_id: env.STRIPE_FOUNDING_MEMBER_PRICE_ID,
          ...(input.internalUserId ? { internal_user_id: input.internalUserId } : {}),
        },
      },
      { idempotencyKey: checkoutReferenceId },
    );

    if (!session.url) {
      try {
        await releaseCheckoutReservation(env.DB, checkoutReferenceId);
      } catch {
        // The reservation expires automatically even if D1 is unavailable.
      }
      return { ok: false, reason: "stripe_error", detail: "missing_session_url" };
    }

    try {
      await attachCheckoutSessionToReservation(env.DB, checkoutReferenceId, session.id);
    } catch {
      // Do not hand out a Checkout URL that the capacity ledger cannot link.
      // Expire the orphaned Stripe Session and release its local reservation
      // on a best-effort basis; either resource also expires naturally.
      try {
        await stripe.checkout.sessions.expire(session.id);
      } catch {
        // Stripe will expire it after 30 minutes even if this request fails.
      }
      try {
        await releaseCheckoutReservation(env.DB, checkoutReferenceId);
      } catch {
        // The reservation has its own short TTL.
      }
      return { ok: false, reason: "stripe_error", detail: "reservation_attach_failed" };
    }
    return { ok: true, url: session.url };
  } catch (error) {
    try {
      await releaseCheckoutReservation(env.DB, checkoutReferenceId);
    } catch {
      // The reservation expires automatically even if D1 is unavailable.
    }
    const detail = error instanceof Error ? error.message : "unknown_stripe_error";
    return { ok: false, reason: "stripe_error", detail };
  }
}
