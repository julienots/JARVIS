package com.superessence.jarvis.util

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.superessence.jarvis.service.JarvisNotificationListener

/** État des autorisations et ouverture des bons écrans de réglages Android. */
object Perms {
    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun mic(ctx: Context) = has(ctx, Manifest.permission.RECORD_AUDIO)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun contacts(ctx: Context) = has(ctx, Manifest.permission.READ_CONTACTS)
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun exactAlarms(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun batteryUnrestricted(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun notificationAccess(ctx: Context) = JarvisNotificationListener.isEnabled(ctx)

    private fun open(ctx: Context, i: Intent, fallback: Intent? = null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) {
            fallback?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        } catch (_: SecurityException) {}
    }

    private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun openOverlaySettings(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")), appDetails(ctx))

    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")), appDetails(ctx))
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryUnrestricted(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openNotificationAccess(ctx: Context) = open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))
    fun openAppSettings(ctx: Context) = open(ctx, appDetails(ctx))
    fun openDefaultAssistant(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    fun openTtsSettings(ctx: Context) = open(ctx, Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    fun openUrl(ctx: Context, url: String) = open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
