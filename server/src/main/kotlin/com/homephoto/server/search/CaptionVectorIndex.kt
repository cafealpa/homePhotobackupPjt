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
    fun count(): Int { val reader = readers.acquire(); try { return reader.indexReader.numDocs() - reader.count(TermQuery(Term("kind", "document"))) } finally { readers.release(reader) } }
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
    data class DocumentHit(val id: Long, val revision: Long, val score: Float)
    fun documentCount(): Int { val s=readers.acquire(); try { return s.count(TermQuery(Term("kind","document"))) } finally { readers.release(s) } }
    fun replaceDocument(id: Long, revision: Long, vectors: List<FloatArray>, type: String = "OTHER", date: String? = null) {
        val docs=vectors.mapIndexed { chunk, vector ->
            require(vector.size==CaptionTextEncoder.DIMENSION && vector.all { it.isFinite() })
            Document().apply {
                add(StringField("kind","document",Field.Store.NO))
                add(StringField("document_type",type,Field.Store.NO))
                date?.let { add(StringField("document_date",it,Field.Store.NO)) }
                add(StringField("document_asset",id.toString(),Field.Store.YES))
                add(StoredField("revision",revision))
                add(StringField("id","document:$id:$chunk",Field.Store.NO))
                add(KnnFloatVectorField("document_vector",vector,VectorSimilarityFunction.DOT_PRODUCT))
            }
        }
        if(docs.isEmpty()) writer.deleteDocuments(Term("document_asset",id.toString()))
        else writer.updateDocuments(Term("document_asset",id.toString()),docs)
    }
    fun searchDocuments(vector: FloatArray, type: String = "", from: String = "", to: String = ""): List<DocumentHit> {
        val s=readers.acquire()
        val filter=BooleanQuery.Builder().add(TermQuery(Term("kind","document")),BooleanClause.Occur.FILTER)
        if(type.isNotBlank()) filter.add(TermQuery(Term("document_type",type)),BooleanClause.Occur.FILTER)
        if(from.isNotBlank() || to.isNotBlank()) filter.add(TermRangeQuery.newStringRange("document_date",from.ifBlank { null },to.ifBlank { null },true,true),BooleanClause.Occur.FILTER)
        try { return s.search(KnnFloatVectorQuery("document_vector",vector,200,filter.build()),200).scoreDocs.map {
            val doc=s.storedFields().document(it.doc)
            DocumentHit(doc.get("document_asset").toLong(),doc.getField("revision").numericValue().toLong(),it.score)
        } } finally { readers.release(s) }
    }
    override fun close() { readers.close(); writer.close(); directory.close() }
}
