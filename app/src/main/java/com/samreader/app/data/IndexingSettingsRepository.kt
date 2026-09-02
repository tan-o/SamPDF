package com.samreader.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IndexingSettings(
    val keepRunningInBackground: Boolean = true,
    val showNotificationProgress: Boolean = true,
)

class IndexingSettingsRepository(private val dao: SamReaderDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(IndexingSettings())
    val settings = _settings.asStateFlow()

    init {
        scope.launch {
            dao.observeAppSettings().collect { rows ->
                val values = rows.associate { it.key to it.value }
                _settings.value = IndexingSettings(
                    keepRunningInBackground = values[BACKGROUND]?.toBooleanStrictOrNull() ?: true,
                    showNotificationProgress = values[PROGRESS]?.toBooleanStrictOrNull() ?: true,
                )
            }
        }
    }

    suspend fun update(value: IndexingSettings) {
        dao.upsertAppSettings(listOf(
            AppSettingEntity(BACKGROUND, value.keepRunningInBackground.toString()),
            AppSettingEntity(PROGRESS, value.showNotificationProgress.toString()),
        ))
    }

    companion object {
        const val BACKGROUND = "index.background.enabled"
        const val PROGRESS = "index.notification.progress"
    }
}
