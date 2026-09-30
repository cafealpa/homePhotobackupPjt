package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.service.AtomicFiles
import com.sun.net.httpserver.HttpServer
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 명시적으로 실행하는 로컬 초기 인증 도구. Spring 시작 시 실행되지 않는다. */
object GooglePhotosDesktopOAuth {
    const val SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
    class Session(val accessToken: String, val refreshToken: String?, val expiresAt: String, val scope: String)
    private val mapper = jacksonObjectMapper()

    @JvmStatic fun main(args: Array<String>) {
        System.setOut(PrintStream(System.out, true, UTF_8)); System.setErr(PrintStream(System.err, true, UTF_8))
        val client = System.getenv("HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON") ?: error("Desktop OAuth JSON 경로가 필요합니다.")
        val target = Path.of(System.getenv("HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON") ?: error("별도 tokens JSON 출력 경로가 필요합니다."))
        require(!Files.exists(target)) { "기존 인증 파일은 덮어쓰지 않습니다. 새 출력 경로를 지정하세요." }
        val session = authorize(Path.of(client), true)
        check(!session.refreshToken.isNullOrBlank()) { "refresh token을 받지 못했습니다. OAuth offline 동의를 확인하세요." }
        AtomicFiles.write(target.toAbsolutePath()) { temp -> mapper.writeValue(temp.toFile(), mapOf(
            "connectionId" to UUID.randomUUID().toString(), "accessToken" to session.accessToken,
            "refreshToken" to session.refreshToken, "expiresAt" to session.expiresAt, "scope" to session.scope,
        )) }
        println("별도 인증 파일 저장 완료: ${target.toAbsolutePath()}")
        println("토큰을 출력하지 않았습니다. 이 명령은 사진을 업로드하지 않습니다.")
    }

    fun authorize(clientFile: Path, offline: Boolean = false): Session {
        val installed = try { mapper.readTree(clientFile.toFile()).path("installed") }
        catch (_: Exception) { throw IllegalArgumentException("Desktop OAuth JSON을 읽지 못했습니다.") }
        require(installed.path("client_id").asText().isNotBlank()) { "Desktop 앱 OAuth JSON이 필요합니다." }
        val verifier = randomToken(); val state = randomToken()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
        val code = CompletableFuture<String>()
        val listener = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirect = "http://127.0.0.1:${listener.address.port}/"
        listener.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), UTF_8) }
            val valid = exchange.requestMethod == "GET" && exchange.requestURI.path == "/" && query["state"] == state
            val authorized = valid && !query["code"].isNullOrBlank() && query["error"] == null
            val bytes = (if (authorized) "HomePhoto authorization received. Close this tab." else "Authorization failed. Return to the console.").toByteArray(UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (authorized) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (authorized) code.complete(query.getValue("code"))
            else if (valid) code.completeExceptionally(IllegalStateException("Google OAuth 동의가 거절되었습니다."))
        }
        listener.start()
        try {
            val parameters = mutableMapOf("client_id" to installed.path("client_id").asText(), "redirect_uri" to redirect,
                "response_type" to "code", "scope" to SCOPE, "state" to state, "code_challenge" to challenge,
                "code_challenge_method" to "S256", "prompt" to if (offline) "consent select_account" else "select_account")
            if (offline) parameters["access_type"] = "offline"
            println("일반 브라우저에서 다음 주소를 열고 사용할 Google 계정으로 동의하세요 (5분 제한):")
            println("https://accounts.google.com/o/oauth2/v2/auth?" + form(parameters))
            val body = mutableMapOf("client_id" to installed.path("client_id").asText(), "code" to code.get(5, TimeUnit.MINUTES),
                "code_verifier" to verifier, "grant_type" to "authorization_code", "redirect_uri" to redirect)
            installed.path("client_secret").asText().takeIf(String::isNotBlank)?.let { body["client_secret"] = it }
            val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body))).build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "Google OAuth 토큰 교환 실패 (HTTP ${response.statusCode()})." }
            val result = try { mapper.readTree(response.body()) }
            catch (_: Exception) { throw IllegalStateException("Google OAuth 응답을 읽지 못했습니다.") }
            val scopes = result.path("scope").asText()
            check(SCOPE in scopes.split(' ')) { "업로드 scope 동의를 확인하지 못했습니다." }
            val access = result.path("access_token").asText().also { check(it.isNotBlank()) { "access token이 없습니다." } }
            return Session(access, result.path("refresh_token").asText().takeIf(String::isNotBlank),
                Instant.now().plusSeconds(result.path("expires_in").asLong(3600)).toString(), scopes)
        } finally { listener.stop(0) }
    }

    private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, UTF_8)}=${URLEncoder.encode(it.value, UTF_8)}"
    }
}
