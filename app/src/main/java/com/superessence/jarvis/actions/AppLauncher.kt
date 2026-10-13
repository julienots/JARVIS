package com.superessence.jarvis.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.superessence.jarvis.core.text.Text

/** Recherche d'une application installée par son nom prononcé (tolère les approximations). */
class AppLauncher(private val context: Context) {

    data class App(val label: String, val packageName: String, val normalized: String)

    @Volatile private var cache: List<App>? = null
    private var cacheAt = 0L

    fun apps(): List<App> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheAt < 60_000) return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            val label = it.loadLabel(pm).toString()
            App(label, it.activityInfo.packageName, Text.normalize(label))
        }.distinctBy { it.packageName }
        cache = list; cacheAt = now
        return list
    }

    private val aliases = mapOf(
        "youtube" to listOf("youtube"), "whatsapp" to listOf("whatsapp", "whats app", "watsap", "ouatsap"),
        "gmail" to listOf("gmail", "mail", "mails", "email", "e mail", "courriel"),
        "maps" to listOf("maps", "google maps", "carte", "cartes", "plans"),
        "chrome" to listOf("chrome", "navigateur", "internet", "google chrome"),
        "calculatrice" to listOf("calculatrice", "calculette", "calculator"),
        "agenda" to listOf("agenda", "calendrier", "calendar"),
        "galerie" to listOf("galerie", "photos", "gallery", "google photos"),
        "horloge" to listOf("horloge", "clock", "reveil"),
        "play store" to listOf("play store", "store", "playstore", "google play"),
        "instagram" to listOf("insta", "instagram"), "facebook" to listOf("facebook", "fb"),
        "snapchat" to listOf("snap", "snapchat"), "tiktok" to listOf("tiktok", "tik tok"),
        "spotify" to listOf("spotify", "spotifaille"), "netflix" to listOf("netflix"),
        "contacts" to listOf("contacts", "repertoire"), "parametres" to listOf("parametres", "reglages", "settings"),
        "messages" to listOf("messages", "sms", "textos"), "telephone" to listOf("telephone", "appels", "phone"),
        "appareil photo" to listOf("appareil photo", "camera", "photo"),
    )

    fun find(spoken: String): App? {
        val q = Text.normalize(spoken).removePrefix("l ").removeSuffix(" s il te plait").trim()
        if (q.isBlank()) return null
        val all = apps()
        all.firstOrNull { it.normalized == q }?.let { return it }
        // alias → mot clé que le libellé de l'application doit contenir
        for ((key, words) in aliases) {
            if (q in words) {
                all.firstOrNull { it.normalized == key || it.normalized.contains(key) }?.let { return it }
                for (w in words) all.firstOrNull { it.normalized == w || it.normalized.contains(w) }?.let { return it }
            }
        }
        all.filter { it.normalized.startsWith(q) || it.normalized.split(' ').contains(q) }.minByOrNull { it.label.length }?.let { return it }
        all.filter { it.normalized.contains(q) }.minByOrNull { it.label.length }?.let { return it }
        val best = all.maxByOrNull { Text.similarity(it.normalized, q) } ?: return null
        return best.takeIf { Text.similarity(it.normalized, q) >= 0.7 }
    }

    fun launchIntent(app: App): Intent? = context.packageManager.getLaunchIntentForPackage(app.packageName)
}
