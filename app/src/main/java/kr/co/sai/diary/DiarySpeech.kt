package kr.co.sai.diary

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class DiarySpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    var stopping = false
        private set
    val active get() = recognizer != null

    fun available() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(onText: (String) -> Unit, onError: (String) -> Unit): Boolean {
        if (active || Build.VERSION.SDK_INT < 31 || !available()) return false
        try {
            val session = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = session
            fun finish(text: String?) {
                if (recognizer !== session) return
                close()
                if (text.isNullOrBlank()) onError("음성을 인식하지 못했어요. 다시 말하거나 글로 남겨주세요.")
                else onText(text)
            }
            session.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = finish(null)
                override fun onResults(results: Bundle?) =
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            session.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"))
            timeout = Runnable { finish(null) }.also { handler.postDelayed(it, 45_000) }
            return true
        } catch (_: RuntimeException) {
            close()
            return false
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        recognizer?.stopListening()
    }

    fun close() {
        val session = recognizer
        recognizer = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        stopping = false
        session?.destroy()
    }
}
