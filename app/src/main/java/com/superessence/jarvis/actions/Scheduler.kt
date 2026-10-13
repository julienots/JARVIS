package com.superessence.jarvis.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.service.AlarmReceiver
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Planification via AlarmManager : rappels, minuteurs internes et routines horaires.
 * Sans l'autorisation « alarmes exactes » (Android 12+), Android peut décaler l'alarme de quelques minutes.
 */
class Scheduler(private val context: Context) {

    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun pending(action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).setAction(action).apply(extras)
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(atMs: Long, pi: PendingIntent) {
        if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    fun scheduleReminder(id: Int, text: String, atMs: Long) =
        setAt(atMs, pending(AlarmReceiver.ACTION_REMINDER, id) { putExtra("id", id); putExtra("text", text) })

    fun cancelReminder(id: Int) = am.cancel(pending(AlarmReceiver.ACTION_REMINDER, id))

    fun scheduleTimer(atMs: Long, label: String) {
        setAt(atMs, pending(AlarmReceiver.ACTION_TIMER, TIMER_CODE) { putExtra("text", label); putExtra("at", atMs) })
        context.getSharedPreferences("jarvis_timer", Context.MODE_PRIVATE).edit().putLong("at", atMs).apply()
    }

    fun cancelTimer(): Boolean {
        val prefs = context.getSharedPreferences("jarvis_timer", Context.MODE_PRIVATE)
        val had = prefs.getLong("at", 0) > System.currentTimeMillis()
        am.cancel(pending(AlarmReceiver.ACTION_TIMER, TIMER_CODE))
        prefs.edit().remove("at").apply()
        return had
    }

    fun scheduleRoutines() {
        val app = JarvisApp.instance
        val now = LocalDateTime.now()
        for (r in app.routines.items) {
            val code = ROUTINE_BASE + (r.id.hashCode() and 0xFFFF)
            val next = r.nextRun(now)
            val pi = pending(AlarmReceiver.ACTION_ROUTINE, code) { putExtra("id", r.id) }
            if (next == null) am.cancel(pi)
            else setAt(next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
        }
    }

    /** Après un redémarrage ou une mise à jour, Android efface les alarmes : on les reprogramme. */
    fun rescheduleAll() {
        val app = JarvisApp.instance
        val now = System.currentTimeMillis()
        app.reminders.items.filter { it.atEpochMs > now }.forEach { scheduleReminder(it.id, it.text, it.atEpochMs) }
        app.reminders.items.filter { it.atEpochMs <= now - 3_600_000 }.forEach { app.reminders.remove(it.id) }
        val timerAt = context.getSharedPreferences("jarvis_timer", Context.MODE_PRIVATE).getLong("at", 0)
        if (timerAt > now) scheduleTimer(timerAt, "Minuteur")
        scheduleRoutines()
    }

    companion object {
        const val TIMER_CODE = 7001
        const val ROUTINE_BASE = 100_000
    }
}
