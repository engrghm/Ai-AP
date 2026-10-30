package bd.aiap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import bd.aiap.core.Assistant
import bd.aiap.core.ClaudeSender

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(value) = prefs.edit().putString("owner_name", value.trim()).apply()

    /** "HH:MM", or empty to turn the morning briefing off. */
    var briefingTime: String
        get() = prefs.getString("briefing_time", "08:00") ?: ""
        set(value) = prefs.edit().putString("briefing_time", value.trim()).apply()
}

class AiApp : Application() {
    lateinit var store: SqliteStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var alarms: AndroidAlarms
        private set

    private var assistant: Assistant? = null
    private var assistantKey: Pair<String, String>? = null

    override fun onCreate() {
        super.onCreate()
        store = SqliteStore(this)
        settings = Settings(this)
        alarms = AndroidAlarms(this)
        createNotificationChannels()
    }

    /** The assistant for the current settings, or null until an API key is set. */
    @Synchronized
    fun assistant(): Assistant? {
        val key = settings.apiKey to settings.ownerName
        if (key.first.isEmpty()) return null
        if (assistant == null || assistantKey != key) {
            assistant = Assistant(
                store = store,
                alarms = alarms,
                sender = ClaudeSender(key.first),
                ownerName = key.second.ifEmpty { "বন্ধু" },
            )
            assistantKey = key
        }
        return assistant
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "রিমাইন্ডার", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BRIEFING, "সকালের ব্রিফিং", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BRIEFING = "briefing"

        fun from(context: Context): AiApp = context.applicationContext as AiApp
    }
}
