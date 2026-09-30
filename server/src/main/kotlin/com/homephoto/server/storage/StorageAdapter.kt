package com.homephoto.server.storage

import java.io.InputStream
import java.nio.file.Path

/** 원본 전용 저장소. key는 기존 assets.original_path의 상대경로이며 물리 경로를 포함하지 않는다. */
interface StorageAdapter {
    /** 관리 화면에 표시할 저장소 위치. */
    val location: String

    fun initialize()

    /** 용량 정보를 제공하지 않는 backend는 null, 접근 실패는 예외. */
    fun space(): Space?

    /** 로컬/UNC 임포트 입력이 이미 이 저장소 안에 있는지 확인한다. */
    fun contains(path: Path): Boolean

    /**
     * source의 SHA-256을 checksum으로 받아 완성된 원본을 저장한다. 같은 key의 변경은 호출자가 AssetLocks로 직렬화한다.
     * DB 성공 후 commit, 실패 시 rollback을 호출하며 저장소 구현이 입력 파일의 수명을 처리한다.
     */
    fun save(key: String, source: Path, checksum: String, moveSource: Boolean = false): Write

    /** 없는 원본만 null. 접근 권한·I/O 오류는 호출자에게 전달한다. */
    fun stat(key: String): Stat?

    /** offset부터 최대 length 바이트를 읽는다. length가 null이면 EOF까지 읽고 호출자가 닫는다. */
    fun open(key: String, offset: Long = 0, length: Long? = null): InputStream

    /** 이미 없는 원본은 성공으로 취급하고, 삭제 오류는 전달한다. */
    fun delete(key: String)

    /** callback 안에서만 유효한 읽기용 파일. 외부 저장소 구현은 필요한 로컬 임시 파일을 관리한다. */
    fun <T> withReadableFile(key: String, reader: (Path) -> T): T

    data class Stat(val size: Long)
    data class Space(val totalBytes: Long, val usableBytes: Long)

    interface Write {
        /** DB 저장 성공 후 MOVE 입력 정리. 오류가 나도 저장된 원본과 DB는 유지한다. */
        fun commit()

        /** DB 저장 실패 후 이동한 입력 복구. 기존 원본은 삭제하지 않는다. */
        fun rollback()
    }
}
