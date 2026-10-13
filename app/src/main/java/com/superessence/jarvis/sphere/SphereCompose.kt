package com.superessence.jarvis.sphere

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.data.SphereStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * La sphère Jarvis en 3D (OpenGL). En cas d'échec OpenGL, une version 2D animée prend le relais.
 * [onTop] : vrai dans l'interface flottante (la surface doit passer au-dessus de la fenêtre).
 */
@Composable
fun JarvisSphere(
    state: AssistantState,
    level: Float,
    style: SphereStyle,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
    onTop: Boolean = false,
    onTap: () -> Unit = {},
) {
    var glFailed by remember { mutableStateOf(false) }
    val description = "Jarvis : ${state.label}"
    if (glFailed) {
        FallbackSphere(state, level, style, modifier.semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, null) { onTap() })
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var viewRef by remember { mutableStateOf<JarvisSphereView?>(null) }
    DisposableEffect(lifecycle, viewRef) {
        val v = viewRef
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    Box(modifier.semantics { contentDescription = description }) {
        AndroidView(
            factory = { ctx ->
                JarvisSphereView(ctx, lowPower, onTop).also { v ->
                    v.onGlFailure = { glFailed = true }
                    viewRef = v
                }
            },
            update = { v ->
                v.onTap = onTap
                v.update(state, level, style)
            },
        )
    }
}

/** Version 2D (Canvas) : relief simulé par dégradés et contours animés. */
@Composable
fun FallbackSphere(state: AssistantState, level: Float, style: SphereStyle, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "sphere").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val pal = Palette.of(style, state)
    val a = Color(pal.a[0], pal.a[1], pal.a[2])
    val b = Color(pal.b[0], pal.b[1], pal.b[2])
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.32f * (1f + level * 0.08f + 0.02f * sin(t * 2))
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.35f), Color.Transparent), c, r * 1.8f), r * 1.8f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), a, b), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.5f), r, c)
        for (k in 0 until 5) {
            val wobble = r * (0.25f + 0.14f * k) * (1f + 0.08f * sin(t * (1 + k) + k))
            drawCircle(a.copy(alpha = 0.25f), wobble, Offset(c.x + cos(t + k) * r * 0.1f, c.y + sin(t * 1.3f + k) * r * 0.1f), style = Stroke(1.5f))
        }
        drawOval(a.copy(alpha = 0.5f), Offset(c.x - r * 1.45f, c.y - r * 0.38f), androidx.compose.ui.geometry.Size(r * 2.9f, r * 0.76f), style = Stroke(2f))
    }
}
