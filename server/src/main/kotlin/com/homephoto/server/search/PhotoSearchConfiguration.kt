package com.homephoto.server.search

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("homephoto.search")
data class PhotoSearchProperties(val enabled: Boolean = false,
    val modelDir: String = "models/siglip2", val indexDir: String = "")

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotoSearchProperties::class)
class PhotoSearchConfiguration
