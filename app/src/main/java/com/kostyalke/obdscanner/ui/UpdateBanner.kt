package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Баннер обновления над содержимым экрана. Информация — на нейтральной панели,
 * единственное действие — синяя кнопка справа.
 */
@Composable
fun UpdateBanner(
    st: UpdateState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (st) {
        UpdateState.Idle, UpdateState.Checking -> return
        is UpdateState.UpToDate -> {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatusNote("Установлена последняя версия ${st.versionName}", Status.colors.ok, Icons.Filled.CheckCircle, compact = true)
            }
            return
        }
        is UpdateState.Failed -> {
            BannerShell(
                title = st.message,
                subtitle = null,
                tone = Status.colors.error,
                action = "Повторить", onAction = onRetry, onDismiss = onDismiss,
            )
            return
        }
        else -> {}
    }
    val rel = when (st) {
        is UpdateState.Available -> st.rel
        is UpdateState.Downloading -> st.rel
        is UpdateState.Ready -> st.rel
        is UpdateState.Installing -> st.rel
        else -> return
    }
    val notes = rel.notes.takeIf { it.isNotBlank() }
    when (st) {
        is UpdateState.Available -> BannerShell("Доступно обновление · сборка ${rel.versionCode}", notes, null,
            "Обновить", onUpdate, onDismiss)
        is UpdateState.Downloading -> BannerShell("Скачиваю обновление · ${(st.progress * 100).toInt()}%", notes, null,
            null, {}, null, progress = st.progress)
        is UpdateState.Ready -> if (st.needPermission) {
            BannerShell("Разрешите установку обновлений",
                "Один раз включите «Разрешить из этого источника».", null,
                "Разрешить", onGrant, onDismiss)
        } else {
            BannerShell("Обновление скачано · сборка ${rel.versionCode}",
                "Приложение перезапустится, связь с машиной прервётся.", null, "Установить", onInstall, onDismiss)
        }
        is UpdateState.Installing -> BannerShell("Устанавливаю обновление…", "Подтвердите установку, если система спросит.",
            null, null, {}, null, progress = -1f)
        else -> {}
    }
}

@Composable
private fun BannerShell(
    title: String,
    subtitle: String?,
    tone: StatusTone?,
    action: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)?,
    progress: Float? = null,
) {
    Surface(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        shape = PanelShape,
        color = tone?.container ?: MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp),
                    tint = tone?.accent ?: MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = tone?.onContainer ?: MaterialTheme.colorScheme.onSurface,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = tone?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Скрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else Spacer(Modifier.width(10.dp))
            }
            // Кнопка под текстом: при крупном шрифте текст не обрезается.
            if (action != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onAction, contentPadding = ButtonPadding, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(action, maxLines = 1)
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.size(8.dp))
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(end = 10.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 10.dp))
            }
        }
    }
}
