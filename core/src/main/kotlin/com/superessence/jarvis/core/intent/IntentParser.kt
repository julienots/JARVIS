package com.superessence.jarvis.core.intent

import com.superessence.jarvis.core.text.FrenchNumbers
import com.superessence.jarvis.core.text.Text

/**
 * Analyseur d'intentions local (sans réseau) pour les commandes françaises courantes.
 * Tout ce qui n'est pas reconnu est renvoyé en [JarvisIntent.Unknown] pour l'IA.
 */
class IntentParser(private val wakeWords: List<String> = listOf("jarvis")) {

    fun parse(raw: String): JarvisIntent {
        val n = stripWakeWord(Text.normalize(raw))
        if (n.isBlank()) return JarvisIntent.Empty
        val t = n.split(' ')

        forbidden(n)?.let { return it }

        // --- Contrôle de la session et de l'interface ---
        if (n.matches(Regex("(arrete|stoppe|coupe|desactive) (l |ton |de )?(ecoute|micro|microphone)( permanente)?.*"))) return JarvisIntent.StopListening
        if (n.matches(Regex("(mode prive|active le mode prive|passe en mode prive).*"))) return JarvisIntent.PrivateMode
        if (n.matches(Regex("(ferme|masque|enleve|retire) (le |ce |les )?(panneau|carte|cartes|fenetre|ecran)s?.*"))) return JarvisIntent.ClosePanel
        if (n.matches(Regex("(cache|planque|masque) toi.*|disparais.*|cache l interface.*"))) return JarvisIntent.HideOverlay
        if (n.matches(Regex("(montre|affiche) toi.*|(reviens|reapparais).*|(affiche|montre) l interface.*"))) return JarvisIntent.ShowOverlay
        if (n.matches(Regex("(arrete|stop|stoppe|tais) toi.*|stop|c est tout|ca suffit|au revoir|termine.*|fin de (la )?session|merci c est tout"))) return JarvisIntent.StopSession
        if (n.matches(Regex("(tais toi|silence|chut)"))) return JarvisIntent.StopSession
        if (n.matches(Regex("(repete|tu peux repeter|redis|repete s il te plait).*"))) return JarvisIntent.Repeat
        if (n.matches(Regex("(resume|resumer|fais (moi )?un resume)( ce que| de ce que)? (je (viens|t ai)|j ai) .*|resume (la|notre) conversation.*"))) return JarvisIntent.Summary
        if (n.matches(Regex("(aide|aide moi|que sais tu faire|qu est ce que tu sais faire|quelles sont tes commandes|tu sais faire quoi).*"))) return JarvisIntent.Help

        // --- Heure, date, batterie ---
        if (n.matches(Regex(".*(quelle heure|l heure qu il est|donne (moi )?l heure|il est quelle heure|heure est il).*"))) return JarvisIntent.Time
        if (n.matches(Regex(".*(quel jour|quelle date|on est le combien|date d aujourd hui|la date).*"))) return JarvisIntent.Date
        if (n.matches(Regex(".*(batterie|niveau de charge|pourcentage de charge).*")) && !n.contains("reglage") && !n.contains("parametre")) return JarvisIntent.Battery

        // --- Minuteur, alarme, rappel ---
        if (n.matches(Regex("(annule|arrete|supprime|stoppe) (le |mon )?(minuteur|compte a rebours|timer).*"))) return JarvisIntent.CancelTimer
        if (n.contains("minuteur") || n.contains("compte a rebours") || n.contains("timer") || n.matches(Regex("(chronometre|minute) .*"))) {
            durationSeconds(t)?.let { return JarvisIntent.Timer(it) }
        }
        if (n.contains("reveil") || n.contains("alarme")) {
            clockTime(t)?.let { (h, m) -> return JarvisIntent.Alarm(h, m) }
        }
        if (n.matches(Regex("(rappelle moi|fais moi penser|n oublie pas de me rappeler|cree un rappel|ajoute un rappel|mets un rappel).*"))) {
            return parseReminder(n, t)
        }

        // --- Tâches ---
        Regex("(ajoute|note|mets|inscris) (a |dans |sur )?(ma |la |mes )?(liste( de taches)?|taches?|todo|choses a faire)( a faire)?( de)? (.+)").matchEntire(n)?.let {
            return JarvisIntent.AddTask(it.groupValues.last().trim())
        }
        Regex("(ajoute|note) (.+) (a|dans|sur) (ma |la |mes )?(liste|taches?|todo).*").matchEntire(n)?.let {
            return JarvisIntent.AddTask(it.groupValues[2].trim())
        }
        if (n.matches(Regex("(vide|efface|supprime) (toute |toutes )?(ma |la |mes )?(liste|taches?).*"))) return JarvisIntent.ClearTasks
        Regex("(j ai (fini|termine)|marque comme (fait|faite|terminee?)|coche) (la tache )?(.+)").matchEntire(n)?.let {
            return JarvisIntent.CompleteTask(it.groupValues.last().trim())
        }
        if (n.matches(Regex(".*((mes|les) (prochaines )?(taches|etapes|choses a faire)|ma liste|ma todo|qu est ce que j ai a faire|montre (mes )?prochaines etapes).*"))) return JarvisIntent.ListTasks

        // --- Météo ---
        if (n.matches(Regex(".*(meteo|temps qu il fait|temps fait il|quel temps|temperature|va t il pleuvoir|pleut il|il pleut).*"))) {
            val city = Regex(".*\\b(a|au|aux|sur|pour|de) ([a-z][a-z ]+?)( demain| aujourd hui| ce soir| maintenant)?$").matchEntire(n)
                ?.groupValues?.get(2)?.trim()?.takeIf { it !in setOps && it.split(' ').size <= 4 }
            return JarvisIntent.Weather(city)
        }

        // --- Lampe torche, volume, multimédia ---
        if (n.matches(Regex(".*(lampe|torche|flash|lumiere du telephone).*"))) {
            val off = n.matches(Regex(".*(eteins|eteint|coupe|desactive|arrete|ferme).*"))
            return JarvisIntent.Flashlight(!off)
        }
        if (n.contains("volume") || n.contains("son ") || n.endsWith(" son") || n.matches(Regex("(monte|baisse|coupe) le son.*|mets en (mode )?silencieux.*"))) {
            volume(n, t)?.let { return it }
        }
        when {
            n.matches(Regex("(chanson|morceau|titre|piste) suivante?.*|suivant|passe (a la )?(chanson|musique|piste)( suivante)?.*|musique suivante")) -> return JarvisIntent.Media(MediaCommand.NEXT)
            n.matches(Regex("(chanson|morceau|titre|piste) precedente?.*|precedent|reviens a la (chanson|piste) precedente.*")) -> return JarvisIntent.Media(MediaCommand.PREVIOUS)
            n.matches(Regex("(mets |met )?(en )?pause.*|(arrete|stoppe|coupe) la (musique|lecture|video).*")) -> return JarvisIntent.Media(MediaCommand.PAUSE)
            n.matches(Regex("(reprends|relance|continue) (la )?(musique|lecture|video).*|lecture|reprends")) -> return JarvisIntent.Media(MediaCommand.PLAY)
        }
        Regex("(mets|joue|lance|ecoute) (de la musique|la musique|la chanson|le morceau|l album|du|de la|des)? ?(.+?)( sur (spotify|youtube|deezer).*)?").matchEntire(n)?.let {
            val q = it.groupValues[3].trim()
            if (it.groupValues[2].contains("musique") || it.groupValues[2].contains("chanson") || it.groupValues[2].contains("morceau") || it.groupValues[2].contains("album") || it.groupValues[4].isNotBlank()) {
                return JarvisIntent.PlayMusic(q)
            }
        }
        if (n.matches(Regex("(mets|joue|lance) (de la |la |une )?musique"))) return JarvisIntent.Media(MediaCommand.PLAY)

        // --- Réglages ---
        settings(n)?.let { return it }

        // --- Communication (actions sensibles, confirmées plus tard) ---
        Regex("(appelle|telephone a|telephone|passe un appel a|compose le numero de|compose le) (.+)").matchEntire(n)?.let {
            return JarvisIntent.Call(it.groupValues[2].trim())
        }
        Regex("(envoie|ecris|envoyer) (un )?(message|sms|texto) a ([a-z0-9 ]+?) (pour lui dire|pour dire|disant|qui dit|que|pour lui demander|dis lui) (.+)").matchEntire(n)?.let {
            return JarvisIntent.SendMessage(it.groupValues[4].trim(), it.groupValues[6].trim())
        }
        Regex("(envoie|ecris) (un )?(message|sms|texto) a ([a-z0-9 ]+)").matchEntire(n)?.let {
            return JarvisIntent.SendMessage(it.groupValues[4].trim(), "")
        }
        Regex("(dis|ecris) a ([a-z]+) (que|de) (.+)").matchEntire(n)?.let {
            return JarvisIntent.SendMessage(it.groupValues[2].trim(), it.groupValues[4].trim())
        }

        // --- Navigation ---
        Regex("(emmene moi|conduis moi|guide moi|itineraire|y aller|navigation|comment aller) (a |au |aux |vers |jusqu a |pour aller a |pour )?(.+)").matchEntire(n)?.let {
            return JarvisIntent.Navigate(it.groupValues[3].trim())
        }

        // --- Notifications ---
        if (n.matches(Regex("(lis|lire|lit|annonce)( moi)? (mes |les )?(dernieres )?notifications.*|(j ai|ai je) (des|de nouvelles|recu des) notifications.*"))) return JarvisIntent.ReadNotifications

        // --- Routines ---
        Regex("(lance|execute|demarre|active) (la )?routine (.+)").matchEntire(n)?.let {
            return JarvisIntent.RunRoutine(it.groupValues[3].trim())
        }

        // --- Applications ---
        Regex("(ouvre|lance|demarre|affiche|va sur|ouvrir) (l application |l appli |l app |application |appli )?(.+)").matchEntire(n)?.let {
            return JarvisIntent.OpenApp(it.groupValues[3].trim())
        }

        // --- Recherche ---
        Regex("(cherche|recherche|trouve|google|qui est|qu est ce que|c est quoi|definition de|que signifie) (moi )?(des informations sur |une information sur |sur internet |sur le web |sur google |des infos sur |la definition de |ce qu est )?(.+)").matchEntire(n)?.let {
            val q = it.groupValues[4].trim()
            val prefix = it.groupValues[1]
            // Pour « qui est » / « c'est quoi », on garde l'expression pour l'IA qui répond mieux.
            return if (prefix in setOf("cherche", "recherche", "trouve", "google")) JarvisIntent.WebSearch(q) else JarvisIntent.Unknown(raw.trim())
        }

        // --- Politesse ---
        if (n.matches(Regex("(bonjour|salut|bonsoir|coucou|hello|hey|tu es la|t es la|es tu la)( jarvis)?"))) return JarvisIntent.Greeting
        if (n.matches(Regex("(merci|merci beaucoup|merci bien|super merci|parfait merci|top merci)"))) return JarvisIntent.Thanks

        return JarvisIntent.Unknown(raw.trim())
    }

