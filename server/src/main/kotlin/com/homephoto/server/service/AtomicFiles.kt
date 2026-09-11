package com.homephoto.server.service

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 최종 경로에는 완성된 파일만 노출한다. 호출자는 동일 목적지 작업을 직렬화해야 한다. */
object AtomicFiles {
    fun write(target: Path, writer: (Path) -> Unit) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".homephoto-", ".${target.fileName.toString().substringAfterLast('.')}")
        try {
            writer(temp)
            check(Files.size(temp) > 0) { "empty output: $target" }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
