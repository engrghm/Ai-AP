package bd.aiap.core

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Categories the UI knows how to show nicely; Claude may still invent others. */
val MEMORY_CATEGORIES = linkedMapOf(
    "profile" to "ব্যক্তিগত তথ্য",
    "family" to "পরিবার",
    "work" to "কাজ",
    "health" to "স্বাস্থ্য",
    "finance" to "টাকা-পয়সা",
    "goals" to "লক্ষ্য",
    "contacts" to "যোগাযোগ",
    "ideas" to "আইডিয়া",
    "preferences" to "পছন্দ-অপছন্দ",
    "notes" to "নোট",
)

/** Spending categories with Bangla names for the tracker screen. */
val EXPENSE_CATEGORIES = linkedMapOf(
    "food" to "খাবার ও বাজার",
    "transport" to "যাতায়াত",
    "bills" to "বিল (বিদ্যুৎ, গ্যাস, নেট, মোবাইল)",
    "rent" to "বাসা ভাড়া",
    "shopping" to "কেনাকাটা",
    "health" to "চিকিৎসা",
    "education" to "পড়াশোনা",
    "family" to "পরিবার",
    "charity" to "দান-সদকা",
    "income" to "আয়",
    "other" to "অন্যান্য",
)

private val STR = mapOf("type" to "string")
private val NUM = mapOf("type" to "number")
private val BOOL = mapOf("type" to "boolean")
private val OPT_STR = mapOf("type" to listOf("string", "null"))
private val INT = mapOf("type" to "integer")

private fun described(base: Map<String, Any>, description: String) = base + ("description" to description)

/** One tool, described once and translated for each AI service. Every property is required; optional ones are nullable. */
class ToolSpec(val name: String, val description: String, val properties: Map<String, Map<String, Any>>) {
    fun toClaude(): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .strict(true)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(properties.keys.toList())
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()
    }

    /** Gemini function declaration: OpenAPI-style schema, with `nullable` instead of a "null" type. */
    fun toGemini(): Map<String, Any> {
        val decl = mutableMapOf<String, Any>("name" to name, "description" to description)
        if (properties.isNotEmpty()) {
            decl["parameters"] = mapOf(
                "type" to "OBJECT",
                "properties" to properties.mapValues { (_, schema) ->
                    val type = schema["type"]
                    val types = if (type is List<*>) type.filterIsInstance<String>() else listOf(type as String)
                    buildMap {
                        put("type", types.first { it != "null" }.uppercase())
                        if ("null" in types) put("nullable", true)
                        schema["description"]?.let { put("description", it) }
                        schema["enum"]?.let { put("enum", it) }
                    }
                },
                "required" to properties.filterValues { (it["type"] as? List<*>)?.contains("null") != true }.keys.toList(),
            )
        }
        return decl
    }
}

private fun tool(name: String, description: String, properties: Map<String, Map<String, Any>>) = ToolSpec(name, description, properties)

