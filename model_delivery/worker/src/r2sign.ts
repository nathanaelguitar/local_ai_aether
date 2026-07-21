/** SigV4 presigned GET URL for Cloudflare R2 using Web Crypto — no dependencies. */

const enc = new TextEncoder();

function arrayBufferToHex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function sha256hex(msg: string): Promise<string> {
  return arrayBufferToHex(await crypto.subtle.digest("SHA-256", enc.encode(msg)));
}

async function hmacBytes(key: ArrayBuffer | Uint8Array, msg: string): Promise<ArrayBuffer> {
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return crypto.subtle.sign("HMAC", k, enc.encode(msg));
}

async function hmacHex(key: ArrayBuffer, msg: string): Promise<string> {
  return arrayBufferToHex(await hmacBytes(key, msg));
}

async function signingKey(secretKey: string, dateStr: string): Promise<ArrayBuffer> {
  const kDate = await hmacBytes(enc.encode("AWS4" + secretKey), dateStr);
  const kRegion = await hmacBytes(kDate, "auto");
  const kService = await hmacBytes(kRegion, "s3");
  return hmacBytes(kService, "aws4_request");
}

function encodeParam(s: string): string {
  return encodeURIComponent(s).replace(/[!'()*]/g, c => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

export async function presignR2Get(opts: {
  accountId: string;
  accessKey: string;
  secretKey: string;
  bucket: string;
  key: string;
  expiresSeconds?: number;
}): Promise<{ url: string; expiresAt: string }> {
  const { accountId, accessKey, secretKey, bucket, key, expiresSeconds = 900 } = opts;

  const host = `${accountId}.r2.cloudflarestorage.com`;
  const now = new Date();
  const amzDate = now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
  const dateStr = amzDate.slice(0, 8);
  const credentialScope = `${dateStr}/auto/s3/aws4_request`;
  const credential = `${accessKey}/${credentialScope}`;
  const canonicalPath = `/${bucket}/${key.split("/").map(encodeParam).join("/")}`;

  const rawParams: [string, string][] = [
    ["X-Amz-Algorithm", "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential", credential],
    ["X-Amz-Date", amzDate],
    ["X-Amz-Expires", String(expiresSeconds)],
    ["X-Amz-SignedHeaders", "host"],
  ];
  rawParams.sort(([a], [b]) => (a < b ? -1 : 1));
  const canonicalQS = rawParams.map(([k, v]) => `${encodeParam(k)}=${encodeParam(v)}`).join("&");

  const canonicalRequest = ["GET", canonicalPath, canonicalQS, `host:${host}\n`, "host", "UNSIGNED-PAYLOAD"].join("\n");

  const stringToSign = [
    "AWS4-HMAC-SHA256",
    amzDate,
    credentialScope,
    await sha256hex(canonicalRequest),
  ].join("\n");

  const sk = await signingKey(secretKey, dateStr);
  const signature = await hmacHex(sk, stringToSign);

  const allParams: [string, string][] = [...rawParams, ["X-Amz-Signature", signature]];
  allParams.sort(([a], [b]) => (a < b ? -1 : 1));
  const finalQS = allParams.map(([k, v]) => `${encodeParam(k)}=${encodeParam(v)}`).join("&");

  const url = `https://${host}${canonicalPath}?${finalQS}`;
  const expiresAt = new Date(now.getTime() + expiresSeconds * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
  return { url, expiresAt };
}

/** SHA-256 of a UTF-8 string — reused for token hashing and IP hashing. */
export async function sha256(input: string): Promise<string> {
  return sha256hex(input);
}
