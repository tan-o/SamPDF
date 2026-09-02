package com.samreader.app.data

import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class BalanceInfo(val currency: String, val totalBalance: String, val grantedBalance: String, val toppedUpBalance: String)
data class DeepSeekBalance(val isAvailable: Boolean, val items: List<BalanceInfo>)
data class TranslationUsage(val promptTokens: Int, val cacheHitTokens: Int, val cacheMissTokens: Int, val completionTokens: Int)
data class TranslationResult(val text: String, val usage: TranslationUsage, val costCurrency: String, val costAmount: String)
data class AnalysisResult(val text: String, val usage: TranslationUsage, val costCurrency: String, val costAmount: String)
data class FullTranslationItem(val sentenceId: String, val correctedText: String, val translatedText: String)
data class FullTranslationBatchResult(val items: List<FullTranslationItem>, val usage: TranslationUsage)

class TranslationRepository(
    private val dao: SamReaderDao,
    private val settings: DeepSeekSettingsRepository,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val data = execute("models", "GET", null).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    }

    suspend fun cachedTranslation(sentence: SentenceEntity): TranslationResult? = withContext(Dispatchers.IO) {
        dao.getTranslation(sentence.id)?.takeIf { it.sourceText == sentence.displayText }?.asResult()
    }

    suspend fun translate(
        sentence: SentenceEntity,
        documentContext: String,
        relatedContext: String,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val text = sentence.displayText
        dao.getTranslation(sentence.id)?.takeIf { it.sourceText == text }?.let { return@withContext it.asResult() }

        val before = runCatching { balance() }.getOrNull()
        val current = settings.settings.value
        val system = buildString {
            append("你是学术论文翻译助手。结合论文上下文统一专业术语，并根据上下文静默修正明显 OCR 字母错误；只输出当前目标句的简体中文译文。\n")
            if (documentContext.isNotBlank()) append("论文概况：\n$documentContext\n")
            if (relatedContext.isNotBlank()) append("相关原文：\n$relatedContext")
        }
        val user = current.promptTemplate.replace("{context}", relatedContext).replace("{text}", text)
        val body = JSONObject().put("model", current.model).put("stream", false)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 1200).put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)),
        )
        val response = execute("chat/completions", "POST", body)
        val translated = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        require(translated.isNotEmpty()) { "DeepSeek 返回了空译文" }
        val usageJson = response.getJSONObject("usage")
        val usage = TranslationUsage(
            promptTokens = usageJson.optInt("prompt_tokens"),
            cacheHitTokens = usageJson.optInt("prompt_cache_hit_tokens"),
            cacheMissTokens = usageJson.optInt("prompt_cache_miss_tokens"),
            completionTokens = usageJson.optInt("completion_tokens"),
        )
        val cost = costSince(before)
        val result = TranslationResult(translated, usage, cost.first, cost.second)
        dao.upsertTranslation(
            TranslationEntity(
                sentence.id, text, translated, System.currentTimeMillis(), usage.promptTokens,
                usage.cacheHitTokens, usage.cacheMissTokens, usage.completionTokens,
                result.costCurrency, result.costAmount,
            ),
        )
        result
    }

    suspend fun analyzeDocument(title: String, sentences: List<SentenceEntity>): AnalysisResult = withContext(Dispatchers.IO) {
        require(sentences.isNotEmpty()) { "论文尚未识别出文字" }
        val step = (sentences.size / 80).coerceAtLeast(1)
        val sample = sentences.filterIndexed { index, _ -> index < 30 || index % step == 0 }
            .joinToString("\n") { it.displayText }.take(24_000)
        val prompt = """
            请分析论文《$title》的抽样全文，输出简洁的中文上下文档案，供逐句翻译复用。必须包含：研究主题、关键方法、核心结论、专业术语英中对照、缩写释义。发现疑似 OCR 错字时结合上下文给出正确术语。不要逐句翻译。

            $sample
        """.trimIndent()
        val body = JSONObject().put("model", settings.settings.value.model).put("stream", false)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 2400).put(
            "messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
        )
        val before = runCatching { balance() }.getOrNull()
        val response = execute("chat/completions", "POST", body)
        val text = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val usageJson = response.getJSONObject("usage")
        val usage = TranslationUsage(
            usageJson.optInt("prompt_tokens"), usageJson.optInt("prompt_cache_hit_tokens"),
            usageJson.optInt("prompt_cache_miss_tokens"), usageJson.optInt("completion_tokens"),
        )
        require(text.isNotEmpty()) { "DeepSeek 返回了空的论文解析结果" }
        val cost = costSince(before)
        AnalysisResult(text, usage, cost.first, cost.second)
    }

    suspend fun translateSelection(
        sentences: List<SentenceEntity>,
        documentContext: String,
    ): TranslationResult = withContext(Dispatchers.IO) {
        require(sentences.isNotEmpty()) { "请先选择句子" }
        val source = sentences.joinToString("\n") { "[${it.pageNumber + 1}] ${it.displayText}" }
        val system = buildString {
            append("你是学术论文翻译助手。按原顺序把所选句子整体翻译成简体中文，统一术语并保留段落结构；只输出译文。\n")
            if (documentContext.isNotBlank()) append("论文概况：\n$documentContext")
        }
        val current = settings.settings.value
        val body = JSONObject().put("model", current.model).put("stream", false)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 2400).put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", source)),
            )
        val before = runCatching { balance() }.getOrNull()
        val response = execute("chat/completions", "POST", body)
        val translated = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        require(translated.isNotEmpty()) { "DeepSeek 返回了空译文" }
        val usageJson = response.getJSONObject("usage")
        val usage = TranslationUsage(
            usageJson.optInt("prompt_tokens"), usageJson.optInt("prompt_cache_hit_tokens"),
            usageJson.optInt("prompt_cache_miss_tokens"), usageJson.optInt("completion_tokens"),
        )
        val cost = costSince(before)
        TranslationResult(translated, usage, cost.first, cost.second)
    }

    suspend fun translateFullBatch(
        sentences: List<SentenceEntity>,
        documentContext: String,
        adjacentContext: String,
        allowSourceCorrection: Boolean,
    ): FullTranslationBatchResult = withContext(Dispatchers.IO) {
        require(sentences.isNotEmpty()) { "全文翻译批次不能为空" }
        val expectedIds = sentences.map(SentenceEntity::id)
        val input = JSONArray().apply {
            sentences.forEach { sentence ->
                put(
                    JSONObject()
                        .put("id", sentence.id)
                        .put("page", sentence.pageNumber + 1)
                        .put("source", sentence.displayText),
                )
            }
        }
        val system = buildString {
            append(
                "你是严谨的学术论文校对与翻译助手。请结合全文档案和相邻段落处理每个句子。",
            )
            if (allowSourceCorrection) {
                append(
                    "corrected_source 只修复有明确依据的 OCR 字符、乱码、错误断词和缺失空格；" +
                        "不得润色、改写事实、翻译原文、改动 LaTeX 公式或引用编号。无法确定时原样保留。",
                )
            } else {
                append("校正功能已关闭，corrected_source 必须逐字复制输入 source，不得进行任何修改。")
            }
            append(
                    "原文中的 \\[ 与 \\] 是数学公式的开始和结束边界，不是普通方括号；" +
                    "从 \\[ 到对应 \\]（包括边界符号）的内容必须逐字符原样复制到 corrected_source，" +
                    "并在 zh_translation 中原样保留，不得解释、改写或重新生成公式。" +
                    "zh_translation 使用简体中文，统一全文术语并忠实保留数字和引用。" +
                    "必须返回 JSON 对象，格式为 {\"items\":[{\"id\":\"原ID\",\"corrected_source\":\"校正后的原文\",\"zh_translation\":\"中文译文\"}]}，" +
                    "每个输入 ID 恰好返回一次，不得添加其他 ID 或解释。\n",
            )
            if (documentContext.isNotBlank()) append("全文档案：\n$documentContext\n")
            if (adjacentContext.isNotBlank()) append("批次前后文（只作参考，不要输出）：\n$adjacentContext")
        }
        val body = JSONObject()
            .put("model", settings.settings.value.model)
            .put("stream", false)
            .put("temperature", 0)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("max_tokens", 6000)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", input.toString())),
            )
        val response = execute("chat/completions", "POST", body)
        val raw = response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val items = parseFullTranslationResponse(raw, expectedIds)
        val usageJson = response.getJSONObject("usage")
        FullTranslationBatchResult(
            items = items,
            usage = TranslationUsage(
                usageJson.optInt("prompt_tokens"), usageJson.optInt("prompt_cache_hit_tokens"),
                usageJson.optInt("prompt_cache_miss_tokens"), usageJson.optInt("completion_tokens"),
            ),
        )
    }

    suspend fun balance(): DeepSeekBalance = withContext(Dispatchers.IO) {
        val json = execute("user/balance", "GET", null)
        val array = json.optJSONArray("balance_infos") ?: JSONArray()
        DeepSeekBalance(json.optBoolean("is_available"), (0 until array.length()).map { index ->
            array.getJSONObject(index).run {
                BalanceInfo(getString("currency"), getString("total_balance"), getString("granted_balance"), getString("topped_up_balance"))
            }
        })
    }

    private suspend fun execute(path: String, method: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder().url("https://api.deepseek.com/$path")
            .header("Authorization", "Bearer ${settings.requireApiKey()}").header("Content-Type", "application/json")
        val request = if (method == "GET") builder.get().build() else builder.post(requireNotNull(body).toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                val remote = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                val local = when (response.code) {
                    401 -> "API Key 无效"; 402 -> "DeepSeek 余额不足"; 429 -> "请求过于频繁"
                    500, 503 -> "DeepSeek 服务暂时不可用"; else -> "DeepSeek 请求失败（${response.code}）"
                }
                throw IOException(if (remote.isBlank()) local else "$local：$remote")
            }
            return JSONObject(raw)
        }
    }

    private suspend fun costSince(before: DeepSeekBalance?): Pair<String, String> {
        if (before == null) return "" to ""
        val after = runCatching { balance() }.getOrNull() ?: return "" to ""
        val currency = before.items.firstOrNull()?.currency.orEmpty()
        val beforeAmount = before.items.firstOrNull { it.currency == currency }?.totalBalance?.toBigDecimalOrNull()
        val afterAmount = after.items.firstOrNull { it.currency == currency }?.totalBalance?.toBigDecimalOrNull()
        if (beforeAmount == null || afterAmount == null) return "" to ""
        return currency to (beforeAmount - afterAmount).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
    }

    private fun TranslationEntity.asResult() = TranslationResult(
        translatedText,
        TranslationUsage(promptTokens, cacheHitTokens, cacheMissTokens, completionTokens),
        costCurrency,
        costAmount,
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

internal fun parseFullTranslationResponse(raw: String, expectedIds: List<String>): List<FullTranslationItem> {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val array = JSONObject(clean).getJSONArray("items")
    val items = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        FullTranslationItem(
            sentenceId = item.getString("id"),
            correctedText = item.getString("corrected_source").trim(),
            translatedText = item.getString("zh_translation").trim(),
        )
    }
    require(items.map(FullTranslationItem::sentenceId).toSet().size == items.size) { "DeepSeek 返回了重复句子" }
    require(items.map(FullTranslationItem::sentenceId).toSet() == expectedIds.toSet()) { "DeepSeek 返回的句子与请求不匹配" }
    require(items.all { it.correctedText.isNotEmpty() && it.translatedText.isNotEmpty() }) { "DeepSeek 返回了空的校正文或译文" }
    val byId = items.associateBy(FullTranslationItem::sentenceId)
    return expectedIds.map { id -> requireNotNull(byId[id]) }
}
