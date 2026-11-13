package com.tauslop.dicomviewer.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.tauslop.dicom.DicomParser
import com.tauslop.dicom.ScannedFile
import com.tauslop.dicom.SeriesBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Recursively scans a SAF tree, parses DICOM headers and builds series. */
class FolderScanner(private val resolver: ContentResolver) {

    suspend fun scan(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit): SeriesBuilder.Result<Uri> =
        withContext(Dispatchers.IO) {
            val files = listFiles(treeUri)
            onProgress(0, files.size)
            val done = AtomicInteger()
            val notDicom = AtomicInteger()
            val io = Dispatchers.IO.limitedParallelism(4)
            val scanned = coroutineScope {
                files.map { uri ->
                    async(io) {
                        ensureActive()
                        val r = try {
                            resolver.openInputStream(uri)?.use { ScannedFile(uri, DicomParser.parse(it, stopAtPixelData = true)) }
                        } catch (e: Exception) {
                            null
                        }
                        if (r == null) notDicom.incrementAndGet()
                        onProgress(done.incrementAndGet(), files.size)
                        r
                    }
                }.awaitAll().filterNotNull()
            }
            SeriesBuilder.build(scanned, notDicom.get())
        }

    /** All document URIs under the tree, depth first. Uses raw DocumentsContract queries (fast). */
    private fun listFiles(treeUri: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        val stack = ArrayDeque<String>()
        stack += DocumentsContract.getTreeDocumentId(treeUri)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)
        while (stack.isNotEmpty()) {
            val parent = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (c.getString(1) == Document.MIME_TYPE_DIR) stack += id
                    else out += DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        return out
    }
}
