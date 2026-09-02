package com.samreader.app.ui

import android.content.*
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samreader.app.data.*
import com.samreader.app.document.PdfPageRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit, onSettings: () -> Unit, onOpenNote: (String) -> Unit, onVocabulary: () -> Unit) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val currentPage by viewModel.pageNumber.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val selectedSentence by viewModel.selectedSentence.collectAsStateWithLifecycle()
    val selectedSentenceIds by viewModel.selectedSentenceIds.collectAsStateWithLifecycle()
    val selectionAnchorPage by viewModel.selectionAnchorPage.collectAsStateWithLifecycle()
    val sentences by viewModel.sentences.collectAsStateWithLifecycle()
    val aiCorrectionReviews by viewModel.aiCorrectionReviews.collectAsStateWithLifecycle()
    val noteSentenceIds by viewModel.sentenceIdsWithNotes.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ink by viewModel.inkSettings.collectAsStateWithLifecycle()
    val parsingDebug by viewModel.parsingDebugSettings.collectAsStateWithLifecycle()
    val layoutConfidence by viewModel.layoutConfidence.collectAsStateWithLifecycle()
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomLocked by remember { mutableStateOf(false) }
    var actionModeAnchorOnScreen by remember { mutableStateOf(IntOffset.Zero) }
    val listState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val compactToolbar = LocalConfiguration.current.screenWidthDp < 720
    var toolbarOverflowExpanded by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    val imageSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingImage
        if (uri != null && bytes != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
        pendingImage = null
    }
    LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex }.collect(viewModel::setPage) }
    Scaffold(topBar = {
        Column { TopAppBar(
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            title = { Column {
                Text(document?.title ?: "读取中", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${currentPage + 1} / ${document?.pageCount ?: 1} · ${statusText(document)}", style = MaterialTheme.typography.labelSmall)
            } },
            actions = {
                InkToolMenu(ink, viewModel::updateInk)
                if (!compactToolbar) {
                    TextButton(onClick = { zoom = 1f; scope.launch { horizontalState.scrollTo(0) } }) { Text("适配") }
                    TextButton(onClick = { zoomLocked = !zoomLocked }) { Text(if (zoomLocked) "缩放锁" else "缩放开") }
                    when (document?.status) {
                        DocumentStatus.INDEXING, DocumentStatus.QUEUED -> {
                            TextButton(onClick = viewModel::pauseIndex) { Text("暂停") }
                            TextButton(onClick = viewModel::cancelIndex) { Text("取消") }
                        }
                        DocumentStatus.PAUSED -> {
                            TextButton(onClick = viewModel::resumeIndex) { Text("继续") }
                            TextButton(onClick = viewModel::cancelIndex) { Text("取消") }
                        }
                        else -> TextButton(onClick = viewModel::retryLocalIndex) { Text("本地重析") }
                    }
                    if (document?.status == DocumentStatus.READY) {
                        TextButton(
                            onClick = viewModel::startFullTranslation,
                            enabled = document?.fullTranslationStatus != FullTranslationStatus.RUNNING,
                        ) { Text(fullTranslationActionLabel(document)) }
                    }
                    if (document?.aiContextStatus == AiContextStatus.FAILED) TextButton(onClick = viewModel::retryAiContext) { Text("AI 重析") }
                    TextButton(onClick = { viewModel.undoStroke(currentPage) }) { Text("撤销") }
                    TextButton(onClick = onVocabulary) { Text("生词") }
                    TextButton(onClick = onSettings) { Text("设置") }
                } else {
                    Box {
                        TextButton(onClick = { toolbarOverflowExpanded = true }) { Text("更多") }
                        DropdownMenu(
                            expanded = toolbarOverflowExpanded,
                            onDismissRequest = { toolbarOverflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("适配屏幕") },
                                onClick = {
                                    toolbarOverflowExpanded = false
                                    zoom = 1f
                                    scope.launch { horizontalState.scrollTo(0) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (zoomLocked) "开启手势缩放" else "锁定缩放") },
                                onClick = {
                                    toolbarOverflowExpanded = false
                                    zoomLocked = !zoomLocked
                                },
                            )
                            when (document?.status) {
                                DocumentStatus.INDEXING, DocumentStatus.QUEUED -> {
                                    DropdownMenuItem(text = { Text("暂停解析") }, onClick = {
                                        toolbarOverflowExpanded = false
                                        viewModel.pauseIndex()
                                    })
                                    DropdownMenuItem(text = { Text("取消解析") }, onClick = {
                                        toolbarOverflowExpanded = false
                                        viewModel.cancelIndex()
                                    })
                                }
                                DocumentStatus.PAUSED -> {
                                    DropdownMenuItem(text = { Text("继续解析") }, onClick = {
                                        toolbarOverflowExpanded = false
                                        viewModel.resumeIndex()
                                    })
                                    DropdownMenuItem(text = { Text("取消解析") }, onClick = {
                                        toolbarOverflowExpanded = false
                                        viewModel.cancelIndex()
                                    })
                                }
                                else -> DropdownMenuItem(text = { Text("本地重新解析") }, onClick = {
                                    toolbarOverflowExpanded = false
                                    viewModel.retryLocalIndex()
                                })
                            }
                            if (document?.status == DocumentStatus.READY) {
                                DropdownMenuItem(
                                    text = { Text(fullTranslationActionLabel(document)) },
                                    enabled = document?.fullTranslationStatus != FullTranslationStatus.RUNNING,
                                    onClick = {
                                        toolbarOverflowExpanded = false
                                        viewModel.startFullTranslation()
                                    },
                                )
                            }
                            if (document?.aiContextStatus == AiContextStatus.FAILED) {
                                DropdownMenuItem(text = { Text("AI 上下文重新解析") }, onClick = {
                                    toolbarOverflowExpanded = false
                                    viewModel.retryAiContext()
                                })
                            }
                            DropdownMenuItem(text = { Text("撤销笔画") }, onClick = {
                                toolbarOverflowExpanded = false
                                viewModel.undoStroke(currentPage)
                            })
                            DropdownMenuItem(text = { Text("生词本") }, onClick = {
                                toolbarOverflowExpanded = false
                                onVocabulary()
                            })
                            DropdownMenuItem(text = { Text("设置") }, onClick = {
                                toolbarOverflowExpanded = false
                                onSettings()
                            })
                        }
                    }
                }
            },
        )
            document?.let { doc ->
                when {
                    doc.status == DocumentStatus.INDEXING -> LinearProgressIndicator(
                        progress = { doc.processedPages.toFloat() / doc.pageCount.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    doc.fullTranslationStatus == FullTranslationStatus.RUNNING -> LinearProgressIndicator(
                        progress = { doc.fullTranslationCompleted.toFloat() / doc.fullTranslationTotal.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    doc.aiContextStatus == AiContextStatus.ANALYZING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        }
    }) { padding ->
        val doc = document
        if (doc == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val viewportWidth = maxWidth
            val continuousWidth = maxWidth * zoom
            Box(Modifier.fillMaxSize().pointerInput(zoomLocked) {
                awaitEachGesture {
                    var pointersPressed: Boolean
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!zoomLocked && event.changes.count { it.pressed } >= 2) {
                            val oldZoom = zoom
                            val newZoom = (oldZoom * event.calculateZoom()).coerceIn(.8f, 3.5f)
                            val ratio = newZoom / oldZoom
                            val centroid = event.calculateCentroid()
                            zoom = newZoom
                            scope.launch {
                                horizontalState.scrollTo(((horizontalState.value + centroid.x) * ratio - centroid.x).roundToInt().coerceIn(0, horizontalState.maxValue))
                                listState.scrollBy((listState.firstVisibleItemScrollOffset + centroid.y) * (ratio - 1f))
                            }
                            event.changes.forEach { it.consume() }
                        }
                        pointersPressed = event.changes.any { it.pressed }
                    } while (pointersPressed)
                }
            }.horizontalScroll(horizontalState)) {
                Box(
                    Modifier.width(if (zoom < 1f) viewportWidth else continuousWidth),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LazyColumn(state = listState, modifier = Modifier.width(continuousWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(doc.pageCount, key = { it }) { page ->
                            PdfPageItem(
                                doc.filePath, page, viewModel, sentences, noteSentenceIds,
                                selectedSentence, selectedSentenceIds, translation, settings, ink, onOpenNote,
                                selectionAnchorPage = selectionAnchorPage,
                                debugOverlay = parsingDebug.overlayEnabled,
                                layoutConfidence = layoutConfidence,
                                onSelectionAnchor = { actionModeAnchorOnScreen = it },
                                onSaveImage = { name, bytes -> pendingImage = bytes; imageSaver.launch(name) },
                            )
                        }
                    }
                }
            }
        }
    }
    SentenceSelectionActionMode(
        active = selectedSentenceIds.isNotEmpty(),
        count = selectedSentenceIds.size,
        anchorOnScreen = actionModeAnchorOnScreen,
        onCopy = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("论文原文", viewModel.selectedSentenceText()),
            )
            viewModel.dismissSentence()
        },
        onTranslate = viewModel::translateSentenceSelection,
        onSelectAll = { viewModel.selectAllSentencesOnPage(currentPage) },
        onDismiss = viewModel::dismissSentence,
    )
    aiCorrectionReviews.firstOrNull()?.let { review ->
        val reviewSentence = sentences.firstOrNull { it.id == review.sentenceId }
        val filePath = document?.filePath
        if (reviewSentence == null || filePath == null) return@let
        AiCorrectionReviewDialog(
            review = review,
            sentence = reviewSentence,
            filePath = filePath,
            remaining = aiCorrectionReviews.size,
            onKeepParsed = { viewModel.resolveAiCorrectionReview(review, accept = false) },
            onAccept = { editedSource, editedTranslation ->
                viewModel.resolveAiCorrectionReview(
                    review.copy(
                        proposedText = editedSource.trim(),
                        translatedText = editedTranslation.trim(),
                    ),
                    accept = true,
                )
            },
        )
    }
}

@Composable
private fun AiCorrectionReviewDialog(
    review: AiCorrectionReviewEntity,
    sentence: SentenceEntity,
    filePath: String,
    remaining: Int,
    onKeepParsed: () -> Unit,
    onAccept: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var editedProposal by remember(review.sentenceId, review.proposedText) {
        mutableStateOf(review.proposedText)
    }
    var editedTranslation by remember(review.sentenceId, review.translatedText) {
        mutableStateOf(review.translatedText)
    }
    val diff = remember(review.parsedText, editedProposal) {
        changedTextRanges(review.parsedText, editedProposal)
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Column {
                Text("确认 AI 原文修改")
                Text(
                    "第 ${review.pageNumber + 1} 页 · 待确认 $remaining 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("PDF 原页内容", style = MaterialTheme.typography.labelLarge)
                    OriginalPdfSentenceCrops(filePath, sentence)
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("解析内容", style = MaterialTheme.typography.labelLarge)
                    Text(
                        highlightedReviewText(
                            review.parsedText,
                            diff.sourceChangedRanges,
                            colors.error,
                            strikeThrough = true,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = editedProposal,
                        onValueChange = { editedProposal = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DeepSeek 建议（可人工修改）") },
                        minLines = 3,
                        maxLines = 8,
                    )
                    Text("改动预览（红色为新增或替换）", style = MaterialTheme.typography.labelMedium)
                    Text(
                        highlightedReviewText(
                            editedProposal,
                            diff.proposedChangedRanges,
                            colors.error,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = editedTranslation,
                    onValueChange = { editedTranslation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("中文翻译（可人工修改）") },
                    minLines = 3,
                    maxLines = 8,
                )
                Text(
                    "删除的解析文字以红色删除线显示；DeepSeek 新增或替换的内容以红色显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAccept(editedProposal, editedTranslation) },
                enabled = editedProposal.isNotBlank() && editedTranslation.isNotBlank(),
            ) {
                Text("确认修改")
            }
        },
        dismissButton = { TextButton(onClick = onKeepParsed) { Text("保留解析内容") } },
    )
}

@Composable
private fun OriginalPdfSentenceCrops(filePath: String, sentence: SentenceEntity) {
    var crops by remember(filePath, sentence.id, sentence.regions) { mutableStateOf<List<Pair<Int, Bitmap>>>(emptyList()) }
    var error by remember(filePath, sentence.id, sentence.regions) { mutableStateOf<String?>(null) }
    LaunchedEffect(filePath, sentence.id, sentence.regions) {
        runCatching { renderOriginalPdfCrops(filePath, sentence) }
            .onSuccess { rendered ->
                crops.forEach { (_, bitmap) -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
                crops = rendered
                error = null
            }
            .onFailure { failure -> error = failure.message ?: "PDF 原图读取失败" }
    }
    DisposableEffect(filePath, sentence.id, sentence.regions) {
        onDispose { crops.forEach { (_, bitmap) -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() } }
    }
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        crops.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("正在从 PDF 原页裁取…", style = MaterialTheme.typography.bodySmall)
        }
        else -> crops.forEach { (page, bitmap) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("第 ${page + 1} 页原图", style = MaterialTheme.typography.labelSmall)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 ${page + 1} 页 PDF 原始内容",
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

private suspend fun renderOriginalPdfCrops(filePath: String, sentence: SentenceEntity): List<Pair<Int, Bitmap>> {
    val pages = sentence.regions.split('|').mapNotNull { encoded ->
        encoded.substringBefore(',').toIntOrNull()
    }.distinct()
    return pages.mapNotNull { page ->
        val regions = sentence.decodedRegions(page)
        if (regions.isEmpty()) return@mapNotNull null
        val rendered = PdfPageRenderer.render(filePath, page, widthPixels = 1800, darkReading = false)
        try {
            val paddingX = .02f
            val paddingY = .015f
            val left = ((regions.minOf { it.left } - paddingX) * rendered.width).roundToInt()
                .coerceIn(0, rendered.width - 1)
            val top = ((regions.minOf { it.top } - paddingY) * rendered.height).roundToInt()
                .coerceIn(0, rendered.height - 1)
            val right = ((regions.maxOf { it.right } + paddingX) * rendered.width).roundToInt()
                .coerceIn(left + 1, rendered.width)
            val bottom = ((regions.maxOf { it.bottom } + paddingY) * rendered.height).roundToInt()
                .coerceIn(top + 1, rendered.height)
            val cropped = Bitmap.createBitmap(rendered, left, top, right - left, bottom - top)
            page to if (cropped === rendered) {
                rendered.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                cropped
            }
        } finally {
            rendered.recycle()
        }
    }
}

private fun highlightedReviewText(
    text: String,
    ranges: List<IntRange>,
    color: Color,
    strikeThrough: Boolean = false,
) = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        if (range.first < text.length && range.last >= range.first) {
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.LineThrough.takeIf { strikeThrough },
                ),
                range.first.coerceAtLeast(0),
                (range.last + 1).coerceAtMost(text.length),
            )
        }
    }
}

internal data class ReviewTextDiff(
    val sourceChangedRanges: List<IntRange>,
    val proposedChangedRanges: List<IntRange>,
)

internal fun changedTextRanges(source: String, proposed: String): ReviewTextDiff {
    val sourceTokens = reviewTokens(source)
    val proposedTokens = reviewTokens(proposed)
    if (sourceTokens.size * proposedTokens.size > 250_000) return fallbackChangedRanges(source, proposed)
    val lcs = Array(sourceTokens.size + 1) { IntArray(proposedTokens.size + 1) }
    for (sourceIndex in sourceTokens.lastIndex downTo 0) {
        for (proposedIndex in proposedTokens.lastIndex downTo 0) {
            lcs[sourceIndex][proposedIndex] = if (
                sourceTokens[sourceIndex].text == proposedTokens[proposedIndex].text
            ) {
                lcs[sourceIndex + 1][proposedIndex + 1] + 1
            } else {
                maxOf(lcs[sourceIndex + 1][proposedIndex], lcs[sourceIndex][proposedIndex + 1])
            }
        }
    }
    val sourceChanged = BooleanArray(sourceTokens.size) { true }
    val proposedChanged = BooleanArray(proposedTokens.size) { true }
    var sourceIndex = 0
    var proposedIndex = 0
    while (sourceIndex < sourceTokens.size && proposedIndex < proposedTokens.size) {
        if (sourceTokens[sourceIndex].text == proposedTokens[proposedIndex].text) {
            sourceChanged[sourceIndex] = false
            proposedChanged[proposedIndex] = false
            sourceIndex++
            proposedIndex++
        } else if (lcs[sourceIndex + 1][proposedIndex] >= lcs[sourceIndex][proposedIndex + 1]) {
            sourceIndex++
        } else {
            proposedIndex++
        }
    }
    return ReviewTextDiff(
        sourceChangedRanges = changedTokenRanges(sourceTokens, sourceChanged),
        proposedChangedRanges = changedTokenRanges(proposedTokens, proposedChanged),
    )
}

private data class ReviewToken(val text: String, val start: Int, val endExclusive: Int)

private fun reviewTokens(text: String): List<ReviewToken> = REVIEW_TOKEN.findAll(text).map { match ->
    ReviewToken(match.value, match.range.first, match.range.last + 1)
}.toList()

private fun changedTokenRanges(tokens: List<ReviewToken>, changed: BooleanArray): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start: Int? = null
    tokens.forEachIndexed { index, token ->
        if (changed[index] && start == null) start = token.start
        val nextChanged = changed.getOrNull(index + 1) == true
        if (start != null && (!changed[index] || !nextChanged)) {
            val end = if (changed[index]) token.endExclusive - 1 else tokens[index - 1].endExclusive - 1
            ranges += start!!..end
            start = null
        }
    }
    return ranges
}

private fun fallbackChangedRanges(source: String, proposed: String): ReviewTextDiff {
    val prefix = source.zip(proposed).indexOfFirst { it.first != it.second }
        .let { if (it < 0) minOf(source.length, proposed.length) else it }
    var suffix = 0
    while (
        suffix < source.length - prefix && suffix < proposed.length - prefix &&
        source[source.lastIndex - suffix] == proposed[proposed.lastIndex - suffix]
    ) suffix++
    fun changedRange(length: Int): List<IntRange> = if (prefix >= length - suffix) emptyList()
    else listOf(prefix..(length - suffix - 1))
    return ReviewTextDiff(changedRange(source.length), changedRange(proposed.length))
}

private val REVIEW_TOKEN = Regex("""\s+|[\p{L}\p{N}_]+|.""")

@Composable
private fun PdfPageItem(
    filePath: String, page: Int, viewModel: ReaderViewModel, allSentences: List<SentenceEntity>, noteSentenceIds: List<String>,
    selectedSentence: SentenceEntity?, selectedSentenceIds: Set<String>, translation: TranslationState,
    readerSettings: DeepSeekSettings, ink: InkSettings, onOpenNote: (String) -> Unit,
    selectionAnchorPage: Int?,
    debugOverlay: Boolean,
    layoutConfidence: Float,
    onSelectionAnchor: (IntOffset) -> Unit,
    onSaveImage: (String, ByteArray) -> Unit,
) {
    val sentences = remember(allSentences, page) { allSentences.filter { it.decodedRegions(page).isNotEmpty() } }
    val strokes by remember(page) { viewModel.strokes(page) }.collectAsStateWithLifecycle(emptyList())
    val layoutBlocks by remember(page) { viewModel.layoutBlocks(page) }.collectAsStateWithLifecycle(emptyList())
    val debugEvidence by remember(page) { viewModel.debugEvidence(page) }
        .collectAsStateWithLifecycle(emptyList())
    val hostView = LocalView.current
    var bitmap by remember(filePath, page) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val darkReading = isSystemInDarkTheme()
    LaunchedEffect(filePath, page, darkReading) {
        runCatching { PdfPageRenderer.render(filePath, page, darkReading = darkReading) }
            .onSuccess { rendered ->
                bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                bitmap = rendered
            }
            .onFailure { error = it.message }
    }
    DisposableEffect(filePath, page) { onDispose { bitmap?.recycle() } }
    val image = bitmap
    if (image == null) { Box(Modifier.fillMaxWidth().aspectRatio(.72f), contentAlignment = Alignment.Center) { if (error == null) CircularProgressIndicator() else Text(error!!) }; return }
    val selected = selectedSentence?.takeIf { it.decodedRegions(page).isNotEmpty() }
    val multiAnchor = allSentences.firstOrNull { it.id in selectedSentenceIds }
        ?.takeIf { it.decodedRegions(page).isNotEmpty() }
    val rendered = strokes.map { RenderStroke(it.id, parsePoints(it.points), Color(it.colorArgb), it.widthNormalized, it.pressureEnabled, InkTool.valueOf(it.tool), parsePoints(it.controlPoints)) }
    Surface(Modifier.fillMaxWidth(), shadowElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (debugOverlay) {
                ParsingDebugOverlay(debugEvidence, layoutConfidence, Modifier.fillMaxSize())
            }
            InkSurface(
                strokes = rendered, settings = ink, sideButtonAction = readerSettings.spenButtonAction,
                onStroke = { viewModel.addStroke(page, it) },
                onUpdateStrokes = { viewModel.updateStrokes(page, it) },
                onStrokeEraseAt = { x, y -> nearest(rendered, x, y)?.let(viewModel::deleteStroke) },
                onAreaErase = { ids, fragments -> viewModel.areaErase(page, ids, fragments) },
                onSentenceShortcut = { x, y -> hitSentence(sentences, page, x, y)?.id?.let(onOpenNote) },
                modifier = Modifier.fillMaxSize(),
            )
            SentenceTouchLayer(
                page, sentences, selected, selectedSentenceIds, noteSentenceIds,
                imageBlocks = layoutBlocks.filter { it.type in IMAGE_BLOCK_TYPES },
                onTap = { sentence ->
                    if (selectedSentenceIds.isEmpty()) viewModel.selectSentence(sentence, page)
                    else viewModel.toggleSentenceSelection(sentence, page)
                },
                onLongPress = { sentence, anchorOnScreen ->
                    onSelectionAnchor(anchorOnScreen)
                    if (selectedSentenceIds.isEmpty()) viewModel.beginSentenceSelection(sentence, page)
                    else viewModel.toggleSentenceSelection(sentence, page)
                },
                onImageLongPress = { block, anchorOnScreen ->
                    showImageActionMode(hostView, anchorOnScreen) { saveImageBlock(image, block, page, onSaveImage) }
                },
                onBlank = viewModel::dismissSentence,
            )
            if (selected != null && selectionAnchorPage == page) {
                val notes by remember(selected.id) { viewModel.noteStrokes(selected.id) }.collectAsStateWithLifecycle(emptyList())
                TranslationPopup(
                    page, selected, translation, notes, { viewModel.correctSentence(selected, it) },
                    { word, note -> viewModel.addVocabulary(word, note, selected.id) },
                    { onOpenNote(selected.id) }, viewModel::translateSelected, viewModel::retryTranslation, viewModel::dismissSentence,
                )
            }
            if (selected == null && multiAnchor != null && selectionAnchorPage == page && translation !is TranslationState.Hidden) {
                TranslationPopup(
                    page = page,
                    sentence = multiAnchor.copy(
                        originalText = allSentences.filter { it.id in selectedSentenceIds }.joinToString("\n") { it.displayText },
                        correctedText = null,
                    ),
                    state = translation,
                    notes = emptyList(),
                    onSave = {},
                    onVocabulary = { _, _ -> },
                    onOpenNote = {},
                    onTranslate = viewModel::translateSentenceSelection,
                    onRetry = viewModel::retryTranslation,
                    onDismiss = viewModel::dismissSentence,
                    multiSelection = true,
                )
            }
        }
    }
}

@Composable
private fun ParsingDebugOverlay(
    evidence: List<PageEvidenceEntity>,
    layoutConfidence: Float,
    modifier: Modifier = Modifier,
) {
    val latexByDetection = remember(evidence) {
        evidence.asSequence()
            .filter { it.kind == EvidenceKind.FORMULA_LATEX && it.parentId != null }
            .associate { it.parentId!! to it.text }
    }
    val boxes = remember(evidence) {
        evidence.filter {
            it.kind == EvidenceKind.LAYOUT_BLOCK ||
                it.kind == EvidenceKind.OCR_LINE ||
                it.kind == EvidenceKind.FORMULA_REGION
        }
    }
    Canvas(modifier) {
        val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = (size.width / 75f).coerceIn(10f, 22f)
            style = AndroidPaint.Style.FILL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        boxes.forEach { item ->
            val color = when (item.kind) {
                EvidenceKind.FORMULA_REGION -> Color(0xFFE91E63)
                EvidenceKind.OCR_LINE -> Color(0xFFFF9800)
                else -> Color(0xFF00BCD4)
            }
            val left = item.left * size.width
            val top = item.top * size.height
            val right = item.right * size.width
            val bottom = item.bottom * size.height
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
                style = Stroke(width = if (item.kind == EvidenceKind.OCR_LINE) 1f else 3f),
            )
            if (item.kind != EvidenceKind.OCR_LINE) {
                val label = if (item.kind == EvidenceKind.FORMULA_REGION) {
                    val decoded = latexByDetection[item.id]
                    buildString {
                        append("公式 ").append(item.text).append(' ')
                        append((item.confidence * 100).roundToInt()).append('%')
                        if (decoded == null) append(" · 未生成 LaTeX")
                        else append(" · ").append(decoded.take(36))
                    }
                } else {
                    "${item.text} ${(item.confidence * 100).roundToInt()}%"
                }
                labelPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    left.coerceAtLeast(2f),
                    (top - 3f).coerceAtLeast(labelPaint.textSize),
                    labelPaint,
                )
            }
        }
    }
    Surface(
        modifier = Modifier.padding(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            "调试：布局 青色 · OCR 橙色 · 公式 红色 · 布局阈值 ${(layoutConfidence * 100).roundToInt()}%",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SentenceSelectionActionMode(
    active: Boolean,
    count: Int,
    anchorOnScreen: IntOffset,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val copy by rememberUpdatedState(onCopy)
    val translate by rememberUpdatedState(onTranslate)
    val selectAll by rememberUpdatedState(onSelectAll)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(view, active, count, anchorOnScreen) {
        if (!active) return@DisposableEffect onDispose { }
        var disposingFromCompose = false
        var actionKeepsSelection = false
        val mode = view.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.title = "已选 $count 句"
                menu.add(Menu.NONE, 1, 1, "复制").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, 2, 2, "翻译").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, 3, 3, "全选本页").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
                when (item.itemId) {
                    1 -> copy()
                    2 -> {
                        actionKeepsSelection = true
                        translate()
                    }
                    3 -> selectAll()
                    else -> return false
                }
                if (item.itemId != 3) mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (!disposingFromCompose && !actionKeepsSelection) dismiss()
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                outRect.setAroundScreenPoint(view, anchorOnScreen)
            }
        }, ActionMode.TYPE_FLOATING)
        onDispose {
            disposingFromCompose = true
            mode?.finish()
        }
    }
}

