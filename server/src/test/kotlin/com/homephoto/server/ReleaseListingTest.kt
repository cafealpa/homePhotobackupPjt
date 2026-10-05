package com.homephoto.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.service.ReleaseUpdateService
import com.homephoto.server.service.ServerActivity
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import java.util.Properties
import kotlin.test.*

class ReleaseListingTest {
    private val mapper = ObjectMapper()
    private val factory = StaticListableBeanFactory().apply {
        addBean("build", BuildProperties(Properties().apply { setProperty("version", "0.1.6-rc3") }))
    }
    private val service = ReleaseUpdateService(mapper, factory.getBeanProvider(BuildProperties::class.java), ServerActivity())
    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, date: String = "2026-10-05T00:00:00Z") = mapper.readTree(
        """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"published_at":"$date","assets":[],"body":"notes"}"""
    )

    @Test fun `all published versions include RC old versions and nonstandard tags`() {
        val releases = service.describe(listOf(release("v0.1.6-rc4", true), release("v0.1.6"),
            release("v0.1.5"), release("nightly", true), release("v0.1.7", draft = true)), true)
        assertEquals(setOf("v0.1.6-rc4", "v0.1.6", "v0.1.5", "nightly"), releases.map { it.tag }.toSet())
        assertTrue(releases.first { it.tag == "v0.1.6-rc4" }.newer)
        assertFalse(releases.first { it.tag == "v0.1.5" }.newer)
        assertFalse(releases.first { it.tag == "nightly" }.newer)
        assertEquals("2026-10-05T00:00:00Z", releases.first().publishedAt)
    }

    @Test fun `explicit stable filter remains supported and listing is not limited to ten`() {
        val data = (1..15).map { release("v0.0.$it") } + listOf(release("v0.1.6-rc4", true))
        assertEquals(16, service.describe(data, true).size)
        assertEquals(15, service.describe(data, false).size)
        assertTrue(service.describe(data, false).none { it.prerelease })
    }

    @Test fun `newest publication appears first`() {
        val releases = service.describe(listOf(release("v0.1.5", date = "2026-09-01T00:00:00Z"), release("v0.1.6-rc4", true)), true)
        assertEquals("v0.1.6-rc4", releases.first().tag)
    }
}
