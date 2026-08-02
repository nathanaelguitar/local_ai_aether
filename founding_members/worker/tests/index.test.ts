import { env, exports } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import { createFoundingMemberCheckout } from "../src/checkout";
import { beginWebhookEvent, getFoundingMemberBySession } from "../src/db";
import { allowedBrowserOrigin } from "../src/index";
import { lookupCheckoutSessionStatus } from "../src/sessionStatus";
import type { Env } from "../src/types";
import { fulfillWebhookEvent, verifyStripeWebhook } from "../src/webhook";
import {
  fakeCheckoutSession,
  fakeEvent,
  makeStubStripe,
  signStripePayload,
} from "./testStripe";

const MIGRATION = `
CREATE TABLE IF NOT EXISTS founding_members (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  normalized_email TEXT NOT NULL,
  internal_user_id TEXT,
  stripe_customer_id TEXT,
  stripe_checkout_session_id TEXT NOT NULL,
  stripe_payment_intent_id TEXT,
  stripe_event_id TEXT NOT NULL,
  checkout_reference_id TEXT NOT NULL,
  amount_paid INTEGER NOT NULL,
  currency TEXT NOT NULL,
  payment_status TEXT NOT NULL,
  member_status TEXT NOT NULL DEFAULT 'paid_waiting_for_beta',
  offer_version TEXT NOT NULL,
  purchased_at TEXT NOT NULL,
  fulfilled_at TEXT NOT NULL,
  beta_invited_at TEXT,
  premium_activated_at TEXT,
  premium_expires_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_session ON founding_members (stripe_checkout_session_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_event ON founding_members (stripe_event_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_payment_intent ON founding_members (stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_founding_members_email ON founding_members (normalized_email);
CREATE TABLE IF NOT EXISTS webhook_events (
  stripe_event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  received_at TEXT NOT NULL,
  outcome TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS checkout_attempts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ip_hash TEXT NOT NULL,
  attempt_type TEXT NOT NULL,
  attempted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checkout_attempts_ip ON checkout_attempts (ip_hash, attempt_type, attempted_at);
CREATE TABLE IF NOT EXISTS checkout_reservations (
  id TEXT PRIMARY KEY,
  stripe_checkout_session_id TEXT UNIQUE,
  reserved_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checkout_reservations_expiry ON checkout_reservations (expires_at);
CREATE TABLE IF NOT EXISTS email_outbox (
  id TEXT PRIMARY KEY,
  member_id TEXT NOT NULL,
  message_type TEXT NOT NULL,
  recipient TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  sent_at TEXT,
  last_error TEXT,
  FOREIGN KEY (member_id) REFERENCES founding_members(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_email_outbox_member_type ON email_outbox (member_id, message_type);
`;

const WEBHOOK_SECRET = "whsec_test_placeholder";
const SITE_ORIGIN = "https://canopychat.app";
const SELF = (
  exports as unknown as {
    default: {
      fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
    };
  }
).default;

function testEnv(): Env {
  return env as unknown as Env;
}

async function seedDb() {
  for (const statement of MIGRATION.split(";").map((value) => value.trim()).filter(Boolean)) {
    await testEnv().DB.prepare(statement).run();
  }
}

async function resetDb() {
  for (const table of [
    "email_outbox",
    "founding_members",
    "webhook_events",
    "checkout_attempts",
    "checkout_reservations",
  ]) {
    await testEnv().DB.prepare(`DELETE FROM ${table}`).run();
  }
}

