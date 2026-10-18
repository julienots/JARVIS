package com.superessence.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * Synthèse vocale française avec gestion du focus audio (la musique baisse pendant la réponse).
 *
 * Mode « voix Jarvis » : voix masculine grave choisie parmi les voix installées sur le téléphone
 * (hors ligne de préférence : gratuite, sans quota, illimitée), puis passée dans une chaîne d'effets
 * (égaliseur grave/présence + légère réverbération) pour un timbre d'assistant « IA ».
 * Le niveau réel de la voix est mesuré pendant la lecture et anime la sphère.
 */
class TtsManager(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private var failed = false
    private val queue = mutableListOf<Pair<String, () -> Unit>>()
    private var onDone: (() -> Unit)? = null
    private var currentId: String? = null
    private var focus: AudioFocusRequest? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    /** Appelé à chaque mot prononcé (anime la sphère quand le niveau réel n'est pas mesurable). */
    var onWord: (() -> Unit)? = null
    /** Niveau réel de la voix 0..1 pendant la lecture (mode voix Jarvis). */
    var onLevel: ((Float) -> Unit)? = null

    private var rate = 1f; private var pitch = 1f; private var voiceName = ""; private var jarvisVoice = true

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        main.post {
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.FRANCE)
                failed = r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED
                ready = !failed
                tts.setAudioAttributes(attrs)
                tts.setOnUtteranceProgressListener(listener)
                applyVoice()
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
        override fun onStart(id: String?) { if (!isChunk(id)) main.post { onSpeakingChanged?.invoke(true) } }
        override fun onDone(id: String?) { main.post { if (isChunk(id)) chunkSynthesized(id!!, true) else finish(id) } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { main.post { if (isChunk(id)) chunkSynthesized(id!!, false) else finish(id) } }
        override fun onError(id: String?, errorCode: Int) { main.post { if (isChunk(id)) chunkSynthesized(id!!, false) else finish(id) } }
        override fun onStop(id: String?, interrupted: Boolean) { main.post { if (!isChunk(id)) finish(id) } }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { if (!isChunk(id)) main.post { onWord?.invoke() } }
    }

    private fun isChunk(id: String?) = id?.contains('#') == true

    private fun finish(id: String?) {
        if (id != null && id != currentId) return
        currentId = null
        releaseFx()
        abandonFocus()
        onSpeakingChanged?.invoke(false)
        val cb = onDone; onDone = null
        cb?.invoke()
    }

    fun applySettings(rate: Float, pitch: Float, voiceName: String, jarvisVoice: Boolean = true) {
        this.rate = rate; this.pitch = pitch; this.voiceName = voiceName; this.jarvisVoice = jarvisVoice
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        // Voix Jarvis : un peu plus grave et posée que les réglages choisis.
        tts.setSpeechRate((if (jarvisVoice) rate * 0.94f else rate).coerceIn(0.5f, 2f))
        tts.setPitch((if (jarvisVoice) pitch * 0.86f else pitch).coerceIn(0.5f, 2f))
        val chosen = voiceName.takeIf { it.isNotBlank() }?.let { n -> tts.voices?.firstOrNull { it.name == n } }
            ?: if (jarvisVoice) bestJarvisVoice() else null
        chosen?.let { runCatching { tts.voice = it } }
    }

    fun frenchVoices(): List<Voice> = try {
        tts.voices?.filter { it.locale.language == "fr" }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /**
     * Voix masculine française installée, hors ligne de préférence.
     * Noms connus des voix masculines : Google « frb » / « frd », Samsung « m0x », « male ».
     */
    fun bestJarvisVoice(): Voice? = frenchVoices()
        .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (it.features ?: emptySet()) }
        .maxByOrNull { v ->
            val n = v.name.lowercase()
            var score = v.quality / 100
            if (Regex("frb|frd|male|homme|m00|m01|m02|-m-").containsMatchIn(n) && !n.contains("female")) score += 20
            if (!v.isNetworkConnectionRequired) score += 8
            if (v.locale.country == "FR") score += 3
            score
        }

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
        if (jarvisVoice && speakWithFx(id, text)) return
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

    // ---------- Voix Jarvis : synthèse par phrases + effets + niveau réel ----------

    private var chunks: List<String> = emptyList()
    private val files = HashMap<Int, File?>()
    private var nextToSynth = 0
    private var playing = -1
    /** Phrase dont la lecture attend la fin de la synthèse (-1 : aucune). */
    private var waitingFor = -1
    private var player: MediaPlayer? = null
    private var eq: Equalizer? = null
    private var reverb: PresetReverb? = null
    private var visualizer: Visualizer? = null

    private fun speakWithFx(id: String, text: String): Boolean {
        chunks = splitSentences(text)
        files.clear(); nextToSynth = 0; playing = -1; waitingFor = 0
        if (chunks.isEmpty()) return false
        onSpeakingChanged?.invoke(true)
        // Synthèse de la première phrase, puis de la suivante pendant la lecture de la précédente.
        return synthNext(id)
    }

    private fun synthNext(id: String): Boolean {
        val i = nextToSynth
        if (i >= chunks.size) return true
        nextToSynth++
        val f = File(app.cacheDir, "jarvis_voice_$i.wav")
        val r = tts.synthesizeToFile(chunks[i], Bundle(), f, "$id#$i")
        if (r != TextToSpeech.SUCCESS) { files[i] = null; return i > 0 }
        return true
    }

    private fun chunkSynthesized(uttId: String, ok: Boolean) {
        val id = uttId.substringBefore('#')
        if (id != currentId) return
        val i = uttId.substringAfter('#').toIntOrNull() ?: return
        files[i] = if (ok) File(app.cacheDir, "jarvis_voice_$i.wav") else null
        if (i == waitingFor) { waitingFor = -1; playChunk(id, i) }
    }

    private fun playChunk(id: String, i: Int) {
        if (id != currentId) return
        if (i >= chunks.size) { finish(id); return }
        playing = i
        synthNext(id)
        if (!files.containsKey(i)) { waitingFor = i; return } // pas encore prête : chunkSynthesized relancera la lecture
        val file = files[i]
        if (file == null) {
            // Échec de cette phrase : on la dit sans effets plutôt que de la sauter.
            tts.speak(chunks[i], TextToSpeech.QUEUE_ADD, Bundle(), "$id#say$i")
            main.postDelayed({ playChunk(id, i + 1) }, 120L * chunks[i].length / 10 + 400)
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.path)
            mp.prepare()
            attachFx(mp.audioSessionId)
            mp.setOnCompletionListener { done ->
                done.release(); if (player === done) player = null
                releaseFx()
                file.delete()
                if (id == currentId) playChunk(id, i + 1)
            }
            player = mp
            mp.start()
        } catch (_: Exception) {
            player?.release(); player = null
            releaseFx()
            files[i] = null
            playChunk(id, i)
        }
    }

    private fun attachFx(session: Int) {
        releaseFx()
        // Égaliseur : graves et présence renforcés, aigus adoucis → timbre plus profond.
        eq = runCatching {
            Equalizer(0, session).apply {
                val (lo, hi) = bandLevelRange.let { it[0] to it[1] }
                for (b in 0 until numberOfBands) {
                    val hz = getCenterFreq(b.toShort()) / 1000
                    val gain = when {
                        hz < 200 -> 450
                        hz < 600 -> 250
                        hz in 1500..4000 -> 150
                        hz > 8000 -> -300
                        else -> 0
                    }
                    setBandLevel(b.toShort(), gain.coerceIn(lo.toInt(), hi.toInt()).toShort())
                }
                enabled = true
            }
        }.getOrNull()
        reverb = runCatching { PresetReverb(0, session).apply { preset = PresetReverb.PRESET_SMALLROOM; enabled = true } }.getOrNull()
        // Niveau réel de la voix (nécessite l'autorisation du micro, déjà accordée pour parler à Jarvis).
        visualizer = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                        val w = wave ?: return
                        var sum = 0.0
                        for (b in w) { val s = ((b.toInt() and 0xFF) - 128) / 128.0; sum += s * s }
                        val rms = sqrt(sum / w.size).toFloat()
                        val lv = (rms * 3.2f).coerceIn(0f, 1f)
                        main.post { onLevel?.invoke(lv) }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        }.getOrNull()
    }

    private fun releaseFx() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        visualizer?.let { runCatching { it.enabled = false }; it.release() }
        visualizer = null
        eq?.release(); eq = null
        reverb?.release(); reverb = null
    }

    private fun splitSentences(text: String): List<String> {
        val parts = Regex("(?<=[.!?…;:])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotBlank() }
        // Regroupe les phrases très courtes pour garder une diction naturelle.
        val out = mutableListOf<String>()
        for (p in parts) {
            if (out.isNotEmpty() && (out.last().length < 40 || p.length < 12)) out[out.lastIndex] = out.last() + " " + p
            else out += p
        }
        return out
    }

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
