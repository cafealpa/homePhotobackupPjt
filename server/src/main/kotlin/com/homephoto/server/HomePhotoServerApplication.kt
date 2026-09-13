package com.homephoto.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class HomePhotoServerApplication

fun main(args: Array<String>) {
    if ("--initialize-photo-oauth" in args) {
        com.homephoto.server.mcp.PhotoOAuthSetup.run(args)
        return
    }
    runApplication<HomePhotoServerApplication>(*args)
}
