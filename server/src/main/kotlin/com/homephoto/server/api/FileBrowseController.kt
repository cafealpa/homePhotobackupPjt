package com.homephoto.server.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 설정 페이지의 "폴더 찾기" 창이 쓰는 **서버 파일 시스템** 탐색 API.
 *
 * 브라우저는 서버 컴퓨터의 경로를 알 수 없어(로컬 파일 선택창은 절대경로를 주지 않는다)
 * 서버가 목록을 내려줘야 한다. 인증은 ApiKeyFilter가 담당한다(= 키를 아는 사람만 접근).
 */
@RestController
@RequestMapping("/api/v1/admin/browse")
class FileBrowseController {

    data class EntryDto(
        val name: String,
        val path: String,
        /** DIR | FILE */
        val type: String,
        val size: Long? = null,
    )

    data class BrowseDto(
        /** 지금 보고 있는 경로. null이면 최상위(드라이브 목록) */
        val path: String?,
        /** 위로 가기 대상. null이면 더 올라갈 수 없다 */
        val parent: String?,
        val entries: List<EntryDto>,
        /** 접근이 거부된 폴더 등, 목록을 못 읽었을 때의 안내 */
        val error: String? = null,
    )

    /**
     * [path]의 하위 목록. path가 비면 드라이브 목록(C:\, D:\ …)을 준다.
     * [files]=true면 파일도 포함한다 (ffmpeg 실행 파일 고르기용).
     */
    @GetMapping
    fun browse(
        @RequestParam(required = false) path: String?,
        @RequestParam(defaultValue = "false") files: Boolean,
    ): BrowseDto {
        if (path.isNullOrBlank()) return roots()

        val dir = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 경로가 아닙니다")
        if (!Files.isDirectory(dir)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다: $dir")
        }

        val entries = runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.mapNotNull { child ->
                    val isDir = runCatching { Files.isDirectory(child) }.getOrDefault(false)
                    if (!isDir && !files) return@mapNotNull null
                    // 숨김·시스템 파일은 감춘다 (윈도우의 System Volume Information 등)
                    if (runCatching { Files.isHidden(child) }.getOrDefault(false)) return@mapNotNull null
                    EntryDto(
                        name = child.fileName?.toString() ?: child.toString(),
                        path = child.toString(),
                        type = if (isDir) "DIR" else "FILE",
                        size = if (isDir) null else runCatching { Files.size(child) }.getOrNull(),
                    )
                }
            }.sortedWith(compareBy({ it.type != "DIR" }, { it.name.lowercase() }))
        }.getOrElse {
            // 권한 없는 폴더를 눌러도 창이 죽지 않게, 빈 목록 + 안내로 응답한다
            return BrowseDto(
                path = dir.toString(),
                parent = dir.parent?.toString(),
                entries = emptyList(),
                error = "이 폴더를 읽을 수 없습니다 (권한 없음)",
            )
        }

        return BrowseDto(path = dir.toString(), parent = dir.parent?.toString(), entries = entries)
    }

    /** 최상위: 드라이브 목록 */
    private fun roots(): BrowseDto {
        val entries = File.listRoots()
            .filter { runCatching { it.exists() } .getOrDefault(false) }
            .map { EntryDto(name = it.path, path = it.path, type = "DIR") }
        return BrowseDto(path = null, parent = null, entries = entries)
    }
}
