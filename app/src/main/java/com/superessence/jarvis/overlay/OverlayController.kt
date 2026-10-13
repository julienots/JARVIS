package com.superessence.jarvis.overlay

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.util.AppVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cycle de vie minimal pour héberger Compose dans une fenêtre flottante. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun create() { saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun destroy() { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
}

/**
 * Interface flottante « hologramme » au-dessus des autres applications.
 * Nécessite l'autorisation « Afficher par-dessus les autres applications » ; sans elle, Jarvis reste
 * utilisable dans son application, via la notification et la tuile.
 */
class OverlayController(private val app: JarvisApp) {

    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var hiddenByUser = false

    private val _expanded = MutableStateFlow(true)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _pinned = MutableStateFlow(false)
    val pinned: StateFlow<Boolean> = _pinned.asStateFlow()
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val autoHide = Runnable { maybeAutoHide() }

    fun hasPermission() = Settings.canDrawOverlays(app)

    /** Affiche la bulle si l'utilisateur l'a activée, si elle est autorisée, et si l'appli n'est pas déjà à l'écran. */
    fun showIfAllowed() {
        val s = app.settings.current
        if (!s.overlayEnabled || s.privateMode || hiddenByUser || AppVisibility.activityVisible) return
        show(force = false)
    }

    fun show(force: Boolean) {
        if (force) hiddenByUser = false
        if (!hasPermission()) return
        main.removeCallbacks(autoHide)
        if (AppVisibility.activityVisible && !force) return
        if (view != null) { _expanded.value = true; return }
        val o = OverlayOwner().also { it.create() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o as LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { OverlayContent(app, this@OverlayController) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = app.resources.displayMetrics
            x = savedX ?: (dm.widthPixels * 0.04f).toInt()
            y = savedY ?: (dm.heightPixels * 0.08f).toInt()
            title = "Jarvis"
        }
        try {
            wm.addView(v, lp)
            o.resume()
            view = v; owner = o; params = lp
            _expanded.value = true
            _visible.value = true
        } catch (e: Exception) {
            o.destroy()
        }
    }

    private var savedX: Int? = null
    private var savedY: Int? = null

    fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val lp = params ?: return
        val dm = app.resources.displayMetrics
        lp.x = (lp.x + dx).toInt().coerceIn(0, (dm.widthPixels - v.width).coerceAtLeast(0))
        lp.y = (lp.y + dy).toInt().coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))
        savedX = lp.x; savedY = lp.y
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    fun toggleExpanded() { _expanded.value = !_expanded.value; scheduleAutoHide() }
    fun collapse() { _expanded.value = false; scheduleAutoHide() }
    fun togglePinned() { _pinned.value = !_pinned.value; if (!_pinned.value) scheduleAutoHide() else main.removeCallbacks(autoHide) }

    /** Fermeture : la bulle disparaît sans toucher à l'application en cours ni annuler les actions. */
    fun hide(userRequested: Boolean) {
        if (userRequested) hiddenByUser = true
        main.removeCallbacks(autoHide)
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        owner?.destroy()
        view = null; owner = null; params = null
        _visible.value = false
    }

    /** L'utilisateur peut de nouveau faire apparaître la bulle à la prochaine demande. */
    fun resetUserHide() { hiddenByUser = false }

    fun scheduleAutoHide() {
        main.removeCallbacks(autoHide)
        if (view == null) return
        main.postDelayed(autoHide, app.settings.current.overlayAutoHideSeconds.coerceAtLeast(2) * 1000L)
    }

    private fun maybeAutoHide() {
        val busy = app.session.state.value.let { it != AssistantState.IDLE && it != AssistantState.MIC_OFF && it != AssistantState.ERROR }
        val keep = app.session.cards.value.any { it.pinned || it.card is PanelCard.Countdown || it.card is PanelCard.Confirm }
        when {
            _pinned.value -> {}
            busy || keep -> { if (keep && !busy) _expanded.value = true; scheduleAutoHide() }
            else -> hide(userRequested = false)
        }
    }

    /** Quand l'application s'ouvre, sa propre interface prend le relais. */
    fun onAppVisible(visible: Boolean) {
        if (visible) hide(userRequested = false)
    }
}