async function seedMember(
  overrides: Partial<{
    id: string;
    email: string;
    sessionId: string;
    paymentIntentId: string;
    eventId: string;
    referenceId: string;
    paymentStatus: string;
    memberStatus: string;
  }> = {},
) {
  const suffix = crypto.randomUUID();
  const now = "2026-01-01T00:00:00.000Z";
  await testEnv().DB.prepare(
    `INSERT INTO founding_members (
      id, email, normalized_email, stripe_checkout_session_id,
      stripe_payment_intent_id, stripe_event_id, checkout_reference_id,
      amount_paid, currency, payment_status, member_status, offer_version,
      purchased_at, fulfilled_at, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1000, 'usd', ?, ?, 'founding_member_v1', ?, ?, ?, ?)`,
  )
    .bind(
      overrides.id ?? `member_${suffix}`,
      overrides.email ?? "existing@example.com",
      (overrides.email ?? "existing@example.com").toLowerCase(),
      overrides.sessionId ?? `cs_test_${suffix}`,
      overrides.paymentIntentId ?? `pi_test_${suffix}`,
      overrides.eventId ?? `evt_test_${suffix}`,
      overrides.referenceId ?? `ref_${suffix}`,
      overrides.paymentStatus ?? "paid",
      overrides.memberStatus ?? "paid_waiting_for_beta",
      now,
      now,
      now,
      now,
    )
    .run();
}

beforeEach(async () => {
  await seedDb();
  await resetDb();
});

describe("createFoundingMemberCheckout", () => {
  it("creates a one-time hosted Checkout Session with only server-controlled offer data", async () => {
    let capturedParams: any;
    let capturedOptions: any;
    const stripe = makeStubStripe({
      createSession: (params, options) => {
        capturedParams = params;
        capturedOptions = options;
        return fakeCheckoutSession();
      },
    });

    const result = await createFoundingMemberCheckout(stripe, testEnv(), {
      internalUserId: null,
    });

    expect(result.ok).toBe(true);
    if (result.ok) expect(result.url).toContain("checkout.stripe.com");
    expect(capturedParams.mode).toBe("payment");
    expect(capturedParams.line_items).toEqual([
      { price: "price_test_placeholder", quantity: 1 },
    ]);
    expect(capturedParams.metadata).toMatchObject({
      purchase_type: "founding_member",
      offer_version: "founding_member_v1",
      price_id: "price_test_placeholder",
    });
    expect(capturedParams.integration_identifier).toMatch(/^canopy_founding_[a-z]{8}$/);
    expect(capturedParams.managed_payments).toEqual({ enabled: false });
    expect(capturedParams.billing_address_collection).toBe("required");
    expect(capturedParams.customer_creation).toBe("always");
    expect(capturedParams.custom_text.submit.message).toContain(
      "final and non-refundable, except where required by law",
    );
    expect(capturedParams.custom_text.submit.message).toContain(
      "does not begin a subscription",
    );
    expect(capturedParams).not.toHaveProperty("payment_method_types");
    expect(capturedParams).not.toHaveProperty("subscription_data");
    expect(capturedParams).not.toHaveProperty("payment_intent_data.setup_future_usage");
    expect(capturedOptions.idempotencyKey).toBe(capturedParams.metadata.checkout_reference_id);
  });

  it("does not expose any caller-controlled price, amount, or currency field", async () => {
    let capturedParams: any;
    const stripe = makeStubStripe({
      createSession: (params) => {
        capturedParams = params;
        return fakeCheckoutSession();
      },
    });

    await createFoundingMemberCheckout(stripe, testEnv(), { internalUserId: null });

    expect(capturedParams.line_items[0].price).toBe(testEnv().STRIPE_FOUNDING_MEMBER_PRICE_ID);
    expect(capturedParams).not.toHaveProperty("amount");
    expect(capturedParams).not.toHaveProperty("currency");
  });

  it("uses an allowlisted localhost return URL only in test mode", async () => {
    let capturedParams: any;
    const stripe = makeStubStripe({
      createSession: (params) => {
        capturedParams = params;
        return fakeCheckoutSession();
      },
    });

    await createFoundingMemberCheckout(stripe, testEnv(), {
      internalUserId: null,
      returnOrigin: "http://127.0.0.1:8765",
    });
    expect(capturedParams.success_url).toBe(
      "http://127.0.0.1:8765/founding-success.html?session_id={CHECKOUT_SESSION_ID}",
    );
    expect(capturedParams.cancel_url).toBe(
      "http://127.0.0.1:8765/founding.html?checkout=cancelled",
    );

    const liveEnv = { ...testEnv(), ENVIRONMENT: "live" };
    expect(
      allowedBrowserOrigin(
        new Request("https://founding-api.canopychat.app/v1/checkout", {
          headers: { Origin: "http://127.0.0.1:8765" },
        }),
        liveEnv,
      ),
    ).toBeNull();
  });

  it("fails closed when configuration is missing or mixes live and test modes", async () => {
    const stripe = makeStubStripe({});
    const missingPrice = { ...testEnv(), STRIPE_FOUNDING_MEMBER_PRICE_ID: "" };
    const liveKeyInTest = { ...testEnv(), STRIPE_SECRET_KEY: "sk_live_wrong_environment" };

    await expect(
      createFoundingMemberCheckout(stripe, missingPrice, { internalUserId: null }),
    ).resolves.toEqual({ ok: false, reason: "misconfigured" });
    await expect(
      createFoundingMemberCheckout(stripe, liveKeyInTest, { internalUserId: null }),
    ).resolves.toEqual({ ok: false, reason: "misconfigured" });
  });

  it("returns a safe Stripe failure and releases the reserved slot", async () => {
    const stripe = makeStubStripe({
      createSession: () => {
        throw new Error("simulated Stripe outage");
      },
    });

    const result = await createFoundingMemberCheckout(stripe, testEnv(), {
      internalUserId: null,
    });
    const reservations = await testEnv().DB.prepare(
      "SELECT COUNT(*) AS count FROM checkout_reservations",
    ).first<{ count: number }>();

    expect(result.ok).toBe(false);
    expect(result).toMatchObject({ reason: "stripe_error" });
    expect(reservations?.count).toBe(0);
  });

  it("atomically reserves the final place across concurrent attempts", async () => {
    const stripe = makeStubStripe({
      createSession: () => fakeCheckoutSession(),
    });

    const results = await Promise.all([
      createFoundingMemberCheckout(stripe, testEnv(), { internalUserId: null }),
      createFoundingMemberCheckout(stripe, testEnv(), { internalUserId: null }),
    ]);

    expect(results.filter((result) => result.ok)).toHaveLength(1);
    expect(results.filter((result) => !result.ok && result.reason === "capacity_reached"))
      .toHaveLength(1);
  });

  it("does not count a fully refunded member against capacity", async () => {
    await seedMember({ memberStatus: "refunded", paymentStatus: "refunded" });
    const stripe = makeStubStripe({ createSession: () => fakeCheckoutSession() });

    const result = await createFoundingMemberCheckout(stripe, testEnv(), {
      internalUserId: null,
    });
    expect(result.ok).toBe(true);
  });
});

