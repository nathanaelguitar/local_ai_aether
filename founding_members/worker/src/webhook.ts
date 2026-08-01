import Stripe from "stripe";
import {
  configuredCapacity,
  hasActiveCheckoutReservation,
  isCapacityAlreadyFull,
} from "./capacity";
import {
  beginWebhookEvent,
  commitFoundingMemberFulfillment,
  recordWebhookEventOutcome,
  updateMemberAfterPaymentAdjustment,
} from "./db";
import type { Env } from "./types";

const FULFILLING_EVENT_TYPES = new Set([
  "checkout.session.completed",
  "checkout.session.async_payment_succeeded",
]);

const TERMINAL_NON_FULFILLING_EVENT_TYPES = new Set([
  "checkout.session.async_payment_failed",
  "checkout.session.expired",
]);

export type VerifyResult =
  | { ok: true; event: Stripe.Event }
  | { ok: false; reason: "invalid_signature" | "missing_signature" };

/** Verify Stripe against the exact raw body before reading any event fields. */
export async function verifyStripeWebhook(
  stripe: Stripe,
  rawBody: string,
  signatureHeader: string | null,
  webhookSecret: string,
): Promise<VerifyResult> {
  if (!signatureHeader) return { ok: false, reason: "missing_signature" };

  try {
    const event = await stripe.webhooks.constructEventAsync(
      rawBody,
      signatureHeader,
      webhookSecret,
      undefined,
      Stripe.createSubtleCryptoProvider(),
    );
    return { ok: true, event };
  } catch {
    return { ok: false, reason: "invalid_signature" };
  }
}

export interface FulfillOutcome {
  outcome:
    | "fulfilled"
    | "fulfilled_over_capacity"
    | "already_fulfilled"
    | "not_paid_yet"
    | "duplicate_event"
    | "recorded_non_fulfilling"
    | "ignored_event_type"
    | "rejected_offer"
    | "payment_adjustment_recorded"
    | "payment_adjustment_member_not_found";
}

function stringId(value: string | { id: string } | null): string | null {
  if (typeof value === "string") return value;
  return value?.id ?? null;
}

function checkoutReferenceId(session: Stripe.Checkout.Session): string | null {
  return session.metadata?.checkout_reference_id?.trim() || null;
}

/**
 * A verified Stripe signature proves the event came from Stripe, not that it
 * belongs to this particular offer. Validate every server-stamped field before
 * a session is allowed to create a Founding Member record.
 */
export function sessionMatchesFoundingOffer(
  session: Stripe.Checkout.Session,
  env: Env,
): boolean {
  const expectedLiveMode = env.ENVIRONMENT === "live";
  return (
    session.mode === "payment" &&
    session.livemode === expectedLiveMode &&
    session.currency?.toLowerCase() === "usd" &&
    session.metadata?.purchase_type === "founding_member" &&
    session.metadata?.offer_version === env.OFFER_VERSION &&
    session.metadata?.price_id === env.STRIPE_FOUNDING_MEMBER_PRICE_ID &&
    session.customer_details?.address?.country?.toUpperCase() === "US" &&
    Boolean(checkoutReferenceId(session))
  );
}

async function handlePaymentAdjustment(
  env: Env,
  event: Stripe.Event,
): Promise<FulfillOutcome | null> {
  if (event.type === "charge.refunded") {
    const charge = event.data.object as Stripe.Charge;
    const paymentIntentId = stringId(charge.payment_intent);
    if (!paymentIntentId) {
      await recordWebhookEventOutcome(
        env.DB,
        event.id,
        event.type,
        "payment_adjustment_member_not_found",
      );
      return { outcome: "payment_adjustment_member_not_found" };
    }

    const fullyRefunded = charge.refunded || charge.amount_refunded >= charge.amount;
    const updated = await updateMemberAfterPaymentAdjustment(
      env,
      event.id,
      event.type,
      paymentIntentId,
      fullyRefunded ? "refunded" : "partially_refunded",
      fullyRefunded ? "refunded" : "partially_refunded_pending_review",
    );
    return {
      outcome: updated
        ? "payment_adjustment_recorded"
        : "payment_adjustment_member_not_found",
    };
  }

  if (event.type === "charge.dispute.created") {
    const dispute = event.data.object as Stripe.Dispute;
    const paymentIntentId = stringId(dispute.payment_intent);
    if (!paymentIntentId) {
      await recordWebhookEventOutcome(
        env.DB,
        event.id,
        event.type,
        "payment_adjustment_member_not_found",
      );
      return { outcome: "payment_adjustment_member_not_found" };
    }

    const updated = await updateMemberAfterPaymentAdjustment(
      env,
      event.id,
      event.type,
      paymentIntentId,
      "disputed",
      "disputed_pending_review",
    );
    return {
      outcome: updated
        ? "payment_adjustment_recorded"
        : "payment_adjustment_member_not_found",
    };
  }

  return null;
}

