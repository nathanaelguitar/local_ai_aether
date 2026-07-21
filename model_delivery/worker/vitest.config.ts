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
            ENVIRONMENT: "test",
          },
        },
      },
    },
  },
});
