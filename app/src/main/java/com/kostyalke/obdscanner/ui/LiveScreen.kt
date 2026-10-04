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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kostyalke.obdscanner.obd.PidDef
import com.kostyalke.obdscanner.obd.Pids

@Composable
fun LiveScreen(vm: AppViewModel) {
    val st by vm.live.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.startLive()
        onDispose { vm.stopLive() }
    }
    val shown = if (st.onlyFavorites && st.favorites.isNotEmpty()) st.supported.filter { it.pid in st.favorites } else st.supported

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Только избранные ♥", Modifier.weight(1f))
                    Switch(checked = st.onlyFavorites, onCheckedChange = { vm.setOnlyFavorites(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (st.cycleMs > 0) "Обновление: раз в ${"%.1f".format(st.cycleMs / 1000.0)} с · параметров: ${shown.size}"
                        else "Параметров: ${shown.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.resetMinMax() }) { Text("Сброс мин/макс") }
                }
                if (st.loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("Опрос поддерживаемых параметров…")
                } else if (st.supported.isEmpty()) {
                    Text("Блок не сообщил ни одного поддерживаемого параметра.")
                } else if (!st.onlyFavorites) {
                    Text(
                        "На K-line опрос медленный: отметьте ♥ нужные параметры и включите «Только избранные» — " +
                            "они будут обновляться чаще.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(shown, key = { it.pid }) { def ->
            LiveCard(def, st.values[def.pid], def.pid in st.favorites) { vm.toggleFavorite(def.pid) }
        }
    }
}

@Composable
private fun LiveCard(def: PidDef, v: LiveValue?, fav: Boolean, onFav: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    def.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onFav) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Избранное",
                        tint = if (fav) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    v?.let { Pids.format(def, it.value) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Text(def.unit, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (v != null) {
                Text(
                    "мин ${Pids.format(def, v.min)} · макс ${Pids.format(def, v.max)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Sparkline(v.history)
            }
        }
    }
}

@Composable
private fun Sparkline(points: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(32.dp).padding(end = 8.dp)) {
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(i * step, size.height - (p - min) / range * size.height)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
