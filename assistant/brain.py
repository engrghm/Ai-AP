"""The assistant itself: talks to Claude, runs tools, keeps the conversation."""

import json
import logging
import threading
from datetime import datetime

import anthropic

from .config import Config
from .db import Database
from .tools import TOOL_DEFINITIONS, ToolExecutor

log = logging.getLogger(__name__)

MAX_TOOL_ROUNDS = 12
# Long-term knowledge lives in the database, so the chat transcript can start fresh
# once it gets long instead of being trimmed (trimming would rewrite history).
MAX_SESSION_MESSAGES = 80
BETAS = ["server-side-fallback-2026-07-01"]

SYSTEM_PROMPT = """You are the personal AI assistant of {owner}. You are their second brain and their \
guide: you remember everything they tell you, keep their lists, remind them of things at the right \
time, and help them plan and stay on track.

How you work:
- Your memory is the database behind your tools, not this chat. Whenever {owner} shares anything \
worth knowing later (people, dates, plans, health, money, preferences, goals, habits), save it with \
`remember` without being asked. Before saving, search so you update instead of duplicating.
- Before answering anything about {owner}'s life, check your memory and lists with the tools rather \
than guessing. If you don't know, say so and ask.
- Lists: todo, shopping, goals or any other list they name go in `add_item`. Mark things done when \
they say so.
- Reminders: when they ask to be reminded, or mention something time-bound (a bill, a meeting, \
medicine, a birthday), set a reminder with `set_reminder`. Resolve relative times ("tomorrow \
evening", "in 2 hours") against the current local time you are given. If the time is ambiguous, \
pick a sensible one and tell them, so they can correct it.
- Guidance: be a proactive, practical coach. Point out what is due, what is overdue, what matters \
most today, and suggest a next step. Keep it short and concrete.
- Never store passwords, PINs or full card/account numbers; tell them to use a password manager.
- Reply in the language {owner} writes in (Bangla by default; English if they write English). Keep \
messages brief and friendly; use short lists when helpful. After changing data, confirm briefly \
what you saved or scheduled."""

BRIEFING_PROMPT = """Write {owner}'s briefing for {when}. Using the data below, give:
1. A one-line greeting.
2. Today's reminders and anything due today or overdue.
3. The top 3 things to focus on today, with a short reason each.
4. One short piece of encouragement or advice tied to their goals.
Keep it under 150 words, in Bangla, as a chat message. Data:

{data}"""


class Assistant:
    def __init__(self, config: Config, db: Database, client: anthropic.Anthropic | None = None):
        self.config = config
        self.db = db
        self.client = client or anthropic.Anthropic()
        self.tools = ToolExecutor(db, config.timezone)
        self.system = [
            {
                "type": "text",
                "text": SYSTEM_PROMPT.format(owner=config.owner_name),
                "cache_control": {"type": "ephemeral"},
            }
        ]
        self._lock = threading.Lock()  # one conversation turn at a time

    # ----- helpers -----
    def _now(self) -> datetime:
        return datetime.now(self.config.timezone)

    def _context_note(self) -> str:
        """Per-turn context: current time, plus what is due today so guidance stays timely."""
        now = self._now()
        today = now.strftime("%Y-%m-%d")
        due_items = [
            i for i in self.db.get_items(None, include_done=False) if i["due_date"] and i["due_date"] <= today
        ]
        upcoming = [
            r for r in self.tools.tool_get_reminders()["reminders"] if r["when"].startswith(today)
        ]
        note = f"Current local time: {now.strftime('%Y-%m-%d %H:%M, %A')} ({self.config.timezone.key})."
        if due_items:
            note += "\nList items due today or overdue: " + json.dumps(due_items, ensure_ascii=False)
        if upcoming:
            note += "\nReminders later today: " + json.dumps(upcoming, ensure_ascii=False)
        return note

    def _create(self, messages: list[dict], tools: list[dict] | None, effort: str):
        kwargs = {"tools": tools} if tools else {}
        return self.client.beta.messages.create(
            model=self.config.model,
            max_tokens=16000,
            system=self.system,
            messages=messages,
            output_config={"effort": effort},
            betas=BETAS,
            fallbacks="default",
            **kwargs,
        )

    @staticmethod
    def _dump(content) -> list[dict]:
        return [block.model_dump(mode="json", exclude_none=True) for block in content]

    @staticmethod
    def _text(content) -> str:
        return "\n".join(b.text for b in content if b.type == "text").strip()

    # ----- conversation -----
    def chat(self, user_text: str) -> str:
        with self._lock:
            session = self.db.current_session()
            messages = self.db.load_messages(session)
            if len(messages) >= MAX_SESSION_MESSAGES:
                session, messages = self.db.new_session(), []

            def add(role: str, content) -> None:
                messages.append({"role": role, "content": content})
                self.db.append_message(session, role, content)

            add("user", user_text)
            add("system", self._context_note())

            try:
                for _ in range(MAX_TOOL_ROUNDS):
                    response = self._create(messages, TOOL_DEFINITIONS, effort="medium")

                    if response.stop_reason == "refusal":
                        reply = "দুঃখিত, এই অনুরোধে আমি সাহায্য করতে পারছি না।"
                        add("assistant", [{"type": "text", "text": reply}])
                        return reply

                    add("assistant", self._dump(response.content))
                    if response.stop_reason != "tool_use":
                        return self._text(response.content) or "✅"

                    results = []
                    for block in response.content:
                        if block.type != "tool_use":
                            continue
                        try:
                            output = self.tools.run(block.name, block.input)
                            results.append({"type": "tool_result", "tool_use_id": block.id, "content": output})
                        except Exception as exc:  # report tool errors back to Claude
                            log.warning("Tool %s failed: %s", block.name, exc)
                            results.append(
                                {"type": "tool_result", "tool_use_id": block.id, "content": str(exc), "is_error": True}
                            )
                    add("user", results)
                return "অনেকগুলো ধাপ হয়ে গেছে, আবার একটু সহজ করে বলবেন?"
            except Exception:
                # A failed turn may leave a dangling tool call; start a clean transcript next time.
                self.db.new_session()
                raise

    def reset(self) -> None:
        with self._lock:
            self.db.new_session()

    # ----- proactive guidance -----
    def briefing(self, when: str = "today") -> str:
        data = {
            "now": self._now().strftime("%Y-%m-%d %H:%M, %A"),
            "open_list_items": self.db.get_items(None, include_done=False),
            "active_reminders": self.tools.tool_get_reminders()["reminders"],
            "goals_and_profile": self.db.search_memories(None, "goals") + self.db.search_memories(None, "profile"),
        }
        prompt = BRIEFING_PROMPT.format(
            owner=self.config.owner_name, when=when, data=json.dumps(data, ensure_ascii=False, indent=1)
        )
        response = self._create([{"role": "user", "content": prompt}], None, effort="low")
        if response.stop_reason == "refusal":
            return "আজকের ব্রিফিং তৈরি করা যায়নি।"
        return self._text(response.content)