describe("verifyStripeWebhook", () => {
  it("rejects missing and invalid signatures", async () => {
    const { newStripeClient } = await import("../src/stripeClient");
    const stripe = newStripeClient(testEnv());

    await expect(verifyStripeWebhook(stripe, "{}", null, WEBHOOK_SECRET)).resolves.toEqual({
      ok: false,
      reason: "missing_signature",
    });
    await expect(
      verifyStripeWebhook(stripe, "{}", "t=1,v1=deadbeef", WEBHOOK_SECRET),
    ).resolves.toEqual({ ok: false, reason: "invalid_signature" });
  });

  it("accepts a correctly signed raw payload", async () => {
    const { newStripeClient } = await import("../src/stripeClient");
    const payload = JSON.stringify(fakeEvent());
    const signature = await signStripePayload(WEBHOOK_SECRET, payload);
    const result = await verifyStripeWebhook(
      newStripeClient(testEnv()),
      payload,
      signature,
      WEBHOOK_SECRET,
    );
    expect(result.ok).toBe(true);
  });
});

describe("fulfillWebhookEvent", () => {
  it("creates one member and one email job without starting Premium", async () => {
    const event = fakeEvent();
    const result = await fulfillWebhookEvent(testEnv(), event);
    const session = event.data.object as { id: string };
    const member = await getFoundingMemberBySession(testEnv().DB, session.id);
    const emailJobs = await testEnv().DB.prepare(
      "SELECT COUNT(*) AS count FROM email_outbox",
    ).first<{ count: number }>();

    expect(result.outcome).toBe("fulfilled");
    expect(member?.member_status).toBe("paid_waiting_for_beta");
    expect(member?.premium_activated_at).toBeNull();
    expect(member?.premium_expires_at).toBeNull();
    expect(member?.fulfilled_at).toBeTruthy();
    expect(emailJobs?.count).toBe(1);
  });

  it("waits for asynchronous payment settlement", async () => {
    const event = fakeEvent({
      data: { object: fakeCheckoutSession({ payment_status: "unpaid" }) },
    });
    const result = await fulfillWebhookEvent(testEnv(), event);
    const member = await getFoundingMemberBySession(
      testEnv().DB,
      (event.data.object as { id: string }).id,
    );
    expect(result.outcome).toBe("not_paid_yet");
    expect(member).toBeNull();
  });

  it("rejects a paid Checkout Session for another offer or environment", async () => {
    const wrongPrice = fakeEvent({
      data: {
        object: fakeCheckoutSession({
          metadata: {
            purchase_type: "founding_member",
            offer_version: "founding_member_v1",
            checkout_reference_id: "ref_wrong",
            price_id: "price_other",
          },
        }),
      },
    });
    const liveEvent = fakeEvent({
      data: { object: fakeCheckoutSession({ livemode: true }) },
    });

    await expect(fulfillWebhookEvent(testEnv(), wrongPrice)).resolves.toEqual({
      outcome: "rejected_offer",
    });
    await expect(fulfillWebhookEvent(testEnv(), liveEvent)).resolves.toEqual({
      outcome: "rejected_offer",
    });
  });

  it("does not fulfill a Checkout Session with a non-US billing address", async () => {
    const event = fakeEvent({
      data: {
        object: fakeCheckoutSession({
          customer_details: {
            email: "buyer@example.com",
            address: { country: "CA" },
          } as any,
        }),
      },
    });

    await expect(fulfillWebhookEvent(testEnv(), event)).resolves.toEqual({
      outcome: "rejected_offer",
    });
  });

  it("releases a capacity reservation when a session expires", async () => {
    await testEnv().DB.prepare(
      "INSERT INTO checkout_reservations (id, reserved_at, expires_at) VALUES ('ref_fake', ?, ?)",
    )
      .bind(new Date().toISOString(), new Date(Date.now() + 3_600_000).toISOString())
      .run();
    const result = await fulfillWebhookEvent(
      testEnv(),
      fakeEvent({ type: "checkout.session.expired" }),
    );
    const reservation = await testEnv().DB.prepare(
      "SELECT id FROM checkout_reservations WHERE id = 'ref_fake'",
    ).first();

    expect(result.outcome).toBe("recorded_non_fulfilling");
    expect(reservation).toBeNull();
  });

  it("is idempotent under duplicate delivery", async () => {
    const event = fakeEvent();
    const first = await fulfillWebhookEvent(testEnv(), event);
    const second = await fulfillWebhookEvent(testEnv(), event);
    const members = await testEnv().DB.prepare(
      "SELECT COUNT(*) AS count FROM founding_members",
    ).first<{ count: number }>();
    const emailJobs = await testEnv().DB.prepare(
      "SELECT COUNT(*) AS count FROM email_outbox",
    ).first<{ count: number }>();

    expect(first.outcome).toBe("fulfilled");
    expect(second.outcome).toBe("duplicate_event");
    expect(members?.count).toBe(1);
    expect(emailJobs?.count).toBe(1);
  });

  it("does not double-fulfill one session across two Stripe event types", async () => {
    const session = fakeCheckoutSession();
    const first = fakeEvent({ data: { object: session } });
    const second = fakeEvent({
      type: "checkout.session.async_payment_succeeded",
      data: { object: session },
    });

    expect((await fulfillWebhookEvent(testEnv(), first)).outcome).toBe("fulfilled");
    expect((await fulfillWebhookEvent(testEnv(), second)).outcome).toBe("already_fulfilled");
  });

  it("retries a previously started event after a transient processing failure", async () => {
    const event = fakeEvent();
    await beginWebhookEvent(testEnv().DB, event.id, event.type);

    const result = await fulfillWebhookEvent(testEnv(), event);
    expect(result.outcome).toBe("fulfilled");
  });

  it("records a late paid session for review if the cohort is already full", async () => {
    await seedMember();
    const event = fakeEvent();
    const result = await fulfillWebhookEvent(testEnv(), event);
    const member = await getFoundingMemberBySession(
      testEnv().DB,
      (event.data.object as { id: string }).id,
    );

    expect(result.outcome).toBe("fulfilled_over_capacity");
    expect(member?.member_status).toBe("paid_over_capacity_pending_review");
  });

  it("updates a member after a full refund without creating another member", async () => {
    const purchase = fakeEvent();
    await fulfillWebhookEvent(testEnv(), purchase);
    const session = purchase.data.object as { payment_intent: string; id: string };
    const refund = fakeEvent({
      type: "charge.refunded",
      data: {
        object: {
          id: "ch_test_refund",
          object: "charge",
          payment_intent: session.payment_intent,
          refunded: true,
          amount: 1000,
          amount_refunded: 1000,
        } as any,
      },
    });

    expect((await fulfillWebhookEvent(testEnv(), refund)).outcome).toBe(
      "payment_adjustment_recorded",
    );
    const member = await getFoundingMemberBySession(testEnv().DB, session.id);
    expect(member?.member_status).toBe("refunded");
    expect(member?.payment_status).toBe("refunded");
  });
});

