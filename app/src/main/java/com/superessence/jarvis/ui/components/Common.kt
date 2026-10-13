package com.superessence.jarvis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.ui.theme.JColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = JColors.Bg, titleContentColor = JColors.Text, navigationIconContentColor = JColors.Cyan),
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title.uppercase(), color = JColors.Cyan, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp))
        Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) JColors.Text else JColors.TextDim, fontSize = 15.sp)
            subtitle?.let { Text(it, color = JColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

enum class Status(val color: Color, val label: String) {
    OK(JColors.Ok, "Actif"), NEEDED(JColors.Gold, "À activer"), OFF(JColors.TextDim, "Désactivé"), UNAVAILABLE(JColors.Error, "Indisponible")
}

@Composable
fun StatusRow(title: String, detail: String, status: Status, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).background(status.color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = JColors.Text, fontSize = 15.sp)
            Text(detail, color = JColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
        } else {
            Text(status.label, color = status.color, fontSize = 12.sp)
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, color = JColors.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
