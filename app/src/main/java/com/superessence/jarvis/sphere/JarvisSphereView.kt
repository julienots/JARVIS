package com.superessence.jarvis.sphere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.data.SphereStyle
import kotlin.math.abs

/** Vue OpenGL transparente qui affiche la sphère ; un toucher la fait réagir, un glissé la fait tourner. */
@SuppressLint("ViewConstructor")
class JarvisSphereView(context: Context, lowPower: Boolean, onTop: Boolean) : GLSurfaceView(context) {

    val renderer = SphereRenderer(lowPower, context.resources.displayMetrics.density)
    var onTap: (() -> Unit)? = null
    var onGlFailure: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val eco = lowPower
    private val ticker = object : Runnable {
        override fun run() { requestRender(); main.postDelayed(this, 50) }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        if (onTop) setZOrderOnTop(true) else setZOrderMediaOverlay(true)
        preserveEGLContextOnPause = true
        // Respecte le réglage « Supprimer les animations » d'Android
        val scale = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        renderer.timeScale = if (scale == 0f) 0.2f else 1f
        renderer.onError = { msg -> main.post { onGlFailure?.invoke(msg) } }
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
        super.onDetachedFromWindow()
    }

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f; private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) + abs(e.y - downY) > 24) moved = true
                if (moved) renderer.drag(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) { renderer.flash(); performClick(); onTap?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
