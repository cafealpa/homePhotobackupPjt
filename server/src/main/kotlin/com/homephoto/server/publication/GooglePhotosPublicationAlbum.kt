package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AtomicFiles
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** 계정 연결별 전용 앨범. 토큰 옆 별도 파일에 저장하며 원본/게시 큐를 수정하지 않는다. */
@Service
class GooglePhotosPublicationAlbum(private val props: AppProperties, private val mapper: ObjectMapper,
                                   private val publisher: GooglePhotosPublisher) {
    companion object { const val TITLE = "HomePhoto · 1600px 게시용" }
    data class State(val status: String, val id: String? = null, val productUrl: String? = null, val lastError: String? = null)
    data class Book(val activeConnection: String = "", val connections: Map<String, State> = emptyMap())
    data class View(val status: String, val title: String, val productUrl: String?, val lastError: String?)
    data class Organized(val included: Int, val failedAssetIds: List<Long>, val stoppedCode: String?, val album: View)

    private fun path(): Path {
        if (props.googlePhotos.tokenFile.isBlank()) throw PublicationFailure(PublicationFailure.Kind.AUTH, "CREDENTIALS_NOT_CONFIGURED")
        val token = Path.of(props.googlePhotos.tokenFile).toAbsolutePath()
        return token.resolveSibling("${token.fileName}.albums.json")
    }
    private fun read(bookPath: Path = path()): Book = try { if (Files.exists(bookPath)) mapper.readValue(Files.readString(bookPath)) else Book() }
        catch (error: PublicationFailure) { throw error }
        catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "ALBUM_STATE_UNREADABLE") }
    private fun save(book: Book, bookPath: Path) = AtomicFiles.write(bookPath) { mapper.writeValue(it.toFile(), book) }
    private fun <T> locked(block: (Path) -> T): T {
        val bookPath = path()
        Files.createDirectories(bookPath.parent)
        FileChannel.open(bookPath.resolveSibling("${bookPath.fileName}.lock"), CREATE, WRITE).use { channel ->
            // CLI와 서버의 동시 생성도 차단한다. 이미 진행 중이면 상태를 덮어쓰지 않는다.
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                ?: throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "ALBUM_BUSY")
            lock.use { return block(bookPath) }
        }
    }
    private fun view(state: State) = View(if (state.status == "CREATING") "UNKNOWN" else state.status, TITLE, state.productUrl, state.lastError)

    /** 폴링은 별도 앨범 상태만 읽고 OAuth 토큰/Google 서버를 읽지 않는다. */
    @Synchronized fun status(): View? {
        if (props.googlePhotos.tokenFile.isBlank()) return null
        return try { val book = read(); book.connections[book.activeConnection]?.let(::view) }
        catch (error: PublicationFailure) { View("FAILED", TITLE, null, error.code) }
    }

    @Synchronized fun ensure(connection: String): GooglePhotosPublisher.Album = locked { bookPath ->
        val book = read(bookPath)
        val previous = book.connections[connection]
        if (previous?.status == "READY") {
            if (book.activeConnection != connection) save(book.copy(activeConnection = connection), bookPath)
            return@locked GooglePhotosPublisher.Album(requireNotNull(previous.id), previous.productUrl)
        }
        if (previous?.status in listOf("CREATING", "UNKNOWN"))
            throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "ALBUM_CONFIRMATION_REQUIRED")
        // 요청 전 intent 저장. 응답 유실/프로세스 중단 후 같은 이름의 앨범을 자동 반복 생성하지 않는다.
        val intent = book.copy(activeConnection = connection, connections = book.connections + (connection to State("CREATING")))
        save(intent, bookPath)
        try {
            val album = publisher.createAlbum(TITLE, connection)
            try { save(intent.copy(connections = intent.connections + (connection to State("READY", album.id, album.productUrl))), bookPath) }
            catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "ALBUM_RESULT_NOT_SAVED") }
            album
        } catch (error: Exception) {
            val failure = error as? PublicationFailure ?: PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "ALBUM_CREATE_FAILED")
            val uncertain = failure.kind == PublicationFailure.Kind.UNCERTAIN
            runCatching { save(intent.copy(connections = intent.connections + (connection to State(if (uncertain) "UNKNOWN" else "FAILED", lastError = failure.code))), bookPath) }
            if (uncertain) throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "ALBUM_CONFIRMATION_REQUIRED")
            throw failure
        }
    }

    /** 기존 media ID만 앨범에 연결한다. bytes 업로드/media 생성/큐 재등록은 하지 않는다. */
    @Synchronized fun organize(queue: GooglePhotosPublicationQueue): Organized {
        val connection = publisher.albumConnectionId()
        val album = ensure(connection)
        val entries = queue.completedForAlbum(connection)
        var included = 0
        val failed = mutableListOf<Long>()
        var stopped: String? = null
        for (batch in entries.chunked(50)) {
            try { publisher.addToAlbum(album.id, batch.map { it.second }, connection); included += batch.size }
            catch (error: PublicationFailure) {
                if (error.kind != PublicationFailure.Kind.PERMANENT || error.code !in listOf("HTTP_400", "HTTP_404")) { stopped = error.code; break }
                // Google에서 지운 항목 하나가 전체 batch를 막으면 나머지 유효한 이력은 개별 연결한다.
                for ((assetId, mediaId) in batch) {
                    try { publisher.addToAlbum(album.id, listOf(mediaId), connection); included++ }
                    catch (single: PublicationFailure) {
                        if (single.kind != PublicationFailure.Kind.PERMANENT) { stopped = single.code; break }
                        failed += assetId
                    }
                }
                if (stopped != null) break
            }
        }
        return Organized(included, failed, stopped, View("READY", TITLE, album.productUrl, null))
    }

    @Synchronized fun resolve(albumId: String?, productUrl: String?, confirmedNotCreated: Boolean) = locked { bookPath ->
        val connection = publisher.albumConnectionId()
        val book = read(bookPath)
        require(book.connections[connection]?.status in listOf("CREATING", "UNKNOWN")) { "결과 확인이 필요한 앨범이 없습니다." }
        val updated = if (!albumId.isNullOrBlank()) book.connections + (connection to State("READY", albumId.trim(), productUrl))
            else { require(confirmedNotCreated) { "Google Photos에서 앨범이 생성되지 않았음을 확인하세요." }; book.connections - connection }
        save(book.copy(activeConnection = connection, connections = updated), bookPath)
    }
}
