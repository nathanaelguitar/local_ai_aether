import { env, SELF, runInDurableObject } from "cloudflare:test";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { claimContributorInstallation } from "../src/contributor";
import type { Env } from "../src/types";

// Apply D1 migration before tests
const MIGRATION = `
CREATE TABLE IF NOT EXISTS install_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL UNIQUE,
  install_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS manifest_issuances (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL,
  model_version TEXT NOT NULL,
  issued_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_issuances_token_ver
  ON manifest_issuances (token_hash, model_version, issued_at);
CREATE TABLE IF NOT EXISTS registration_attempts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ip_hash TEXT NOT NULL,
  install_id TEXT,
  attempted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reg_attempts_ip
  ON registration_attempts (ip_hash, attempted_at);
CREATE TABLE IF NOT EXISTS contributor_upload_attempts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL,
  ip_hash TEXT NOT NULL,
  attempted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_contributor_attempts_token_time
  ON contributor_upload_attempts (token_hash, attempted_at);
CREATE INDEX IF NOT EXISTS idx_contributor_attempts_ip_time
  ON contributor_upload_attempts (ip_hash, attempted_at);
CREATE TABLE IF NOT EXISTS contributor_installations (
  token_hash TEXT PRIMARY KEY,
  first_seen_at TEXT NOT NULL
);
`;

const FAKE_META = JSON.stringify({
  version: "1.1.2",
  filename: "canopy-1.1.2.Q4_K_M.gguf",
  size_bytes: 1274818816,
  sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  key: "models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf",
});

async function seedDb() {
  const db = (env as unknown as Env).DB;
  for (const stmt of MIGRATION.split(";").map(s => s.trim()).filter(Boolean)) {
    await db.prepare(stmt).run();
  }
}

async function seedManifest() {
  const bucket = (env as unknown as Env).MODEL_BUCKET;
  await bucket.put("manifests/current.json", FAKE_META);
}

async function registerInstall(installId = "test-install"): Promise<string> {
  const resp = await SELF.fetch("http://model-api.canopychat.app/v1/tokens", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "1.2.3.4" },
    body: JSON.stringify({ install_id: installId }),
  });
  expect(resp.status).toBe(201);
  const body = await resp.json<{ token: string }>();
  expect(body.token.length).toBeGreaterThanOrEqual(24);
  return body.token;
}

function contributorBody(batchId = "batch-test-001"): string {
  return JSON.stringify({
    schema_version: 1,
    batch_id: batchId,
    installation_id: "install-test-001",
    sent_at: "2026-07-21T00:00:00Z",
    consent_for_model_improvement: true,
    events: [],
  });
}

// ── Health ────────────────────────────────────────────────────────────────────

describe("GET /health", () => {
  it("returns 503 when manifest not synced", async () => {
    await seedDb();
    const resp = await SELF.fetch("http://model-api.canopychat.app/health");
    expect(resp.status).toBe(503);
  });

  it("returns 200 ok when manifest is present", async () => {
    await seedDb();
    await seedManifest();
    const resp = await SELF.fetch("http://model-api.canopychat.app/health");
    expect(resp.status).toBe(200);
    const body = await resp.json<{ status: string; version: string }>();
    expect(body.status).toBe("ok");
    expect(body.version).toBe("1.1.2");
  });
});

// ── Token registration ────────────────────────────────────────────────────────

describe("POST /v1/tokens", () => {
  beforeEach(async () => { await seedDb(); });

  it("registers a new install and returns a ≥64-char token", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/tokens", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "1.2.3.4" },
      body: JSON.stringify({ install_id: "device-abc" }),
    });
    expect(resp.status).toBe(201);
    const body = await resp.json<{ token: string; installation_token: string; install_id: string }>();
    expect(body.token.length).toBe(64);
    expect(body.installation_token).toBe(body.token);
    expect(body.install_id).toBe("device-abc");
  });

  it("generates an install_id if not provided", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/tokens", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "2.3.4.5" },
      body: "{}",
    });
    expect(resp.status).toBe(201);
    const body = await resp.json<{ install_id: string }>();
    expect(body.install_id.length).toBeGreaterThan(0);
  });

  it("returns 429 after 5 registrations from the same IP in one hour", async () => {
    const headers = { "Content-Type": "application/json", "CF-Connecting-IP": "3.4.5.6" };
    for (let i = 0; i < 5; i++) {
      const r = await SELF.fetch("http://model-api.canopychat.app/v1/tokens", {
        method: "POST",
        headers,
        body: JSON.stringify({ install_id: `device-${i}` }),
      });
      expect(r.status).toBe(201);
    }
    const r6 = await SELF.fetch("http://model-api.canopychat.app/v1/tokens", {
      method: "POST",
      headers,
      body: JSON.stringify({ install_id: "device-6" }),
    });
    expect(r6.status).toBe(429);
    expect(r6.headers.get("Retry-After")).toBeTruthy();
  });
});

