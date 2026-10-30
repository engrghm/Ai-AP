"""Telegram front-end: chat from your phone and receive reminders as push messages."""

import asyncio
import logging

from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes, MessageHandler, filters

from .brain import Assistant
from .scheduler import ReminderScheduler

log = logging.getLogger(__name__)
CHECK_INTERVAL_SECONDS = 30
TELEGRAM_LIMIT = 4000


async def _send(bot, chat_id: int, text: str) -> None:
    for start in range(0, len(text), TELEGRAM_LIMIT):
        await bot.send_message(chat_id=chat_id, text=text[start : start + TELEGRAM_LIMIT])


def run_telegram(assistant: Assistant) -> None:
    config = assistant.config
    owner = config.telegram_owner_chat_id
    app = Application.builder().token(config.telegram_token).build()

    def is_owner(update: Update) -> bool:
        return owner is not None and update.effective_chat.id == owner

    async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        chat_id = update.effective_chat.id
        if owner is None:
            await update.message.reply_text(
                f"আপনার chat ID: {chat_id}\n.env ফাইলে TELEGRAM_OWNER_CHAT_ID={chat_id} লিখে বট রিস্টার্ট করুন।"
            )
        elif is_owner(update):
            await update.message.reply_text(f"আসসালামু আলাইকুম {config.owner_name}! আমি প্রস্তুত। যা দরকার বলুন।")

    async def briefing(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if is_owner(update):
            await _send(context.bot, owner, await asyncio.to_thread(assistant.briefing))

    async def reset(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if is_owner(update):
            assistant.reset()
            await update.message.reply_text("নতুন কথোপকথন শুরু হলো (আপনার সংরক্ষিত তথ্য ঠিক আছে)।")

    async def on_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not is_owner(update):
            return  # personal data: ignore everyone else
        await context.bot.send_chat_action(owner, "typing")
        try:
            reply = await asyncio.to_thread(assistant.chat, update.message.text)
        except Exception:
            log.exception("Chat failed")
            reply = "⚠️ একটা সমস্যা হয়েছে, একটু পরে আবার চেষ্টা করুন।"
        await _send(context.bot, owner, reply)

    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("briefing", briefing))
    app.add_handler(CommandHandler("reset", reset))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_message))

    if owner is not None:
        loop_holder: dict = {}

        def notify(text: str) -> None:
            # Called from the worker thread; hand the send back to the bot's event loop.
            asyncio.run_coroutine_threadsafe(_send(app.bot, owner, text), loop_holder["loop"]).result()

        scheduler = ReminderScheduler(assistant, notify)

        async def check(context: ContextTypes.DEFAULT_TYPE) -> None:
            loop_holder["loop"] = asyncio.get_running_loop()
            await asyncio.to_thread(scheduler.tick)

        app.job_queue.run_repeating(check, interval=CHECK_INTERVAL_SECONDS, first=5)
    else:
        log.warning("TELEGRAM_OWNER_CHAT_ID not set: send /start to the bot to get it.")

    log.info("Telegram bot running. Ctrl+C to stop.")
    app.run_polling()