describe("lookupCheckoutSessionStatus", () => {
  it("reports confirmed only after webhook fulfillment", async () => {
    const event = fakeEvent();
    await fulfillWebhookEvent(testEnv(), event);
    const sessionId = (event.data.object as { id: string }).id;
    const status = await lookupCheckoutSessionStatus(makeStubStripe({}), testEnv(), sessionId);
    expect(status).toEqual({
      status: "confirmed",
      member_status: "paid_waiting_for_beta",
    });
  });

  it("reports processing—not confirmed—from Stripe alone", async () => {
    const stripe = makeStubStripe({
      retrieveSession: () => fakeCheckoutSession({ payment_status: "paid" }),
    });
    const status = await lookupCheckoutSessionStatus(stripe, testEnv(), "cs_test_pending");
    expect(status).toEqual({ status: "processing" });
  });

  it("does not reveal sessions for another offer", async () => {
    const stripe = makeStubStripe({
      retrieveSession: () => fakeCheckoutSession({ metadata: { purchase_type: "other" } }),
    });
    await expect(
      lookupCheckoutSessionStatus(stripe, testEnv(), "cs_test_other"),
    ).resolves.toEqual({ status: "not_found" });
  });

  it("returns needs_review for paid records requiring manual attention", async () => {
    await seedMember({
      sessionId: "cs_test_review",
      memberStatus: "paid_over_capacity_pending_review",
    });
    await expect(
      lookupCheckoutSessionStatus(makeStubStripe({}), testEnv(), "cs_test_review"),
    ).resolves.toEqual({
      status: "needs_review",
      member_status: "paid_over_capacity_pending_review",
    });
  });

  it("reports not_found for an unknown Stripe session", async () => {
    const stripe = makeStubStripe({
      retrieveSession: () => {
        throw new Error("No such session");
      },
    });
    await expect(
      lookupCheckoutSessionStatus(stripe, testEnv(), "cs_test_unknown"),
    ).resolves.toEqual({ status: "not_found" });
  });
});

