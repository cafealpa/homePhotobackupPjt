package com.chochocho.homephotoclient.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 앱 팔레트. 사진이 주인공이므로 크롬(배경·바·카드)은 채도 낮은 중립색으로,
 * 포인트 색은 하나(틸)만 쓴다. 화면 코드에서 Color(0x...)를 직접 쓰지 말고
 * MaterialTheme.colorScheme.* 역할 토큰을 통해 접근한다.
 */
object HomePhotoColors {
    // 포인트(틸) — 버튼, 선택 탭, 진행률, 링크
    val Teal10 = Color(0xFF002020)
    val Teal30 = Color(0xFF004F51)
    val Teal40 = Color(0xFF00696B)
    val Teal80 = Color(0xFF4FD8DA)
    val Teal90 = Color(0xFF6FF6F8)

    // 보조(중립 틸 그레이) — 칩, 보조 텍스트 배경
    val Neutral10 = Color(0xFF051F1F)
    val Neutral30 = Color(0xFF324B4B)
    val Neutral40 = Color(0xFF4A6363)
    val Neutral80 = Color(0xFFB0CCCC)
    val Neutral90 = Color(0xFFCCE8E8)

    // 표면(라이트) — 약간 따뜻한 오프화이트
    val SurfaceLight = Color(0xFFFAFAF8)
    val SurfaceVariantLight = Color(0xFFE9EEEE)
    val OnSurfaceLight = Color(0xFF1A1C1C)
    val OnSurfaceVariantLight = Color(0xFF3F4949)
    val OutlineLight = Color(0xFF6F7979)

    // 표면(다크) — 순흑 대신 아주 어두운 회색. 전체화면 뷰어만 순흑.
    val SurfaceDark = Color(0xFF121414)
    val SurfaceVariantDark = Color(0xFF3F4949)
    val OnSurfaceDark = Color(0xFFE1E3E3)
    val OnSurfaceVariantDark = Color(0xFFBEC9C9)
    val OutlineDark = Color(0xFF899393)

    // 오류 (M3 기본값과 동일 — 백업 실패 건수 등)
    val Error10 = Color(0xFF410002)
    val Error30 = Color(0xFF93000A)
    val Error40 = Color(0xFFBA1A1A)
    val Error80 = Color(0xFFFFB4AB)
    val Error90 = Color(0xFFFFDAD6)

    // 뷰어·썸네일 오버레이 전용 (테마와 무관하게 항상 사진 위에 얹힘)
    val ViewerBackground = Color.Black
    val OverlayText = Color.White
    val OverlayTextDim = Color(0xFFBDBDBD)
    val OverlayWarning = Color(0xFFFFD54F)
    val Scrim = Color(0x99000000)
}
