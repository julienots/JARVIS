package com.superessence.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.superessence.jarvis.core.wake.WakeWordMatcher
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Détection continue du mot de réveil. */
interface WakeWordEngine {
    val name: String
    /** Démarre l'écoute ; [onWake] reçoit la suite éventuelle de la phrase (« Jarvis, quelle heure est-il »). */
    fun start(onWake: (remainder: String) -> Unit, onError: (String) -> Unit)
    fun stop()
    fun release()
    val isRunning: Boolean
}

/**
 * Moteur 100 % local basé sur Vosk : l'audio ne quitte jamais le téléphone.
 * Il peut aussi transcrire la demande complète hors ligne (voir [VoskSpeechInput]).
 */
class VoskWakeWordEngine(
    private val models: VoskModelManager,
    private val matcher: () -> WakeWordMatcher,
) : WakeWordEngine {
    override val name = "Vosk (hors ligne)"
    private var service: SpeechService? = null
    private val main = Handler(Looper.getMainLooper())
    override val isRunning get() = service != null

    override fun start(onWake: (String) -> Unit, onError: (String) -> Unit) {
        if (service != null) return
        Thread {
            val model = models.loadModel()
            main.post {
                if (model == null) { onError("Modèle vocal hors ligne absent."); return@post }
                try {
                    val rec = Recognizer(model, 16000f)
                    val s = SpeechService(rec, 16000f)
                    service = s
                    s.startListening(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {
                            val text = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
                            // Réveil rapide dès que le mot apparaît seul dans le résultat partiel
                            val m = matcher().find(text)
                            if (m != null && m.remainder.isBlank() && text.split(' ').size <= 2) fire(onWake, "")
                        }
                        override fun onResult(hypothesis: String?) {
                            val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
                            matcher().find(text)?.let { fire(onWake, it.remainder) }
                        }
                        override fun onFinalResult(hypothesis: String?) {}
                        override fun onError(exception: Exception?) { onError(exception?.message ?: "Erreur micro") }
                        override fun onTimeout() {}
                    })
                } catch (e: Exception) {
                    service = null
                    onError("Micro indisponible : ${e.message}")
                }
            }
        }.start()
    }

    private var lastFire = 0L
    private fun fire(onWake: (String) -> Unit, remainder: String) {
        val now = System.currentTimeMillis()
        if (now - lastFire < 1500) return
        lastFire = now
        main.post { onWake(remainder) }
    }

    override fun stop() {
        service?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
        service = null
    }

    override fun release() = stop()
}

/**
 * Moteur de repli : boucle de reconnaissance Android qui cherche « Jarvis ».
 * Plus gourmand et dépendant du fournisseur du téléphone ; un délai croissant évite les boucles serrées.
 */
class RecognizerWakeWordEngine(
    private val context: Context,
    private val matcher: () -> WakeWordMatcher,
) : WakeWordEngine {
    override val name = "Reconnaissance Android"
    private val main = Handler(Looper.getMainLooper())
    private var input: AndroidSpeechInput? = null
    private var running = false
    private var backoffMs = 400L
    override val isRunning get() = running

    override fun start(onWake: (String) -> Unit, onError: (String) -> Unit) {
        if (running) return
        if (!AndroidSpeechInput.isAvailable(context)) { onError("Aucun service de reconnaissance vocale sur ce téléphone."); return }
        running = true
        backoffMs = 400L
        loop(onWake, onError)
    }

    private fun loop(onWake: (String) -> Unit, onError: (String) -> Unit) {
        if (!running) return
        val i = AndroidSpeechInput(context, preferOffline = true)
        input = i
        i.start(object : SpeechInput.Callback {
            override fun onReady() {}
            override fun onLevel(level: Float) {}
            override fun onPartial(text: String) {
                if (matcher().find(text) != null && text.split(' ').size <= 2) {
                    // on attend le résultat final pour récupérer la suite de la phrase
                }
            }
            override fun onFinal(text: String) {
                val m = matcher().find(text)
                if (m != null) { running = false; i.stop(); onWake(m.remainder); return }
                backoffMs = 400L
                main.postDelayed({ loop(onWake, onError) }, backoffMs)
            }
            override fun onError(message: String, silent: Boolean) {
                if (message.contains("Autorisation")) { running = false; onError(message); return }
                backoffMs = if (silent) 300L else (backoffMs * 2).coerceAtMost(30_000L)
                main.postDelayed({ loop(onWake, onError) }, backoffMs)
            }
        })
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        input?.stop(); input = null
    }

    override fun release() = stop()
}

/** Transcription hors ligne d'une demande avec Vosk (mode sans Internet). */
class VoskSpeechInput(private val models: VoskModelManager) : SpeechInput {
    private var service: SpeechService? = null
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    override fun start(callback: SpeechInput.Callback) {
        stop()
        val model = models.loadModel()
        if (model == null) { callback.onError("Modèle vocal hors ligne absent.", false); return }
        try {
            val s = SpeechService(Recognizer(model, 16000f), 16000f)
            service = s
            var heard = false
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val t = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty()
                    if (t.isNotBlank()) { heard = true; callback.onPartial(t); callback.onLevel(0.6f) }
                }
                override fun onResult(hypothesis: String?) {
                    val t = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
                    if (t.isNotBlank()) { stop(); callback.onFinal(t) }
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) { stop(); callback.onError("Erreur micro.", false) }
                override fun onTimeout() { stop(); if (!heard) callback.onError("Je n'ai rien entendu.", true) }
            }, 9000)
            callback.onReady()
        } catch (e: Exception) {
            callback.onError("Micro indisponible.", false)
        }
    }

    override fun stop() {
        timeout?.let { main.removeCallbacks(it) }
        service?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
        service = null
    }

    override fun release() = stop()
}
