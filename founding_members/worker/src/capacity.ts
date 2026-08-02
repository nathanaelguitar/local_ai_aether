import { countActiveFoundingMembers } from "./db";

// Stripe's minimum is 30 minutes from its own Session creation time. Use 35
// minutes so D1 reservation latency cannot make expires_at fall below that
// minimum by the time Stripe receives the request.
const CHECKOUT_SESSION_SECONDS = 35 * 60;
const RESERVATION_GRACE_SECONDS = 5 * 60;

export function configuredCapacity(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const parsed = Number.parseInt(value, 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

export function checkoutExpiresAt(now = Date.now()): number {
  return Math.floor(now / 1000) + CHECKOUT_SESSION_SECONDS;
}

export async function reserveCheckoutCapacity(
  db: D1Database,
  capacity: number,
  reservationId: string,
  stripeExpiresAt: number,
): Promise<boolean> {
  const now = new Date().toISOString();
  const reservationExpiry = new Date(
    (stripeExpiresAt + RESERVATION_GRACE_SECONDS) * 1000,
  ).toISOString();

  await db
    .prepare("DELETE FROM checkout_reservations WHERE expires_at <= ?")
    .bind(now)
    .run();

  const result = await db
    .prepare(
      `INSERT INTO checkout_reservations (id, reserved_at, expires_at)
       SELECT ?, ?, ?
       WHERE (
         (SELECT COUNT(*) FROM founding_members WHERE member_status != 'refunded') +
         (SELECT COUNT(*) FROM checkout_reservations WHERE expires_at > ?)
       ) < ?`,
    )
    .bind(reservationId, now, reservationExpiry, now, capacity)
    .run();

  return (result.meta.changes ?? 0) === 1;
}

export async function attachCheckoutSessionToReservation(
  db: D1Database,
  reservationId: string,
  sessionId: string,
): Promise<void> {
  const result = await db
    .prepare(
      "UPDATE checkout_reservations SET stripe_checkout_session_id = ? WHERE id = ?",
    )
    .bind(sessionId, reservationId)
    .run();
  if ((result.meta.changes ?? 0) !== 1) {
    throw new Error("checkout_reservation_not_found");
  }
}

export async function releaseCheckoutReservation(
  db: D1Database,
  reservationId: string | null,
): Promise<void> {
  if (!reservationId) return;
  await db.prepare("DELETE FROM checkout_reservations WHERE id = ?").bind(reservationId).run();
}

export async function hasActiveCheckoutReservation(
  db: D1Database,
  reservationId: string | null,
): Promise<boolean> {
  if (!reservationId) return false;
  const row = await db
    .prepare(
      "SELECT 1 AS present FROM checkout_reservations WHERE id = ? AND expires_at > ?",
    )
    .bind(reservationId, new Date().toISOString())
    .first<{ present: number }>();
  return row?.present === 1;
}

export async function isCapacityAlreadyFull(
  db: D1Database,
  capacity: number,
): Promise<boolean> {
  return (await countActiveFoundingMembers(db)) >= capacity;
}
