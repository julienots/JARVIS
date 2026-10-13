package com.superessence.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JColors {
    val Bg = Color(0xFF02060C)
    val Surface = Color(0xFF07121F)
    val SurfaceHigh = Color(0xFF0C1C2E)
    val Cyan = Color(0xFF22E4FF)
    val CyanDim = Color(0xFF0E7FA8)
    val Gold = Color(0xFFFFD02A)
    val Text = Color(0xFFE6F7FF)
    val TextDim = Color(0xFF8AA4B8)
    val Error = Color(0xFFFF5D6C)
    val Ok = Color(0xFF3BE8A0)
    val Glass = Color(0xCC061424)
}

private val scheme = darkColorScheme(
    primary = JColors.Cyan,
    onPrimary = Color(0xFF00141C),
    secondary = JColors.Gold,
    onSecondary = Color(0xFF1E1600),
    background = JColors.Bg,
    onBackground = JColors.Text,
    surface = JColors.Surface,
    onSurface = JColors.Text,
    surfaceVariant = JColors.SurfaceHigh,
    onSurfaceVariant = JColors.TextDim,
    error = JColors.Error,
    outline = Color(0xFF1E3A52),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
