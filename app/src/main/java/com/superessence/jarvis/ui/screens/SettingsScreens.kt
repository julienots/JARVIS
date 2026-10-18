package com.superessence.jarvis.ui.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.BuildConfig
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.R
import com.superessence.jarvis.core.ai.AiException
import com.superessence.jarvis.core.ai.AiProviderConfig
import com.superessence.jarvis.core.ai.AiProviderType
import com.superessence.jarvis.core.ai.ChatMessage
import com.superessence.jarvis.core.ai.Role
import com.superessence.jarvis.data.SphereStyle
import com.superessence.jarvis.data.SttEngineChoice
import com.superessence.jarvis.data.WakeEngineChoice
import com.superessence.jarvis.service.JarvisService
import com.superessence.jarvis.ui.MainActivity
import com.superessence.jarvis.ui.components.Hint
import com.superessence.jarvis.ui.components.ScreenBar
import com.superessence.jarvis.ui.components.Section
import com.superessence.jarvis.ui.components.SwitchRow
import com.superessence.jarvis.ui.components.clickableNoRipple
import com.superessence.jarvis.ui.components.glass
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.util.Perms
import com.superessence.jarvis.voice.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun ScrollScreen(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenBar(title, onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsHub(open: (String) -> Unit) {
    ScrollScreen("Réglages", null) {
        listOf(
            Triple("ai", "Intelligence artificielle", "Gemini, Claude, ChatGPT, Groq, Mistral, serveur local"),
            Triple("voice", "Voix et mot de réveil", "Écoute « Jarvis », moteur hors ligne, voix, débit"),
            Triple("appearance", "Apparence et bulle", "Sphère, bulle flottante, économie d'énergie"),
            Triple("permissions", "Autorisations", "Guide pas à pas"),
            Triple("privacy", "Confidentialité et micro", "Mode privé, historique, données"),
            Triple("log", "Journal des actions", "Ce que Jarvis a exécuté"),
            Triple("battery", "Batterie et arrière-plan", "Fonctionnement fiable en arrière-plan"),
            Triple("diagnostics", "Diagnostic", "Ce qui marche sur ce téléphone"),
            Triple("about", "À propos", "SuperEssence"),
        ).forEach { (key, title, sub) ->
            Row(Modifier.fillMaxWidth().glass().clickableNoRipple { open(key) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = JColors.Text, fontSize = 16.sp)
                    Text(sub, color = JColors.TextDim, fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JColors.Cyan)
            }
        }
    }
}

// ---------------- IA ----------------

@Composable
fun AiSettingsScreen(app: JarvisApp, onBack: () -> Unit) {
    val providers by app.settings.providers.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val ordered = settings.providerOrder.mapNotNull { t -> providers.firstOrNull { it.type == t } }
    ScrollScreen("Intelligence artificielle", onBack) {
        Hint("Jarvis fonctionne sans IA pour les commandes du téléphone. Une IA permet de répondre à toutes les questions. " +
            "Activez un ou plusieurs fournisseurs : s'il y a une erreur (quota, réseau), Jarvis passe automatiquement au suivant dans l'ordre ci-dessous. " +
            "Vos clés sont chiffrées sur le téléphone et envoyées uniquement au fournisseur concerné. " +
            "Les demandes envoyées à l'IA quittent le téléphone : n'y dictez pas d'informations sensibles.")
        SwitchRow("L'IA peut proposer des actions", "Ouvrir une appli, lancer un minuteur… Les actions sensibles restent confirmées.",
            settings.allowAiActions) { v -> app.settings.update { it.copy(allowAiActions = v) } }
        ordered.forEachIndexed { i, cfg ->
            ProviderCard(app, cfg, canUp = i > 0, canDown = i < ordered.lastIndex)
        }
        OutlinedButton(onClick = { app.settings.forgetAllKeys() }) { Text("Effacer toutes les clés") }
    }
}

@Composable
private fun ProviderCard(app: JarvisApp, cfg: AiProviderConfig, canUp: Boolean, canDown: Boolean) {
    val ctx = LocalContext.current
    var key by remember(cfg.type) { mutableStateOf(cfg.apiKey) }
    var model by remember(cfg.type) { mutableStateOf(cfg.model) }
    var url by remember(cfg.type) { mutableStateOf(cfg.baseUrl) }
    var show by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(cfg.type == AiProviderType.CUSTOM) }
    val scope = rememberCoroutineScope()
    fun current() = cfg.copy(apiKey = key.trim(), model = model.trim(), baseUrl = url.trim())

    Section(cfg.type.displayName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SwitchRow("Activé", cfg.type.pricingNote, cfg.enabled) { on -> app.settings.saveProvider(current().copy(enabled = on)) }
            }
            IconButton(enabled = canUp, onClick = { app.settings.moveProvider(cfg.type, -1) }) { Icon(Icons.Filled.ArrowUpward, "Priorité plus haute") }
            IconButton(enabled = canDown, onClick = { app.settings.moveProvider(cfg.type, 1) }) { Icon(Icons.Filled.ArrowDownward, "Priorité plus basse") }
        }
        OutlinedTextField(
            key, { key = it }, label = { Text(if (cfg.type.requiresKey) "Clé API" else "Clé API (facultative)") }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Afficher la clé") } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(model, { model = it }, label = { Text("Modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (advanced) OutlinedTextField(url, { url = it }, label = { Text("Adresse de l'API") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (cfg.type == AiProviderType.GEMINI) {
            SwitchRow("Recherche Google intégrée", "Réponses à jour (actualité, horaires…). Peut entraîner des coûts au-delà du quota gratuit.", cfg.webGrounding) { on ->
                app.settings.saveProvider(current().copy(webGrounding = on))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.settings.saveProvider(current()); test = "Enregistré." }) { Text("Enregistrer") }
            OutlinedButton(onClick = {
                app.settings.saveProvider(current())
                test = "Test en cours…"
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        try {
                            val a = app.ai.askOne(current().copy(enabled = true), "Réponds en un seul mot.", listOf(ChatMessage(Role.USER, "Dis bonjour")))
                            if (a.model != current().model) withContext(Dispatchers.Main) { model = a.model }
                            "✓ Fonctionne avec ${a.model} : « ${a.text.take(40)} »"
                        } catch (e: AiException) { "✗ ${e.message}" } catch (e: Exception) { "✗ ${e.message}" }
                    }
                }
            }) { Text("Tester") }
        }
        test?.let { Text(it, color = if (it.startsWith("✗")) JColors.Error else JColors.Ok, fontSize = 13.sp) }
        Row {
            TextButton(onClick = { Perms.openUrl(ctx, cfg.type.keyHelpUrl) }) { Text("Obtenir une clé") }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Masquer l'adresse" else "Avancé") }
        }
    }
}

