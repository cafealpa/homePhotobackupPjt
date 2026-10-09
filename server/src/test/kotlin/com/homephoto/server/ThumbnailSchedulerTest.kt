package com.homephoto.server

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.*
import com.homephoto.server.worker.ThumbnailWorker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ThumbnailSchedulerTest {
    @TempDir lateinit var temp: Path
    @Test fun `slow thumbnail never holds the shared scheduler and has no duplicate claim`() {
        val thumbnails=mock(ThumbnailService::class.java)
        val queue=mock(JobQueueService::class.java)
        val started=CountDownLatch(1);val release=CountDownLatch(1);val claims=AtomicInteger()
        `when`(queue.claim("THUMBNAIL")).thenAnswer {
            if(claims.incrementAndGet()==1) JobQueueService.Claimed(1,1,"hash","original.jpg","PHOTO",0) else null
        }
        doAnswer { started.countDown();check(release.await(5,TimeUnit.SECONDS));Unit }.`when`(thumbnails).generate("hash","original.jpg","PHOTO")
        val worker=ThumbnailWorker(thumbnails,AppProperties(temp,"test",thumbnailThreads=1),queue)
        val caller=Executors.newSingleThreadExecutor()
        try {
            caller.submit { worker.tick() }.get(1,TimeUnit.SECONDS)
            assertTrue(started.await(3,TimeUnit.SECONDS))
            caller.submit { worker.tick() }.get(1,TimeUnit.SECONDS)
            assertEquals(1,claims.get())
        } finally { release.countDown();caller.shutdownNow();worker.shutdown() }
    }
}
