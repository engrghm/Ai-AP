"""Checks for due reminders and the daily briefing; the caller decides how to deliver them."""

import logging
from datetime import datetime, timezone
from typing import Callable

from .brain import Assistant
from .tools import next_occurrence

log = logging.getLogger(__name__)


class ReminderScheduler:
    def __init__(self, assistant: Assistant, notify: Callable[[str], None]):
        self.assistant = assistant
        self.db = assistant.db
        self.tz = assistant.config.timezone
        self.notify = notify

    def tick(self) -> None:
        """Run once per check interval."""
        self._send_due_reminders()
        self._maybe_send_briefing()

    def _send_due_reminders(self) -> None:
        now = datetime.now(timezone.utc)
        for r in self.db.due_reminders(now):
            self.notify(f"⏰ রিমাইন্ডার: {r['text']}")
            if r["repeat"] == "none":
                self.db.deactivate_reminder(r["id"])
                continue
            nxt = next_occurrence(datetime.fromisoformat(r["due_at"]), r["repeat"], self.tz)
            while nxt <= now:  # skip occurrences missed while the assistant was offline
                nxt = next_occurrence(nxt, r["repeat"], self.tz)
            self.db.reschedule_reminder(r["id"], nxt)

    def _maybe_send_briefing(self) -> None:
        briefing_time = self.assistant.config.briefing_time
        if not briefing_time:
            return
        now = datetime.now(self.tz)
        today = now.strftime("%Y-%m-%d")
        if now.strftime("%H:%M") < briefing_time or self.db.get_kv("last_briefing") == today:
            return
        self.db.set_kv("last_briefing", today)
        try:
            self.notify("🌅 " + self.assistant.briefing())
        except Exception:
            log.exception("Briefing failed")
