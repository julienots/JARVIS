package com.superessence.jarvis.overlay

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
 * Une fenêtre flottante Compose, transparente et non bloquante : elle ne prend jamais le clavier
 * et les touchers en dehors d'elle vont à l'application en dessous.
 */
private class FloatingWindow(private val app: JarvisApp, private val wm: WindowManager, private val name: String) {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    var x: Int? = null; private set
    var y: Int? = null; private set
    val isShown get() = view != null

    fun show(defaultX: Int, defaultY: Int, content: @Composable () -> Unit): Boolean {
        if (view != null) return true
        val o = OverlayOwner().also { it.create() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o as LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(o)
            setContent(content)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x ?: defaultX
            this.y = y ?: defaultY
            title = "Jarvis $name"
        }
        return try {
            wm.addView(v, lp)
            o.resume()
            view = v; owner = o; params = lp
            true
        } catch (e: Exception) {
            o.destroy(); false
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val lp = params ?: return
        val dm = app.resources.displayMetrics
        lp.x = (lp.x + dx).toInt().coerceIn(-v.width / 3, (dm.widthPixels - v.width * 2 / 3).coerceAtLeast(0))
        lp.y = (lp.y + dy).toInt().coerceIn(0, (dm.heightPixels - v.height / 2).coerceAtLeast(0))
        x = lp.x; y = lp.y
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        owner?.destroy()
        view = null; owner = null; params = null
    }
}

/**
 * Interface flottante au-dessus des autres applications, en deux parties indépendantes :
 *  - la **bulle** : uniquement la sphère, sans fond, qui s'anime quand Jarvis écoute et parle ;
 *  - le **mini-écran** : la réponse et les cartes (météo, minuteur, résultats…).
 * Les deux se déplacent au doigt, se ferment, et disparaissent seules au bout d'un moment.
 * Elles ne prennent jamais le focus : l'utilisateur continue d'utiliser l'application en dessous.
 * Nécessite l'autorisation « Afficher par-dessus les autres applications ».
 */
class OverlayController(private val app: JarvisApp) {

    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bubble = FloatingWindow(app, wm, "bulle")
    private val panel = FloatingWindow(app, wm, "panneau")
    private var hiddenByUser = false

    private val _pinned = MutableStateFlow(false)
    val pinned: StateFlow<Boolean> = _pinned.asStateFlow()
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    /** La réponse texte reste affichée dans le mini-écran jusqu'à expiration ou fermeture. */
    private val _replyShown = MutableStateFlow(false)
    val replyShown: StateFlow<Boolean> = _replyShown.asStateFlow()

    private val autoHide = Runnable { maybeAutoHide() }
    private val replyTimeout = Runnable { _replyShown.value = false }

    init {
        // Une nouvelle demande ou une nouvelle réponse rouvre le mini-écran.
        scope.launch {
            combine(app.session.lastUser, app.session.lastReply) { u, r -> u to r }.collect { (u, r) ->
                if (u.isNotBlank() || r.isNotBlank()) { main.removeCallbacks(replyTimeout); _replyShown.value = true }
            }
        }
        scope.launch {
            combine(app.session.state, app.session.cards, _replyShown) { st, cards, reply -> Triple(st, cards, reply) }
                .collect { (st, cards, reply) ->
                    if (!bubble.isShown) return@collect
                    val busy = st != AssistantState.IDLE && st != AssistantState.MIC_OFF && st != AssistantState.ERROR
                    if (!busy && reply) {
                        main.removeCallbacks(replyTimeout)
                        main.postDelayed(replyTimeout, autoHideMs())
                    }
                    if (busy || reply || cards.isNotEmpty()) showPanel() else panel.hide()
                }
        }
    }

    fun hasPermission() = Settings.canDrawOverlays(app)

    private fun autoHideMs() = app.settings.current.overlayAutoHideSeconds.coerceAtLeast(2) * 1000L

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
        if (bubble.isShown) { showPanelIfNeeded(); return }
        val dm = app.resources.displayMetrics
        val ok = bubble.show((dm.widthPixels - 110 * dm.density).toInt(), (dm.heightPixels * 0.18f).toInt()) {
            BubbleContent(app, this)
        }
        _visible.value = ok
        if (ok) showPanelIfNeeded()
    }

    private fun showPanelIfNeeded() {
        val s = app.session
        if (s.isBusy || _replyShown.value || s.cards.value.isNotEmpty()) showPanel()
    }

    private fun showPanel() {
        if (panel.isShown || !bubble.isShown) return
        val dm = app.resources.displayMetrics
        panel.show((dm.widthPixels * 0.05f).toInt(), (dm.heightPixels * 0.18f + 100 * dm.density).toInt()) {
            PanelContent(app, this)
        }
    }

    fun moveBubble(dx: Float, dy: Float) = bubble.moveBy(dx, dy)
    fun movePanel(dx: Float, dy: Float) = panel.moveBy(dx, dy)

    /** Ferme le mini-écran : la réponse et les cartes non épinglées disparaissent, la bulle reste. */
    fun closePanel() {
        _replyShown.value = false
        val s = app.session
        s.cards.value.filter { !it.pinned }.forEach { s.dismissCard(it.card.key) }
        panel.hide()
        scheduleAutoHide()
    }

    fun collapse() = closePanel()
    fun togglePinned() { _pinned.value = !_pinned.value; if (!_pinned.value) scheduleAutoHide() else main.removeCallbacks(autoHide) }

    /** Fermeture : la bulle disparaît sans toucher à l'application en cours ni annuler les actions. */
    fun hide(userRequested: Boolean) {
        if (userRequested) hiddenByUser = true
        main.removeCallbacks(autoHide)
        panel.hide()
        bubble.hide()
        _visible.value = false
    }

    /** L'utilisateur peut de nouveau faire apparaître la bulle à la prochaine demande. */
    fun resetUserHide() { hiddenByUser = false }

    fun scheduleAutoHide() {
        main.removeCallbacks(autoHide)
        if (!bubble.isShown) return
        main.postDelayed(autoHide, autoHideMs())
    }

    private fun maybeAutoHide() {
        val s = app.session
        val keep = s.cards.value.any { it.pinned || it.card is PanelCard.Countdown || it.card is PanelCard.Confirm }
        when {
            _pinned.value || app.settings.current.overlayAlwaysOn -> {}
            s.isBusy || keep || _replyShown.value || s.cards.value.isNotEmpty() -> scheduleAutoHide()
            else -> hide(userRequested = false)
        }
    }

    /** Quand l'application s'ouvre, sa propre interface prend le relais ; quand on la quitte, la bulle permanente revient. */
    fun onAppVisible(visible: Boolean) {
        if (visible) hide(userRequested = false)
        else if (app.settings.current.overlayAlwaysOn) showIfAllowed()
    }
}
