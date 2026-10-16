package com.wizzard.cc1101.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.ui.theme.*

/** A small round LED + label reflecting the connection state. */
@Composable
fun ConnectionIndicator(state: ConnState, deviceName: String?) {
    val (color, text) = when (state) {
        ConnState.DISCONNECTED -> ConnBad to "Rozłączono"
        ConnState.CONNECTING -> ConnWarn to "Łączenie…"
        ConnState.CONNECTED -> ConnWarn to "Połączono (niezweryfikowane)"
        ConnState.VERIFIED -> ConnOk to "Połączono: cc1101-wizzard"
        ConnState.WRONG_DEVICE -> ConnBad to "Nieznane urządzenie"
        ConnState.ERROR -> ConnBad to "Błąd"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (deviceName != null && (state == ConnState.VERIFIED || state == ConnState.CONNECTED)) {
                Text(
                    deviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shown on command tabs while the monitor is running — commands are locked. */
@Composable
fun MonitorLockBanner() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ColorError.copy(alpha = 0.16f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "Monitor aktywny — wysyłanie profili i komend do RP2040 jest zablokowane. " +
                "Zatrzymaj monitor w zakładce Monitor.",
            color = ColorError,
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}
