package com.superessence.jarvis.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ContentUris
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import android.telephony.SmsManager
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
import com.superessence.jarvis.core.intent.BrightnessCommand
import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.intent.SystemAction
import com.superessence.jarvis.core.intent.MediaCommand
import com.superessence.jarvis.core.intent.SettingsTarget
import com.superessence.jarvis.core.intent.VolumeCommand
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.core.panel.ResultItem
import com.superessence.jarvis.core.text.Text
import com.superessence.jarvis.data.ReminderItem
import com.superessence.jarvis.service.JarvisAccessibilityService
import com.superessence.jarvis.service.JarvisNotificationListener
import com.superessence.jarvis.service.Notifications
import com.superessence.jarvis.ui.MainActivity
import com.superessence.jarvis.util.AppVisibility
import com.superessence.jarvis.util.Perms
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Exécute les intentions avec les API Android officielles et rapporte honnêtement le résultat.
 * Les recherches, l'agenda, la luminosité ou la lecture d'écran se font en arrière-plan : l'application
 * en cours reste à l'écran et le résultat s'affiche dans le mini-écran flottant.
 * Les appels et SMS ne partent qu'après votre « oui » (et directement si vous l'avez autorisé).
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
        is JarvisIntent.PhoneSearch -> phoneSearch(intent.query)
        is JarvisIntent.Agenda -> agenda(intent.dayOffset)
        is JarvisIntent.Brightness -> brightness(intent.command, intent.percent)
        is JarvisIntent.DoNotDisturb -> doNotDisturb(intent.on)
        is JarvisIntent.System -> systemAction(intent.action)
        is JarvisIntent.ReadScreen -> readScreen(intent.summarize)
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
        val here = if (city.isBlank()) lastLocation() else null
        if (city.isBlank() && here == null) return ActionResult(false, "Pour quelle ville ? Dites par exemple « météo à Paris », choisissez une ville par défaut dans les réglages, ou autorisez la localisation.")
        return try {
            val place = if (here != null) OpenMeteo.Place("votre position", here.latitude, here.longitude) else {
                val geo = app.http.get(OpenMeteo.geocodeUrl(city))
                (if (geo.code == 200) OpenMeteo.parsePlace(geo.body) else null)
                    ?: return ActionResult(false, "Je ne trouve pas la ville $city.")
            }
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
            } else if (app.ai.hasUsableProvider()) {
                // Pas de fiche Wikipédia : l'IA répond, sans ouvrir de navigateur par-dessus votre application.
                ActionResult(true, "", aiFollowUp = query)
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
        if (found != null && Perms.callPhone(ctx)) {
            when (start(Intent(Intent.ACTION_CALL, uri), "Appel")) {
                Launch.OK -> return ActionResult(true, "J'appelle $who.")
                Launch.BLOCKED -> return ActionResult(false, blockedText("le téléphone"))
                Launch.NO_APP -> {}
            }
        }
        return when (start(Intent(Intent.ACTION_DIAL, uri), "Appel")) {
            Launch.OK -> if (found != null) ActionResult(true, "Le numéro de $who est prêt. Appuyez sur le bouton d'appel pour lancer l'appel.")
            else ActionResult(true, "J'ouvre le téléphone. Je n'ai pas trouvé $contact dans vos contacts${if (!hasContacts()) " car l'accès aux contacts n'est pas autorisé" else ""}.")
            Launch.BLOCKED -> ActionResult(false, blockedText("le téléphone"))
            Launch.NO_APP -> ActionResult(false, "Ce téléphone ne peut pas passer d'appels.")
        }
    }

    private fun message(contact: String, body: String): ActionResult {
        val found = lookupNumber(contact)
        if (found != null && body.isNotBlank() && Perms.sendSms(ctx)) {
            try {
                @Suppress("DEPRECATION")
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendMultipartTextMessage(found.second, null, sms.divideMessage(body), null, null)
                return ActionResult(true, "Message envoyé à ${found.first}.", listOf(PanelCard.Info("SMS envoyé · ${found.first}", body, lifetimeMs = 8_000)))
            } catch (_: Exception) { /* repli : la messagerie s'ouvre avec le message prêt */ }
        }
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

    // ---------- Téléphone : recherche, agenda, système (sans quitter l'application en cours) ----------

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (!Perms.location(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } } catch (_: Exception) { null }
    }

    private fun phoneSearch(query: String): ActionResult {
        val q = query.trim()
        if (q.isBlank()) return ActionResult(false, "Que voulez-vous que je cherche dans le téléphone ?")
        val nq = Text.normalize(q)
        val apps = launcher.apps().filter { it.normalized.contains(nq) || Text.similarity(it.normalized, nq) >= 0.8 }.take(4)
            .map { ResultItem(it.label, "Application", "app:${it.packageName}") }
        val contacts = if (Perms.contacts(ctx)) searchContacts(q) else emptyList()
        val files = if (Perms.media(ctx)) searchFiles(q) else emptyList()
        val events = if (Perms.calendar(ctx)) searchEvents(q) else emptyList()
        val all = apps + contacts + files + events
        val missing = listOfNotNull(
            "contacts".takeIf { !Perms.contacts(ctx) }, "fichiers".takeIf { !Perms.media(ctx) }, "agenda".takeIf { !Perms.calendar(ctx) },
        )
        val note = if (missing.isEmpty()) "" else " Pour chercher aussi dans vos ${missing.joinToString(", ")}, autorisez-les dans Jarvis, Réglages, Autorisations."
        if (all.isEmpty()) return ActionResult(false, "Je n'ai rien trouvé pour « $q » dans le téléphone.$note")
        val parts = listOfNotNull(
            plural(apps.size, "application"), plural(contacts.size, "contact"), plural(files.size, "fichier"), plural(events.size, "événement"),
        )
        return ActionResult(true, "Pour « $q », j'ai trouvé ${parts.joinToString(", ")}. Touchez un résultat pour l'ouvrir.$note",
            listOf(PanelCard.Results("Dans le téléphone · $q", all)))
    }

    private fun plural(n: Int, word: String) = if (n == 0) null else "$n $word${if (n > 1) "s" else ""}"

    private fun searchContacts(q: String): List<ResultItem> = try {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext() && size < 5) {
                    val lookup = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    add(ResultItem(c.getString(2) ?: "Contact", "Contact", lookup?.toString()))
                }
            }
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun searchFiles(q: String): List<ResultItem> = try {
        val files = MediaStore.Files.getContentUri("external")
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        ctx.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, type),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < 6) {
                    val id = c.getLong(0)
                    val (base, kind) = when (c.getInt(2)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Photo"
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Vidéo"
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Audio"
                        else -> files to "Fichier"
                    }
                    add(ResultItem(c.getString(1) ?: kind, kind, ContentUris.withAppendedId(base, id).toString()))
                }
            }
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun searchEvents(q: String): List<ResultItem> {
        val now = System.currentTimeMillis()
        return queryInstances(now - 86_400_000L, now + 90 * 86_400_000L, "${CalendarContract.Instances.TITLE} LIKE ?", arrayOf("%$q%"))
            .take(4).map { it.copy(detail = "Agenda · " + it.detail) }
    }

    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", java.util.Locale.FRENCH)

    private fun queryInstances(begin: Long, end: Long, selection: String?, args: Array<String>?): List<ResultItem> = try {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            selection, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < 12) {
                    val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(2)), ZoneId.systemDefault())
                    val whenTxt = if (c.getInt(3) == 1) "Toute la journée" else at.format(dayFormat)
                    val place = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                    add(ResultItem(c.getString(1) ?: "Événement", whenTxt + place,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.getLong(0)).toString()))
                }
            }
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun agenda(dayOffset: Int): ActionResult {
        val label = if (dayOffset == 1) "demain" else "aujourd'hui"
        if (!Perms.calendar(ctx)) return ActionResult(false, "Pour lire votre agenda, autorisez l'accès au calendrier dans Jarvis, Réglages, Autorisations.")
        val day = java.time.LocalDate.now().plusDays(dayOffset.toLong())
        val zone = ZoneId.systemDefault()
        val events = queryInstances(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), null, null)
        if (events.isEmpty()) return ActionResult(true, "Rien de prévu $label dans votre agenda.")
        val speech = "Vous avez ${events.size} événement${if (events.size > 1) "s" else ""} $label : " +
            events.take(4).joinToString(", ") { e -> e.label + (e.detail.substringAfter("à ", "").substringBefore(" ·").takeIf { it.isNotBlank() }?.let { " à $it" } ?: "") } + "."
        return ActionResult(true, speech, listOf(PanelCard.Results("Agenda · $label", events)))
    }

    private fun brightness(cmd: BrightnessCommand, percent: Int?): ActionResult {
        if (!Perms.writeSettings(ctx)) {
            return ActionResult(false, "Pour régler la luminosité, autorisez « Modifier les paramètres système » dans Jarvis, Réglages, Autorisations.")
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (cmd) {
            BrightnessCommand.UP -> cur + 51
            BrightnessCommand.DOWN -> cur - 51
            BrightnessCommand.MAX -> 255
            BrightnessCommand.MIN -> 5
            BrightnessCommand.SET -> ((percent ?: 50) / 100f * 255).roundToInt()
        }.coerceIn(5, 255)
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            ActionResult(true, "Luminosité à ${(target * 100f / 255).roundToInt()} %.")
        } catch (_: Exception) {
            ActionResult(false, "Android a refusé de changer la luminosité.")
        }
    }

    private fun doNotDisturb(on: Boolean): ActionResult {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult(false, "Pour gérer le mode Ne pas déranger, autorisez l'accès « Ne pas déranger » dans Jarvis, Réglages, Autorisations.")
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ActionResult(true, if (on) "Mode Ne pas déranger activé." else "Mode Ne pas déranger désactivé.")
    }

    private fun accessibilityMissing() = ActionResult(false,
        "Pour cela, activez le service d'accessibilité de Jarvis : Jarvis, Réglages, Autorisations, Accessibilité.")

    private fun systemAction(action: SystemAction): ActionResult {
        val svc = JarvisAccessibilityService.instance ?: return accessibilityMissing()
        return if (svc.perform(action)) ActionResult(true, when (action) {
            SystemAction.SCREENSHOT -> "Capture d'écran faite."
            SystemAction.LOCK_SCREEN -> "Écran verrouillé."
            else -> ""
        }) else ActionResult(false, "${action.label} : impossible sur cette version d'Android.")
    }

    private fun readScreen(summarize: Boolean): ActionResult {
        val svc = JarvisAccessibilityService.instance ?: return accessibilityMissing()
        val screen = svc.readScreen() ?: return ActionResult(false, "Je n'arrive pas à lire l'écran pour le moment.")
        if (screen.text.isBlank()) return ActionResult(false, "Je ne vois pas de texte sur l'écran de ${screen.app}.")
        val card = PanelCard.Info("À l'écran · ${screen.app}", screen.text.take(700), lifetimeMs = 30_000)
        if (!summarize) return ActionResult(true, "Sur ${screen.app} : " + screen.text.replace('\n', ' ').take(500), listOf(card))
        if (offline()) return ActionResult(true, "Mode privé : je ne l'envoie pas à l'IA. Voici le début : " + screen.text.replace('\n', ' ').take(300), listOf(card))
        return ActionResult(true, "Voici le début : " + screen.text.replace('\n', ' ').take(300), listOf(card),
            aiFollowUp = "Voici le texte affiché à l'écran de mon téléphone, dans l'application ${screen.app}. " +
                "Résume-le en deux ou trois phrases, en français, sans balise ACTION :\n\n${screen.text}")
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
