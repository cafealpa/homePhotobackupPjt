package com.chochocho.homephotoclient.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chochocho.homephotoclient.data.ApiFactory
import com.chochocho.homephotoclient.data.AppSettings
import com.chochocho.homephotoclient.data.AssetDto
import com.chochocho.homephotoclient.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed interface Cell {
    data class Header(val yearMonth: String) : Cell
    data class Photo(val asset: AssetDto, val index: Int) : Cell
}

@Composable
fun TimelineScreen(repository: SettingsRepository) {
    var config by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(Unit) { config = repository.settings.first() }
    val cfg = config ?: return

    // 주의: Retrofit 프록시 객체를 remember의 key로 쓰면 안 된다 (equals가 항상 false)
    val state = remember(cfg.serverUrl, cfg.apiKey) {
        AssetListState(ApiFactory.create(cfg.serverUrl, cfg.apiKey))
    }
    val scope = rememberCoroutineScope()
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var columns by remember { mutableIntStateOf(4) }

    LaunchedEffect(state) { state.loadMore() }

    val cells = remember(state.items) {
        buildList {
            var current: String? = null
            state.items.forEachIndexed { index, asset ->
                val ym = asset.yearMonth ?: "기타"
                if (ym != current) {
                    add(Cell.Header(ym))
                    current = ym
                }
                add(Cell.Photo(asset, index))
            }
        }
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to state.items.size
        }.collect { (lastVisible, itemCount) ->
            if (itemCount > 0 && lastVisible >= itemCount - 40) state.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("사진", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    state.reset()
                    scope.launch { state.loadMore() }
                }) { Text("새로고침") }
            }

            val error = state.error
            if (state.items.isEmpty() && error != null && !state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Text("사진을 불러올 수 없어요", style = MaterialTheme.typography.titleMedium)
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = {
                            state.reset()
                            scope.launch { state.loadMore() }
                        }) { Text("다시 시도") }
                    }
                }
                return@Column
            }

            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { state.loadMore() } }) { Text("재시도") }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 두 손가락 핀치로 그리드 열 수 조절 (2~8열). 한 손가락 스크롤과 충돌 없음
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var zoomAccum = 1f
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) break
                                if (event.changes.count { it.pressed } >= 2) {
                                    zoomAccum *= event.calculateZoom()
                                    if (zoomAccum > 1.2f) {
                                        if (columns > 2) columns--
                                        zoomAccum = 1f
                                    } else if (zoomAccum < 1f / 1.2f) {
                                        if (columns < 8) columns++
                                        zoomAccum = 1f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = cells.size,
                        key = { i ->
                            when (val c = cells[i]) {
                                is Cell.Header -> "h:${c.yearMonth}"
                                is Cell.Photo -> c.asset.id
                            }
                        },
                        span = { i ->
                            if (cells[i] is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                    ) { i ->
                        when (val cell = cells[i]) {
                            is Cell.Header -> Text(
                                text = formatMonth(cell.yearMonth),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                            )
                            is Cell.Photo -> ThumbCell(
                                asset = cell.asset,
                                baseUrl = cfg.serverUrl,
                                apiKey = cfg.apiKey,
                                onClick = { selectedIndex = cell.index },
                            )
                        }
                    }
                    if (state.loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }
                    }
                }
            }
        }

        selectedIndex?.let { startIndex ->
            FullScreenViewer(
                items = state.items,
                startIndex = startIndex,
                baseUrl = cfg.serverUrl,
                apiKey = cfg.apiKey,
                onClose = { selectedIndex = null },
                onNearEnd = { scope.launch { state.loadMore() } },
                onDelete = { asset ->
                    scope.launch {
                        try {
                            state.api.deleteAsset(asset.id)
                            state.removeById(asset.id)
                        } catch (e: Exception) {
                            android.util.Log.e("Timeline", "delete failed", e)
                        }
                        selectedIndex = null // 인덱스가 밀리므로 뷰어를 닫는다
                    }
                },
            )
        }
    }
}

private fun formatMonth(yearMonth: String): String {
    val parts = yearMonth.split("-")
    return if (parts.size == 2) "${parts[0]}년 ${parts[1].trimStart('0')}월" else yearMonth
}
