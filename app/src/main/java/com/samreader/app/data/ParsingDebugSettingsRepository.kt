package com.samreader.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ParsingDebugSettings(
    val overlayEnabled: Boolean = false,
)

object ParsingTuning {
    const val MIN_LAYOUT_CONFIDENCE = .20f
    const val MAX_LAYOUT_CONFIDENCE = .90f
    const val DEFAULT_LAYOUT_CONFIDENCE = .50f

    fun normalizeLayoutConfidence(value: Float?): Float =
        value?.takeIf(Float::isFinite)
            ?.coerceIn(MIN_LAYOUT_CONFIDENCE, MAX_LAYOUT_CONFIDENCE)
            ?: DEFAULT_LAYOUT_CONFIDENCE
}

class ParsingDebugSettingsRepository(private val dao: SamReaderDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(ParsingDebugSettings())
    val settings = _settings.asStateFlow()

    init {
        scope.launch {
            dao.observeAppSetting(DEBUG_OVERLAY).collect { value ->
                _settings.value = ParsingDebugSettings(
                    overlayEnabled = value?.toBooleanStrictOrNull() ?: false,
                )
            }
        }
    }

    fun documentLayoutConfidence(documentId: String): Flow<Float> =
        dao.observeAppSetting(layoutConfidenceKey(documentId))
            .map { ParsingTuning.normalizeLayoutConfidence(it?.toFloatOrNull()) }

    suspend fun getDocumentLayoutConfidence(documentId: String): Float =
        ParsingTuning.normalizeLayoutConfidence(
            dao.getAppSetting(layoutConfidenceKey(documentId))?.toFloatOrNull(),
        )

    suspend fun updateOverlay(enabled: Boolean) {
        dao.upsertAppSettings(listOf(AppSettingEntity(DEBUG_OVERLAY, enabled.toString())))
    }

    suspend fun updateDocumentLayoutConfidence(documentId: String, value: Float) {
        dao.upsertAppSettings(listOf(
            AppSettingEntity(
                layoutConfidenceKey(documentId),
                ParsingTuning.normalizeLayoutConfidence(value).toString(),
            ),
        ))
    }

    companion object {
        private const val DEBUG_OVERLAY = "parsing.debug.overlay.enabled"
        private fun layoutConfidenceKey(documentId: String) =
            "parsing.layout.confidence.$documentId"
    }
}
