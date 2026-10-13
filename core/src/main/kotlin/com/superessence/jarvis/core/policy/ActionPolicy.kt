package com.superessence.jarvis.core.policy

import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.text.Text

enum class Risk { SAFE, SENSITIVE, FORBIDDEN }

/** Décide quelles actions exigent une confirmation explicite ou sont interdites. */
object ActionPolicy {

    fun risk(intent: JarvisIntent): Risk = when (intent) {
        is JarvisIntent.Forbidden -> Risk.FORBIDDEN
        is JarvisIntent.Call, is JarvisIntent.SendMessage, is JarvisIntent.ClearTasks -> Risk.SENSITIVE
        else -> Risk.SAFE
    }

    fun requiresConfirmation(intent: JarvisIntent) = risk(intent) == Risk.SENSITIVE

    fun confirmationQuestion(intent: JarvisIntent): String = when (intent) {
        is JarvisIntent.Call -> "Voulez-vous vraiment appeler ${intent.contact} ? Répondez oui ou non."
        is JarvisIntent.SendMessage ->
            if (intent.message.isBlank()) "Je prépare un message pour ${intent.contact}. Je l'ouvre dans votre messagerie, d'accord ?"
            else "Je prépare le message « ${intent.message} » pour ${intent.contact}. Vous devrez appuyer sur Envoyer vous-même. On y va ?"
        is JarvisIntent.ClearTasks -> "Voulez-vous vraiment effacer toute votre liste de tâches ? Répondez oui ou non."
        else -> "Confirmez-vous ? Répondez oui ou non."
    }
}

enum class Confirmation { YES, NO, UNCLEAR }

object ConfirmationParser {
    private val yes = setOf(
        "oui", "ouais", "ok", "okay", "d accord", "dac", "confirme", "je confirme", "vas y", "va y", "allez y",
        "c est bon", "exactement", "bien sur", "oui vas y", "oui oui", "affirmatif", "absolument", "parfait", "carrement",
        "oui s il te plait", "oui merci", "go", "valide", "fais le",
    )
    private val no = setOf(
        "non", "non merci", "annule", "annuler", "laisse tomber", "pas maintenant", "surtout pas", "negatif", "stop",
        "arrete", "non non", "oublie", "jamais", "ne fais rien", "non annule",
    )

    fun parse(answer: String): Confirmation {
        val n = Text.normalize(answer).removePrefix("jarvis ").trim()
        if (n.isBlank()) return Confirmation.UNCLEAR
        val saysNo = n in no || no.any { n.startsWith("$it ") }
        val saysYes = n in yes || yes.any { n.startsWith("$it ") }
        val tokens = n.split(' ')
        val mixed = (saysYes && ("non" in tokens || "annule" in tokens)) || (saysNo && "oui" in tokens)
        return when {
            mixed -> Confirmation.UNCLEAR
            saysNo -> Confirmation.NO
            saysYes -> Confirmation.YES
            else -> Confirmation.UNCLEAR
        }
    }
}
