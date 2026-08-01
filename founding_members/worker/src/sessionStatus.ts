import type Stripe from "stripe";
import { getFoundingMemberBySession } from "./db";
import type { Env } from "./types";
import { sessionMatchesFoundingOffer } from "./webhook";

export interface CheckoutSessionStatus {
  status: "confirmed" | "processing" | "needs_review" | "not_paid" | "not_found";
  member_status?: string;
}

const REVIEW_STATUSES = new Set([
  "paid_over_capacity_pending_review",
  "partially_refunded_pending_review",
  "disputed_pending_review",
  "refunded",
]);

/**
 * Used only by the confirmation page. The D1 row created by the verified
 * webhook is authoritative — the success-page redirect itself proves
 * nothing. If the webhook hasn't landed yet, this falls back to a
 * read-only Stripe lookup so the visitor sees "processing" rather than a
 * false negative, but that fallback never grants entitlement by itself;
 * it only ever returns "processing" or "not_paid", never "confirmed".
 */
export async function lookupCheckoutSessionStatus(
  stripe: Stripe,
  env: Env,
  sessionId: string,
): Promise<CheckoutSessionStatus> {
  const member = await getFoundingMemberBySession(env.DB, sessionId);
  if (member) {
    if (REVIEW_STATUSES.has(member.member_status)) {
      return { status: "needs_review", member_status: member.member_status };
    }
    return { status: "confirmed", member_status: member.member_status };
  }

  try {
    const session = await stripe.checkout.sessions.retrieve(sessionId);
    if (!sessionMatchesFoundingOffer(session, env)) {
      return { status: "not_found" };
    }
    if (session.payment_status === "paid") {
      // Paid at Stripe but the webhook has not fulfilled it yet (replication
      // lag or in-flight delivery). Do not report "confirmed" — that status
      // may only ever come from our own D1 record.
      return { status: "processing" };
    }
    if (session.status === "expired") {
      return { status: "not_found" };
    }
    return { status: "not_paid" };
  } catch {
    return { status: "not_found" };
  }
}
