package com.samreader.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCanvasScreen(viewModel: NoteCanvasViewModel, onBack: () -> Unit) {
    val sentence by viewModel.sentence.collectAsStateWithLifecycle()
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val ink by viewModel.inkSettings.collectAsStateWithLifecycle()
    val rendered = strokes.map { RenderStroke(it.id, parsePoints(it.points), Color(it.colorArgb), it.widthNormalized, it.pressureEnabled, com.samreader.app.data.InkTool.valueOf(it.tool), parsePoints(it.controlPoints)) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                title = { Text(sentence?.displayText ?: "句子画板", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    InkToolMenu(ink, viewModel::updateInk)
                    TextButton(onClick = viewModel::undo, enabled = strokes.isNotEmpty()) { Text("撤销") }
                    TextButton(onClick = viewModel::clear, enabled = strokes.isNotEmpty()) { Text("清空") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("与 PDF 共用画笔、粗细、颜色、压感和笔画橡皮。返回自动保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            InkSurface(
                strokes = rendered,
                settings = ink,
                onStroke = viewModel::addStroke,
                onUpdateStrokes = viewModel::updateStrokes,
                onStrokeEraseAt = { x, y -> nearest(rendered, x, y)?.let(viewModel::deleteStroke) },
                onAreaErase = viewModel::areaErase,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun nearest(strokes: List<RenderStroke>, x: Float, y: Float): String? = strokes
    .map { stroke -> stroke.id to (stroke.points.minOfOrNull { (it.x - x).pow(2) + (it.y - y).pow(2) } ?: Float.MAX_VALUE) }
    .minByOrNull { it.second }?.takeIf { it.second < 0.003f }?.first
