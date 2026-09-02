package com.samreader.app.document

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.ParsingTuning
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

data class LayoutRegion(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val readingOrder: Int = Int.MAX_VALUE,
    val mask: ByteArray = ByteArray(0),
) {
    init {
        require(mask.isEmpty() || mask.size == MASK_SIZE * MASK_SIZE)
    }

    val label: String get() = LABELS[classId]

    val blockType: String get() = when (label) {
        "doc_title" -> LayoutBlockType.DOCUMENT_TITLE
        "content" -> LayoutBlockType.CONTENTS
        "paragraph_title" -> LayoutBlockType.SECTION_TITLE
        "abstract" -> LayoutBlockType.ABSTRACT
        "reference", "reference_content" -> LayoutBlockType.REFERENCE
        "footnote", "vision_footnote" -> LayoutBlockType.FOOTNOTE
        "aside_text" -> LayoutBlockType.SIDEBAR
        "figure_title" -> LayoutBlockType.CAPTION
        "image", "header_image", "footer_image", "seal" -> LayoutBlockType.IMAGE
        "chart" -> LayoutBlockType.CHART
        "table" -> LayoutBlockType.TABLE
        "header" -> LayoutBlockType.HEADER
        "footer" -> LayoutBlockType.FOOTER
        "number" -> LayoutBlockType.PAGE_NUMBER
        "display_formula", "inline_formula", "formula_number" -> LayoutBlockType.EQUATION
        else -> LayoutBlockType.PARAGRAPH
    }

    val requiresOcr: Boolean get() = label in OCR_LABELS
    val selectableBody: Boolean get() = label in SELECTABLE_LABELS
    val caption: Boolean get() = label == "figure_title"

    /** Fraction of this OCR line's sampling grid covered by the instance mask. */
    fun coverageOf(line: PositionedLine): Float {
        if (mask.isEmpty()) return 0f
        val x0 = (line.left.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
        val y0 = (line.top.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
        val x1 = (line.right.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(x0 + 1, MASK_SIZE)
        val y1 = (line.bottom.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(y0 + 1, MASK_SIZE)
        var covered = 0
        var total = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (mask[y * MASK_SIZE + x].toInt() != 0) covered++
            total++
        }
        return if (total == 0) 0f else covered.toFloat() / total
    }

    /** True when the model instance mask, or its box when no mask is available, owns the point. */
    fun ownsPoint(x: Float, y: Float): Boolean {
        if (x !in left..right || y !in top..bottom) return false
        if (mask.isEmpty()) return true
        val maskX = (x.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
        val maskY = (y.coerceIn(0f, 1f) * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
        return mask[maskY * MASK_SIZE + maskX].toInt() != 0
    }

    companion object {
        const val MASK_SIZE = 200
        private val LABELS = listOf(
            "abstract", "algorithm", "aside_text", "chart", "content",
            "display_formula", "doc_title", "figure_title", "footer", "footer_image",
            "footnote", "formula_number", "header", "header_image", "image",
            "inline_formula", "number", "paragraph_title", "reference", "reference_content",
            "seal", "table", "text", "vertical_text", "vision_footnote",
        )
        private val OCR_LABELS = setOf(
            "abstract", "algorithm", "aside_text", "content", "doc_title", "figure_title",
            "footer", "footnote", "header", "number", "paragraph_title", "reference",
            "reference_content", "text", "vertical_text", "vision_footnote",
        )
        private val SELECTABLE_LABELS = setOf(
            "abstract", "algorithm", "aside_text", "content", "display_formula", "doc_title",
            "figure_title", "footnote", "paragraph_title", "reference", "reference_content",
            "text", "vertical_text", "vision_footnote",
        )
    }
}

object DocumentLayoutModel {
    const val MODEL_ID = "pp-doclayout-v3-fp32"
    private const val MODEL_ASSET = "models/pp_doclayout_v3_fp32.onnx"
    private const val INPUT_SIZE = 800
    private const val QUERY_COUNT = 300
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionLock = Any()
    @Volatile private var session: OrtSession? = null

    private data class Candidate(val query: Int, val classId: Int, val score: Float)

    fun detect(
        context: Context,
        bitmap: Bitmap,
        scoreThreshold: Float = ParsingTuning.DEFAULT_LAYOUT_CONFIDENCE,
    ): List<LayoutRegion> {
        val normalizedThreshold = ParsingTuning.normalizeLayoutConfidence(scoreThreshold)
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        return try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val imageBuffer = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(3) { channel ->
                val shift = 16 - channel * 8
                pixels.forEach { pixel -> imageBuffer.put(((pixel ushr shift) and 0xff) / 255f) }
            }
            imageBuffer.rewind()
            OnnxTensor.createTensor(
                environment,
                imageBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { input ->
                getSession(context).run(mapOf("pixel_values" to input)).use { output ->
                    val logits = (output[0] as OnnxTensor).floatBuffer
                    val boxes = (output[1] as OnnxTensor).floatBuffer
                    val masks = (output[2] as OnnxTensor).floatBuffer
                    val orderLogits = (output[3] as OnnxTensor).floatBuffer

                    val candidates = buildList {
                        repeat(QUERY_COUNT) { query ->
                            repeat(CLASS_COUNT) { classId ->
                                add(Candidate(
                                    query,
                                    classId,
                                    sigmoid(logits.get(query * CLASS_COUNT + classId)),
                                ))
                            }
                        }
                    }.sortedByDescending(Candidate::score)
                        .take(QUERY_COUNT)
                        .filter { candidate -> candidate.score >= normalizedThreshold }
                    val ranks = readingOrderRanks(orderLogits)
                    candidates.sortedBy { ranks[it.query] }.map { candidate ->
                        val mask = ByteArray(LayoutRegion.MASK_SIZE * LayoutRegion.MASK_SIZE)
                        val maskOffset = candidate.query * mask.size
                        mask.indices.forEach { index ->
                            if (masks.get(maskOffset + index) > 0f) mask[index] = 1
                        }
                        val boxOffset = candidate.query * 4
                        val centerX = boxes.get(boxOffset)
                        val centerY = boxes.get(boxOffset + 1)
                        val width = boxes.get(boxOffset + 2)
                        val height = boxes.get(boxOffset + 3)
                        LayoutRegion(
                            classId = candidate.classId,
                            score = candidate.score,
                            left = (centerX - width / 2f).coerceIn(0f, 1f),
                            top = (centerY - height / 2f).coerceIn(0f, 1f),
                            right = (centerX + width / 2f).coerceIn(0f, 1f),
                            bottom = (centerY + height / 2f).coerceIn(0f, 1f),
                            readingOrder = ranks[candidate.query],
                            mask = mask,
                        )
                    }
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun release() = synchronized(sessionLock) {
        session?.close()
        session = null
    }

    private fun readingOrderRanks(logits: FloatBuffer): IntArray {
        val votes = FloatArray(QUERY_COUNT)
        for (query in 0 until QUERY_COUNT) {
            for (other in 0 until query) {
                votes[query] += sigmoid(logits.get(other * QUERY_COUNT + query))
            }
            for (other in query + 1 until QUERY_COUNT) {
                votes[query] += 1f - sigmoid(logits.get(query * QUERY_COUNT + other))
            }
        }
        val pointers = (0 until QUERY_COUNT).sortedBy { votes[it] }
        return IntArray(QUERY_COUNT).also { ranks ->
            pointers.forEachIndexed { rank, query -> ranks[query] = rank }
        }
    }

    private fun sigmoid(value: Float): Float = if (value >= 0f) {
        1f / (1f + exp(-value.toDouble()).toFloat())
    } else {
        val exponential = exp(value.toDouble()).toFloat()
        exponential / (1f + exponential)
    }

    private fun getSession(context: Context): OrtSession = session ?: synchronized(sessionLock) {
        session ?: context.assets.openFd(MODEL_ASSET).use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                val model = channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
                OrtSession.SessionOptions().use { options ->
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.setCPUArenaAllocator(false)
                    options.setMemoryPatternOptimization(false)
                    options.setIntraOpNumThreads(4)
                    options.setInterOpNumThreads(1)
                    environment.createSession(model, options).also { session = it }
                }
            }
        }
    }

    private const val CLASS_COUNT = 25
}
