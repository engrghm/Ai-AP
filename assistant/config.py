import os
from dataclasses import dataclass
from zoneinfo import ZoneInfo

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Config:
    owner_name: str
    timezone: ZoneInfo
    briefing_time: str | None
    db_path: str
    model: str
    telegram_token: str | None
    telegram_owner_chat_id: int | None


def load_config() -> Config:
    chat_id = os.getenv("TELEGRAM_OWNER_CHAT_ID", "").strip()
    return Config(
        owner_name=os.getenv("OWNER_NAME", "বন্ধু"),
        timezone=ZoneInfo(os.getenv("TIMEZONE", "Asia/Dhaka")),
        briefing_time=os.getenv("BRIEFING_TIME", "08:00").strip() or None,
        db_path=os.getenv("DB_PATH", "data/assistant.db"),
        model=os.getenv("CLAUDE_MODEL", "claude-opus-5-5"),
        telegram_token=os.getenv("TELEGRAM_BOT_TOKEN", "").strip() or None,
        telegram_owner_chat_id=int(chat_id) if chat_id else None,
    )
