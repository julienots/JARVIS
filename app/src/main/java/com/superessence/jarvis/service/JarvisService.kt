package com.superessence.jarvis.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.core.wake.WakeWordMatcher
import com.superessence.jarvis.data.WakeEngineChoice
import com.superessence.jarvis.voice.RecognizerWakeWordEngine
import com.superessence.jarvis.voice.VoskWakeWordEngine
import com.superessence.jarvis.voice.WakeWordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service au premier plan (type « microphone ») qui écoute le mot de réveil.
 * Une notification permanente indique honnêtement que le micro est actif.
 * Android exige que ce service soit démarré pendant que Jarvis est visible (ou depuis une notification / tuile).
 */
class JarvisService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.superessence.jarvis.START"
        const val ACTION_STOP = "com.superessence.jarvis.STOP"
        const val ACTION_TALK = "com.superessence.jarvis.TALK"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _status = MutableStateFlow("")
        val status: StateFlow<String> = _status.asStateFlow()

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, JarvisService::class.java).setAction(ACTION_STOP))
        }
    }

    private val app get() = application as JarvisApp
    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeWordEngine? = null
    private var retries = 0
    private var matcher = WakeWordMatcher()

    override fun onCreate() {
        super.onCreate()
        val s = app.session
        s.onMicNeeded = { pauseWake() }
        s.onSessionIdle = { main.postDelayed({ resumeWake() }, 350) }
        s.onStopWakeRequested = {
            app.settings.update { it.copy(wakeEnabled = false) }
            shutdown()
        }
        lifecycleScope.launch {
            s.state.collect { st ->
                updateNotification()
                // Pendant que Jarvis parle, le moteur Vosk reste actif pour permettre l'interruption (« Jarvis… »).
                if (st == AssistantState.SPEAKING && engine is VoskWakeWordEngine && engine?.isRunning == false) resumeWake()
            }
        }
        lifecycleScope.launch {
            app.settings.flow.collect { cfg ->
                matcher = WakeWordMatcher(cfg.wakeWord.ifBlank { "jarvis" })
                if (cfg.privateMode) shutdown()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                app.settings.update { it.copy(wakeEnabled = false) }
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TALK -> {
                if (!goForeground()) return START_NOT_STICKY
                app.session.startListening()
            }
            else -> {
                if (!goForeground()) return START_NOT_STICKY
                resumeWake()
            }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        if (!app.session.hasMicPermission()) {
            _status.value = "Autorisation du micro manquante."
            stopSelf(); return false
        }
        return try {
            val n = Notifications.serviceNotification(this, "Prêt", listening = true)
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, n, type)
            _running.value = true
            true
        } catch (e: Exception) {
            // Android 12+ : démarrage depuis l'arrière-plan refusé. On propose une reprise manuelle.
            _status.value = "Android a refusé de démarrer l'écoute en arrière-plan."
            Notifications.postResume(this)
            stopSelf()
            false
        }
    }

    private fun chooseEngine(): WakeWordEngine {
        val choice = app.settings.current.wakeEngine
        val useVosk = when (choice) {
            WakeEngineChoice.VOSK -> true
            WakeEngineChoice.ANDROID -> false
            WakeEngineChoice.AUTO -> app.vosk.isInstalled()
        }
        val current = engine
        if (useVosk && current is VoskWakeWordEngine) return current
        if (!useVosk && current is RecognizerWakeWordEngine) return current
        current?.release()
        return (if (useVosk) VoskWakeWordEngine(app.vosk) { matcher } else RecognizerWakeWordEngine(this) { matcher }).also { engine = it }
    }

    private fun resumeWake() {
        if (!_running.value) return
        val s = app.settings.current
        if (!s.wakeEnabled || s.privateMode) { updateNotification(); return }
        val st = app.session.state.value
        val canListen = st == AssistantState.IDLE || st == AssistantState.ERROR ||
            (st == AssistantState.SPEAKING && engine is VoskWakeWordEngine)
        if (!canListen) return
        val e = chooseEngine()
        if (e.isRunning) return
        e.start(
            onWake = { remainder -> retries = 0; e.stop(); app.session.onWakeWord(remainder) },
            onError = { msg ->
                _status.value = msg
                e.stop()
                retries++
                if (retries <= 5) main.postDelayed({ resumeWake() }, 2000L * retries)
                updateNotification()
            },
        )
        _status.value = "Dites « ${s.wakeWord} » · moteur : ${e.name}"
        updateNotification()
    }

    private fun pauseWake() {
        engine?.stop()
    }

    private fun updateNotification() {
        if (!_running.value) return
        val st = app.session.state.value
        val listening = engine?.isRunning == true
        val text = when (st) {
            AssistantState.IDLE -> if (listening) "Dites « ${app.settings.current.wakeWord} » pour parler" else (_status.value.ifBlank { "En pause" })
            else -> st.label
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Notifications.canPost(this)) nm.notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, text, listening))
    }

    private fun shutdown() {
        engine?.release(); engine = null
        _running.value = false
        _status.value = "Écoute arrêtée"
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.release(); engine = null
        _running.value = false
        app.session.onMicNeeded = null
        app.session.onSessionIdle = null
        app.session.onStopWakeRequested = null
        super.onDestroy()
    }
}
