package bd.aiap

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import bd.aiap.core.ChatMessage
import bd.aiap.core.ListItem
import bd.aiap.core.Memory
import bd.aiap.core.Reminder
import bd.aiap.core.Repeat
import bd.aiap.core.Store

/** Everything the assistant knows, stored privately on the phone. */
class SqliteStore(context: Context) : SQLiteOpenHelper(context, "assistant.db", null, 1), Store {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    // ----- chat log shown in the app -----
    override fun addChat(fromUser: Boolean, text: String): Long = insert("chats", ContentValues().apply {
        put("from_user", if (fromUser) 1 else 0)
        put("text", text)
        put("created_at", System.currentTimeMillis())
    })

    override fun recentChats(limit: Int): List<ChatMessage> =
        query(
            "SELECT id, from_user, text, created_at FROM (SELECT * FROM chats ORDER BY id DESC LIMIT ?) ORDER BY id",
            listOf(limit.toString()),
        ) { ChatMessage(it.getLong(0), it.getInt(1) == 1, it.getString(2), it.getLong(3)) }

    fun clearChats() {
        writableDatabase.delete("chats", null, null)
    }
}
