package com.chochocho.homephotoclient.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 타이포 스케일. 시스템 기본 글꼴(한국어는 기기 기본 — 보통 Noto Sans CJK)을 그대로 쓴다.
 * M3 기본값에서 화면 제목(headlineSmall)과 섹션 제목(titleMedium)만 조금 무겁게 조정.
 *
 * 용도 규칙:
 *  - headlineSmall : 탭 화면 제목 ("사진", "인물", "백업", "설정")
 *  - titleLarge    : 하위 화면 제목 (인물 상세, 실패 이력)
 *  - titleMedium   : 카드/섹션 제목, 타임라인 월 헤더
 *  - bodyMedium    : 본문, 설명
 *  - bodySmall     : 보조 정보 (날짜, 건수, 안내문)
 *  - labelLarge    : 버튼
 */
val HomePhotoTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
)
