package com.superessence.jarvis.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.brain.BrainReply
import com.superessence.jarvis.core.brain.SessionControl
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.core.state.AssistantStateMachine
import com.superessence.jarvis.data.SttEngineChoice
import com.superessence.jarvis.data.StoredMessage
import com.superessence.jarvis.voice.AndroidSpeechInput
import com.superessence.jarvis.voice.SpeechInput
import com.superessence.jarvis.voice.TtsManager
import com.superessence.jarvis.voice.VoskSpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ActiveCard(val card: PanelCard, val shownAt: Long, val pinned: Boolean = false)
data class UiMessage(val fromUser: Boolean, val text: String, val at: Long = System.currentTimeMillis(), val isError: Boolean = false)

/**
 * Chef d'orchestre d'une session vocale. Vit au niveau de l'application pour être partagé
 * par l'écran principal, l'interface flottante, la tuile et le service d'écoute.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionController(private val app: JarvisApp) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Le cerveau n'est pas thread-safe : une seule demande traitée à la fois. */
    private val brainDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val machine = AssistantStateMachine()
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()
    private val _lastUser = MutableStateFlow("")
    val lastUser: StateFlow<String> = _lastUser.asStateFlow()
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()
    private val _cards = MutableStateFlow<List<ActiveCard>>(emptyList())
    val cards: StateFlow<List<ActiveCard>> = _cards.asStateFlow()
    private val _level = MutableStateFlow(0f)
    /** Niveau sonore 0..1 (voix de l'utilisateur ou de Jarvis), anime la sphère. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val tts = TtsManager(app)
    private var input: SpeechInput? = null
    private var followUps = 0
    private var sessionStartedAt = 0L
    private val maxSessionMs = 5 * 60_000L
    private var tone: ToneGenerator? = null

    /** Rappelé quand la session se termine (le service relance alors l'écoute du mot de réveil). */
    var onSessionIdle: (() -> Unit)? = null
    /** Rappelé avant d'utiliser le micro (le service coupe alors la détection du mot de réveil). */
    var onMicNeeded: (() -> Unit)? = null
    /** Demande d'arrêt de l'écoute permanente (« Jarvis, arrête l'écoute »). */
    var onStopWakeRequested: (() -> Unit)? = null

    init {
        machine.addListener { _, to -> _state.value = to }
        tts.onSpeakingChanged = { speaking -> if (!speaking) _level.value = 0f }
        tts.onWord = { _level.value = 0.45f + Math.random().toFloat() * 0.55f; main.postDelayed({ if (tts.isSpeaking) _level.value *= 0.5f }, 120) }
        if (app.settings.current.privateMode) machine.reset(AssistantState.MIC_OFF)
        if (app.settings.current.historyEnabled) {
            _messages.value = app.conversation.items.map { UiMessage(it.fromUser, it.text, it.at) }
        }
        scope.launch {
            app.settings.flow.collect { s ->
                tts.applySettings(s.ttsRate, s.ttsPitch, s.ttsVoice)
                if (s.privateMode && _state.value != AssistantState.MIC_OFF) {
                    cancelAll(); machine.reset(AssistantState.MIC_OFF)
                } else if (!s.privateMode && _state.value == AssistantState.MIC_OFF) machine.reset(AssistantState.IDLE)
            }
        }
        // Expiration des cartes temporaires
        main.post(object : Runnable {
            override fun run() { expireCards(); main.postDelayed(this, 1000) }
        })
    }

    val isBusy get() = _state.value != AssistantState.IDLE && _state.value != AssistantState.MIC_OFF && _state.value != AssistantState.ERROR

    fun hasMicPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Mot de réveil détecté (éventuellement avec la demande complète). */
    fun onWakeWord(remainder: String) {
        if (app.settings.current.privateMode) return
        if (tts.isSpeaking) tts.stop() // interruption de la réponse
        machine.moveTo(AssistantState.WAKE_DETECTED)
        app.overlay.showIfAllowed()
        beep()
        sessionStartedAt = System.currentTimeMillis()
        followUps = 0
        if (remainder.split(' ').count { it.isNotBlank() } >= 2) submit(remainder, spoken = true)
        else main.postDelayed({ listen() }, 180)
    }

    /** Bouton micro (application, bulle, tuile, notification, raccourci). */
    fun startListening() {
        if (app.settings.current.privateMode) {
            _error.value = "Mode privé actif : le micro est coupé. Désactivez-le dans Confidentialité."
            return
        }
        if (tts.isSpeaking) tts.stop()
        sessionStartedAt = System.currentTimeMillis()
        followUps = 0
        app.overlay.showIfAllowed()
        listen()
    }

    private fun listen() {
        if (!hasMicPermission()) {
            fail("L'autorisation du micro est nécessaire. Ouvrez Jarvis pour l'accorder.")
            return
        }
        onMicNeeded?.invoke()
        input?.stop()
        val useVosk = app.settings.current.sttEngine == SttEngineChoice.VOSK && app.vosk.isInstalled() ||
            !AndroidSpeechInput.isAvailable(app) && app.vosk.isInstalled()
        if (!useVosk && !AndroidSpeechInput.isAvailable(app)) {
            fail("Aucun service de reconnaissance vocale disponible. Installez « Google » ou téléchargez le modèle hors ligne dans Réglages → Voix.")
            return
        }
        val i: SpeechInput = if (useVosk) VoskSpeechInput(app.vosk) else AndroidSpeechInput(app)
        input = i
        if (!machine.moveTo(AssistantState.LISTENING)) { machine.reset(AssistantState.LISTENING) }
        _partial.value = ""
        _error.value = null
        i.start(object : SpeechInput.Callback {
            override fun onReady() {}
            override fun onLevel(level: Float) { _level.value = level }
            override fun onPartial(text: String) { _partial.value = text }
            override fun onFinal(text: String) { _level.value = 0f; input = null; submit(text, spoken = true) }
            override fun onError(message: String, silent: Boolean) {
                _level.value = 0f
                input = null
                if (silent) {
                    // Silence : fin de la conversation, retour en veille sans bruit.
                    if (app.brain.hasPendingConfirmation) { app.brain.cancelPending(); speakThen("Sans réponse, j'annule.") { endSession() } }
                    else endSession()
                } else fail(message)
            }
        })
    }

    /** Traite une demande écrite ou dictée. */
    fun submit(text: String, spoken: Boolean) {
        if (text.isBlank()) { endSession(); return }
        _lastUser.value = text
        _partial.value = ""
        addMessage(UiMessage(true, text))
        if (!machine.moveTo(AssistantState.THINKING)) machine.reset(AssistantState.THINKING)
        scope.launch {
            val reply = withContext(brainDispatcher) {
                try { app.brain.handle(text) } catch (e: Exception) {
                    BrainReply("Une erreur interne est survenue : ${e.message}", success = false)
                }
            }
            deliver(reply, spoken)
        }
    }

    private fun deliver(reply: BrainReply, spoken: Boolean) {
        reply.cards.forEach { showCard(it) }
        if (reply.speech.isNotBlank()) {
            _lastReply.value = reply.speech
            addMessage(UiMessage(false, reply.speech, isError = !reply.success))
        }
        when (reply.control) {
            SessionControl.CLOSE_PANEL -> { _cards.update { list -> list.filter { it.card is PanelCard.Countdown } }; app.overlay.collapse() }
            SessionControl.HIDE_OVERLAY -> app.overlay.hide(userRequested = true)
            SessionControl.SHOW_OVERLAY -> app.overlay.show(force = true)
            SessionControl.STOP_LISTENING -> onStopWakeRequested?.invoke()
            SessionControl.PRIVATE_MODE -> {
                speakThen(reply.speech) { app.settings.update { it.copy(privateMode = true) }; app.overlay.hide(userRequested = true) }
                return
            }
            else -> {}
        }
        val endAfter = reply.control != SessionControl.NONE
        // Conversation continue : après une réponse dictée, Jarvis réécoute quelques secondes
        // (le silence termine la session). Le nombre de relances est plafonné pour éviter toute boucle.
        val wantsMore = reply.awaitingConfirmation ||
            (spoken && !endAfter && (reply.expectsFollowUp || app.settings.current.continuousConversation))
        val limit = app.settings.current.maxFollowUps + if (reply.awaitingConfirmation) 2 else 0
        if (reply.awaitingConfirmation) machine.moveTo(AssistantState.AWAITING_CONFIRMATION)
        speakThen(reply.speech) {
            val tooLong = System.currentTimeMillis() - sessionStartedAt > maxSessionMs
            if (!endAfter && wantsMore && !tooLong && followUps < limit) {
                followUps++
                listen()
            } else endSession()
        }
    }

    private fun speakThen(text: String, then: () -> Unit) {
        if (text.isBlank()) { then(); return }
        if (!machine.moveTo(AssistantState.SPEAKING)) machine.reset(AssistantState.SPEAKING)
        if (tts.isBroken) {
            _error.value = "Synthèse vocale indisponible : installez une voix française (Réglages Android → Synthèse vocale)."
            main.postDelayed(then, 1500)
            return
        }
        tts.speak(text, then)
    }

    /** Fin de session : retour en veille et reprise de l'écoute du mot de réveil. */
    fun endSession() {
        input?.stop(); input = null
        _partial.value = ""
        _level.value = 0f
        if (app.settings.current.privateMode) machine.reset(AssistantState.MIC_OFF)
        else if (!machine.moveTo(AssistantState.IDLE)) machine.reset(AssistantState.IDLE)
        app.overlay.scheduleAutoHide()
        onSessionIdle?.invoke()
    }

    /** Arrêt immédiat (bouton stop) : coupe la voix, l'écoute et toute demande en cours. */
    fun cancelAll() {
        tts.stop()
        input?.stop(); input = null
        app.brain.cancelPending()
        _partial.value = ""
        _level.value = 0f
        if (!app.settings.current.privateMode) machine.reset(AssistantState.IDLE)
        onSessionIdle?.invoke()
    }

    private fun fail(message: String) {
        _error.value = message
        machine.reset(AssistantState.ERROR)
        addMessage(UiMessage(false, message, isError = true))
        speakThen(message) {
            main.postDelayed({ if (_state.value == AssistantState.ERROR) endSession() }, 1200)
        }
    }

    /** Fait parler Jarvis sans session d'écoute (rappels, routines planifiées). */
    fun announce(text: String, cards: List<PanelCard> = emptyList()) {
        cards.forEach { showCard(it) }
        if (text.isBlank() || app.settings.current.privateMode) return
        _lastReply.value = text
        addMessage(UiMessage(false, text))
        if (isBusy) return
        app.overlay.showIfAllowed()
        speakThen(text) { endSession() }
    }

    /** Lance une routine hors session (alarme planifiée, bouton « Exécuter »). */
    fun runRoutineNow(id: String) {
        val r = app.routines.byId(id) ?: return
        scope.launch {
            val reply = withContext(brainDispatcher) { app.brain.handle("lance la routine ${r.name}") }
            app.routines.upsert(r.copy(lastRunEpochMs = System.currentTimeMillis()))
            reply.cards.forEach { showCard(it) }
            announce(reply.speech)
        }
    }

    fun showCard(card: PanelCard) {
        _cards.update { list -> list.filterNot { it.card.key == card.key } + ActiveCard(card, System.currentTimeMillis()) }
        app.overlay.showIfAllowed()
    }

    fun dismissCard(key: String) = _cards.update { list -> list.filterNot { it.card.key == key } }

    fun togglePin(key: String) = _cards.update { list -> list.map { if (it.card.key == key) it.copy(pinned = !it.pinned) else it } }

    private fun expireCards() {
        val now = System.currentTimeMillis()
        val list = _cards.value
        val kept = list.filter { ac ->
            when {
                ac.pinned -> true
                ac.card is PanelCard.Countdown -> (ac.card as PanelCard.Countdown).endAtEpochMs + 60_000 > now
                ac.card is PanelCard.Confirm -> app.brain.hasPendingConfirmation
                else -> {
                    val life = ac.card.lifetimeMs ?: return@filter true
                    // Les cartes restent visibles tant que Jarvis parle
                    isBusy || now - ac.shownAt < life
                }
            }
        }
        if (kept.size != list.size) _cards.value = kept
    }

    fun clearConversation() {
        _messages.value = emptyList()
        app.brain.clearConversation()
        app.conversation.clear()
    }

    private fun addMessage(m: UiMessage) {
        _messages.update { (it + m).takeLast(200) }
        if (app.settings.current.historyEnabled) app.conversation.add(StoredMessage(m.fromUser, m.text, m.at))
    }

    private fun beep() {
        if (!app.settings.current.earcons) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (_: Exception) {}
    }

    fun clearError() { _error.value = null }
}
