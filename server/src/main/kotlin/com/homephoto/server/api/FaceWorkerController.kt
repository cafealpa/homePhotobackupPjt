package com.homephoto.server.api

import com.homephoto.server.worker.FaceWorker
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class FaceWorkerController(private val worker: FaceWorker) {
    @GetMapping("/api/v1/faces/status") fun status() = worker.status()
}
