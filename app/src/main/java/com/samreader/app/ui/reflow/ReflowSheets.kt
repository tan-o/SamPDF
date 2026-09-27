package com.samreader.app.ui.reflow

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.samreader.app.data.ReadingFont
import com.samreader.app.data.ReadingSettings
import com.samreader.app.data.ReadingTheme
import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.TranslationTone
import com.samreader.app.document.PdfPageRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The "Aa" panel. [onChange] previews every adjustment live; [onDismiss] ends the session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStyleSheet(settings: ReadingSettings, onChange: (ReadingSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LabeledSlider("字号", "${settings.fontSize.roundToInt()}", settings.fontSize, ReadingSettings.FONT_SIZE_RANGE, steps = 16) {
                onChange(settings.copy(fontSize = it.roundToInt().toFloat()))
            }
            LabeledSlider("行距", "%.1f".format(settings.lineHeight), settings.lineHeight, ReadingSettings.LINE_HEIGHT_RANGE, steps = 9) {
                onChange(settings.copy(lineHeight = (it * 10).roundToInt() / 10f))
            }
            Text("字体", style = MaterialTheme.typography.labelLarge)
            Choice(listOf(ReadingFont.SERIF to "衬线", ReadingFont.SANS to "无衬线"), settings.font) { onChange(settings.copy(font = it)) }
            Text("主题", style = MaterialTheme.typography.labelLarge)
            Choice(
                listOf(ReadingTheme.SYSTEM to "系统", ReadingTheme.LIGHT to "浅色", ReadingTheme.SEPIA to "护眼", ReadingTheme.DARK to "深色"),
                settings.theme,
            ) { onChange(settings.copy(theme = it)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("逐句显示译文", style = MaterialTheme.typography.labelLarge)
                    Text("译文以较小字号显示在每句原文下方", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.showTranslation, { onChange(settings.copy(showTranslation = it)) })
            }
            if (settings.showTranslation) {
                LabeledSlider(
                    "译文字号", "${(settings.translationScale * 100).roundToInt()}%", settings.translationScale,
                    ReadingSettings.TRANSLATION_SCALE_RANGE, steps = 7,
                ) { onChange(settings.copy(translationScale = (it * 20).roundToInt() / 20f)) }
                Text("译文颜色", style = MaterialTheme.typography.labelLarge)
                Choice(
                    listOf(TranslationTone.MUTED to "灰色", TranslationTone.ACCENT to "青色", TranslationTone.WARM to "暖色"),
                    settings.translationTone,
                ) { onChange(settings.copy(translationTone = it)) }
            }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var position by remember(current) { mutableFloatStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(position, { position = it; onChange(it) }, valueRange = range, steps = steps)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** The paper's section headings; tapping one jumps there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(headings: List<SentenceEntity>, onSelect: (SentenceEntity) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (headings.isEmpty()) {
            Text(
                "解析结果中没有识别到章节标题",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(headings, key = SentenceEntity::id) { heading ->
                Text(
                    heading.displayText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(heading) }.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** A figure or table at full resolution, with pinch zoom, pan and save. */
@Composable
fun FigureViewer(filePath: String, crop: PageCrop, onSave: (String, ByteArray) -> Unit, onDismiss: () -> Unit) {
    var bitmap by remember(crop) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(crop) { bitmap = PdfPageRenderer.renderRegion(filePath, crop.page, crop.rect, FIGURE_WIDTH_PIXELS) }
    DisposableEffect(bitmap) {
        val shown = bitmap
        onDispose { shown?.recycle() }
    }
    var scale by remember(crop) { mutableFloatStateOf(1f) }
    var offset by remember(crop) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "第 ${crop.page + 1} 页图表",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(crop) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = image != null, onClick = {
                    val bytes = ByteArrayOutputStream().use { output ->
                        image?.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                    onSave("第${crop.page + 1}页-图表.png", bytes)
                }) { Text("保存", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
            }
        }
    }
}

private const val FIGURE_WIDTH_PIXELS = 2400