val TOOL_SPECS: List<ToolSpec> = listOf(
    tool(
        "remember",
        "Save a durable fact about the owner to long-term memory. Use whenever the owner shares something " +
            "worth knowing later, even if they did not say 'remember'. Search first and use update_memory " +
            "instead if a matching fact already exists. Never store passwords, PINs or full card/account numbers.",
        mapOf(
            "category" to described(STR, "One of: ${MEMORY_CATEGORIES.keys.joinToString()} (or another short lowercase word if none fits)"),
            "content" to described(STR, "The fact as a clear standalone sentence, in the owner's language"),
        ),
    ),
    tool(
        "search_memory",
        "Search long-term memory. Pass null for both to list everything. Words in query are AND-matched.",
        mapOf("query" to OPT_STR, "category" to OPT_STR),
    ),
    tool("update_memory", "Replace the content of an existing memory by id.", mapOf("memory_id" to INT, "content" to STR)),
    tool("forget_memory", "Delete a memory by id (only when the owner asks or it is clearly wrong).", mapOf("memory_id" to INT)),
    tool(
        "add_item",
        "Add an item to one of the owner's lists (todo, shopping, goals, books, ideas, or any list name they use).",
        mapOf(
            "list_name" to described(STR, "Short lowercase list name, e.g. todo, shopping, books"),
            "text" to STR,
            "due_date" to described(OPT_STR, "Optional local date YYYY-MM-DD"),
            "priority" to described(OPT_STR, "high, medium, low or null"),
        ),
    ),
    tool(
        "get_items",
        "Get items from the owner's lists. list_name null means all lists.",
        mapOf("list_name" to OPT_STR, "include_done" to mapOf("type" to "boolean")),
    ),
    tool("complete_item", "Mark a list item as done.", mapOf("item_id" to INT)),
    tool("delete_item", "Delete a list item entirely.", mapOf("item_id" to INT)),
    tool(
        "set_reminder",
        "Schedule a reminder notification on the owner's phone. Write the text as the message they should " +
            "receive, in their language.",
        mapOf(
            "text" to STR,
            "when" to described(STR, "Local date-time YYYY-MM-DDTHH:MM"),
            "repeat" to mapOf("type" to "string", "enum" to Repeat.entries.map { it.key }),
        ),
    ),
    tool("get_reminders", "List all active reminders.", emptyMap()),
    tool("cancel_reminder", "Cancel an active reminder by id.", mapOf("reminder_id" to INT)),
    tool(
        "add_expense",
        "Record money the owner spent or received (e.g. 'bazar e 500 taka khoroch', 'salary pelam 40000'). " +
            "Use a positive amount for spending and a negative amount for money received.",
        mapOf(
            "amount" to described(NUM, "Amount in taka; negative for income/received"),
            "category" to described(STR, "One of: ${EXPENSE_CATEGORIES.keys.joinToString()}"),
            "note" to described(STR, "Short description, in the owner's language"),
            "date" to described(OPT_STR, "Local date YYYY-MM-DD; null for today"),
        ),
    ),
    tool(
        "get_expenses",
        "Get spending between two dates with totals per category. Nulls mean the current month so far.",
        mapOf("from_date" to OPT_STR, "to_date" to OPT_STR),
    ),
    tool("delete_expense", "Delete a recorded expense by id.", mapOf("expense_id" to INT)),
    tool(
        "add_habit",
        "Start tracking a daily habit (prayer, exercise, reading, water, ...). Optionally also set a daily reminder.",
        mapOf(
            "name" to described(STR, "Short habit name in the owner's language"),
            "reminder_time" to described(OPT_STR, "Optional daily reminder time HH:MM"),
        ),
    ),
    tool(
        "log_habit",
        "Mark a habit as done (or not done) for a day, when the owner says they did it.",
        mapOf("habit_id" to INT, "date" to described(OPT_STR, "YYYY-MM-DD; null for today"), "done" to BOOL),
    ),
    tool("get_habits", "List habits with today's status and current streak.", emptyMap()),
    tool("delete_habit", "Stop tracking a habit.", mapOf("habit_id" to INT)),
    tool(
        "prepare_phone_action",
        "Prepare a button the owner can tap to do something on the phone: call or message someone, " +
            "send WhatsApp/email, open a place in maps, search the web, add a calendar event or set an alarm. " +
            "Look up phone numbers and emails in memory first. Nothing happens until the owner taps it.",
        mapOf(
            "type" to mapOf("type" to "string", "enum" to PHONE_ACTION_TYPES),
            "label" to described(STR, "Button text in the owner's language, e.g. 'আম্মুকে কল করুন'"),
            "target" to described(
                STR,
                "Phone number for call/sms/whatsapp, email address, place for map, search text for web, " +
                    "event title for calendar, alarm label for alarm",
            ),
            "text" to described(OPT_STR, "Message body for sms/whatsapp/email, or event details"),
            "time" to described(OPT_STR, "Local YYYY-MM-DDTHH:MM for calendar or alarm, else null"),
        ),
    ),
)

val TOOL_DEFINITIONS: List<BetaTool> = TOOL_SPECS.map { it.toClaude() }
val GEMINI_FUNCTIONS: List<Map<String, Any>> = TOOL_SPECS.map { it.toGemini() }

