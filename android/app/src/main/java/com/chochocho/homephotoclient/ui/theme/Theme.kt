package com.chochocho.homephotoclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = HomePhotoColors.Teal40,
    onPrimary = Color.White,
    primaryContainer = HomePhotoColors.Teal90,
    onPrimaryContainer = HomePhotoColors.Teal10,
    secondary = HomePhotoColors.Neutral40,
    onSecondary = Color.White,
    secondaryContainer = HomePhotoColors.Neutral90,
    onSecondaryContainer = HomePhotoColors.Neutral10,
    error = HomePhotoColors.Error40,
    onError = Color.White,
    errorContainer = HomePhotoColors.Error90,
    onErrorContainer = HomePhotoColors.Error10,
    background = HomePhotoColors.SurfaceLight,
    onBackground = HomePhotoColors.OnSurfaceLight,
    surface = HomePhotoColors.SurfaceLight,
    onSurface = HomePhotoColors.OnSurfaceLight,
    surfaceVariant = HomePhotoColors.SurfaceVariantLight,
    onSurfaceVariant = HomePhotoColors.OnSurfaceVariantLight,
    outline = HomePhotoColors.OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = HomePhotoColors.Teal80,
    onPrimary = HomePhotoColors.Teal10,
    primaryContainer = HomePhotoColors.Teal30,
    onPrimaryContainer = HomePhotoColors.Teal90,
    secondary = HomePhotoColors.Neutral80,
    onSecondary = HomePhotoColors.Neutral10,
    secondaryContainer = HomePhotoColors.Neutral30,
    onSecondaryContainer = HomePhotoColors.Neutral90,
    error = HomePhotoColors.Error80,
    onError = HomePhotoColors.Error10,
    errorContainer = HomePhotoColors.Error30,
    onErrorContainer = HomePhotoColors.Error90,
    background = HomePhotoColors.SurfaceDark,
    onBackground = HomePhotoColors.OnSurfaceDark,
    surface = HomePhotoColors.SurfaceDark,
    onSurface = HomePhotoColors.OnSurfaceDark,
    surfaceVariant = HomePhotoColors.SurfaceVariantDark,
    onSurfaceVariant = HomePhotoColors.OnSurfaceVariantDark,
    outline = HomePhotoColors.OutlineDark,
)

/** 모서리: 카드/다이얼로그 12dp, 칩·버튼 8dp, 썸네일 셀은 0(그리드 밀착). */
val HomePhotoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 간격 토큰. 화면 여백 16, 섹션 사이 12, 항목 사이 8, 그리드 틈 2. */
object HomePhotoSpacing {
    val screen = 16.dp
    val section = 12.dp
    val item = 8.dp
    val tight = 4.dp
    val grid = 2.dp
}

/**
 * 앱 전체 테마. 시스템 다크 모드를 따르며 다이내믹 컬러(Material You)는 쓰지 않는다 —
 * 사진 색감과 충돌하지 않도록 팔레트를 고정하기 위함.
 */
@Composable
fun HomePhotoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = HomePhotoTypography,
        shapes = HomePhotoShapes,
        content = content,
    )
}
