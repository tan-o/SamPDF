package com.samreader.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

object DocumentStatus {
    const val QUEUED = "QUEUED"
    const val INDEXING = "INDEXING"
    const val PAUSED = "PAUSED"
    const val CANCELED = "CANCELED"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

object TextSource {
    const val HYBRID_PDF_VISUAL = "HYBRID_PDF_VISUAL"
}

object EvidenceChannel {
    const val PDF_ANDROID = "PDF_ANDROID"
    const val PDF_RUST = "PDF_RUST"
    const val VISUAL_LAYOUT = "VISUAL_LAYOUT"
    const val VISUAL_OCR = "VISUAL_OCR"
    const val VISUAL_FORMULA = "VISUAL_FORMULA"
}

object EvidenceKind {
    const val RAW_TEXT_CONTENT = "RAW_TEXT_CONTENT"
    const val TEXT_REGION = "TEXT_REGION"
    const val WORD = "WORD"
    const val LAYOUT_BLOCK = "LAYOUT_BLOCK"
    const val OCR_LINE = "OCR_LINE"
    const val OCR_GLYPH = "OCR_GLYPH"
    const val IMAGE_ALT = "IMAGE_ALT"
    const val FORMULA_REGION = "FORMULA_REGION"
    const val FORMULA_LATEX = "FORMULA_LATEX"
}

object SentenceSpanKind {
    const val TEXT = "TEXT"
    const val INLINE_FORMULA = "INLINE_FORMULA"
    const val DISPLAY_FORMULA = "DISPLAY_FORMULA"
}

object AiContextStatus {
    const val NOT_REQUESTED = "NOT_REQUESTED"
    const val SKIPPED = "SKIPPED"
    const val PENDING = "PENDING"
    const val ANALYZING = "ANALYZING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

object FullTranslationStatus {
    const val NOT_STARTED = "NOT_STARTED"
    const val RUNNING = "RUNNING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

@Entity(tableName = "documents", indices = [Index(value = ["sha256"], unique = true)])
data class DocumentEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val sha256: String,
    val importedAt: Long,
    val lastOpenedAt: Long,
    val pageCount: Int,
    val processedPages: Int = 0,
    val status: String = DocumentStatus.QUEUED,
    val errorMessage: String? = null,
    val aiContextStatus: String = AiContextStatus.NOT_REQUESTED,
    val aiContextSummary: String? = null,
    val aiContextError: String? = null,
    val aiContextCostCurrency: String = "",
    val aiContextCostAmount: String = "0",
    val aiContextPromptTokens: Int = 0,
    val aiContextCompletionTokens: Int = 0,
    @ColumnInfo(defaultValue = "'NOT_STARTED'") val fullTranslationStatus: String = FullTranslationStatus.NOT_STARTED,
    @ColumnInfo(defaultValue = "0") val fullTranslationCompleted: Int = 0,
    @ColumnInfo(defaultValue = "0") val fullTranslationTotal: Int = 0,
    @ColumnInfo(defaultValue = "0") val fullTranslationCorrectedCount: Int = 0,
    val fullTranslationError: String? = null,
    @ColumnInfo(defaultValue = "''") val fullTranslationCostCurrency: String = "",
    @ColumnInfo(defaultValue = "'0'") val fullTranslationCostAmount: String = "0",
    @ColumnInfo(defaultValue = "0") val fullTranslationPromptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val fullTranslationCompletionTokens: Int = 0,
    val folderId: String? = null,
    val isTrashed: Boolean = false,
)

object LayoutBlockType {
    const val DOCUMENT_TITLE = "DOCUMENT_TITLE"
    const val AUTHOR = "AUTHOR"
    const val CONTENTS = "CONTENTS"
    const val SECTION_TITLE = "SECTION_TITLE"
    const val PARAGRAPH = "PARAGRAPH"
    const val ABSTRACT = "ABSTRACT"
    const val REFERENCE = "REFERENCE"
    const val FOOTNOTE = "FOOTNOTE"
    const val SIDEBAR = "SIDEBAR"
    const val CAPTION = "CAPTION"
    const val IMAGE = "IMAGE"
    const val CHART = "CHART"
    const val TABLE = "TABLE"
    const val HEADER = "HEADER"
    const val FOOTER = "FOOTER"
    const val PAGE_NUMBER = "PAGE_NUMBER"
    const val EQUATION = "EQUATION"
}

@Entity(
    tableName = "page_layout_blocks",
    primaryKeys = ["documentId", "pageNumber", "position"],
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class PageLayoutBlockEntity(
    val documentId: String,
    val pageNumber: Int,
    val position: Int,
    val type: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
)

/** Raw observations retained before PDF-text and visual channels are fused. */
@Entity(
    tableName = "page_evidence",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["documentId", "pageNumber", "channel", "position"], unique = true),
        Index("documentId"),
    ],
)
data class PageEvidenceEntity(
    @androidx.room.PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val channel: String,
    val kind: String,
    val position: Int,
    val parentId: String? = null,
    val blockType: String? = null,
    val modelId: String? = null,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val imagePng: ByteArray? = null,
)

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "document_tags", primaryKeys = ["documentId", "tagId"])
data class DocumentTagCrossRef(val documentId: String, val tagId: String)

