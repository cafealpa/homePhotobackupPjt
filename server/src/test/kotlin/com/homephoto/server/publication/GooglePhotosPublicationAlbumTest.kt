package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.AppProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.*

class GooglePhotosPublicationAlbumTest {
    @TempDir lateinit var temp: Path
    private val mapper = jacksonObjectMapper()
    private fun props() = AppProperties(temp, "", googlePhotos = AppProperties.GooglePhotosProperties(tokenFile = temp.resolve("tokens.json").toString()))
    private class Fake : GooglePhotosPublisher {
        var creates = 0
        var failure: PublicationFailure? = null
        var beforeCreate: () -> Unit = {}
        override fun connectionId() = "first"
        override fun createAlbum(title: String, connectionId: String): GooglePhotosPublisher.Album {
            creates++; beforeCreate(); failure?.let { throw it }
            return GooglePhotosPublisher.Album("album-$connectionId", "https://photos.google.com/album/$connectionId")
        }
        override fun uploadBytes(file: Path, connectionId: String): String = error("no upload")
        override fun createMediaItem(uploadToken: String, fileName: String, connectionId: String, albumId: String?): GooglePhotosPublisher.Published = error("no creation")
        override fun addToAlbum(albumId: String, mediaItemIds: List<String>, connectionId: String) = error("unused")
    }

    @Test fun `album intent precedes request and saved account albums survive restart without changing tokens`() {
        val p = props(); Files.writeString(Path.of(p.googlePhotos.tokenFile), "private-token-fixture")
        val fake = Fake(); val albums = GooglePhotosPublicationAlbum(p, mapper, fake)
        fake.beforeCreate = { assertEquals("UNKNOWN", albums.status()!!.status) }
        assertEquals("album-first", albums.ensure("first").id)
        val restarted = GooglePhotosPublicationAlbum(p, mapper, fake)
        assertEquals("album-first", restarted.ensure("first").id); assertEquals(1, fake.creates)
        assertEquals("album-second", restarted.ensure("second").id)
        assertEquals("album-first", restarted.ensure("first").id); assertEquals(2, fake.creates)
        assertEquals("private-token-fixture", Files.readString(Path.of(p.googlePhotos.tokenFile)))
        assertFalse(mapper.writeValueAsString(albums.status()).contains("connection"))
    }

    @Test fun `unknown album result blocks repeated creation until explicit resolution`() {
        val p = props(); val fake = Fake()
        fake.failure = PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "HTTP_500")
        val albums = GooglePhotosPublicationAlbum(p, mapper, fake)
        assertEquals("ALBUM_CONFIRMATION_REQUIRED", assertFailsWith<PublicationFailure> { albums.ensure("first") }.code)
        val restarted = GooglePhotosPublicationAlbum(p, mapper, fake)
        assertEquals("ALBUM_CONFIRMATION_REQUIRED", assertFailsWith<PublicationFailure> { restarted.ensure("first") }.code)
        assertEquals(1, fake.creates)
        assertFailsWith<IllegalArgumentException> { restarted.resolve(null, null, false) }
        restarted.resolve("existing-app-album", "https://photos.google.com/album/existing", false)
        assertEquals("existing-app-album", restarted.ensure("first").id); assertEquals(1, fake.creates)
    }

    @Test fun `unconfirmed interrupted intent and active process lock cannot create another album`() {
        val p = props(); val fake = Fake(); val albums = GooglePhotosPublicationAlbum(p, mapper, fake)
        val book = temp.resolve("tokens.json.albums.json")
        mapper.writeValue(book.toFile(), GooglePhotosPublicationAlbum.Book("first", mapOf("first" to GooglePhotosPublicationAlbum.State("CREATING"))))
        assertEquals("ALBUM_CONFIRMATION_REQUIRED", assertFailsWith<PublicationFailure> { albums.ensure("first") }.code)
        albums.resolve(null, null, true)
        FileChannel.open(temp.resolve("tokens.json.albums.json.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { assertEquals("ALBUM_BUSY", assertFailsWith<PublicationFailure> { albums.ensure("first") }.code) }
        }
        assertEquals(0, fake.creates)
        albums.ensure("first"); assertEquals(1, fake.creates)
        Files.writeString(book, "invalid-private-fixture")
        assertEquals("ALBUM_STATE_UNREADABLE", assertFailsWith<PublicationFailure> { albums.ensure("first") }.code)
        assertEquals(1, fake.creates)
    }
}
