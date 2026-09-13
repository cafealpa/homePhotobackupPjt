package com.homephoto.server.mcp

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.oauth2.core.*
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.oauth2.server.authorization.*
import org.springframework.security.oauth2.server.authorization.client.*
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.*
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** 기존 /api 인증은 ApiKeyFilter가 담당한다. 새 Spring Security 기본 로그인으로 덮어쓰지 않는다. */
@Configuration(proxyBeanMethods = false)
class PhotoSecurityBaseline {
    @Bean @Order(100)
    fun existingApplicationSecurity(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { it.anyRequest().permitAll() }.csrf { it.disable() }
        .headers { it.disable() }.logout { it.disable() }
        .requestCache { it.disable() }.securityContext { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }.build()
}

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${'$'}{homephoto.mcp.enabled:false}' == 'true' && '${'$'}{homephoto.mcp.mode:local}' == 'oauth'")
class PhotoOAuthConfiguration(props: PhotoMcpProperties, environment: org.springframework.core.env.Environment) {
    init {
        props.validate()
        require(environment.getProperty("server.forward-headers-strategy", "none").equals("none", true)) {
            "OAuth proxy validation requires server.forward-headers-strategy=none"
        }
    }
    @Bean fun photoSessionCookie() = org.springframework.boot.web.servlet.ServletContextInitializer { context ->
        context.sessionCookieConfig.name = "__Host-hp_oauth"
        context.sessionCookieConfig.isSecure = true
        context.sessionCookieConfig.isHttpOnly = true
        context.sessionCookieConfig.path = "/"
        context.sessionTimeout = 15
    }
    @Bean fun photoSameSiteCookie() = org.springframework.boot.web.servlet.server.CookieSameSiteSupplier.ofLax()
        .whenHasName("__Host-hp_oauth")
    @Bean fun ownerUsers(props: PhotoMcpProperties): UserDetailsService {
        props.validate()
        return InMemoryUserDetailsManager(User.withUsername(props.oauth.owner).password(props.oauth.passwordHash)
            .roles("PHOTO_OWNER").build())
    }

