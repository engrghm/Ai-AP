package bd.aiap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps listening until the owner taps stop. Android's recognizer ends a session at the first pause,
 * so each time it ends we keep what was heard and start it again straight away. Must be used on the
 * main thread.
 */
class VoiceListener(context: Context, private val onFinished: (String) -> Unit, private val onError: (String) -> Unit) :
    RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Hints to wait longer at pauses; some recognizers ignore them, which the restart loop covers.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)

    /** True while the owner wants us to listen (between start and stop/cancel). */
    var active by mutableStateOf(false)
        private set

    /** Everything heard so far, plus what is being heard right now. */
    var committed by mutableStateOf("")
        private set
    var partial by mutableStateOf("")
        private set

    /** Microphone loudness 0..1, for the animation. */
    var level by mutableFloatStateOf(0f)
        private set

    private var finishing = false
    private var silentRestarts = 0

    init {
        recognizer.setRecognitionListener(this)
    }

    val text: String get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    fun start() {
        committed = ""
        partial = ""
        finishing = false
        silentRestarts = 0
        active = true
        begin()
    }

    /** Stop and hand over everything heard. */
    fun stop() {
        if (!active) return
        active = false
        finishing = true
        recognizer.stopListening()
        // If the recognizer never sends final results, finish with what we have.
        handler.postDelayed({ if (finishing) finish() }, 1500)
    }

    /** Stop and throw away what was heard. */
    fun cancel() {
        active = false
        finishing = false
        handler.removeCallbacksAndMessages(null)
        recognizer.cancel()
        committed = ""
        partial = ""
        level = 0f
    }

    fun destroy() {
        cancel()
        recognizer.destroy()
    }

    private fun begin() {
        if (!active) return
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            active = false
            onError("ভয়েস শোনা শুরু করা যায়নি: ${e.message}")
        }
    }

    private fun restartSoon() {
        handler.postDelayed({ begin() }, 150)
    }

    private fun finish() {
        finishing = false
        handler.removeCallbacksAndMessages(null)
        val result = text
        committed = ""
        partial = ""
        level = 0f
        if (result.isNotBlank()) onFinished(result)
    }

    private fun Bundle?.best(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onResults(results: Bundle?) {
        val heard = results.best()
        if (heard.isNotBlank()) {
            committed = listOf(committed, heard).filter { it.isNotBlank() }.joinToString(" ")
            silentRestarts = 0
        } else {
            silentRestarts++
        }
        partial = ""
        when {
            finishing -> finish()
            active && silentRestarts >= MAX_SILENT_RESTARTS -> {
                active = false
                finish() // a long silence: the owner has probably finished
            }
            active -> restartSoon()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val heard = partialResults.best()
        if (heard.isNotBlank()) partial = heard
    }

    override fun onError(error: Int) {
        level = 0f
        if (finishing) {
            finish()
            return
        }
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // A pause with nothing new: keep listening, unless the silence has gone on a while.
                silentRestarts++
                if (silentRestarts >= MAX_SILENT_RESTARTS) {
                    active = false
                    finish()
                } else {
                    restartSoon()
                }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                recognizer.cancel()
                handler.postDelayed({ begin() }, 400)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                active = false
                onError("মাইক্রোফোনের অনুমতি দিন।")
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                active = false
                finish()
                onError("ইন্টারনেট সংযোগে সমস্যা, ভয়েস শোনা বন্ধ হয়েছে। যা শোনা গেছে তা রাখা হয়েছে।")
            }
            else -> {
                active = false
                finish()
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        /** About 25-40 seconds of silence before listening stops on its own. */
        private const val MAX_SILENT_RESTARTS = 5

        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
