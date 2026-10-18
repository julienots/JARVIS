package com.superessence.jarvis.core.brain

import com.superessence.jarvis.core.ai.AiActionExtractor
import com.superessence.jarvis.core.ai.AiException
import com.superessence.jarvis.core.ai.AiErrorKind
import com.superessence.jarvis.core.ai.AiOrchestrator
import com.superessence.jarvis.core.ai.ChatMessage
import com.superessence.jarvis.core.ai.Role
import com.superessence.jarvis.core.intent.IntentParser
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.policy.ActionPolicy
import com.superessence.jarvis.core.policy.Confirmation
import com.superessence.jarvis.core.policy.ConfirmationParser
import com.superessence.jarvis.core.policy.Risk
import com.superessence.jarvis.core.routine.Routine
import com.superessence.jarvis.core.routine.StepType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Contrôle de la session demandé par une commande (fermer le panneau, s'arrêter…). */
enum class SessionControl { NONE, END_SESSION, STOP_LISTENING, HIDE_OVERLAY, SHOW_OVERLAY, CLOSE_PANEL, PRIVATE_MODE }

/** Résultat réel d'une action exécutée sur le téléphone. */
data class ActionResult(
    val success: Boolean,
    val speech: String,
    val cards: List<PanelCard> = emptyList(),
    /**
     * Demande à transmettre à l'IA pour compléter le résultat (résumer l'écran, répondre quand
     * Wikipédia ne sait pas…). Sans IA configurée, [speech] est utilisé tel quel.
     */
    val aiFollowUp: String? = null,
)

/** Exécute les intentions sur l'appareil (implémenté côté Android). Peut bloquer : appelé hors du thread UI. */
interface ActionExecutor {
    fun execute(intent: JarvisIntent): ActionResult
}

enum class ReplySource { LOCAL, AI, SYSTEM }

data class BrainReply(
    val speech: String,
    val cards: List<PanelCard> = emptyList(),
    val intent: JarvisIntent? = null,
    val awaitingConfirmation: Boolean = false,
    val control: SessionControl = SessionControl.NONE,
    val source: ReplySource = ReplySource.LOCAL,
    val success: Boolean = true,
    /** Le panneau et le micro doivent rester ouverts pour une réponse de suivi. */
    val expectsFollowUp: Boolean = false,
)

data class ActionLogEntry(
    val timestampMs: Long, val intentId: String, val detail: String, val success: Boolean, val confirmed: Boolean,
)

/**
 * Orchestrateur : texte reconnu → intention → politique de sécurité → exécution ou IA → réponse.
 */
class AssistantBrain(
    private val parser: IntentParser,
    private val executor: ActionExecutor,
    private val ai: AiOrchestrator?,
    private val routines: () -> List<Routine> = { emptyList() },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val log: (ActionLogEntry) -> Unit = {},
    private val userName: () -> String = { "" },
    private val extraInstructions: () -> String = { "" },
) {
    private val history = ArrayDeque<ChatMessage>()
    private val maxHistory = 16
    private var pending: JarvisIntent? = null
    private var pendingAsked = 0
    private var lastReply: BrainReply? = null
    private var runningRoutine = false

    val hasPendingConfirmation get() = pending != null

    fun clearConversation() {
        history.clear(); pending = null; lastReply = null
    }

    fun cancelPending() { pending = null; pendingAsked = 0 }

    fun conversation(): List<ChatMessage> = history.toList()

    fun handle(text: String): BrainReply {
        val reply = handleInternal(text)
        if (reply.speech.isNotBlank() && text.isNotBlank()) remember(text, reply.speech)
        lastReply = reply
        return reply
    }

    private fun handleInternal(text: String): BrainReply {
        // 1. Confirmation en attente
        pending?.let { p ->
            when (ConfirmationParser.parse(text)) {
                Confirmation.YES -> {
                    pending = null; pendingAsked = 0
                    return execute(p, confirmed = true)
                }
                Confirmation.NO -> {
                    pending = null; pendingAsked = 0
                    log(ActionLogEntry(now().toEpochMs(), p.id, "Annulé par l'utilisateur", success = false, confirmed = false))
                    return BrainReply("D'accord, j'annule.", source = ReplySource.SYSTEM)
                }
                Confirmation.UNCLEAR -> {
                    val other = parser.parse(text)
                    if (other !is JarvisIntent.Unknown && other !is JarvisIntent.Empty) {
                        pending = null; pendingAsked = 0
                        // Nouvelle demande : on abandonne l'action sensible.
                    } else {
                        pendingAsked++
                        if (pendingAsked >= 2) {
                            pending = null; pendingAsked = 0
                            return BrainReply("Je n'ai pas eu de confirmation claire, j'annule par sécurité.", source = ReplySource.SYSTEM)
                        }
                        return BrainReply(
                            "Je n'ai pas compris. Répondez simplement oui ou non. ${ActionPolicy.confirmationQuestion(p)}",
                            cards = listOf(PanelCard.Confirm(ActionPolicy.confirmationQuestion(p))),
                            intent = p, awaitingConfirmation = true, expectsFollowUp = true,
                        )
                    }
                }
            }
        }

        // 2. Routines déclenchées par une phrase
        if (!runningRoutine) routines().firstOrNull { it.matchesPhrase(text) }?.let { return runRoutine(it) }

        // 3. Intention locale
        val intent = parser.parse(text)
        return route(intent, text)
    }

    private fun route(intent: JarvisIntent, original: String): BrainReply = when (intent) {
        JarvisIntent.Empty -> BrainReply("Je n'ai rien entendu.", intent = intent, success = false, source = ReplySource.SYSTEM)
        is JarvisIntent.Forbidden -> {
            log(ActionLogEntry(now().toEpochMs(), intent.id, "Refusé : $original", success = false, confirmed = false))
            BrainReply(intent.reason, intent = intent, success = false, source = ReplySource.SYSTEM)
        }
        JarvisIntent.Time -> {
            val t = now()
            val time = t.format(DateTimeFormatter.ofPattern("HH:mm"))
            BrainReply(
                "Il est ${t.hour} heure${if (t.hour > 1) "s" else ""}${if (t.minute > 0) " ${t.minute}" else ""}.",
                cards = listOf(PanelCard.Clock(time, formatDate(t))), intent = intent,
            )
        }
        JarvisIntent.Date -> {
            val t = now()
            BrainReply("Nous sommes le ${formatDate(t)}.", cards = listOf(PanelCard.Clock(t.format(DateTimeFormatter.ofPattern("HH:mm")), formatDate(t))), intent = intent)
        }
        JarvisIntent.Greeting -> BrainReply(greeting(), intent = intent, expectsFollowUp = true)
        JarvisIntent.Thanks -> BrainReply("Avec plaisir.", intent = intent)
        JarvisIntent.Help -> BrainReply(HELP_SPEECH, cards = listOf(PanelCard.Info("Ce que je sais faire", HELP_CARD)), intent = intent)
        JarvisIntent.Repeat -> lastReply?.copy(control = SessionControl.NONE) ?: BrainReply("Je n'ai encore rien dit.", intent = intent)
        JarvisIntent.Summary -> summary()
        JarvisIntent.StopSession -> BrainReply("À votre service.", intent = intent, control = SessionControl.END_SESSION)
        JarvisIntent.StopListening -> BrainReply("J'arrête l'écoute du mot Jarvis.", intent = intent, control = SessionControl.STOP_LISTENING)
        JarvisIntent.ClosePanel -> BrainReply("", intent = intent, control = SessionControl.CLOSE_PANEL)
        JarvisIntent.HideOverlay -> BrainReply("Je me fais discret.", intent = intent, control = SessionControl.HIDE_OVERLAY)
        JarvisIntent.ShowOverlay -> BrainReply("Me voici.", intent = intent, control = SessionControl.SHOW_OVERLAY)
        JarvisIntent.PrivateMode -> BrainReply("Mode privé activé. Micro coupé et interface fermée.", intent = intent, control = SessionControl.PRIVATE_MODE)
        is JarvisIntent.RunRoutine -> {
            val r = routines().firstOrNull { com.superessence.jarvis.core.text.Text.similarity(
                com.superessence.jarvis.core.text.Text.normalize(it.name), com.superessence.jarvis.core.text.Text.normalize(intent.name)) >= 0.75 }
            if (r == null) BrainReply("Je ne trouve pas de routine nommée ${intent.name}.", intent = intent, success = false)
            else runRoutine(r)
        }
        is JarvisIntent.Unknown -> askAi(original)
        else -> guarded(intent)
    }

    private fun guarded(intent: JarvisIntent): BrainReply = when (ActionPolicy.risk(intent)) {
        Risk.FORBIDDEN -> BrainReply((intent as? JarvisIntent.Forbidden)?.reason ?: "Action refusée.", intent = intent, success = false)
        Risk.SENSITIVE -> {
            pending = intent; pendingAsked = 0
            val q = ActionPolicy.confirmationQuestion(intent)
            BrainReply(q, cards = listOf(PanelCard.Confirm(q)), intent = intent, awaitingConfirmation = true, expectsFollowUp = true)
        }
        Risk.SAFE -> execute(intent, confirmed = false)
    }

    private fun execute(intent: JarvisIntent, confirmed: Boolean): BrainReply {
        val result = try {
            executor.execute(intent)
        } catch (e: Exception) {
            ActionResult(false, "L'action a échoué : ${e.message ?: "erreur inconnue"}.")
        }
        log(ActionLogEntry(now().toEpochMs(), intent.id, describe(intent) + " → " + result.speech, result.success, confirmed))
        val followUp = result.aiFollowUp
        if (followUp != null && ai != null && ai.hasUsableProvider()) {
            val answer = askAi(followUp)
            if (answer.success) return answer.copy(cards = result.cards + answer.cards, intent = intent)
        }
        val speech = result.speech.ifBlank { if (followUp != null) "Je n'ai pas trouvé de réponse fiable." else "" }
        return BrainReply(speech, result.cards, intent, success = result.success, source = ReplySource.LOCAL)
    }

    private fun askAi(text: String): BrainReply {
        if (ai == null || !ai.hasUsableProvider()) {
            return BrainReply(
                "Je n'ai pas compris cette commande. Pour les questions libres, ajoutez une clé d'IA dans les réglages : Gemini, Claude, ChatGPT, Groq ou Mistral.",
                intent = JarvisIntent.Unknown(text), success = false, source = ReplySource.SYSTEM,
            )
        }
        val messages = history.toList() + ChatMessage(Role.USER, text)
        val answer = try {
            ai.ask(systemPrompt(), messages)
        } catch (e: AiException) {
            val msg = if (e.kind == AiErrorKind.NOT_CONFIGURED) e.kind.userMessage else e.message ?: e.kind.userMessage
            return BrainReply(msg, intent = JarvisIntent.Unknown(text), success = false, source = ReplySource.SYSTEM)
        }
        val extracted = AiActionExtractor.extract(answer.text)
        val action = extracted.intent
        if (action == null) {
            return BrainReply(extracted.speech, intent = JarvisIntent.Unknown(text), source = ReplySource.AI,
                expectsFollowUp = extracted.speech.trimEnd().endsWith("?"))
        }
        val sub = guarded(action)
        val speech = when {
            sub.awaitingConfirmation -> sub.speech
            !sub.success -> listOf(extracted.speech, sub.speech).filter { it.isNotBlank() }.joinToString(" ")
            extracted.speech.isNotBlank() -> extracted.speech
            else -> sub.speech
        }
        return sub.copy(speech = speech, source = ReplySource.AI)
    }

    private fun runRoutine(r: Routine): BrainReply {
        if (!r.enabled) return BrainReply("La routine ${r.name} est désactivée.", success = false)
        runningRoutine = true
        try {
            val speeches = mutableListOf<String>()
            val cards = mutableListOf<PanelCard>()
            var allOk = true
            for (step in r.steps) {
                when (step.type) {
                    StepType.SAY -> speeches += step.value
                    StepType.COMMAND -> {
                        val intent = parser.parse(step.value)
                        val reply = when {
                            intent is JarvisIntent.RunRoutine -> BrainReply("Une routine ne peut pas en lancer une autre.", success = false)
                            ActionPolicy.risk(intent) != Risk.SAFE ->
                                BrainReply("Étape « ${step.value} » ignorée : elle demande une confirmation manuelle.", success = false)
                            intent is JarvisIntent.Unknown -> askAi(step.value)
                            else -> route(intent, step.value)
                        }
                        if (reply.speech.isNotBlank()) speeches += reply.speech
                        cards += reply.cards
                        if (!reply.success) allOk = false
                    }
                }
            }
            log(ActionLogEntry(now().toEpochMs(), "routine", "Routine « ${r.name} »", allOk, false))
            return BrainReply(speeches.joinToString(" "), cards, JarvisIntent.RunRoutine(r.name), success = allOk)
        } finally {
            runningRoutine = false
        }
    }

    private fun summary(): BrainReply {
        val asked = history.filter { it.role == Role.USER }.takeLast(5)
        if (asked.isEmpty()) return BrainReply("Vous ne m'avez encore rien demandé dans cette session.", intent = JarvisIntent.Summary)
        val body = asked.joinToString("\n") { "• ${it.text}" }
        if (ai != null && ai.hasUsableProvider()) {
            try {
                val a = ai.ask(
                    "Tu résumes en français, en deux phrases maximum, ce que l'utilisateur a demandé et ce qui a été répondu.",
                    listOf(ChatMessage(Role.USER, history.joinToString("\n") { (if (it.role == Role.USER) "Utilisateur : " else "Jarvis : ") + it.text })),
                )
                return BrainReply(AiActionExtractor.extract(a.text).speech, listOf(PanelCard.Info("Résumé", body)), JarvisIntent.Summary, source = ReplySource.AI)
            } catch (_: AiException) { /* repli local ci-dessous */ }
        }
        return BrainReply(
            "Vous m'avez demandé : " + asked.joinToString(", puis ") { it.text } + ".",
            listOf(PanelCard.Info("Résumé", body)), JarvisIntent.Summary,
        )
    }

    private fun remember(user: String, assistant: String) {
        history.addLast(ChatMessage(Role.USER, user))
        history.addLast(ChatMessage(Role.ASSISTANT, assistant))
        while (history.size > maxHistory) history.removeFirst()
    }

    fun systemPrompt(): String {
        val t = now()
        val name = userName().takeIf { it.isNotBlank() }?.let { " L'utilisateur s'appelle $it." } ?: ""
        return """
            Tu es Jarvis, un assistant vocal français élégant, efficace et chaleureux, créé par le studio SuperEssence, qui fonctionne sur un téléphone Android.$name
            Nous sommes le ${formatDate(t)}, il est ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}.
            Tes réponses sont lues à voix haute : sois bref (1 à 3 phrases), naturel, sans markdown, sans listes à puces, sans emojis.
            Si la demande est ambiguë, pose une seule question courte.
            Si une information peut être périmée ou que tu n'as pas accès à Internet, dis-le honnêtement.
            ${AiActionExtractor.protocolDescription}
            ${extraInstructions()}
        """.trimIndent()
    }

    private fun greeting(): String {
        val h = now().hour
        val base = when (h) { in 5..11 -> "Bonjour"; in 12..17 -> "Bon après-midi"; else -> "Bonsoir" }
        val name = userName().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "$base$name. Que puis-je faire pour vous ?"
    }

    companion object {
        private val FR = Locale.FRENCH
        fun formatDate(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", FR))
        private fun LocalDateTime.toEpochMs() = atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun describe(intent: JarvisIntent): String = when (intent) {
            is JarvisIntent.OpenApp -> "Ouvrir ${intent.appName}"
            is JarvisIntent.Timer -> "Minuteur ${intent.seconds} s"
            is JarvisIntent.Alarm -> "Alarme ${intent.hour}h${"%02d".format(intent.minute)}"
            is JarvisIntent.Call -> "Appel ${intent.contact}"
            is JarvisIntent.SendMessage -> "Message à ${intent.contact}"
            is JarvisIntent.OpenSettings -> "Réglages ${intent.target.label}"
            is JarvisIntent.AddTask -> "Tâche « ${intent.text} »"
            is JarvisIntent.Reminder -> "Rappel « ${intent.text} »"
            is JarvisIntent.WebSearch -> "Recherche « ${intent.query} »"
            is JarvisIntent.PhoneSearch -> "Recherche dans le téléphone « ${intent.query} »"
            is JarvisIntent.System -> intent.action.label
            else -> intent.id
        }

        const val HELP_SPEECH = "Je peux donner l'heure, la météo, lancer un minuteur ou une alarme, créer des rappels et des tâches, " +
            "ouvrir une application ou un réglage, contrôler la musique, le volume et la luminosité, allumer la lampe, " +
            "chercher une information sur Internet ou dans votre téléphone, lire votre agenda, lire ou résumer l'écran, " +
            "et répondre à vos questions si une IA est configurée."
        const val HELP_CARD = "« Quelle heure est-il ? »\n« Lance un minuteur de 5 minutes »\n« Réveille-moi à 7 h 30 »\n" +
            "« Rappelle-moi dans 10 minutes de sortir le linge »\n« Ajoute du pain à ma liste »\n« Donne-moi la météo à Lyon »\n" +
            "« Ouvre Spotify »\n« Ouvre mes réglages Wi-Fi »\n« Allume la lampe »\n« Chanson suivante »\n« Cherche la tour Eiffel »\n" +
            "« Cherche Paul dans mon téléphone »\n« Qu'est-ce que j'ai de prévu demain ? »\n« Résume l'écran »\n" +
            "« Baisse la luminosité »\n« Active ne pas déranger »\n« Fais une capture d'écran »\n" +
            "« Ferme le panneau » · « Cache-toi » · « Arrête-toi » · « Arrête l'écoute »"
    }
}
