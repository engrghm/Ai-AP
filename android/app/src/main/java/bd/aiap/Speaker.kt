package bd.aiap

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads replies aloud in Bangla when the phone has a Bangla voice, otherwise in the default voice. */
class Speaker(context: Context) {
    private var ready = false
    private var queued: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            queued?.let { speak(it) }
            queued = null
        }
    }

    fun speak(text: String) {
        if (!ready) {
            queued = text
            return
        }
        val bangla = Locale.forLanguageTag("bn-BD")
        if (tts.isLanguageAvailable(bangla) >= TextToSpeech.LANG_AVAILABLE) tts.language = bangla
        // Emoji and list markers sound odd when read aloud.
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}*#•]"), " ")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stop() {
        if (ready) tts.stop()
    }
}
