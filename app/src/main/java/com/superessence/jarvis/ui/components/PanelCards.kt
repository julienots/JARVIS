package com.superessence.jarvis.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.core.panel.PanelCard
import com.superessence.jarvis.runtime.ActiveCard
import com.superessence.jarvis.ui.theme.JColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val cardShape = RoundedCornerShape(18.dp)

fun Modifier.glass(): Modifier = this
    .background(Brush.verticalGradient(listOf(Color(0xE60A1A2C), Color(0xE6050C16))), cardShape)
    .border(1.dp, Brush.linearGradient(listOf(JColors.Cyan.copy(alpha = 0.7f), JColors.Cyan.copy(alpha = 0.1f))), cardShape)

/** Mini-écran contextuel (heure, minuteur, météo, tâches, info, confirmation). */
@Composable
fun PanelCardView(active: ActiveCard, onClose: () -> Unit, onPin: () -> Unit, modifier: Modifier = Modifier) {
    val card = active.card
    Column(modifier.fillMaxWidth().glass().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title(card).uppercase(), color = JColors.Cyan, fontSize = 11.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onPin, modifier = Modifier.size(30.dp)) {
                Icon(if (active.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (active.pinned) "Désépingler" else "Épingler",
                    tint = if (active.pinned) JColors.Gold else JColors.TextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer la carte", tint = JColors.TextDim, modifier = Modifier.size(16.dp))
            }
        }
        when (card) {
            is PanelCard.Clock -> {
                Text(card.time, color = JColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Text(card.date.replaceFirstChar { it.uppercase() }, color = JColors.TextDim, fontSize = 14.sp)
            }
            is PanelCard.Countdown -> Countdown(card)
            is PanelCard.Weather -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${card.temperatureC.roundToInt()}°", color = JColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(card.description.replaceFirstChar { it.uppercase() }, color = JColors.Text, fontSize = 15.sp)
                        val mm = if (card.minC != null && card.maxC != null) "${card.minC!!.roundToInt()}° / ${card.maxC!!.roundToInt()}°" else ""
                        val wind = card.windKmh?.let { " · vent ${it.roundToInt()} km/h" } ?: ""
                        Text(mm + wind, color = JColors.TextDim, fontSize = 13.sp)
                    }
                }
                Text("Source : ${card.source}", color = JColors.TextDim, fontSize = 11.sp)
            }
            is PanelCard.Tasks -> card.items.forEach {
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◇", color = JColors.Gold, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = JColors.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            is PanelCard.Info -> {
                Text(card.body, color = JColors.Text, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 10, overflow = TextOverflow.Ellipsis)
                card.sourceUrl?.let { url ->
                    val ctx = LocalContext.current
                    Text("Ouvrir la source", color = JColors.Cyan, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).clickableNoRipple {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                }
            }
            is PanelCard.Confirm -> Text(card.question, color = JColors.Gold, fontSize = 15.sp)
            is PanelCard.Results -> {
                val ctx = LocalContext.current
                card.items.take(12).forEach { item ->
                    val uri = item.openUri
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .then(if (uri != null) Modifier.clickableNoRipple { openResult(ctx, uri) } else Modifier)) {
                        Text(item.label, color = if (uri != null) JColors.Cyan else JColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.detail.isNotBlank()) Text(item.detail, color = JColors.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun title(c: PanelCard) = when (c) {
    is PanelCard.Clock -> "Heure"
    is PanelCard.Countdown -> c.label
    is PanelCard.Weather -> "Météo · ${c.place}"
    is PanelCard.Tasks -> c.title
    is PanelCard.Info -> c.title
    is PanelCard.Confirm -> "Confirmation requise"
    is PanelCard.Results -> c.title
}

/** Ouvre un résultat touché par l'utilisateur : application (« app:paquet ») ou lien / contenu. */
private fun openResult(ctx: Context, uri: String) {
    val intent = if (uri.startsWith("app:")) ctx.packageManager.getLaunchIntentForPackage(uri.removePrefix("app:"))
    else Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent ?: return
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Countdown(card: PanelCard.Countdown) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(card.endAtEpochMs) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val left = ((card.endAtEpochMs - now) / 1000).coerceAtLeast(0)
    val txt = if (left >= 3600) "%d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60) else "%02d:%02d".format(left / 60, left % 60)
    Text(if (left == 0L) "Terminé" else txt, color = if (left == 0L) JColors.Gold else JColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Light)
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

@Composable
fun CardsColumn(cards: List<ActiveCard>, onClose: (String) -> Unit, onPin: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { ac -> PanelCardView(ac, { onClose(ac.card.key) }, { onPin(ac.card.key) }) }
    }
}
