package com.samreader.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SamReaderDao {
    @Query("SELECT * FROM documents WHERE isTrashed = :trashed ORDER BY lastOpenedAt DESC")
    fun observeDocuments(trashed: Boolean): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getDocumentByHash(sha256: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: DocumentEntity)

    @Delete
    suspend fun deleteDocument(document: DocumentEntity)

    @Query("UPDATE documents SET isTrashed = :trashed WHERE id = :id")
    suspend fun setDocumentTrashed(id: String, trashed: Boolean)

    @Query("UPDATE documents SET folderId = :folderId WHERE id = :id")
    suspend fun setDocumentFolder(id: String, folderId: String?)

    @Query("UPDATE documents SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun setDocumentsFolder(ids: List<String>, folderId: String?)

    @Query("UPDATE documents SET isTrashed = 1 WHERE id IN (:ids)")
    suspend fun trashDocuments(ids: List<String>)

    @Query("UPDATE documents SET isTrashed = 0 WHERE id IN (:ids)")
    suspend fun restoreDocuments(ids: List<String>)

    @Query("UPDATE documents SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query(
        """
        UPDATE documents
        SET status = :status,
            processedPages = :processedPages,
            errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun updateDocumentProgress(
        id: String,
        status: String,
        processedPages: Int,
        errorMessage: String? = null,
    )

    @Query(
        """
        UPDATE documents
        SET status = :newStatus,
            processedPages = :processedPages,
            errorMessage = :errorMessage
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun updateDocumentProgressIfStatus(
        id: String,
        expectedStatus: String,
        newStatus: String,
        processedPages: Int,
        errorMessage: String? = null,
    ): Int

    @Query("UPDATE documents SET aiContextStatus = :status, aiContextError = NULL WHERE id = :id")
    suspend fun setAiContextStatus(id: String, status: String)

    @Query("UPDATE documents SET aiContextStatus = :newStatus WHERE id = :id AND aiContextStatus = :expected")
    suspend fun transitionAiContext(id: String, expected: String, newStatus: String): Int

    @Query("UPDATE documents SET aiContextStatus = :status, aiContextSummary = :summary, aiContextError = :error, aiContextCostCurrency = :currency, aiContextCostAmount = :amount, aiContextPromptTokens = :promptTokens, aiContextCompletionTokens = :completionTokens WHERE id = :id")
    suspend fun finishAiContext(id: String, status: String, summary: String?, error: String?, currency: String = "", amount: String = "0", promptTokens: Int = 0, completionTokens: Int = 0)

    @Query(
        """
        UPDATE documents SET
            fullTranslationStatus = :status,
            fullTranslationCompleted = :completed,
            fullTranslationTotal = :total,
            fullTranslationCorrectedCount = :correctedCount,
            fullTranslationError = :error,
            fullTranslationCostCurrency = :currency,
            fullTranslationCostAmount = :amount,
            fullTranslationPromptTokens = :promptTokens,
            fullTranslationCompletionTokens = :completionTokens
        WHERE id = :id
        """,
    )
    suspend fun updateFullTranslation(
        id: String,
        status: String,
        completed: Int,
        total: Int,
        correctedCount: Int,
        error: String? = null,
        currency: String = "",
        amount: String = "0",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
    )

    @Query("SELECT * FROM sentences WHERE documentId = :documentId ORDER BY pageNumber, position")
    fun observeDocumentSentences(documentId: String): Flow<List<SentenceEntity>>

    @Query("SELECT * FROM sentences WHERE documentId = :documentId ORDER BY pageNumber, position")
    suspend fun getDocumentSentences(documentId: String): List<SentenceEntity>

    @Query("SELECT * FROM page_layout_blocks WHERE documentId = :documentId AND pageNumber = :pageNumber ORDER BY position")
    fun observePageLayoutBlocks(documentId: String, pageNumber: Int): Flow<List<PageLayoutBlockEntity>>

    @Query("SELECT * FROM sentences WHERE id = :id")
    fun observeSentence(id: String): Flow<SentenceEntity?>

    @Query("DELETE FROM sentences WHERE documentId = :documentId AND pageNumber = :pageNumber")
    suspend fun deleteSentencesForPage(documentId: String, pageNumber: Int)

    @Query("DELETE FROM sentences WHERE documentId = :documentId")
    suspend fun deleteSentences(documentId: String)

    @Query("DELETE FROM page_layout_blocks WHERE documentId = :documentId AND pageNumber = :pageNumber")
    suspend fun deleteLayoutBlocksForPage(documentId: String, pageNumber: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayoutBlocks(blocks: List<PageLayoutBlockEntity>)

    @Query("DELETE FROM page_evidence WHERE documentId = :documentId AND pageNumber = :pageNumber")
    suspend fun deleteEvidenceForPage(documentId: String, pageNumber: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageEvidence(evidence: List<PageEvidenceEntity>)

    @Query("SELECT * FROM page_evidence WHERE documentId = :documentId AND pageNumber = :pageNumber ORDER BY channel, position")
    suspend fun getPageEvidence(documentId: String, pageNumber: Int): List<PageEvidenceEntity>

    @Query(
        """
        SELECT * FROM page_evidence
        WHERE documentId = :documentId AND pageNumber = :pageNumber
          AND kind IN ('LAYOUT_BLOCK', 'OCR_LINE', 'FORMULA_REGION', 'FORMULA_LATEX')
        ORDER BY channel, position
        """,
    )
    fun observePageDebugEvidence(documentId: String, pageNumber: Int): Flow<List<PageEvidenceEntity>>

    @Upsert
    suspend fun upsertPage(page: PageEntity)

    @Upsert
    suspend fun insertSentences(sentences: List<SentenceEntity>)

    @Query("DELETE FROM sentences WHERE documentId = :documentId AND pageNumber = :pageNumber AND id NOT IN (:retainedIds)")
    suspend fun deleteStaleSentencesForPage(documentId: String, pageNumber: Int, retainedIds: List<String>)

    @Transaction
    suspend fun replacePage(
        page: PageEntity,
        blocks: List<PageLayoutBlockEntity>,
        evidence: List<PageEvidenceEntity>,
        sentences: List<SentenceEntity>,
    ) {
        deleteLayoutBlocksForPage(page.documentId, page.pageNumber)
        deleteEvidenceForPage(page.documentId, page.pageNumber)
        upsertPage(page)
        if (blocks.isNotEmpty()) insertLayoutBlocks(blocks)
        if (evidence.isNotEmpty()) insertPageEvidence(evidence)
        if (sentences.isNotEmpty()) {
            insertSentences(sentences)
            deleteStaleSentencesForPage(page.documentId, page.pageNumber, sentences.map(SentenceEntity::id))
        }
    }

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePages(documentId: String)

    @Query("DELETE FROM page_layout_blocks WHERE documentId = :documentId")
    suspend fun deleteLayoutBlocks(documentId: String)

    @Query("DELETE FROM page_evidence WHERE documentId = :documentId")
    suspend fun deleteEvidence(documentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentenceSpans(spans: List<SentenceSpanEntity>)

    @Query("SELECT * FROM sentence_spans WHERE sentenceId = :sentenceId ORDER BY position")
    suspend fun getSentenceSpans(sentenceId: String): List<SentenceSpanEntity>

    @Transaction
    suspend fun replaceDocumentSentences(sentences: List<SentenceEntity>, spans: List<SentenceSpanEntity>) {
        if (sentences.isNotEmpty()) insertSentences(sentences)
        if (spans.isNotEmpty()) insertSentenceSpans(spans)
    }

    @Transaction
    suspend fun resetDocumentIndex(documentId: String, aiContextStatus: String) {
        deleteSentences(documentId)
        deleteLayoutBlocks(documentId)
        deleteEvidence(documentId)
        deletePages(documentId)
        updateDocumentProgress(documentId, DocumentStatus.QUEUED, 0, null)
        finishAiContext(documentId, aiContextStatus, null, null)
        updateFullTranslation(
            id = documentId,
            status = FullTranslationStatus.NOT_STARTED,
            completed = 0,
            total = 0,
            correctedCount = 0,
        )
    }

    @Query("UPDATE sentences SET correctedText = :correctedText WHERE id = :sentenceId")
    suspend fun correctSentence(sentenceId: String, correctedText: String?)

    @Query("SELECT * FROM translations WHERE sentenceId = :sentenceId")
    suspend fun getTranslation(sentenceId: String): TranslationEntity?

    @Upsert
    suspend fun upsertTranslation(translation: TranslationEntity)

    @Query("SELECT * FROM ai_correction_reviews WHERE documentId = :documentId ORDER BY pageNumber, createdAt")
    fun observeAiCorrectionReviews(documentId: String): Flow<List<AiCorrectionReviewEntity>>

    @Upsert
    suspend fun upsertAiCorrectionReviews(reviews: List<AiCorrectionReviewEntity>)

    @Query("DELETE FROM ai_correction_reviews WHERE sentenceId IN (:sentenceIds)")
    suspend fun deleteAiCorrectionReviews(sentenceIds: List<String>)

    @Query("DELETE FROM ai_correction_reviews WHERE sentenceId = :sentenceId")
    suspend fun deleteAiCorrectionReview(sentenceId: String)

    @Query(
        """
        UPDATE documents SET fullTranslationCorrectedCount = (
            SELECT COUNT(*) FROM sentences
            WHERE documentId = :documentId AND correctedText IS NOT NULL
        ) WHERE id = :documentId
        """,
    )
    suspend fun refreshFullTranslationCorrectedCount(documentId: String)

    @Transaction
    suspend fun applyFullTranslationBatch(
        corrections: List<SentenceCorrection>,
        translations: List<TranslationEntity>,
        reviews: List<AiCorrectionReviewEntity>,
    ) {
        if (translations.isNotEmpty()) deleteAiCorrectionReviews(translations.map(TranslationEntity::sentenceId))
        corrections.forEach { correction ->
            correctSentence(correction.sentenceId, correction.correctedText)
        }
        translations.forEach { translation -> upsertTranslation(translation) }
        if (reviews.isNotEmpty()) upsertAiCorrectionReviews(reviews)
    }

    @Transaction
    suspend fun resolveAiCorrectionReview(review: AiCorrectionReviewEntity, accept: Boolean) {
        if (accept) {
            correctSentence(
                review.sentenceId,
                review.proposedText.takeUnless { it == getSentenceOriginalText(review.sentenceId) },
            )
            val existing = getTranslation(review.sentenceId)
            upsertTranslation(
                existing?.copy(
                    sourceText = review.proposedText,
                    translatedText = review.translatedText,
                    updatedAt = System.currentTimeMillis(),
                ) ?: TranslationEntity(
                    sentenceId = review.sentenceId,
                    sourceText = review.proposedText,
                    translatedText = review.translatedText,
                    updatedAt = System.currentTimeMillis(),
                    promptTokens = 0,
                    cacheHitTokens = 0,
                    cacheMissTokens = 0,
                    completionTokens = 0,
                    costCurrency = "",
                    costAmount = "0",
                ),
            )
        }
        deleteAiCorrectionReview(review.sentenceId)
        refreshFullTranslationCorrectedCount(review.documentId)
    }

    @Query("SELECT originalText FROM sentences WHERE id = :sentenceId")
    suspend fun getSentenceOriginalText(sentenceId: String): String?

    @Query(
        """
        SELECT * FROM annotation_strokes
        WHERE documentId = :documentId AND pageNumber = :pageNumber
        ORDER BY createdAt
        """,
    )
    fun observeStrokes(documentId: String, pageNumber: Int): Flow<List<AnnotationStrokeEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStroke(stroke: AnnotationStrokeEntity)

    @Upsert
    suspend fun upsertStrokes(strokes: List<AnnotationStrokeEntity>)

    @Query("DELETE FROM annotation_strokes WHERE id = :id")
    suspend fun deleteStroke(id: String)

    @Query("DELETE FROM annotation_strokes WHERE id IN (:ids)")
    suspend fun deleteStrokes(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStrokes(strokes: List<AnnotationStrokeEntity>)

    @Transaction
    suspend fun replaceStrokes(ids: List<String>, strokes: List<AnnotationStrokeEntity>) {
        if (ids.isNotEmpty()) deleteStrokes(ids)
        if (strokes.isNotEmpty()) insertStrokes(strokes)
    }

    @Query(
        """
        DELETE FROM annotation_strokes
        WHERE id = (
            SELECT id FROM annotation_strokes
            WHERE documentId = :documentId AND pageNumber = :pageNumber
            ORDER BY createdAt DESC LIMIT 1
        )
        """,
    )
    suspend fun deleteLastStroke(documentId: String, pageNumber: Int)

    @Query("SELECT * FROM sentence_note_strokes WHERE sentenceId = :sentenceId ORDER BY createdAt")
    fun observeSentenceNoteStrokes(sentenceId: String): Flow<List<SentenceNoteStrokeEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSentenceNoteStroke(stroke: SentenceNoteStrokeEntity)

    @Upsert
    suspend fun upsertSentenceNoteStrokes(strokes: List<SentenceNoteStrokeEntity>)

    @Query("DELETE FROM sentence_note_strokes WHERE id = :id")
    suspend fun deleteSentenceNoteStroke(id: String)

    @Query("DELETE FROM sentence_note_strokes WHERE id IN (:ids)")
    suspend fun deleteSentenceNoteStrokes(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSentenceNoteStrokes(strokes: List<SentenceNoteStrokeEntity>)

    @Transaction
    suspend fun replaceSentenceNoteStrokes(ids: List<String>, strokes: List<SentenceNoteStrokeEntity>) {
        if (ids.isNotEmpty()) deleteSentenceNoteStrokes(ids)
        if (strokes.isNotEmpty()) insertSentenceNoteStrokes(strokes)
    }

    @Query("DELETE FROM sentence_note_strokes WHERE sentenceId = :sentenceId")
    suspend fun clearSentenceNote(sentenceId: String)

    @Query(
        "DELETE FROM sentence_note_strokes WHERE id = (SELECT id FROM sentence_note_strokes WHERE sentenceId = :sentenceId ORDER BY createdAt DESC LIMIT 1)",
    )
    suspend fun deleteLastSentenceNoteStroke(sentenceId: String)

    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeVocabulary(): Flow<List<VocabularyEntity>>

    @Upsert
    suspend fun upsertVocabulary(item: VocabularyEntity)

    @Delete
    suspend fun deleteVocabulary(item: VocabularyEntity)

    @Query("SELECT * FROM app_settings")
    fun observeAppSettings(): Flow<List<AppSettingEntity>>

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getAppSetting(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observeAppSetting(key: String): Flow<String?>

    @Upsert
    suspend fun upsertAppSettings(items: List<AppSettingEntity>)

    @Query("DELETE FROM app_settings WHERE `key` IN (:keys)")
    suspend fun deleteAppSettings(keys: List<String>)

    @Query("SELECT * FROM folders ORDER BY name")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Upsert
    suspend fun upsertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId = :id")
    suspend fun clearFolderFromDocuments(id: String)

    @Query("SELECT document_tags.documentId, tags.id AS tagId, tags.name FROM document_tags JOIN tags ON tags.id = document_tags.tagId")
    fun observeDocumentTags(): Flow<List<DocumentTagName>>

    @Upsert
    suspend fun upsertTags(tags: List<TagEntity>)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    suspend fun clearDocumentTags(documentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentTags(items: List<DocumentTagCrossRef>)

    @Query("SELECT DISTINCT sentences.id FROM sentences JOIN sentence_note_strokes ON sentence_note_strokes.sentenceId = sentences.id WHERE sentences.documentId = :documentId")
    fun observeSentenceIdsWithNotes(documentId: String): Flow<List<String>>
}
