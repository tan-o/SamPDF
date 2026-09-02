package com.samreader.app.document

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.samreader.app.MainActivity
import com.samreader.app.SamReaderApplication
import com.samreader.app.data.AiContextStatus
import com.samreader.app.data.AiCorrectionReviewEntity
import com.samreader.app.data.DeepSeekBalance
import com.samreader.app.data.DocumentStatus
import com.samreader.app.data.FullTranslationStatus
import com.samreader.app.data.SentenceCorrection
import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.TranslationEntity
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FullDocumentTranslationWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val container = (appContext.applicationContext as SamReaderApplication).container
    private val dao = container.database.dao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(DOCUMENT_ID) ?: return@withContext Result.failure()
        var document = dao.getDocument(documentId) ?: return@withContext Result.failure()
        val sentences = dao.getDocumentSentences(documentId)
        if (document.status != DocumentStatus.READY || sentences.isEmpty()) {
            dao.updateFullTranslation(
                id = documentId,
                status = FullTranslationStatus.FAILED,
                completed = 0,
                total = sentences.size,
                correctedCount = 0,
                error = "请先完成本地解析",
            )
            return@withContext Result.failure()
        }

        var completed = document.fullTranslationCompleted.coerceIn(0, sentences.size)
        var correctedCount = document.fullTranslationCorrectedCount
        var promptTokens = document.fullTranslationPromptTokens
        var completionTokens = document.fullTranslationCompletionTokens
        val balanceBefore = runCatching { container.translations.balance() }.getOrNull()
        setForeground(foregroundInfo(documentId, document.title, completed, sentences.size))

        try {
            val context = if (
                document.aiContextStatus == AiContextStatus.READY &&
                !document.aiContextSummary.isNullOrBlank()
            ) {
                document.aiContextSummary.orEmpty()
            } else {
                dao.setAiContextStatus(documentId, AiContextStatus.ANALYZING)
                val analysis = container.translations.analyzeDocument(document.title, sentences)
                dao.finishAiContext(
                    id = documentId,
                    status = AiContextStatus.READY,
                    summary = analysis.text,
                    error = null,
                    currency = analysis.costCurrency,
                    amount = analysis.costAmount,
                    promptTokens = analysis.usage.promptTokens,
                    completionTokens = analysis.usage.completionTokens,
                )
                analysis.text
            }

            while (completed < sentences.size) {
                if (isStopped) throw CancellationException("全文翻译已停止")
                val end = fullTranslationBatchEnd(sentences, completed)
                val batch = sentences.subList(completed, end)
                val adjacent = adjacentContext(sentences, completed, end)
                val correctionSettings = container.deepSeekSettings.current()
                val result = container.translations.translateFullBatch(
                    batch,
                    context,
                    adjacent,
                    allowSourceCorrection = correctionSettings.aiCorrectionEnabled,
                )
                val now = System.currentTimeMillis()
                var batchCorrected = 0
                val decisions = batch.zip(result.items).map { (sentence, item) ->
                    if (!correctionSettings.aiCorrectionEnabled) {
                        CorrectionDecision(sentence, item, sentence.correctedText, requiresReview = false)
                    } else {
                        val candidate = item.correctedText.trim()
                        val accepted = candidate.takeIf { it == sentence.displayText } ?:
                            acceptedAiCorrection(
                                sentence.displayText,
                                candidate,
                                correctionSettings.aiCorrectionMaxChangeRatio,
                            )
                        if (accepted == null) {
                            CorrectionDecision(sentence, item, sentence.correctedText, requiresReview = true)
                        } else {
                            val stored = accepted.takeUnless { it == sentence.originalText }
                            if (stored != null && stored != sentence.originalText) batchCorrected++
                            CorrectionDecision(sentence, item, stored, requiresReview = false)
                        }
                    }
                }
                val corrections = decisions.map { decision ->
                    SentenceCorrection(decision.sentence.id, decision.storedCorrection)
                }
                val translations = decisions.map { decision ->
                    TranslationEntity(
                        sentenceId = decision.sentence.id,
                        sourceText = decision.storedCorrection ?: decision.sentence.originalText,
                        translatedText = decision.item.translatedText,
                        updatedAt = now,
                        promptTokens = 0,
                        cacheHitTokens = 0,
                        cacheMissTokens = 0,
                        completionTokens = 0,
                        costCurrency = "",
                        costAmount = "0",
                    )
                }
                val reviews = decisions.filter(CorrectionDecision::requiresReview).map { decision ->
                    AiCorrectionReviewEntity(
                        sentenceId = decision.sentence.id,
                        documentId = decision.sentence.documentId,
                        pageNumber = decision.sentence.pageNumber,
                        pdfOriginalText = "PDF 原页裁片",
                        parsedText = decision.sentence.displayText,
                        proposedText = decision.item.correctedText,
                        translatedText = decision.item.translatedText,
                        createdAt = now,
                    )
                }
                dao.applyFullTranslationBatch(corrections, translations, reviews)
                completed = end
                correctedCount += batchCorrected
                promptTokens += result.usage.promptTokens
                completionTokens += result.usage.completionTokens
                dao.updateFullTranslation(
                    id = documentId,
                    status = FullTranslationStatus.RUNNING,
                    completed = completed,
                    total = sentences.size,
                    correctedCount = correctedCount,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                )
                setForeground(foregroundInfo(documentId, document.title, completed, sentences.size))
            }

            val cost = balanceDifference(balanceBefore, runCatching { container.translations.balance() }.getOrNull())
            dao.updateFullTranslation(
                id = documentId,
                status = FullTranslationStatus.READY,
                completed = sentences.size,
                total = sentences.size,
                correctedCount = correctedCount,
                currency = cost.first,
                amount = cost.second,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            document = dao.getDocument(documentId) ?: document
            if (document.aiContextStatus == AiContextStatus.ANALYZING) {
                dao.finishAiContext(
                    id = documentId,
                    status = AiContextStatus.FAILED,
                    summary = null,
                    error = error.message ?: "AI 上下文解析失败",
                )
            }
            val cost = balanceDifference(balanceBefore, runCatching { container.translations.balance() }.getOrNull())
            dao.updateFullTranslation(
                id = documentId,
                status = FullTranslationStatus.FAILED,
                completed = document.fullTranslationCompleted,
                total = sentences.size,
                correctedCount = document.fullTranslationCorrectedCount,
                error = error.message ?: "全文 AI 翻译失败",
                currency = cost.first,
                amount = cost.second,
                promptTokens = document.fullTranslationPromptTokens,
                completionTokens = document.fullTranslationCompletionTokens,
            )
            Result.failure()
        }
    }

    private fun foregroundInfo(documentId: String, title: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "AI 全文翻译", NotificationManager.IMPORTANCE_LOW).apply {
                description = "结合论文上下文校正文稿并翻译全文"
            },
        )
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在 AI 翻译《$title》")
            .setContentText("已完成 $completed / $total 句")
            .setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + documentId.hashCode().and(0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val DOCUMENT_ID = "document_id"
        private const val NOTIFICATION_CHANNEL = "full_document_translation"
        private const val NOTIFICATION_ID_BASE = 4800
    }
}

