package com.samreader.app.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.samreader.app.document.DocumentIndexWorker
import com.samreader.app.document.FullDocumentTranslationWorker
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DocumentRepository(
    private val context: Context,
    private val dao: SamReaderDao,
    private val workManager: WorkManager,
) {
    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments(false)
    val trashedDocuments: Flow<List<DocumentEntity>> = dao.observeDocuments(true)
    val folders: Flow<List<FolderEntity>> = dao.observeFolders()
    val documentTags: Flow<List<DocumentTagName>> = dao.observeDocumentTags()

    fun observeDocument(id: String): Flow<DocumentEntity?> = dao.observeDocument(id)

    fun observeDocumentSentences(documentId: String): Flow<List<SentenceEntity>> =
        dao.observeDocumentSentences(documentId)

    fun observeAiCorrectionReviews(documentId: String): Flow<List<AiCorrectionReviewEntity>> =
        dao.observeAiCorrectionReviews(documentId)

    fun observePageLayoutBlocks(documentId: String, pageNumber: Int): Flow<List<PageLayoutBlockEntity>> =
        dao.observePageLayoutBlocks(documentId, pageNumber)

    fun observePageDebugEvidence(documentId: String, pageNumber: Int): Flow<List<PageEvidenceEntity>> =
        dao.observePageDebugEvidence(documentId, pageNumber)

    fun observeStrokes(documentId: String, pageNumber: Int): Flow<List<AnnotationStrokeEntity>> =
        dao.observeStrokes(documentId, pageNumber)

    fun observeSentence(id: String): Flow<SentenceEntity?> = dao.observeSentence(id)

    fun observeSentenceNoteStrokes(id: String): Flow<List<SentenceNoteStrokeEntity>> =
        dao.observeSentenceNoteStrokes(id)

    fun observeSentenceIdsWithNotes(documentId: String): Flow<List<String>> =
        dao.observeSentenceIdsWithNotes(documentId)

    val vocabulary: Flow<List<VocabularyEntity>> = dao.observeVocabulary()

    suspend fun setAiContextRequested(id: String, enabled: Boolean) = dao.setAiContextStatus(
        id, if (enabled) AiContextStatus.PENDING else AiContextStatus.SKIPPED,
    )

    suspend fun beginAiContext(id: String): Boolean =
        dao.transitionAiContext(id, AiContextStatus.PENDING, AiContextStatus.ANALYZING) == 1

    suspend fun documentSentences(id: String): List<SentenceEntity> = dao.getDocumentSentences(id)

    suspend fun finishAiContext(id: String, result: AnalysisResult) =
        dao.finishAiContext(
            id, AiContextStatus.READY, result.text, null, result.costCurrency, result.costAmount,
            result.usage.promptTokens, result.usage.completionTokens,
        )

    suspend fun failAiContext(id: String, message: String) =
        dao.finishAiContext(id, AiContextStatus.FAILED, null, message)

    suspend fun translationContext(sentence: SentenceEntity): String {
        val all = dao.getDocumentSentences(sentence.documentId)
        val index = all.indexOfFirst { it.id == sentence.id }
        if (index < 0) return ""
        val nearby = all.subList((index - 2).coerceAtLeast(0), (index + 3).coerceAtMost(all.size))
        val keywords = sentence.displayText.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 5 }.toSet()
        val related = all.asSequence().filter { it.id !in nearby.map(SentenceEntity::id) }
            .map { item -> item to item.displayText.lowercase().split(Regex("[^a-z0-9]+" )).count { it in keywords } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { it.first }.toList()
        return (nearby + related).distinctBy(SentenceEntity::id)
            .joinToString("\n") { "[p.${it.pageNumber + 1}] ${it.displayText}" }
    }

    suspend fun addVocabulary(word: String, note: String, sentenceId: String?) {
        val clean = word.trim()
        require(clean.isNotEmpty()) { "单词不能为空" }
        dao.upsertVocabulary(
            VocabularyEntity(
                id = clean.lowercase(), word = clean, normalizedWord = clean.lowercase(), note = note.trim(),
                sourceSentenceId = sentenceId, createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteVocabulary(item: VocabularyEntity) = dao.deleteVocabulary(item)

    suspend fun importPdf(uri: Uri): PdfImportResult = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val title = queryDisplayName(uri)
            ?.removeSuffix(".pdf")
            ?.takeIf(String::isNotBlank)
            ?: "未命名论文"
        val directory = File(context.filesDir, "documents").apply { mkdirs() }
        val target = File(directory, "$id.pdf")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选 PDF" }
                DigestInputStream(input, digest).use { hashing -> target.outputStream().use(hashing::copyTo) }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            dao.getDocumentByHash(sha256)?.let { duplicate ->
                target.delete()
                if (!duplicate.isTrashed) error("该 PDF 已导入：《${duplicate.title}》")
                require(File(duplicate.filePath).isFile) { "回收站记录的 PDF 源文件已丢失，请先彻底删除该记录" }
                dao.setDocumentTrashed(duplicate.id, false)
                return@withContext PdfImportResult(duplicate.id, duplicate.title, restoredFromTrash = true)
            }

            val pageCount = ParcelFileDescriptor.open(
                target,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
            }
            require(pageCount > 0) { "PDF 没有可阅读页面" }

            val now = System.currentTimeMillis()
            dao.insertDocument(
                DocumentEntity(
                    id = id,
                    title = title,
                    filePath = target.absolutePath,
                    sha256 = sha256,
                    importedAt = now,
                    lastOpenedAt = now,
                    pageCount = pageCount,
                ),
            )
            enqueueIndex(id, ExistingWorkPolicy.KEEP)
            PdfImportResult(id, title, restoredFromTrash = false)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun markOpened(id: String) = dao.markOpened(id, System.currentTimeMillis())

    suspend fun correctSentence(sentence: SentenceEntity, text: String) {
        val corrected = text.trim().takeUnless { it == sentence.originalText || it.isEmpty() }
        dao.correctSentence(sentence.id, corrected)
    }

    suspend fun resolveAiCorrectionReview(review: AiCorrectionReviewEntity, accept: Boolean) =
        dao.resolveAiCorrectionReview(review, accept)

    suspend fun startFullTranslation(documentId: String) {
        val document = requireNotNull(dao.getDocument(documentId)) { "论文不存在" }
        require(document.status == DocumentStatus.READY) { "请先完成本地解析" }
        if (document.fullTranslationStatus == FullTranslationStatus.RUNNING) return
        val sentenceCount = dao.getDocumentSentences(documentId).size
        require(sentenceCount > 0) { "论文尚未识别出文字" }
        val resume = document.fullTranslationStatus == FullTranslationStatus.FAILED &&
            document.fullTranslationCompleted in 1 until sentenceCount &&
            document.fullTranslationTotal == sentenceCount
        dao.updateFullTranslation(
            id = documentId,
            status = FullTranslationStatus.RUNNING,
            completed = if (resume) document.fullTranslationCompleted else 0,
            total = sentenceCount,
            correctedCount = if (resume) document.fullTranslationCorrectedCount else 0,
            promptTokens = if (resume) document.fullTranslationPromptTokens else 0,
            completionTokens = if (resume) document.fullTranslationCompletionTokens else 0,
        )
        val request = OneTimeWorkRequestBuilder<FullDocumentTranslationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(FullDocumentTranslationWorker.DOCUMENT_ID to documentId))
            .build()
        workManager.enqueueUniqueWork(fullTranslationWorkName(documentId), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun addStroke(stroke: AnnotationStrokeEntity) = dao.insertStroke(stroke)

    suspend fun updateStrokes(strokes: List<AnnotationStrokeEntity>) = dao.upsertStrokes(strokes)

    suspend fun deleteStroke(id: String) = dao.deleteStroke(id)

    suspend fun replaceStrokes(ids: List<String>, strokes: List<AnnotationStrokeEntity>) {
        dao.replaceStrokes(ids, strokes)
    }

    suspend fun undoStroke(documentId: String, pageNumber: Int) =
        dao.deleteLastStroke(documentId, pageNumber)

    suspend fun addSentenceNoteStroke(stroke: SentenceNoteStrokeEntity) =
        dao.insertSentenceNoteStroke(stroke)

    suspend fun updateSentenceNoteStrokes(strokes: List<SentenceNoteStrokeEntity>) = dao.upsertSentenceNoteStrokes(strokes)

    suspend fun deleteSentenceNoteStroke(id: String) = dao.deleteSentenceNoteStroke(id)

    suspend fun replaceSentenceNoteStrokes(ids: List<String>, strokes: List<SentenceNoteStrokeEntity>) {
        dao.replaceSentenceNoteStrokes(ids, strokes)
    }

    suspend fun undoSentenceNoteStroke(sentenceId: String) =
        dao.deleteLastSentenceNoteStroke(sentenceId)

    suspend fun clearSentenceNote(sentenceId: String) = dao.clearSentenceNote(sentenceId)

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(id))
        workManager.cancelUniqueWork(fullTranslationWorkName(id))
        val document = dao.getDocument(id) ?: return@withContext
        dao.clearDocumentTags(id)
        dao.deleteDocument(document)
        File(document.filePath).delete()
    }

    suspend fun trashDocument(id: String) = dao.setDocumentTrashed(id, true)

    suspend fun trashDocuments(ids: List<String>) = dao.trashDocuments(ids)

    suspend fun restoreDocument(id: String) = dao.setDocumentTrashed(id, false)

    suspend fun restoreDocuments(ids: List<String>) {
        require(ids.isNotEmpty()) { "请先选择文档" }
        dao.restoreDocuments(ids)
    }

    suspend fun deleteDocuments(ids: List<String>) {
        require(ids.isNotEmpty()) { "请先选择文档" }
        ids.forEach { deleteDocument(it) }
    }

    suspend fun createFolder(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "文件夹名称不能为空" }
        dao.upsertFolder(FolderEntity(UUID.nameUUIDFromBytes(clean.lowercase().toByteArray()).toString(), clean, System.currentTimeMillis()))
    }

    suspend fun deleteFolder(id: String) {
        dao.clearFolderFromDocuments(id)
        dao.deleteFolder(id)
    }

    suspend fun organizeDocument(id: String, folderId: String?, tagNames: List<String>) {
        dao.setDocumentFolder(id, folderId)
        val clean = tagNames.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        val tags = clean.map { TagEntity(UUID.nameUUIDFromBytes(it.lowercase().toByteArray()).toString(), it) }
        if (tags.isNotEmpty()) dao.upsertTags(tags)
        dao.clearDocumentTags(id)
        if (tags.isNotEmpty()) dao.insertDocumentTags(tags.map { DocumentTagCrossRef(id, it.id) })
    }

    suspend fun moveDocuments(ids: List<String>, folderId: String?) {
        require(ids.isNotEmpty()) { "请先选择文档" }
        dao.setDocumentsFolder(ids, folderId)
    }

    suspend fun addTagsToDocuments(ids: List<String>, tagNames: List<String>) {
        val clean = tagNames.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        require(clean.isNotEmpty()) { "标签不能为空" }
        val tags = clean.map { TagEntity(UUID.nameUUIDFromBytes(it.lowercase().toByteArray()).toString(), it) }
        dao.upsertTags(tags)
        ids.forEach { documentId ->
            dao.insertDocumentTags(tags.map { DocumentTagCrossRef(documentId, it.id) })
        }
    }

    suspend fun reparseDocument(id: String, aiContextEnabled: Boolean) {
        workManager.cancelUniqueWork(workName(id)).await()
        workManager.cancelUniqueWork(fullTranslationWorkName(id)).await()
        dao.resetDocumentIndex(
            id,
            if (aiContextEnabled) AiContextStatus.PENDING else AiContextStatus.SKIPPED,
        )
        enqueueIndex(id, ExistingWorkPolicy.KEEP)
    }

    suspend fun retryLocalIndex(documentId: String) = reparseDocument(documentId, aiContextEnabled = false)

    suspend fun pauseIndex(documentId: String) {
        val before = dao.getDocument(documentId) ?: return
        if (before.status != DocumentStatus.INDEXING && before.status != DocumentStatus.QUEUED) return
        workManager.cancelUniqueWork(workName(documentId)).await()
        val current = dao.getDocument(documentId) ?: return
        dao.updateDocumentProgress(documentId, DocumentStatus.PAUSED, current.processedPages, null)
    }

    suspend fun resumeIndex(documentId: String) {
        val document = dao.getDocument(documentId) ?: return
        if (document.status != DocumentStatus.PAUSED) return
        dao.updateDocumentProgress(documentId, DocumentStatus.QUEUED, document.processedPages, null)
        enqueueIndex(documentId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancelIndex(documentId: String) {
        val before = dao.getDocument(documentId) ?: return
        if (before.status !in setOf(DocumentStatus.INDEXING, DocumentStatus.QUEUED, DocumentStatus.PAUSED)) return
        workManager.cancelUniqueWork(workName(documentId)).await()
        val current = dao.getDocument(documentId) ?: return
        dao.updateDocumentProgress(documentId, DocumentStatus.CANCELED, current.processedPages, null)
    }

    private fun enqueueIndex(id: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.DOCUMENT_ID to id))
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    private fun workName(id: String) = "document-index-$id"

    private fun fullTranslationWorkName(id: String) = "full-translation-$id"

    private fun queryDisplayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

data class PdfImportResult(
    val documentId: String,
    val title: String,
    val restoredFromTrash: Boolean,
)
