"""Offline tests: storage, tools, scheduler and the chat loop with a fake Claude client."""

import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from zoneinfo import ZoneInfo

from assistant.brain import Assistant
from assistant.config import Config
from assistant.db import Database
from assistant.scheduler import ReminderScheduler
from assistant.tools import TOOL_DEFINITIONS, ToolExecutor, next_occurrence

TZ = ZoneInfo("Asia/Dhaka")


def make_config(**overrides):
    base = dict(owner_name="Masum", timezone=TZ, briefing_time=None, db_path=":memory:",
                model="claude-opus-5-5", telegram_token=None, telegram_owner_chat_id=None)
    return Config(**{**base, **overrides})


def test_tool_schemas_are_strict():
    for tool in TOOL_DEFINITIONS:
        schema = tool["input_schema"]
        assert schema["additionalProperties"] is False
        assert set(schema["required"]) == set(schema["properties"])


def test_memory_items_and_reminders(tmp_path):
    db = Database(str(tmp_path / "a.db"))
    tools = ToolExecutor(db, TZ)
    mid = json.loads(tools.run("remember", {"category": "Family", "content": "Wife's birthday is 12 March"}))["saved_memory_id"]
    found = json.loads(tools.run("search_memory", {"query": "birthday", "category": None}))["matches"]
    assert found[0]["id"] == mid and found[0]["category"] == "family"

    iid = json.loads(tools.run("add_item", {"list_name": "Shopping", "text": "milk", "due_date": None, "priority": None}))["added_item_id"]
    tools.run("complete_item", {"item_id": iid})
    assert json.loads(tools.run("get_items", {"list_name": "shopping", "include_done": False}))["items"] == []

    when = (datetime.now(TZ) + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M")
    out = json.loads(tools.run("set_reminder", {"text": "Pay bill", "when": when, "repeat": "none"}))
    assert out["reminder_id"] == 1
    assert len(json.loads(tools.run("get_reminders", {}))["reminders"]) == 1


def test_monthly_repeat_clamps_day():
    due = datetime(2026, 1, 31, 9, 0, tzinfo=TZ).astimezone(timezone.utc)
    nxt = next_occurrence(due, "monthly", TZ).astimezone(TZ)
    assert (nxt.month, nxt.day, nxt.hour) == (2, 28, 9)


def test_scheduler_fires_and_reschedules(tmp_path):
    db = Database(str(tmp_path / "a.db"))
    past = datetime.now(timezone.utc) - timedelta(days=3, minutes=1)
    db.add_reminder("Medicine", past, "daily")
    db.add_reminder("Call mom", past, "none")
    sent = []
    ReminderScheduler(Assistant(make_config(), db, client=object()), sent.append).tick()
    assert len(sent) == 2
    remaining = db.active_reminders()
    assert [r["text"] for r in remaining] == ["Medicine"]
    assert datetime.fromisoformat(remaining[0]["due_at"]) > datetime.now(timezone.utc)


class FakeBlock(SimpleNamespace):
    def model_dump(self, **_):
        return {k: v for k, v in vars(self).items()}


class FakeClient:
    """Replays a scripted tool call, then a text answer."""

    def __init__(self):
        self.calls = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self.create))

    def create(self, **kwargs):
        self.calls.append(json.loads(json.dumps(kwargs["messages"], default=str)))
        if len(self.calls) == 1:
            return SimpleNamespace(stop_reason="tool_use", content=[FakeBlock(
                type="tool_use", id="t1", name="add_item",
                input={"list_name": "todo", "text": "Submit report", "due_date": None, "priority": "high"})])
        return SimpleNamespace(stop_reason="end_turn", content=[FakeBlock(type="text", text="যোগ করেছি ✅")])


def test_chat_loop_runs_tools_and_persists(tmp_path):
    db = Database(str(tmp_path / "a.db"))
    client = FakeClient()
    assistant = Assistant(make_config(), db, client=client)
    assert assistant.chat("রিপোর্ট জমা দেওয়া todo তে রাখো") == "যোগ করেছি ✅"
    assert db.get_items("todo", False)[0]["text"] == "Submit report"
    roles = [m["role"] for m in client.calls[1]]
    assert roles == ["user", "system", "assistant", "user"]
    assert len(db.load_messages(db.current_session())) == 5
