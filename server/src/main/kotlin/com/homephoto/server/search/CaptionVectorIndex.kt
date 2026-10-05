package com.homephoto.server.search

import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import java.nio.file.Path

/** Disk-backed HNSW. SQLite remains authoritative; this index is rebuildable. */
class CaptionVectorIndex(path: Path) : AutoCloseable {
    data class Hit(val id: Long, val fingerprint: String, val score: Float)
    private val directory = FSDirectory.open(path)
    private val writer = IndexWriter(directory, IndexWriterConfig().setRAMBufferSizeMB(32.0))
    private val readers = SearcherManager(writer, null)
    fun count(): Int { val reader = readers.acquire(); try { return reader.indexReader.numDocs() } finally { readers.release(reader) } }
    fun fingerprint(id: Long): String? {
        val searcher = readers.acquire()
        try { return searcher.search(TermQuery(Term("id", id.toString())), 1).scoreDocs.firstOrNull()
            ?.let { searcher.storedFields().document(it.doc).get("fingerprint") } }
        finally { readers.release(searcher) }
    }
    fun put(id: Long, fingerprint: String, vector: FloatArray) {
        require(vector.size == CaptionTextEncoder.DIMENSION && vector.all { it.isFinite() })
        val doc = Document()
        doc.add(StringField("id", id.toString(), Field.Store.YES))
        doc.add(StoredField("fingerprint", fingerprint))
        doc.add(KnnFloatVectorField("vector", vector, VectorSimilarityFunction.DOT_PRODUCT))
        writer.updateDocument(Term("id", id.toString()), doc)
    }
    fun delete(id: Long) { writer.deleteDocuments(Term("id", id.toString())) }
    fun commit() { writer.commit(); readers.maybeRefreshBlocking() }
    fun search(vector: FloatArray, limit: Int = 200): List<Hit> {
        require(limit in 1..200)
        val searcher = readers.acquire()
        try { return searcher.search(KnnFloatVectorQuery("vector", vector, limit), limit).scoreDocs.map {
            val doc = searcher.storedFields().document(it.doc)
            Hit(doc.get("id").toLong(), doc.get("fingerprint"), it.score)
        } } finally { readers.release(searcher) }
    }
    override fun close() { readers.close(); writer.close(); directory.close() }
}
