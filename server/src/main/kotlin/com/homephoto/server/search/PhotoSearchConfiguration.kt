package com.homephoto.server.search

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.net.URI

@ConfigurationProperties("homephoto.search")
data class PhotoSearchProperties(val enabled: Boolean = false,
    val baseUrl: String = "http://127.0.0.1:18082", val token: String = "",
    val workerDir: String = "") {
    fun validate() {
        val uri = URI(baseUrl)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "homephoto.search.base-url must be a loopback HTTP origin"
        }
        require(token.length >= 32 && token.none { it.isWhitespace() }) { "homephoto.search.token needs at least 32 non-whitespace characters" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotoSearchProperties::class)
class PhotoSearchConfiguration
