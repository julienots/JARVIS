package com.superessence.jarvis.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.service.JarvisService
import com.superessence.jarvis.sphere.JarvisSphere
import com.superessence.jarvis.ui.MainActivity
import com.superessence.jarvis.ui.components.CardsColumn
import com.superessence.jarvis.ui.components.Hint
import com.superessence.jarvis.ui.components.Section
import com.superessence.jarvis.ui.components.SwitchRow
import com.superessence.jarvis.ui.components.glass
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.util.Perms

@Composable
fun HomeScreen(activity: MainActivity, app: JarvisApp, openSettings: (String) -> Unit) {
    val s = app.session
    val state by s.state.collectAsState()
    val level by s.level.collectAsState()
    val partial by s.partial.collectAsState()
    val lastUser by s.lastUser.collectAsState()
    val reply by s.lastReply.collectAsState()
    val cards by s.cards.collectAsState()
    val error by s.error.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val serviceRunning by JarvisService.running.collectAsState()
    val serviceStatus by JarvisService.status.collectAsState()
    val providers by app.settings.providers.collectAsState()
    val tick by activity.resumeTick
    var refresh = remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick; refresh.intValue

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh.intValue++
        if (granted) s.startListening()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }

    fun talk() {
        if (!Perms.mic(activity)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else if (s.isBusy) s.cancelAll() else s.startListening()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("J A R V I S", color = JColors.Cyan, fontSize = 18.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Light)
        Text("by SuperEssence", color = JColors.Gold.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 2.sp)

        Box(Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
            JarvisSphere(state, level, settings.sphereStyle, lowPower = settings.ecoMode, modifier = Modifier.fillMaxSize()) { talk() }
        }

        Text(state.label, color = when (state) {
            AssistantState.ERROR -> JColors.Error
            AssistantState.AWAITING_CONFIRMATION -> JColors.Gold
            else -> JColors.Text
        }, fontSize = 18.sp)
        val heard = if (state == AssistantState.LISTENING) partial else lastUser
        if (heard.isNotBlank()) Text("« $heard »", color = JColors.TextDim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (reply.isNotBlank() && state != AssistantState.LISTENING) {
            Text(reply, color = JColors.Text, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().glass().padding(14.dp))
        }
        error?.let {
            Text(it, color = JColors.Error, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { talk() }, modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (s.isBusy) JColors.Error else JColors.Cyan)) {
                Icon(if (s.isBusy) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (s.isBusy) "Arrêter" else "Parler à Jarvis",
                    modifier = Modifier.size(34.dp))
            }
        }
        Hint(if (Perms.mic(activity)) "Touchez la sphère ou le micro, ou dites « ${settings.wakeWord} »." else "Autorisez le micro pour parler à Jarvis.")

        if (cards.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardsColumn(cards, { s.dismissCard(it) }, { s.togglePin(it) }, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        Section("Contrôles rapides") {
            SwitchRow(
                "Écoute du mot « ${settings.wakeWord} »",
                when {
                    settings.privateMode -> "Indisponible en mode privé."
                    !Perms.mic(activity) -> "Autorisez d'abord le micro."
                    serviceRunning -> serviceStatus.ifBlank { "Active : notification permanente affichée." }
                    else -> "Désactivée. Le micro n'est utilisé que quand vous touchez le bouton."
                },
                checked = settings.wakeEnabled && serviceRunning,
                enabled = Perms.mic(activity) && !settings.privateMode,
            ) { on ->
                if (on) {
                    if (!settings.wakeConsent) { openSettings("voice"); return@SwitchRow }
                    if (Build.VERSION.SDK_INT >= 33 && !Perms.notifications(activity)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    app.settings.update { it.copy(wakeEnabled = true) }
                    JarvisService.start(activity)
                } else {
                    app.settings.update { it.copy(wakeEnabled = false) }
                    JarvisService.stop(activity)
                }
            }
            SwitchRow(
                "Bulle flottante",
                if (Perms.overlay(activity)) "Jarvis apparaît par-dessus vos applications pendant une demande." else "Nécessite l'autorisation « Afficher par-dessus les autres applications ».",
                checked = settings.overlayEnabled && Perms.overlay(activity),
            ) { on ->
                if (on && !Perms.overlay(activity)) Perms.openOverlaySettings(activity)
                app.settings.update { it.copy(overlayEnabled = on) }
            }
            SwitchRow("Mode privé", "Micro coupé, aucune requête réseau pour la voix, bulle fermée.", checked = settings.privateMode) { on ->
                app.settings.update { it.copy(privateMode = on) }
                if (on) { JarvisService.stop(activity); app.overlay.hide(userRequested = true) }
            }
        }

        if (providers.none { it.isUsable }) {
            Section("Intelligence artificielle") {
                Hint("Aucune IA configurée : les commandes du téléphone marchent, mais pas les questions libres. " +
                    "Gemini et Groq proposent des clés gratuites.")
                TextButton(onClick = { openSettings("ai") }) { Text("Configurer une IA") }
            }
        }
        val missing = listOf(Perms.mic(activity), Perms.notifications(activity), Perms.overlay(activity)).count { !it }
        if (missing > 0) {
            Section("Configuration") {
                Hint("$missing autorisation${if (missing > 1) "s" else ""} utile${if (missing > 1) "s" else ""} non accordée${if (missing > 1) "s" else ""}.")
                TextButton(onClick = { openSettings("permissions") }) { Text("Ouvrir le guide des autorisations") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
