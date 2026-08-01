# Founding Member payments

This integration connects the existing CanopyChat Founding Members page to
Stripe-hosted Checkout for a **one-time $10 USD payment**. It does not create a
subscription, trial subscription, recurring Price, scheduled charge, or future
payment authorization.

The three-month Premium period begins only when beta access is activated.
Payment fulfillment deliberately leaves `premium_activated_at` and
`premium_expires_at` `NULL`.

## Current deployment status

- Static site: GitHub Pages at `https://canopychat.app`
- Payment API: Cloudflare Worker `canopy-founding-members`, deployed in test mode
- API hostname: `https://founding-api.canopychat.app` (active; health check passes)
- D1 database: `canopy-founding-members`
  (`b5f9dea2-5fab-4b6e-83fd-4007b2c30eb5`), created and migrated
- Stripe mode: test only; credentials are stored in a mode-`0600`, gitignored
  local `.env` and are not committed or deployed
- Stripe test Product: `prod_UzgQBh4WeIVtlK`
- Stripe test one-time Price: `price_1Tzh3EEqEljz4LpyWSWKxLD4`
- Stripe test webhook: `we_1Tzh4QEqEljz4LpyPL6GBgOB`, enabled and delivering
  successfully to the custom Worker hostname
- Live payments: hard-disabled unless `ENVIRONMENT=live` and
  `LIVE_PAYMENTS_ENABLED=true` are both deliberately configured with live-mode
  resources

Do not publish the connected CTA or enable live payments until the go-live
checklist below is complete.

## Architecture

```text
canopy_publicsite (static HTML/CSS/JS on GitHub Pages)
  founding.html
    assets/founding.js
        POST /v1/checkout
              |
              v
Cloudflare Worker: canopy-founding-members
  POST /v1/checkout          creates one hosted Checkout Session
  POST /v1/webhook           verifies and fulfills Stripe events
  GET  /v1/checkout-session  reports confirmation-page status
  GET  /health               checks configuration and D1
              |
              v
Cloudflare D1: canopy-founding-members
  founding_members
  webhook_events
  checkout_reservations
  checkout_attempts
  email_outbox
```

The payment Worker and database are separate from `canopy-model-delivery`.
Stripe secrets never share bindings with model-delivery credentials.

There is currently no authentication, email provider, or analytics provider.
Stripe’s collected email is the initial member identity; `internal_user_id`
remains nullable for future account claiming.

## Exact payment flow

1. A visitor selects the existing Founding Member CTA.
2. The browser disables the CTA, announces a loading state, and sends an empty
   JSON object to `POST /v1/checkout`.
3. The Worker verifies the exact browser origin, validates configuration,
   requires Cloudflare to locate the visitor in the United States, applies a
   per-IP abuse limit, and atomically reserves one cohort place.
4. The Worker creates a fresh Stripe Checkout Session using server-held offer
   data: `mode: payment`, the configured one-time Price ID, and quantity `1`.
   The browser never supplies price, currency, quantity, product, or duration.
5. The browser validates that the returned URL is on
   `https://checkout.stripe.com` and performs a full-page redirect.
6. Stripe collects email, a required billing address, and payment details. Card
   data never reaches CanopyChat or Cloudflare D1.
7. Stripe redirects the browser to
   `founding-success.html?session_id={CHECKOUT_SESSION_ID}`.
8. Independently, Stripe posts a signed event to `POST /v1/webhook`.
9. The Worker verifies the signature against the raw body, verifies that the
   session belongs to this offer and environment, and requires
   `payment_status=paid`.
10. One D1 transaction creates the Founding Member, queues one confirmation
    email job, marks the event complete, and releases the capacity reservation.
11. The confirmation page asks the Worker for status. Only the webhook-created
    D1 row can return `confirmed`; a Stripe lookup alone can return only
    `processing`, `not_paid`, or `not_found`.

Cancellation returns to `founding.html?checkout=cancelled`, displays a calm
message, and does not create a member.

## Stripe Checkout configuration

The Worker uses the current Stripe Node SDK and API version:

- `stripe` `22.4.x`
- API `2026-07-29.dahlia`
- `mode: "payment"`
- standard Stripe Payments with `managed_payments.enabled=false`
- required billing address and US-only enrollment
- dynamic payment methods (no `payment_method_types` field)
- `integration_identifier: canopy_founding_<8 letters>`
- `customer_creation: "always"`
- final/non-refundable policy disclosure beside the Checkout submit action,
  with the legally required exception preserved
