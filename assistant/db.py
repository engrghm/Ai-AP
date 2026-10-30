"""SQLite storage: everything the assistant knows about the owner lives here."""

import json
import os
import sqlite3
import threading
from datetime import datetime, timezone

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY,
    category TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY,
    list_name TEXT NOT NULL,
    text TEXT NOT NULL,
    due_date TEXT,
    priority TEXT,
    done INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    done_at TEXT
);
CREATE TABLE IF NOT EXISTS reminders (
    id INTEGER PRIMARY KEY,
    text TEXT NOT NULL,
    due_at TEXT NOT NULL,          -- UTC ISO-8601
    repeat TEXT NOT NULL DEFAULT 'none',
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY,
    session INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL          -- JSON
);
CREATE TABLE IF NOT EXISTS kv (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Database:
    def __init__(self, path: str):
        if os.path.dirname(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.executescript(SCHEMA)

    def _exec(self, sql: str, params: tuple = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, params)
            self._conn.commit()
            return cur

    def _all(self, sql: str, params: tuple = ()) -> list[dict]:
        return [dict(r) for r in self._exec(sql, params).fetchall()]

    # --- memories ---
    def add_memory(self, category: str, content: str) -> int:
        now = utcnow().isoformat()
        return self._exec(
            "INSERT INTO memories (category, content, created_at, updated_at) VALUES (?, ?, ?, ?)",
            (category.strip().lower(), content, now, now),
        ).lastrowid

    def search_memories(self, query: str | None, category: str | None) -> list[dict]:
        sql, params = "SELECT id, category, content, updated_at FROM memories WHERE 1=1", []
        if category:
            sql += " AND category = ?"
            params.append(category.strip().lower())
        if query:
            for word in query.split():
                sql += " AND (content LIKE ? OR category LIKE ?)"
                params += [f"%{word}%", f"%{word}%"]
        return self._all(sql + " ORDER BY id", tuple(params))

    def update_memory(self, memory_id: int, content: str) -> bool:
        cur = self._exec(
            "UPDATE memories SET content = ?, updated_at = ? WHERE id = ?",
            (content, utcnow().isoformat(), memory_id),
        )
        return cur.rowcount > 0

    def delete_memory(self, memory_id: int) -> bool:
        return self._exec("DELETE FROM memories WHERE id = ?", (memory_id,)).rowcount > 0

    def memory_categories(self) -> list[dict]:
        return self._all("SELECT category, COUNT(*) AS count FROM memories GROUP BY category ORDER BY category")

    # --- list items (todo, shopping, goals, ...) ---
    def add_item(self, list_name: str, text: str, due_date: str | None, priority: str | None) -> int:
        return self._exec(
            "INSERT INTO items (list_name, text, due_date, priority, created_at) VALUES (?, ?, ?, ?, ?)",
            (list_name.strip().lower(), text, due_date, priority, utcnow().isoformat()),
        ).lastrowid

    def get_items(self, list_name: str | None, include_done: bool) -> list[dict]:
        sql, params = "SELECT id, list_name, text, due_date, priority, done FROM items WHERE 1=1", []
        if list_name:
            sql += " AND list_name = ?"
            params.append(list_name.strip().lower())
        if not include_done:
            sql += " AND done = 0"
        return self._all(sql + " ORDER BY list_name, due_date IS NULL, due_date, id", tuple(params))

    def complete_item(self, item_id: int) -> bool:
        cur = self._exec(
            "UPDATE items SET done = 1, done_at = ? WHERE id = ?", (utcnow().isoformat(), item_id)
        )
        return cur.rowcount > 0

    def delete_item(self, item_id: int) -> bool:
        return self._exec("DELETE FROM items WHERE id = ?", (item_id,)).rowcount > 0

    # --- reminders ---
    def add_reminder(self, text: str, due_at_utc: datetime, repeat: str) -> int:
        return self._exec(
            "INSERT INTO reminders (text, due_at, repeat, created_at) VALUES (?, ?, ?, ?)",
            (text, due_at_utc.isoformat(), repeat, utcnow().isoformat()),
        ).lastrowid

    def active_reminders(self) -> list[dict]:
        return self._all("SELECT id, text, due_at, repeat FROM reminders WHERE active = 1 ORDER BY due_at")

    def due_reminders(self, now_utc: datetime) -> list[dict]:
        return self._all(
            "SELECT id, text, due_at, repeat FROM reminders WHERE active = 1 AND due_at <= ? ORDER BY due_at",
            (now_utc.isoformat(),),
        )

    def reschedule_reminder(self, reminder_id: int, due_at_utc: datetime) -> None:
        self._exec("UPDATE reminders SET due_at = ? WHERE id = ?", (due_at_utc.isoformat(), reminder_id))

    def deactivate_reminder(self, reminder_id: int) -> bool:
        return self._exec("UPDATE reminders SET active = 0 WHERE id = ?", (reminder_id,)).rowcount > 0

    # --- conversation history ---
    def current_session(self) -> int:
        return int(self.get_kv("session") or 1)

    def new_session(self) -> int:
        session = self.current_session() + 1
        self.set_kv("session", str(session))
        return session

    def load_messages(self, session: int) -> list[dict]:
        rows = self._all("SELECT role, content FROM messages WHERE session = ? ORDER BY id", (session,))
        return [{"role": r["role"], "content": json.loads(r["content"])} for r in rows]

    def append_message(self, session: int, role: str, content) -> None:
        self._exec(
            "INSERT INTO messages (session, role, content) VALUES (?, ?, ?)",
            (session, role, json.dumps(content, ensure_ascii=False)),
        )

    # --- key/value ---
    def get_kv(self, key: str) -> str | None:
        rows = self._all("SELECT value FROM kv WHERE key = ?", (key,))
        return rows[0]["value"] if rows else None

    def set_kv(self, key: str, value: str) -> None:
        self._exec("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))
