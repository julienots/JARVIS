package com.superessence.jarvis.core.text

import java.text.Normalizer

/** Outils de normalisation du texte français issu de la reconnaissance vocale. */
object Text {

    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonWord = Regex("[^a-z0-9: ]+")
    private val spaces = Regex("\\s+")

    /** Minuscules, sans accents, apostrophes et ponctuation remplacées par des espaces. */
    fun normalize(input: String): String {
        val lower = input.lowercase().replace('’', '\'').replace('œ', 'o').replace("æ", "ae")
        val noAccent = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(diacritics, "")
        return noAccent.replace('\'', ' ').replace('-', ' ')
            .replace(nonWord, " ").replace(spaces, " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Similarité entre 0 et 1 (1 = identique). */
    fun similarity(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }
}

/** Conversion des nombres écrits en lettres (français) en entiers, jusqu'à 9999. */
object FrenchNumbers {
    private val units = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
        "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "vingts" to 20,
        "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60, "septante" to 70,
        "huitante" to 80, "octante" to 80, "nonante" to 90,
    )

    fun isNumberWord(w: String): Boolean = w in units || w == "cent" || w == "cents" || w == "mille" ||
        w == "et" || w.toIntOrNull() != null

    /**
     * Lit un nombre à partir de [tokens] en position [start].
     * Renvoie la valeur et le nombre de jetons consommés, ou null.
     */
    fun parse(tokens: List<String>, start: Int): Pair<Int, Int>? {
        if (start >= tokens.size) return null
        tokens[start].toIntOrNull()?.let { return it to 1 }
        var total = 0
        var current = 0
        var i = start
        var consumed = 0
        while (i < tokens.size) {
            val w = tokens[i]
            when {
                w in units -> {
                    val v = units.getValue(w)
                    // « quatre vingt » = 80
                    if (v == 20 && current % 100 == 4) current = current - 4 + 80
                    else current += v
                }
                w == "cent" || w == "cents" -> current = (if (current == 0) 1 else current) * 100
                w == "mille" -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                w == "et" && consumed > 0 && i + 1 < tokens.size && tokens[i + 1] in units -> {}
                else -> break
            }
            i++; consumed++
        }
        if (consumed == 0) return null
        // un « et » final n'est pas consommé
        while (consumed > 0 && tokens[start + consumed - 1] == "et") consumed--
        return (total + current) to consumed
    }
}
