package com.samreader.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a single tap selects, in both the PDF view and the reading view. */
enum class TapTarget { SENTENCE, WORD }

enum class ReadingTheme { SYSTEM, LIGHT, SEPIA, DARK }

enum class ReadingFont { SERIF, SANS }

/** Colour of the translation line shown under each sentence in the reading view. */
enum class TranslationTone { MUTED, ACCENT, WARM }

data class ReadingSettings(
    val tapTarget: TapTarget = TapTarget.SENTENCE,
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.6f,
    val font: ReadingFont = ReadingFont.SERIF,
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val showTranslation: Boolean = true,
    /** Translation font size relative to [fontSize]. */
    val translationScale: Float = .82f,
    val translationTone: TranslationTone = TranslationTone.MUTED,
) {
    companion object {
        val FONT_SIZE_RANGE = 13f..30f
        val LINE_HEIGHT_RANGE = 1.2f..2.2f
        val TRANSLATION_SCALE_RANGE = .6f..1f
    }
}

class ReadingSettingsRepository(private val dao: SamReaderDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(ReadingSettings())
    val settings = _settings.asStateFlow()

    init {
        scope.launch {
            dao.observeAppSettings().collect { rows ->
                val map = rows.associate { it.key to it.value }
                val defaults = ReadingSettings()
                _settings.value = ReadingSettings(
                    tapTarget = map[TAP_TARGET].enumOr(defaults.tapTarget),
                    fontSize = map[FONT_SIZE]?.toFloatOrNull()?.coerceIn(ReadingSettings.FONT_SIZE_RANGE) ?: defaults.fontSize,
                    lineHeight = map[LINE_HEIGHT]?.toFloatOrNull()?.coerceIn(ReadingSettings.LINE_HEIGHT_RANGE) ?: defaults.lineHeight,
                    font = map[FONT].enumOr(defaults.font),
                    theme = map[THEME].enumOr(defaults.theme),
                    showTranslation = map[SHOW_TRANSLATION]?.toBooleanStrictOrNull() ?: defaults.showTranslation,
                    translationScale = map[TRANSLATION_SCALE]?.toFloatOrNull()
                        ?.coerceIn(ReadingSettings.TRANSLATION_SCALE_RANGE) ?: defaults.translationScale,
                    translationTone = map[TRANSLATION_TONE].enumOr(defaults.translationTone),
                )
            }
        }
    }

    suspend fun update(value: ReadingSettings) = dao.upsertAppSettings(
        listOf(
            AppSettingEntity(TAP_TARGET, value.tapTarget.name),
            AppSettingEntity(FONT_SIZE, value.fontSize.toString()),
            AppSettingEntity(LINE_HEIGHT, value.lineHeight.toString()),
            AppSettingEntity(FONT, value.font.name),
            AppSettingEntity(THEME, value.theme.name),
            AppSettingEntity(SHOW_TRANSLATION, value.showTranslation.toString()),
            AppSettingEntity(TRANSLATION_SCALE, value.translationScale.toString()),
            AppSettingEntity(TRANSLATION_TONE, value.translationTone.name),
        ),
    )

    private inline fun <reified T : Enum<T>> String?.enumOr(default: T): T =
        this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val TAP_TARGET = "reading.tap"
        const val FONT_SIZE = "reading.font.size"
        const val LINE_HEIGHT = "reading.line.height"
        const val FONT = "reading.font"
        const val THEME = "reading.theme"
        const val SHOW_TRANSLATION = "reading.translation.show"
        const val TRANSLATION_SCALE = "reading.translation.scale"
        const val TRANSLATION_TONE = "reading.translation.tone"
    }
}
