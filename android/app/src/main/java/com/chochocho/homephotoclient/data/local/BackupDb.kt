package com.chochocho.homephotoclient.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalAsset(
    val uri: String,
    val displayName: String,
    val size: Long,
    val mtime: Long?,
    val hash: String?,
    val status: String,       // NEW | HASHED | UPLOADED | FAILED
    val error: String?,
)

/** 백업 실패 이력 한 건. 어떤 파일이 어느 단계에서 왜 실패했는지 남긴다. */
data class FailureEntry(
    val id: Long,
    val at: Long,             // epoch millis
    val displayName: String,
    val stage: String,        // 해시 | 업로드 | 실행
    val message: String,
)

/**
 * 업로드 상태 추적용 로컬 DB.
 * Room 대신 직접 구현 — 테이블 하나에 코드 생성기(KSP) 의존을 더할 이유가 없다.
 */
class BackupDb(context: Context) : SQLiteOpenHelper(context, "backup.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE local_assets (
              uri          TEXT PRIMARY KEY,
              display_name TEXT NOT NULL,
              size         INTEGER NOT NULL,
              mtime        INTEGER,
              hash         TEXT,
              status       TEXT NOT NULL DEFAULT 'NEW',
              error        TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_local_assets_status ON local_assets(status)")
        db.execSQL("CREATE INDEX idx_local_assets_hash ON local_assets(hash)")
        createFailureLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFailureLog(db)
    }

    private fun createFailureLog(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS failure_log (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              at           INTEGER NOT NULL,
              display_name TEXT NOT NULL,
              stage        TEXT NOT NULL,
              message      TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    // ---- 실패 이력 ----

    /** 실패 한 건 기록. 오래된 것은 최근 [MAX_FAILURE_LOG]건만 남기고 정리한다. */
    fun logFailure(displayName: String, stage: String, message: String) {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO failure_log (at, display_name, stage, message) VALUES (?, ?, ?, ?)",
            arrayOf(System.currentTimeMillis(), displayName, stage, message.take(500)),
        )
        db.execSQL(
            "DELETE FROM failure_log WHERE id NOT IN (SELECT id FROM failure_log ORDER BY id DESC LIMIT $MAX_FAILURE_LOG)"
        )
    }

    /** 최근 실패 이력 (최신순) */
    fun failureLog(limit: Int = MAX_FAILURE_LOG): List<FailureEntry> =
        readableDatabase.rawQuery(
            "SELECT id, at, display_name, stage, message FROM failure_log ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FailureEntry(
                            id = cursor.getLong(0),
                            at = cursor.getLong(1),
                            displayName = cursor.getString(2),
                            stage = cursor.getString(3),
                            message = cursor.getString(4),
                        )
                    )
                }
            }
        }

    fun failureLogCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM failure_log", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun clearFailureLog() {
        writableDatabase.execSQL("DELETE FROM failure_log")
    }

    // ---- 자산 상태 ----

    /** 스캔 결과 반영. 이미 아는 uri는 건드리지 않는다. */
    fun upsertScanned(items: List<LocalAsset>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("uri", item.uri)
                    put("display_name", item.displayName)
                    put("size", item.size)
                    put("mtime", item.mtime)
                    put("status", "NEW")
                }
                db.insertWithOnConflict("local_assets", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun itemsWithStatus(vararg statuses: String): List<LocalAsset> {
        val placeholders = statuses.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT uri, display_name, size, mtime, hash, status, error FROM local_assets WHERE status IN ($placeholders) ORDER BY mtime DESC",
            statuses,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalAsset(
                            uri = cursor.getString(0),
                            displayName = cursor.getString(1),
                            size = cursor.getLong(2),
                            mtime = if (cursor.isNull(3)) null else cursor.getLong(3),
                            hash = cursor.getString(4),
                            status = cursor.getString(5),
                            error = cursor.getString(6),
                        )
                    )
                }
            }
        }
    }

    fun updateHash(uri: String, hash: String) {
        writableDatabase.execSQL(
            "UPDATE local_assets SET hash = ?, status = 'HASHED' WHERE uri = ?",
            arrayOf(hash, uri),
        )
    }

    fun updateStatus(uri: String, status: String, error: String? = null) {
        writableDatabase.execSQL(
            "UPDATE local_assets SET status = ?, error = ? WHERE uri = ?",
            arrayOf(status, error, uri),
        )
    }

    /**
     * 해시 계산 단계에서 실패한 건(hash 없음)을 NEW로 되돌려 다음 실행에서 재시도되게 한다.
     * 해시 없는 FAILED는 업로드 대상 조회(hash != null 필터)에도 걸리지 않아 이 경로가 유일한 복구 수단이다.
     */
    fun revertHashFailures() {
        writableDatabase.execSQL(
            "UPDATE local_assets SET status = 'NEW', error = NULL WHERE status = 'FAILED' AND hash IS NULL"
        )
    }

    /**
     * 서버에서 삭제된 해시들을 SKIPPED로 마킹 — 재백업 때 올리지 않는다.
     * 단, 사용자가 명시적으로 재업로드를 지정한 RESTORE 상태는 건드리지 않는다.
     */
    fun markSkippedByHashes(hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in hashes.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE local_assets SET status = 'SKIPPED', error = NULL WHERE hash IN ($placeholders) AND status != 'RESTORE'",
                    chunk.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 스킵된 항목을 사용자가 선택해 다시 올리기로 함 → RESTORE 상태 (스킵 마킹을 우회하고 업로드됨) */
    fun requeueByUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in uris.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE local_assets SET status = 'RESTORE', error = NULL WHERE uri IN ($placeholders)",
                    chunk.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 서버에서 사라진 해시들을 재업로드 대기(HASHED)로 되돌림 — 서버 데이터 유실 시 자기치유용 */
    fun revertToHashedByHashes(hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in hashes.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE local_assets SET status = 'HASHED', error = NULL WHERE status = 'UPLOADED' AND hash IN ($placeholders)",
                    chunk.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 서버에 이미 있는 해시들을 UPLOADED로 마킹 */
    fun markUploadedByHashes(hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in hashes.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE local_assets SET status = 'UPLOADED', error = NULL WHERE hash IN ($placeholders)",
                    chunk.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        /** 실패 이력 보관 상한 — 넘으면 오래된 것부터 지운다 */
        const val MAX_FAILURE_LOG = 500
    }

    fun counts(): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM local_assets GROUP BY status", null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }
}
