export interface Env {
  DB: D1Database;
  MODEL_BUCKET: R2Bucket;
  R2_ACCOUNT_ID: string;
  R2_ACCESS_KEY_ID: string;
  R2_SECRET_ACCESS_KEY: string;
  R2_BUCKET_NAME: string;
  ADMIN_SECRET: string;
  CONTRIBUTOR_INGEST_HMAC_SECRET: string;
  CONTRIBUTOR_INGEST_ORIGIN: string;
  ENVIRONMENT: string;
}

/** Shape stored in R2 at manifests/current.json by the DGX sync job. */
export interface ManifestMeta {
  version: string;
  filename: string;
  size_bytes: number;
  sha256: string;
  /** R2 object key, e.g. "models/canopy/1.1.2/canopy-1.1.2.Q4_K_M.gguf" */
  key: string;
}

export interface InstallTokenRow {
  id: number;
  token_hash: string;
  install_id: string;
  created_at: string;
  revoked: number;
}
