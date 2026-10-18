package com.superessence.jarvis.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.service.JarvisService
import com.superessence.jarvis.ui.theme.JarvisTheme
import com.superessence.jarvis.util.AppVisibility
import com.superessence.jarvis.util.Perms

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_TALK = "com.superessence.jarvis.action.TALK"
        const val ACTION_OVERLAY = "com.superessence.jarvis.action.OVERLAY"
        const val ACTION_RESUME_WAKE = "com.superessence.jarvis.action.RESUME_WAKE"
        const val EXTRA_SCREEN = "screen"
        /** L'écran SuperEssence n'est montré qu'au premier lancement du processus. */
        var splashShown = false
    }

    private val app get() = application as JarvisApp
    /** Incrémenté à chaque retour sur l'application : les écrans relisent l'état des autorisations. */
    val resumeTick = mutableIntStateOf(0)
    val pendingTalk = mutableStateOf(false)
    val requestedScreen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { JarvisTheme { AppRoot(this, app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            ACTION_TALK, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> pendingTalk.value = true
            ACTION_OVERLAY -> {
                app.overlay.resetUserHide()
                if (Perms.overlay(this)) {
                    moveTaskToBack(true)
                    window.decorView.postDelayed({ app.overlay.show(force = true) }, 400)
                } else requestedScreen.value = "permissions"
            }
            ACTION_RESUME_WAKE -> if (Perms.mic(this) && !app.settings.current.privateMode) {
                app.settings.update { it.copy(wakeEnabled = true) }
                JarvisService.start(this)
            }
        }
        i?.getStringExtra(EXTRA_SCREEN)?.let { requestedScreen.value = it }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.activityVisible = true
        app.overlay.onAppVisible(true)
        resumeTick.intValue++
    }

    override fun onPause() {
        super.onPause()
        AppVisibility.activityVisible = false
        // Si une conversation est en cours, elle continue dans la bulle flottante.
        if (app.session.isBusy || app.session.cards.value.isNotEmpty()) app.overlay.showIfAllowed()
        else app.overlay.onAppVisible(false)
    }
}