    /** Retire le mot de réveil et les politesses en tête de phrase. */
    fun stripWakeWord(normalized: String): String {
        var s = normalized
        for (w in wakeWords.map { Text.normalize(it) }) {
            s = s.replace(Regex("^(ok |dis |hey |he |eh )?$w\\b ?"), "")
        }
        return s.replace(Regex("^(s il te plait|stp|peux tu|est ce que tu peux|tu peux|pourrais tu|tu pourrais|je veux que tu|j aimerais que tu|je voudrais que tu|voudrais tu) "), "")
            .replace(Regex(" (s il te plait|stp)$"), "").trim()
    }

    private val setOps = setOps()
    private fun setOps() = setOf("demain", "aujourd hui", "maintenant", "ce soir", "il fait", "la", "dehors")

    private fun forbidden(n: String): JarvisIntent.Forbidden? {
        val buyVerb = Regex("^(achete|acheter|paie|paye|payer|commande|commander|vire|transfere|envoie de l argent|regle) .*")
        val moneyWords = Regex(".*\\b(virement|carte bancaire|code (pin|secret|de (la )?carte)|bitcoin|crypto(monnaie)?s?)\\b.*")
        if (buyVerb.matches(n) || moneyWords.matches(n)) {
            return JarvisIntent.Forbidden("Je n'effectue ni paiement, ni achat, ni virement. Faites-le vous-même dans l'application concernée, sur son écran de validation.")
        }
        val destroy = Regex(".*(supprime|efface|formate|reinitialise|wipe) ((tous|toutes|tout|mes|les|le|mon|ma) )*(fichiers|photos|donnees|contacts|telephone|messages|applications|compte|sms|videos).*")
        if (destroy.matches(n)) return JarvisIntent.Forbidden("Je ne supprime pas de fichiers, photos, messages ou données. Cette opération doit être faite manuellement.")
        val security = Regex(".*(desactive|enleve|contourne|supprime|cracke|pirate) ((le|mon|la|ton|les|mes) )*(code|mot de passe|verrouillage|empreinte|biometrie|antivirus|securite|play protect).*")
        if (security.matches(n)) return JarvisIntent.Forbidden("Je ne modifie pas les protections de sécurité du téléphone et ne contourne aucun verrouillage.")
        return null
    }