private fun showImageActionMode(view: View, anchorOnScreen: IntOffset, onSave: () -> Unit) {
    view.startActionMode(object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = "图片"
            menu.add(Menu.NONE, 1, 1, "保存图片")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
            if (item.itemId != 1) return false
            onSave()
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.setAroundScreenPoint(view, anchorOnScreen)
        }
    }, ActionMode.TYPE_FLOATING)
}

private fun saveImageBlock(
    pageBitmap: Bitmap,
    block: PageLayoutBlockEntity,
    page: Int,
    onSave: (String, ByteArray) -> Unit,
) {
    val left = (block.left * pageBitmap.width).roundToInt().coerceIn(0, pageBitmap.width - 1)
    val top = (block.top * pageBitmap.height).roundToInt().coerceIn(0, pageBitmap.height - 1)
    val right = (block.right * pageBitmap.width).roundToInt().coerceIn(left + 1, pageBitmap.width)
    val bottom = (block.bottom * pageBitmap.height).roundToInt().coerceIn(top + 1, pageBitmap.height)
    val crop = Bitmap.createBitmap(pageBitmap, left, top, right - left, bottom - top)
    val bytes = try {
        ByteArrayOutputStream().use { output ->
            crop.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    } finally {
        crop.recycle()
    }
    onSave("第${page + 1}页-图片${block.position + 1}.png", bytes)
}

private val IMAGE_BLOCK_TYPES = setOf(LayoutBlockType.IMAGE, LayoutBlockType.CHART, LayoutBlockType.TABLE)

@Composable
private fun SentenceTouchLayer(
    page: Int, sentences: List<SentenceEntity>, selected: SentenceEntity?, selectedIds: Set<String>,
    noteSentenceIds: List<String>, imageBlocks: List<PageLayoutBlockEntity>,
    onTap: (SentenceEntity) -> Unit, onLongPress: (SentenceEntity, IntOffset) -> Unit,
    onImageLongPress: (PageLayoutBlockEntity, IntOffset) -> Unit, onBlank: () -> Unit,
) {
    val context = LocalContext.current
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    val touchRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    val currentHandler = rememberUpdatedState<(Boolean, Float, Float, IntOffset) -> Unit> { longPress, x, y, touchOnScreen ->
        if (layerSize.width != 0 && layerSize.height != 0) {
            val layerOriginOnScreen = IntOffset(
                touchOnScreen.x - x.roundToInt(),
                touchOnScreen.y - y.roundToInt(),
            )
            val normalizedX = x / layerSize.width
            val normalizedY = y / layerSize.height
            if (longPress) {
                imageBlocks.filter { normalizedX in it.left..it.right && normalizedY in it.top..it.bottom }
                    .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                    ?.let { block ->
                        val anchor = targetAnchorOnScreen(
                            layerOriginOnScreen,
                            (block.left + block.right) * layerSize.width / 2f,
                            block.top * layerSize.height,
                        )
                        onImageLongPress(block, anchor)
                        return@rememberUpdatedState
                    }
            }
            val sentence = hitSentence(
                sentences, page, normalizedX, normalizedY,
                layerSize.width.toFloat(), layerSize.height.toFloat(), touchRadiusPx,
            )
            if (sentence == null) {
                onBlank()
            } else if (longPress) {
                val hitRegion = sentence.decodedRegions(page).minByOrNull { region ->
                    val centerX = (region.left + region.right) / 2f
                    val centerY = (region.top + region.bottom) / 2f
                    (centerX - normalizedX) * (centerX - normalizedX) +
                        (centerY - normalizedY) * (centerY - normalizedY)
                }
                val anchor = hitRegion?.let { region ->
                    targetAnchorOnScreen(
                        layerOriginOnScreen,
                        (region.left + region.right) * layerSize.width / 2f,
                        region.top * layerSize.height,
                    )
                } ?: touchOnScreen
                onLongPress(sentence, anchor)
            } else onTap(sentence)
        }
    }
    val gestureDetector = remember(context) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                currentHandler.value(false, event.x, event.y, IntOffset(event.rawX.roundToInt(), event.rawY.roundToInt()))
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                currentHandler.value(true, event.x, event.y, IntOffset(event.rawX.roundToInt(), event.rawY.roundToInt()))
            }
        })
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { layerSize = it }.pointerInteropFilter { event ->
        val tool = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) false
        else gestureDetector.onTouchEvent(event)
    }) {
        sentences.filter { it.id in noteSentenceIds }.forEach { sentence -> sentence.decodedRegions(page).forEach { r ->
            drawRect(Color(0x3342A5F5), Offset(r.left*size.width,r.top*size.height), Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height))
        } }
        selected?.decodedRegions(page)?.forEach { r ->
            drawRect(Color(0x55E49A65), Offset(r.left * size.width, r.top * size.height), Size((r.right-r.left)*size.width, (r.bottom-r.top)*size.height))
        }
        sentences.filter { it.id in selectedIds }.forEach { sentence -> sentence.decodedRegions(page).forEach { r ->
            drawRect(Color(0x664A90E2), Offset(r.left * size.width, r.top * size.height), Size((r.right-r.left)*size.width, (r.bottom-r.top)*size.height))
        } }
    }
}

