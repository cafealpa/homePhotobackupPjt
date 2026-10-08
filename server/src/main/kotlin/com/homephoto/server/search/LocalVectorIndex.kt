package com.homephoto.server.search

import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** Rebuildable index. Visibility and fingerprints are checked against SQLite at query time. */
class LocalVectorIndex(path: Path, private val dimension: Int) : AutoCloseable {
    data class Hit(val id: Long, val assetId: Long, val fingerprint: String, val similarity: Double)
    data class Entry(val fingerprint: String, val day: Long, val vector: FloatArray, val assetId: Long)
    private val directory = FSDirectory.open(path)
    private val writer = IndexWriter(directory, IndexWriterConfig().setRAMBufferSizeMB(32.0))
    private val readers = SearcherManager(writer, null)

    private fun <T> read(block: (IndexSearcher) -> T): T {
        val reader = readers.acquire()
        try { return block(reader) } finally { readers.release(reader) }
    }
    fun count(): Long = read { it.indexReader.numDocs().toLong() }
    fun lookup(id: Long): Entry? = read { s ->
        s.search(TermQuery(Term("id", id.toString())), 1).scoreDocs.firstOrNull()?.let {
            val doc = s.storedFields().document(it.doc)
            val bytes = doc.getBinaryValue("raw")
            val vector = FloatArray(dimension)
            ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector)
            Entry(doc.get("fingerprint"), doc.getField("day_value").numericValue().toLong(), vector, doc.get("asset").toLong())
        }
    }
    fun put(id: Long, assetId: Long, fingerprint: String, day: Long, vector: FloatArray) {
        require(vector.size == dimension)
        val unit = normalized(vector)
        val bytes = ByteBuffer.allocate(dimension * 4).order(ByteOrder.LITTLE_ENDIAN)
        unit.forEach(bytes::putFloat)
        val doc = Document().apply {
            add(StringField("id", id.toString(), Field.Store.YES))
            add(StringField("asset", assetId.toString(), Field.Store.YES))
            add(StoredField("fingerprint", fingerprint))
            add(LongPoint("day", day))
            add(StoredField("day_value", day))
            add(StoredField("raw", bytes.array()))
            add(KnnFloatVectorField("vector", unit, VectorSimilarityFunction.DOT_PRODUCT))
        }
        writer.updateDocument(Term("id", id.toString()), doc)
    }
    fun delete(id: Long) { writer.deleteDocuments(Term("id", id.toString())) }
    fun commit() { writer.commit(); readers.maybeRefreshBlocking() }
    /** Only called after a complete scan, never after interruption or a failed DB page. */
    fun retain(seen: Set<Long>) = read { s ->
        var after: ScoreDoc? = null
        while (true) {
            val page = s.searchAfter(after, MatchAllDocsQuery(), 512).scoreDocs
            if (page.isEmpty()) break
            for (hit in page) {
                val id = s.storedFields().document(hit.doc).get("id").toLong()
                if (id !in seen) delete(id)
            }
            after = page.last()
        }
    }
    fun search(vector: FloatArray, limit: Int = 200, from: Long? = null, to: Long? = null, excludeAsset: Long? = null): List<Hit> {
        require(vector.size == dimension && limit in 1..200)
        val filter = BooleanQuery.Builder().add(MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
        if (from != null && to != null) filter.add(LongPoint.newRangeQuery("day", from, to), BooleanClause.Occur.FILTER)
        if (excludeAsset != null) filter.add(TermQuery(Term("asset", excludeAsset.toString())), BooleanClause.Occur.MUST_NOT)
        return read { s ->
            s.search(KnnFloatVectorQuery("vector", normalized(vector), limit, filter.build()), limit).scoreDocs.map {
                val doc = s.storedFields().document(it.doc)
                Hit(doc.get("id").toLong(), doc.get("asset").toLong(), doc.get("fingerprint"), (it.score * 2.0 - 1.0).coerceIn(-1.0, 1.0))
            }
        }
    }
    override fun close() { readers.close(); writer.close(); directory.close() }
}
