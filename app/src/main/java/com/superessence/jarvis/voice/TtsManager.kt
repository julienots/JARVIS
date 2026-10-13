package com.superessence.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/** Synthèse vocale française avec gestion du focus audio (la musique baisse pendant la réponse). */
class TtsManager(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private var failed = false
    private val queue = mutableListOf<Pair<String, () -> Unit>>()
    private var onDone: (() -> Unit)? = null
    private var currentId: String? = null
    private var focus: AudioFocusRequest? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    /** Appelé à chaque mot prononcé (anime la sphère). */
    var onWord: (() -> Unit)? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.FRANCE)
                failed = r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED
                ready = !failed
                tts.setAudioAttributes(attrs)
                tts.setOnUtteranceProgressListener(listener)
                queue.toList().also { queue.clear() }.forEach { (t, cb) -> speak(t, cb) }
            } else {
                failed = true
                queue.toList().also { queue.clear() }.forEach { (_, cb) -> cb() }
            }
        }
    }

    val isAvailable get() = ready
    val isBroken get() = failed

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { main.post { onSpeakingChanged?.invoke(true) } }
        override fun onDone(id: String?) { main.post { finish(id) } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { main.post { finish(id) } }
        override fun onError(id: String?, errorCode: Int) { main.post { finish(id) } }
        override fun onStop(id: String?, interrupted: Boolean) { main.post { finish(id) } }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { main.post { onWord?.invoke() } }
    }

    private fun finish(id: String?) {
        if (id != null && id != currentId) return
        currentId = null
        abandonFocus()
        onSpeakingChanged?.invoke(false)
        val cb = onDone; onDone = null
        cb?.invoke()
    }

    fun applySettings(rate: Float, pitch: Float, voiceName: String) {
        if (!ready) return
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        tts.setPitch(pitch.coerceIn(0.5f, 2f))
        if (voiceName.isNotBlank()) tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
    }

    fun frenchVoices(): List<Voice> = try {
        tts.voices?.filter { it.locale.language == "fr" }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Parle puis appelle [done] (même en cas d'erreur, pour ne jamais bloquer la session). */
    fun speak(text: String, done: () -> Unit = {}) {
        if (text.isBlank()) { done(); return }
        if (failed) { done(); return }
        if (!ready) { queue += text to done; return }
        stop()
        val id = UUID.randomUUID().toString()
        currentId = id
        onDone = done
        requestFocus()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        if (currentId != null) {
            val id = currentId
            onDone = null
            tts.stop()
            finish(id)
        }
    }

    val isSpeaking get() = currentId != null

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun shutdown() {
        stop(); tts.shutdown()
    }
}