// ---------------- Voix ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSettingsScreen(activity: MainActivity, app: JarvisApp, onBack: () -> Unit) {
    val s by app.settings.flow.collectAsState()
    val vosk by app.vosk.status.collectAsState()
    val running by JarvisService.running.collectAsState()
    val scope = rememberCoroutineScope()
    var wakeWord by remember { mutableStateOf(s.wakeWord) }
    ScrollScreen("Voix et mot de réveil", onBack) {
        Section("Écoute permanente") {
            Hint("Le micro reste actif en arrière-plan pour entendre « ${s.wakeWord} ». Android impose une notification permanente et peut " +
                "arrêter l'écoute pour économiser la batterie (voir Batterie). Aucun audio n'est enregistré.")
            SwitchRow("J'accepte l'écoute permanente", "Consentement requis avant toute écoute en arrière-plan.", s.wakeConsent) { v ->
                app.settings.update { it.copy(wakeConsent = v, wakeEnabled = if (v) it.wakeEnabled else false) }
                if (!v) JarvisService.stop(activity)
            }
            SwitchRow("Écoute active", if (running) "En cours" else "Arrêtée", s.wakeEnabled && running, enabled = s.wakeConsent && Perms.mic(activity) && !s.privateMode) { on ->
                app.settings.update { it.copy(wakeEnabled = on) }
                if (on) JarvisService.start(activity) else JarvisService.stop(activity)
            }
            OutlinedTextField(wakeWord, { wakeWord = it }, label = { Text("Mot de réveil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = wakeWord.isNotBlank() && wakeWord != s.wakeWord, onClick = { app.settings.update { it.copy(wakeWord = wakeWord.trim()) } }) { Text("Appliquer") }
            Text("Moteur de détection", color = JColors.Cyan, fontSize = 13.sp)
            WakeEngineChoice.entries.forEach { c ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { app.settings.update { it.copy(wakeEngine = c) } }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = s.wakeEngine == c, onClick = { app.settings.update { it.copy(wakeEngine = c) } })
                    Column { Text(c.label, color = JColors.Text); Hint(c.description) }
                }
            }
            SwitchRow("Proposer la reprise après redémarrage", "Une notification permet de relancer l'écoute en un geste.", s.resumeAfterBoot) { v ->
                app.settings.update { it.copy(resumeAfterBoot = v) }
            }
        }
        Section("Modèle vocal hors ligne (Vosk)") {
            Hint("Modèle français libre (${VoskModelManager.MODEL_SIZE_MB} Mo). Une fois installé : détection du mot de réveil 100 % locale et " +
                "reconnaissance sans Internet. Téléchargement depuis alphacephei.com, de préférence en Wi-Fi.")
            when (val st = vosk) {
                VoskModelManager.Status.Missing -> Button(onClick = { scope.launch { app.vosk.download() } }) { Text("Télécharger le modèle") }
                is VoskModelManager.Status.Downloading -> {
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    Hint("${(st.progress * 100).roundToInt()} %")
                }
                VoskModelManager.Status.Unpacking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint("Installation…") }
                VoskModelManager.Status.Ready -> {
                    Text("✓ Modèle installé", color = JColors.Ok)
                    OutlinedButton(onClick = { app.vosk.delete() }) { Text("Supprimer le modèle") }
                }
                is VoskModelManager.Status.Failed -> {
                    Text(st.message, color = JColors.Error, fontSize = 13.sp)
                    Button(onClick = { scope.launch { app.vosk.download() } }) { Text("Réessayer") }
                }
            }
        }
        Section("Reconnaissance de vos demandes") {
            SttEngineChoice.entries.forEach { c ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { app.settings.update { it.copy(sttEngine = c) } }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = s.sttEngine == c, onClick = { app.settings.update { it.copy(sttEngine = c) } })
                    Column { Text(c.label, color = JColors.Text); Hint(c.description) }
                }
            }
            SwitchRow("Conversation continue", "Après une réponse, Jarvis réécoute quelques secondes sans redire « Jarvis ».", s.continuousConversation) { v ->
                app.settings.update { it.copy(continuousConversation = v) }
            }
            Text("Relances maximum : ${s.maxFollowUps}", color = JColors.Text, fontSize = 14.sp)
            Slider(value = s.maxFollowUps.toFloat(), onValueChange = { v -> app.settings.update { it.copy(maxFollowUps = v.roundToInt()) } }, valueRange = 1f..10f, steps = 8)
            SwitchRow("Son de réveil", "Petit bip quand Jarvis commence à écouter.", s.earcons) { v -> app.settings.update { it.copy(earcons = v) } }
        }
        Section("Voix de Jarvis") {
            SwitchRow("Voix Jarvis", "Voix masculine grave avec un timbre d'IA (égaliseur et légère réverbération). " +
                "Générée sur le téléphone : gratuite, sans quota, illimitée, même hors ligne.", s.jarvisVoice) { on ->
                app.settings.update { it.copy(jarvisVoice = on) }
            }
            Text("Débit : ${"%.1f".format(s.ttsRate)}", color = JColors.Text, fontSize = 14.sp)
            Slider(value = s.ttsRate, onValueChange = { v -> app.settings.update { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.8f)
            Text("Hauteur : ${"%.1f".format(s.ttsPitch)}", color = JColors.Text, fontSize = 14.sp)
            Slider(value = s.ttsPitch, onValueChange = { v -> app.settings.update { it.copy(ttsPitch = v) } }, valueRange = 0.6f..1.5f)
            val voices = remember(app.session.tts.isAvailable) { app.session.tts.frenchVoices() }
            if (voices.isNotEmpty()) {
                Text("Voix française", color = JColors.Cyan, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = s.ttsVoice.isBlank(), onClick = { app.settings.update { it.copy(ttsVoice = "") } },
                        label = { Text(if (s.jarvisVoice) "Automatique (voix d'homme)" else "Par défaut") })
                    voices.take(12).forEachIndexed { i, v ->
                        FilterChip(selected = s.ttsVoice == v.name, onClick = { app.settings.update { it.copy(ttsVoice = v.name) } },
                            label = { Text("Voix ${i + 1}${if (v.isNetworkConnectionRequired) " (en ligne)" else ""}") })
                    }
                }
            } else Hint("Aucune voix française détectée. Installez-en une dans les réglages de synthèse vocale d'Android.")
            Hint("Pour la meilleure voix : Synthèse vocale Android → moteur « Services vocaux Google » → Français → téléchargez les voix " +
                "(hors ligne). Jarvis choisit alors automatiquement la voix d'homme la plus grave.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.session.announce("Bonjour, je suis Jarvis. Tous les systèmes sont opérationnels.") }) { Text("Écouter") }
                OutlinedButton(onClick = { Perms.openTtsSettings(activity) }) { Text("Synthèse vocale Android") }
            }
        }
    }
}

