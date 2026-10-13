package com.superessence.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.sphere.JarvisSphere
import com.superessence.jarvis.ui.components.ScreenBar
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.util.Perms

/** Conversation écrite ou vocale. L'historique n'est conservé après fermeture que si vous l'activez. */
@Composable
fun AssistantScreen(app: JarvisApp) {
    val s = app.session
    val messages by s.messages.collectAsState()
    val state by s.state.collectAsState()
    val level by s.level.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenBar("Assistant")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisSphere(state, level, settings.sphereStyle, lowPower = true, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(10.dp))
            Text(state.label, color = JColors.TextDim, modifier = Modifier.weight(1f))
            IconButton(onClick = { s.clearConversation() }) { Icon(Icons.Filled.DeleteSweep, "Effacer la conversation", tint = JColors.TextDim) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item {
                Text(
                    "Écrivez ou dictez une demande. Exemples : « Quelle heure est-il ? », « Lance un minuteur de 5 minutes », " +
                        "« Ajoute du pain à ma liste », « Explique-moi la photosynthèse ».\n\n" +
                        (if (settings.historyEnabled) "L'historique est conservé sur ce téléphone." else "L'historique n'est pas conservé (activable dans Confidentialité)."),
                    color = JColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            items(messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text,
                        color = if (m.isError) JColors.Error else JColors.Text, fontSize = 15.sp, lineHeight = 21.sp,
                        modifier = Modifier.widthIn(max = 320.dp)
                            .background(if (m.fromUser) JColors.SurfaceHigh else JColors.Surface, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Écrire à Jarvis…") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { s.submit(text.trim(), spoken = false); text = "" } }),
            )
            IconButton(onClick = { if (text.isNotBlank()) { s.submit(text.trim(), spoken = false); text = "" } }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = JColors.Cyan)
            }
            IconButton(enabled = Perms.mic(app), onClick = { if (s.isBusy) s.cancelAll() else s.startListening() }) {
                Icon(Icons.Filled.Mic, "Dicter", tint = JColors.Cyan)
            }
        }
    }
}
