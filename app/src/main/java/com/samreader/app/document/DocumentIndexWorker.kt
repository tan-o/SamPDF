package com.samreader.app.document

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.samreader.app.SamReaderApplication
import com.samreader.app.MainActivity
import com.samreader.app.data.DocumentStatus
import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.PageEntity
import com.samreader.app.data.PageLayoutBlockEntity
import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.TextSource
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DocumentIndexWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val container = (appContext.applicationContext as SamReaderApplication).container
    private val dao = container.database.dao()

    override suspend fun doWork(): Result = INDEX_MUTEX.withLock {
        withContext(Dispatchers.IO) {
        val documentId = inputData.getString(DOCUMENT_ID) ?: return@withContext Result.failure()
        val document = dao.getDocument(documentId) ?: return@withContext Result.failure()
        val file = File(document.filePath)
        if (!file.isFile) {
            dao.updateDocumentProgress(documentId, DocumentStatus.FAILED, 0, "PDF 文件不存在")
            return@withContext Result.failure()
        }

        val backgroundEnabled = dao.getAppSetting(BACKGROUND_INDEXING)?.toBooleanStrictOrNull() ?: true
        val showNotificationProgress = dao.getAppSetting(SHOW_NOTIFICATION_PROGRESS)?.toBooleanStrictOrNull() ?: true
        val layoutConfidence = container.parsingDebugSettings.getDocumentLayoutConfidence(documentId)
        val existingSentences = dao.getDocumentSentences(documentId)
        val correctedTexts = existingSentences.mapNotNull { sentence -> sentence.correctedText?.let { sentence.id to it } }.toMap()
        val startPage = SentenceStream.resumePage(
            committedPages = document.processedPages,
            sentencePages = existingSentences.map { sentence -> sentence.pageNumber to sentence.decodedPages() },
        )
        dao.deleteSentencesFromPage(documentId, startPage)
        if (backgroundEnabled) setForeground(foregroundInfo(document.id, document.title, startPage, document.pageCount, showNotificationProgress))
        dao.updateDocumentProgress(id = documentId, status = DocumentStatus.INDEXING, processedPages = startPage)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val sentences = SentenceStream(WtpSentenceBoundaryModel(applicationContext))
        try {
            val rustPages = runCatching { RustPdfTextExtractor.extract(file.absolutePath) }
                .onFailure { error -> Log.w("SamReaderIndex", "Rust PDF text extraction failed", error) }
                .getOrDefault(emptyList())
            val nativePages = rustPages.map { it.toPositionedLines() }
            val furniture = RunningFurniture.detect(nativePages)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (pageNumber in startPage until renderer.pageCount) {
                        if (isStopped) return@withContext Result.success()
                        renderer.openPage(pageNumber).use { page ->
                            val rustPage = rustPages.getOrNull(pageNumber)
                            val nativeLines = nativePages.getOrNull(pageNumber).orEmpty()
                            val recognizedPage = recognizePage(page, recognizer, layoutConfidence, nativeLines)
                            val layoutBlocks = PageParser.parse(
                                pageNumber = pageNumber,
                                regions = recognizedPage.regions,
                                nativeLines = nativeLines,
                                ocrLines = recognizedPage.ocrLines,
                                formulas = recognizedPage.formulas,
                                furniture = furniture,
                            )
                            val committed = sentences.addPage(pageNumber, layoutBlocks).toMutableList()
                            if (pageNumber == renderer.pageCount - 1) committed += sentences.finish()
                            val quality = layoutBlocks.flatMap(PositionedBlock::lines)
                                .map(PositionedLine::confidence).takeIf(List<Float>::isNotEmpty)
                                ?.average()?.toFloat() ?: if (nativeLines.isNotEmpty()) 1f else 0f
                            Log.i(
                                "SamReaderIndex",
                                "page=${pageNumber + 1} visualBlocks=${layoutBlocks.size} " +
                                    "bodyBlocks=${layoutBlocks.count(PositionedBlock::selectableBody)} " +
                                    "committedSentences=${committed.size}",
                            )
                            val evidence = ParseEvidenceBuilder(
                                documentId, pageNumber, page.width, page.height,
                            ).apply {
                                addRustPage(rustPage)
                                addVisual(
                                    recognizedPage.regions,
                                    assignLinesToRegions(recognizedPage.regions, recognizedPage.ocrLines),
                                    recognizedPage.formulaRegions,
                                    recognizedPage.formulas,
                                )
                            }.build()
                            val persistedBlocks = layoutBlocks.mapIndexed { index, block ->
                                PageLayoutBlockEntity(
                                    documentId, pageNumber, index,
                                    block.type,
                                    block.left, block.top, block.right, block.bottom,
                                    if (block.type !in IMAGE_BLOCK_TYPES) {
                                        block.lines.joinToString("\n", transform = PositionedLine::text)
                                    } else "",
                                )
                            }
                            val entities = committed.map { it.toEntity(documentId, correctedTexts) }
                            dao.replacePage(
                                page = PageEntity(
                                    documentId = documentId,
                                    pageNumber = pageNumber,
                                    widthPoints = page.width.toFloat(),
                                    heightPoints = page.height.toFloat(),
                                    source = TextSource.HYBRID_PDF_VISUAL,
                                    textQuality = quality,
                                ),
                                blocks = persistedBlocks,
                                evidence = evidence,
                                sentences = entities,
                                spans = entities.flatMap { SentenceSpanParser.parse(it.id, it.originalText) },
                            )
                        }
                        dao.updateDocumentProgressIfStatus(
                            id = documentId,
                            expectedStatus = DocumentStatus.INDEXING,
                            newStatus = DocumentStatus.INDEXING,
                            // Pages holding a still-open sentence are re-parsed after an interruption.
                            processedPages = sentences.firstOpenPage ?: (pageNumber + 1),
                        )
                        if (backgroundEnabled) {
                            setForeground(foregroundInfo(document.id, document.title, pageNumber + 1, renderer.pageCount, showNotificationProgress))
                        }
                    }
                }
            }
            val hasSentences = dao.getDocumentSentences(documentId).isNotEmpty()
            dao.updateDocumentProgressIfStatus(
                id = documentId,
                expectedStatus = DocumentStatus.INDEXING,
                newStatus = if (hasSentences) DocumentStatus.READY else DocumentStatus.FAILED,
                processedPages = document.pageCount,
                errorMessage = if (hasSentences) null else "布局模型和区域 OCR 未生成正文句子",
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("SamReaderIndex", "Document indexing failed for $documentId", error)
            dao.updateDocumentProgressIfStatus(
                id = documentId,
                expectedStatus = DocumentStatus.INDEXING,
                newStatus = DocumentStatus.FAILED,
                processedPages = dao.getDocument(documentId)?.processedPages ?: 0,
                errorMessage = error.message ?: error::class.java.simpleName,
            )
            Result.failure()
        } finally {
            recognizer.close()
            DocumentLayoutModel.release()
            Pix2TextFormulaModel.release()
            WtpSentenceBoundaryModel.release()
        }
    }
    }

    private suspend fun recognizePage(
        page: PdfRenderer.Page,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        layoutConfidence: Float,
        nativeLines: List<PositionedLine>,
    ): RecognizedPage {
        val bitmapWidth = PAGE_RENDER_WIDTH
        val bitmapHeight = (bitmapWidth * page.height.toFloat() / page.width)
            .roundToInt()
            .coerceAtLeast(1)
        val bitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val regions = DocumentLayoutModel.detect(applicationContext, bitmap, layoutConfidence)
            val pageLines = if (requiresVisualOcr(regions, nativeLines)) {
                recognizePageLines(bitmap, recognizer)
            } else {
                emptyList()
            }
            val formulaPage = Pix2TextFormulaModel.recognizePage(applicationContext, bitmap, regions)
            RecognizedPage(pageLines, regions, formulaPage.regions, formulaPage.formulas)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizePageLines(
        pageBitmap: Bitmap,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): List<PositionedLine> {
        val result = recognizer.process(InputImage.fromBitmap(pageBitmap, 0)).await()
        return result.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                val glyphs = line.elements.flatMap { element ->
                    val symbols = element.symbols.mapNotNull { symbol -> symbol.boundingBox?.let { box ->
                        PositionedGlyph(
                            symbol.text,
                            box.left.toFloat() / pageBitmap.width,
                            box.top.toFloat() / pageBitmap.height,
                            box.right.toFloat() / pageBitmap.width,
                            box.bottom.toFloat() / pageBitmap.height,
                            symbol.confidence ?: element.confidence ?: line.confidence ?: .8f,
                        )
                    } }
                    if (symbols.isNotEmpty()) symbols else {
                        val box = element.boundingBox ?: return@flatMap emptyList()
                        val characters = element.text.toList()
                        characters.mapIndexed { index, character -> PositionedGlyph(
                            character.toString(),
                            (box.left + box.width() * index / characters.size.coerceAtLeast(1)).toFloat() / pageBitmap.width,
                            box.top.toFloat() / pageBitmap.height,
                            (box.left + box.width() * (index + 1) / characters.size.coerceAtLeast(1)).toFloat() / pageBitmap.width,
                            box.bottom.toFloat() / pageBitmap.height,
                            element.confidence ?: line.confidence ?: .8f,
                        ) }
                    }
                }
                PositionedLine(
                    text = line.text,
                    left = rect.left.toFloat() / pageBitmap.width,
                    top = rect.top.toFloat() / pageBitmap.height,
                    right = rect.right.toFloat() / pageBitmap.width,
                    bottom = rect.bottom.toFloat() / pageBitmap.height,
                    confidence = line.confidence ?: .8f,
                    glyphs = glyphs,
                )
            }
    }

    private fun foregroundInfo(documentId: String, title: String, processed: Int, total: Int, showProgress: Boolean): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "论文解析", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在后台建立 PDF 版面、文字和公式索引"
            },
        )
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在解析《$title》")
            .setContentText(if (showProgress) "已完成 $processed / $total 页" else "解析在后台持续进行")
            .setProgress(total.coerceAtLeast(1), processed.coerceAtMost(total), !showProgress)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                IndexControlReceiver.pendingIntent(applicationContext, documentId, IndexControlReceiver.ACTION_PAUSE),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                IndexControlReceiver.pendingIntent(applicationContext, documentId, IndexControlReceiver.ACTION_CANCEL),
            )
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + documentId.hashCode().and(0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        // Small inline formulas in scanned papers need enough source pixels before the recognizer
        // downsamples their crop to 384x384. 2400 px keeps integral signs and scripts legible while
        // remaining comfortably within the memory budget of the supported 8 GB tablets.
        private const val PAGE_RENDER_WIDTH = 2400
        private val IMAGE_BLOCK_TYPES = setOf(LayoutBlockType.IMAGE, LayoutBlockType.CHART, LayoutBlockType.TABLE)
        private const val BACKGROUND_INDEXING = "index.background.enabled"
        private const val SHOW_NOTIFICATION_PROGRESS = "index.notification.progress"
        private const val NOTIFICATION_CHANNEL = "document_indexing"
        private const val NOTIFICATION_ID_BASE = 3200
        private val INDEX_MUTEX = Mutex()
        const val DOCUMENT_ID = "document_id"
    }
}