// ---------------- Apparence ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(app: JarvisApp, onBack: () -> Unit) {
    val s by app.settings.flow.collectAsState()
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(s.userName) }
    var city by remember { mutableStateOf(s.defaultCity) }
    ScrollScreen("Apparence et bulle", onBack) {
        Section("Sphère") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SphereStyle.entries.forEach { st -> FilterChip(selected = s.sphereStyle == st, onClick = { app.settings.update { it.copy(sphereStyle = st) } }, label = { Text(st.label) }) }
            }
            SwitchRow("Mode économie d'énergie", "Animation à 20 images/s et géométrie allégée.", s.ecoMode) { v -> app.settings.update { it.copy(ecoMode = v) } }
            Hint("Si « Supprimer les animations » est activé dans l'accessibilité d'Android, la sphère ralentit automatiquement.")
        }
        Section("Bulle flottante") {
            SwitchRow("Afficher pendant les demandes", if (Perms.overlay(ctx)) "Autorisée" else "Autorisation requise", s.overlayEnabled && Perms.overlay(ctx)) { on ->
                if (on && !Perms.overlay(ctx)) Perms.openOverlaySettings(ctx)
                app.settings.update { it.copy(overlayEnabled = on) }
            }
            Text("Disparition automatique après ${s.overlayAutoHideSeconds} s", color = JColors.Text, fontSize = 14.sp)
            Slider(value = s.overlayAutoHideSeconds.toFloat(), onValueChange = { v -> app.settings.update { it.copy(overlayAutoHideSeconds = v.roundToInt()) } }, valueRange = 3f..30f)
            SwitchRow("Sphère toujours visible", "Hors de l'appli, la sphère reste par-dessus vos applications : touchez-la pour parler.",
                s.overlayAlwaysOn, enabled = Perms.overlay(ctx)) { on -> app.settings.update { it.copy(overlayAlwaysOn = on) } }
            Hint("Seule la sphère s'affiche, sans fond. Touchez-la pour parler, faites-la glisser pour la déplacer, appui long pour la fermer. " +
                "Les réponses s'ouvrent dans un mini-écran séparé que vous pouvez déplacer, épingler ou fermer ; il disparaît seul ensuite. " +
                "Vous continuez d'utiliser l'application en dessous.")
            Button(enabled = Perms.overlay(ctx), onClick = { app.overlay.resetUserHide(); app.overlay.show(force = true) }) { Text("Afficher la bulle maintenant") }
        }
        Section("Personnalisation") {
            OutlinedTextField(name, { name = it }, label = { Text("Votre prénom (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(city, { city = it }, label = { Text("Ville pour la météo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { app.settings.update { it.copy(userName = name.trim(), defaultCity = city.trim()) } }) { Text("Enregistrer") }
        }
    }
}