private data class CorrectionDecision(
    val sentence: SentenceEntity,
    val item: com.samreader.app.data.FullTranslationItem,
    val storedCorrection: String?,
    val requiresReview: Boolean,
)

internal fun fullTranslationBatchEnd(
    sentences: List<SentenceEntity>,
    start: Int,
    maxSentences: Int = 16,
    maxCharacters: Int = 9_000,
): Int {
    var end = start
    var characters = 0
    while (end < sentences.size && end - start < maxSentences) {
        val next = sentences[end].displayText.length
        if (end > start && characters + next > maxCharacters) break
        characters += next
        end++
    }
    return end.coerceAtLeast((start + 1).coerceAtMost(sentences.size))
}

internal fun acceptedAiCorrection(source: String, candidate: String, maxChangeRatio: Float): String? {
    val clean = candidate.trim()
    if (clean.isEmpty() || clean == source) return null
    if (LATEX.findAll(clean).map { it.value }.toList() != LATEX.findAll(source).map { it.value }.toList()) return null
    val maxLength = maxOf(source.length, clean.length).coerceAtLeast(1)
    val allowedEdits = (maxLength * maxChangeRatio.coerceIn(.05f, 1f)).toInt().coerceAtLeast(1)
    if (!editDistanceWithinLimit(source, clean, allowedEdits)) return null
    return clean
}

private fun editDistanceWithinLimit(source: String, candidate: String, limit: Int): Boolean {
    if (kotlin.math.abs(source.length - candidate.length) > limit) return false
    if (limit >= maxOf(source.length, candidate.length)) return true
    val unreachable = limit + 1
    var previous = IntArray(candidate.length + 1) { unreachable }
    for (column in 0..minOf(candidate.length, limit)) previous[column] = column
    for (row in 1..source.length) {
        val current = IntArray(candidate.length + 1) { unreachable }
        if (row <= limit) current[0] = row
        val from = maxOf(1, row - limit)
        val through = minOf(candidate.length, row + limit)
        for (column in from..through) {
            val substitution = previous[column - 1] + if (source[row - 1] == candidate[column - 1]) 0 else 1
            current[column] = minOf(previous[column] + 1, current[column - 1] + 1, substitution)
        }
        previous = current
    }
    return previous[candidate.length] <= limit
}

private fun adjacentContext(sentences: List<SentenceEntity>, start: Int, end: Int): String {
    val before = sentences.subList((start - 2).coerceAtLeast(0), start)
    val after = sentences.subList(end, (end + 2).coerceAtMost(sentences.size))
    return (before + after).joinToString("\n") { "[p.${it.pageNumber + 1}] ${it.displayText}" }
}

private fun balanceDifference(before: DeepSeekBalance?, after: DeepSeekBalance?): Pair<String, String> {
    if (before == null || after == null) return "" to "0"
    val currency = before.items.firstOrNull()?.currency.orEmpty()
    val beforeAmount = before.items.firstOrNull { it.currency == currency }?.totalBalance?.toBigDecimalOrNull()
    val afterAmount = after.items.firstOrNull { it.currency == currency }?.totalBalance?.toBigDecimalOrNull()
    if (beforeAmount == null || afterAmount == null) return "" to "0"
    return currency to (beforeAmount - afterAmount).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
}

private val LATEX = Regex("""\\\[[\s\S]*?\\\]|\$[^$]+\$""")