private fun targetAnchorOnScreen(
    layerOriginOnScreen: IntOffset,
    contentCenterX: Float,
    contentTopY: Float,
) = IntOffset(
    layerOriginOnScreen.x + contentCenterX.roundToInt(),
    layerOriginOnScreen.y + contentTopY.roundToInt(),
)

@Composable
private fun TranslationPopup(
    page: Int, sentence: SentenceEntity, state: TranslationState, notes: List<SentenceNoteStrokeEntity>, onSave: (String) -> Unit,
    onVocabulary: (String, String) -> Unit, onOpenNote: () -> Unit, onTranslate: () -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit,
    multiSelection: Boolean = false,
) {
    val anchor = sentence.decodedRegions(page).minByOrNull { it.top } ?: return
    var pageSize by remember { mutableStateOf(IntSize.Zero) }; var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var draft by remember(sentence.id, sentence.correctedText) { mutableStateOf(TextFieldValue(sentence.displayText)) }
    var wordDialog by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember(sentence.id) { mutableStateOf(IntOffset.Zero) }
    val context = LocalContext.current
    val x = ((anchor.left + anchor.right)/2f * pageSize.width - cardSize.width/2f).roundToInt().coerceIn(0, (pageSize.width-cardSize.width).coerceAtLeast(0))
    val above = (anchor.top * pageSize.height - cardSize.height - 8).roundToInt()
    val below = (anchor.bottom * pageSize.height + 8).roundToInt()
    val y = (if (above >= 0) above else below).coerceIn(0, (pageSize.height - cardSize.height).coerceAtLeast(0))
    val cardOffset = IntOffset(x + dragOffset.x, y + dragOffset.y)
    Box(Modifier.fillMaxSize().onSizeChanged { pageSize = it }.pointerInput(sentence.id, cardOffset, cardSize) {
        awaitEachGesture {
            var down = awaitPointerEvent(PointerEventPass.Initial)
                .changes.firstOrNull { it.pressed && !it.previousPressed }
            while (down == null) {
                down = awaitPointerEvent(PointerEventPass.Initial)
                    .changes.firstOrNull { it.pressed && !it.previousPressed }
            }
            val point = down.position
            val insideCard = point.x >= cardOffset.x && point.x <= cardOffset.x + cardSize.width &&
                point.y >= cardOffset.y && point.y <= cardOffset.y + cardSize.height
            if (!insideCard) onDismiss()
        }
    }) {
        Surface(
            Modifier
                .offset { cardOffset }
                .onSizeChanged { cardSize = it }
                .width(540.dp)
                .pointerInput(sentence.id) {
                detectDragGestures { change, amount -> change.consume(); dragOffset = IntOffset(dragOffset.x + amount.x.roundToInt(), dragOffset.y + amount.y.roundToInt()) }
            },
            RoundedCornerShape(18.dp),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Text(if (multiSelection) "多句翻译 · 窗口可拖动" else "按住窗口空白处可移动", style = MaterialTheme.typography.labelMedium) }
                    OutlinedTextField(draft, { if (!multiSelection) draft = it }, Modifier.fillMaxWidth(), label = { Text(if (multiSelection) "所选原文" else "原文（可选词、修正）") }, minLines = 2, maxLines = 5, readOnly = multiSelection)
                    when (state) {
                        TranslationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("结合论文上下文翻译中…") }
                        is TranslationState.Ready -> {
                            Text(state.result.text, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "本次费用 ${state.result.costAmount} ${state.result.costCurrency} · 输入 ${state.result.usage.promptTokens}（缓存 ${state.result.usage.cacheHitTokens}）· 输出 ${state.result.usage.completionTokens} tokens",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is TranslationState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) { Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)); TextButton(onClick = onRetry) { Text("重试") } }
                        TranslationState.Hidden -> Text("已选择句子，尚未调用 AI。", style = MaterialTheme.typography.labelMedium)
                    }
                    if (notes.isNotEmpty()) {
                        Text("句子笔记", style = MaterialTheme.typography.labelMedium)
                        InkPreview(notes.map { RenderStroke(it.id, parsePoints(it.points), Color(it.colorArgb), it.widthNormalized, it.pressureEnabled, InkTool.valueOf(it.tool), parsePoints(it.controlPoints)) }, Modifier.fillMaxWidth().height(80.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!multiSelection) TextButton(onClick = {
                            val range = draft.selection
                            val selected = if (!range.collapsed) draft.text.substring(range.min, range.max) else ""
                            wordDialog = selected
                        }) { Text("查词") }
                        if (!multiSelection) TextButton(onClick = onOpenNote) { Text("画板") }
                        if (state == TranslationState.Hidden) TextButton(onClick = onTranslate) { Text("翻译") }
                        if (state is TranslationState.Ready) TextButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("译文", state.result.text))
                        }) { Text("复制") }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                        if (!multiSelection) Button(onClick = { onSave(draft.text) }) { Text("保存原文") }
                    }
                }
            }
    }
    wordDialog?.let { initial -> WordDialog(initial, onDismiss = { wordDialog = null }, onLookup = { lookupSamsungDictionary(context, it) }, onSave = { word, note -> onVocabulary(word, note); wordDialog = null }) }
}

