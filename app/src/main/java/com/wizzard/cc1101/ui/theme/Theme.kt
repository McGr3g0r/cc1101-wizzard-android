package com.wizzard.cc1101.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand defaults (graphite). Kept as named constants so fixed-colour UI (splash,
// log/connection colours) stays stable regardless of the chosen template.
// ---------------------------------------------------------------------------
val Graphite = Color(0xFF1E2127)
val GraphiteSurface = Color(0xFF262A32)
val GraphiteElevated = Color(0xFF2E333C)
val Accent = Color(0xFF4FC3F7)

// Log line colours (kept constant across templates for recognisability).
val ColorTx = Color(0xFF80CBC4)
val ColorRxCli = Color(0xFFE0E0E0)
val ColorMonitor = Color(0xFFFFB74D)
val ColorError = Color(0xFFEF5350)
val ColorEcho = Color(0xFF6B7280)
val ColorInfo = Color(0xFF9CCC65)

val ConnOk = Color(0xFF66BB6A)
val ConnBad = Color(0xFFEF5350)
val ConnWarn = Color(0xFFFFCA28)

// ---------------------------------------------------------------------------
// Colour templates selectable in Konfiguracja.
// ---------------------------------------------------------------------------
data class ThemeTemplate(val id: String, val name: String, val scheme: ColorScheme)

private fun dark(bg: Long, surface: Long, variant: Long, primary: Long) = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color.Black,
    background = Color(bg),
    onBackground = Color(0xFFECEFF1),
    surface = Color(surface),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(variant),
    onSurfaceVariant = Color(0xFFB0BEC5),
)

val THEME_TEMPLATES: List<ThemeTemplate> = listOf(
    ThemeTemplate("graphite", "Grafit (domyślny)", dark(0xFF1E2127, 0xFF262A32, 0xFF2E333C, 0xFF4FC3F7)),
    ThemeTemplate("midnight", "Midnight Blue", dark(0xFF0D1B2A, 0xFF16263A, 0xFF1E3350, 0xFF4DA3FF)),
    ThemeTemplate("purple", "Deep Purple", dark(0xFF1A1326, 0xFF241834, 0xFF2E2042, 0xFFB388FF)),
    ThemeTemplate("forest", "Forest", dark(0xFF12201A, 0xFF182C24, 0xFF20392F, 0xFF4CD08A)),
    ThemeTemplate("amber", "Amber Dark", dark(0xFF211B10, 0xFF2C2416, 0xFF392F1E, 0xFFFFB74D)),
    ThemeTemplate(
        "light", "Light", lightColorScheme(
            primary = Color(0xFF0277BD),
            onPrimary = Color.White,
            background = Color(0xFFF4F5F7),
            onBackground = Color(0xFF1A1C1E),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1A1C1E),
            surfaceVariant = Color(0xFFE6E9EF),
            onSurfaceVariant = Color(0xFF52606D),
        )
    ),
)

fun schemeFor(id: String): ColorScheme =
    (THEME_TEMPLATES.firstOrNull { it.id == id } ?: THEME_TEMPLATES.first()).scheme

@Composable
fun CC1101Theme(
    themeId: String = "graphite",
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = schemeFor(themeId),
        typography = MaterialTheme.typography,
        content = content,
    )
}
