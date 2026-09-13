package com.homephoto.server.mcp

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.MediaType
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import org.springframework.web.servlet.view.RedirectView

@RestController
@ConditionalOnExpression("'${'$'}{homephoto.mcp.enabled:false}' == 'true' && '${'$'}{homephoto.mcp.mode:local}' == 'oauth'")
class PhotoOAuthController(private val props: PhotoMcpProperties, private val db: PhotoOAuthDatabase) {
    @GetMapping("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")
    fun resourceMetadata() = mapOf("resource" to "${props.baseUrl}/mcp", "authorization_servers" to listOf(props.baseUrl),
        "scopes_supported" to listOf("photos:read"), "bearer_methods_supported" to listOf("header"))

    @GetMapping("/oauth/login", produces = [MediaType.TEXT_HTML_VALUE])
    fun login(request: HttpServletRequest): String = page("Home Photo 연결", """
        <p>사진 검색과 미리보기를 허용하려면 소유자 계정으로 로그인해 주세요.</p>
        ${if (request.getParameter("error") != null) "<p role='alert'>로그인 정보를 확인해 주세요.</p>" else ""}
        <form method="post" action="/oauth/login">${csrf(request)}
        <p><label>계정 <input name="username" autocomplete="username" required></label></p>
        <p><label>비밀번호 <input name="password" type="password" autocomplete="current-password" required></label></p>
        <button type="submit">로그인</button></form>
    """)

    @GetMapping("/oauth/connections", produces = [MediaType.TEXT_HTML_VALUE])
    fun connections(request: HttpServletRequest): String = page("ChatGPT 사진 연결 관리", """
        <p>연결을 해제하면 발급된 접근 권한과 미리보기가 무효화돼요. 다시 사용하려면 ChatGPT에서 계정을 연결해 주세요.</p>
        <form method="post" action="/oauth/revoke-all">${csrf(request)}<button>모든 사진 연결 해제</button></form>
        <form method="post" action="/oauth/logout">${csrf(request)}<button>로그아웃</button></form>
    """)

    @GetMapping("/oauth/consent", produces = [MediaType.TEXT_HTML_VALUE])
    fun consent(request: HttpServletRequest): String {
        fun value(name: String) = HtmlUtils.htmlEscape(request.getParameter(name).orEmpty())
        return page("ChatGPT에 사진 조회 허용", """
            <p>ChatGPT가 촬영일로 사진을 검색하고 미리보기를 표시할 수 있도록 허용할까요?</p>
            <p>사진 업로드·수정·삭제 권한은 포함되지 않아요. 연결 관리 화면에서 언제든 해제할 수 있어요.</p>
            <form method="post" action="/oauth2/authorize">${csrf(request)}
            <input type="hidden" name="client_id" value="${value("client_id")}">
            <input type="hidden" name="state" value="${value("state")}">
            <button name="scope" value="photos:read">사진 검색과 미리보기 허용</button>
            <button type="submit">취소</button></form>
        """)
    }

    @PostMapping("/oauth/revoke-all")
    fun revokeAll(): RedirectView {
        org.springframework.transaction.support.TransactionTemplate(
            org.springframework.jdbc.datasource.DataSourceTransactionManager(db.jdbc.dataSource!!)).executeWithoutResult {
            db.jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", props.oauth.owner)
            db.jdbc.update("DELETE FROM oauth2_authorization_consent WHERE principal_name = ?", props.oauth.owner)
        }
        return RedirectView("/oauth/connections")
    }

    private fun csrf(request: HttpServletRequest): String {
        val token = request.getAttribute(CsrfToken::class.java.name) as CsrfToken
        return "<input type=\"hidden\" name=\"${HtmlUtils.htmlEscape(token.parameterName)}\" value=\"${HtmlUtils.htmlEscape(token.token)}\">"
    }
    private fun page(title: String, body: String) = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title></head><body><main><h1>$title</h1>$body</main></body></html>"""
}
