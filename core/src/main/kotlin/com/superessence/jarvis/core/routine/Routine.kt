package com.superessence.jarvis.core.routine

import com.superessence.jarvis.core.text.Text
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

enum class TriggerType(val label: String) { MANUAL("Manuel"), TIME("Heure planifiée"), VOICE("Phrase vocale") }

enum class StepType(val label: String) {
    SAY("Dire un texte"),
    COMMAND("Commande Jarvis"),
}

data class RoutineStep(val type: StepType, val value: String)

data class Routine(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: TriggerType = TriggerType.MANUAL,
    val hour: Int = 8,
    val minute: Int = 0,
    /** Jours actifs (1 = lundi … 7 = dimanche). Vide = tous les jours. */
    val days: Set<Int> = emptySet(),
    val phrase: String = "",
    val steps: List<RoutineStep> = emptyList(),
    /** Demander une confirmation vocale avant de lancer la routine. */
    val confirmBeforeRun: Boolean = false,
    val lastRunEpochMs: Long = 0,
) {
    fun matchesPhrase(text: String): Boolean {
        if (trigger != TriggerType.VOICE || phrase.isBlank() || !enabled) return false
        val a = Text.normalize(text).removePrefix("jarvis ").trim()
        val b = Text.normalize(phrase).removePrefix("jarvis ").trim()
        return a == b || Text.similarity(a, b) >= 0.85
    }

    fun isActiveOn(day: DayOfWeek) = days.isEmpty() || day.value in days

    /** Prochaine exécution planifiée strictement après [now], ou null si non planifiée. */
    fun nextRun(now: LocalDateTime): LocalDateTime? {
        if (!enabled || trigger != TriggerType.TIME) return null
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var candidate = now.toLocalDate().atTime(time)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        repeat(8) {
            if (isActiveOn(candidate.dayOfWeek)) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("trigger", trigger.name)
        .put("hour", hour).put("minute", minute).put("days", JSONArray(days.sorted()))
        .put("phrase", phrase).put("confirm", confirmBeforeRun).put("lastRun", lastRunEpochMs)
        .put("steps", JSONArray().apply { steps.forEach { put(JSONObject().put("type", it.type.name).put("value", it.value)) } })

    companion object {
        fun fromJson(j: JSONObject): Routine {
            val daysArr = j.optJSONArray("days") ?: JSONArray()
            val stepsArr = j.optJSONArray("steps") ?: JSONArray()
            return Routine(
                id = j.getString("id"),
                name = j.optString("name"),
                enabled = j.optBoolean("enabled", true),
                trigger = runCatching { TriggerType.valueOf(j.optString("trigger")) }.getOrDefault(TriggerType.MANUAL),
                hour = j.optInt("hour", 8),
                minute = j.optInt("minute", 0),
                days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet(),
                phrase = j.optString("phrase"),
                steps = (0 until stepsArr.length()).mapNotNull {
                    val s = stepsArr.getJSONObject(it)
                    runCatching { RoutineStep(StepType.valueOf(s.getString("type")), s.optString("value")) }.getOrNull()
                },
                confirmBeforeRun = j.optBoolean("confirm", false),
                lastRunEpochMs = j.optLong("lastRun", 0),
            )
        }

        fun listToJson(list: List<Routine>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String?): List<Routine> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
            } catch (_: Exception) { emptyList() }
        }

        /** Exemples proposés à la première ouverture de l'écran Routines. */
        fun examples(): List<Routine> = listOf(
            Routine(
                id = "exemple-matin", name = "Bonjour le matin", enabled = false, trigger = TriggerType.TIME,
                hour = 7, minute = 30, days = setOf(1, 2, 3, 4, 5),
                steps = listOf(
                    RoutineStep(StepType.SAY, "Bonjour ! Voici votre point du matin."),
                    RoutineStep(StepType.COMMAND, "quelle heure est-il"),
                    RoutineStep(StepType.COMMAND, "donne-moi la météo"),
                    RoutineStep(StepType.COMMAND, "montre mes prochaines étapes"),
                ),
            ),
            Routine(
                id = "exemple-seance", name = "Séance de sport", trigger = TriggerType.VOICE, phrase = "je lance ma séance",
                steps = listOf(
                    RoutineStep(StepType.SAY, "C'est parti, bonne séance !"),
                    RoutineStep(StepType.COMMAND, "lance un minuteur de 45 minutes"),
                ),
            ),
        )
    }
}
