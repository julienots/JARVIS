package com.superessence.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.core.routine.Routine
import com.superessence.jarvis.core.routine.RoutineStep
import com.superessence.jarvis.core.routine.StepType
import com.superessence.jarvis.core.routine.TriggerType
import com.superessence.jarvis.ui.components.Hint
import com.superessence.jarvis.ui.components.ScreenBar
import com.superessence.jarvis.ui.components.glass
import com.superessence.jarvis.ui.theme.JColors
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val dayNames = listOf("L", "M", "M", "J", "V", "S", "D")

@Composable
fun RoutinesScreen(app: JarvisApp) {
    val routines by app.routines.flow.collectAsState()
    var editing by remember { mutableStateOf<Routine?>(null) }
    var toDelete by remember { mutableStateOf<Routine?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenBar("Routines")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Une routine enchaîne plusieurs commandes : à heure fixe, sur une phrase (« Jarvis, je lance ma séance ») ou d'un geste. " +
                    "Les actions sensibles (appel, message) sont toujours ignorées dans une routine. " +
                    "Android peut retarder une routine horaire de quelques minutes si les « alarmes exactes » ne sont pas autorisées.")
                routines.forEach { r ->
                    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = JColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(describe(r), color = JColors.TextDim, fontSize = 13.sp)
                            }
                            Switch(checked = r.enabled, onCheckedChange = { on ->
                                app.routines.upsert(r.copy(enabled = on)); app.scheduler.scheduleRoutines()
                            })
                        }
                        Text(r.steps.joinToString(" → ") { if (it.type == StepType.SAY) "« ${it.value} »" else it.value },
                            color = JColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        if (r.lastRunEpochMs > 0) Text("Dernière exécution : " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.lastRunEpochMs)),
                            color = JColors.TextDim, fontSize = 11.sp)
                        Row {
                            TextButton(onClick = { app.session.runRoutineNow(r.id) }, enabled = r.enabled) {
                                Icon(Icons.Filled.PlayArrow, null); Text("Exécuter")
                            }
                            TextButton(onClick = { editing = r }) { Icon(Icons.Filled.Edit, null); Text("Modifier") }
                            TextButton(onClick = { toDelete = r }) { Icon(Icons.Filled.Delete, null); Text("Supprimer") }
                        }
                    }
                }
                Spacer(Modifier.height(90.dp))
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Routine(UUID.randomUUID().toString(), "Nouvelle routine", steps = listOf(RoutineStep(StepType.SAY, "Bonjour !"))) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nouvelle") },
        )
    }

    editing?.let { r ->
        RoutineEditor(r, onDismiss = { editing = null }) { saved ->
            app.routines.upsert(saved); app.scheduler.scheduleRoutines(); editing = null
        }
    }
    toDelete?.let { r ->
        AlertDialog(onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${r.name} » ?") },
            confirmButton = { TextButton(onClick = { app.routines.remove(r.id); app.scheduler.scheduleRoutines(); toDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } })
    }
}

private fun describe(r: Routine) = when (r.trigger) {
    TriggerType.MANUAL -> "Manuelle"
    TriggerType.VOICE -> "Phrase : « ${r.phrase} »"
    TriggerType.TIME -> "%02d:%02d · %s".format(r.hour, r.minute,
        if (r.days.isEmpty() || r.days.size == 7) "tous les jours" else r.days.sorted().joinToString(" ") { dayNames[it - 1] })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(initial: Routine, onDismiss: () -> Unit, onSave: (Routine) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var trigger by remember { mutableStateOf(initial.trigger) }
    var hour by remember { mutableStateOf(initial.hour.toString()) }
    var minute by remember { mutableStateOf("%02d".format(initial.minute)) }
    var phrase by remember { mutableStateOf(initial.phrase) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial.days) } }
    val steps = remember { mutableStateListOf<RoutineStep>().apply { addAll(initial.steps) } }
    val valid = name.isNotBlank() && steps.isNotEmpty() &&
        (trigger != TriggerType.TIME || (hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59)) &&
        (trigger != TriggerType.VOICE || phrase.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Routine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                Text("Déclencheur", color = JColors.Cyan, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TriggerType.entries.forEach { t -> FilterChip(selected = trigger == t, onClick = { trigger = t }, label = { Text(t.label) }) }
                }
                when (trigger) {
                    TriggerType.TIME -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("Heure") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                            Text(" : ")
                            OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text("Min") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                FilterChip(selected = d in days, onClick = { if (d in days) days.remove(d) else days.add(d) }, label = { Text(dayNames[d - 1]) })
                            }
                        }
                        Hint("Aucun jour coché = tous les jours.")
                    }
                    TriggerType.VOICE -> OutlinedTextField(phrase, { phrase = it }, label = { Text("Phrase (après « Jarvis »)") }, singleLine = true)
                    TriggerType.MANUAL -> Hint("Lancée avec le bouton Exécuter ou « Jarvis, lance la routine $name ».")
                }
                Text("Étapes", color = JColors.Cyan, fontSize = 13.sp)
                steps.forEachIndexed { i, st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            steps[i] = st.copy(type = if (st.type == StepType.SAY) StepType.COMMAND else StepType.SAY)
                        }) { Text(if (st.type == StepType.SAY) "Dire" else "Commande", fontSize = 12.sp) }
                        OutlinedTextField(st.value, { v -> steps[i] = st.copy(value = v) }, singleLine = true, modifier = Modifier.weight(1f))
                        IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Filled.Delete, "Retirer l'étape") }
                    }
                }
                Row {
                    TextButton(onClick = { steps.add(RoutineStep(StepType.COMMAND, "")) }) { Text("+ Commande") }
                    TextButton(onClick = { steps.add(RoutineStep(StepType.SAY, "")) }) { Text("+ Phrase") }
                }
                Hint("Une commande s'écrit comme vous la diriez : « donne-moi la météo », « lance un minuteur de 10 minutes ».")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(
                    name = name.trim(), trigger = trigger, hour = hour.toIntOrNull() ?: 8, minute = minute.toIntOrNull() ?: 0,
                    days = days.toSet(), phrase = phrase.trim(), steps = steps.filter { it.value.isNotBlank() },
                ))
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