data class DocumentTagName(val documentId: String, val tagId: String, val name: String)

@Entity(
    tableName = "pages",
    primaryKeys = ["documentId", "pageNumber"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    val documentId: String,
    val pageNumber: Int,
    val widthPoints: Float,
    val heightPoints: Float,
    val source: String,
    val textQuality: Float,
)

@Entity(
    tableName = "sentences",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("documentId"),
        Index(value = ["documentId", "pageNumber", "position"], unique = true),
    ],
)
data class SentenceEntity(
    @androidx.room.PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val position: Int,
    val originalText: String,
    val correctedText: String? = null,
    val regions: String,
    val source: String,
    val confidence: Float,
) {
    val displayText: String get() = correctedText ?: originalText

    fun decodedRegions(page: Int = pageNumber): List<NormalizedRect> = regions.split('|').mapNotNull { encoded ->
        val values = encoded.split(',').mapNotNull(String::toFloatOrNull)
        values.takeIf { it.size == 5 && it[0].toInt() == page }?.let {
            NormalizedRect(it[1], it[2], it[3], it[4])
        }
    }
}

@Entity(
    tableName = "sentence_spans",
    primaryKeys = ["sentenceId", "position"],
    foreignKeys = [ForeignKey(
        entity = SentenceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sentenceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sentenceId")],
)
data class SentenceSpanEntity(
    val sentenceId: String,
    val position: Int,
    val kind: String,
    val text: String,
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Entity(
    tableName = "translations",
    foreignKeys = [
        ForeignKey(
            entity = SentenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranslationEntity(
    @androidx.room.PrimaryKey val sentenceId: String,
    val sourceText: String,
    val translatedText: String,
    val updatedAt: Long,
    val promptTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val completionTokens: Int,
    val costCurrency: String,
    val costAmount: String,
)

data class SentenceCorrection(
    val sentenceId: String,
    val correctedText: String?,
)

@Entity(
    tableName = "ai_correction_reviews",
    foreignKeys = [ForeignKey(
        entity = SentenceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sentenceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class AiCorrectionReviewEntity(
    @androidx.room.PrimaryKey val sentenceId: String,
    val documentId: String,
    val pageNumber: Int,
    val pdfOriginalText: String,
    val parsedText: String,
    val proposedText: String,
    val translatedText: String,
    val createdAt: Long,
)

@Entity(
    tableName = "annotation_strokes",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "pageNumber", "createdAt"])],
)
data class AnnotationStrokeEntity(
    @androidx.room.PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val points: String,
    val colorArgb: Long,
    val widthNormalized: Float,
    val pressureEnabled: Boolean,
    val tool: String,
    val controlPoints: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "sentence_note_strokes",
    foreignKeys = [
        ForeignKey(
            entity = SentenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sentenceId", "createdAt"])],
)
data class SentenceNoteStrokeEntity(
    @androidx.room.PrimaryKey val id: String,
    val sentenceId: String,
    val points: String,
    val colorArgb: Long,
    val widthNormalized: Float,
    val pressureEnabled: Boolean,
    val tool: String,
    val controlPoints: String = "",
    val createdAt: Long,
)

@Entity(tableName = "vocabulary", indices = [Index(value = ["normalizedWord"], unique = true)])
data class VocabularyEntity(
    @androidx.room.PrimaryKey val id: String,
    val word: String,
    val normalizedWord: String,
    val note: String,
    val sourceSentenceId: String?,
    val createdAt: Long,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @androidx.room.PrimaryKey val key: String,
    val value: String,
)
