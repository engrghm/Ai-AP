"""Tools Claude can call to read and change what it knows about the owner."""

import calendar
import json
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .db import Database

REPEATS = ["none", "daily", "weekly", "monthly"]


def _schema(properties: dict) -> dict:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _tool(name: str, description: str, properties: dict) -> dict:
    return {"name": name, "description": description, "strict": True, "input_schema": _schema(properties)}


STR = {"type": "string"}
OPT_STR = {"type": ["string", "null"]}
INT = {"type": "integer"}

TOOL_DEFINITIONS = [
    _tool(
        "remember",
        "Save a durable fact about the owner to long-term memory: personal info, family, health, "
        "finances, preferences, goals, habits. Never store passwords, PINs or full card/account numbers. "
        "Use whenever the owner shares something worth knowing later, even if they did not say 'remember'. Check search_memory "
        "first and use update_memory instead if a matching fact already exists.",
        {
            "category": {**STR, "description": "Short lowercase category, e.g. profile, family, health, work, finance, goals, preferences, contacts, notes"},
            "content": {**STR, "description": "The fact, written as a clear standalone sentence"},
        },
    ),
    _tool(
        "search_memory",
        "Search long-term memory. Pass null for both to list everything. Words in query are AND-matched.",
        {"query": OPT_STR, "category": OPT_STR},
    ),
    _tool("update_memory", "Replace the content of an existing memory by id.", {"memory_id": INT, "content": STR}),
    _tool("forget_memory", "Delete a memory by id (only when the owner asks or it is clearly wrong).", {"memory_id": INT}),
    _tool(
        "add_item",
        "Add an item to one of the owner's lists (todo, shopping, goals, ideas, books, or any list name they use).",
        {
            "list_name": {**STR, "description": "Lowercase list name, e.g. todo, shopping, goals"},
            "text": STR,
            "due_date": {**OPT_STR, "description": "Optional local date YYYY-MM-DD"},
            "priority": {**OPT_STR, "description": "high, medium, low or null"},
        },
    ),
    _tool(
        "get_items",
        "Get items from the owner's lists. list_name null means all lists.",
        {"list_name": OPT_STR, "include_done": {"type": "boolean"}},
    ),
    _tool("complete_item", "Mark a list item as done.", {"item_id": INT}),
    _tool("delete_item", "Delete a list item entirely.", {"item_id": INT}),
    _tool(
        "set_reminder",
        "Schedule a reminder that will be pushed to the owner at the given time. Write the text as the "
        "message they should receive, in their language.",
        {
            "text": STR,
            "when": {**STR, "description": "Local date-time in the owner's timezone, YYYY-MM-DDTHH:MM"},
            "repeat": {"type": "string", "enum": REPEATS},
        },
    ),
    _tool("get_reminders", "List all active reminders.", {}),
    _tool("cancel_reminder", "Cancel an active reminder by id.", {"reminder_id": INT}),
]


def next_occurrence(due: datetime, repeat: str, tz: ZoneInfo) -> datetime:
    """Next due time (UTC) for a repeating reminder, computed in local time so it keeps the wall-clock hour."""
    local = due.astimezone(tz)
    if repeat == "daily":
        local += timedelta(days=1)
    elif repeat == "weekly":
        local += timedelta(weeks=1)
    elif repeat == "monthly":
        year, month = (local.year + 1, 1) if local.month == 12 else (local.year, local.month + 1)
        day = min(local.day, calendar.monthrange(year, month)[1])
        local = local.replace(year=year, month=month, day=day)
    return local.replace(tzinfo=tz).astimezone(timezone.utc)


class ToolExecutor:
    def __init__(self, db: Database, tz: ZoneInfo):
        self.db = db
        self.tz = tz

    def _local(self, iso_utc: str) -> str:
        return datetime.fromisoformat(iso_utc).astimezone(self.tz).strftime("%Y-%m-%d %H:%M (%a)")

    def run(self, name: str, args: dict) -> str:
        handler = getattr(self, f"tool_{name}", None)
        if handler is None:
            raise ValueError(f"Unknown tool: {name}")
        result = handler(**args)
        return result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)

    def tool_remember(self, category: str, content: str):
        return {"saved_memory_id": self.db.add_memory(category, content)}

    def tool_search_memory(self, query: str | None, category: str | None):
        results = self.db.search_memories(query, category)
        if not results and (query or category):
            return {"matches": [], "categories": self.db.memory_categories()}
        return {"matches": results}

    def tool_update_memory(self, memory_id: int, content: str):
        return {"updated": self.db.update_memory(memory_id, content)}

    def tool_forget_memory(self, memory_id: int):
        return {"deleted": self.db.delete_memory(memory_id)}

    def tool_add_item(self, list_name: str, text: str, due_date: str | None, priority: str | None):
        if due_date:
            datetime.strptime(due_date, "%Y-%m-%d")  # validate
        return {"added_item_id": self.db.add_item(list_name, text, due_date, priority)}

    def tool_get_items(self, list_name: str | None, include_done: bool):
        return {"items": self.db.get_items(list_name, include_done)}

    def tool_complete_item(self, item_id: int):
        return {"completed": self.db.complete_item(item_id)}

    def tool_delete_item(self, item_id: int):
        return {"deleted": self.db.delete_item(item_id)}

    def tool_set_reminder(self, text: str, when: str, repeat: str):
        if repeat not in REPEATS:
            raise ValueError(f"repeat must be one of {REPEATS}")
        local = datetime.fromisoformat(when)
        if local.tzinfo is None:
            local = local.replace(tzinfo=self.tz)
        due_utc = local.astimezone(timezone.utc)
        if due_utc <= datetime.now(timezone.utc) and repeat == "none":
            raise ValueError("That time is already in the past. Ask the owner for a future time.")
        reminder_id = self.db.add_reminder(text, due_utc, repeat)
        return {"reminder_id": reminder_id, "scheduled_for": self._local(due_utc.isoformat()), "repeat": repeat}

    def tool_get_reminders(self):
        return {
            "reminders": [
                {"id": r["id"], "text": r["text"], "when": self._local(r["due_at"]), "repeat": r["repeat"]}
                for r in self.db.active_reminders()
            ]
        }

    def tool_cancel_reminder(self, reminder_id: int):
        return {"cancelled": self.db.deactivate_reminder(reminder_id)}