class ToolExecutor(
    private val store: Store,
    private val alarms: AlarmScheduler,
    private val zone: () -> ZoneId,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = ObjectMapper()
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)")
    private val actions = mutableListOf<PhoneAction>()

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()

    /** Phone actions prepared during this turn, handed to the UI with the reply. */
    fun takeActions(): List<PhoneAction> = actions.toList().also { actions.clear() }

    fun formatLocal(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone()).format(display)

    /** Runs a tool and returns its JSON result; throws on bad input so the caller can report an error. */
    fun run(name: String, input: Map<*, *>): String {
        fun str(key: String): String = input[key] as? String ?: throw IllegalArgumentException("$key is required")
        fun optStr(key: String): String? = (input[key] as? String)?.takeIf { it.isNotBlank() }
        fun long(key: String): Long = (input[key] as? Number)?.toLong() ?: throw IllegalArgumentException("$key must be an integer")
        fun date(key: String): String = optStr(key)?.also { LocalDate.parse(it) } ?: today().toString()

        val result: Any = when (name) {
            "remember" -> mapOf("saved_memory_id" to store.addMemory(str("category").trim().lowercase(), str("content")))
            "search_memory" -> {
                val matches = store.searchMemories(optStr("query"), optStr("category")?.lowercase())
                if (matches.isEmpty()) {
                    mapOf("matches" to emptyList<Any>(), "categories_in_use" to store.searchMemories(null, null).map { it.category }.distinct())
                } else {
                    mapOf("matches" to matches.map { mapOf("id" to it.id, "category" to it.category, "content" to it.content) })
                }
            }
            "update_memory" -> mapOf("updated" to store.updateMemory(long("memory_id"), str("content")))
            "forget_memory" -> mapOf("deleted" to store.deleteMemory(long("memory_id")))
            "add_item" -> {
                val due = optStr("due_date")?.also { LocalDate.parse(it) }
                val priority = optStr("priority")?.lowercase()?.takeIf { it in setOf("high", "medium", "low") }
                mapOf("added_item_id" to store.addItem(str("list_name").trim().lowercase(), str("text"), due, priority))
            }
            "get_items" -> mapOf(
                "items" to store.getItems(optStr("list_name")?.lowercase(), input["include_done"] == true).map {
                    mapOf("id" to it.id, "list" to it.listName, "text" to it.text, "due_date" to it.dueDate, "priority" to it.priority, "done" to it.done)
                }
            )
            "complete_item" -> mapOf("completed" to store.setItemDone(long("item_id"), true))
            "delete_item" -> mapOf("deleted" to store.deleteItem(long("item_id")))
            "set_reminder" -> setReminder(str("text"), str("when"), Repeat.of(str("repeat")))
            "get_reminders" -> mapOf(
                "reminders" to store.activeReminders().map {
                    mapOf("id" to it.id, "text" to it.text, "when" to formatLocal(it.dueAtMillis), "repeat" to it.repeat.key)
                }
            )
            "cancel_reminder" -> {
                val id = long("reminder_id")
                alarms.cancel(id)
                mapOf("cancelled" to store.deactivateReminder(id))
            }
            "add_expense" -> {
                val amount = (input["amount"] as? Number)?.toDouble() ?: throw IllegalArgumentException("amount must be a number")
                val category = str("category").trim().lowercase().let { if (it in EXPENSE_CATEGORIES) it else "other" }
                val id = store.addExpense(amount, if (amount < 0) "income" else category, str("note"), date("date"))
                mapOf("expense_id" to id, "month_so_far" to expenseSummary(today().withDayOfMonth(1).toString(), today().toString())["total_spent"])
            }
            "get_expenses" -> expenseSummary(
                optStr("from_date")?.also { LocalDate.parse(it) } ?: today().withDayOfMonth(1).toString(),
                optStr("to_date")?.also { LocalDate.parse(it) } ?: today().toString(),
            )
            "delete_expense" -> mapOf("deleted" to store.deleteExpense(long("expense_id")))
            "add_habit" -> {
                val habitName = str("name")
                val id = store.addHabit(habitName)
                val time = optStr("reminder_time")
                val reminder = time?.let {
                    val at = today().atTime(java.time.LocalTime.parse(it)).toString()
                    setReminder("✅ $habitName", at, Repeat.DAILY)
                }
                mapOf("habit_id" to id, "daily_reminder" to reminder)
            }
            "log_habit" -> {
                val id = long("habit_id")
                require(store.habits().any { it.id == id }) { "No habit with id $id" }
                store.setHabitDone(id, date("date"), input["done"] != false)
                mapOf("ok" to true, "streak" to streak(store.habitDoneDates(id, today().minusDays(400).toString()), today()))
            }
            "get_habits" -> mapOf("habits" to habitStatus())
            "delete_habit" -> mapOf("deleted" to store.deleteHabit(long("habit_id")))
            "prepare_phone_action" -> {
                val type = str("type")
                require(type in PHONE_ACTION_TYPES) { "type must be one of $PHONE_ACTION_TYPES" }
                val time = optStr("time")?.also { LocalDateTime.parse(it.take(16)) }
                require(type !in setOf("calendar", "alarm") || time != null) { "time is required for $type" }
                actions += PhoneAction(type, str("label"), str("target"), optStr("text"), time?.take(16))
                mapOf("prepared" to true, "note" to "A button was added under your reply; the owner must tap it.")
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
        return json.writeValueAsString(result)
    }

    fun expenseSummary(from: String, to: String): Map<String, Any> {
        val list = store.expenses(from, to)
        val spent = list.filter { it.amount > 0 }
        return mapOf(
            "from" to from,
            "to" to to,
            "total_spent" to spent.sumOf { it.amount },
            "total_received" to -list.filter { it.amount < 0 }.sumOf { it.amount },
            "by_category" to spent.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } },
            "items" to list.take(100).map { mapOf("id" to it.id, "date" to it.date, "amount" to it.amount, "category" to it.category, "note" to it.note) },
        )
    }

    fun habitStatus(): List<Map<String, Any>> {
        val today = today()
        return store.habits().map { habit ->
            val done = store.habitDoneDates(habit.id, today.minusDays(400).toString())
            mapOf(
                "id" to habit.id,
                "name" to habit.name,
                "done_today" to (today.toString() in done),
                "streak" to streak(done, today),
                "last_7_days" to (0L..6L).count { today.minusDays(it).toString() in done },
            )
        }
    }

    private fun setReminder(text: String, `when`: String, repeat: Repeat): Map<String, Any> {
        var due = LocalDateTime.parse(`when`.take(16)).atZone(zone()).toInstant().toEpochMilli()
        if (due <= now()) {
            require(repeat != Repeat.NONE) { "That time is already in the past. Ask the owner for a future time." }
            due = nextFutureOccurrence(due, repeat, zone(), now())
        }
        val id = store.addReminder(text, due, repeat)
        alarms.schedule(Reminder(id, text, due, repeat))
        return mapOf("reminder_id" to id, "scheduled_for" to formatLocal(due), "repeat" to repeat.key)
    }
}
