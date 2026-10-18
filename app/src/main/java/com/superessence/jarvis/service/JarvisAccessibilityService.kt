package com.superessence.jarvis.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.superessence.jarvis.core.intent.SystemAction

/**
 * Service d'accessibilité de Jarvis (facultatif, activé par l'utilisateur dans les réglages Android).
 * Il permet des gestes système à la voix (retour, accueil, capture, verrouillage…) et la lecture du
 * texte affiché à l'écran, sans changer d'application. Il n'écoute aucun événement en continu :
 * l'écran n'est lu qu'à la demande, et les champs de mot de passe sont toujours ignorés.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, JarvisAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Exécute un geste système ; renvoie false si Android ne le permet pas sur cette version. */
    fun perform(action: SystemAction): Boolean {
        val global = when (action) {
            SystemAction.BACK -> GLOBAL_ACTION_BACK
            SystemAction.HOME -> GLOBAL_ACTION_HOME
            SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
            SystemAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            SystemAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            SystemAction.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            SystemAction.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
        }
        return performGlobalAction(global)
    }

    /** Texte visible de l'application au premier plan (hors Jarvis et hors mots de passe). */
    fun readScreen(maxChars: Int = 6000): ScreenText? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val lines = LinkedHashSet<String>()
        collect(root, lines, 0)
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val text = buildString {
            for (l in lines) { if (length + l.length > maxChars) break; appendLine(l) }
        }.trim()
        return ScreenText(label, text)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 40 || node.isPassword) return
        if (node.packageName?.toString() == packageName) return
        val t = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!t.isNullOrBlank() && node.isVisibleToUser) out += t
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    data class ScreenText(val app: String, val text: String)
}
