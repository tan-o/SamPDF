package com.samreader.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        SentenceEntity::class,
        TranslationEntity::class,
        AnnotationStrokeEntity::class,
        SentenceNoteStrokeEntity::class,
        VocabularyEntity::class,
        AppSettingEntity::class,
        FolderEntity::class,
        TagEntity::class,
        DocumentTagCrossRef::class,
        PageLayoutBlockEntity::class,
        PageEvidenceEntity::class,
        SentenceSpanEntity::class,
        AiCorrectionReviewEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): SamReaderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationStatus TEXT NOT NULL DEFAULT 'NOT_STARTED'")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationTotal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationCorrectedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationError TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationCostCurrency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationCostAmount TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationPromptTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN fullTranslationCompletionTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_correction_reviews (
                        sentenceId TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        pageNumber INTEGER NOT NULL,
                        pdfOriginalText TEXT NOT NULL,
                        parsedText TEXT NOT NULL,
                        proposedText TEXT NOT NULL,
                        translatedText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sentenceId) REFERENCES sentences(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_correction_reviews_documentId " +
                        "ON ai_correction_reviews(documentId)",
                )
            }
        }
    }
}
