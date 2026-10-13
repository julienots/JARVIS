package com.superessence.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Reconnaissance vocale du système (Google ou autre fournisseur installé), en français. */
class AndroidSpeechInput(private val context: Context, private val preferOffline: Boolean = false) : SpeechInput {

    private var recognizer: SpeechRecognizer? = null
    private var cb: SpeechInput.Callback? = null
    private var delivered = false

    companion object {
        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Erreur d'enregistrement audio."
            SpeechRecognizer.ERROR_CLIENT -> "Reconnaissance interrompue."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro manquante."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Problème de réseau pour la reconnaissance vocale."
            SpeechRecognizer.ERROR_NO_MATCH -> "Je n'ai pas compris."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Le micro est occupé."
            SpeechRecognizer.ERROR_SERVER -> "Le service de reconnaissance ne répond pas."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu."
            11 /* ERROR_SERVER_DISCONNECTED */ -> "Service vocal déconnecté."
            12, 13 /* LANGUAGE_NOT_SUPPORTED / UNAVAILABLE */ -> "Le français n'est pas disponible pour la reconnaissance vocale."
            else -> "Erreur de reconnaissance ($code)."
        }
    }

    override fun start(callback: SpeechInput.Callback) {
        stop()
        cb = callback
        delivered = false
        val r = if (preferOffline && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { cb?.onReady() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { cb?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                val silent = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                cb?.onError(errorText(error), silent)
            }
            override fun onResults(results: Bundle?) {
                if (delivered) return
                delivered = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) cb?.onError("Je n'ai rien entendu.", true) else cb?.onFinal(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { cb?.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            delivered = true
            cb?.onError("Impossible de démarrer la reconnaissance vocale.", false)
        }
    }

    override fun stop() {
        recognizer?.let {
            delivered = true
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    override fun release() = stop()
}
