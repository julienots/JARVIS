package com.superessence.jarvis.core

import com.superessence.jarvis.core.ai.AiActionExtractor
import com.superessence.jarvis.core.intent.BrightnessCommand
import com.superessence.jarvis.core.intent.IntentParser
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.intent.SettingsTarget
import com.superessence.jarvis.core.intent.SystemAction
import com.superessence.jarvis.core.policy.ActionPolicy
import com.superessence.jarvis.core.policy.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Commandes qui agissent sur le téléphone sans quitter l'application en cours. */
class PhoneControlTest {
    private val p = IntentParser()

    @Test fun phoneSearch() {
        assertEquals(JarvisIntent.PhoneSearch("paul"), p.parse("Cherche Paul dans mon téléphone"))
        assertEquals(JarvisIntent.PhoneSearch("facture edf"), p.parse("Trouve facture EDF sur mon tel"))
        assertEquals(JarvisIntent.PhoneSearch("vacances"), p.parse("Jarvis, cherche vacances dans mes photos"))
        assertEquals(JarvisIntent.PhoneSearch("contrat"), p.parse("Trouve le fichier contrat"))
        assertEquals(JarvisIntent.WebSearch("la tour eiffel"), p.parse("Cherche la tour Eiffel"))
    }

    @Test fun agenda() {
        assertEquals(JarvisIntent.Agenda(0), p.parse("Qu'est-ce que j'ai de prévu aujourd'hui ?"))
        assertEquals(JarvisIntent.Agenda(1), p.parse("Qu'est-ce que j'ai demain ?"))
        assertEquals(JarvisIntent.Agenda(0), p.parse("Montre mon agenda"))
        assertEquals(JarvisIntent.ListTasks, p.parse("Qu'est-ce que j'ai à faire ?"))
    }

    @Test fun brightnessAndDnd() {
        assertEquals(JarvisIntent.Brightness(BrightnessCommand.DOWN), p.parse("Baisse la luminosité"))
        assertEquals(JarvisIntent.Brightness(BrightnessCommand.SET, 40), p.parse("Mets la luminosité à 40 %"))
        assertEquals(JarvisIntent.Brightness(BrightnessCommand.MAX), p.parse("Luminosité à fond"))
        assertEquals(JarvisIntent.OpenSettings(SettingsTarget.DISPLAY), p.parse("Ouvre les réglages de luminosité"))
        assertEquals(JarvisIntent.DoNotDisturb(true), p.parse("Active le mode ne pas déranger"))
        assertEquals(JarvisIntent.DoNotDisturb(false), p.parse("Désactive ne pas déranger"))
    }

    @Test fun systemActions() {
        assertEquals(JarvisIntent.System(SystemAction.BACK), p.parse("Retour"))
        assertEquals(JarvisIntent.System(SystemAction.HOME), p.parse("Va à l'accueil"))
        assertEquals(JarvisIntent.System(SystemAction.SCREENSHOT), p.parse("Fais une capture d'écran"))
        assertEquals(JarvisIntent.System(SystemAction.LOCK_SCREEN), p.parse("Verrouille le téléphone"))
        assertEquals(JarvisIntent.System(SystemAction.NOTIFICATIONS), p.parse("Ouvre le volet des notifications"))
        assertEquals(JarvisIntent.System(SystemAction.RECENTS), p.parse("Applications récentes"))
        assertEquals(JarvisIntent.ReadNotifications, p.parse("Lis mes notifications"))
    }

    @Test fun readScreen() {
        assertEquals(JarvisIntent.ReadScreen(false), p.parse("Lis l'écran"))
        assertEquals(JarvisIntent.ReadScreen(true), p.parse("Résume cette page"))
        assertEquals(JarvisIntent.ReadScreen(true), p.parse("Qu'est-ce qu'il y a sur l'écran ?"))
    }

    @Test fun newActionsAreSafeAndMessagesStillConfirmed() {
        assertEquals(Risk.SAFE, ActionPolicy.risk(JarvisIntent.System(SystemAction.LOCK_SCREEN)))
        assertEquals(Risk.SAFE, ActionPolicy.risk(JarvisIntent.PhoneSearch("paul")))
        assertEquals(Risk.SENSITIVE, ActionPolicy.risk(JarvisIntent.SendMessage("paul", "salut")))
    }

    @Test fun aiCanTriggerNewActions() {
        assertEquals(JarvisIntent.PhoneSearch("paul"), AiActionExtractor.extract("Je cherche. [[ACTION {\"type\":\"phone_search\",\"query\":\"paul\"}]]").intent)
        assertEquals(JarvisIntent.System(SystemAction.HOME), AiActionExtractor.extract("[[ACTION {\"type\":\"system\",\"action\":\"home\"}]]").intent)
        assertEquals(JarvisIntent.Brightness(BrightnessCommand.SET, 70), AiActionExtractor.extract("[[ACTION {\"type\":\"brightness\",\"percent\":70}]]").intent)
    }
}
