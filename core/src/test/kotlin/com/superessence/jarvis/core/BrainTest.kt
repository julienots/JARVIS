package com.superessence.jarvis.core

import com.superessence.jarvis.core.ai.AiOrchestrator
import com.superessence.jarvis.core.ai.AiProviderConfig
import com.superessence.jarvis.core.ai.AiProviderType
import com.superessence.jarvis.core.ai.HttpResult
import com.superessence.jarvis.core.brain.ActionExecutor
import com.superessence.jarvis.core.brain.ActionLogEntry
import com.superessence.jarvis.core.brain.ActionResult
import com.superessence.jarvis.core.brain.AssistantBrain
import com.superessence.jarvis.core.brain.ReplySource
import com.superessence.jarvis.core.brain.SessionControl
import com.superessence.jarvis.core.intent.IntentParser
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.routine.Routine
import com.superessence.jarvis.core.routine.RoutineStep
import com.superessence.jarvis.core.routine.StepType
import com.superessence.jarvis.core.routine.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BrainTest {
    private class FakeExecutor : ActionExecutor {
        val executed = mutableListOf<JarvisIntent>()
        var fail = false
        override fun execute(intent: JarvisIntent): ActionResult {
            executed += intent
            return if (fail) ActionResult(false, "Échec simulé.") else ActionResult(true, "Fait : ${intent.id}.")
        }
    }

    private val fixed = LocalDateTime.of(2026, 10, 13, 9, 5)
    private val exec = FakeExecutor()
    private val logs = mutableListOf<ActionLogEntry>()

    private fun brain(ai: AiOrchestrator? = null, routines: List<Routine> = emptyList()) =
        AssistantBrain(IntentParser(), exec, ai, { routines }, { fixed }, { logs += it })

    @Test fun timeIsLocalWithClockCard() {
        val r = brain().handle("Jarvis quelle heure est-il")
        assertEquals("Il est 9 heures 5.", r.speech)
        assertTrue(r.cards.single() is PanelCard.Clock)
        assertTrue(exec.executed.isEmpty())
    }

    @Test fun sensitiveActionNeedsConfirmationThenRuns() {
        val b = brain()
        val ask = b.handle("appelle maman")
        assertTrue(ask.awaitingConfirmation)
        assertTrue(exec.executed.isEmpty()) // rien n'est fait avant le oui
        val ok = b.handle("oui")
        assertEquals(listOf<JarvisIntent>(JarvisIntent.Call("maman")), exec.executed)
        assertTrue(ok.success)
        assertTrue(logs.last().confirmed)
    }

    @Test fun sensitiveActionCancelled() {
        val b = brain()
        b.handle("envoie un message à Paul pour lui dire je suis en retard")
        val r = b.handle("non")
        assertEquals("D'accord, j'annule.", r.speech)
        assertTrue(exec.executed.isEmpty())
    }

    @Test fun unclearConfirmationCancelsAfterTwoTries() {
        val b = brain()
        b.handle("appelle Paul")
        assertTrue(b.handle("hmm").awaitingConfirmation)
        val r = b.handle("euh")
        assertFalse(r.awaitingConfirmation)
        assertFalse(b.hasPendingConfirmation)
        assertTrue(exec.executed.isEmpty())
    }

    @Test fun newCommandDropsPendingConfirmation() {
        val b = brain()
        b.handle("appelle Paul")
        b.handle("allume la lampe")
        assertEquals(listOf<JarvisIntent>(JarvisIntent.Flashlight(true)), exec.executed)
        assertFalse(b.hasPendingConfirmation)
    }

    @Test fun forbiddenIsNeverExecuted() {
        val r = brain().handle("paye la facture avec ma carte bancaire")
        assertFalse(r.success)
        assertTrue(exec.executed.isEmpty())
        assertFalse(logs.last().success)
    }

    @Test fun failedActionIsReportedHonestly() {
        exec.fail = true
        val r = brain().handle("ouvre Spotify")
        assertFalse(r.success)
        assertEquals("Échec simulé.", r.speech)
    }

    @Test fun unknownWithoutAiExplainsConfiguration() {
        val r = brain().handle("raconte-moi une blague")
        assertFalse(r.success)
        assertEquals(ReplySource.SYSTEM, r.source)
        assertTrue(r.speech.contains("clé"))
    }

    @Test fun aiAnswerAndAiProposedSensitiveActionIsConfirmed() {
        var reply = """{"choices":[{"message":{"content":"Pourquoi les plongeurs plongent-ils en arrière ?"}}]}"""
        val ai = AiOrchestrator({ _, _ -> HttpResult(200, reply) },
            { listOf(AiProviderConfig(AiProviderType.GROQ, apiKey = "k", enabled = true)) })
        val b = brain(ai)
        val joke = b.handle("raconte-moi une blague")
        assertEquals(ReplySource.AI, joke.source)
        assertTrue(joke.expectsFollowUp)

        reply = """{"choices":[{"message":{"content":"J'appelle Paul. [[ACTION {\"type\":\"call\",\"contact\":\"Paul\"}]]"}}]}"""
        val r = b.handle("j'aimerais parler à Paul au téléphone")
        assertTrue(r.awaitingConfirmation)
        assertTrue(exec.executed.isEmpty())
    }

    @Test fun aiNetworkErrorKeepsLocalFeatures() {
        val ai = AiOrchestrator({ _, _ -> throw java.io.IOException("hors ligne") },
            { listOf(AiProviderConfig(AiProviderType.GEMINI, apiKey = "k", enabled = true)) })
        val b = brain(ai)
        val r = b.handle("explique la relativité")
        assertFalse(r.success)
        assertTrue(r.speech.contains("Internet"))
        assertEquals("Il est 9 heures 5.", b.handle("quelle heure est-il").speech)
    }

    @Test fun sessionControls() {
        val b = brain()
        assertEquals(SessionControl.CLOSE_PANEL, b.handle("ferme le panneau").control)
        assertEquals(SessionControl.HIDE_OVERLAY, b.handle("cache-toi").control)
        assertEquals(SessionControl.END_SESSION, b.handle("arrête-toi").control)
        assertEquals(SessionControl.STOP_LISTENING, b.handle("arrête l'écoute").control)
    }

    @Test fun summaryListsPreviousRequests() {
        val b = brain()
        b.handle("quelle heure est-il")
        b.handle("allume la lampe")
        val r = b.handle("résume ce que je viens de demander")
        assertTrue(r.speech.contains("quelle heure est-il"))
        assertTrue(r.speech.contains("allume la lampe"))
    }

    @Test fun voiceRoutineRunsStepsAndSkipsSensitive() {
        val routine = Routine(
            "r1", "Séance", trigger = TriggerType.VOICE, phrase = "je lance ma séance",
            steps = listOf(
                RoutineStep(StepType.SAY, "C'est parti."),
                RoutineStep(StepType.COMMAND, "lance un minuteur de 45 minutes"),
                RoutineStep(StepType.COMMAND, "appelle Paul"),
                RoutineStep(StepType.COMMAND, "lance la routine Séance"),
            ),
        )
        val r = brain(routines = listOf(routine)).handle("Jarvis, je lance ma séance")
        assertEquals(listOf<JarvisIntent>(JarvisIntent.Timer(2700)), exec.executed)
        assertTrue(r.speech.startsWith("C'est parti."))
        assertFalse(r.success)
    }

    @Test fun repeatReturnsLastReply() {
        val b = brain()
        val first = b.handle("quelle heure est-il")
        assertEquals(first.speech, b.handle("répète").speech)
    }
}
