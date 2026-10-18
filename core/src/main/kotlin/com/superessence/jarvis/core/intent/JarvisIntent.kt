package com.superessence.jarvis.core.intent

/** Écrans de réglages Android que Jarvis sait ouvrir. */
enum class SettingsTarget(val label: String) {
    WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), DISPLAY("Affichage"), SOUND("Son"), LOCATION("Localisation"),
    BATTERY("Batterie"), AIRPLANE("Mode avion"), MOBILE_DATA("Données mobiles"), NFC("NFC"),
    NOTIFICATIONS("Notifications"), APPS("Applications"), STORAGE("Stockage"), ACCESSIBILITY("Accessibilité"),
    DATE("Date et heure"), SECURITY("Sécurité"), GENERAL("Paramètres"), JARVIS("Paramètres de Jarvis"),
}

enum class MediaCommand { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }
enum class VolumeCommand { UP, DOWN, MUTE, SET, MAX }
enum class BrightnessCommand { UP, DOWN, SET, MAX, MIN }

/** Gestes système réalisés par le service d'accessibilité de Jarvis. */
enum class SystemAction(val label: String) {
    BACK("Retour"), HOME("Accueil"), RECENTS("Applications récentes"), NOTIFICATIONS("Volet des notifications"),
    QUICK_SETTINGS("Réglages rapides"), SCREENSHOT("Capture d'écran"), LOCK_SCREEN("Verrouillage de l'écran"),
}

/** Intention reconnue dans une phrase de l'utilisateur. */
sealed class JarvisIntent {
    /** Nom technique stable, utilisé par le journal et le protocole d'actions IA. */
    abstract val id: String

    data object Time : JarvisIntent() { override val id = "time" }
    data object Date : JarvisIntent() { override val id = "date" }
    data object Battery : JarvisIntent() { override val id = "battery" }
    data class Timer(val seconds: Int, val label: String? = null) : JarvisIntent() { override val id = "timer" }
    data object CancelTimer : JarvisIntent() { override val id = "cancel_timer" }
    data class Alarm(val hour: Int, val minute: Int) : JarvisIntent() { override val id = "alarm" }
    data class Reminder(val text: String, val delayMinutes: Int?, val hour: Int?, val minute: Int?) : JarvisIntent() {
        override val id = "reminder"
    }
    data class OpenApp(val appName: String) : JarvisIntent() { override val id = "open_app" }
    data class OpenSettings(val target: SettingsTarget) : JarvisIntent() { override val id = "open_settings" }
    data class Flashlight(val on: Boolean) : JarvisIntent() { override val id = "flashlight" }
    data class Volume(val command: VolumeCommand, val percent: Int? = null) : JarvisIntent() { override val id = "volume" }
    data class Media(val command: MediaCommand) : JarvisIntent() { override val id = "media" }
    data class AddTask(val text: String) : JarvisIntent() { override val id = "add_task" }
    data object ListTasks : JarvisIntent() { override val id = "list_tasks" }
    data class CompleteTask(val text: String) : JarvisIntent() { override val id = "complete_task" }
    data object ClearTasks : JarvisIntent() { override val id = "clear_tasks" }
    data class Weather(val city: String?) : JarvisIntent() { override val id = "weather" }
    data class WebSearch(val query: String) : JarvisIntent() { override val id = "web_search" }
    data class Call(val contact: String) : JarvisIntent() { override val id = "call" }
    data class SendMessage(val contact: String, val message: String) : JarvisIntent() { override val id = "send_message" }
    data class Navigate(val destination: String) : JarvisIntent() { override val id = "navigate" }
    data class PlayMusic(val query: String) : JarvisIntent() { override val id = "play_music" }
    data object ReadNotifications : JarvisIntent() { override val id = "read_notifications" }
    /** Recherche dans le téléphone (applications, contacts, fichiers, agenda) sans quitter l'application en cours. */
    data class PhoneSearch(val query: String) : JarvisIntent() { override val id = "phone_search" }
    /** Événements de l'agenda : 0 = aujourd'hui, 1 = demain. */
    data class Agenda(val dayOffset: Int) : JarvisIntent() { override val id = "agenda" }
    data class Brightness(val command: BrightnessCommand, val percent: Int? = null) : JarvisIntent() { override val id = "brightness" }
    data class DoNotDisturb(val on: Boolean) : JarvisIntent() { override val id = "dnd" }
    data class System(val action: SystemAction) : JarvisIntent() { override val id = "system" }
    /** Lit le texte affiché à l'écran ; [summarize] le fait résumer par l'IA. */
    data class ReadScreen(val summarize: Boolean) : JarvisIntent() { override val id = "read_screen" }
    data object Summary : JarvisIntent() { override val id = "summary" }
    data class RunRoutine(val name: String) : JarvisIntent() { override val id = "run_routine" }
    data object ClosePanel : JarvisIntent() { override val id = "close_panel" }
    data object HideOverlay : JarvisIntent() { override val id = "hide_overlay" }
    data object ShowOverlay : JarvisIntent() { override val id = "show_overlay" }
    data object StopSession : JarvisIntent() { override val id = "stop_session" }
    data object StopListening : JarvisIntent() { override val id = "stop_listening" }
    data object PrivateMode : JarvisIntent() { override val id = "private_mode" }
    data object Help : JarvisIntent() { override val id = "help" }
    data object Greeting : JarvisIntent() { override val id = "greeting" }
    data object Thanks : JarvisIntent() { override val id = "thanks" }
    data object Repeat : JarvisIntent() { override val id = "repeat" }

    /** Demande explicitement refusée (paiement, virement, suppression de données…). */
    data class Forbidden(val reason: String) : JarvisIntent() { override val id = "forbidden" }

    /** Rien de local ne correspond : la phrase part à l'IA si elle est configurée. */
    data class Unknown(val text: String) : JarvisIntent() { override val id = "unknown" }

    /** Transcription vide ou inaudible. */
    data object Empty : JarvisIntent() { override val id = "empty" }
}
