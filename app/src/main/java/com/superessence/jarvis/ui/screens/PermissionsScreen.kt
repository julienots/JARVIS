package com.superessence.jarvis.ui.screens

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.AlarmClock
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.R
import com.superessence.jarvis.service.JarvisService
import com.superessence.jarvis.service.JarvisTileService
import com.superessence.jarvis.ui.MainActivity
import com.superessence.jarvis.ui.components.Hint
import com.superessence.jarvis.ui.components.Section
import com.superessence.jarvis.ui.components.Status
import com.superessence.jarvis.ui.components.StatusRow
import com.superessence.jarvis.util.Perms

/** Guide des autorisations : chaque ligne explique à quoi elle sert et ouvre le bon écran. */
@Composable
fun PermissionsScreen(activity: MainActivity, app: JarvisApp, onBack: () -> Unit) {
    val tick by activity.resumeTick
    val refresh = remember { mutableIntStateOf(0) }
    remember(tick, refresh.intValue) { 0 } // relit les autorisations au retour dans l'appli
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh.intValue++
        if (!granted) Perms.openAppSettings(activity) // refus définitif : on ouvre les réglages
    }
    fun st(ok: Boolean, required: Boolean = true) = if (ok) Status.OK else if (required) Status.NEEDED else Status.OFF

    ScrollScreen("Autorisations", onBack) {
        Hint("Chaque autorisation est facultative : Jarvis reste utilisable sans elles, avec moins de fonctions. Vous pouvez les retirer à tout moment.")
        Section("Essentielles") {
            StatusRow("Micro", "Pour vous entendre. Utilisé uniquement pendant l'écoute.", st(Perms.mic(activity)),
                if (Perms.mic(activity)) null else "Autoriser") { req.launch(Manifest.permission.RECORD_AUDIO) }
            StatusRow("Notifications", "Rappels, minuteurs et indicateur d'écoute.", st(Perms.notifications(activity)),
                if (Perms.notifications(activity) || Build.VERSION.SDK_INT < 33) null else "Autoriser") { req.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        Section("Recommandées") {
            StatusRow("Affichage par-dessus les applications",
                "Bulle flottante, et ouverture d'applications à la voix quand Jarvis est en arrière-plan.",
                st(Perms.overlay(activity)), if (Perms.overlay(activity)) null else "Ouvrir") { Perms.openOverlaySettings(activity) }
            StatusRow("Alarmes exactes", "Rappels et routines à la minute près (sinon Android peut les décaler).",
                st(Perms.exactAlarms(activity)), if (Perms.exactAlarms(activity)) null else "Ouvrir") { Perms.openExactAlarmSettings(activity) }
            StatusRow("Batterie sans restriction", "Évite que l'écoute du mot « Jarvis » soit coupée.",
                st(Perms.batteryUnrestricted(activity)), if (Perms.batteryUnrestricted(activity)) null else "Ouvrir") { Perms.requestBatteryUnrestricted(activity) }
        }
        Section("Facultatives") {
            StatusRow("Contacts", "Trouver le numéro pour « appelle Paul ». Lecture seule, rien n'est envoyé.",
                st(Perms.contacts(activity), false), if (Perms.contacts(activity)) null else "Autoriser") { req.launch(Manifest.permission.READ_CONTACTS) }
            StatusRow("Accès aux notifications", "Pour « lis mes notifications ». Gardées en mémoire uniquement ; banques et codes ignorés.",
                st(Perms.notificationAccess(activity), false), if (Perms.notificationAccess(activity)) "Gérer" else "Activer") { Perms.openNotificationAccess(activity) }
            StatusRow("Assistant par défaut", "Appui long sur Accueil pour parler à Jarvis.", Status.OFF, "Choisir") { Perms.openDefaultAssistant(activity) }
            if (Build.VERSION.SDK_INT >= 33) {
                StatusRow("Tuile Réglages rapides", "Un bouton Jarvis dans le volet de notifications.", Status.OFF, "Ajouter") { addTile(activity) }
            } else {
                StatusRow("Tuile Réglages rapides", "Déroulez le volet, touchez le crayon et ajoutez « Jarvis ».", Status.OFF)
            }
        }
        Section("Non utilisé volontairement") {
            Hint("Service d'accessibilité : Jarvis n'en a pas besoin et ne le demande pas. Il ne lit pas le contenu de vos écrans, " +
                "ne clique pas à votre place et ne peut pas contourner un code, une empreinte ou une validation bancaire.")
        }
    }
}

private fun addTile(ctx: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = ctx.getSystemService(StatusBarManager::class.java)
    sbm.requestAddTileService(
        ComponentName(ctx, JarvisTileService::class.java), "Jarvis",
        Icon.createWithResource(ctx, R.drawable.ic_stat_jarvis), ctx.mainExecutor,
    ) { }
}

/** Écran de diagnostic : ce qui fonctionne, ce qui demande une autorisation, ce qui est indisponible. */
@Composable
fun DiagnosticsScreen(activity: MainActivity, app: JarvisApp, onBack: () -> Unit) {
    val tick by activity.resumeTick
    remember(tick) { 0 } // relit les autorisations au retour dans l'appli
    val ctx = LocalContext.current
    val running by JarvisService.running.collectAsState()
    val providers by app.settings.providers.collectAsState()
    val pm = ctx.packageManager
    val online = remember(tick) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    val torch = remember {
        runCatching {
            val cm = ctx.getSystemService(CameraManager::class.java)
            cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrDefault(false)
    }
    ScrollScreen("Diagnostic", onBack) {
        Section("Voix") {
            StatusRow("Micro", if (Perms.mic(ctx)) "Autorisé" else "Non autorisé", if (Perms.mic(ctx)) Status.OK else Status.NEEDED)
            val rec = SpeechRecognizer.isRecognitionAvailable(ctx)
            StatusRow("Reconnaissance vocale Android", if (rec) "Disponible" else "Aucun service installé (installez l'application Google)", if (rec) Status.OK else Status.UNAVAILABLE)
            if (Build.VERSION.SDK_INT >= 31) {
                val od = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                StatusRow("Reconnaissance sur l'appareil", if (od) "Disponible" else "Non proposée par ce téléphone", if (od) Status.OK else Status.OFF)
            }
            StatusRow("Modèle hors ligne Vosk", if (app.vosk.isInstalled()) "Installé" else "Non installé (Réglages → Voix)", if (app.vosk.isInstalled()) Status.OK else Status.OFF)
            val tts = app.session.tts
            StatusRow("Synthèse vocale française", when { tts.isAvailable -> "Prête"; tts.isBroken -> "Voix française absente"; else -> "Initialisation…" },
                when { tts.isAvailable -> Status.OK; tts.isBroken -> Status.UNAVAILABLE; else -> Status.OFF })
            StatusRow("Écoute du mot de réveil", if (running) "Service actif" else "Arrêtée", if (running) Status.OK else Status.OFF)
        }
        Section("Interface et actions") {
            StatusRow("Bulle flottante", if (Perms.overlay(ctx)) "Autorisée" else "Autorisation requise", if (Perms.overlay(ctx)) Status.OK else Status.NEEDED)
            StatusRow("Notifications", if (Perms.notifications(ctx)) "Autorisées" else "Refusées", if (Perms.notifications(ctx)) Status.OK else Status.NEEDED)
            StatusRow("Alarmes exactes", if (Perms.exactAlarms(ctx)) "Autorisées" else "Rappels possiblement décalés", if (Perms.exactAlarms(ctx)) Status.OK else Status.NEEDED)
            val clock = Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(pm) != null
            StatusRow("Horloge compatible", if (clock) "Minuteurs et alarmes via l'Horloge" else "Absente : minuteur interne utilisé", if (clock) Status.OK else Status.OFF)
            StatusRow("Lampe torche", if (torch) "Disponible" else "Absente", if (torch) Status.OK else Status.UNAVAILABLE)
            StatusRow("Contacts", if (Perms.contacts(ctx)) "Autorisés" else "Non autorisés (facultatif)", if (Perms.contacts(ctx)) Status.OK else Status.OFF)
            StatusRow("Lecture des notifications", if (Perms.notificationAccess(ctx)) "Activée" else "Désactivée (facultatif)", if (Perms.notificationAccess(ctx)) Status.OK else Status.OFF)
            val gl = remember { (ctx.getSystemService(android.app.ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion shr 16) }
            StatusRow("OpenGL ES (sphère 3D)", "Version $gl.x", if (gl >= 2) Status.OK else Status.UNAVAILABLE)
        }
        Section("Réseau et IA") {
            StatusRow("Internet", if (online) "Connecté" else "Hors ligne : seules les commandes locales fonctionnent", if (online) Status.OK else Status.UNAVAILABLE)
            val usable = providers.filter { it.isUsable }
            StatusRow("IA configurée", if (usable.isEmpty()) "Aucune" else usable.joinToString { it.type.displayName }, if (usable.isEmpty()) Status.NEEDED else Status.OK)
            StatusRow("Mode privé", if (app.settings.current.privateMode) "Actif : micro et réseau coupés" else "Inactif", if (app.settings.current.privateMode) Status.OFF else Status.OK)
        }
        Section("Appareil") {
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Hint("Écran de diagnostic local : aucune information n'est envoyée.")
            OutlinedButton(onClick = { Perms.openAppSettings(ctx) }) { Text("Réglages Android de Jarvis") }
        }
    }
}

