export interface Env {
  DB: D1Database;
  STRIPE_SECRET_KEY: string;
  STRIPE_WEBHOOK_SECRET: string;
  STRIPE_FOUNDING_MEMBER_PRICE_ID: string;
  RATE_LIMIT_SALT: string;
  ENVIRONMENT: string;
  LIVE_PAYMENTS_ENABLED: string;
  PUBLIC_SITE_URL: string;
  OFFER_VERSION: string;
  /** Required positive integer. Checkout stays disabled when absent/invalid. */
  FOUNDING_MEMBER_CAPACITY: string;
}

export interface FoundingMemberRow {
  id: string;
  email: string;
  normalized_email: string;
  internal_user_id: string | null;
  stripe_customer_id: string | null;
  stripe_checkout_session_id: string;
  stripe_payment_intent_id: string | null;
  stripe_event_id: string;
  checkout_reference_id: string;
  amount_paid: number;
  currency: string;
  payment_status: string;
  member_status: string;
  offer_version: string;
  purchased_at: string;
  fulfilled_at: string;
  beta_invited_at: string | null;
  premium_activated_at: string | null;
  premium_expires_at: string | null;
  created_at: string;
  updated_at: string;
}

export type MemberStatus =
  | "paid_waiting_for_beta"
  | "paid_over_capacity_pending_review"
  | "partially_refunded_pending_review"
  | "disputed_pending_review"
  | "beta_invited"
  | "premium_active"
  | "premium_expired"
  | "refunded";
