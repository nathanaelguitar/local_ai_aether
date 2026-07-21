"""Sync GGUF from Hugging Face to Cloudflare R2.

Run once before starting the server:
    python -m canopy_delivery.sync
"""

from __future__ import annotations

import hashlib
import http.client
import json
import logging
import os
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

from .r2 import _r2_host, signed_put_headers

log = logging.getLogger(__name__)


_HF_TOKEN = os.environ["CANOPY_DELIVERY_HF_TOKEN"]
_R2_ACCOUNT_ID = os.environ["CANOPY_DELIVERY_R2_ACCOUNT_ID"]
_R2_ACCESS_KEY = os.environ["CANOPY_DELIVERY_R2_ACCESS_KEY_ID"]
_R2_SECRET_KEY = os.environ["CANOPY_DELIVERY_R2_SECRET_ACCESS_KEY"]
_R2_BUCKET = os.environ["CANOPY_DELIVERY_R2_BUCKET"]

_HF_REPO = os.environ.get("CANOPY_DELIVERY_HF_REPO", "nathanaelguitar/canopy-1.1.2")
_MODEL_FILENAME = os.environ.get("CANOPY_DELIVERY_MODEL_FILENAME", "canopy-1.1.2.Q4_K_M.gguf")
_MODEL_VERSION = os.environ.get("CANOPY_DELIVERY_MODEL_VERSION", "1.1.2")
_DATA_ROOT = Path(os.environ.get("CANOPY_DELIVERY_ROOT", "/data/canopy/model_delivery"))
_CHUNK = 8 * 1024 * 1024  # 8 MB read chunks


def _resolve_hf_url(hf_url: str) -> str:
    """Follow HF redirect to get the actual CDN download URL."""
    parsed = urllib.parse.urlparse(hf_url)
    conn = http.client.HTTPSConnection(parsed.netloc, timeout=30)
    path = parsed.path
    if parsed.query:
        path += "?" + parsed.query
    conn.request("HEAD", path, headers={"Authorization": f"Bearer {_HF_TOKEN}"})
    resp = conn.getresponse()
    resp.read()
    conn.close()
    if resp.status in (301, 302, 303, 307, 308):
        location = resp.getheader("Location", "")
        if location:
            return location
    return hf_url


def _download_to_temp(url: str) -> tuple[Path, str, int]:
    """Stream URL to a temp file. Returns (path, sha256_hex, size_bytes)."""
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc
    path = parsed.path
    if parsed.query:
        path += "?" + parsed.query

    conn = http.client.HTTPSConnection(host, timeout=300)
    conn.request("GET", path)
    resp = conn.getresponse()

    if resp.status != 200:
        raise RuntimeError(f"Download failed: HTTP {resp.status} from {url}")

    total = int(resp.getheader("Content-Length", "0"))
    hasher = hashlib.sha256()
    size = 0

    tmp_dir = Path(tempfile.mkdtemp())
    tmp_path = tmp_dir / _MODEL_FILENAME

    log.info("Downloading %s (%.1f GB) ...", _MODEL_FILENAME, total / 1e9)
    with tmp_path.open("wb") as fh:
        while True:
            chunk = resp.read(_CHUNK)
            if not chunk:
                break
            fh.write(chunk)
            hasher.update(chunk)
            size += len(chunk)
            if total:
                pct = size * 100 // total
                if pct % 10 == 0:
                    log.info("  %d%% (%d MB)", pct, size // 1_000_000)

    conn.close()
    return tmp_path, hasher.hexdigest(), size


def _put_to_r2(local_path: Path, key: str, size: int) -> None:
    """Upload local_path to R2 using a single signed PUT."""
    host = _r2_host(_R2_ACCOUNT_ID)
    headers = signed_put_headers(
        account_id=_R2_ACCOUNT_ID,
        access_key=_R2_ACCESS_KEY,
        secret_key=_R2_SECRET_KEY,
        bucket=_R2_BUCKET,
        key=key,
        content_length=size,
    )
    conn = http.client.HTTPSConnection(host, timeout=600)
    log.info("Uploading %s to R2 (%.1f GB) ...", key, size / 1e9)
    with local_path.open("rb") as fh:
        conn.request("PUT", f"/{_R2_BUCKET}/{key}", body=fh, headers=headers)
    resp = conn.getresponse()
    body = resp.read().decode(errors="replace")
    conn.close()
    if resp.status not in (200, 204):
        raise RuntimeError(f"R2 PUT failed: HTTP {resp.status}: {body[:200]}")
    log.info("R2 upload complete.")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    meta_path = _DATA_ROOT / "manifest_meta.json"
    if meta_path.exists():
        log.info("manifest_meta.json already present — skipping sync.")
        return

    _DATA_ROOT.mkdir(parents=True, exist_ok=True)

    hf_url = f"https://huggingface.co/{_HF_REPO}/resolve/main/{_MODEL_FILENAME}"
    log.info("Resolving HF URL: %s", hf_url)
    cdn_url = _resolve_hf_url(hf_url)
    log.info("CDN URL: %s", cdn_url[:80] + "...")

    tmp_path, sha256, size = _download_to_temp(cdn_url)
    log.info("Downloaded: sha256=%s  size=%d", sha256, size)

    _put_to_r2(tmp_path, _MODEL_FILENAME, size)

    meta: dict = {
        "version": _MODEL_VERSION,
        "filename": _MODEL_FILENAME,
        "size_bytes": size,
        "sha256": sha256,
    }
    meta_path.write_text(json.dumps(meta, indent=2))
    log.info("manifest_meta.json written.")

    tmp_path.unlink(missing_ok=True)
    tmp_path.parent.rmdir()


if __name__ == "__main__":
    main()
