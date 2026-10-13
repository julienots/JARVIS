package com.superessence.jarvis.actions

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.brain.ActionExecutor
import com.superessence.jarvis.core.brain.ActionResult
import com.superessence.jarvis.core.info.OpenMeteo
import com.superessence.jarvis.core.info.Wikipedia
import com.superessence.jarvis.core.info.firstSentences
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.intent.MediaCommand
import com.superessence.jarvis.core.intent.SettingsTarget
import com.superessence.jarvis.core.intent.VolumeCommand
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.text.Text
import com.superessence.jarvis.data.ReminderItem
import com.superessence.jarvis.service.JarvisNotificationListener
import com.superessence.jarvis.service.Notifications
import com.superessence.jarvis.ui.MainActivity
import com.superessence.jarvis.util.AppVisibility
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Exécute les intentions avec les API Android officielles et rapporte honnêtement le résultat.
 * Aucune action ne simule de geste à l'écran ; les envois et appels sont préparés, jamais déclenchés seuls.
 */
class AndroidActionExecutor(private val app: JarvisApp) : ActionExecutor {

    private val ctx: Context get() = app
    private val launcher = AppLauncher(app)
    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    enum class Launch { OK, NO_APP, BLOCKED }

    override fun execute(intent: JarvisIntent): ActionResult = when (intent) {
        JarvisIntent.Battery -> battery()
        is JarvisIntent.Timer -> timer(intent.seconds)
        JarvisIntent.CancelTimer -> cancelTimer()
        is JarvisIntent.Alarm -> alarm(intent.hour, intent.minute)
        is JarvisIntent.Reminder -> reminder(intent)
        is JarvisIntent.OpenApp -> openApp(intent.appName)
        is JarvisIntent.OpenSettings -> openSettings(intent.target)
        is JarvisIntent.Flashlight -> flashlight(intent.on)
        is JarvisIntent.Volume -> volume(intent.command, intent.percent)
        is JarvisIntent.Media -> media(intent.command)
        is JarvisIntent.PlayMusic -> playMusic(intent.query)
        is JarvisIntent.AddTask -> {
            app.tasks.add(intent.text)
            ActionResult(true, "C'est noté : ${intent.text}.", listOf(tasksCard()))
        }
        JarvisIntent.ListTasks -> listTasks()
        is JarvisIntent.CompleteTask -> {
            val t = app.tasks.completeMatching(intent.text)
            if (t == null) ActionResult(false, "Je ne trouve pas cette tâche dans votre liste.", listOf(tasksCard()))
            else ActionResult(true, "Bravo, « ${t.text} » est terminée.", listOf(tasksCard()))
        }
        JarvisIntent.ClearTasks -> { app.tasks.clear(); ActionResult(true, "Votre liste de tâches est vide.") }
        is JarvisIntent.Weather -> weather(intent.city)
        is JarvisIntent.WebSearch -> search(intent.query)
        is JarvisIntent.Call -> call(intent.contact)
        is JarvisIntent.SendMessage -> message(intent.contact, intent.message)
        is JarvisIntent.Navigate -> navigate(intent.destination)
        JarvisIntent.ReadNotifications -> readNotifications()
        else -> ActionResult(false, "Je ne sais pas encore faire cela sur ce téléphone.")
    }

    // ---------- Lancement d'écrans ----------