    private fun parseReminder(n: String, t: List<String>): JarvisIntent {
        val body = n.replace(Regex("^(rappelle moi|fais moi penser|n oublie pas de me rappeler|cree un rappel|ajoute un rappel|mets un rappel)( (de|d|que|a|pour)(?= ))? ?"), "")
        // « dans 10 minutes de sortir le linge »
        val dIdx = t.indexOf("dans")
        if (dIdx >= 0) {
            val secs = durationSeconds(t.drop(dIdx))
            if (secs != null) {
                val text = body.replace(Regex("dans [a-z0-9 ]*?(secondes?|minutes?|heures?|h|min)( et demie| et quart| [a-z0-9]+)?"), "")
                    .replace(Regex("^(de|d|que|pour) "), "").replace(Regex(" (de|d|que|pour)$"), "").trim()
                return JarvisIntent.Reminder(text.ifBlank { "Rappel" }, (secs + 59) / 60, null, null)
            }
        }
        val at = Regex(".*\\b(a|vers) (\\d{1,2})( h| heures?|h)( ?\\d{1,2})?.*").matchEntire(n)
        if (at != null) {
            val h = at.groupValues[2].toInt()
            val m = at.groupValues[4].trim().toIntOrNull() ?: 0
            val text = body.replace(Regex("\\b(a|vers) \\d{1,2}( h| heures?|h)( ?\\d{1,2})?"), "")
                .replace(Regex("^(de|d|que|pour) "), "").trim()
            if (h in 0..23 && m in 0..59) return JarvisIntent.Reminder(text.ifBlank { "Rappel" }, null, h, m)
        }
        clockTime(t)?.let { (h, m) ->
            return JarvisIntent.Reminder(body.ifBlank { "Rappel" }, null, h, m)
        }
        return JarvisIntent.Reminder(body.ifBlank { "Rappel" }, null, null, null)
    }

