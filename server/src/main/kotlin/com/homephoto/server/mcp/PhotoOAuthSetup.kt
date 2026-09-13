package com.homephoto.server.mcp

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.*
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** 명시적으로 실행할 때만 새 폴더에 자격 증명을 만든다. Spring/사진 DB/서버는 시작하지 않는다. */
object PhotoOAuthSetup {
    fun run(args: Array<String>) {
        val options = args.filter { it.startsWith("--") && '=' in it }.associate {
            it.substringAfter("--").substringBefore('=') to it.substringAfter('=')
        }
        val root = Path.of(requireNotNull(options["directory"]) { "--directory=<new absolute directory> required" })
        require(root.isAbsolute && !Files.exists(root)) { "Use a new absolute directory; existing credentials are never overwritten" }
        val base = requireNotNull(options["base-url"]) { "--base-url=https://... required" }
        val redirect = requireNotNull(options["redirect-uri"]) { "--redirect-uri=<exact ChatGPT callback> required" }
        val console = requireNotNull(System.console()) { "Run this command in an interactive local terminal" }
        val password = console.readPassword("Home Photo 소유자 비밀번호 (12자 이상): ")
        val confirm = console.readPassword("비밀번호 확인: ")
        try {
            require(password.size >= 12 && password.contentEquals(confirm)) { "Passwords must match and contain at least 12 characters" }
            val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
            fun randomSecret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val secret = randomSecret()
            val oauth = PhotoOAuthProperties(passwordHash = encoder.encode(String(password)), clientSecretHash = encoder.encode(secret),
                redirectUri = redirect, stateDirectory = root.resolve("state").toString(),
                signingKeyFile = root.resolve("signing-key.json").toString(), previewKey = randomSecret())
            PhotoMcpProperties(enabled = true, mode = "oauth", baseUrl = base, oauth = oauth).validate()
            Files.createDirectory(root)
            // 비밀 파일을 쓰기 전에 새 디렉터리 접근을 현재 소유자로 제한한다.
            val acl = Files.getFileAttributeView(root, AclFileAttributeView::class.java)
            if (acl != null) {
                acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                    .setPermissions(*AclEntryPermission.entries.toTypedArray())
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build())
            } else Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
            Files.writeString(root.resolve("signing-key.json"), RSAKeyGenerator(3072).keyID(UUID.randomUUID().toString()).generate().toJSONString())
            Files.writeString(root.resolve("chatgpt-client-secret.txt"), secret)
            val properties = Properties().apply {
                setProperty("homephoto.mcp.enabled", "false")
                setProperty("homephoto.mcp.mode", "oauth")
                setProperty("homephoto.mcp.base-url", base)
                setProperty("homephoto.mcp.oauth.owner", oauth.owner)
                setProperty("homephoto.mcp.oauth.password-hash", oauth.passwordHash)
                setProperty("homephoto.mcp.oauth.client-id", oauth.clientId)
                setProperty("homephoto.mcp.oauth.client-secret-hash", oauth.clientSecretHash)
                setProperty("homephoto.mcp.oauth.redirect-uri", redirect)
                setProperty("homephoto.mcp.oauth.state-directory", oauth.stateDirectory)
                setProperty("homephoto.mcp.oauth.signing-key-file", oauth.signingKeyFile)
                setProperty("homephoto.mcp.oauth.preview-key", oauth.previewKey)
                setProperty("server.forward-headers-strategy", "none")
            }
            Files.newOutputStream(root.resolve("application-oauth.properties")).use { properties.store(it, "Home Photo OAuth - private, keep outside release archives") }
            console.printf("OAuth 준비 파일 생성 완료: %s%nMCP는 아직 비활성 상태예요. 비밀 파일은 대화나 저장소에 올리지 마세요.%n", root)
        } finally { password.fill('\u0000'); confirm.fill('\u0000') }
    }
}
