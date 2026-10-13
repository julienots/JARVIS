package com.superessence.jarvis.core.state

/** États visibles de l'assistant (sphère, notification, overlay). */
enum class AssistantState(val label: String) {
    IDLE("En veille"),
    WAKE_DETECTED("Mot de réveil détecté"),
    LISTENING("J'écoute…"),
    THINKING("Réflexion…"),
    EXECUTING("Exécution…"),
    SPEAKING("Je réponds"),
    AWAITING_CONFIRMATION("Confirmation attendue"),
    ERROR("Erreur"),
    MIC_OFF("Micro désactivé"),
}

/**
 * Machine à états de la session vocale.
 * veille → réveil → écoute → réflexion → (exécution) → réponse → écoute (suite) | veille
 * Les transitions invalides sont refusées pour éviter les boucles (ex. micro relancé en continu).
 */
class AssistantStateMachine(initial: AssistantState = AssistantState.IDLE) {

    var state: AssistantState = initial
        private set

    private val listeners = mutableListOf<(AssistantState, AssistantState) -> Unit>()

    fun addListener(l: (from: AssistantState, to: AssistantState) -> Unit) { listeners += l }

    private val allowed: Map<AssistantState, Set<AssistantState>> = mapOf(
        AssistantState.IDLE to setOf(AssistantState.WAKE_DETECTED, AssistantState.LISTENING, AssistantState.THINKING, AssistantState.MIC_OFF, AssistantState.SPEAKING, AssistantState.ERROR),
        AssistantState.WAKE_DETECTED to setOf(AssistantState.LISTENING, AssistantState.THINKING, AssistantState.IDLE, AssistantState.ERROR, AssistantState.MIC_OFF),
        AssistantState.LISTENING to setOf(AssistantState.THINKING, AssistantState.IDLE, AssistantState.ERROR, AssistantState.MIC_OFF),
        AssistantState.THINKING to setOf(AssistantState.EXECUTING, AssistantState.SPEAKING, AssistantState.AWAITING_CONFIRMATION, AssistantState.IDLE, AssistantState.ERROR, AssistantState.MIC_OFF),
        AssistantState.EXECUTING to setOf(AssistantState.SPEAKING, AssistantState.IDLE, AssistantState.ERROR, AssistantState.MIC_OFF),
        AssistantState.SPEAKING to setOf(AssistantState.LISTENING, AssistantState.IDLE, AssistantState.AWAITING_CONFIRMATION, AssistantState.ERROR, AssistantState.MIC_OFF, AssistantState.SPEAKING, AssistantState.WAKE_DETECTED),
        AssistantState.AWAITING_CONFIRMATION to setOf(AssistantState.LISTENING, AssistantState.SPEAKING, AssistantState.THINKING, AssistantState.IDLE, AssistantState.MIC_OFF, AssistantState.ERROR),
        AssistantState.ERROR to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.SPEAKING, AssistantState.MIC_OFF, AssistantState.WAKE_DETECTED),
        AssistantState.MIC_OFF to setOf(AssistantState.IDLE, AssistantState.SPEAKING, AssistantState.THINKING, AssistantState.LISTENING),
    )

    fun canMove(to: AssistantState) = to == state || allowed[state]?.contains(to) == true

    /** Tente la transition ; renvoie false si elle est invalide (l'état reste inchangé). */
    fun moveTo(to: AssistantState): Boolean {
        if (to == state) return true
        if (!canMove(to)) return false
        val from = state
        state = to
        listeners.forEach { it(from, to) }
        return true
    }

    /** Retour forcé en veille (bouton d'arrêt d'urgence, mode privé). */
    fun reset(to: AssistantState = AssistantState.IDLE) {
        val from = state
        state = to
        if (from != to) listeners.forEach { it(from, to) }
    }
}
