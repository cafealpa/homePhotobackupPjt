package com.homephoto.server.document

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.DatabaseMigrations
import com.homephoto.server.config.StartupJobRecovery
import com.homephoto.server.db.*
import com.homephoto.server.search.CaptionVectorIndex
import com.homephoto.server.service.JobQueueService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class DocumentTest {
    @TempDir lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var ds: HikariDataSource
    @BeforeEach fun setup() {
        ds=HikariDataSource(HikariConfig().apply { jdbcUrl="jdbc:sqlite:${dir.resolve("test.db")}"; maximumPoolSize=1 })
        db=Database.connect(ds)
        DatabaseMigrations().migrate()
    }
    @AfterEach fun close() { TransactionManager.closeAndUnregister(db); ds.close() }
    private fun asset(name: String="scan.jpg")=transaction {
        Assets.insert {
            it[hash]=name; it[mediaType]="PHOTO"; it[originalPath]=name; it[originalFilename]=name
            it[fileSize]=1; it[takenAtSource]="UPLOAD_TIME"; it[yearMonth]="2026-10"; it[createdAt]="2026-10-06"
        }[Assets.id]
    }
    private fun analysis(ocr: String="준비물: 물통", classification: String="DOCUMENT") = DocumentAnalysis.parse(ObjectMapper().valueToTree(mapOf(
        "classification" to classification,"type" to "NOTICE","title" to "현장체험학습 안내","summary" to "준비물 안내",
        "date" to "2026-10-06","issuer" to "학교","ocrText" to ocr,"keywords" to listOf("준비물"),
        "needsReview" to false,"reviewReason" to "")))

    @Test fun `migration is repeatable and queue registration is bounded and idempotent`() {
        DatabaseMigrations().migrate()
        repeat(4) { asset("$it.jpg") }
        assertFalse(DocumentRepository.enabled())
        assertEquals(2,DocumentRepository.enqueue("missing",2))
        assertEquals(2,DocumentRepository.enqueue("missing",2))
        assertEquals(0,DocumentRepository.enqueue("missing",2))
        assertEquals(4L,DocumentRepository.counts()["pending"])
        val job=JobQueueService().claim("DOCUMENT")!!
        assertEquals(0,DocumentRepository.enqueue("reanalyze",1,job.assetId))
    }
    @Test fun `literal wildcards search OCR and deletion hides every read path`() {
        val id=asset(); val other=asset("other.jpg")
        DocumentRepository.save(id,analysis("금액 100% 항목_A 번호 X123"),"test")
        DocumentRepository.save(other,analysis("다른 내용"),"test")
        assertEquals(listOf(id),DocumentRepository.lexical("100%"))
        assertEquals(listOf(id),DocumentRepository.lexical("항목_"))
        assertTrue(DocumentRepository.lexical("' OR 1=1 --").isEmpty())
        assertEquals(listOf(id),DocumentRepository.lexical("X123"))
        assertEquals(2,DocumentRepository.lexical("안내문").size)
        transaction { exec("UPDATE assets SET deleted_at='2026-10-06' WHERE id=$id") }
        assertTrue(DocumentRepository.lexical("X123").isEmpty())
        assertNull(DocumentRepository.detail(id)); assertNull(DocumentRepository.source(id))
        assertTrue(DocumentRepository.items(listOf(id)).isEmpty())
        transaction { exec("UPDATE assets SET deleted_at=NULL WHERE id=$id") }
        assertNotNull(DocumentRepository.source(id))
    }
    @Test fun `old acknowledgement cannot erase a newer OCR revision and indexing does not retrigger itself`() {
        val id=asset()
        DocumentRepository.save(id,analysis(),"test")
        val old=DocumentRepository.pending().single(); val source=DocumentRepository.source(id)!!
        DocumentRepository.save(id,analysis("수정된 준비물"),"test")
        DocumentRepository.acknowledge(old,source,listOf(source.text))
        assertEquals(1,DocumentRepository.pending().size)
        val fresh=DocumentRepository.source(id)!!
        DocumentRepository.acknowledge(DocumentRepository.pending().single(),fresh,listOf(fresh.text))
        assertTrue(DocumentRepository.pending().isEmpty())
        assertEquals(2L,DocumentRepository.items(listOf(id)).single().indexedRevision)
    }
    @Test fun `non documents are retained without embeddings and failures remain failed on restart`() {
        val id=asset()
        DocumentRepository.detected(id,"DOCUMENT")
        val queue=JobQueueService();val job=queue.claim("DOCUMENT")!!
        repeat(3) { i -> if(i>0) queue.claim("DOCUMENT"); queue.fail(job.jobId,"DOCUMENT","error") }
        StartupJobRecovery().recover()
        assertEquals(1L,DocumentRepository.counts()["failed"])
        assertNull(queue.claim("DOCUMENT"))
        DocumentRepository.save(id,analysis("","NOT_DOCUMENT"),"test")
        assertNull(DocumentRepository.source(id))
        assertTrue(DocumentRepository.lexical("학교").isEmpty())
    }
    @Test fun `RRF merges ranks once per document and rewards both retrieval paths`() {
        assertEquals(2L,DocumentController.rrf(listOf(1,2,3),listOf(2,4)).first())
        assertEquals(DocumentController.rrf(listOf(1,2),listOf(2)),DocumentController.rrf(listOf(1,1,2),listOf(2,2)))
    }
    @Test fun `long OCR tail remains in bounded overlapping chunks`() {
        val text="제목: 긴 문서\n본문:\n"+"가나다라 준비물 ".repeat(1500)+"마지막 특별 준비물"
        val chunks=DocumentChunks.split(text) { it.length }
        assertTrue(chunks.size>10)
        assertTrue(chunks.all { it.length<=480 })
        assertTrue(chunks.last().contains("마지막 특별 준비물"))
    }
    @Test fun `shared Lucene file isolates captions from document chunks and replaces all old chunks`() {
        fun v(axis: Int)=FloatArray(384) { if(it==axis) 1f else 0f }
        CaptionVectorIndex(dir.resolve("index")).use { index ->
            index.put(1,"caption",v(0)); index.replaceDocument(1,1,listOf(v(0),v(1))); index.commit()
            assertEquals(1,index.count());assertEquals(2,index.documentCount())
            assertEquals(1,index.search(v(0)).size);assertEquals(2,index.searchDocuments(v(0)).size)
            index.replaceDocument(1,2,listOf(v(2)));index.commit()
            assertEquals(1,index.documentCount());assertEquals(2L,index.searchDocuments(v(2)).single().revision)
            index.replaceDocument(1,3,emptyList());index.commit()
            assertTrue(index.searchDocuments(v(0)).isEmpty());assertEquals(1,index.count())
        }
    }
    @Test fun `invalid dates empty OCR and malformed fields are rejected`() {
        assertFailsWith<IllegalArgumentException> { analysis("") }
        val json=ObjectMapper().readTree(analysis().json) as com.fasterxml.jackson.databind.node.ObjectNode
        json.put("date","2026-02-31")
        assertFails { DocumentAnalysis.parse(json) }
    }

    @Test fun `date and document type filters apply before candidate limits`() {
        val id=asset(); DocumentRepository.save(id,analysis(),"test")
        assertEquals(listOf(id),DocumentRepository.lexical("준비물","NOTICE","2026-10-01","2026-10-31"))
        assertTrue(DocumentRepository.lexical("준비물","RECEIPT").isEmpty())
        assertEquals(0,DocumentRepository.list("documents",0,"","2027-01-01").second.size)
        val vector=FloatArray(384) { if(it==0) 1f else 0f }
        CaptionVectorIndex(dir.resolve("filtered")).use { index ->
            index.replaceDocument(id,1,listOf(vector),"NOTICE","2026-10-06")
            index.replaceDocument(999,1,listOf(vector),"RECEIPT",null); index.commit()
            assertEquals(listOf(id),index.searchDocuments(vector,"NOTICE","2026-10-01","2026-10-31").map { it.id })
            assertTrue(index.searchDocuments(vector,"","2027-01-01").isEmpty())
        }
    }

    @Test fun `document endpoints require existing API authentication and expose progress`() {
        asset()
        val props=com.homephoto.server.config.AppProperties(dir,"test")
        val controller=DocumentController(org.mockito.Mockito.mock(DocumentWorker::class.java),
            org.mockito.Mockito.mock(com.homephoto.server.search.CaptionTextSearch::class.java))
        val mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(com.homephoto.server.config.ApiKeyFilter(props)).build()
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/documents"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/documents/enqueue")
            .header("X-Api-Key","test").contentType("application/json").content("""{"limit":1}"""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/documents?filter=pending")
            .header("X-Api-Key","test"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].status").value("PENDING"))
    }
}
