package com.homephoto.server.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** 원격 작업 이름을 저장하기 전 SUBMITTING도 남겨 불명확한 제출을 자동 반복하지 않는다. */
object CaptionBatches : Table("caption_batches") {
    val id = text("id")
    val remoteName = text("remote_name").nullable()
    val model = text("model")
    val keyFile = text("key_file")
    val nextPoll = long("next_poll")
    override val primaryKey = PrimaryKey(id)
}

object CaptionBatchItems : Table("caption_batch_items") {
    val jobId = long("job_id").references(Jobs.id, onDelete = ReferenceOption.CASCADE)
    val batchId = text("batch_id").references(CaptionBatches.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(jobId)
}
