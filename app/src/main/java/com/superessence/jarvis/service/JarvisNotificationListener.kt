package com.superessence.jarvis.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lecture des notifications à voix haute, seulement si l'utilisateur l'active dans Android.
 * Rien n'est stocké sur disque ni envoyé : les 15 dernières notifications restent en mémoire.
 * Les applications sensibles (banques, mots de passe, codes) sont ignorées.
 */
class JarvisNotificationListener : NotificationListenerService() {

    data class Item(val app: String, val title: String, val text: String, val at: Long)

    companion object {
        private val items = ArrayDeque<Item>()
        private val ignoredKeywords = listOf("bank", "banque", "pay", "password", "authenticator", "bitwarden", "lastpass", "1password", "keepass", "otp")

        @Synchronized fun recent(): List<Item> = items.toList().reversed()
        @Synchronized private fun add(i: Item) { items.addLast(i); while (items.size > 15) items.removeFirst() }
        @Synchronized fun clear() = items.clear()

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, JarvisNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.visibility == Notification.VISIBILITY_SECRET) return
        val pkg = sbn.packageName.lowercase()
        if (ignoredKeywords.any { pkg.contains(it) }) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        if (Regex("\\b(code|otp|mot de passe|password)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title + " " + text)) return
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        add(Item(label, title, text, sbn.postTime))
    }

    override fun onListenerDisconnected() { clear() }
}
