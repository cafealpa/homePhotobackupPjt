package com.homephoto.server.publication

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.ProcessMonitor
import com.homephoto.server.service.ProcessStoppedException
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class GooglePhotosPublicationProcessor(
    private val props: AppProperties,
    private val queue: GooglePhotosPublicationQueue,
    private val export: GooglePhotosExport,
    private val publisher: GooglePhotosPublisher,
    private val albums: GooglePhotosPublicationAlbum,
    private val activity: com.homephoto.server.service.ServerActivity = com.homephoto.server.service.ServerActivity(),
) {
    fun process(job: GooglePhotosPublicationQueue.Claimed) {
        var creating = false
        var file: Path? = job.path?.let(Path::of)
        try {
            ProcessMonitor.stage("Google Photos 전송",job.asset.originalFilename)
            ProcessMonitor.checkpoint()
            if (!props.googlePhotos.enabled) { queue.release(job); return }
            val connection = publisher.connectionId()
            if (job.connection != null && job.connection != connection) throw PublicationFailure(PublicationFailure.Kind.AUTH, "CONNECTION_CHANGED")
            val album = albums.ensure(connection)
            val legacyVideo = job.asset.mediaType == "VIDEO" && job.version != GooglePhotosExport.VIDEO_VERSION
            if (legacyVideo) {
                file?.let(export::delete)
                file = null // 생성 전인 대표 JPEG snapshot만 원본 영상으로 교체한다. 완료/UNKNOWN은 claim되지 않는다.
            }
            val ready = !legacyVideo && job.token != null && job.tokenCreatedAt != null && queue.clock() - job.tokenCreatedAt in 0 until 23 * 60 * 60 * 1000L
            var token = if (ready) job.token else null
            if (ready) {
                if (!queue.resume(job, true, connection)) return
            } else {
                if (file != null && Files.isRegularFile(file)) {
                    if (!export.ownedPath(file) || GooglePhotosExport.sha256(file) != job.sha256)
                        throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "EXPORT_FILE_CHANGED")
                    if (!queue.resume(job, false, connection)) return
                } else {
                    val prepared = export.prepare(job.asset)
                    file = prepared.path
                    if (!queue.prepared(job, prepared, connection)) { export.delete(prepared.path); return }
                }
                if (!props.googlePhotos.enabled) { queue.release(job); return }
                val contentType = if (job.asset.mediaType == "VIDEO") GooglePhotosExport.videoContentType(job.asset.originalFilename) else "image/jpeg"
                token = ProcessMonitor.interruptible { publisher.uploadBytes(file!!, connection, contentType) }
                if (!queue.tokenSaved(job, token)) throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "UPLOAD_RESULT_NOT_SAVED")
            }
            if (!props.googlePhotos.enabled) { queue.release(job); return }
            ProcessMonitor.checkpoint()
            if (!queue.beginCreate(job)) { file?.let { runCatching { export.delete(it) } }; return }
            creating = true
            val published = publisher.createMediaItem(token!!, job.asset.originalFilename, connection, album.id)
            if (!queue.complete(job, published)) throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "CREATE_RESULT_NOT_SAVED")
            activity.success("GOOGLE_PHOTOS","사진 #${job.asset.id} 게시 완료")
            file?.let { runCatching { export.delete(it) } }
        } catch (_: ProcessStoppedException) {
            if(creating) queue.fail(job,PublicationFailure(PublicationFailure.Kind.UNCERTAIN,"CREATE_INTERRUPTED")) else queue.release(job)
        } catch (failure: PublicationFailure) {
            activity.issue("GOOGLE_PHOTOS","사진 #${job.asset.id} 게시 실패 · ${failure.code}")
            if (!creating && failure.code == "PUBLICATION_DISABLED") queue.release(job) else queue.fail(job, failure)
        } catch (_: Exception) {
            activity.issue("GOOGLE_PHOTOS",if(creating) "게시 결과를 확인하지 못했습니다. Google Photos 화면에서 확인하세요." else "게시 준비 또는 전송 실패")
            queue.fail(job, PublicationFailure(if (creating) PublicationFailure.Kind.UNCERTAIN else PublicationFailure.Kind.RETRYABLE,
                if (creating) "CREATE_PROCESS_FAILED" else "PREPARE_OR_UPLOAD_FAILED"))
        }
    }
}
