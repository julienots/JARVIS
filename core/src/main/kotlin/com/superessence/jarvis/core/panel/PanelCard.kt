package com.superessence.jarvis.core.panel

/** Mini-écran temporaire affiché dans l'overlay ou l'application. */
sealed class PanelCard {
    abstract val key: String
    /** Durée de vie avant disparition automatique ; null = reste jusqu'à fermeture (minuteur actif…). */
    abstract val lifetimeMs: Long?

    data class Clock(val time: String, val date: String, override val lifetimeMs: Long? = 8_000) : PanelCard() {
        override val key = "clock"
    }
    data class Countdown(val label: String, val endAtEpochMs: Long) : PanelCard() {
        override val key = "timer-$endAtEpochMs"
        override val lifetimeMs: Long? = null
    }
    data class Weather(
        val place: String, val temperatureC: Double, val description: String,
        val minC: Double?, val maxC: Double?, val windKmh: Double?, val source: String,
        override val lifetimeMs: Long? = 15_000,
    ) : PanelCard() { override val key = "weather" }
    data class Tasks(val title: String, val items: List<String>, override val lifetimeMs: Long? = 15_000) : PanelCard() {
        override val key = "tasks"
    }
    data class Info(
        val title: String, val body: String, val sourceUrl: String? = null,
        override val lifetimeMs: Long? = 15_000,
    ) : PanelCard() { override val key = "info-${title.hashCode()}" }
    /** Liste de résultats touchables (recherche dans le téléphone, agenda…). [openUri] ouvre l'élément quand on le touche. */
    data class Results(
        val title: String, val items: List<ResultItem>,
        override val lifetimeMs: Long? = 25_000,
    ) : PanelCard() { override val key = "results-${title.hashCode()}" }
    data class Confirm(val question: String) : PanelCard() {
        override val key = "confirm"
        override val lifetimeMs: Long? = null
    }
}

/** Un élément de [PanelCard.Results] : libellé, détail, et lien à ouvrir (« app:paquet », « content:// », « https:// »…). */
data class ResultItem(val label: String, val detail: String = "", val openUri: String? = null)