/**
 * Process one verified event. Stripe delivers events at least once, and a
 * second event type may reference the same Checkout Session. Database unique
 * constraints plus the retryable event ledger make both cases safe.
 */
export async function fulfillWebhookEvent(
  env: Env,
  event: Stripe.Event,
): Promise<FulfillOutcome> {
  const begin = await beginWebhookEvent(env.DB, event.id, event.type);
  if (begin === "duplicate") return { outcome: "duplicate_event" };

  const paymentAdjustment = await handlePaymentAdjustment(env, event);
  if (paymentAdjustment) return paymentAdjustment;

  if (TERMINAL_NON_FULFILLING_EVENT_TYPES.has(event.type)) {
    const session = event.data.object as Stripe.Checkout.Session;
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "recorded_non_fulfilling",
      checkoutReferenceId(session),
    );
    return { outcome: "recorded_non_fulfilling" };
  }

  if (!FULFILLING_EVENT_TYPES.has(event.type)) {
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "ignored_event_type",
    );
    return { outcome: "ignored_event_type" };
  }

  const session = event.data.object as Stripe.Checkout.Session;
  const referenceId = checkoutReferenceId(session);

  if (!sessionMatchesFoundingOffer(session, env)) {
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "rejected_offer",
      referenceId,
    );
    return { outcome: "rejected_offer" };
  }

  // Completed can precede final settlement for asynchronous methods. Their
  // later async_payment_succeeded event is what fulfills the purchase.
  if (session.payment_status !== "paid") {
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "not_paid_yet",
    );
    return { outcome: "not_paid_yet" };
  }

  const email = session.customer_details?.email ?? session.customer_email;
  if (!email?.trim()) {
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "missing_email",
      referenceId,
    );
    return { outcome: "rejected_offer" };
  }

  const capacity = configuredCapacity(env.FOUNDING_MEMBER_CAPACITY);
  if (!capacity) throw new Error("invalid_capacity_configuration");

  // A valid reservation owns a cohort slot. A very late payment can arrive
  // after its reservation has expired; accept it only while real capacity
  // remains, otherwise record it for manual review rather than losing a paid
  // purchaser or silently overselling.
  const hasReservation = await hasActiveCheckoutReservation(env.DB, referenceId);
  const overCapacity = !hasReservation && (await isCapacityAlreadyFull(env.DB, capacity));
  const memberStatus = overCapacity
    ? "paid_over_capacity_pending_review"
    : "paid_waiting_for_beta";
  const eventOutcome = overCapacity ? "fulfilled_over_capacity" : "fulfilled";

  const created = await commitFoundingMemberFulfillment(env.DB, event.type, eventOutcome, {
    id: crypto.randomUUID(),
    email: email.trim(),
    internalUserId: session.metadata?.internal_user_id ?? null,
    stripeCustomerId: stringId(session.customer),
    stripeCheckoutSessionId: session.id,
    stripePaymentIntentId: stringId(session.payment_intent),
    stripeEventId: event.id,
    checkoutReferenceId: referenceId!,
    amountPaid: session.amount_total ?? 0,
    currency: session.currency!.toLowerCase(),
    paymentStatus: session.payment_status,
    memberStatus,
    offerVersion: env.OFFER_VERSION,
    purchasedAt: new Date(event.created * 1000).toISOString(),
  });

  if (created === "already_fulfilled") {
    await recordWebhookEventOutcome(
      env.DB,
      event.id,
      event.type,
      "already_fulfilled",
      referenceId,
    );
    return { outcome: "already_fulfilled" };
  }

  return { outcome: eventOutcome };
}
