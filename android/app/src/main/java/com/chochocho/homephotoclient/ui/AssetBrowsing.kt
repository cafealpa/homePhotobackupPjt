package com.chochocho.homephotoclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.chochocho.homephotoclient.data.AssetDto
import com.chochocho.homephotoclient.data.HomePhotoApi
import com.chochocho.homephotoclient.data.toFriendlyMessage

/** 서버 사진 목록의 페이지네이션 상태. 타임라인과 인물별 보기가 공유한다. */
internal class AssetListState(
    val api: HomePhotoApi,
    private val clusterId: Int? = null,
) {
    var items by mutableStateOf(listOf<AssetDto>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var nextCursor: String? = null
    private var reachedEnd = false

    suspend fun loadMore() {
        if (loading || reachedEnd) return
        loading = true
        error = null
        try {
            val page = api.assets(cursor = nextCursor, limit = 200, clusterId = clusterId)
            items = items + page.items
            nextCursor = page.nextCursor
            if (page.nextCursor == null) reachedEnd = true
        } catch (e: Exception) {
            android.util.Log.e("AssetList", "loadMore failed", e)
            error = e.toFriendlyMessage()
        } finally {
            loading = false
        }
    }

    fun reset() {
        items = emptyList()
        nextCursor = null
        reachedEnd = false
        error = null
    }

    fun removeById(id: Long) {
        items = items.filterNot { it.id == id }
    }
}

/** 그리드 한 칸: 정사각 썸네일 (+동영상 배지). */
@Composable
internal fun ThumbCell(asset: AssetDto, baseUrl: String, apiKey: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("$baseUrl/api/v1/assets/${asset.id}/thumb?size=400")
                .setHeader("X-Api-Key", apiKey)
                .build(),
            contentDescription = asset.originalFilename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (asset.mediaType == "VIDEO") {
            Text(
                "▶",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
    }
}

/** 전체화면 뷰어 — 좌우 스와이프로 앞뒤 사진 이동. */
@Composable
internal fun FullScreenViewer(
    items: List<AssetDto>,
    startIndex: Int,
    baseUrl: String,
    apiKey: String,
    onClose: () -> Unit,
    onNearEnd: () -> Unit = {},
    onDelete: ((AssetDto) -> Unit)? = null,
) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    var confirmDelete by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page >= items.size - 10) onNearEnd()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { items[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val asset = items[page]
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("$baseUrl/api/v1/assets/${asset.id}/thumb?size=1600")
                    .setHeader("X-Api-Key", apiKey)
                    .build(),
                contentDescription = asset.originalFilename,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClose),
            )
        }

        items.getOrNull(pagerState.currentPage)?.let { asset ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                if (asset.mediaType == "VIDEO") {
                    Text("동영상 — 재생은 추후 지원", color = Color.Yellow)
                }
                Text(asset.originalFilename ?: "", color = Color.White)
                Text(asset.takenAt?.replace('T', ' ') ?: "", color = Color.LightGray, fontSize = 12.sp)
            }

            if (onDelete != null) {
                androidx.compose.material3.TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) { Text("🗑", fontSize = 20.sp) }
            }

            if (confirmDelete && onDelete != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("휴지통으로 이동") },
                    text = {
                        Text(
                            "이 사진을 서버 휴지통으로 이동합니다. 폰의 원본은 남습니다.\n" +
                                "30일 뒤 자동 영구 삭제되며, 그 전에는 웹 뷰어의 휴지통에서 " +
                                "복원할 수 있습니다. 휴지통에 있는 동안에도 재백업은 건너뜁니다.",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmDelete = false
                            onDelete(asset)
                        }) { Text("삭제") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                            Text("취소")
                        }
                    },
                )
            }
        }
    }
}
