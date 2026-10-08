package com.homephoto.server.db

import org.jetbrains.exposed.sql.Table

/** One immutable daily selection, shared by the household. Empty days have an EMPTY row. */
object MemorySets : Table("memory_sets") {
    val id = long("id").autoIncrement()
    val generatedOn = text("generated_on")
    val kind = text("kind")
    val title = text("title")
    val startDate = text("start_date")
    val endDate = text("end_date")
    val coverAssetId = long("cover_asset_id").nullable()
    val albumId = long("album_id").references(Albums.id).nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(generatedOn, kind) }
}

object MemoryPhotos : Table("memory_photos") {
    val memoryId = long("memory_id").references(MemorySets.id)
    val assetId = long("asset_id").references(Assets.id)
    val position = integer("position")
    override val primaryKey = PrimaryKey(memoryId, assetId)
}
