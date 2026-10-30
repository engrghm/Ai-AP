package bd.aiap

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import bd.aiap.core.ActionsJson
import bd.aiap.core.ChatMessage
import bd.aiap.core.Expense
import bd.aiap.core.Habit
import bd.aiap.core.ListItem
import bd.aiap.core.Memory
import bd.aiap.core.PhoneAction
import bd.aiap.core.Reminder
import bd.aiap.core.Repeat
import bd.aiap.core.Store

/** Everything the assistant knows, stored privately on the phone. */
class SqliteStore(context: Context) : SQLiteOpenHelper(context, "assistant.db", null, 2), Store {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, " +
                "content TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, list_name TEXT NOT NULL, text TEXT NOT NULL, " +
                "due_date TEXT, priority TEXT, done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, due_at INTEGER NOT NULL, " +
                "repeat TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, from_user INTEGER NOT NULL, text TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE chats ADD COLUMN actions TEXT")
            db.execSQL(
                "CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL, category TEXT NOT NULL, " +
                    "note TEXT NOT NULL, date TEXT NOT NULL)"
            )
            db.execSQL("CREATE TABLE habits (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE habit_logs (habit_id INTEGER NOT NULL, date TEXT NOT NULL, PRIMARY KEY (habit_id, date))")
        }
    }

    private fun insert(table: String, values: ContentValues): Long = writableDatabase.insertOrThrow(table, null, values)

    private fun update(table: String, values: ContentValues, id: Long): Boolean =
        writableDatabase.update(table, values, "id = ?", arrayOf(id.toString())) > 0

    private fun <T> query(sql: String, args: List<String>, map: (Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(map(cursor)) }
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    // ----- memories -----
    override fun addMemory(category: String, content: String): Long = insert("memories", ContentValues().apply {
        put("category", category)
        put("content", content)
        put("updated_at", System.currentTimeMillis())
    })

    override fun searchMemories(query: String?, category: String?): List<Memory> {
        val sql = StringBuilder("SELECT id, category, content, updated_at FROM memories WHERE 1 = 1")
        val args = mutableListOf<String>()
        if (category != null) {
            sql.append(" AND category = ?")
            args += category
        }
        query?.split(" ")?.filter { it.isNotBlank() }?.forEach { word ->
            sql.append(" AND (content LIKE ? OR category LIKE ?)")
            args += "%$word%"
            args += "%$word%"
        }
        sql.append(" ORDER BY category, updated_at DESC")
        return query(sql.toString(), args) { Memory(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3)) }
    }

    override fun updateMemory(id: Long, content: String): Boolean = update("memories", ContentValues().apply {
        put("content", content)
        put("updated_at", System.currentTimeMillis())
    }, id)

    override fun deleteMemory(id: Long): Boolean =
        writableDatabase.delete("memories", "id = ?", arrayOf(id.toString())) > 0

    // ----- lists -----
    override fun addItem(listName: String, text: String, dueDate: String?, priority: String?): Long =
        insert("items", ContentValues().apply {
            put("list_name", listName)
            put("text", text)
            put("due_date", dueDate)
            put("priority", priority)
            put("created_at", System.currentTimeMillis())
        })

    override fun getItems(listName: String?, includeDone: Boolean): List<ListItem> {
        val sql = StringBuilder("SELECT id, list_name, text, due_date, priority, done FROM items WHERE 1 = 1")
        val args = mutableListOf<String>()
        if (listName != null) {
            sql.append(" AND list_name = ?")
            args += listName
        }
        if (!includeDone) sql.append(" AND done = 0")
        sql.append(" ORDER BY list_name, done, due_date IS NULL, due_date, id")
        return query(sql.toString(), args) {
            ListItem(it.getLong(0), it.getString(1), it.getString(2), it.stringOrNull(3), it.stringOrNull(4), it.getInt(5) == 1)
        }
    }

    override fun setItemDone(id: Long, done: Boolean): Boolean =
        update("items", ContentValues().apply { put("done", if (done) 1 else 0) }, id)

    override fun deleteItem(id: Long): Boolean =
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString())) > 0

    // ----- reminders -----
    override fun addReminder(text: String, dueAtMillis: Long, repeat: Repeat): Long = insert("reminders", ContentValues().apply {
        put("text", text)
        put("due_at", dueAtMillis)
        put("repeat", repeat.key)
    })

    private fun reminderOf(c: Cursor) = Reminder(c.getLong(0), c.getString(1), c.getLong(2), Repeat.of(c.getString(3)))

    override fun activeReminders(): List<Reminder> =
        query("SELECT id, text, due_at, repeat FROM reminders WHERE active = 1 ORDER BY due_at", emptyList(), ::reminderOf)

    override fun getReminder(id: Long): Reminder? =
        query("SELECT id, text, due_at, repeat FROM reminders WHERE active = 1 AND id = ?", listOf(id.toString()), ::reminderOf)
            .firstOrNull()

    override fun rescheduleReminder(id: Long, dueAtMillis: Long) {
        update("reminders", ContentValues().apply { put("due_at", dueAtMillis) }, id)
    }

    override fun deactivateReminder(id: Long): Boolean =
        update("reminders", ContentValues().apply { put("active", 0) }, id)

    // ----- money -----
    override fun addExpense(amount: Double, category: String, note: String, date: String): Long =
        insert("expenses", ContentValues().apply {
            put("amount", amount)
            put("category", category)
            put("note", note)
            put("date", date)
        })

    override fun expenses(from: String, to: String): List<Expense> =
        query(
            "SELECT id, amount, category, note, date FROM expenses WHERE date >= ? AND date <= ? ORDER BY date DESC, id DESC",
            listOf(from, to),
        ) { Expense(it.getLong(0), it.getDouble(1), it.getString(2), it.getString(3), it.getString(4)) }

    override fun deleteExpense(id: Long): Boolean =
        writableDatabase.delete("expenses", "id = ?", arrayOf(id.toString())) > 0

    // ----- habits -----
    override fun addHabit(name: String): Long = insert("habits", ContentValues().apply { put("name", name) })

    override fun habits(): List<Habit> =
        query("SELECT id, name FROM habits WHERE active = 1 ORDER BY id", emptyList()) { Habit(it.getLong(0), it.getString(1)) }

    override fun deleteHabit(id: Long): Boolean =
        update("habits", ContentValues().apply { put("active", 0) }, id)

    override fun setHabitDone(habitId: Long, date: String, done: Boolean) {
        if (done) {
            writableDatabase.insertWithOnConflict(
                "habit_logs",
                null,
                ContentValues().apply {
                    put("habit_id", habitId)
                    put("date", date)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            writableDatabase.delete("habit_logs", "habit_id = ? AND date = ?", arrayOf(habitId.toString(), date))
        }
    }

    override fun habitDoneDates(habitId: Long, from: String): Set<String> =
        query("SELECT date FROM habit_logs WHERE habit_id = ? AND date >= ?", listOf(habitId.toString(), from)) { it.getString(0) }
            .toSet()

    // ----- chat log shown in the app -----
    override fun addChat(fromUser: Boolean, text: String, actions: List<PhoneAction>): Long = insert("chats", ContentValues().apply {
        put("from_user", if (fromUser) 1 else 0)
        put("text", text)
        put("created_at", System.currentTimeMillis())
        put("actions", ActionsJson.encode(actions))
    })

    override fun recentChats(limit: Int): List<ChatMessage> =
        query(
            "SELECT id, from_user, text, created_at, actions FROM (SELECT * FROM chats ORDER BY id DESC LIMIT ?) ORDER BY id",
            listOf(limit.toString()),
        ) { ChatMessage(it.getLong(0), it.getInt(1) == 1, it.getString(2), it.getLong(3), ActionsJson.decode(it.stringOrNull(4))) }

    fun clearChats() {
        writableDatabase.delete("chats", null, null)
    }
}
