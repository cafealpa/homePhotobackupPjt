package com.homephoto.server.service

import org.springframework.stereotype.Component

/** 단일 서버 프로세스에서 같은 자산의 저장·복원·삭제·썸네일 생성을 직렬화한다. */
@Component
class AssetLocks {
    private val stripes = Array(256) { Any() }

    fun <T> withHash(hash: String, action: () -> T): T =
        synchronized(stripes[(hash.hashCode() and Int.MAX_VALUE) % stripes.size], action)
}