    @Bean fun oauthPasswordEncoder() = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean fun photoOAuthClients(props: PhotoMcpProperties): RegisteredClientRepository = InMemoryRegisteredClientRepository(
        RegisteredClient.withId(props.oauth.clientId).clientId(props.oauth.clientId)
            .clientName("ChatGPT 사진 읽기 연결").clientSecret(props.oauth.clientSecretHash)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(props.oauth.redirectUri).scope("photos:read")
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(2))
                .accessTokenTimeToLive(Duration.ofMinutes(10)).refreshTokenTimeToLive(Duration.ofDays(30))
                .reuseRefreshTokens(false).build()).build())

    // 별도 DataSource 빈을 등록하면 사진 DB 자동 설정을 바꿀 수 있으므로 OAuth 전용 holder 안에 둔다.
    @Bean(destroyMethod = "close") fun photoOAuthDatabase(props: PhotoMcpProperties): PhotoOAuthDatabase = PhotoOAuthDatabase(props.oauth.stateDirectory)
    @Bean fun photoAuthorizations(db: PhotoOAuthDatabase, clients: RegisteredClientRepository): OAuth2AuthorizationService =
        JdbcOAuth2AuthorizationService(db.jdbc, clients)
    @Bean fun photoConsents(db: PhotoOAuthDatabase, clients: RegisteredClientRepository): OAuth2AuthorizationConsentService =
        JdbcOAuth2AuthorizationConsentService(db.jdbc, clients)

    @Bean fun photoSigningKeys(props: PhotoMcpProperties): JWKSource<SecurityContext> {
        val key = RSAKey.parse(Files.readString(Path.of(props.oauth.signingKeyFile)))
        require(key.isPrivate && key.size() >= 2048 && !key.keyID.isNullOrBlank()) { "Persistent private RSA signing key with kid required" }
        return ImmutableJWKSet(JWKSet(key))
    }

    @Bean fun photoJwtDecoder(keys: JWKSource<SecurityContext>, props: PhotoMcpProperties,
                             authorizations: OAuth2AuthorizationService): JwtDecoder {
        val publicKey = keys.get(com.nimbusds.jose.jwk.JWKSelector(com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null)
            .single().toRSAKey().toRSAPublicKey()
        return NimbusJwtDecoder.withPublicKey(publicKey).build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(props.baseUrl),
                OAuth2TokenValidator<Jwt> { jwt ->
                    val grant = authorizations.findByToken(jwt.tokenValue, OAuth2TokenType.ACCESS_TOKEN)
                    if (jwt.audience == listOf("${props.baseUrl}/mcp") && jwt.subject == props.oauth.owner &&
                        grant?.accessToken?.isActive == true && grant.authorizedScopes.contains("photos:read")) OAuth2TokenValidatorResult.success()
                    else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
                }))
        }
    }

    @Bean fun photoTokenClaims(props: PhotoMcpProperties) = OAuth2TokenCustomizer<JwtEncodingContext> { context ->
        if (context.tokenType == OAuth2TokenType.ACCESS_TOKEN) {
            val request = context.authorization?.getAttribute<org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest>(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest::class.java.name)
            if (request?.additionalParameters?.get("resource") != "${props.baseUrl}/mcp") {
                throw OAuth2AuthenticationException(OAuth2Error("invalid_target"))
            }
            context.claims.audience(listOf("${props.baseUrl}/mcp"))
        }
    }

    @Bean fun photoAuthorizationSettings(props: PhotoMcpProperties): AuthorizationServerSettings =
        AuthorizationServerSettings.builder().issuer(props.baseUrl).build()

    @Bean @Order(1)
    fun photoAuthorizationSecurity(http: HttpSecurity): SecurityFilterChain {
        val config = OAuth2AuthorizationServerConfigurer.authorizationServer()
        http.securityMatcher(config.endpointsMatcher)
            .with(config) { it.authorizationEndpoint { endpoint -> endpoint.consentPage("/oauth/consent") }
                .authorizationServerMetadataEndpoint { endpoint ->
                endpoint.authorizationServerMetadataCustomizer { metadata -> metadata.codeChallengeMethods { methods ->
                    methods.clear(); methods.add("S256")
                } }
            } }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling { it.authenticationEntryPoint(LoginUrlAuthenticationEntryPoint("/oauth/login")) }
        return http.build()
    }

    @Bean @Order(2)
    fun photoLoginSecurity(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/oauth/**")
        .authorizeHttpRequests { it.requestMatchers("/oauth/login").permitAll().anyRequest().hasRole("PHOTO_OWNER") }
        .formLogin { it.loginPage("/oauth/login").loginProcessingUrl("/oauth/login").defaultSuccessUrl("/oauth/connections") }
        .logout { it.logoutUrl("/oauth/logout").logoutSuccessUrl("/oauth/login") }
        .build()

    @Bean @Order(3)
    fun photoResourceSecurity(http: HttpSecurity, decoder: JwtDecoder, props: PhotoMcpProperties): SecurityFilterChain = http
        .securityMatcher("/mcp", "/mcp/**")
        .csrf { it.disable() }.requestCache { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().hasAuthority("SCOPE_photos:read") }
        .oauth2ResourceServer { oauth -> oauth.jwt { it.decoder(decoder) }
            .authenticationEntryPoint { _, response, _ ->
                response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"${props.baseUrl}/.well-known/oauth-protected-resource\", scope=\"photos:read\"")
                response.status = 401
            }
        }.build()
}

class PhotoOAuthDatabase(directory: String) : AutoCloseable {
    val jdbc: JdbcTemplate
    private val dataSource: com.zaxxer.hikari.HikariDataSource
    init {
        val root = Path.of(directory).toAbsolutePath().normalize()
        Files.createDirectories(root)
        require(!root.toString().contains(';')) { "Invalid OAuth state directory" }
        val ds = com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = "jdbc:h2:file:${root.resolve("oauth").toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE"
            username = "sa"; password = ""; maximumPoolSize = 2; poolName = "homephoto-oauth"
        })
        dataSource = ds
        jdbc = JdbcTemplate(ds)
        ds.connection.use { connection ->
            for (table in listOf("oauth2_authorization", "oauth2_authorization_consent")) {
                connection.metaData.getTables(null, null, table.uppercase(), arrayOf("TABLE")).use { tables ->
                    if (!tables.next()) ResourceDatabasePopulator(ClassPathResource(
                        "org/springframework/security/oauth2/server/authorization/${table.replace('_', '-')}-schema.sql")).execute(ds)
                }
            }
        }
    }
    override fun close() = dataSource.close()
}
