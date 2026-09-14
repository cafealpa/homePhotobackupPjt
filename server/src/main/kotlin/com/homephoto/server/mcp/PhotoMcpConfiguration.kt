package com.homephoto.server.mcp

import com.homephoto.server.service.AssetQueryService
import com.homephoto.server.service.ThumbnailService
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.core.Ordered
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotoMcpProperties::class)
@Import(PhotoSecurityBaseline::class, PhotoOAuthConfiguration::class, PhotoOAuthController::class)
class PhotoMcpConfiguration {
    @Bean
    fun photoOAuthBoundary(props: PhotoMcpProperties) = FilterRegistrationBean(PhotoOAuthBoundaryFilter(props)).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 10
        addUrlPatterns("/*")
    }
    @Bean
    fun photoMcpFilter(props: PhotoMcpProperties) = FilterRegistrationBean(PhotoMcpAccessFilter(props)).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 20
        addUrlPatterns("/mcp", "/mcp/*", "/mcp-media/*", "/mcp-dev")
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "homephoto.mcp", name = ["enabled"], havingValue = "true")
    class Enabled {
        @Bean
        fun photoPreviewService(props: PhotoMcpProperties, thumbnails: ThumbnailService,
                               grants: ObjectProvider<OAuth2AuthorizationService>): PhotoPreviewService {
            props.validate()
            return PhotoPreviewService(props, thumbnails, grants = grants.ifAvailable)
        }

        @Bean
        fun photoMcpTools(query: AssetQueryService, previews: PhotoPreviewService, props: PhotoMcpProperties,
                          semantic: ObjectProvider<com.homephoto.server.search.PhotoSemanticSearch>) =
            PhotoMcpTools(query, previews, props.publicOAuth, semantic.ifAvailable)

        @Bean
        fun photoMcpTransport(props: PhotoMcpProperties, grants: ObjectProvider<OAuth2AuthorizationService>): WebMvcStatelessServerTransport {
            props.validate()
            val validator = DefaultServerTransportSecurityValidator.builder().allowedOrigin(props.baseUrl)
            if (props.publicOAuth) validator.allowedHost(java.net.URI(props.baseUrl).rawAuthority)
            else validator.allowedHost("localhost:*").allowedHost("127.0.0.1:*").allowedHost("[::1]:*")
            return WebMvcStatelessServerTransport.builder().messageEndpoint("/mcp")
                .contextExtractor { request ->
                    if (!props.publicOAuth) io.modelcontextprotocol.common.McpTransportContext.EMPTY
                    else {
                        val principal = request.principal().orElse(null) as? org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
                        val grant = principal?.let { grants.getObject().findByToken(it.token.tokenValue,
                            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN) }
                        require(grant?.accessToken?.isActive == true) { "Active photo grant required" }
                        io.modelcontextprotocol.common.McpTransportContext.create(mapOf("photoGrantId" to grant.id))
                    }
                }
                .securityValidator(validator.build()).build()
        }

        @Bean(destroyMethod = "close")
        fun photoMcpServer(transport: WebMvcStatelessServerTransport, tools: PhotoMcpTools,
                           props: PhotoMcpProperties): McpStatelessSyncServer {
            val uri = PhotoMcpTools.GALLERY_URI
            val mime = "text/html;profile=mcp-app"
            val html = ClassPathResource("mcp/gallery.html").getContentAsString(Charsets.UTF_8)
            val resource = McpSchema.Resource.builder().uri(uri).name("homephoto-gallery").mimeType(mime).build()
            val resourceMeta = mapOf<String, Any>("ui" to mapOf("prefersBorder" to true,
                "csp" to mapOf("resourceDomains" to listOf(props.baseUrl))))
            return McpServer.sync(transport).serverInfo("homephoto", "0.1.0")
                .instructions("단일 소유자의 사진 라이브러리입니다. 연도 없는 날짜는 확인하고, 촬영일은 기존 홈 포토 타임라인 기준으로 조회하세요.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).build())
                .tools(tools.specifications())
                .resources(SyncResourceSpecification(resource) { _, _ ->
                    McpSchema.ReadResourceResult(listOf(McpSchema.TextResourceContents(uri, mime, html, resourceMeta)))
                }).build()
        }

        @Bean
        fun photoMcpRoutes(transport: WebMvcStatelessServerTransport, server: McpStatelessSyncServer,
                           mapper: com.fasterxml.jackson.databind.ObjectMapper): RouterFunction<ServerResponse> =
            transport.routerFunction.filter(PhotoMcpDiscoveryCompatibility(mapper))
    }
}
