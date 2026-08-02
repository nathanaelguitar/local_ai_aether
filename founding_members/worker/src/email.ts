import {
  claimPendingEmail,
  markEmailFailed,
  markEmailSent,
  type PendingEmailRow,
} from "./db";
import type { Env } from "./types";

const DEFAULT_SENDER = "support@canopychat.app";
const DEFAULT_TESTFLIGHT_URL = "https://testflight.apple.com/join/f5xCZmVv";
const DEFAULT_TESTFLIGHT_CODE = "CANOPY-TEST";
const MAX_EMAILS_PER_RUN = 5;
const STALE_CLAIM_MS = 10 * 60 * 1_000;

function emailConfiguration(env: Env) {
  if (!env.GMAIL_CLIENT_ID || !env.GMAIL_CLIENT_SECRET || !env.GMAIL_REFRESH_TOKEN) {
    return null;
  }
  return {
    clientId: env.GMAIL_CLIENT_ID,
    clientSecret: env.GMAIL_CLIENT_SECRET,
    refreshToken: env.GMAIL_REFRESH_TOKEN,
    sender: env.GMAIL_SENDER?.trim() || DEFAULT_SENDER,
    testflightUrl: env.TESTFLIGHT_URL?.trim() || DEFAULT_TESTFLIGHT_URL,
    testflightCode: env.TESTFLIGHT_CODE?.trim() || DEFAULT_TESTFLIGHT_CODE,
  };
}

function safeHeader(value: string, field: string): string {
  if (!value || /[\r\n]/.test(value)) throw new Error(`invalid_${field}`);
  return value;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8_000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function accessToken(config: NonNullable<ReturnType<typeof emailConfiguration>>): Promise<string> {
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: config.clientId,
      client_secret: config.clientSecret,
      refresh_token: config.refreshToken,
      grant_type: "refresh_token",
    }),
  });
  if (!response.ok) throw new Error("gmail_token_refresh_failed");
  const payload = (await response.json()) as { access_token?: string };
  if (!payload.access_token) throw new Error("gmail_access_token_missing");
  return payload.access_token;
}

function messageFor(
  config: NonNullable<ReturnType<typeof emailConfiguration>>,
  row: PendingEmailRow,
): string {
  const sender = safeHeader(config.sender, "sender");
  const recipient = safeHeader(row.recipient.trim(), "recipient");
  const body = [
    "Hi,",
    "",
    "Thank you for becoming a CanopyChat Founding Member.",
    "",
    "Your iPhone beta access:",
    config.testflightUrl,
    `Beta code: ${config.testflightCode}`,
    "",
    "Open the link on your iPhone, install CanopyChat through TestFlight, and use the same email address you entered at Checkout.",
    "",
    "Founding Membership includes three months of Premium access beginning when your beta access is activated. This was a one-time payment and does not start a subscription or authorize future charges.",
    "",
    "If you need help, reply to this email or contact support@canopychat.app.",
    "",
    "Welcome to the first community,",
    "The CanopyChat team",
  ].join("\n");
  const raw = [
    `From: CanopyChat <${sender}>`,
    `To: ${recipient}`,
    `Reply-To: ${sender}`,
    "Subject: Your CanopyChat Founding Member access",
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "",
    body,
  ].join("\r\n");
  return base64Url(new TextEncoder().encode(raw));
}

async function sendOne(
  config: NonNullable<ReturnType<typeof emailConfiguration>>,
  row: PendingEmailRow,
  token: string,
): Promise<void> {
  const response = await fetch("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ raw: messageFor(config, row) }),
  });
  if (!response.ok) throw new Error("gmail_message_send_failed");
}

/** Sends only rows created by verified webhook fulfillment. */
export async function processPendingFoundingMemberEmails(env: Env): Promise<void> {
  const config = emailConfiguration(env);
  if (!config) {
    console.warn(JSON.stringify({ event: "email_delivery_not_configured" }));
    return;
  }

  let token: string;
  try {
    token = await accessToken(config);
  } catch {
    console.error(JSON.stringify({ event: "email_token_refresh_failed" }));
    return;
  }

  for (let i = 0; i < MAX_EMAILS_PER_RUN; i += 1) {
    const now = new Date().toISOString();
    const staleBefore = new Date(Date.now() - STALE_CLAIM_MS).toISOString();
    const row = await claimPendingEmail(env.DB, now, staleBefore);
    if (!row) return;
    try {
      await sendOne(config, row, token);
      await markEmailSent(env.DB, row.outbox_id, new Date().toISOString());
      console.log(JSON.stringify({ event: "founding_member_email_sent", outbox_id: row.outbox_id }));
    } catch {
      await markEmailFailed(
        env.DB,
        row.outbox_id,
        row.attempts,
        "email_provider_send_failed",
        new Date().toISOString(),
      );
      console.error(JSON.stringify({ event: "founding_member_email_failed", outbox_id: row.outbox_id }));
    }
  }
}
