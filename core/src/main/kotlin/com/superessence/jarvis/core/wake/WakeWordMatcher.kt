package com.superessence.jarvis.core.wake

import com.superessence.jarvis.core.text.Text

/**
 * Détecte le mot de réveil dans une transcription locale (Vosk ou reconnaisseur Android).
 * La reconnaissance française déforme souvent « Jarvis » (« jarvisse », « jar vis », « charvis »…) :
 * on tolère donc une petite distance d'édition, y compris sur deux mots collés.
 */
class WakeWordMatcher(wakeWord: String = "jarvis", private val tolerance: Double = 0.72) {

    private val target = Text.normalize(wakeWord).replace(" ", "")
    private val aliases = setOf(target, "jarvisse", "jarvice", "jarviss", "charvis", "jervis", "jarvi", "djarvis", "garvis")
        .filter { target == "jarvis" || it == target }.toSet()

    data class Match(val index: Int, val remainder: String)

    /** Renvoie la position du mot de réveil et la commande qui suit, ou null. */
    fun find(transcript: String): Match? {
        val tokens = Text.normalize(transcript).split(' ').filter { it.isNotBlank() }
        for (i in tokens.indices) {
            val single = tokens[i]
            if (matches(single)) return Match(i, tokens.drop(i + 1).joinToString(" "))
            if (i + 1 < tokens.size) {
                val pair = single + tokens[i + 1]
                if (pair.length <= target.length + 2 && matches(pair)) return Match(i, tokens.drop(i + 2).joinToString(" "))
            }
        }
        return null
    }

    fun contains(transcript: String) = find(transcript) != null

    private fun matches(word: String): Boolean {
        if (word in aliases) return true
        if (word.length < target.length - 2 || word.length > target.length + 3) return false
        return Text.similarity(word, target) >= tolerance
    }
}
