package com.homephoto.server.service

import java.time.LocalDate

/** 검색, MCP 집계, HTTP 집계에서 동일하게 사용하는 촬영 기간. */
data class PhotoDateRange(val start: LocalDate, val end: LocalDate, val singleDay: Boolean) {
    fun filter() = AssetFilter(startDate = start, endDate = end, mediaType = "PHOTO")
    fun metadata(): Map<String, Any?> = mapOf("date" to if (singleDay) start.toString() else null,
        "start_date" to start.toString(), "end_date" to end.toString())
    val label: String get() = if (start == end) start.toString() else "$start ~ $end"

    companion object {
        fun parse(args: Map<String, Any>): PhotoDateRange {
            val singleDay = "date" in args
            require(if (singleDay) "start_date" !in args && "end_date" !in args
                else "start_date" in args && "end_date" in args) {
                "하루는 date, 기간은 start_date와 end_date를 함께 지정해 주세요. 혼용할 수 없습니다."
            }
            fun dateArgument(name: String): LocalDate {
                val value = args[name] as? String ?: throw IllegalArgumentException("$name 값은 날짜 문자열이어야 합니다.")
                require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "날짜는 YYYY-MM-DD 형식이어야 합니다." }
                return LocalDate.parse(value).also { require(it.year in 1..9998) { "연도는 0001~9998 범위여야 합니다." } }
            }
            val start = dateArgument(if (singleDay) "date" else "start_date")
            val end = if (singleDay) start else dateArgument("end_date")
            require(!start.isAfter(end)) { "시작일은 종료일보다 늦을 수 없습니다." }
            return PhotoDateRange(start, end, singleDay)
        }
    }
}
