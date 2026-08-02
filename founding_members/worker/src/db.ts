import type { Env, FoundingMemberRow } from "./types";

function isUniqueConstraintError(error: unknown): boolean {
  return error instanceof Error && /UNIQUE constraint failed/i.test(error.message);
}

export async function countActiveFoundingMembers(db: D1Database): Promise<number> {
  const row = await db
    .prepare("SELECT COUNT(*) as cnt FROM founding_members WHERE member_status != 'refunded'")
    .first<{ cnt: number }>();
  return row?.cnt ?? 0;
}

export type BeginWebhookResult = "new" | "retry" | "duplicate";

/**
 * Starts an idempotent webhook delivery. A previous `received` row is
 * deliberately retryable: if fulfillment threw after writing that row,
 * Stripe's next delivery must get another chance instead of being discarded.
 */
export async function beginWebhookEvent(
  db: D1Database,
  eventId: string,
  eventType: string,
): Promise<BeginWebhookResult> {
  const result = await db
    .prepare(
      `INSERT INTO webhook_events (stripe_event_id, event_type, received_at, outcome)
       VALUES (?, ?, ?, 'received')
       ON CONFLICT(stripe_event_id) DO NOTHING`,
    )
    .bind(eventId, eventType, new Date().toISOString())
    .run();

  if ((result.meta.changes ?? 0) === 1) return "new";

  const existing = await db
    .prepare("SELECT outcome FROM webhook_events WHERE stripe_event_id = ?")
    .bind(eventId)
    .first<{ outcome: string }>();
  return existing?.outcome === "received" || existing?.outcome === "processing_failed"
    ? "retry"
    : "duplicate";
}

export async function recordWebhookEventOutcome(
  db: D1Database,
  eventId: string,
  eventType: string,
  outcome: string,
  reservationId: string | null = null,
): Promise<void> {
  const statements = [
    db
      .prepare(
        `INSERT INTO webhook_events (stripe_event_id, event_type, received_at, outcome)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(stripe_event_id) DO UPDATE SET outcome = excluded.outcome`,
      )
      .bind(eventId, eventType, new Date().toISOString(), outcome),
  ];
  if (reservationId) {
    statements.push(
      db.prepare("DELETE FROM checkout_reservations WHERE id = ?").bind(reservationId),
    );
  }
  await db.batch(statements);
}

export interface InsertFoundingMemberInput {
  id: string;
  email: string;
  internalUserId: string | null;
  stripeCustomerId: string | null;
  stripeCheckoutSessionId: string;
  stripePaymentIntentId: string | null;
  stripeEventId: string;
  checkoutReferenceId: string;
  amountPaid: number;
  currency: string;
  paymentStatus: string;
  memberStatus: string;
  offerVersion: string;
  purchasedAt: string;
}

/**
 * Atomically creates the member, queues the future onboarding email, marks
 * the event complete, and releases the capacity reservation. D1 batch()
 * rolls the entire sequence back if any statement fails.
 */
export async function commitFoundingMemberFulfillment(
  db: D1Database,
  eventType: string,
  eventOutcome: string,
  input: InsertFoundingMemberInput,
): Promise<"created" | "already_fulfilled"> {
  const now = new Date().toISOString();
  const normalizedEmail = input.email.trim().toLowerCase();

  try {
    await db.batch([
      db
        .prepare(
          `INSERT INTO founding_members (
            id, email, normalized_email, internal_user_id, stripe_customer_id,
            stripe_checkout_session_id, stripe_payment_intent_id, stripe_event_id,
            checkout_reference_id, amount_paid, currency, payment_status,
            member_status, offer_version, purchased_at, fulfilled_at,
            beta_invited_at, premium_activated_at, premium_expires_at,
            created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)`,
        )
        .bind(
          input.id,
          input.email,
          normalizedEmail,
          input.internalUserId,
          input.stripeCustomerId,
          input.stripeCheckoutSessionId,
          input.stripePaymentIntentId,
          input.stripeEventId,
          input.checkoutReferenceId,
          input.amountPaid,
          input.currency,
          input.paymentStatus,
          input.memberStatus,
          input.offerVersion,
          input.purchasedAt,
          now,
          now,
          now,
        ),
      db
        .prepare(
          `INSERT INTO email_outbox (
            id, member_id, message_type, recipient, status, attempts, created_at,
            updated_at, next_attempt_at
          ) VALUES (?, ?, 'founding_member_confirmation', ?, 'pending', 0, ?, ?, ?)`,
        )
        .bind(crypto.randomUUID(), input.id, input.email, now, now, now),
      db
        .prepare(
          `INSERT INTO webhook_events (stripe_event_id, event_type, received_at, outcome)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(stripe_event_id) DO UPDATE SET outcome = excluded.outcome`,
        )
        .bind(input.stripeEventId, eventType, now, eventOutcome),
      db
        .prepare("DELETE FROM checkout_reservations WHERE id = ?")
        .bind(input.checkoutReferenceId),
    ]);
    return "created";
  } catch (error) {
    if (isUniqueConstraintError(error)) {
      const existingBySession = await getFoundingMemberBySession(
        db,
        input.stripeCheckoutSessionId,
      );
      const existingByPaymentIntent = input.stripePaymentIntentId
        ? await getFoundingMemberByPaymentIntent(db, input.stripePaymentIntentId)
        : null;
      if (existingBySession || existingByPaymentIntent) return "already_fulfilled";
    }
    throw error;
  }
}