// ---------------- Confidentialité ----------------

@Composable
fun PrivacyScreen(app: JarvisApp, onBack: () -> Unit) {
    val s by app.settings.flow.collectAsState()
    val ctx = LocalContext.current
    var cleared by remember { mutableStateOf<String?>(null) }
    ScrollScreen("Confidentialité et micro", onBack) {
        Section("Micro") {
            SwitchRow("Mode privé", "Micro coupé, bulle fermée, aucune requête réseau (IA, météo, recherche).", s.privateMode) { on ->
                app.settings.update { it.copy(privateMode = on) }
                if (on) { JarvisService.stop(ctx); app.overlay.hide(userRequested = true) }
            }
            Hint("Android affiche un indicateur vert dès que le micro est utilisé. Jarvis ne le masque jamais.")
        }
        Section("Où vont vos données ?") {
            Hint("• Mot de réveil avec le modèle hors ligne : tout reste sur le téléphone.\n" +
                "• Reconnaissance Android : selon le fournisseur du téléphone (souvent Google), l'audio peut être traité sur ses serveurs.\n" +
                "• IA : le texte de vos questions et le contexte récent sont envoyés au fournisseur choisi.\n" +
                "• Météo : la ville est envoyée à Open-Meteo. Recherche : la requête est envoyée à Wikipédia.\n" +
                "• Aucun audio brut n'est conservé. Aucune donnée n'est envoyée au studio SuperEssence.")
        }
        Section("Historique") {
            SwitchRow("Conserver la conversation", "Stockée uniquement sur ce téléphone, dans les fichiers privés de l'application.", s.historyEnabled) { v ->
                app.settings.update { it.copy(historyEnabled = v) }
                if (!v) app.conversation.clear()
            }
            SwitchRow("Journal des actions", "Liste locale des actions exécutées (sans audio).", s.actionLogEnabled) { v -> app.settings.update { it.copy(actionLogEnabled = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.session.clearConversation(); cleared = "Conversation effacée." }) { Text("Effacer la conversation") }
                OutlinedButton(onClick = { app.actionLog.clear(); cleared = "Journal effacé." }) { Text("Effacer le journal") }
            }
            OutlinedButton(onClick = { app.settings.forgetAllKeys(); cleared = "Clés API effacées." }) { Text("Oublier les clés API") }
            cleared?.let { Text(it, color = JColors.Ok, fontSize = 13.sp) }
        }
        Section("Autorisations") {
            Hint("Vous pouvez retirer chaque autorisation à tout moment dans les réglages Android de l'application.")
            OutlinedButton(onClick = { Perms.openAppSettings(ctx) }) { Text("Ouvrir les réglages Android de Jarvis") }
        }
    }
}

