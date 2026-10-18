package com.superessence.jarvis

import android.app.Application
import com.superessence.jarvis.actions.AndroidActionExecutor
import com.superessence.jarvis.actions.Scheduler
import com.superessence.jarvis.core.ai.AiOrchestrator
import com.superessence.jarvis.core.ai.UrlConnectionHttpClient
import com.superessence.jarvis.core.brain.AssistantBrain
import com.superessence.jarvis.core.intent.IntentParser
import com.superessence.jarvis.data.ActionLogStore
import com.superessence.jarvis.data.ConversationStore
import com.superessence.jarvis.data.ReminderStore
import com.superessence.jarvis.data.RoutineStore
import com.superessence.jarvis.data.SecureKeyStore
import com.superessence.jarvis.data.SettingsRepository
import com.superessence.jarvis.data.TaskStore
import com.superessence.jarvis.overlay.OverlayController
import com.superessence.jarvis.runtime.SessionController
import com.superessence.jarvis.service.Notifications
import com.superessence.jarvis.voice.VoskModelManager

/** Point d'entrée : construit les composants partagés (injection manuelle, sans framework). */
class JarvisApp : Application() {

    lateinit var settings: SettingsRepository; private set
    lateinit var tasks: TaskStore; private set
    lateinit var reminders: ReminderStore; private set
    lateinit var routines: RoutineStore; private set
    lateinit var actionLog: ActionLogStore; private set
    lateinit var conversation: ConversationStore; private set
    lateinit var vosk: VoskModelManager; private set
    lateinit var scheduler: Scheduler; private set
    lateinit var http: UrlConnectionHttpClient; private set
    lateinit var ai: AiOrchestrator; private set
    lateinit var executor: AndroidActionExecutor; private set
    lateinit var brain: AssistantBrain; private set
    lateinit var session: SessionController; private set
    lateinit var overlay: OverlayController; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this, SecureKeyStore(this))
        tasks = TaskStore(this)
        reminders = ReminderStore(this)
        routines = RoutineStore(this)
        actionLog = ActionLogStore(this)
        conversation = ConversationStore(this)
        vosk = VoskModelManager(this)
        scheduler = Scheduler(this)
        http = UrlConnectionHttpClient()
        ai = AiOrchestrator(http, {
            val s = settings.current
            if (s.privateMode) emptyList() else settings.orderedProviders()
        })
        executor = AndroidActionExecutor(this)
        brain = AssistantBrain(
            parser = IntentParser(listOf("jarvis")),
            executor = executor,
            ai = ai,
            routines = { routines.items },
            log = { if (settings.current.actionLogEnabled) actionLog.add(it) },
            userName = { settings.current.userName },
            extraInstructions = {
                if (settings.current.allowAiActions) "" else "N'utilise jamais la balise ACTION : réponds uniquement par du texte."
            },
        )
        Notifications.createChannels(this)
        session = SessionController(this)
        overlay = OverlayController(this)
        scheduler.rescheduleAll()
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
