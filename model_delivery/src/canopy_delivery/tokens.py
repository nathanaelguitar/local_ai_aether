"""Per-install token registry backed by SQLite."""

from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path


_SCHEMA = """
CREATE TABLE IF NOT EXISTS install_tokens (
    token      TEXT PRIMARY KEY,
    install_id TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_install_id ON install_tokens (install_id);
"""


class TokenRegistry:
    def __init__(self, db_path: Path) -> None:
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def register(self, install_id: str = "") -> tuple[str, str]:
        """Create and store a new token. Returns (token, install_id)."""
        if not install_id:
            install_id = str(uuid.uuid4())
        token = str(uuid.uuid4())
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self._conn.execute(
            "INSERT INTO install_tokens (token, install_id, created_at) VALUES (?, ?, ?)",
            (token, install_id, now),
        )
        self._conn.commit()
        return token, install_id

    def validate(self, token: str) -> bool:
        row = self._conn.execute(
            "SELECT 1 FROM install_tokens WHERE token = ?", (token,)
        ).fetchone()
        return row is not None
