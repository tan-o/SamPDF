package com.samreader.app.document

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samreader.app.SamReaderApplication
import com.samreader.app.data.DocumentStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfRecognitionIntegrationTest {
    @Test
    fun recognizesProvidedResearchPaperThroughProductionPipeline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val remotePath = InstrumentationRegistry.getArguments().getString("pdfPath")
            ?: error("Pass -e pdfPath /sdcard/Android/data/com.samreader.app/files/paper.pdf")
        val sourcePdf = File(remotePath)
        require(sourcePdf.isFile && sourcePdf.length() > 0L) { "Test PDF is missing or empty: $remotePath" }
        val localPdf = File(context.cacheDir, "recognition-integration.pdf")
        sourcePdf.inputStream().use { input -> localPdf.outputStream().use(input::copyTo) }

        val hash = MessageDigest.getInstance("SHA-256").digest(localPdf.readBytes())
            .joinToString("") { "%02x".format(it) }
        val container = (context.applicationContext as SamReaderApplication).container
        val existing = container.database.dao().getDocumentByHash(hash)
        val documentId = if (existing == null) {
            container.documents.importPdf(Uri.fromFile(localPdf)).documentId
        } else {
            if (existing.isTrashed) container.documents.restoreDocument(existing.id)
            container.documents.reparseDocument(existing.id, aiContextEnabled = false)
            existing.id
        }

        val document = withTimeout(300_000) {
            while (true) {
                val current = container.database.dao().getDocument(documentId)
                    ?: error("Imported document disappeared")
                if (current.status == DocumentStatus.READY || current.status == DocumentStatus.FAILED) {
                    return@withTimeout current
                }
                delay(500)
            }
            error("unreachable")
        }
        val sentences = container.database.dao().getDocumentSentences(documentId)
        val recognized = sentences.joinToString("\n") { it.originalText }
        Log.i(
            "SamReaderPdfTest",
            "document=${document.title} status=${document.status} pages=${document.pageCount} " +
                "sentences=${sentences.size} characters=${recognized.length}\n${recognized.take(1500)}",
        )

        assertEquals(document.errorMessage, DocumentStatus.READY, document.status)
        assertEquals(10, document.pageCount)
        assertTrue("No sentences were stored", sentences.isNotEmpty())
        assertTrue(
            "Expected visible title or section heading was not recognized",
            recognized.contains("Terahertz metalens", ignoreCase = true) ||
                recognized.contains("THEORETICAL CONSIDERATION", ignoreCase = true),
        )
    }
}
