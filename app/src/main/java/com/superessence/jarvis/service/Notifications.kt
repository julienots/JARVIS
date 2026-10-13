package com.superessence.jarvis.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.superessence.jarvis.R
import com.superessence.jarvis.ui.MainActivity

object Notifications {
    const val CH_SERVICE = "jarvis_service"
    const val CH_REMINDER = "jarvis_reminders"
    const val CH_TIMER = "jarvis_timers"
    const val CH_INFO = "jarvis_info"

    const val ID_SERVICE = 1
    const val ID_RESUME = 2
    const val ID_OPEN = 3
    const val ID_TIMER = 4

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Écoute de Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Indique que le micro écoute le mot « Jarvis ». Obligatoire pour l'écoute en arrière-plan."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "Rappels", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rappels créés par la voix."
        })
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Minuteurs", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Fin des minuteurs internes."
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Informations", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canPost(ctx: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(ctx: Context, action: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            this.action = action ?: Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(ctx, (action ?: "main").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun serviceNotification(ctx: Context, status: String, listening: Boolean): Notification {
        val talk = PendingIntent.getService(ctx, 10, Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(ctx, 11, Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (listening) "Jarvis écoute le mot « Jarvis »" else "Jarvis")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, "Parler", talk)
            .addAction(0, "Arrêter l'écoute", stop)
            .build()
    }

    /** Android ne permet pas de relancer le micro tout seul après un redémarrage : on propose de le faire. */
    fun postResume(ctx: Context) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Réactiver l'écoute de Jarvis")
            .setContentText("Touchez pour reprendre l'écoute du mot « Jarvis ».")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, MainActivity.ACTION_RESUME_WAKE))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RESUME, n)
    }

    fun postOpenRequest(ctx: Context, label: String, target: Intent) {
        if (!canPost(ctx)) return
        val pi = PendingIntent.getActivity(ctx, label.hashCode(), target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis : ouvrir $label")
            .setContentText("Touchez pour ouvrir. Android bloque l'ouverture automatique depuis l'arrière-plan.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_OPEN, n)
    }

    fun postReminder(ctx: Context, id: Int, text: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Rappel")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + id, n)
    }

    fun postTimerDone(ctx: Context, label: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Minuteur terminé")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_TIMER, n)
    }
}
