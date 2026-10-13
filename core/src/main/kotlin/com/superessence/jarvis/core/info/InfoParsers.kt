package com.superessence.jarvis.core.info

import org.json.JSONObject
import java.net.URLEncoder

/** Sources d'information gratuites et sans clé : Open-Meteo (météo) et Wikipédia (recherche). */
object OpenMeteo {
    fun geocodeUrl(city: String) =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&format=json&name=" + URLEncoder.encode(city, "UTF-8")

    fun forecastUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"

    data class Place(val name: String, val lat: Double, val lon: Double)
    data class Forecast(val tempC: Double, val code: Int, val windKmh: Double?, val minC: Double?, val maxC: Double?)

    fun parsePlace(json: String): Place? {
        val arr = JSONObject(json).optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Place(o.optString("name"), o.getDouble("latitude"), o.getDouble("longitude"))
    }

    fun parseForecast(json: String): Forecast {
        val j = JSONObject(json)
        val cur = j.getJSONObject("current")
        val daily = j.optJSONObject("daily")
        return Forecast(
            cur.getDouble("temperature_2m"),
            cur.optInt("weather_code", -1),
            if (cur.has("wind_speed_10m")) cur.getDouble("wind_speed_10m") else null,
            daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() },
            daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
        )
    }

    /** Codes météo WMO → description française. */
    fun describe(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1 -> "plutôt dégagé"
        2 -> "partiellement nuageux"
        3 -> "couvert"
        45, 48 -> "brouillard"
        51, 53, 55 -> "bruine"
        56, 57 -> "bruine verglaçante"
        61 -> "pluie faible"
        63 -> "pluie"
        65 -> "forte pluie"
        66, 67 -> "pluie verglaçante"
        71 -> "neige faible"
        73 -> "neige"
        75 -> "forte neige"
        77 -> "grains de neige"
        80, 81 -> "averses"
        82 -> "violentes averses"
        85, 86 -> "averses de neige"
        95 -> "orage"
        96, 99 -> "orage avec grêle"
        else -> "conditions inconnues"
    }
}

object Wikipedia {
    fun searchUrl(query: String) =
        "https://fr.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" + URLEncoder.encode(query, "UTF-8")

    fun summaryUrl(title: String) =
        "https://fr.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")

    fun parseSearchTitle(json: String): String? {
        val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return null
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).optString("title").ifBlank { null }
    }

    data class Summary(val title: String, val extract: String, val url: String?)

    fun parseSummary(json: String): Summary? {
        val j = JSONObject(json)
        val extract = j.optString("extract")
        if (extract.isBlank()) return null
        val url = j.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
        return Summary(j.optString("title"), extract, url?.ifBlank { null })
    }
}

/** Raccourcit un texte à quelques phrases pour la lecture à voix haute. */
fun firstSentences(text: String, max: Int = 2): String {
    val parts = text.split(Regex("(?<=[.!?])\\s+"))
    return parts.take(max).joinToString(" ").trim()
}
