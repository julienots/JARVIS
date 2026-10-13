package com.superessence.jarvis.voice

/** Source de transcription d'une demande (après le mot de réveil). */
interface SpeechInput {
    interface Callback {
        fun onReady()
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** [silent] = rien n'a été dit (silence, délai écoulé). */
        fun onError(message: String, silent: Boolean)
    }

    fun start(callback: Callback)
    fun stop()
    fun release()
}
