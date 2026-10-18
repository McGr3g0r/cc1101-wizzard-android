/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.protocol.FieldType
import com.wizzard.cc1101.protocol.ProtoField
import com.wizzard.cc1101.ui.theme.*

/** A small round LED + label reflecting the connection state. */
@Composable
fun ConnectionIndicator(state: ConnState, deviceName: String?) {
    val (color, text) = when (state) {
        ConnState.DISCONNECTED -> ConnBad to stringResource(R.string.conn_disconnected)
        ConnState.CONNECTING -> ConnWarn to stringResource(R.string.conn_connecting)
        ConnState.CONNECTED -> ConnWarn to stringResource(R.string.conn_connected_unverified)
        ConnState.VERIFIED -> ConnOk to stringResource(R.string.conn_verified)
        ConnState.WRONG_DEVICE -> ConnBad to stringResource(R.string.conn_wrong_device)
        ConnState.ERROR -> ConnBad to stringResource(R.string.conn_error)
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
            stringResource(R.string.monitor_lock_banner),
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

/**
 * Like [SectionCard] but the body collapses behind a chevron, freeing screen
 * space. When collapsed, [collapsedSummary] (if given) is shown inline in the
 * header so key state stays visible.
 */
@Composable
fun CollapsibleSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    collapsedSummary: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (!expanded && collapsedSummary != null) {
                    collapsedSummary()
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.cd_collapse else R.string.cd_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/**
 * Renders one [ProtoField] for editing: a +/- stepper for [FieldType.TICKER]
 * (bounded by the field's min/max), otherwise a text field. Shared by the
 * Protocol and TX field editors so every protocol form behaves the same.
 */
@Composable
fun ProtoFieldRow(
    field: ProtoField,
    value: String,
    editable: Boolean,
    onChange: (String) -> Unit,
) {
    if (field.type == FieldType.TICKER) {
        val cur = value.toIntOrNull()?.coerceIn(field.min, field.max) ?: field.min
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label, Modifier.weight(1f), fontSize = 13.sp)
            OutlinedIconButton(
                onClick = { onChange((cur - 1).coerceAtLeast(field.min).toString()) },
                enabled = editable && cur > field.min,
            ) { Icon(Icons.Filled.Remove, stringResource(R.string.cd_decrease)) }
            Text(
                cur.toString(),
                Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp),
                fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            OutlinedIconButton(
                onClick = { onChange((cur + 1).coerceAtMost(field.max).toString()) },
                enabled = editable && cur < field.max,
            ) { Icon(Icons.Filled.Add, stringResource(R.string.cd_increase)) }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(field.label) },
            readOnly = !editable,
            singleLine = field.type != FieldType.TEXT,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.type == FieldType.DEC) KeyboardType.Number else KeyboardType.Ascii
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

/**
 * A millisecond value edited either by direct numeric entry (1 ms precision) or
 * with +/- nudge buttons. Used for the inter-frame gap in TX and Settings.
 */
@Composable
fun MsStepper(
    label: String,
    sub: String?,
    valueMs: Int,
    onChange: (Int) -> Unit,
    step: Int = 1,
    max: Int = 10000,
    unit: String = "ms",
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (sub != null) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedIconButton(
            onClick = { onChange((valueMs - step).coerceIn(0, max)) },
            enabled = valueMs > 0,
        ) { Icon(Icons.Filled.Remove, stringResource(R.string.cd_decrease)) }

        // Local text mirror so the user can type freely; re-seeded whenever the
        // backing value changes (e.g. via the +/- buttons).
        var text by remember(valueMs) { mutableStateOf(valueMs.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = { s ->
                val digits = s.filter { it.isDigit() }.take(6)
                text = digits
                digits.toIntOrNull()?.let { onChange(it.coerceIn(0, max)) }
            },
            label = { Text(unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 16.sp, textAlign = TextAlign.Center
            ),
            modifier = Modifier.width(112.dp).padding(horizontal = 6.dp),
        )
        OutlinedIconButton(
            onClick = { onChange((valueMs + step).coerceIn(0, max)) },
            enabled = valueMs < max,
        ) { Icon(Icons.Filled.Add, stringResource(R.string.cd_increase)) }
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
