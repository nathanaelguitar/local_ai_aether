import type Stripe from "stripe";

/**
 * Minimal stand-ins for the two Stripe surfaces this Worker touches
 * (Checkout Sessions, webhook signature verification). Using these instead
 * of the real Stripe API keeps tests deterministic and independent of a
 * live Stripe test-mode account, which nothing in this environment has
 * credentials for.
 */
export function makeStubStripe(overrides: {
  createSession?: (
    params: Stripe.Checkout.SessionCreateParams,
    options?: Stripe.RequestOptions,
  ) => Stripe.Checkout.Session | Promise<Stripe.Checkout.Session>;
  retrieveSession?: (id: string) => Stripe.Checkout.Session | Promise<Stripe.Checkout.Session>;
  expireSession?: (id: string) => Stripe.Checkout.Session | Promise<Stripe.Checkout.Session>;
}): Stripe {
  return {
    checkout: {
      sessions: {
        create: async (
          params: Stripe.Checkout.SessionCreateParams,
          options?: Stripe.RequestOptions,
        ) => {
          if (!overrides.createSession) {
            throw new Error("createSession not stubbed");
          }
          return overrides.createSession(params, options);
        },
        retrieve: async (id: string) => {
          if (!overrides.retrieveSession) {
            throw new Error("retrieveSession not stubbed");
          }
          return overrides.retrieveSession(id);
        },
        expire: async (id: string) => {
          if (!overrides.expireSession) {
            throw new Error("expireSession not stubbed");
          }
          return overrides.expireSession(id);
        },
      },
    },
  } as unknown as Stripe;
}

export function fakeCheckoutSession(
  overrides: Partial<Stripe.Checkout.Session> = {},
): Stripe.Checkout.Session {
  return {
    id: "cs_test_" + Math.random().toString(36).slice(2),
    object: "checkout.session",
    url: "https://checkout.stripe.com/c/pay/cs_test_fake",
    mode: "payment",
    livemode: false,
    payment_status: "paid",
    status: "complete",
    customer: "cus_test_fake",
    payment_intent: "pi_test_fake",
    amount_total: 1000,
    currency: "usd",
    customer_details: {
      email: "buyer@example.com",
      address: { country: "US" },
    } as Stripe.Checkout.Session.CustomerDetails,
    customer_email: null,
    metadata: {
      purchase_type: "founding_member",
      offer_version: "founding_member_v1",
      checkout_reference_id: "ref_fake",
      price_id: "price_test_placeholder",
    },
    ...overrides,
  } as Stripe.Checkout.Session;
}

export function fakeEvent(overrides: Partial<Stripe.Event> = {}): Stripe.Event {
  return {
    id: "evt_test_" + Math.random().toString(36).slice(2),
    object: "event",
    livemode: false,
    type: "checkout.session.completed",
    created: Math.floor(Date.now() / 1000),
    data: { object: fakeCheckoutSession() },
    ...overrides,
  } as Stripe.Event;
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Builds a valid Stripe-Signature header for a raw payload, matching Stripe's `t=...,v1=...` scheme. */
export async function signStripePayload(secret: string, payload: string, timestamp = Math.floor(Date.now() / 1000)): Promise<string> {
  const signedPayload = `${timestamp}.${payload}`;
  const signature = await hmacSha256Hex(secret, signedPayload);
  return `t=${timestamp},v1=${signature}`;
}
