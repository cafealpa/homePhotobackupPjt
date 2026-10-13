package com.homephoto.server.search

import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.transactions.transaction

/** 장면분석 원문은 보존하고, 현재 인물 연결로 검색용 입력만 구성한다. */
internal data class CaptionSceneSource(val text: String, val personIds: List<Long>, val clusterIds: List<Int>) {
    val fingerprint: String get() = CaptionTextEncoder.fingerprint(
        "$FORMAT_VERSION\n${personIds.joinToString(",")}\n${clusterIds.joinToString(",")}\n$text")

    companion object {
        const val FORMAT_VERSION = 2

        fun load(ids: List<Long>): Map<Long, CaptionSceneSource> {
            if (ids.isEmpty()) return emptyMap()
            return transaction {
                val captions = Captions.innerJoin(Assets).selectAll().where {
                    (Captions.assetId inList ids) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and (Assets.mediaType eq "PHOTO")
                }.toList()
                if (captions.isEmpty()) return@transaction emptyMap()
                val faces = Faces.leftJoin(Persons).select(Faces.assetId, Faces.personId, Faces.clusterId, Persons.name).where {
                    (Faces.assetId inList captions.map { it[Captions.assetId] }) and (Faces.hidden eq false)
                }.toList()
                val clusters = faces.mapNotNull { it[Faces.clusterId] }.distinct()
                // 자동 그룹 배정은 person_id를 채우지 않는다. 유일한 확정 라벨만 참조하며 얼굴 행은 수정하지 않는다.
                val labels = if (clusters.isEmpty()) emptyMap() else Faces.innerJoin(Persons).select(Faces.clusterId, Persons.id, Persons.name).where {
                    (Faces.clusterId inList clusters) and (Faces.hidden eq false) and Persons.name.isNotNull()
                }.withDistinct().mapNotNull { row ->
                    row[Persons.name]?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
                        ?.let { Triple(row[Faces.clusterId]!!, row[Persons.id], it) }
                }.groupBy { it.first }.mapValues { (_, rows) ->
                    rows.map { it.second to it.third }.distinct().singleOrNull()
                }
                val byAsset = faces.groupBy { it[Faces.assetId] }
                captions.associate { caption ->
                    val members = byAsset[caption[Captions.assetId]].orEmpty()
                    val people = members.mapNotNull { row ->
                        if (row[Faces.personId] != null) {
                            row.getOrNull(Persons.name)?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
                                ?.let { row[Faces.personId]!! to it }
                        } else labels[row[Faces.clusterId]]
                    }.distinct().sortedBy { it.first }
                    val names = people.map { it.second }.distinct().sorted()
                    val text = buildList {
                        if (names.isNotEmpty()) add("등장인물: ${names.joinToString(", ")}")
                        add("장면: ${caption[Captions.caption].trim()}")
                        caption[Captions.tags]?.replace(',', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.let { add("태그: $it") }
                    }.joinToString("\n")
                    caption[Captions.assetId] to CaptionSceneSource(text, people.map { it.first }, members.mapNotNull { it[Faces.clusterId] }.distinct().sorted())
                }
            }
        }

        /** 이름도 기존 단어 검색에 포함한다. 문장에서 이름을 추출하거나 조사/행동을 해석하지 않는다. */
        fun personMatches(word: String): Op<Boolean> {
            val direct = Faces.innerJoin(Persons).select(Faces.assetId).where {
                (Faces.hidden eq false) and (Persons.name like "%$word%")
            }
            val label = Persons.name.min()
            val groups = Faces.innerJoin(Persons).select(Faces.clusterId).where {
                (Faces.hidden eq false) and Faces.clusterId.isNotNull() and Persons.name.isNotNull() and (Persons.name neq "")
            }.groupBy(Faces.clusterId).having { (Persons.id.countDistinct() eq 1L) and (label like "%$word%") }
            val inferred = Faces.select(Faces.assetId).where {
                (Faces.hidden eq false) and Faces.personId.isNull() and (Faces.clusterId inSubQuery groups)
            }
            return (Assets.id inSubQuery direct) or (Assets.id inSubQuery inferred)
        }
    }
}
