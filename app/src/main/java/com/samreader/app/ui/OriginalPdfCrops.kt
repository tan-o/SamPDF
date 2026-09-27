package com.samreader.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.samreader.app.data.NormalizedRect
import com.samreader.app.data.SentenceEntity
import com.samreader.app.document.PdfPageRenderer

private class SentenceCrop(val page: Int, val area: NormalizedRect, val lines: List<NormalizedRect>, val bitmap: Bitmap)

/**
 * The sentence as typeset in the PDF, one crop per page it spans, with the sentence's own line
 * fragments highlighted. This is the reference for checking a transcription.
 */
@Composable
internal fun OriginalPdfSentenceCrops(filePath: String, sentence: SentenceEntity) {
    var crops by remember(filePath, sentence.id, sentence.regions) { mutableStateOf<List<SentenceCrop>>(emptyList()) }
    var error by remember(filePath, sentence.id, sentence.regions) { mutableStateOf<String?>(null) }
    LaunchedEffect(filePath, sentence.id, sentence.regions) {
        runCatching { renderSentenceCrops(filePath, sentence) }
            .onSuccess { rendered -> crops = rendered; error = null }
            .onFailure { failure -> error = failure.message ?: "PDF 原图读取失败" }
    }
    DisposableEffect(crops) {
        val shown = crops
        onDispose { shown.forEach { it.bitmap.recycle() } }
    }
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = .22f)
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        crops.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("正在从 PDF 原页裁取…", style = MaterialTheme.typography.bodySmall)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            crops.forEach { crop ->
                if (crops.size > 1) Text("第 ${crop.page + 1} 页", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.fillMaxWidth().aspectRatio(crop.bitmap.width.toFloat() / crop.bitmap.height.coerceAtLeast(1))) {
                    Image(
                        bitmap = crop.bitmap.asImageBitmap(),
                        contentDescription = "第 ${crop.page + 1} 页 PDF 原文",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val width = crop.area.right - crop.area.left
                        val height = crop.area.bottom - crop.area.top
                        crop.lines.forEach { line ->
                            drawRect(
                                color = highlight,
                                topLeft = Offset(
                                    (line.left - crop.area.left) / width * size.width,
                                    (line.top - crop.area.top) / height * size.height,
                                ),
                                size = Size(
                                    (line.right - line.left) / width * size.width,
                                    (line.bottom - line.top) / height * size.height,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun renderSentenceCrops(filePath: String, sentence: SentenceEntity): List<SentenceCrop> {
    val pages = sentence.regions.split('|').mapNotNull { it.substringBefore(',').toIntOrNull() }.distinct()
    return pages.mapNotNull { page ->
        val lines = sentence.decodedRegions(page).ifEmpty { return@mapNotNull null }
        val area = NormalizedRect(
            (lines.minOf { it.left } - PADDING_X).coerceAtLeast(0f),
            (lines.minOf { it.top } - PADDING_Y).coerceAtLeast(0f),
            (lines.maxOf { it.right } + PADDING_X).coerceAtMost(1f),
            (lines.maxOf { it.bottom } + PADDING_Y).coerceAtMost(1f),
        )
        SentenceCrop(page, area, lines, PdfPageRenderer.renderRegion(filePath, page, area, CROP_WIDTH_PIXELS))
    }
}

private const val PADDING_X = .02f
private const val PADDING_Y = .012f
private const val CROP_WIDTH_PIXELS = 1400
