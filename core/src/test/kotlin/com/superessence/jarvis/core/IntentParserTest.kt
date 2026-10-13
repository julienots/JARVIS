package com.superessence.jarvis.core

import com.superessence.jarvis.core.intent.IntentParser
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.intent.MediaCommand
import com.superessence.jarvis.core.intent.SettingsTarget
import com.superessence.jarvis.core.intent.VolumeCommand
import com.superessence.jarvis.core.text.FrenchNumbers
import com.superessence.jarvis.core.text.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private val p = IntentParser()

    @Test fun normalizeRemovesAccentsAndPunctuation() {
        assertEquals("quelle heure est il", Text.normalize("Quelle heure est-il ?"))
        assertEquals("ouvre mes reglages wi fi", Text.normalize("Ouvre mes réglages Wi-Fi !"))
    }

    @Test fun frenchNumbers() {
        assertEquals(5 to 1, FrenchNumbers.parse(listOf("cinq", "minutes"), 0))
        assertEquals(21 to 3, FrenchNumbers.parse(listOf("vingt", "et", "un"), 0))
        assertEquals(80 to 2, FrenchNumbers.parse(listOf("quatre", "vingt"), 0))
        assertEquals(95 to 3, FrenchNumbers.parse(listOf("quatre", "vingt", "quinze"), 0))
        assertEquals(42 to 1, FrenchNumbers.parse(listOf("42"), 0))
    }

    @Test fun timeAndDate() {
        assertEquals(JarvisIntent.Time, p.parse("Jarvis, quelle heure est-il ?"))
        assertEquals(JarvisIntent.Time, p.parse("il est quelle heure"))
        assertEquals(JarvisIntent.Date, p.parse("on est quel jour aujourd'hui"))
    }

    @Test fun timers() {
        assertEquals(JarvisIntent.Timer(300), p.parse("Jarvis, lance un minuteur de 5 minutes"))
        assertEquals(JarvisIntent.Timer(300), p.parse("mets un minuteur de cinq minutes"))
        assertEquals(JarvisIntent.Timer(5400), p.parse("minuteur d'une heure et demie"))
        assertEquals(JarvisIntent.Timer(90), p.parse("minuteur 1 minute 30"))
        assertEquals(JarvisIntent.Timer(45), p.parse("lance un compte à rebours de 45 secondes"))
        assertEquals(JarvisIntent.Timer(1800), p.parse("minuteur d'une demi heure"))
        assertEquals(JarvisIntent.CancelTimer, p.parse("annule le minuteur"))
    }

    @Test fun alarms() {
        assertEquals(JarvisIntent.Alarm(7, 30), p.parse("réveille-moi à 7h30"))
        assertEquals(JarvisIntent.Alarm(6, 15), p.parse("mets une alarme à six heures et quart"))
        assertEquals(JarvisIntent.Alarm(18, 45), p.parse("alarme à 18:45"))
    }

    @Test fun reminders() {
        val r = p.parse("rappelle-moi dans 10 minutes de sortir le linge") as JarvisIntent.Reminder
        assertEquals(10, r.delayMinutes)
        assertEquals("sortir le linge", r.text)
        val r2 = p.parse("rappelle-moi d'appeler maman à 18 heures") as JarvisIntent.Reminder
        assertEquals(18, r2.hour)
        assertTrue(r2.text.contains("appeler maman"))
    }

    @Test fun tasks() {
        assertEquals(JarvisIntent.AddTask("acheter du pain"), p.parse("ajoute à ma liste acheter du pain"))
        assertEquals(JarvisIntent.AddTask("du lait"), p.parse("ajoute du lait à ma liste"))
        assertEquals(JarvisIntent.ListTasks, p.parse("Jarvis, montre mes prochaines étapes"))
        assertEquals(JarvisIntent.ClearTasks, p.parse("efface toute ma liste"))
    }

    @Test fun settingsAndApps() {
        assertEquals(JarvisIntent.OpenSettings(SettingsTarget.WIFI), p.parse("Jarvis, ouvre mes réglages Wi-Fi"))
        assertEquals(JarvisIntent.OpenSettings(SettingsTarget.BLUETOOTH), p.parse("active le bluetooth"))
        assertEquals(JarvisIntent.OpenApp("spotify"), p.parse("ouvre Spotify"))
        assertEquals(JarvisIntent.OpenApp("whatsapp"), p.parse("lance l'application WhatsApp"))
    }

    @Test fun deviceControls() {
        assertEquals(JarvisIntent.Flashlight(true), p.parse("allume la lampe torche"))
        assertEquals(JarvisIntent.Flashlight(false), p.parse("éteins la lampe"))
        assertEquals(JarvisIntent.Volume(VolumeCommand.UP), p.parse("monte le volume"))
        assertEquals(JarvisIntent.Volume(VolumeCommand.SET, 50), p.parse("mets le volume à 50 %"))
        assertEquals(JarvisIntent.Volume(VolumeCommand.MUTE), p.parse("coupe le son"))
        assertEquals(JarvisIntent.Media(MediaCommand.NEXT), p.parse("chanson suivante"))
        assertEquals(JarvisIntent.Media(MediaCommand.PAUSE), p.parse("mets en pause"))
        assertEquals(JarvisIntent.Battery, p.parse("quel est le niveau de batterie"))
    }

    @Test fun weather() {
        assertEquals(JarvisIntent.Weather(null), p.parse("Jarvis, donne-moi la météo"))
        assertEquals(JarvisIntent.Weather("lyon"), p.parse("quelle est la météo à Lyon"))
    }

    @Test fun overlayAndSession() {
        assertEquals(JarvisIntent.ClosePanel, p.parse("Jarvis, ferme le panneau"))
        assertEquals(JarvisIntent.HideOverlay, p.parse("Jarvis, cache-toi"))
        assertEquals(JarvisIntent.StopSession, p.parse("Jarvis, arrête-toi"))
        assertEquals(JarvisIntent.StopListening, p.parse("Jarvis, arrête l'écoute"))
        assertEquals(JarvisIntent.Summary, p.parse("Jarvis, résume ce que je viens de demander"))
        assertEquals(JarvisIntent.PrivateMode, p.parse("passe en mode privé"))
    }

    @Test fun communication() {
        assertEquals(JarvisIntent.Call("maman"), p.parse("appelle maman"))
        assertEquals(JarvisIntent.SendMessage("paul", "j arrive dans 5 minutes"), p.parse("envoie un message à Paul pour lui dire j'arrive dans 5 minutes"))
    }

    @Test fun searchAndUnknown() {
        assertEquals(JarvisIntent.WebSearch("la tour eiffel"), p.parse("cherche des informations sur la tour Eiffel"))
        assertTrue(p.parse("raconte-moi une blague") is JarvisIntent.Unknown)
        assertTrue(p.parse("qui est Marie Curie") is JarvisIntent.Unknown)
    }

    @Test fun emptyTranscript() {
        assertEquals(JarvisIntent.Empty, p.parse(""))
        assertEquals(JarvisIntent.Empty, p.parse("   Jarvis  "))
    }

    @Test fun forbiddenRequests() {
        assertTrue(p.parse("fais un virement de 200 euros à Paul") is JarvisIntent.Forbidden)
        assertTrue(p.parse("achète des chaussures sur Amazon") is JarvisIntent.Forbidden)
        assertTrue(p.parse("supprime toutes mes photos") is JarvisIntent.Forbidden)
        assertTrue(p.parse("désactive le code de verrouillage") is JarvisIntent.Forbidden)
    }
}
