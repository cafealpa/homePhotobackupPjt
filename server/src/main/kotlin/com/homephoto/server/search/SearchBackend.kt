package com.homephoto.server.search

import com.homephoto.server.service.PhotoDateRange

interface SearchBackend {
    data class Result(val hits: List<LocalVectorIndex.Hit>, val indexed: Long)
    fun photos(text: String, range: PhotoDateRange?, assetIds: Set<Long>? = null): Result
    fun faces(embedding: ByteArray, sourceAsset: Long): Result
}