describe("HTTP router", () => {
  const localApi = "http://localhost";
  const browserHeaders = {
    Origin: SITE_ORIGIN,
    "Content-Type": "application/json",
    "CF-Connecting-IP": "9.9.9.9",
  };

  it("reports healthy only with valid test-mode configuration and D1", async () => {
    const response = await SELF.fetch("https://founding-api.canopychat.app/health");
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ status: "ok", environment: "test" });
  });

  it("rejects missing and invalid webhook signatures without leaking secrets", async () => {
    const missing = await SELF.fetch("https://founding-api.canopychat.app/v1/webhook", {
      method: "POST",
      body: "{}",
    });
    const invalid = await SELF.fetch("https://founding-api.canopychat.app/v1/webhook", {
      method: "POST",
      headers: { "Stripe-Signature": "t=1,v1=deadbeef" },
      body: "{}",
    });

    expect(missing.status).toBe(400);
    expect(invalid.status).toBe(400);
    expect(await invalid.text()).not.toContain(WEBHOOK_SECRET);
  });

  it("fulfills a validly signed webhook end to end", async () => {
    const event = fakeEvent();
    const payload = JSON.stringify(event);
    const response = await SELF.fetch("https://founding-api.canopychat.app/v1/webhook", {
      method: "POST",
      headers: { "Stripe-Signature": await signStripePayload(WEBHOOK_SECRET, payload) },
      body: payload,
    });

    expect(response.status).toBe(200);
    expect(
      await getFoundingMemberBySession(
        testEnv().DB,
        (event.data.object as { id: string }).id,
      ),
    ).not.toBeNull();
  });

  it("rejects checkout and status requests from any other browser origin", async () => {
    const checkout = await SELF.fetch("https://founding-api.canopychat.app/v1/checkout", {
      method: "POST",
      headers: { ...browserHeaders, Origin: "https://evil.example" },
      body: "{}",
    });
    const status = await SELF.fetch(
      "https://founding-api.canopychat.app/v1/checkout-session?session_id=cs_test_valid",
      { headers: { Origin: "https://evil.example" } },
    );
    expect(checkout.status).toBe(403);
    expect(status.status).toBe(403);
  });

  it("allows the exact localhost preview origin only in test mode", async () => {
    const preflight = await SELF.fetch("https://founding-api.canopychat.app/v1/checkout", {
      method: "OPTIONS",
      headers: {
        Origin: "http://127.0.0.1:8765",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
      },
    });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("Access-Control-Allow-Origin")).toBe(
      "http://127.0.0.1:8765",
    );

    const wrongPort = await SELF.fetch("https://founding-api.canopychat.app/v1/checkout", {
      method: "OPTIONS",
      headers: { Origin: "http://127.0.0.1:9999" },
    });
    expect(wrongPort.status).toBe(403);
  });

  it("rejects browser-supplied checkout fields", async () => {
    const response = await SELF.fetch(`${localApi}/v1/checkout`, {
      method: "POST",
      headers: browserHeaders,
      body: JSON.stringify({ price: "price_attacker" }),
    });
    expect(response.status).toBe(400);
  });

  it("rate-limits repeated checkout attempts before Stripe", async () => {
    await seedMember();
    let response: Response | undefined;
    for (let index = 0; index < 11; index += 1) {
      response = await SELF.fetch(`${localApi}/v1/checkout`, {
        method: "POST",
        headers: browserHeaders,
        body: "{}",
      });
    }
    expect(response?.status).toBe(429);
  });

  it("returns capacity_reached before contacting Stripe", async () => {
    await seedMember();
    const response = await SELF.fetch(`${localApi}/v1/checkout`, {
      method: "POST",
      headers: { ...browserHeaders, "CF-Connecting-IP": "5.5.5.5" },
      body: "{}",
    });
    expect(response.status).toBe(409);
  });

  it("rejects checkout when Cloudflare cannot place the buyer in the US", async () => {
    const response = await SELF.fetch("https://founding-api.canopychat.app/v1/checkout", {
      method: "POST",
      headers: { ...browserHeaders, "CF-Connecting-IP": "203.0.113.10" },
      body: "{}",
    });
    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toEqual({ error: "country_not_supported" });
  });

  it("validates session IDs and methods", async () => {
    const malformed = await SELF.fetch(
      "https://founding-api.canopychat.app/v1/checkout-session?session_id=not-real",
      { headers: { Origin: SITE_ORIGIN, "CF-Connecting-IP": "4.4.4.4" } },
    );
    const wrongMethod = await SELF.fetch("https://founding-api.canopychat.app/v1/checkout", {
      method: "GET",
      headers: { Origin: SITE_ORIGIN },
    });
    expect(malformed.status).toBe(400);
    expect(wrongMethod.status).toBe(405);
    expect(wrongMethod.headers.get("Allow")).toBe("POST");
  });

  it("returns 404 for unknown routes", async () => {
    const response = await SELF.fetch("https://founding-api.canopychat.app/nope");
    expect(response.status).toBe(404);
  });
});