    /** « 5 minutes », « une heure et demie », « 1 minute 30 », « 90 secondes », « deux heures dix ». */
    fun durationSeconds(t: List<String>): Int? {
        var total = 0
        var found = false
        var i = 0
        while (i < t.size) {
            val num = FrenchNumbers.parse(t, i)
            if (num == null) {
                if (t[i] == "demi" || t[i] == "demie") {
                    // « une demi heure »
                    if (i + 1 < t.size && t[i + 1].startsWith("heure")) { total += 1800; found = true; i += 2; continue }
                }
                if (t[i] == "quart" && i + 2 < t.size && t[i + 1] == "d" && t[i + 2].startsWith("heure")) { total += 900; found = true; i += 3; continue }
                i++; continue
            }
            val (value, used) = num
            val unitIdx = i + used
            val unit = t.getOrNull(unitIdx)
            when {
                unit != null && (unit == "heure" || unit == "heures" || unit == "h") -> {
                    total += value * 3600; found = true
                    var j = unitIdx + 1
                    if (t.getOrNull(j) == "et" && (t.getOrNull(j + 1) == "demie" || t.getOrNull(j + 1) == "demi")) { total += 1800; j += 2 }
                    else if (t.getOrNull(j) == "et" && t.getOrNull(j + 1) == "quart") { total += 900; j += 2 }
                    else FrenchNumbers.parse(t, j)?.let { (mins, u) ->
                        if (t.getOrNull(j + u)?.startsWith("seconde") != true) { total += mins * 60; j += u; if (t.getOrNull(j) == "minute" || t.getOrNull(j) == "minutes") j++ }
                    }
                    i = j; continue
                }
                unit != null && (unit == "minute" || unit == "minutes" || unit == "min" || unit == "mn") -> {
                    total += value * 60; found = true
                    var j = unitIdx + 1
                    if (t.getOrNull(j) == "et" && (t.getOrNull(j + 1) == "demie" || t.getOrNull(j + 1) == "demi")) { total += 30; j += 2 }
                    else FrenchNumbers.parse(t, j)?.let { (secs, u) -> total += secs; j += u; if (t.getOrNull(j)?.startsWith("seconde") == true) j++ }
                    i = j; continue
                }
                unit != null && (unit.startsWith("seconde") || unit == "s" || unit == "sec") -> {
                    total += value; found = true; i = unitIdx + 1; continue
                }
                else -> i = unitIdx
            }
        }
        return if (found && total > 0) total else null
    }

