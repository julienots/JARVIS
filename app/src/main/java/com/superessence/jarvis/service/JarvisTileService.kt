package com.superessence.jarvis.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.superessence.jarvis.ui.MainActivity

/** Tuile « Jarvis » des Réglages rapides : parler en un geste, même sans écoute permanente. */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (JarvisService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (JarvisService.running.value) "Écoute active" else "Toucher pour parler"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (JarvisService.running.value) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_TALK))
            return
        }
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_TALK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
