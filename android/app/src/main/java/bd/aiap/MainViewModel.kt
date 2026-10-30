package bd.aiap

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bd.aiap.core.Backup
import bd.aiap.core.ChatMessage
import bd.aiap.core.Expense
import bd.aiap.core.ListItem
import bd.aiap.core.Memory
import bd.aiap.core.PhoneAction
import bd.aiap.core.Reminder
import bd.aiap.core.streak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class HabitRow(val id: Long, val name: String, val doneToday: Boolean, val streak: Int, val last7: Int)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AiApp
    val settings get() = app.settings

    var chats by mutableStateOf(emptyList<ChatMessage>())
        private set
    var memories by mutableStateOf(emptyList<Memory>())
        private set
    var items by mutableStateOf(emptyList<ListItem>())
        private set
    var reminders by mutableStateOf(emptyList<Reminder>())
        private set
    var habits by mutableStateOf(emptyList<HabitRow>())
        private set
    var monthExpenses by mutableStateOf(emptyList<Expense>())
        private set

    /** The message being answered right now, shown until the reply arrives. */
    var pending by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var hasApiKey by mutableStateOf(app.settings.activeKey.isNotEmpty())
        private set
    var canScheduleExact by mutableStateOf(app.alarms.canScheduleExact())
        private set

    /** Set when the app was opened from the "মুখে বলুন" shortcut; the chat screen starts listening. */
    var listenRequested by mutableStateOf(false)

    init {
        refresh()
    }

    private class Snapshot(
        val chats: List<ChatMessage>,
        val memories: List<Memory>,
        val items: List<ListItem>,
        val reminders: List<Reminder>,
        val habits: List<HabitRow>,
        val monthExpenses: List<Expense>,
    )

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val store = app.store
                val today = LocalDate.now()
                Snapshot(
                    chats = store.recentChats(300),
                    memories = store.searchMemories(null, null),
                    items = store.getItems(null, includeDone = true),
                    reminders = store.activeReminders(),
                    habits = store.habits().map { habit ->
                        val done = store.habitDoneDates(habit.id, today.minusDays(400).toString())
                        HabitRow(
                            habit.id,
                            habit.name,
                            today.toString() in done,
                            streak(done, today),
                            (0L..6L).count { today.minusDays(it).toString() in done },
                        )
                    },
                    monthExpenses = store.expenses(today.withDayOfMonth(1).toString(), today.toString()),
                )
            }
            chats = data.chats
            memories = data.memories
            items = data.items
            reminders = data.reminders
            habits = data.habits
            monthExpenses = data.monthExpenses
            canScheduleExact = app.alarms.canScheduleExact()
        }
    }

    private fun background(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh()
        }
    }

    private fun requireKey(): Boolean {
        if (!hasApiKey) error = "প্রথমে সেটিংসে API key দিন।"
        return hasApiKey
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || pending != null || !requireKey()) return
        pending = message
        error = null
        app.stopSpeaking()
        viewModelScope.launch {
            try {
                // Building the HTTP client the first time is slow, so do it off the main thread too.
                val reply = withContext(Dispatchers.IO) { app.assistant()?.chat(message) }
                if (reply != null && app.settings.speakReplies) app.speaker.speak(reply.text)
            } catch (e: Exception) {
                error = "উত্তর আনা যায়নি: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending = null
                refresh()
            }
        }
    }

    fun briefingNow(kind: String) {
        if (pending != null || !requireKey()) return
        pending = if (kind == "evening") "আজকের হিসাব" else "আজকের পরিকল্পনা"
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    app.assistant()?.briefing(kind).orEmpty().also {
                        if (it.isNotBlank()) app.store.addChat(fromUser = false, text = (if (kind == "evening") "🌙 " else "🌅 ") + it)
                    }
                }
                if (text.isNotBlank() && app.settings.speakReplies) app.speaker.speak(text)
            } catch (e: Exception) {
                error = "আনা যায়নি: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending = null
                refresh()
            }
        }
    }

    fun stopSpeaking() = app.stopSpeaking()

    fun runAction(action: PhoneAction) {
        PhoneActions.run(app, action)?.let { error = it }
    }

    fun saveSettings(
        name: String,
        provider: String,
        geminiKey: String,
        geminiModel: String,
        apiKey: String,
        briefingTime: String,
        eveningTime: String,
        autoSendVoice: Boolean,
        speakReplies: Boolean,
    ) {
        app.settings.ownerName = name
        app.settings.provider = provider
        app.settings.geminiKey = geminiKey
        app.settings.geminiModel = geminiModel
        app.settings.apiKey = apiKey
        app.settings.briefingTime = normalizeTime(briefingTime)
        app.settings.eveningTime = normalizeTime(eveningTime)
        app.settings.autoSendVoice = autoSendVoice
        app.settings.speakReplies = speakReplies
        if (!speakReplies) app.stopSpeaking()
        hasApiKey = app.settings.activeKey.isNotEmpty()
        app.alarms.scheduleBriefing(app.settings.briefingTime)
        app.alarms.scheduleEvening(app.settings.eveningTime)
    }

    fun clearChat() = background {
        app.store.clearChats()
        app.assistant()?.reset()
    }

    fun setDone(item: ListItem, done: Boolean) = background { app.store.setItemDone(item.id, done) }
    fun deleteItem(item: ListItem) = background { app.store.deleteItem(item.id) }
    fun deleteMemory(memory: Memory) = background { app.store.deleteMemory(memory.id) }
    fun updateMemory(memory: Memory, content: String) = background { app.store.updateMemory(memory.id, content) }
    fun deleteExpense(expense: Expense) = background { app.store.deleteExpense(expense.id) }
    fun deleteHabit(habit: HabitRow) = background { app.store.deleteHabit(habit.id) }

    fun toggleHabitToday(habit: HabitRow) = background {
        app.store.setHabitDone(habit.id, LocalDate.now().toString(), !habit.doneToday)
    }

    fun cancelReminder(reminder: Reminder) = background {
        app.alarms.cancel(reminder.id)
        app.store.deactivateReminder(reminder.id)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = Backup.export(app.store)
                    app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: throw IllegalStateException("ফাইল খোলা যায়নি")
                }
                info = "ব্যাকআপ সংরক্ষণ হয়েছে ✅"
            } catch (e: Exception) {
                error = "ব্যাকআপ করা যায়নি: ${e.message}"
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException("ফাইল খোলা যায়নি")
                    Backup.import(text, app.store, app.alarms)
                }
                info = "$count টি তথ্য ফিরিয়ে আনা হয়েছে ✅"
            } catch (e: Exception) {
                error = "ব্যাকআপ থেকে আনা যায়নি: ${e.message}"
            } finally {
                refresh()
            }
        }
    }
}

/** Accepts "8:00", "08:00" or "8" and returns "08:00"; anything else turns the feature off. */
fun normalizeTime(input: String): String {
    val match = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").find(input) ?: return ""
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
    return if (hour in 0..23 && minute in 0..59) "%02d:%02d".format(hour, minute) else ""
}
