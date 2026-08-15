package com.chochocho.homephotoclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.chochocho.homephotoclient.data.ApiFactory
import com.chochocho.homephotoclient.data.AppSettings
import com.chochocho.homephotoclient.data.ClusterDto
import com.chochocho.homephotoclient.data.HomePhotoApi
import com.chochocho.homephotoclient.data.NameClusterRequest
import com.chochocho.homephotoclient.data.SettingsRepository
import com.chochocho.homephotoclient.data.toFriendlyMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PeopleScreen(repository: SettingsRepository) {
    var config by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(Unit) { config = repository.settings.first() }
    val cfg = config ?: return

    val api = remember(cfg.serverUrl, cfg.apiKey) { ApiFactory.create(cfg.serverUrl, cfg.apiKey) }
    val scope = rememberCoroutineScope()

    var clusters by remember { mutableStateOf<List<ClusterDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ClusterDto?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(api, refreshKey) {
        error = null
        try {
            clusters = api.clusters()
        } catch (e: Exception) {
            error = e.toFriendlyMessage()
        }
    }

    selected?.let { cluster ->
        PersonDetailScreen(
            api = api,
            cfg = cfg,
            cluster = cluster,
            onBack = {
                selected = null
                refreshKey++ // 이름이 바뀌었을 수 있으니 목록 갱신
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("인물", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { refreshKey++ }) { Text("새로고침") }
        }

        when {
            error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(error!!, textAlign = TextAlign.Center)
                    Button(onClick = { refreshKey++ }) { Text("다시 시도") }
                }
            }

            clusters == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            clusters!!.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 인물이 없어요.\n얼굴 분석이 끝나면 여기에 표시됩니다.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                items(clusters!!, key = { it.clusterId }) { cluster ->
                    ClusterCell(
                        cluster = cluster,
                        baseUrl = cfg.serverUrl,
                        apiKey = cfg.apiKey,
                        onClick = { selected = cluster },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClusterCell(cluster: ClusterDto, baseUrl: String, apiKey: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("$baseUrl/api/v1/faces/${cluster.coverFaceId}/thumb")
                .setHeader("X-Api-Key", apiKey)
                .build(),
            contentDescription = cluster.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape),
        )
        Text(
            cluster.name ?: "인물 ${cluster.clusterId}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            "${cluster.faceCount}장",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PersonDetailScreen(
    api: HomePhotoApi,
    cfg: AppSettings,
    cluster: ClusterDto,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val state = remember(cluster.clusterId) { AssetListState(api, clusterId = cluster.clusterId) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf(cluster.name) }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(cluster.name ?: "") }

    LaunchedEffect(state) { state.loadMore() }

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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 인물") }
                Text(
                    name ?: "인물 ${cluster.clusterId}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    nameInput = name ?: ""
                    showNameDialog = true
                }) { Text(if (name == null) "이름 붙이기" else "이름 변경") }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = state.items.size,
                    key = { state.items[it].id },
                ) { i ->
                    ThumbCell(
                        asset = state.items[i],
                        baseUrl = cfg.serverUrl,
                        apiKey = cfg.apiKey,
                        onClick = { selectedIndex = i },
                    )
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
                            android.util.Log.e("People", "delete failed", e)
                        }
                        selectedIndex = null
                    }
                },
            )
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("이름 붙이기") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = nameInput.trim()
                    if (newName.isNotEmpty()) {
                        scope.launch {
                            try {
                                api.nameCluster(cluster.clusterId, NameClusterRequest(newName))
                                name = newName
                            } catch (_: Exception) {
                                // 실패 시 조용히 유지 — 재시도 가능
                            }
                            showNameDialog = false
                        }
                    } else {
                        showNameDialog = false
                    }
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("취소") }
            },
        )
    }
}