private data class RecognizedPage(
    val ocrLines: List<PositionedLine>,
    val regions: List<LayoutRegion>,
    val formulaRegions: List<FormulaRegion>,
    val formulas: List<RecognizedFormula>,
)

private fun DocumentSentence.toEntity(documentId: String, correctedTexts: Map<String, String>): SentenceEntity {
    val id = UUID.nameUUIDFromBytes("$documentId:$firstPage:$position".toByteArray()).toString()
    return SentenceEntity(
        id = id,
        documentId = documentId,
        pageNumber = firstPage,
        position = position,
        originalText = text,
        regions = regions.joinToString("|") { region ->
            listOf(
                region.pageNumber,
                region.rect.left.coerceIn(0f, 1f),
                region.rect.top.coerceIn(0f, 1f),
                region.rect.right.coerceIn(0f, 1f),
                region.rect.bottom.coerceIn(0f, 1f),
            ).joinToString(",")
        },
        source = "${TextSource.HYBRID_PDF_VISUAL}:$semanticRole",
        confidence = confidence,
        correctedText = correctedTexts[id],
    )
}

private fun SentenceEntity.decodedPages(): Set<Int> =
    regions.split('|').mapNotNullTo(mutableSetOf()) { it.substringBefore(',').toIntOrNull() }
