package com.samreader.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class InkTool { SELECT, PEN, ERASER, LINE, BEZIER, ELLIPSE, RECTANGLE }
enum class EraserMode { STROKE, AREA }
data class InkSettings(
    val colorArgb: Long = 0xFF1F5E66,
    val widthNormalized: Float = 0.003f,
    val pressureEnabled: Boolean = true,
    val tool: InkTool = InkTool.PEN,
    val penEnabled: Boolean = true,
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserRadiusNormalized: Float = 0.025f,
    val shapeFromCenter: Boolean = false,
    val shapeSnapEnabled: Boolean = true,
)

class InkSettingsRepository(private val dao: SamReaderDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(InkSettings())
    val settings = _settings.asStateFlow()
    init {
        scope.launch {
            dao.observeAppSettings().collect { rows ->
                val map = rows.associate { it.key to it.value }
                _settings.value = InkSettings(
                    colorArgb = map[COLOR]?.toLongOrNull() ?: 0xFF1F5E66,
                    widthNormalized = map[WIDTH]?.toFloatOrNull() ?: 0.003f,
                    pressureEnabled = map[PRESSURE]?.toBooleanStrictOrNull() ?: true,
                    tool = map[TOOL]?.let { runCatching { InkTool.valueOf(it) }.getOrNull() } ?: InkTool.PEN,
                    penEnabled = map[PEN_ENABLED]?.toBooleanStrictOrNull() ?: true,
                    eraserMode = map[ERASER_MODE]?.let { runCatching { EraserMode.valueOf(it) }.getOrNull() } ?: EraserMode.STROKE,
                    eraserRadiusNormalized = map[ERASER_SIZE]?.toFloatOrNull() ?: 0.025f,
                    shapeFromCenter = map[SHAPE_CENTER]?.toBooleanStrictOrNull() ?: false,
                    shapeSnapEnabled = map[SHAPE_SNAP]?.toBooleanStrictOrNull() ?: true,
                )
            }
        }
    }
    suspend fun update(value: InkSettings) = dao.upsertAppSettings(
        listOf(
            AppSettingEntity(COLOR, value.colorArgb.toString()), AppSettingEntity(WIDTH, value.widthNormalized.toString()),
            AppSettingEntity(PRESSURE, value.pressureEnabled.toString()), AppSettingEntity(TOOL, value.tool.name),
            AppSettingEntity(PEN_ENABLED, value.penEnabled.toString()), AppSettingEntity(ERASER_MODE, value.eraserMode.name),
            AppSettingEntity(ERASER_SIZE, value.eraserRadiusNormalized.toString()),
            AppSettingEntity(SHAPE_CENTER, value.shapeFromCenter.toString()), AppSettingEntity(SHAPE_SNAP, value.shapeSnapEnabled.toString()),
        ),
    )
    companion object {
        private const val COLOR = "ink.color"; private const val WIDTH = "ink.width"; private const val PRESSURE = "ink.pressure"; private const val TOOL = "ink.tool"
        private const val PEN_ENABLED = "ink.enabled"; private const val ERASER_MODE = "ink.eraser.mode"; private const val ERASER_SIZE = "ink.eraser.size"
        private const val SHAPE_CENTER = "ink.shape.center"; private const val SHAPE_SNAP = "ink.shape.snap"
    }
}
