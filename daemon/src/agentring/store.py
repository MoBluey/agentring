"""Local SQLite mirror of the relay's messages (and, from M2, calls)."""

from __future__ import annotations

import sqlite3
import json
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS messages (
    msg_id TEXT PRIMARY KEY,
    sender TEXT NOT NULL,
    text TEXT NOT NULL,
    reply_to TEXT,
    created_at INTEGER NOT NULL,
    read_at INTEGER,
    -- when `agentring replies` / get_replies last handed this message to the agent
    delivered_at INTEGER
);
CREATE INDEX IF NOT EXISTS messages_created ON messages(created_at);
"""


class Store:
    def __init__(self, path: Path | str):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript(SCHEMA)
        columns = {r[1] for r in self.db.execute("PRAGMA table_info(messages)")}
        if "chat_id" not in columns:
            self.db.execute("ALTER TABLE messages ADD COLUMN chat_id TEXT NOT NULL DEFAULT 'chat_default'")
        if "attachments_json" not in columns:
            self.db.execute("ALTER TABLE messages ADD COLUMN attachments_json TEXT NOT NULL DEFAULT '[]'")
        for column in ("question_json", "answer_json"):
            if column not in columns:
                self.db.execute(f"ALTER TABLE messages ADD COLUMN {column} TEXT")
        if "relay_delivered_at" not in columns:
            self.db.execute("ALTER TABLE messages ADD COLUMN relay_delivered_at INTEGER")
        self.db.execute("CREATE TABLE IF NOT EXISTS chats (chat_id TEXT PRIMARY KEY, metadata TEXT NOT NULL)")

    def close(self) -> None:
        self.db.close()

    # state
    def get_state(self, key: str, default: str | None = None) -> str | None:
        row = self.db.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else default

    def set_state(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    @property
    def last_seq(self) -> int:
        return int(self.get_state("last_seq", "0") or 0)

    def advance_seq(self, seq: int) -> None:
        if seq > self.last_seq:
            self.set_state("last_seq", str(seq))

    def reset_if_relay_changed(self, relay_id: str) -> bool:
        """A different relay (or a wiped one) means our cursor is meaningless."""
        known = self.get_state("relay_id")
        if known == relay_id:
            return False
        self.set_state("relay_id", relay_id)
        if known is not None:
            self.set_state("last_seq", "0")
            return True
        return False

    # messages
    def upsert_message(self, msg_id: str, sender: str, text: str, created_at: int, reply_to: str | None = None,
                       chat_id: str = "chat_default", attachments: list[dict] | None = None,
                       question: dict | None = None, answer: dict | None = None) -> bool:
        cur = self.db.execute(
            "INSERT OR IGNORE INTO messages (msg_id, sender, text, reply_to, created_at, chat_id, attachments_json, question_json, answer_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (msg_id, sender, text, reply_to, created_at, chat_id, json.dumps(attachments or []),
             json.dumps(question) if question is not None else None, json.dumps(answer) if answer is not None else None),
        )
        return cur.rowcount == 1

    def pending_receipts(self) -> list[dict[str, Any]]:
        return [dict(r) for r in self.db.execute("""SELECT msg_id FROM messages
            WHERE sender = 'user' AND relay_delivered_at IS NULL""")]

    def mark_received(self, msg_id: str, ts: int) -> None:
        self.db.execute("UPDATE messages SET relay_delivered_at = COALESCE(relay_delivered_at, ?) WHERE msg_id = ?", (ts, msg_id))

    def mark_question_answered(self, question_id: str, answer_msg_id: str, ts: int) -> None:
        row = self.db.execute("SELECT question_json FROM messages WHERE msg_id = ?", (question_id,)).fetchone()
        if row and row["question_json"]:
            question = json.loads(row["question_json"])
            question.update(answered_by_msg_id=answer_msg_id, answered_at=ts)
            self.db.execute("UPDATE messages SET question_json = ? WHERE msg_id = ?", (json.dumps(question), question_id))

    def mark_read(self, msg_id: str, ts: int) -> None:
        self.db.execute("UPDATE messages SET read_at = COALESCE(read_at, ?) WHERE msg_id = ?", (ts, msg_id))

    def replies(self, *, since: int | None = None, undelivered_only: bool = False, limit: int = 100, chat_id: str | None = None) -> list[dict[str, Any]]:
        q = "SELECT * FROM messages WHERE sender = 'user'"
        args: list[Any] = []
        if chat_id is not None:
            q += " AND chat_id = ?"
            args.append(chat_id)
        if since is not None:
            q += " AND created_at >= ?"
            args.append(since)
        if undelivered_only:
            q += " AND delivered_at IS NULL"
        q += " ORDER BY msg_id LIMIT ?"
        args.append(limit)
        rows = [dict(r) for r in self.db.execute(q, args).fetchall()]
        for row in rows:
            row["attachments"] = json.loads(row.pop("attachments_json"))
            for name in ("question", "answer"):
                raw = row.pop(name + "_json")
                if raw is not None:
                    row[name] = json.loads(raw)
        return rows

    def upsert_chat(self, chat: dict) -> None:
        self.db.execute("INSERT INTO chats VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET metadata=excluded.metadata",
                        (chat["chat_id"], json.dumps(chat)))

    def mark_delivered(self, msg_ids: list[str], ts: int) -> None:
        self.db.executemany("UPDATE messages SET delivered_at = ? WHERE msg_id = ?", [(ts, m) for m in msg_ids])
