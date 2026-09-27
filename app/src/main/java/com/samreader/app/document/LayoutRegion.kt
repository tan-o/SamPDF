package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import java.nio.FloatBuffer
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
        "image", "seal" -> LayoutBlockType.IMAGE
        "chart" -> LayoutBlockType.CHART
        "table" -> LayoutBlockType.TABLE
        // Logos and emblems in the page margins are page furniture, not figures.
        "header", "header_image" -> LayoutBlockType.HEADER
        "footer", "footer_image" -> LayoutBlockType.FOOTER
        "number" -> LayoutBlockType.PAGE_NUMBER
        "display_formula", "inline_formula", "formula_number" -> LayoutBlockType.EQUATION
        else -> LayoutBlockType.PARAGRAPH
    }

    val requiresOcr: Boolean get() = label in OCR_LABELS
    val selectableBody: Boolean get() = label in SELECTABLE_LABELS
    val caption: Boolean get() = label == "figure_title"

    /**
     * Priority with which this region claims a text glyph that several instance masks cover.
     * Formula structure is more specific than prose (PaddleX keeps everything a formula box
     * contains inside the formula), and prose outranks purely visual regions such as figures.
     * Inline formulas are nested spans inside prose and are placed later by the typed-span
     * assembler, so they never own glyphs here.
     */
    val glyphOwnershipRank: Int get() = when {
        label == "inline_formula" -> 0
        label == "display_formula" || label == "formula_number" -> 3
        requiresOcr -> 2
        else -> 1
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
        internal val LABELS = listOf(
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

/**
 * PP-DocLayoutV3 post-processing exactly as PaddleX ships it for PaddleOCR-VL
 * (`PPDocLayoutV3PostProcess` followed by `DetPostProcess` with the pipeline's
 * `layout_nms: True` and per-class `layout_merge_bboxes_mode`).
 */
internal object LayoutPostProcessor {
    const val QUERY_COUNT = 300
    private val CLASS_COUNT = LayoutRegion.LABELS.size
    private const val NMS_SAME_CLASS_IOU = .6f
    private const val NMS_OTHER_CLASS_IOU = .98f
    private const val CONTAINMENT_RATIO = .9f

    /** Classes whose PaddleOCR-VL merge mode is "large": anything they contain is dropped. */
    private val LARGE_MERGE_LABELS = setOf(
        "chart", "display_formula", "doc_title", "inline_formula", "paragraph_title",
    )

    fun decode(
        logits: FloatBuffer,
        boxes: FloatBuffer,
        masks: FloatBuffer,
        orderLogits: FloatBuffer,
        threshold: Float,
        pageAspectRatio: Float,
    ): List<LayoutRegion> {
        val (orderSequence, orderVotes) = readingOrder(orderLogits)
        val candidates = (0 until QUERY_COUNT * CLASS_COUNT)
            .map { index -> index to sigmoid(logits.get(index)) }
            .sortedByDescending { it.second }
            .take(QUERY_COUNT)
            .filter { it.second > threshold }
            .map { (index, score) ->
                val query = index / CLASS_COUNT
                val offset = query * 4
                val centerX = boxes.get(offset)
                val centerY = boxes.get(offset + 1)
                val width = boxes.get(offset + 2)
                val height = boxes.get(offset + 3)
                Candidate(
                    query = query,
                    classId = index % CLASS_COUNT,
                    score = score,
                    left = (centerX - width / 2f).coerceIn(0f, 1f),
                    top = (centerY - height / 2f).coerceIn(0f, 1f),
                    right = (centerX + width / 2f).coerceIn(0f, 1f),
                    bottom = (centerY + height / 2f).coerceIn(0f, 1f),
                )
            }
        val kept = mergeContainedBoxes(filterFullPageImages(nms(candidates), pageAspectRatio))
        return kept
            .sortedWith(compareBy<Candidate> { orderSequence[it.query] }.thenByDescending { orderVotes[it.query] })
            .map { candidate ->
                val mask = ByteArray(LayoutRegion.MASK_SIZE * LayoutRegion.MASK_SIZE)
                val maskOffset = candidate.query * mask.size
                mask.indices.forEach { index ->
                    if (masks.get(maskOffset + index) > 0f) mask[index] = 1
                }
                LayoutRegion(
                    classId = candidate.classId,
                    score = candidate.score,
                    left = candidate.left,
                    top = candidate.top,
                    right = candidate.right,
                    bottom = candidate.bottom,
                    readingOrder = orderSequence[candidate.query],
                    mask = mask,
                )
            }
    }

    internal data class Candidate(
        val query: Int,
        val classId: Int,
        val score: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val area: Float get() = (right - left) * (bottom - top)
        val label: String get() = LayoutRegion.LABELS[classId]
    }

    /** `nms(boxes, iou_same=0.6, iou_diff=0.98)`: a query predicted as two classes keeps one. */
    internal fun nms(candidates: List<Candidate>): List<Candidate> {
        val remaining = candidates.sortedByDescending(Candidate::score).toMutableList()
        val selected = mutableListOf<Candidate>()
        while (remaining.isNotEmpty()) {
            val current = remaining.removeAt(0)
            selected += current
            remaining.removeAll { other ->
                val limit = if (other.classId == current.classId) NMS_SAME_CLASS_IOU else NMS_OTHER_CLASS_IOU
                iou(current, other) >= limit
            }
        }
        return selected
    }

    /** Drops an `image` covering almost the whole page (a scanned-page background, not a figure). */
    internal fun filterFullPageImages(candidates: List<Candidate>, pageAspectRatio: Float): List<Candidate> {
        if (candidates.size <= 1) return candidates
        val areaLimit = if (pageAspectRatio > 1f) .82f else .93f
        return candidates.filterNot { it.label == "image" && it.area > areaLimit }.ifEmpty { candidates }
    }

    /** `layout_merge_bboxes_mode`: boxes 90% inside a "large"-mode class box are redundant. */
    internal fun mergeContainedBoxes(candidates: List<Candidate>): List<Candidate> =
        candidates.filterIndexed { index, inner ->
            candidates.indices.none { outerIndex ->
                val outer = candidates[outerIndex]
                outerIndex != index && outer.label in LARGE_MERGE_LABELS && containment(inner, outer) >= CONTAINMENT_RATIO
            }
        }

    /** `get_order`: pairwise precedence votes, then rank of each query in ascending vote order. */
    private fun readingOrder(logits: FloatBuffer): Pair<IntArray, FloatArray> {
        val votes = FloatArray(QUERY_COUNT)
        for (query in 0 until QUERY_COUNT) {
            for (other in 0 until query) {
                votes[query] += sigmoid(logits.get(other * QUERY_COUNT + query))
            }
            for (other in query + 1 until QUERY_COUNT) {
                votes[query] += 1f - sigmoid(logits.get(query * QUERY_COUNT + other))
            }
        }
        val sequence = IntArray(QUERY_COUNT)
        (0 until QUERY_COUNT).sortedBy { votes[it] }.forEachIndexed { rank, query -> sequence[query] = rank }
        return sequence to votes
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val intersection = intersection(a, b)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun containment(inner: Candidate, outer: Candidate): Float =
        if (inner.area <= 0f) 0f else intersection(inner, outer) / inner.area

    private fun intersection(a: Candidate, b: Candidate): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)

    private fun sigmoid(value: Float): Float = if (value >= 0f) {
        1f / (1f + exp(-value.toDouble()).toFloat())
    } else {
        val exponential = exp(value.toDouble()).toFloat()
        exponential / (1f + exponential)
    }
}
