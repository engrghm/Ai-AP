package bd.aiap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import bd.aiap.core.Assistant
import bd.aiap.core.ClaudeAssistant
import bd.aiap.core.ClaudeSender
import bd.aiap.core.DEFAULT_GEMINI_MODEL
import bd.aiap.core.GeminiAssistant
import bd.aiap.core.GeminiHttp

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "gemini" or "claude". */
    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("provider", value).apply()

    /** Claude API key. */
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_key", value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", DEFAULT_GEMINI_MODEL)?.ifBlank { DEFAULT_GEMINI_MODEL } ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString("gemini_model", value.trim()).apply()

    /** The key for the chosen service. */
    val activeKey: String
        get() = if (provider == "claude") apiKey else geminiKey

    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(value) = prefs.edit().putString("owner_name", value.trim()).apply()

    /** "HH:MM", or empty to turn the morning briefing off. */
    var briefingTime: String
        get() = prefs.getString("briefing_time", "08:00") ?: ""
        set(value) = prefs.edit().putString("briefing_time", value.trim()).apply()

    /** "HH:MM", or empty to turn the evening review off. */
    var eveningTime: String
        get() = prefs.getString("evening_time", "21:30") ?: ""
        set(value) = prefs.edit().putString("evening_time", value.trim()).apply()

    /** Send what was spoken straight away, without tapping send. */
    var autoSendVoice: Boolean
        get() = prefs.getBoolean("auto_send_voice", true)
        set(value) = prefs.edit().putBoolean("auto_send_voice", value).apply()

    /** Read replies aloud. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", false)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()
}

class AiApp : Application() {
    lateinit var store: SqliteStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var alarms: AndroidAlarms
        private set
    private var speakerInstance: Speaker? = null

    /** Created on first use, so the speech engine only starts when reading aloud is on. */
    @get:Synchronized
    val speaker: Speaker
        get() = speakerInstance ?: Speaker(this).also { speakerInstance = it }

    fun stopSpeaking() {
        speakerInstance?.stop()
    }

    private var assistant: Assistant? = null
    private var assistantKey: List<String>? = null

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
        val apiKey = settings.activeKey
        if (apiKey.isEmpty()) return null
        val key = listOf(settings.provider, apiKey, settings.ownerName, settings.geminiModel)
        if (assistant == null || assistantKey != key) {
            val owner = settings.ownerName.ifEmpty { "বন্ধু" }
            assistant = if (settings.provider == "claude") {
                ClaudeAssistant(store = store, alarms = alarms, sender = ClaudeSender(apiKey), ownerName = owner)
            } else {
                GeminiAssistant(store = store, alarms = alarms, transport = GeminiHttp(apiKey), ownerName = owner, model = settings.geminiModel)
            }
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
            NotificationChannel(CHANNEL_BRIEFING, "সকাল ও রাতের সারাংশ", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BRIEFING = "briefing"

        fun from(context: Context): AiApp = context.applicationContext as AiApp
    }
}
