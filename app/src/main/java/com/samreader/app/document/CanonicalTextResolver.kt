package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType

internal enum class CanonicalBlockSource {
    NATIVE_PDF,
    VISUAL_OCR,
    NONE,
}

internal data class CanonicalPageText(
    val blocks: List<PositionedBlock>,
    val sources: List<CanonicalBlockSource>,
)

/**
 * Resolves exactly one lexical source for every semantic region. A readable embedded PDF text
 * layer is authoritative; visual OCR is only used for regions where native text is absent or
 * internally corrupt. OCR disagreement is deliberately not allowed to veto valid source text.
 */
internal fun resolveCanonicalText(
    regions: List<LayoutRegion>,
    nativeLines: List<PositionedLine>,
    ocrLines: List<PositionedLine>,
): CanonicalPageText {
    val nativeBlocks = assignLinesToRegions(regions, nativeLines)
    val ocrBlocks = assignLinesToRegions(regions, ocrLines)
    val sources = ArrayList<CanonicalBlockSource>(regions.size)
    val blocks = regions.indices.map { index ->
        val region = regions[index]
        val native = nativeBlocks[index]
        val ocr = ocrBlocks[index]
        when {
            !region.requiresOcr -> {
                sources += CanonicalBlockSource.NONE
                ocr.copy(lines = emptyList())
            }
            native.hasReliableNativeText() -> {
                sources += CanonicalBlockSource.NATIVE_PDF
                native.copy(lines = native.lines.map { it.copy(confidence = 1f) })
            }
            ocr.lines.isNotEmpty() -> {
                sources += CanonicalBlockSource.VISUAL_OCR
                ocr
            }
            else -> {
                sources += CanonicalBlockSource.NONE
                ocr.copy(lines = emptyList())
            }
        }
    }
    return CanonicalPageText(blocks, sources)
}

internal fun requiresVisualOcr(
    regions: List<LayoutRegion>,
    nativeLines: List<PositionedLine>,
): Boolean {
    val native = resolveCanonicalText(regions, nativeLines, emptyList())
    return regions.indices.any { regions[it].requiresOcr && native.sources[it] != CanonicalBlockSource.NATIVE_PDF }
}

internal fun assignLinesToRegions(
    regions: List<LayoutRegion>,
    lines: List<PositionedLine>,
): List<PositionedBlock> {
    val assigned = Array(regions.size) { mutableListOf<PositionedLine>() }
    val textRegionIndices = regions.indices.filter { regions[it].requiresOcr }
    lines.forEach { line ->
        val points = line.glyphs.takeIf(List<PositionedGlyph>::isNotEmpty)?.map { glyph ->
            (glyph.left + glyph.right) / 2f to (glyph.top + glyph.bottom) / 2f
        } ?: listOf((line.left + line.right) / 2f to (line.top + line.bottom) / 2f)
        val best = textRegionIndices.mapNotNull { index ->
            val region = regions[index]
            val ownedPoints = points.count { (x, y) -> region.ownsPoint(x, y) }
            if (ownedPoints == 0) null else LineOwner(index, ownedPoints, region.score)
        }.maxWithOrNull(compareBy(LineOwner::ownedPoints, LineOwner::regionConfidence))?.regionIndex
        if (best != null) assigned[best] += line
    }
    return regions.mapIndexed { index, region ->
        PositionedBlock(
            // Both ML Kit OCR and the native PDF extractor already expose a source reading order.
            // Re-sorting here destroys that order when adjacent OCR lines have slightly different
            // left edges (indents, formulas, or justified text).
            lines = assigned[index].toList(),
            left = region.left,
            top = region.top,
            right = region.right,
            bottom = region.bottom,
            isCaption = region.caption,
            selectableBody = region.selectableBody,
            type = region.blockType,
            readingOrder = region.readingOrder,
            layoutLabel = region.label,
        )
    }
}

private data class LineOwner(
    val regionIndex: Int,
    val ownedPoints: Int,
    val regionConfidence: Float,
)

private fun PositionedBlock.hasReliableNativeText(): Boolean {
    if (lines.isEmpty()) return false
    val text = lines.joinToString(" ", transform = PositionedLine::text)
    if (text.none(Char::isLetterOrDigit)) return false
    val meaningful = text.count {
        it.isLetterOrDigit() || it.isWhitespace() || it in COMMON_TEXT_PUNCTUATION
    }
    return meaningful.toFloat() / text.length.coerceAtLeast(1) >= MIN_NATIVE_READABILITY
}

private const val MIN_NATIVE_READABILITY = .82f
private const val COMMON_TEXT_PUNCTUATION = ".,;:!?()[]{}'\"-/+*=<>%&@#_\\"