    /** « à 7 heures 30 », « 7h30 », « 18:45 », « sept heures et quart », « midi ». */
    fun clockTime(t: List<String>): Pair<Int, Int>? {
        val joined = t.joinToString(" ")
        Regex("\\b(\\d{1,2})(?::| ?h ?)(\\d{2})\\b").find(joined)?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return h to m
        }
        if (joined.contains("midi") && !joined.contains("apres midi")) return 12 to (if (joined.contains("et demi")) 30 else 0)
        if (joined.contains("minuit")) return 0 to 0
        for (i in t.indices) {
            val num = FrenchNumbers.parse(t, i) ?: continue
            val (h, used) = num
            val unit = t.getOrNull(i + used) ?: continue
            if (unit == "h" || unit.startsWith("heure")) {
                var hour = h
                var minute = 0
                val j = i + used + 1
                when {
                    t.getOrNull(j) == "et" && t.getOrNull(j + 1)?.startsWith("demi") == true -> minute = 30
                    t.getOrNull(j) == "et" && t.getOrNull(j + 1) == "quart" -> minute = 15
                    t.getOrNull(j) == "moins" && t.getOrNull(j + 1) == "le" && t.getOrNull(j + 2) == "quart" -> { hour -= 1; minute = 45 }
                    else -> FrenchNumbers.parse(t, j)?.let { minute = it.first }
                }
                if (joined.contains("du soir") || joined.contains("de l apres midi")) if (hour < 12) hour += 12
                if (hour in 0..23 && minute in 0..59) return hour to minute
            }
        }
        return null
    }

    private fun volume(n: String, t: List<String>): JarvisIntent? {
        val pct = Regex("(\\d{1,3}) ?(pour ?cent|%|pourcent)").find(n)?.groupValues?.get(1)?.toIntOrNull()
            ?: t.indexOf("a").takeIf { it >= 0 }?.let { idx -> FrenchNumbers.parse(t, idx + 1)?.first }
        return when {
            n.matches(Regex(".*(coupe|muet|silencieux|mute).*")) -> JarvisIntent.Volume(VolumeCommand.MUTE)
            n.matches(Regex(".*(au max|au maximum|a fond|maximum).*")) -> JarvisIntent.Volume(VolumeCommand.MAX)
            pct != null && pct in 0..100 -> JarvisIntent.Volume(VolumeCommand.SET, pct)
            n.matches(Regex(".*(monte|augmente|plus fort|hausse|leve).*")) -> JarvisIntent.Volume(VolumeCommand.UP)
            n.matches(Regex(".*(baisse|diminue|moins fort|reduis).*")) -> JarvisIntent.Volume(VolumeCommand.DOWN)
            else -> null
        }
    }

    private fun settings(n: String): JarvisIntent? {
        val wantsSettings = n.matches(Regex(".*(reglage|parametre|option|configuration|active|desactive|allume|eteins|coupe|ouvre).*"))
        if (!wantsSettings) return null
        val map = listOf(
            "wifi|wi fi|wi-fi|internet|reseau" to SettingsTarget.WIFI,
            "bluetooth" to SettingsTarget.BLUETOOTH,
            "luminosite|affichage|ecran|mode sombre|theme" to SettingsTarget.DISPLAY,
            "son|sonnerie|vibreur|ne pas deranger" to SettingsTarget.SOUND,
            "localisation|gps|position" to SettingsTarget.LOCATION,
            "batterie|economie d energie|economiseur" to SettingsTarget.BATTERY,
            "mode avion|avion" to SettingsTarget.AIRPLANE,
            "donnees mobiles|4g|5g|data" to SettingsTarget.MOBILE_DATA,
            "nfc|sans contact" to SettingsTarget.NFC,
            "notification" to SettingsTarget.NOTIFICATIONS,
            "stockage|memoire" to SettingsTarget.STORAGE,
            "accessibilite" to SettingsTarget.ACCESSIBILITY,
            "date et heure|fuseau" to SettingsTarget.DATE,
            "securite|verrouillage" to SettingsTarget.SECURITY,
            "applications|applis" to SettingsTarget.APPS,
            "jarvis" to SettingsTarget.JARVIS,
        )
        for ((pattern, target) in map) {
            if (Regex(".*\\b($pattern)\\b.*").matches(n)) return JarvisIntent.OpenSettings(target)
        }
        if (n.matches(Regex("(ouvre )?(les )?(reglages|parametres)( du telephone| systeme)?"))) return JarvisIntent.OpenSettings(SettingsTarget.GENERAL)
        return null
    }
}