    private fun start(intent: Intent, label: String): Launch {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.packageManager) == null) return Launch.NO_APP
        if (!AppVisibility.canStartActivities(ctx)) {
            // Android interdit d'ouvrir un écran depuis l'arrière-plan : on passe par une notification.
            Notifications.postOpenRequest(ctx, label, intent)
            return Launch.BLOCKED
        }
        return try { ctx.startActivity(intent); Launch.OK } catch (_: ActivityNotFoundException) { Launch.NO_APP } catch (_: SecurityException) { Launch.NO_APP }
    }

    private fun blockedText(what: String) =
        "Android m'empêche d'ouvrir $what depuis l'arrière-plan. Touchez la notification, ou autorisez l'affichage par-dessus les autres applications."

    // ---------- Système ----------

    private fun battery(): ActionResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        if (pct <= 0) return ActionResult(false, "Je n'arrive pas à lire le niveau de batterie.")
        return ActionResult(true, "La batterie est à $pct %${if (charging) ", en charge" else ""}.",
            listOf(PanelCard.Info("Batterie", "$pct %${if (charging) " · en charge" else ""}", lifetimeMs = 6000)))
    }

    private fun timer(seconds: Int): ActionResult {
        val end = System.currentTimeMillis() + seconds * 1000L
        val card = PanelCard.Countdown("Minuteur ${formatDuration(seconds)}", end)
        val clock = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        return when (if (AppVisibility.canStartActivities(ctx)) start(clock, "Minuteur") else Launch.NO_APP) {
            Launch.OK -> ActionResult(true, "Minuteur de ${formatDuration(seconds)} lancé dans l'Horloge.", listOf(card))
            else -> {
                // Repli fiable : minuteur interne avec notification et alarme système.
                app.scheduler.scheduleTimer(end, "Minuteur ${formatDuration(seconds)}")
                ActionResult(true, "Minuteur de ${formatDuration(seconds)} lancé.", listOf(card))
            }
        }
    }

    private fun cancelTimer(): ActionResult {
        val own = app.scheduler.cancelTimer()
        app.session.let { s -> s.cards.value.filter { it.card is PanelCard.Countdown }.forEach { s.dismissCard(it.card.key) } }
        if (own) return ActionResult(true, "Minuteur annulé.")
        if (Build.VERSION.SDK_INT >= 28) {
            val r = start(Intent(AlarmClock.ACTION_DISMISS_TIMER), "Minuteur")
            if (r == Launch.OK) return ActionResult(true, "J'ai demandé à l'Horloge d'arrêter le minuteur.")
        }
        return ActionResult(false, "Je ne peux pas annuler le minuteur de l'Horloge directement. Ouvrez l'application Horloge.")
    }

    private fun alarm(hour: Int, minute: Int): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        val hm = "%d h %02d".format(hour, minute)
        return when (start(i, "Alarme")) {
            Launch.OK -> ActionResult(true, "Alarme réglée à $hm.")
            Launch.BLOCKED -> ActionResult(false, blockedText("l'Horloge"))
            Launch.NO_APP -> {
                val at = nextOccurrence(hour, minute)
                scheduleReminder("Alarme de $hm", at)
                ActionResult(true, "Pas d'application Horloge compatible : j'ai programmé un rappel à $hm à la place.")
            }
        }
    }

    private fun reminder(r: JarvisIntent.Reminder): ActionResult {
        val at: Long = when {
            r.delayMinutes != null -> System.currentTimeMillis() + r.delayMinutes!! * 60_000L
            r.hour != null -> nextOccurrence(r.hour!!, r.minute ?: 0)
            else -> {
                app.tasks.add(r.text)
                return ActionResult(true, "Je n'ai pas compris l'heure, alors j'ai ajouté « ${r.text} » à votre liste. Précisez par exemple : dans 10 minutes, ou à 18 heures.", listOf(tasksCard()))
            }
        }
        scheduleReminder(r.text, at)
        val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneId.systemDefault())
        val exactNote = if (app.scheduler.canExact()) "" else " L'heure peut varier de quelques minutes sans l'autorisation d'alarmes exactes."
        return ActionResult(true, "Je vous rappellerai « ${r.text} » à ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}.$exactNote",
            listOf(PanelCard.Info("Rappel programmé", "${r.text}\n${time.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", java.util.Locale.FRENCH))}")))
    }

    private fun scheduleReminder(text: String, at: Long) {
        val id = (System.currentTimeMillis() % 1_000_000).toInt()
        app.reminders.add(ReminderItem(id, text, at))
        app.scheduler.scheduleReminder(id, text, at)
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    // ---------- Applications et réglages ----------

    private fun openApp(name: String): ActionResult {
        val n = Text.normalize(name)
        val special: Intent? = when {
            n in setOf("appareil photo", "camera", "la camera", "l appareil photo") -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            n in setOf("telephone", "le telephone", "composeur") -> Intent(Intent.ACTION_DIAL)
            n in setOf("jarvis", "toi") -> Intent(ctx, MainActivity::class.java)
            n in setOf("reglages", "parametres", "les reglages", "les parametres") -> Intent(Settings.ACTION_SETTINGS)
            n in setOf("messages", "sms", "mes messages") -> Telephony.Sms.getDefaultSmsPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
            else -> null
        }
        if (special != null) return launchResult(start(special, name), name)
        val found = launcher.find(name) ?: return ActionResult(false, "Je ne trouve pas d'application nommée « $name » sur ce téléphone.")
        val i = launcher.launchIntent(found) ?: return ActionResult(false, "${found.label} ne peut pas être ouverte directement.")
        return launchResult(start(i, found.label), found.label)
    }

    private fun launchResult(r: Launch, label: String) = when (r) {
        Launch.OK -> ActionResult(true, "J'ouvre $label.")
        Launch.NO_APP -> ActionResult(false, "Je n'ai pas pu ouvrir $label.")
        Launch.BLOCKED -> ActionResult(false, blockedText(label))
    }

    private fun openSettings(target: SettingsTarget): ActionResult {
        val action = when (target) {
            SettingsTarget.WIFI -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            SettingsTarget.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsTarget.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsTarget.SOUND -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            SettingsTarget.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsTarget.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            SettingsTarget.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsTarget.MOBILE_DATA -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            SettingsTarget.NFC -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            SettingsTarget.NOTIFICATIONS -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            SettingsTarget.APPS -> Settings.ACTION_APPLICATION_SETTINGS
            SettingsTarget.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            SettingsTarget.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsTarget.DATE -> Settings.ACTION_DATE_SETTINGS
            SettingsTarget.SECURITY -> Settings.ACTION_SECURITY_SETTINGS
            SettingsTarget.GENERAL -> Settings.ACTION_SETTINGS
            SettingsTarget.JARVIS -> null
        }
        val intent = if (action == null) Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "settings")
        else Intent(action).apply { if (target == SettingsTarget.NOTIFICATIONS) putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName) }
        var r = start(intent, target.label)
        if (r == Launch.NO_APP && action != Settings.ACTION_SETTINGS) r = start(Intent(Settings.ACTION_SETTINGS), target.label)
        return when (r) {
            Launch.OK -> ActionResult(true, "J'ouvre les réglages ${target.label}. Android vous laisse faire le changement vous-même.")
            Launch.NO_APP -> ActionResult(false, "Ce réglage n'est pas accessible sur ce téléphone.")
            Launch.BLOCKED -> ActionResult(false, blockedText("les réglages"))
        }
    }

    // ---------- Matériel ----------

    private fun flashlight(on: Boolean): ActionResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null } ?: return ActionResult(false, "Ce téléphone n'a pas de lampe accessible.")
        return try {
            cm.setTorchMode(id, on)
            ActionResult(true, if (on) "Lampe allumée." else "Lampe éteinte.")
        } catch (e: Exception) {
            ActionResult(false, "La lampe est indisponible, peut-être utilisée par l'appareil photo.")
        }
    }

    private fun volume(cmd: VolumeCommand, percent: Int?): ActionResult {
        val a = audio
        val stream = AudioManager.STREAM_MUSIC
        val max = a.getStreamMaxVolume(stream)
        return try {
            when (cmd) {
                VolumeCommand.UP -> a.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                VolumeCommand.DOWN -> a.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                VolumeCommand.MUTE -> a.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                VolumeCommand.MAX -> a.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
                VolumeCommand.SET -> a.setStreamVolume(stream, ((percent ?: 50) / 100f * max).roundToInt(), AudioManager.FLAG_SHOW_UI)
            }
            val now = (a.getStreamVolume(stream) * 100f / max).roundToInt()
            ActionResult(true, if (cmd == VolumeCommand.MUTE) "Son coupé." else "Volume multimédia à $now %.")
        } catch (e: SecurityException) {
            ActionResult(false, "Android refuse de changer le volume en mode Ne pas déranger.")
        }
    }

    private fun media(cmd: MediaCommand): ActionResult {
        val key = when (cmd) {
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val a = audio
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        if (cmd == MediaCommand.PAUSE) {
            Thread.sleep(700)
            return if (a.isMusicActive) ActionResult(false, "J'ai envoyé la pause, mais le son semble continuer. L'application en cours ne l'accepte peut-être pas.")
            else ActionResult(true, "")
        }
        return ActionResult(true, when (cmd) {
            MediaCommand.NEXT -> "Piste suivante."
            MediaCommand.PREVIOUS -> "Piste précédente."
            else -> ""
        })
    }

    private fun playMusic(query: String): ActionResult {
        if (query.isBlank()) return media(MediaCommand.PLAY)
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        return when (start(i, "Musique")) {
            Launch.OK -> ActionResult(true, "Je lance $query.")
            Launch.BLOCKED -> ActionResult(false, blockedText("le lecteur de musique"))
            Launch.NO_APP -> {
                val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")))
                if (start(yt, "YouTube") == Launch.OK) ActionResult(true, "Je cherche $query sur YouTube.")
                else ActionResult(false, "Aucune application de musique ne répond.")
            }
        }
    }

    // ---------- Tâches ----------

    private fun tasksCard(): PanelCard.Tasks {
        val pending = app.tasks.pending()
        return PanelCard.Tasks("Prochaines étapes", pending.take(8).map { it.text }.ifEmpty { listOf("Aucune tâche en attente") })
    }

    private fun listTasks(): ActionResult {
        val p = app.tasks.pending()
        val reminders = app.reminders.upcoming().take(3)
        val speech = when {
            p.isEmpty() && reminders.isEmpty() -> "Votre liste est vide. Dites par exemple : ajoute acheter du pain à ma liste."
            p.isEmpty() -> "Aucune tâche, mais ${reminders.size} rappel${if (reminders.size > 1) "s" else ""} à venir."
            else -> "Vous avez ${p.size} tâche${if (p.size > 1) "s" else ""} : " + p.take(4).joinToString(", ") { it.text } + if (p.size > 4) ", et d'autres." else "."
        }
        return ActionResult(true, speech, listOf(tasksCard()))
    }

    // ---------- Internet ----------

    private fun offline() = app.settings.current.privateMode

    private fun weather(cityArg: String?): ActionResult {
        if (offline()) return ActionResult(false, "Mode privé actif : je ne consulte pas Internet.")
        val city = cityArg?.takeIf { it.isNotBlank() } ?: app.settings.current.defaultCity
        if (city.isBlank()) return ActionResult(false, "Pour quelle ville ? Dites par exemple « météo à Paris », ou choisissez une ville par défaut dans les réglages.")
        return try {
            val geo = app.http.get(OpenMeteo.geocodeUrl(city))
            val place = (if (geo.code == 200) OpenMeteo.parsePlace(geo.body) else null)
                ?: return ActionResult(false, "Je ne trouve pas la ville $city.")
            val f = app.http.get(OpenMeteo.forecastUrl(place.lat, place.lon))
            if (f.code != 200) return ActionResult(false, "Le service météo ne répond pas pour le moment.")
            val fc = OpenMeteo.parseForecast(f.body)
            val desc = OpenMeteo.describe(fc.code)
            val minmax = if (fc.minC != null && fc.maxC != null) " Entre ${fc.minC!!.roundToInt()} et ${fc.maxC!!.roundToInt()} degrés aujourd'hui." else ""
            ActionResult(true, "À ${place.name}, il fait ${fc.tempC.roundToInt()} degrés, $desc.$minmax",
                listOf(PanelCard.Weather(place.name, fc.tempC, desc, fc.minC, fc.maxC, fc.windKmh, "Open-Meteo")))
        } catch (e: Exception) {
            ActionResult(false, "Impossible de récupérer la météo : pas de connexion Internet.")
        }
    }

    private fun search(query: String): ActionResult {
        if (offline()) return ActionResult(false, "Mode privé actif : je ne fais pas de recherche en ligne.")
        return try {
            val s = app.http.get(Wikipedia.searchUrl(query))
            val title = if (s.code == 200) Wikipedia.parseSearchTitle(s.body) else null
            val summary = title?.let { t -> app.http.get(Wikipedia.summaryUrl(t)).takeIf { it.code == 200 }?.let { Wikipedia.parseSummary(it.body) } }
            if (summary != null) {
                ActionResult(true, "D'après Wikipédia : " + firstSentences(summary.extract),
                    listOf(PanelCard.Info(summary.title, firstSentences(summary.extract, 4), summary.url, lifetimeMs = 25_000)))
            } else openWebSearch(query)
        } catch (e: Exception) {
            ActionResult(false, "Recherche impossible : pas de connexion Internet.")
        }
    }

    private fun openWebSearch(query: String): ActionResult {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        return when (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Recherche")) {
            Launch.OK -> ActionResult(true, "Je n'ai pas de résumé fiable, j'ouvre la recherche pour « $query ».")
            Launch.BLOCKED -> ActionResult(false, blockedText("le navigateur"))
            Launch.NO_APP -> ActionResult(false, "Aucun navigateur n'est installé.")
        }
    }

    private fun navigate(dest: String): ActionResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        return when (start(i, "Itinéraire")) {
            Launch.OK -> ActionResult(true, "J'ouvre l'itinéraire vers $dest.")
            Launch.BLOCKED -> ActionResult(false, blockedText("la carte"))
            Launch.NO_APP -> ActionResult(false, "Aucune application de cartes n'est installée.")
        }
    }

    // ---------- Communication (préparée, jamais envoyée automatiquement) ----------

    private fun lookupNumber(name: String): Pair<String, String>? {
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6) return name to digits
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val target = Text.normalize(name)
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        ) ?: return null
        var best: Pair<String, String>? = null
        var bestScore = 0.0
        c.use {
            while (it.moveToNext()) {
                val display = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val n = Text.normalize(display)
                val score = when {
                    n == target -> 1.0
                    n.split(' ').contains(target) -> 0.9
                    else -> Text.similarity(n, target)
                }
                if (score > bestScore) { bestScore = score; best = display to number }
            }
        }
        return best.takeIf { bestScore >= 0.75 }
    }

    private fun call(contact: String): ActionResult {
        val found = lookupNumber(contact)
        val uri = Uri.parse("tel:" + (found?.second ?: ""))
        val who = found?.first ?: contact
        return when (start(Intent(Intent.ACTION_DIAL, uri), "Appel")) {
            Launch.OK -> if (found != null) ActionResult(true, "Le numéro de $who est prêt. Appuyez sur le bouton d'appel pour lancer l'appel.")
            else ActionResult(true, "J'ouvre le téléphone. Je n'ai pas trouvé $contact dans vos contacts${if (!hasContacts()) " car l'accès aux contacts n'est pas autorisé" else ""}.")
            Launch.BLOCKED -> ActionResult(false, blockedText("le téléphone"))
            Launch.NO_APP -> ActionResult(false, "Ce téléphone ne peut pas passer d'appels.")
        }
    }

    private fun message(contact: String, body: String): ActionResult {
        val found = lookupNumber(contact)
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (found?.second ?: ""))).putExtra("sms_body", body)
        return when (start(i, "Message")) {
            Launch.OK -> ActionResult(true, "Le message est prêt${if (found != null) " pour ${found.first}" else ""}. Vérifiez-le, puis appuyez sur Envoyer.")
            Launch.BLOCKED -> ActionResult(false, blockedText("la messagerie"))
            Launch.NO_APP -> ActionResult(false, "Aucune application de SMS n'est disponible.")
        }
    }

    private fun hasContacts() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun readNotifications(): ActionResult {
        if (!JarvisNotificationListener.isEnabled(ctx)) {
            start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Accès aux notifications")
            return ActionResult(false, "Pour lire vos notifications, activez Jarvis dans l'accès aux notifications. J'ouvre le réglage.")
        }
        val list = JarvisNotificationListener.recent()
        if (list.isEmpty()) return ActionResult(true, "Aucune nouvelle notification.")
        val speech = "Vous avez ${list.size} notification${if (list.size > 1) "s" else ""}. " +
            list.take(4).joinToString(" ") { "${it.app} : ${it.title}${if (it.text.isNotBlank()) ", ${it.text.take(80)}" else ""}." }
        return ActionResult(true, speech, listOf(PanelCard.Info("Notifications", list.take(6).joinToString("\n") { "${it.app} · ${it.title}" })))
    }

    companion object {
        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it heure${if (it > 1) "s" else ""}" },
                m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
                s.takeIf { it > 0 }?.let { "$it seconde${if (it > 1) "s" else ""}" },
            ).joinToString(" ")
        }
    }
}
