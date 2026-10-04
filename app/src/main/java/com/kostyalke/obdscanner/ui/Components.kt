package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
val PanelShape = RoundedCornerShape(16.dp)
val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** Нейтральная панель с информацией. Без тени: глубина задаётся тоном поверхности. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

@Composable
fun PanelDivider() = HorizontalDivider(
    modifier = Modifier.padding(start = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant,
)

/** Заголовок группы: больше воздуха сверху, чем снизу. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Статус: цвет + иконка + текст (цвет никогда не единственный признак). */
@Composable
fun StatusPill(text: String, tone: StatusTone, icon: ImageVector? = null) {
    Surface(color = tone.container, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = tone.onContainer, fontWeight = FontWeight.Medium)
        }
    }
}

/** Заметка-статус на всю ширину: предупреждение, ошибка, успех. */
@Composable
fun StatusNote(
    title: String,
    tone: StatusTone,
    icon: ImageVector,
    body: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = PanelShape, color = tone.container) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalAlignment = if (body == null) Alignment.CenterVertically else Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
                    color = tone.onContainer,
                )
                if (body != null) {
                    Spacer(Modifier.size(2.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall, color = tone.onContainer)
                }
            }
        }
    }
}

/** Строка «подпись — значение» внутри панели. */
@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value ?: "—", style = MaterialTheme.typography.bodyLarge.merge(Tabular), fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Нижняя панель действий — в зоне большого пальца. Все кнопки экрана живут здесь,
 * поэтому глаз не ищет их среди данных.
 */
@Composable
fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