// ---------------- Journal ----------------

@Composable
fun ActionLogScreen(app: JarvisApp, onBack: () -> Unit) {
    val log by app.actionLog.flow.collectAsState()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    ScrollScreen("Journal des actions", onBack) {
        if (log.isEmpty()) Hint("Aucune action enregistrée.")
        OutlinedButton(onClick = { app.actionLog.clear() }, enabled = log.isNotEmpty()) { Text("Tout effacer") }
        log.take(200).forEach { e ->
            Column(Modifier.fillMaxWidth().glass().padding(12.dp)) {
                Row {
                    Text(if (e.success) "✓" else "✗", color = if (e.success) JColors.Ok else JColors.Error)
                    Spacer(Modifier.padding(4.dp))
                    Text(e.intentId, color = JColors.Cyan, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(fmt.format(Date(e.timestampMs)), color = JColors.TextDim, fontSize = 11.sp)
                }
                Text(e.detail, color = JColors.Text, fontSize = 13.sp)
                if (e.confirmed) Text("Confirmé par vous", color = JColors.Gold, fontSize = 11.sp)
            }
        }
    }
}

// ---------------- Batterie ----------------

@Composable
fun BatteryScreen(activity: MainActivity, app: JarvisApp, onBack: () -> Unit) {
    val tick by activity.resumeTick
    remember(tick) { 0 } // relit les autorisations au retour dans l'appli
    val s by app.settings.flow.collectAsState()
    ScrollScreen("Batterie et arrière-plan", onBack) {
        Section("Fonctionnement en arrière-plan") {
            Hint("Pour l'écoute du mot « Jarvis », Android impose un service avec notification permanente. Certains fabricants " +
                "(Xiaomi, Huawei, Oppo, Samsung…) ferment quand même les applications en arrière-plan : il faut alors autoriser Jarvis manuellement.")
            val ok = Perms.batteryUnrestricted(activity)
            Text(if (ok) "✓ Jarvis n'est pas limité par l'optimisation de batterie." else "Jarvis est soumis à l'optimisation de batterie : l'écoute peut s'arrêter.",
                color = if (ok) JColors.Ok else JColors.Gold, fontSize = 14.sp)
            if (!ok) Button(onClick = { Perms.requestBatteryUnrestricted(activity) }) { Text("Retirer la limitation") }
            OutlinedButton(onClick = { Perms.openUrl(activity, "https://dontkillmyapp.com/" + Build.MANUFACTURER.lowercase()) }) {
                Text("Conseils pour ${Build.MANUFACTURER}")
            }
        }
        Section("Consommation") {
            Hint("• Moteur hors ligne Vosk : consommation modérée et constante.\n• Reconnaissance Android en boucle : plus gourmande.\n" +
                "• Sans écoute permanente : aucune consommation, utilisez le bouton, la tuile ou l'assistant par défaut.")
            SwitchRow("Mode économie d'énergie", "Sphère allégée.", s.ecoMode) { v -> app.settings.update { it.copy(ecoMode = v) } }
        }
        Section("Raccourcis sans écoute permanente") {
            Hint("• Tuile « Jarvis » : déroulez les Réglages rapides, touchez le crayon et ajoutez la tuile.\n" +
                "• Assistant par défaut : appui long sur le bouton Accueil (choisissez Jarvis ci-dessous).\n" +
                "• Raccourci : appui long sur l'icône Jarvis → « Parler ».")
            OutlinedButton(onClick = { Perms.openDefaultAssistant(activity) }) { Text("Choisir l'assistant par défaut") }
        }
    }
}

// ---------------- À propos ----------------

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScrollScreen("À propos", onBack) {
        Image(painterResource(R.drawable.superessence_logo), "SuperEssence", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        Text("Jarvis ${BuildConfig.VERSION_NAME}", color = JColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Hint("Assistant vocal Android conçu par le studio SuperEssence. Inspiré des assistants de science-fiction, avec une identité visuelle originale.")
        Hint("Composants libres : Vosk (Apache 2.0) pour la voix hors ligne, bruit simplex d'Ashima Arts (MIT) pour la sphère, " +
            "Open-Meteo (CC BY 4.0) pour la météo, Wikipédia (CC BY-SA) pour les résumés.")
    }
}
