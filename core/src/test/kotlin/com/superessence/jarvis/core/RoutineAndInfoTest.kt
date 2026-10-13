package com.superessence.jarvis.core

import com.superessence.jarvis.core.info.OpenMeteo
import com.superessence.jarvis.core.info.Wikipedia
import com.superessence.jarvis.core.info.firstSentences
import com.superessence.jarvis.core.routine.Routine
import com.superessence.jarvis.core.routine.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RoutineAndInfoTest {
    @Test fun nextRunSameDayOrTomorrow() {
        val r = Routine("a", "Matin", trigger = TriggerType.TIME, hour = 7, minute = 30)
        assertEquals(LocalDateTime.of(2026, 10, 13, 7, 30), r.nextRun(LocalDateTime.of(2026, 10, 13, 6, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 14, 7, 30), r.nextRun(LocalDateTime.of(2026, 10, 13, 7, 30)))
    }

    @Test fun nextRunRespectsDays() {
        // 2026-10-16 est un vendredi ; la routine ne tourne que le lundi (1)
        val r = Routine("a", "Lundi", trigger = TriggerType.TIME, hour = 8, minute = 0, days = setOf(1))
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 0), r.nextRun(LocalDateTime.of(2026, 10, 16, 9, 0)))
        assertNull(r.copy(enabled = false).nextRun(LocalDateTime.of(2026, 10, 16, 9, 0)))
        assertNull(r.copy(trigger = TriggerType.MANUAL).nextRun(LocalDateTime.of(2026, 10, 16, 9, 0)))
    }

    @Test fun jsonRoundTrip() {
        val list = Routine.examples()
        val back = Routine.listFromJson(Routine.listToJson(list))
        assertEquals(list, back)
        assertTrue(Routine.listFromJson("pas du json").isEmpty())
    }

    @Test fun weatherParsing() {
        val place = OpenMeteo.parsePlace("""{"results":[{"name":"Lyon","latitude":45.75,"longitude":4.85}]}""")!!
        assertEquals("Lyon", place.name)
        assertNull(OpenMeteo.parsePlace("""{"generationtime_ms":0.5}"""))
        val f = OpenMeteo.parseForecast("""{"current":{"temperature_2m":17.4,"weather_code":3,"wind_speed_10m":12.0},
            "daily":{"temperature_2m_max":[19.1],"temperature_2m_min":[9.8]}}""")
        assertEquals(17.4, f.tempC, 0.001)
        assertEquals("couvert", OpenMeteo.describe(f.code))
        assertEquals(19.1, f.maxC!!, 0.001)
    }

    @Test fun wikipediaParsing() {
        assertEquals("Tour Eiffel", Wikipedia.parseSearchTitle("""{"query":{"search":[{"title":"Tour Eiffel"}]}}"""))
        val s = Wikipedia.parseSummary("""{"title":"Tour Eiffel","extract":"La tour Eiffel est une tour. Elle est à Paris. Troisième.",
            "content_urls":{"mobile":{"page":"https://fr.m.wikipedia.org/wiki/Tour_Eiffel"}}}""")!!
        assertEquals("La tour Eiffel est une tour. Elle est à Paris.", firstSentences(s.extract))
        assertTrue(Wikipedia.summaryUrl("Tour Eiffel").endsWith("Tour_Eiffel"))
    }
}