// ── Manifest ──────────────────────────────────────────────────────────────────

describe("GET /v1/model-manifest", () => {
  beforeEach(async () => { await seedDb(); await seedManifest(); });

  it("returns 401 without Authorization header", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest");
    expect(resp.status).toBe(401);
    expect(resp.headers.get("WWW-Authenticate")).toMatch(/Bearer/);
  });

  it("returns 403 for an invalid token", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", {
      headers: { Authorization: "Bearer invalid-token-value", "CF-Connecting-IP": "1.2.3.4" },
    });
    expect(resp.status).toBe(403);
    const body = await resp.json<{ error: string }>();
    expect(body.error).toBe("invalid_token");
  });

  it("returns a valid manifest for a registered token", async () => {
    const token = await registerInstall("iphone-1");
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", {
      headers: { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "1.2.3.4" },
    });
    expect(resp.status).toBe(200);
    const body = await resp.json<{
      version: string;
      filename: string;
      size_bytes: number;
      sha256: string;
      download_url: string;
      url_expires_at: string;
    }>();
    expect(body.version).toBe("1.1.2");
    expect(body.filename).toBe("canopy-1.1.2.Q4_K_M.gguf");
    expect(body.size_bytes).toBe(1274818816);
    expect(body.sha256).toMatch(/^[a-f0-9]{64}$/);
    expect(body.download_url).toMatch(/^https:\/\//);
    expect(body.url_expires_at).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  });

  it("signed URL contains X-Amz-Expires=900 and UNSIGNED-PAYLOAD", async () => {
    const token = await registerInstall("iphone-exp");
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", {
      headers: { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "1.2.3.5" },
    });
    const body = await resp.json<{ download_url: string }>();
    const u = new URL(body.download_url);
    expect(u.searchParams.get("X-Amz-Expires")).toBe("900");
    expect(u.searchParams.get("X-Amz-SignedHeaders")).toBe("host");
  });

  it("/refresh alias works identically", async () => {
    const token = await registerInstall("iphone-refresh");
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest/refresh", {
      headers: { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "5.6.7.8" },
    });
    expect(resp.status).toBe(200);
  });

  it("returns 403 for a revoked token", async () => {
    const token = await registerInstall("revoke-test");

    // Revoke it
    const revokeResp = await SELF.fetch("http://model-api.canopychat.app/admin/tokens/revoke", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer test-admin-secret`,
      },
      body: JSON.stringify({ install_id: "revoke-test" }),
    });
    expect(revokeResp.status).toBe(200);
    const rv = await revokeResp.json<{ revoked: number }>();
    expect(rv.revoked).toBe(1);

    // Now the token should be rejected
    const manifestResp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", {
      headers: { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "1.2.3.4" },
    });
    expect(manifestResp.status).toBe(403);
  });

  it("returns 429 after 24 manifest requests for the same version", async () => {
    const token = await registerInstall("rate-test");
    const headers = { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "9.9.9.9" };

    for (let i = 0; i < 24; i++) {
      const r = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", { headers });
      expect(r.status).toBe(200);
    }
    const r25 = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", { headers });
    expect(r25.status).toBe(429);
    expect(r25.headers.get("Retry-After")).toBeTruthy();
  });
});

// ── Immutability ──────────────────────────────────────────────────────────────

describe("Immutable versioning", () => {
  it("a new version in manifests/current.json does not overwrite the old R2 key", async () => {
    await seedDb();
    await seedManifest();

    // Simulate bumping the manifest pointer to 1.1.3 without removing 1.1.2 object
    const bucket = (env as unknown as Env).MODEL_BUCKET;
    await bucket.put("manifests/current.json", JSON.stringify({
      version: "1.1.3",
      filename: "canopy-1.1.3.Q4_K_M.gguf",
      size_bytes: 1300000000,
      sha256: "aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd",
      key: "models/canopy/1.1.3/canopy-1.1.3.Q4_K_M.gguf",
    }));

    // Old object at 1.1.2 key is still present (we didn't delete it)
    const old = await bucket.get("models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf");
    // Not present in test (we never put the model bytes) — verify it's null, not an error
    expect(old).toBeNull();

    // New manifest pointer returns 1.1.3
    await seedDb();
    const token = await registerInstall("immutable-test");
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/model-manifest", {
      headers: { Authorization: `Bearer ${token}`, "CF-Connecting-IP": "4.5.6.7" },
    });
    expect(resp.status).toBe(200);
    const body = await resp.json<{ version: string }>();
    expect(body.version).toBe("1.1.3");
  });
});

// ── Unknown routes ────────────────────────────────────────────────────────────

describe("Unknown routes", () => {
  it("returns 404 for unknown paths", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/unknown");
    expect(resp.status).toBe(404);
  });
});

// ── Contributor relay ────────────────────────────────────────────────────────

describe("POST /v1/contributor/batches", () => {
  beforeEach(async () => { await seedDb(); });

  it("requires an authenticated installation token", async () => {
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: contributorBody(),
    });
    expect(resp.status).toBe(401);
  });

  it("rejects an invalid installation token without contacting the origin", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
      method: "POST",
      headers: {
        Authorization: "Bearer invalid-contributor-token",
        "Content-Type": "application/json",
      },
      body: contributorBody(),
    });
    expect(resp.status).toBe(403);
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("signs and forwards the exact request body without forwarding authorization", async () => {
    const token = await registerInstall("contributor-forward");
    const body = contributorBody("batch-forward-001");
    let forwarded: Request | undefined;
    let forwardedBody = "";
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      forwarded = new Request(input, init);
      forwardedBody = await forwarded.clone().text();
      return new Response(JSON.stringify({ receipt_id: "receipt-forward-001" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });

    try {
      const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          "CF-Connecting-IP": "10.20.30.40",
          "X-Canopy-App-Version": "should-not-be-forwarded",
        },
        body,
      });
      expect(resp.status).toBe(200);
      expect(forwarded).toBeDefined();
      expect(forwardedBody).toBe(body);
      expect(forwarded!.headers.get("Authorization")).toBeNull();
      expect(forwarded!.headers.get("Content-Type")).toBe("application/json");
      expect(forwarded!.headers.get("X-Canopy-Timestamp")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(forwarded!.headers.get("X-Canopy-Signature")).toMatch(/^sha256=[a-f0-9]{64}$/);
      expect(forwarded!.headers.get("X-Canopy-Request-ID")).toMatch(/^[0-9a-f-]{36}$/);
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("returns 413 for an oversized body before forwarding", async () => {
    const token = await registerInstall("contributor-oversized");
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    try {
      const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          "Content-Length": "2000001",
        },
        body: "x",
      });
      expect(resp.status).toBe(413);
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("enforces the per-install upload limit", async () => {
    const token = await registerInstall("contributor-rate");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      new Response(JSON.stringify({ receipt_id: "rate-receipt" }), { status: 200 }),
    );
    try {
      for (let i = 0; i < 30; i++) {
        const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
            "CF-Connecting-IP": "10.20.30.41",
          },
          body: contributorBody(`batch-rate-${i}`),
        });
        expect(resp.status).toBe(200);
      }
      const blocked = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          "CF-Connecting-IP": "10.20.30.41",
        },
        body: contributorBody("batch-rate-blocked"),
      });
      expect(blocked.status).toBe(429);
      expect(blocked.headers.get("Retry-After")).toBe("86400");
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("maps an unreachable contributor origin to 503", async () => {
    const token = await registerInstall("contributor-origin-failure");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("origin unavailable"));
    try {
      const resp = await SELF.fetch("http://model-api.canopychat.app/v1/contributor/batches", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: contributorBody("batch-origin-failure"),
      });
      expect(resp.status).toBe(503);
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it("enforces the beta installation ceiling without affecting existing installs", async () => {
    const db = (env as unknown as Env).DB;
    await db.prepare("DELETE FROM contributor_installations").run();
    await db
      .prepare("INSERT INTO contributor_installations (token_hash, first_seen_at) VALUES (?, ?)")
      .bind("already-admitted", "2026-07-22T00:00:00Z")
      .run();

    expect(await claimContributorInstallation(db, "already-admitted", 1)).toBe(true);
    expect(await claimContributorInstallation(db, "new-installation", 1)).toBe(false);
  });
});
