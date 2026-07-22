import { defineWorkersProject } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersProject({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          d1Databases: ["DB"],
          r2Buckets: ["MODEL_BUCKET"],
          bindings: {
            R2_ACCOUNT_ID: "test-account",
            R2_ACCESS_KEY_ID: "test-access-key",
            R2_SECRET_ACCESS_KEY: "test-secret-key",
            R2_BUCKET_NAME: "test-bucket",
            ADMIN_SECRET: "test-admin-secret",
            CONTRIBUTOR_INGEST_HMAC_SECRET: "test-contributor-hmac-secret-32-bytes",
            CONTRIBUTOR_INGEST_ORIGIN: "https://contributor-api.canopychat.app/v1/contributor/batches",
            CONTRIBUTOR_BETA_MAX_INSTALLATIONS: "10000",
            ENVIRONMENT: "test",
          },
        },
      },
    },
  },
});
