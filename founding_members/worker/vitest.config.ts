import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig({
  logLevel: "error",
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        d1Databases: ["DB"],
        bindings: {
          STRIPE_SECRET_KEY: "sk_test_placeholder",
          STRIPE_WEBHOOK_SECRET: "whsec_test_placeholder",
          STRIPE_FOUNDING_MEMBER_PRICE_ID: "price_test_placeholder",
          RATE_LIMIT_SALT: "test-rate-limit-salt-not-for-production",
          ENVIRONMENT: "test",
          LIVE_PAYMENTS_ENABLED: "false",
          PUBLIC_SITE_URL: "https://canopychat.app",
          OFFER_VERSION: "founding_member_v1",
          FOUNDING_MEMBER_CAPACITY: "1",
        },
      },
    }),
  ],
});
