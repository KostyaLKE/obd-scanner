package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kostyalke.obdscanner.obd.PidDef
import com.kostyalke.obdscanner.obd.Pids

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveContent(
    st: LiveState,
    onToggleFavorite: (Int) -> Unit,
    onOnlyFavorites: (Boolean) -> Unit,
    onResetMinMax: () -> Unit,
) {
    val shown = if (st.onlyFavorites && st.favorites.isNotEmpty()) st.supported.filter { it.pid in st.favorites } else st.supported

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = !st.onlyFavorites, onClick = { onOnlyFavorites(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Все · ${st.supported.size}") }
                SegmentedButton(
                    selected = st.onlyFavorites, onClick = { onOnlyFavorites(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Избранные · ${st.favorites.count { f -> st.supported.any { it.pid == f } }}") }
            }
            IconButton(onClick = onResetMinMax) {
                Icon(Icons.Filled.Refresh, contentDescription = "Сбросить мин/макс", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            when {
                st.loading -> "Узнаю, какие параметры отдаёт блок…"
                st.cycleMs > 0 -> "Обновление раз в ${"%.1f".format(Ru, st.cycleMs / 1000.0)} с"
                else -> "Опрос…"
            },
            style = MaterialTheme.typography.bodySmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!st.loading && st.supported.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StatusNote("Блок не сообщил ни одного параметра", Status.colors.neutral, AppIcons.Speed,
                        "Попробуйте переподключиться. На некоторых старых дизелях текущие данные недоступны по OBD-II.")
                }
            } else if (st.onlyFavorites && shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StatusNote("Избранных пока нет", Status.colors.neutral, AppIcons.StarOutline,
                        "Отметьте звёздочкой нужные параметры во вкладке «Все» — они будут обновляться чаще.")
                }
            } else if (!st.onlyFavorites && st.favorites.isEmpty() && st.supported.size > 6) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Опрос по K-line медленный: отметьте звёздочкой 3–5 нужных параметров и переключитесь на «Избранные».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            items(shown, key = { it.pid }) { def ->
                LiveTile(def, st.values[def.pid], def.pid in st.favorites) { onToggleFavorite(def.pid) }
            }
        }
    }
}

@Composable
private fun LiveTile(def: PidDef, v: LiveValue?, fav: Boolean, onFav: () -> Unit) {
    Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(start = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    def.short,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                IconButton(onClick = onFav) {
                    Icon(
                        if (fav) Icons.Filled.Star else AppIcons.StarOutline,
                        contentDescription = if (fav) "Убрать из избранного" else "В избранное",
                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 14.dp)) {
                Text(
                    v?.let { Pids.format(def, it.value) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (def.unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(def.unit, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Text(
                if (v != null) "мин ${Pids.format(def, v.min)} · макс ${Pids.format(def, v.max)}" else " ",
                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(v?.history ?: emptyList(), Modifier.padding(end = 14.dp))
        }
    }
}

@Composable
private fun Sparkline(points: List<Float>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val base = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (points.size - 1)
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(i * step, pad + h - (p - min) / range * h)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
