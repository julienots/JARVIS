package com.superessence.jarvis.sphere

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.data.SphereStyle
import kotlin.math.abs

/**
 * Vue OpenGL transparente qui affiche la sphère, sans aucun fond.
 * Un toucher la fait réagir ; un glissé la fait tourner, ou déplace la bulle flottante si [onDragBy] est défini.
 */
@SuppressLint("ViewConstructor")
class JarvisSphereView(context: Context, lowPower: Boolean) : GLTextureView(context) {

    val renderer = SphereRenderer(lowPower, context.resources.displayMetrics.density)
    var onTap: (() -> Unit)? = null
    var onGlFailure: ((String) -> Unit)? = null
    /** Bulle flottante : le glissé déplace la fenêtre (deltas en pixels écran) au lieu de faire tourner la sphère. */
    var onDragBy: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val eco = lowPower
    private val ticker = object : Runnable {
        override fun run() { requestRender(); main.postDelayed(this, 50) }
    }
    private val longPress = Runnable { if (!moved) { longPressed = true; renderer.flash(); onLongPress?.invoke() } }

    init {
        // Respecte le réglage « Supprimer les animations » d'Android
        val scale = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        renderer.timeScale = if (scale == 0f) 0.2f else 1f
        renderer.onError = { msg -> main.post { onGlFailure?.invoke(msg) } }
        onEglFailure = { msg -> main.post { onGlFailure?.invoke(msg) } }
        setRenderer(renderer)
        // Mode économie : 20 images/s au lieu de 60
        renderMode = if (eco) RENDERMODE_WHEN_DIRTY else RENDERMODE_CONTINUOUSLY
        isClickable = true
        contentDescription = "Sphère Jarvis. Touchez pour parler."
    }

    fun update(state: AssistantState, level: Float, style: SphereStyle) {
        renderer.state = state
        renderer.level = level
        renderer.style = style
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (eco) main.post(ticker)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var moved = false; private var longPressed = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Coordonnées écran : la bulle se déplace sous le doigt, les coordonnées locales ne suffisent pas.
        val x = e.rawX; val y = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y; moved = false; longPressed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (onLongPress != null) main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - downX) + abs(y - downY) > 24) { moved = true; main.removeCallbacks(longPress) }
                if (moved && !longPressed) {
                    val drag = onDragBy
                    if (drag != null) drag(x - lastX, y - lastY) else renderer.drag(x - lastX, y - lastY)
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (moved) onDragEnd?.invoke()
                else if (!longPressed) { renderer.flash(); performClick(); onTap?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
