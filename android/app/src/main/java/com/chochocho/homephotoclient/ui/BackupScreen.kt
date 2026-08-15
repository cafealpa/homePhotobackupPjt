package com.chochocho.homephotoclient.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.chochocho.homephotoclient.backup.BackupEngine
import com.chochocho.homephotoclient.backup.BackupState
import com.chochocho.homephotoclient.backup.formatElapsed
import com.chochocho.homephotoclient.data.local.LocalAsset
import kotlinx.coroutines.delay

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

@Composable
fun BackupScreen(engine: BackupEngine) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    val state by engine.state.collectAsState()
    val counts by engine.counts.collectAsState()
    var showSkipped by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { engine.refreshCounts() }

    if (showSkipped) {
        SkippedManager(engine = engine, onClose = {
            showSkipped = false
            engine.refreshCounts()
        })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("백업", style = MaterialTheme.typography.headlineSmall)

        if (!granted) {
            Text("사진·동영상을 읽으려면 권한이 필요합니다.")
            Button(onClick = { launcher.launch(requiredPermissions()) }) {
                Text("권한 허용")
            }
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val total = counts.values.sum()
                Text("발견된 파일: ${total}개")
                Text("백업 완료: ${counts["UPLOADED"] ?: 0}개")
                Text("대기 중: ${(counts["NEW"] ?: 0) + (counts["HASHED"] ?: 0)}개")
                Text(
                    "실패: ${counts["FAILED"] ?: 0}개",
                    color = if ((counts["FAILED"] ?: 0) > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                val skipped = counts["SKIPPED"] ?: 0
                if (skipped > 0) {
                    Text(
                        "스킵(서버에서 삭제됨): ${skipped}개",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if ((counts["SKIPPED"] ?: 0) > 0) {
            OutlinedButton(onClick = { showSkipped = true }) {
                Text("스킵된 사진 관리 (${counts["SKIPPED"]})")
            }
        }

        when (val s = state) {
            is BackupState.Working -> {
                // 1초마다 갱신되는 경과 시간
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                if (s.total > 0) {
                    LinearProgressIndicator(
                        progress = { s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${s.phase} ${s.done}/${s.total} — ${s.current ?: ""}")
                    Text(
                        listOfNotNull(
                            "경과 ${formatElapsed(now - s.startedAtMillis)}",
                            s.speedBps?.let { com.chochocho.homephotoclient.backup.formatSpeed(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${s.phase} 중...")
                    Text(
                        "경과 ${formatElapsed(now - s.startedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { engine.cancel() }) { Text("중지") }
            }
            is BackupState.Done -> {
                Text(
                    "완료 — 업로드 ${s.uploaded}개, 서버에 이미 있음 ${s.alreadyOnServer}개, 실패 ${s.failed}개" +
                        " (소요 시간 ${formatElapsed(s.elapsedMillis)})",
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { engine.start() }) { Text("다시 백업") }
                }
            }
            is BackupState.Error -> {
                Text("오류: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { engine.start() }) { Text("다시 시도") }
            }
            BackupState.Idle -> {
                Button(onClick = { engine.start() }) { Text("지금 백업") }
            }
        }
    }
}

/** 서버에서 삭제되어 백업이 스킵된 사진을 모아 보고, 선택해서 다시 올리는 화면. */
@Composable
private fun SkippedManager(engine: BackupEngine, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var items by remember { mutableStateOf<List<LocalAsset>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { items = engine.skippedItems() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← 뒤로") }
            Text(
                "스킵된 사진",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "서버에서 삭제되어 백업에서 제외된 사진입니다. 선택 후 다시 올릴 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val list = items
        when {
            list == null -> CircularProgressIndicator()
            list.isEmpty() -> Text("스킵된 사진이 없습니다.")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(list.size, key = { list[it].uri }) { i ->
                        val item = list[i]
                        val isSelected = item.uri in selected
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable {
                                    if (isSelected) selected.remove(item.uri) else selected.add(item.uri)
                                }
                                .then(
                                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                                    else Modifier
                                ),
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (isSelected) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                                )
                            }
                        }
                    }
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        engine.requeueAndBackup(selected.toList())
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("선택 ${selected.size}개 다시 올리기") }
            }
        }
    }
}
