package com.superessence.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.panel.PanelCard

/** Rappels, minuteurs internes et routines planifiées. */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMINDER = "com.superessence.jarvis.REMINDER"
        const val ACTION_TIMER = "com.superessence.jarvis.TIMER"
        const val ACTION_ROUTINE = "com.superessence.jarvis.ROUTINE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        when (intent.action) {
            ACTION_REMINDER -> {
                val id = intent.getIntExtra("id", 0)
                val text = intent.getStringExtra("text").orEmpty()
                Notifications.postReminder(context, id, text)
                app.reminders.remove(id)
                app.session.announce("Rappel : $text", listOf(PanelCard.Info("Rappel", text, lifetimeMs = 30_000)))
            }
            ACTION_TIMER -> {
                val label = intent.getStringExtra("text") ?: "Minuteur"
                context.getSharedPreferences("jarvis_timer", Context.MODE_PRIVATE).edit().remove("at").apply()
                Notifications.postTimerDone(context, label)
                app.session.announce("Le minuteur est terminé.")
            }
            ACTION_ROUTINE -> {
                val id = intent.getStringExtra("id") ?: return
                app.session.runRoutineNow(id)
                app.scheduler.scheduleRoutines()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        app.scheduler.rescheduleAll()
        val s = app.settings.current
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && s.wakeEnabled && s.resumeAfterBoot && !s.privateMode) {
            // Android 14+ interdit de démarrer un service micro au démarrage : on propose une reprise en un geste.
            if (Build.VERSION.SDK_INT >= 34) Notifications.postResume(context)
            else try { JarvisService.start(context) } catch (_: Exception) { Notifications.postResume(context) }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
