package com.samreader.app

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.samreader.app.data.AppDatabase
import com.samreader.app.data.DocumentRepository
import com.samreader.app.data.DeepSeekSettingsRepository
import com.samreader.app.data.TranslationRepository
import com.samreader.app.data.InkSettingsRepository
import com.samreader.app.data.IndexingSettingsRepository
import com.samreader.app.data.ParsingDebugSettingsRepository

class AppContainer(context: Context) {
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "samreader-v8.db",
    ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

    val deepSeekSettings = DeepSeekSettingsRepository(database.dao())
    val inkSettings = InkSettingsRepository(database.dao())
    val indexingSettings = IndexingSettingsRepository(database.dao())
    val parsingDebugSettings = ParsingDebugSettingsRepository(database.dao())

    val documents = DocumentRepository(
        context = context,
        dao = database.dao(),
        workManager = WorkManager.getInstance(context),
    )

    val translations = TranslationRepository(database.dao(), deepSeekSettings)
}
