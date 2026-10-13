package com.superessence.jarvis.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.superessence.jarvis.ui.components.Hint
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.util.Perms

/** Première configuration guidée, pensée pour quelqu'un qui ne connaît rien à la technique. */
@Composable
fun OnboardingScreen(activity: MainActivity, app: JarvisApp) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val tick by activity.resumeTick
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick; refresh
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ ; if (it) page++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; page++ }
    var consent by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JarvisSphere(if (page == 2) AssistantState.LISTENING else AssistantState.IDLE, 0f, app.settings.current.sphereStyle,
            lowPower = false, modifier = Modifier.size(220.dp))
        Text("Étape ${page + 1} sur 6", color = JColors.TextDim, fontSize = 12.sp)
        when (page) {
            0 -> Page("Bienvenue, je suis Jarvis",
                "Votre assistant vocal, conçu par le studio SuperEssence. Parlez-moi naturellement : je donne l'heure et la météo, " +
                    "je lance des minuteurs, j'ouvre vos applications, je gère vos tâches et je réponds à vos questions.") {
                Button(onClick = { page++ }) { Text("Commencer") }
            }
            1 -> Page("Ce que je ne ferai jamais",
                "Je n'envoie aucun message, n'appelle personne et ne supprime rien sans votre confirmation. Je ne fais ni achat ni paiement. " +
                    "Je n'enregistre pas vos conversations par défaut. Vous pouvez couper le micro à tout moment.") {
                Button(onClick = { page++ }) { Text("Compris") }
            }
            2 -> Page("Le micro",
                "Pour vous entendre, Jarvis a besoin du micro. Android affiche toujours un indicateur quand il est utilisé.") {
                if (Perms.mic(activity)) {
                    Text("✓ Micro autorisé", color = JColors.Ok)
                    Button(onClick = { page++ }) { Text("Continuer") }
                } else {
                    Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Autoriser le micro") }
                    TextButton(onClick = { page++ }) { Text("Plus tard") }
                }
            }
            3 -> Page("Notifications",
                "Elles servent aux rappels, aux minuteurs et à l'indicateur d'écoute permanente.") {
                if (Build.VERSION.SDK_INT >= 33 && !Perms.notifications(activity)) {
                    Button(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Autoriser les notifications") }
                    TextButton(onClick = { page++ }) { Text("Plus tard") }
                } else {
                    Text("✓ Notifications autorisées", color = JColors.Ok)
                    Button(onClick = { page++ }) { Text("Continuer") }
                }
            }
            4 -> Page("Écoute du mot « Jarvis »",
                "Si vous l'activez, le micro reste allumé en arrière-plan pour détecter le mot « Jarvis ». " +
                    "Une notification permanente l'indique. Avec le modèle hors ligne (Réglages → Voix), la détection se fait entièrement sur le téléphone ; " +
                    "sinon elle utilise le service vocal d'Android. Cela consomme un peu de batterie. Vous pouvez l'arrêter à tout moment : " +
                    "« Jarvis, arrête l'écoute », la notification, ou le bouton sur l'accueil.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Text("J'ai compris et j'accepte l'écoute permanente", color = JColors.Text, fontSize = 14.sp)
                }
                Button(enabled = consent && Perms.mic(activity), onClick = {
                    app.settings.update { it.copy(wakeConsent = true, wakeEnabled = true) }
                    JarvisService.start(activity)
                    page++
                }) { Text("Activer l'écoute") }
                if (!Perms.mic(activity)) Hint("Autorisez d'abord le micro (étape 3).")
                TextButton(onClick = { page++ }) { Text("Non merci, j'utiliserai le bouton micro") }
            }
            else -> Page("Tout est prêt",
                "Touchez la sphère ou dites « Jarvis » pour parler. Pour les questions libres, ajoutez une clé d'IA gratuite (Gemini ou Groq) " +
                    "dans Réglages → Intelligence artificielle. La bulle flottante et les autres options se trouvent dans Réglages → Autorisations.") {
                Button(onClick = { app.settings.update { it.copy(onboardingDone = true) } }) { Text("Découvrir Jarvis") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("SuperEssence", color = JColors.Gold, fontSize = 12.sp, letterSpacing = 3.sp)
    }
}

@Composable
private fun Page(title: String, body: String, actions: @Composable () -> Unit) {
    Text(title, color = JColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(body, color = JColors.TextDim, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
}