export async function getFoundingMemberBySession(
  db: D1Database,
  sessionId: string,
): Promise<FoundingMemberRow | null> {
  const row = await db
    .prepare("SELECT * FROM founding_members WHERE stripe_checkout_session_id = ?")
    .bind(sessionId)
    .first<FoundingMemberRow>();
  return row ?? null;
}

export async function getFoundingMemberByPaymentIntent(
  db: D1Database,
  paymentIntentId: string,
): Promise<FoundingMemberRow | null> {
  const row = await db
    .prepare("SELECT * FROM founding_members WHERE stripe_payment_intent_id = ?")
    .bind(paymentIntentId)
    .first<FoundingMemberRow>();
  return row ?? null;
}

export async function updateMemberAfterPaymentAdjustment(
  env: Env,
  eventId: string,
  eventType: string,
  paymentIntentId: string,
  paymentStatus: string,
  memberStatus: string,
): Promise<boolean> {
  const member = await getFoundingMemberByPaymentIntent(env.DB, paymentIntentId);
  if (!member) {
    await recordWebhookEventOutcome(
      env.DB,
      eventId,
      eventType,
      "payment_adjustment_member_not_found",
    );
    return false;
  }

  await env.DB.batch([
    env.DB
      .prepare(
        "UPDATE founding_members SET payment_status = ?, member_status = ?, updated_at = ? WHERE id = ?",
      )
      .bind(paymentStatus, memberStatus, new Date().toISOString(), member.id),
    env.DB
      .prepare("UPDATE webhook_events SET outcome = 'payment_adjustment_recorded' WHERE stripe_event_id = ?")
      .bind(eventId),
  ]);
  return true;
}

export interface PendingEmailRow {
  outbox_id: string;
  member_id: string;
  recipient: string;
  attempts: number;
  email: string;
  purchased_at: string;
  member_status: string;
}

/** Claim one outbox row; stale claims can be recovered after an isolate dies. */
export async function claimPendingEmail(
  db: D1Database,
  now: string,
  staleBefore: string,
): Promise<PendingEmailRow | null> {
  const candidate = await db
    .prepare(
      `SELECT o.id AS outbox_id, o.member_id, o.recipient, o.attempts,
              m.email, m.purchased_at, m.member_status
       FROM email_outbox o
       JOIN founding_members m ON m.id = o.member_id
       WHERE o.message_type = 'founding_member_confirmation'
         AND o.attempts < 5
         AND (
           o.status = 'pending'
           OR (o.status = 'failed' AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?))
           OR (o.status = 'sending' AND o.updated_at <= ?)
         )
       ORDER BY o.created_at ASC
       LIMIT 1`,
    )
    .bind(now, staleBefore)
    .first<PendingEmailRow>();
  if (!candidate) return null;

  const claimed = await db
    .prepare(
      `UPDATE email_outbox
       SET status = 'sending', attempts = attempts + 1, updated_at = ?
       WHERE id = ?
         AND attempts < 5
         AND (
           status = 'pending'
           OR (status = 'failed' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
           OR (status = 'sending' AND updated_at <= ?)
         )`,
    )
    .bind(now, candidate.outbox_id, now, staleBefore)
    .run();
  return (claimed.meta.changes ?? 0) === 1
    ? { ...candidate, attempts: candidate.attempts + 1 }
    : null;
}

export async function markEmailSent(db: D1Database, outboxId: string, now: string): Promise<void> {
  await db
    .prepare(
      `UPDATE email_outbox
       SET status = 'sent', sent_at = ?, updated_at = ?, last_error = NULL, next_attempt_at = NULL
       WHERE id = ?`,
    )
    .bind(now, now, outboxId)
    .run();
}

export async function markEmailFailed(
  db: D1Database,
  outboxId: string,
  attempts: number,
  error: string,
  now: string,
): Promise<void> {
  const delaySeconds = [60, 300, 900, 3_600, 21_600][Math.min(attempts - 1, 4)] ?? 21_600;
  const nextAttemptAt = new Date(Date.now() + delaySeconds * 1_000).toISOString();
  await db
    .prepare(
      `UPDATE email_outbox
       SET status = 'failed', updated_at = ?, last_error = ?, next_attempt_at = ?
       WHERE id = ?`,
    )
    .bind(now, error.slice(0, 255), nextAttemptAt, outboxId)
    .run();
}
