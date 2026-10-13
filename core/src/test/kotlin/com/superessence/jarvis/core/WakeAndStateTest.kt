package com.superessence.jarvis.core

import com.superessence.jarvis.core.policy.Confirmation
import com.superessence.jarvis.core.policy.ConfirmationParser
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.core.state.AssistantStateMachine
import com.superessence.jarvis.core.wake.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAndStateTest {
    private val wake = WakeWordMatcher()

    @Test fun wakeWordVariants() {
        assertNotNull(wake.find("jarvis"))
        assertNotNull(wake.find("Jarvisse quelle heure est-il"))
        assertNotNull(wake.find("jar vis"))
        assertNotNull(wake.find("ok jervis"))
        assertEquals("quelle heure est il", wake.find("Jarvis, quelle heure est-il ?")!!.remainder)
        assertNull(wake.find("je vais au marché"))
        assertNull(wake.find("travis scott"))
        assertNull(wake.find(""))
    }

    @Test fun customWakeWord() {
        val w = WakeWordMatcher("Friday")
        assertNotNull(w.find("friday allume la lampe"))
        assertNull(w.find("jarvis"))
    }

    @Test fun nominalCycle() {
        val sm = AssistantStateMachine()
        val seen = mutableListOf<AssistantState>()
        sm.addListener { _, to -> seen += to }
        assertTrue(sm.moveTo(AssistantState.WAKE_DETECTED))
        assertTrue(sm.moveTo(AssistantState.LISTENING))
        assertTrue(sm.moveTo(AssistantState.THINKING))
        assertTrue(sm.moveTo(AssistantState.EXECUTING))
        assertTrue(sm.moveTo(AssistantState.SPEAKING))
        assertTrue(sm.moveTo(AssistantState.LISTENING)) // conversation continue
        assertTrue(sm.moveTo(AssistantState.IDLE))
        assertEquals(7, seen.size)
    }

    @Test fun invalidTransitionsRejected() {
        val sm = AssistantStateMachine()
        assertFalse(sm.moveTo(AssistantState.EXECUTING))
        assertEquals(AssistantState.IDLE, sm.state)
        sm.moveTo(AssistantState.MIC_OFF)
        assertFalse(sm.moveTo(AssistantState.WAKE_DETECTED)) // micro coupé : pas de réveil
        sm.reset()
        assertEquals(AssistantState.IDLE, sm.state)
    }

    @Test fun confirmations() {
        assertEquals(Confirmation.YES, ConfirmationParser.parse("Oui"))
        assertEquals(Confirmation.YES, ConfirmationParser.parse("oui vas-y"))
        assertEquals(Confirmation.YES, ConfirmationParser.parse("je confirme"))
        assertEquals(Confirmation.NO, ConfirmationParser.parse("non"))
        assertEquals(Confirmation.NO, ConfirmationParser.parse("annule"))
        assertEquals(Confirmation.NO, ConfirmationParser.parse("laisse tomber"))
        assertEquals(Confirmation.UNCLEAR, ConfirmationParser.parse("oui enfin non"))
        assertEquals(Confirmation.UNCLEAR, ConfirmationParser.parse("peut-être"))
        assertEquals(Confirmation.UNCLEAR, ConfirmationParser.parse(""))
    }
}
