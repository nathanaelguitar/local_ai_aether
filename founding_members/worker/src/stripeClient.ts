import Stripe from "stripe";
import type { Env } from "./types";

/**
 * Cloudflare Workers has no Node net/http stack, so stripe-node must be
 * pointed at the fetch-based HTTP client. Webhook signature verification
 * must go through the SubtleCrypto provider and the async constructEvent
 * variant for the same reason — see stripeWebhook.ts.
 */
export function newStripeClient(env: Env): Stripe {
  return new Stripe(env.STRIPE_SECRET_KEY, {
    apiVersion: "2026-07-29.dahlia",
    httpClient: Stripe.createFetchHttpClient(),
  });
}
