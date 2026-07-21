"""Cloudflare R2 request signing — stdlib only, no boto3."""

from __future__ import annotations

import hashlib
import hmac
import http.client
import urllib.parse
from datetime import datetime, timezone
from typing import Any


def _sign(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def _signing_key(secret_key: str, date_str: str) -> bytes:
    k = _sign(f"AWS4{secret_key}".encode(), date_str)
    k = _sign(k, "auto")      # region
    k = _sign(k, "s3")        # service
    k = _sign(k, "aws4_request")
    return k


def _r2_host(account_id: str) -> str:
    return f"{account_id}.r2.cloudflarestorage.com"


def presign_r2_get(
    *,
    account_id: str,
    access_key: str,
    secret_key: str,
    bucket: str,
    key: str,
    expires_seconds: int = 900,
) -> tuple[str, str]:
    """Return (presigned_url, iso_expiry_utc)."""
    host = _r2_host(account_id)
    now = datetime.now(timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_str = now.strftime("%Y%m%d")
    credential_scope = f"{date_str}/auto/s3/aws4_request"
    credential = f"{access_key}/{credential_scope}"
    signed_headers = "host"

    qs_params: dict[str, str] = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": credential,
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires_seconds),
        "X-Amz-SignedHeaders": signed_headers,
    }

    def _encode_qs(params: dict[str, str]) -> str:
        return "&".join(
            f"{urllib.parse.quote(k, safe='')}={urllib.parse.quote(v, safe='')}"
            for k, v in sorted(params.items())
        )

    canonical_qs = _encode_qs(qs_params)
    canonical_path = f"/{bucket}/{urllib.parse.quote(key, safe='/')}"
    canonical_request = "\n".join([
        "GET",
        canonical_path,
        canonical_qs,
        f"host:{host}\n",
        signed_headers,
        "UNSIGNED-PAYLOAD",
    ])

    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256",
        amz_date,
        credential_scope,
        hashlib.sha256(canonical_request.encode()).hexdigest(),
    ])

    sig = hmac.new(
        _signing_key(secret_key, date_str),
        string_to_sign.encode(),
        hashlib.sha256,
    ).hexdigest()

    qs_params["X-Amz-Signature"] = sig
    url = f"https://{host}{canonical_path}?{_encode_qs(qs_params)}"
    expiry = datetime.fromtimestamp(
        now.timestamp() + expires_seconds, tz=timezone.utc
    ).strftime("%Y-%m-%dT%H:%M:%SZ")
    return url, expiry


def signed_put_headers(
    *,
    account_id: str,
    access_key: str,
    secret_key: str,
    bucket: str,
    key: str,
    content_length: int,
    content_type: str = "application/octet-stream",
) -> dict[str, str]:
    """Return Authorization + required headers for a single-part PUT to R2."""
    host = _r2_host(account_id)
    now = datetime.now(timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_str = now.strftime("%Y%m%d")
    credential_scope = f"{date_str}/auto/s3/aws4_request"
    payload_hash = "UNSIGNED-PAYLOAD"
    signed_headers = "content-length;content-type;host;x-amz-content-sha256;x-amz-date"

    canonical_headers = (
        f"content-length:{content_length}\n"
        f"content-type:{content_type}\n"
        f"host:{host}\n"
        f"x-amz-content-sha256:{payload_hash}\n"
        f"x-amz-date:{amz_date}\n"
    )

    canonical_path = f"/{bucket}/{urllib.parse.quote(key, safe='/')}"
    canonical_request = "\n".join([
        "PUT",
        canonical_path,
        "",
        canonical_headers,
        signed_headers,
        payload_hash,
    ])

    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256",
        amz_date,
        credential_scope,
        hashlib.sha256(canonical_request.encode()).hexdigest(),
    ])

    sig = hmac.new(
        _signing_key(secret_key, date_str),
        string_to_sign.encode(),
        hashlib.sha256,
    ).hexdigest()

    auth = (
        f"AWS4-HMAC-SHA256 Credential={access_key}/{credential_scope},"
        f"SignedHeaders={signed_headers},"
        f"Signature={sig}"
    )
    return {
        "Authorization": auth,
        "Content-Length": str(content_length),
        "Content-Type": content_type,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
