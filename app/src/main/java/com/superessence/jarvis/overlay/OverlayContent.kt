package com.superessence.jarvis.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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

@Composable
fun OverlayContent(app: JarvisApp, ctl: OverlayController) {
    JarvisTheme {
        val s = app.session
        val state by s.state.collectAsState()
        val level by s.level.collectAsState()
        val partial by s.partial.collectAsState()
        val lastUser by s.lastUser.collectAsState()
        val reply by s.lastReply.collectAsState()
        val cards by s.cards.collectAsState()
        val expanded by ctl.expanded.collectAsState()
        val pinned by ctl.pinned.collectAsState()
        val settings by app.settings.flow.collectAsState()
        val width = (LocalConfiguration.current.screenWidthDp * 0.92f).dp

        Column(Modifier.widthIn(max = if (expanded) width else 260.dp).padding(4.dp)) {
            // En-tête déplaçable : sphère + état + commandes
            Row(
                Modifier.glass().padding(6.dp).pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); ctl.moveBy(drag.x, drag.y) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JarvisSphere(state, level, settings.sphereStyle, lowPower = settings.ecoMode,
                    modifier = Modifier.size(64.dp), onTop = true) {
                    if (s.isBusy) s.cancelAll() else s.startListening()
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f, fill = false).widthIn(min = 90.dp)) {
                    Text("JARVIS", color = JColors.Cyan, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Text(state.label, color = if (state == AssistantState.ERROR) JColors.Error else JColors.Text, fontSize = 13.sp, maxLines = 1)
                }
                Btn(if (s.isBusy) Icons.Filled.Stop else Icons.Filled.Mic, if (s.isBusy) "Arrêter" else "Parler") {
                    if (s.isBusy) s.cancelAll() else s.startListening()
                }
                Btn(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "Désépingler" else "Épingler") { ctl.togglePinned() }
                Btn(if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore, if (expanded) "Réduire" else "Agrandir") { ctl.toggleExpanded() }
                Btn(Icons.Filled.Close, "Fermer") { s.cancelAll(); ctl.hide(userRequested = true) }
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val userLine = if (state == AssistantState.LISTENING) partial.ifBlank { "…" } else lastUser
                    if (userLine.isNotBlank() || reply.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().glass().padding(12.dp)) {
                            if (userLine.isNotBlank()) Text("« $userLine »", color = JColors.TextDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (reply.isNotBlank() && state != AssistantState.LISTENING) {
                                Spacer(Modifier.size(4.dp))
                                Text(reply, color = JColors.Text, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    CardsColumn(cards, { s.dismissCard(it) }, { s.togglePin(it) })
                }
            }
        }
    }
}

@Composable
private fun Btn(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = JColors.Cyan, modifier = Modifier.size(20.dp)) }
    }
}