@Composable
private fun WordDialog(initial: String, onDismiss: () -> Unit, onLookup: (String) -> Unit, onSave: (String,String) -> Unit) {
    var word by remember(initial) { mutableStateOf(initial) }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Samsung 词典 / 生词本") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(word, { word = it }, label = { Text("单词或短语") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("释义或备注（可选）") })
            TextButton(onClick = { onLookup(word) }, enabled = word.isNotBlank()) { Text("用 Samsung 词典查看") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(word, note) }, enabled = word.isNotBlank()) { Text("加入生词本") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

fun lookupSamsungDictionary(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage("com.diotek.sec.lookup.dictionary")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text.trim()).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        android.widget.Toast.makeText(context, "Samsung 词典不可用或尚未启用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

internal fun hitSentence(
    sentences: List<SentenceEntity>,
    page: Int,
    x: Float,
    y: Float,
    pageWidthPx: Float = 1f,
    pageHeightPx: Float = 1f,
    touchRadiusPx: Float = 0f,
): SentenceEntity? = sentences.asSequence().mapNotNull { sentence ->
    val regions = sentence.decodedRegions(page)
    val distanceSquared = regions.minOfOrNull { region ->
        val dx = when { x < region.left -> region.left - x; x > region.right -> x - region.right; else -> 0f } * pageWidthPx
        val dy = when { y < region.top -> region.top - y; y > region.bottom -> y - region.bottom; else -> 0f } * pageHeightPx
        dx * dx + dy * dy
    } ?: return@mapNotNull null
    val smallestRegion = regions.minOfOrNull { (it.right-it.left) * (it.bottom-it.top) } ?: Float.MAX_VALUE
    Triple(sentence, distanceSquared, smallestRegion)
}.filter { (_, distanceSquared) -> distanceSquared <= touchRadiusPx * touchRadiusPx }
    .minWithOrNull(compareBy<Triple<SentenceEntity, Float, Float>> { it.second }.thenBy { it.third })
    ?.first

private fun statusText(document: DocumentEntity?): String = when {
    document == null -> "读取中"
    document.fullTranslationStatus == FullTranslationStatus.RUNNING ->
        "AI 正在校正并翻译全文 · ${document.fullTranslationCompleted}/${document.fullTranslationTotal} 句"
    document.fullTranslationStatus == FullTranslationStatus.FAILED ->
        "全文翻译失败：${document.fullTranslationError ?: "未知错误"}"
    document.fullTranslationStatus == FullTranslationStatus.READY -> buildString {
        append("全文翻译完成")
        if (document.fullTranslationCorrectedCount > 0) append(" · AI 修复 ${document.fullTranslationCorrectedCount} 句")
        if (document.fullTranslationCostCurrency.isNotBlank()) {
            append(" · 费用 ${document.fullTranslationCostAmount} ${document.fullTranslationCostCurrency}")
        }
        append(" · ${document.fullTranslationPromptTokens + document.fullTranslationCompletionTokens} tokens")
    }
    document.aiContextStatus == AiContextStatus.ANALYZING -> "AI 正在解析论文"
    document.aiContextStatus == AiContextStatus.READY -> buildString {
        append("全文上下文已就绪")
        if (document.aiContextCostCurrency.isNotBlank()) append(" · 费用 ${document.aiContextCostAmount} ${document.aiContextCostCurrency}")
        append(" · ${document.aiContextPromptTokens + document.aiContextCompletionTokens} tokens")
    }
    document.aiContextStatus == AiContextStatus.FAILED -> "AI 解析失败：${document.aiContextError ?: "未知错误"}"
    document.status == DocumentStatus.INDEXING -> "正在建立文字层"
    document.status == DocumentStatus.PAUSED -> "解析已暂停 · ${document.processedPages}/${document.pageCount} 页"
    document.status == DocumentStatus.CANCELED -> "解析已取消 · 已保留 ${document.processedPages} 页"
    document.status == DocumentStatus.READY -> "点句选择"
    document.status == DocumentStatus.FAILED -> "文字层失败"
    else -> "等待索引"
}

private fun fullTranslationActionLabel(document: DocumentEntity?): String = when (document?.fullTranslationStatus) {
    FullTranslationStatus.RUNNING -> "AI ${document.fullTranslationCompleted}/${document.fullTranslationTotal}"
    FullTranslationStatus.FAILED -> "重试全文翻译"
    FullTranslationStatus.READY -> "AI 重译全文"
    else -> "AI 翻译全文"
}
