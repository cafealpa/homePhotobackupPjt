package com.homephoto.server.service

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile
import java.util.zip.ZipFile

internal object ReleaseArtifacts {
    private val version = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")
    fun compare(a: String, b: String): Int {
        val x = version.matchEntire(a)?.groupValues ?: error("지원하지 않는 버전: $a")
        val y = version.matchEntire(b)?.groupValues ?: error("지원하지 않는 버전: $b")
        for (i in 1..3) { val c = x[i].toBigInteger().compareTo(y[i].toBigInteger()); if (c != 0) return c }
        if (x[4] == y[4]) return 0
        if (x[4].isEmpty()) return 1
        if (y[4].isEmpty()) return -1
        // 기존 rc1/rc2 태그와 SemVer rc.1 모두 자연스러운 숫자 순서로 비교한다.
        val tokens = Regex("[0-9]+|[A-Za-z-]+")
        val p = tokens.findAll(x[4]).map { it.value }.toList(); val q = tokens.findAll(y[4]).map { it.value }.toList()
        for (i in 0 until minOf(p.size, q.size)) {
            val n = p[i].toBigIntegerOrNull(); val m = q[i].toBigIntegerOrNull()
            val c = if (n != null && m != null) n.compareTo(m) else if (n != null) -1 else if (m != null) 1 else p[i].compareTo(q[i])
            if (c != 0) return c
        }
        return p.size.compareTo(q.size)
    }
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verify(path: Path, expected: String) {
        require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256 정보가 없습니다" }
        check(sha256(path).equals(expected, true)) { "다운로드 SHA-256 불일치" }
    }
    fun extract(zip: Path, target: Path) {
        ZipFile(zip.toFile()).use { archive ->
            val entries = archive.entries().asSequence().filter { !it.isDirectory && (it.name == "homephoto-server.jar" || it.name.endsWith("/homephoto-server.jar")) }.toList()
            require(entries.size == 1) { "배포 ZIP에 실행 JAR가 정확히 하나 있어야 합니다" }
            val entry = entries.single()
            require(!entry.name.startsWith('/') && !entry.name.contains('\\') && entry.name.split('/').none { it == ".." || it.contains(':') }) { "잘못된 ZIP 경로" }
            require(entry.size in 1..MAX_JAR) { "실행 JAR 크기 초과" }
            archive.getInputStream(entry).use { input -> Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; check(total <= MAX_JAR); output.write(buffer, 0, n) }
            } }
        }
    }
    fun validateJar(path: Path, tag: String) {
        JarFile(path.toFile()).use { jar ->
            check(jar.manifest?.mainAttributes?.getValue("Start-Class") == "com.homephoto.server.HomePhotoServerApplicationKt") { "HomePhoto 실행 JAR가 아닙니다" }
            val entry = jar.getJarEntry("BOOT-INF/classes/META-INF/build-info.properties") ?: error("빌드 버전 정보가 없습니다")
            val props = Properties().apply { jar.getInputStream(entry).use { load(it) } }
            check(props.getProperty("build.version") == tag.removePrefix("v")) { "릴리즈 태그와 JAR 버전이 다릅니다" }
        }
    }
    const val MAX_JAR = 150L * 1024 * 1024
}
