package com.samreader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.samreader.app.data.InkTool
import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.SentenceNoteStrokeEntity

/**
 * A sentence tapped in the reading view. The PDF original comes first so the transcription
 * below it can be checked against it, and corrected in place when they differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SentenceSheet(
    sentence: SentenceEntity,
    filePath: String,
    translation: TranslationState,
    notes: List<SentenceNoteStrokeEntity>,
    onTranslate: () -> Unit,
    onRetry: () -> Unit,
    onSave: (String) -> Unit,
    onOpenNote: () -> Unit,
    onLocateInPdf: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(sentence.id, sentence.displayText) { mutableStateOf(sentence.displayText) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PDF 原文 · 第 ${sentence.pageNumber + 1} 页",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLocateInPdf) { Text("在 PDF 中定位") }
            }
            OriginalPdfSentenceCrops(filePath, sentence)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (sentence.correctedText != null) "解析文本（已修正）" else "解析文本（与原文不符可直接修改）") },
                minLines = 2,
                maxLines = 8,
            )
            if (draft.trim() != sentence.displayText) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) { Text("保存修改") }
                    TextButton(onClick = { draft = sentence.displayText }) { Text("还原") }
                }
            }
            HorizontalDivider()
            when (translation) {
                TranslationState.Hidden -> OutlinedButton(onClick = onTranslate) { Text("翻译这一句") }
                TranslationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("结合论文上下文翻译中…")
                }
                is TranslationState.Ready -> Text(translation.result.text, style = MaterialTheme.typography.bodyLarge)
                is TranslationState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(translation.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            if (notes.isNotEmpty()) {
                Text("句子笔记", style = MaterialTheme.typography.labelMedium)
                InkPreview(
                    notes.map {
                        RenderStroke(
                            it.id, parsePoints(it.points), Color(it.colorArgb), it.widthNormalized, it.pressureEnabled,
                            InkTool.valueOf(it.tool), parsePoints(it.controlPoints),
                        )
                    },
                    Modifier.fillMaxWidth().height(80.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("论文原文", sentence.displayText))
                }) { Text("复制原文") }
                if (translation is TranslationState.Ready) TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("译文", translation.result.text))
                }) { Text("复制译文") }
                TextButton(onClick = onOpenNote) { Text("画板") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