- a new Checkout Session and unique idempotency key per attempt
- 35-minute Checkout expiration (safely above Stripe's 30-minute minimum)
- success and cancellation URLs derived from `PUBLIC_SITE_URL`

Creating a Stripe Customer does not save a payment method or authorize a
future charge. The integration does not set `setup_future_usage`.

### Product and Price

Create these in the Stripe Dashboard in **test mode**:

1. Product name: `CanopyChat Founding Member`
2. Price type: one time
3. Amount: `$10.00 USD`
4. Store its `price_...` ID as `STRIPE_FOUNDING_MEMBER_PRICE_ID`

The sandbox Product and one-time Price above have been created and verified as
`$10.00 USD`, `type=one_time`, `recurring=null`, and `livemode=false`.

Do not create the Product or Price from each checkout request. Do not create a
recurring Price.

### Runtime key

Prefer a dedicated restricted test key (`rk_test_...`) with the minimum Stripe
Checkout Session permission required for create, retrieve, and expire. Test it
while watching Stripe request logs, adding permissions only when Stripe returns
a documented authorization error. A standard test secret key works during
setup but is not the preferred deployed credential.

Never put either key in source, `.dev.vars.example`, `.env.example`, frontend JavaScript, logs,
or chat. Store it with `wrangler secret put STRIPE_SECRET_KEY`.

The Stripe publishable key is not used because this integration redirects to
hosted Checkout without Stripe.js.

## Webhook

Endpoint:

```text
https://founding-api.canopychat.app/v1/webhook
```

Subscribe the test-mode endpoint to:

- `checkout.session.completed`
- `checkout.session.async_payment_succeeded`
- `checkout.session.async_payment_failed`
- `checkout.session.expired`
- `charge.refunded`
- `charge.dispute.created`

Store its `whsec_...` signing secret as `STRIPE_WEBHOOK_SECRET`. The test
endpoint is enabled, its signing secret is installed as an encrypted Worker
secret, and signed deliveries have been verified end to end.

The webhook:

- reads and verifies the exact raw body before trusting event fields;
- validates mode, livemode, currency, purchase type, offer version, Price ID,
  checkout reference, and US billing country;
- fulfills only a paid Checkout Session;
- treats asynchronous success as a distinct fulfilling event;
- keeps `received` and `processing_failed` event rows retryable;
- treats completed outcomes as terminal duplicates;
- uses unique Checkout Session and PaymentIntent constraints;
- records full refunds, partial refunds, and disputes for review. The published
  policy is final and non-refundable except where required by law; the webhook
  observes Stripe state but never initiates or approves refunds.

## D1 data model

### `founding_members`

One row per fulfilled purchase. It stores:

- purchaser and normalized email;
- optional future internal user ID;
- Stripe Customer, Checkout Session, PaymentIntent, and event IDs;
- internal checkout reference;
- amount, currency, payment status, member status, and offer version;
- purchase and fulfillment timestamps;
- nullable beta invitation, Premium activation, and Premium expiration dates.

Unique indexes cover event ID, Checkout Session ID, and non-null PaymentIntent
ID.

Initial normal status is `paid_waiting_for_beta`. Refunds, disputes, and an
already-full late payment use explicit manual-review states.

### `webhook_events`

One idempotency-ledger row per Stripe event ID. An event left in `received` or
`processing_failed` can be retried safely. Terminal outcomes are ignored on
duplicate delivery.

### `checkout_reservations`

Short-lived reservations prevent multiple concurrent buyers from all passing a
count-then-create check for the last place. Each reservation lasts five minutes
beyond its 35-minute Stripe Checkout Session and is released on fulfillment,
failure, or expiration.

### `checkout_attempts`

Per-IP request records for card-testing and abuse protection. IPs are stored as
HMAC-SHA-256 pseudonyms using `RATE_LIMIT_SALT`, not as plain IP addresses or
enumerable unsalted hashes.

### `email_outbox`

Fulfillment atomically queues one `founding_member_confirmation` job. No sender
is implemented because the project has no transactional email provider.

## Capacity enforcement

`FOUNDING_MEMBER_CAPACITY` is configured as **1,000**.

The cap is enforced server-side even though the site does not display remaining
seat counts:

1. Checkout creation atomically inserts a reservation only when active members
   plus active reservations are below 1,000.
2. A paid session with a valid reservation is admitted normally.
3. A very late paid session whose reservation expired is admitted only while
   capacity remains. If the cohort is already full, it is recorded as
   `paid_over_capacity_pending_review` so a real payment is never discarded.

When capacity is reached, new Checkout Sessions are not created and the page
offers the waitlist/support path. There are no fabricated remaining-seat
figures.

## Email confirmation

`support@canopychat.app` currently uses Cloudflare Email Routing for inbound
forwarding. It is the correct public sender and reply address, but Email Routing
does not itself send webhook-triggered transactional messages.

Until outbound sending is enabled:

- enable Stripe’s successful-payment receipts in the Dashboard;
- treat the D1 `email_outbox` row as the integration point for a separate
  Founding Member onboarding message;
- do not send beta credentials from the payment webhook.

The smallest provider addition is Cloudflare Email Service with
`support@canopychat.app` as the allowed sender and an Email Sending binding on
this Worker. Sending to arbitrary purchaser addresses requires a Workers Paid
plan. Do not add the binding to production until the plan and sender domain are
approved and active.

The future email sender should send only after a pending outbox row exists and
should include: one-time payment, no recurring billing, beta invitation arrives
separately, Premium begins on activation, and `support@canopychat.app`.

Stripe’s receipt is not a replacement for that onboarding email.

## Environment and secrets

Secrets, set with Cloudflare’s secret store:

| Name | Purpose |
| --- | --- |
| `STRIPE_SECRET_KEY` | Prefer a least-privilege `rk_test_...` key |
| `STRIPE_WEBHOOK_SECRET` | Test webhook `whsec_...` secret |
| `STRIPE_FOUNDING_MEMBER_PRICE_ID` | Test one-time `price_...` ID |
| `RATE_LIMIT_SALT` | At least 32 random bytes for HMAC pseudonyms |

Non-secret vars in `wrangler.toml`:

| Name | Test value |
| --- | --- |
| `ENVIRONMENT` | `test` |
| `LIVE_PAYMENTS_ENABLED` | `false` |
| `PUBLIC_SITE_URL` | `https://canopychat.app` |
| `OFFER_VERSION` | `founding_member_v1` |
| `FOUNDING_MEMBER_CAPACITY` | `1000` |

Checkout fails closed if any value is absent, malformed, or mixes live and test
resources. A live key is rejected in test mode. Live mode additionally requires
the explicit `LIVE_PAYMENTS_ENABLED=true` gate.

## Local verification

```bash
cd founding_members/worker
npm install
npm test
npx tsc --noEmit
npx wrangler deploy --dry-run
npm audit --omit=dev
```

Current result: 33 tests pass, type checking passes, the Worker bundle succeeds,
and the production dependency audit reports zero vulnerabilities.

Coverage includes:

- server-controlled Price, mode, quantity, and metadata;
- absence of subscription and future-payment fields;
- missing/mixed environment configuration;
- Stripe API failure and reservation release;
- concurrent final-slot reservation;
- webhook signature acceptance/rejection;
- paid, unpaid, expired, non-US, foreign-offer, and wrong-environment events;
- retry after transient processing failure;
- duplicate event and duplicate Checkout Session fulfillment;
- one member and one email job per purchase;
- Premium activation dates remaining null;
- full refund state updates;
- confirmation, processing, review, and not-found statuses;
- exact browser-origin and US-country enforcement, fixed request shape, method
  validation, rate limiting, and capacity responses.

## Stripe CLI test workflow

For local webhook testing:

```bash
stripe login
stripe listen \
  --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,charge.refunded,charge.dispute.created \
  --forward-to localhost:8787/v1/webhook
```

Use the temporary `whsec_...` printed by `stripe listen` in a gitignored
`.dev.vars`, then:

```bash
npm run db:migrate:local
npm run dev
stripe trigger checkout.session.completed
```

An actual Checkout Session created by the Worker is the preferred full-flow
test because generic CLI fixtures will not automatically contain this Worker’s
server-generated metadata and Price ID.

Full test-mode journey (completed successfully on 2026-08-01):

1. Open the Founding Members page.
2. Select the CTA.
3. Complete Stripe-hosted Checkout with a Stripe test card.
4. Confirm redirect to `founding-success.html`.
5. Confirm one webhook event creates exactly one member and email job.
6. Confirm no Stripe Subscription exists.
7. Confirm Premium activation and expiration remain null.
8. Resend/replay the event and confirm no duplicate row is created.
9. Cancel a second Checkout and confirm no member is created.
10. Confirm the page can retry cleanly after cancellation or an API error.

## Cloudflare deployment

The D1 database, migration, Worker, encrypted secrets, and custom hostname are
already deployed in test mode. A future redeployment uses:

```bash
cd founding_members/worker

npx wrangler secret put STRIPE_SECRET_KEY
npx wrangler secret put STRIPE_WEBHOOK_SECRET
npx wrangler secret put STRIPE_FOUNDING_MEMBER_PRICE_ID
npx wrangler secret put RATE_LIMIT_SALT

npx wrangler deploy
```

The custom domain `founding-api.canopychat.app` is already bound to the
`canopy-founding-members` Worker. Verify after each deployment:

```bash
curl https://founding-api.canopychat.app/health
```

Expected test response:

```json
{"status":"ok","environment":"test"}
```

The frontend checkout connection is on a draft pull-request branch, not the
public site. Do not merge it until the owner explicitly approves publication.
For owner validation, the deployed test Worker also accepts exactly
`http://127.0.0.1:8765` and `http://localhost:8765`. Those origins are rejected
unless `ENVIRONMENT=test`, and Stripe returns to the same validated local
origin after test Checkout.

## Test-to-live checklist

- [ ] Rotate any Stripe key that was pasted into chat, logs, or another unsafe
      channel and review Stripe Workbench for unrecognized use.
- [x] Publish the Founding Member policy: final and non-refundable except where
      required by law; no subscription or future charge is authorized.
- [x] Use standard Stripe Payments and limit beta enrollment to US purchasers.
      Managed Payments is explicitly disabled so its additional merchant-of-
      record fee is not charged on US transactions.
- [ ] Select a transactional email provider or approve the temporary
      Stripe-receipt-only experience.
- [x] Run the complete test-mode journey against the deployed Worker.
- [ ] Confirm the 1,000-member capacity remains the intended cohort size.
- [ ] Create a separate live one-time Product and Price; test IDs do not carry
      into live mode.
- [ ] Create a separate live D1 database so sandbox smoke-test records never
      share storage with real purchasers.
- [ ] Create a separate live webhook endpoint and signing secret.
- [ ] Create a separate least-privilege live restricted key.
- [ ] Replace all four Worker secrets deliberately.
- [ ] Change `ENVIRONMENT` to `live` and explicitly set
      `LIVE_PAYMENTS_ENABLED=true` in the same reviewed deployment.
- [ ] Make one small real purchase, verify one D1 record and no Subscription,
      and retain the purchase as the live smoke test.
- [ ] Publish the frontend connection only after the live smoke test is clean.

## Remaining decisions and blockers

1. **US indirect tax registrations:** CanopyChat remains the merchant of record.
   Automatic Stripe Tax has not been silently enabled; determine applicable US
   registrations before collecting sales tax in any jurisdiction.
2. **Transactional email:** the outbox exists. `support@canopychat.app` is an
   inbound forwarding address; approve Cloudflare Email Service/Workers Paid or
   use Stripe receipts temporarily before live launch.
3. **Account claiming:** no authentication exists. A future account flow should
   link the Stripe email to a verified account rather than trusting arbitrary
   browser-supplied IDs.
4. **Beta activation:** a separate trusted operation must set
   `premium_activated_at` and calculate `premium_expires_at` three months later.

## Security review

- No Stripe secret or publishable key appears in frontend code.
- No real secret is committed; `.dev.vars` is gitignored.
- The browser cannot choose the offer or amount.
- Only Stripe-hosted Checkout handles payment data.
- Dynamic payment methods remain enabled.
- Exact-origin and payload-shape checks protect Checkout creation.
- Cloudflare geolocation rejects non-US Checkout creation, Stripe collects a
  required billing address, and fulfillment requires a US billing country.
- Checkout and status requests are rate-limited with HMAC-pseudonymized IPs.
- Webhooks require a valid signature over the raw body.
- Webhook offer/environment metadata is validated before fulfillment.
- Browser redirects and query parameters never grant entitlement.
- D1 unique constraints and retry-safe event outcomes prevent duplicate
  fulfillment without losing transient failures.
- Capacity uses atomic reservations instead of a race-prone count check.
- Refunds and disputes change member state but never approve or initiate a
  refund automatically.
- Live keys are rejected unless two explicit live-mode settings agree.
- Logs omit secrets, signatures, and full Stripe payloads.

## Final confirmations

- This integration creates a **one-time payment**.
- It creates **no subscription**.
- It does **not** start Premium at payment.
- It saves **no payment method for off-session charging**.
- It takes **no future payment without a separate Checkout flow and explicit
  customer authorization**.
