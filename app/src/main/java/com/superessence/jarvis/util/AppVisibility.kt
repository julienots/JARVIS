package com.superessence.jarvis.util

import android.content.Context
import android.provider.Settings

/** Savoir si l'écran de Jarvis est visible (pour éviter un doublon avec la bulle flottante). */
object AppVisibility {
    @Volatile var activityVisible = false

    /** Android bloque l'ouverture d'écrans depuis l'arrière-plan, sauf si l'affichage par-dessus est autorisé. */
    fun canStartActivities(context: Context) = activityVisible || Settings.canDrawOverlays(context)
}
