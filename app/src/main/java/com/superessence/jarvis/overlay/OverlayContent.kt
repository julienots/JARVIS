package com.superessence.jarvis.overlay

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.state.AssistantState
import com.superessence.jarvis.sphere.JarvisSphere
import com.superessence.jarvis.ui.components.CardsColumn
import com.superessence.jarvis.ui.components.glass
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.ui.theme.JarvisTheme

/**
 * Bulle flottante : uniquement la sphère, sans fond ni cadre.
 * Toucher = parler / arrêter · glisser = déplacer · appui long = fermer.
 */
@Composable
fun BubbleContent(app: JarvisApp, ctl: OverlayController) {
    JarvisTheme {
        val s = app.session
        val state by s.state.collectAsState()
        val level by s.level.collectAsState()
        val settings by app.settings.flow.collectAsState()
        Box(Modifier.size(104.dp)) {
            JarvisSphere(
                state, level, settings.sphereStyle, lowPower = settings.ecoMode,
                modifier = Modifier.fillMaxSize(),
                onDragBy = { dx, dy -> ctl.moveBubble(dx, dy) },
                onLongPress = { s.cancelAll(); ctl.hide(userRequested = true) },
            ) {
                if (s.isBusy) s.cancelAll() else s.startListening()
            }
        }
    }
}

/**
 * Mini-écran flottant : ce que vous avez dit, la réponse de Jarvis et ses cartes.
 * Se déplace par sa poignée, se ferme avec la croix, disparaît seul après la réponse.
 */
@Composable
fun PanelContent(app: JarvisApp, ctl: OverlayController) {
    JarvisTheme {
        val s = app.session
        val state by s.state.collectAsState()
        val partial by s.partial.collectAsState()
        val lastUser by s.lastUser.collectAsState()
        val reply by s.lastReply.collectAsState()
        val cards by s.cards.collectAsState()
        val replyShown by ctl.replyShown.collectAsState()
        val pinned by ctl.pinned.collectAsState()
        val cfg = LocalConfiguration.current

        Column(
            Modifier.width((cfg.screenWidthDp * 0.9f).dp).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Poignée : glisser pour déplacer le mini-écran
            Row(
                Modifier.fillMaxWidth().glass().padding(horizontal = 8.dp, vertical = 2.dp).pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); ctl.movePanel(drag.x, drag.y) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DragIndicator, contentDescription = "Déplacer", tint = JColors.TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("JARVIS", color = JColors.Cyan, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(state.label, color = if (state == AssistantState.ERROR) JColors.Error else JColors.TextDim, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Btn(if (s.isBusy) Icons.Filled.Stop else Icons.Filled.Mic, if (s.isBusy) "Arrêter" else "Parler") {
                    if (s.isBusy) s.cancelAll() else s.startListening()
                }
                Btn(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "Désépingler" else "Garder affiché") { ctl.togglePinned() }
                Btn(Icons.Filled.Close, "Fermer") { ctl.closePanel() }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = (cfg.screenHeightDp * 0.55f).dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val userLine = if (state == AssistantState.LISTENING) partial.ifBlank { "…" } else lastUser
                if ((replyShown || s.isBusy) && (userLine.isNotBlank() || reply.isNotBlank())) {
                    Column(Modifier.fillMaxWidth().glass().padding(12.dp)) {
                        if (userLine.isNotBlank()) Text("« $userLine »", color = JColors.TextDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (reply.isNotBlank() && state != AssistantState.LISTENING) {
                            Spacer(Modifier.size(4.dp))
                            Text(reply, color = JColors.Text, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 10, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                CardsColumn(cards, { s.dismissCard(it) }, { s.togglePin(it) })
            }
        }
    }
}

@Composable
private fun Btn(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = JColors.Cyan, modifier = Modifier.size(19.dp)) }
    }
}
