package bd.aiap.core

import com.fasterxml.jackson.databind.ObjectMapper

/** Exports everything to one JSON file (for a new phone or safekeeping) and restores it. */
object Backup {
    private val json = ObjectMapper()

    fun export(store: Store, nowMillis: Long = System.currentTimeMillis()): String {
        val habits = store.habits()
        val data = mapOf(
            "app" to "bd.aiap",
            "version" to 1,
            "exported_at" to nowMillis,
            "memories" to store.searchMemories(null, null).map { mapOf("category" to it.category, "content" to it.content) },
            "items" to store.getItems(null, includeDone = true).map {
                mapOf("list" to it.listName, "text" to it.text, "due_date" to it.dueDate, "priority" to it.priority, "done" to it.done)
            },
            "reminders" to store.activeReminders().map { mapOf("text" to it.text, "due_at" to it.dueAtMillis, "repeat" to it.repeat.key) },
            "expenses" to store.expenses("0000-01-01", "9999-12-31").map {
                mapOf("amount" to it.amount, "category" to it.category, "note" to it.note, "date" to it.date)
            },
            "habits" to habits.map { mapOf("name" to it.name, "done_dates" to store.habitDoneDates(it.id, "0000-01-01").sorted()) },
        )
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(data)
    }

    /** Adds the backup's contents to [store] and schedules its reminders. Returns how many records were restored. */
    fun import(text: String, store: Store, alarms: AlarmScheduler): Int {
        val root = json.readTree(text)
        require(root.path("app").asText() == "bd.aiap") { "এটি এই অ্যাপের ব্যাকআপ ফাইল নয়।" }
        var count = 0
        root.path("memories").forEach {
            store.addMemory(it.path("category").asText("notes"), it.path("content").asText())
            count++
        }
        root.path("items").forEach {
            val id = store.addItem(
                it.path("list").asText("todo"),
                it.path("text").asText(),
                it.path("due_date").textOrNull(),
                it.path("priority").textOrNull(),
            )
            if (it.path("done").asBoolean(false)) store.setItemDone(id, true)
            count++
        }
        root.path("reminders").forEach {
            val text = it.path("text").asText()
            val dueAt = it.path("due_at").asLong()
            val repeat = Repeat.of(it.path("repeat").asText("none"))
            val id = store.addReminder(text, dueAt, repeat)
            alarms.schedule(Reminder(id, text, dueAt, repeat))
            count++
        }
        root.path("expenses").forEach {
            store.addExpense(it.path("amount").asDouble(), it.path("category").asText("other"), it.path("note").asText(), it.path("date").asText())
            count++
        }
        root.path("habits").forEach { habit ->
            val id = store.addHabit(habit.path("name").asText())
            habit.path("done_dates").forEach { store.setHabitDone(id, it.asText(), true) }
            count++
        }
        return count
    }

    private fun com.fasterxml.jackson.databind.JsonNode.textOrNull(): String? = if (isNull || isMissingNode) null else asText()
}

/** Phone actions are stored with each chat message as a small JSON array. */
object ActionsJson {
    private val json = ObjectMapper()

    fun encode(actions: List<PhoneAction>): String? =
        if (actions.isEmpty()) null
        else json.writeValueAsString(actions.map { mapOf("type" to it.type, "label" to it.label, "target" to it.target, "text" to it.text, "time" to it.time) })

    fun decode(text: String?): List<PhoneAction> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.readTree(text).map {
                PhoneAction(
                    it.path("type").asText(),
                    it.path("label").asText(),
                    it.path("target").asText(),
                    it.path("text").let { t -> if (t.isNull || t.isMissingNode) null else t.asText() },
                    it.path("time").let { t -> if (t.isNull || t.isMissingNode) null else t.asText() },
                )
            }
        }.getOrDefault(emptyList())
    }
}
