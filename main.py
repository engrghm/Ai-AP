"""Personal AI assistant.

    python main.py            # chat in the terminal (reminders pop up while it runs)
    python main.py telegram   # run as a Telegram bot (reminders on your phone)
    python main.py briefing   # print today's briefing once
"""

import logging
import sys
import threading

from assistant.brain import Assistant
from assistant.config import load_config
from assistant.db import Database
from assistant.scheduler import ReminderScheduler

CHECK_INTERVAL_SECONDS = 30


def run_cli(assistant: Assistant) -> None:
    print_lock = threading.Lock()

    def notify(text: str) -> None:
        with print_lock:
            print(f"\n\n{text}\n\nআপনি> ", end="", flush=True)

    scheduler = ReminderScheduler(assistant, notify)
    stop = threading.Event()

    def loop() -> None:
        while not stop.is_set():
            try:
                scheduler.tick()
            except Exception:
                logging.exception("Scheduler tick failed")
            stop.wait(CHECK_INTERVAL_SECONDS)

    threading.Thread(target=loop, daemon=True).start()

    print(f"আসসালামু আলাইকুম {assistant.config.owner_name}! (/briefing, /reset, /exit)\n")
    while True:
        try:
            text = input("আপনি> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not text:
            continue
        if text in ("/exit", "/quit"):
            break
        try:
            if text == "/briefing":
                reply = assistant.briefing()
            elif text == "/reset":
                assistant.reset()
                reply = "নতুন কথোপকথন শুরু হলো (আপনার সংরক্ষিত তথ্য ঠিক আছে)।"
            else:
                reply = assistant.chat(text)
        except Exception as exc:
            logging.exception("Chat failed")
            reply = f"⚠️ সমস্যা: {exc}"
        with print_lock:
            print(f"\nসহকারী> {reply}\n")
    stop.set()
    print("আল্লাহ হাফেজ!")


def main() -> None:
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = load_config()
    assistant = Assistant(config, Database(config.db_path))
    mode = sys.argv[1] if len(sys.argv) > 1 else "cli"

    if mode == "telegram":
        if not config.telegram_token:
            sys.exit("TELEGRAM_BOT_TOKEN .env ফাইলে দিন।")
        logging.getLogger().setLevel(logging.INFO)
        from assistant.telegram_bot import run_telegram

        run_telegram(assistant)
    elif mode == "briefing":
        print(assistant.briefing())
    else:
        run_cli(assistant)


if __name__ == "__main__":
    main()
