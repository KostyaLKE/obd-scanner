package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/*
 * Палитра «приборная панель».
 * Правило разделения: информация живёт на нейтральных панелях; статусы — цветные плашки
 * с иконкой и текстом (красный/янтарный/зелёный); действия — только синий цвет «action».
 * Синий нигде не используется для данных и статусов, поэтому кнопку видно сразу.
 * Динамические цвета Android 12+ (от обоев) отключены сознательно: они ломают это разделение.
 */

/** Цвета статусов: акцент (иконка/текст на панели), фон плашки и текст на плашке. */
@Immutable
data class StatusTone(val accent: Color, val container: Color, val onContainer: Color)

@Immutable
data class StatusColors(val error: StatusTone, val warning: StatusTone, val ok: StatusTone, val neutral: StatusTone)

private val DarkStatus = StatusColors(
    error = StatusTone(Color(0xFFFF7A6B), Color(0xFF3B1815), Color(0xFFFFD3CD)),
    warning = StatusTone(Color(0xFFF2B13C), Color(0xFF36290E), Color(0xFFFFE2A6)),
    ok = StatusTone(Color(0xFF4CCB8E), Color(0xFF0F2D20), Color(0xFFB9F0D3)),
    neutral = StatusTone(Color(0xFF9AA5B2), Color(0xFF1F252C), Color(0xFFE7EBF0)),
)

private val LightStatus = StatusColors(
    error = StatusTone(Color(0xFFC02B26), Color(0xFFFCE4E1), Color(0xFF7A1410)),
    warning = StatusTone(Color(0xFF965800), Color(0xFFFFF0D0), Color(0xFF5C3600)),
    ok = StatusTone(Color(0xFF1B7A4B), Color(0xFFDDF4E8), Color(0xFF0D4A2C)),
    neutral = StatusTone(Color(0xFF525E6C), Color(0xFFE8ECF1), Color(0xFF11161C)),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5AAEFF),
    onPrimary = Color(0xFF04192E),
    primaryContainer = Color(0xFF15283D),
    onPrimaryContainer = Color(0xFFB3D8FF),
    secondaryContainer = Color(0xFF15283D),
    onSecondaryContainer = Color(0xFFB3D8FF),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE7EBF0),
    surface = Color(0xFF0E1114),
    onSurface = Color(0xFFE7EBF0),
    onSurfaceVariant = Color(0xFF9AA5B2),
    surfaceVariant = Color(0xFF1F252C),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF13171B),
    surfaceContainer = Color(0xFF161A1F),
    surfaceContainerHigh = Color(0xFF1F252C),
    surfaceContainerHighest = Color(0xFF283039),
    outline = Color(0xFF3A444F),
    outlineVariant = Color(0xFF2B333C),
    error = Color(0xFFFF7A6B),
    onError = Color(0xFF2A0703),
    errorContainer = Color(0xFF3B1815),
    onErrorContainer = Color(0xFFFFD3CD),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F62D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FB),
    onPrimaryContainer = Color(0xFF0A3D86),
    secondaryContainer = Color(0xFFDCE8FB),
    onSecondaryContainer = Color(0xFF0A3D86),
    background = Color(0xFFF1F3F6),
    onBackground = Color(0xFF11161C),
    surface = Color(0xFFF1F3F6),
    onSurface = Color(0xFF11161C),
    onSurfaceVariant = Color(0xFF525E6C),
    surfaceVariant = Color(0xFFE8ECF1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8ECF1),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFFB4BEC9),
    outlineVariant = Color(0xFFD3DAE2),
    error = Color(0xFFC02B26),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E1),
    onErrorContainer = Color(0xFF7A1410),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

val Ru: java.util.Locale = java.util.Locale.forLanguageTag("ru")

/** Цифры одинаковой ширины: значения датчиков не «прыгают» при обновлении. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = Typography(),
            content = content,
        )
    }
}

object Status {
    val colors: StatusColors @Composable get() = LocalStatusColors.current
}
