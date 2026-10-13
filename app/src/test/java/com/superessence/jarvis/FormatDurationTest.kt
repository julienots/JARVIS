package com.superessence.jarvis

import com.superessence.jarvis.actions.AndroidActionExecutor
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test fun durations() {
        assertEquals("5 minutes", AndroidActionExecutor.formatDuration(300))
        assertEquals("1 heure 30 minutes", AndroidActionExecutor.formatDuration(5400))
        assertEquals("1 minute 30 secondes", AndroidActionExecutor.formatDuration(90))
        assertEquals("45 secondes", AndroidActionExecutor.formatDuration(45))
    }
}
