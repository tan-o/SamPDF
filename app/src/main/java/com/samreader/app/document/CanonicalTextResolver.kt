package com.samreader.app.document

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

/**
 * Fills source text into layout regions at glyph granularity, the span-to-block pattern used by
 * MinerU: every glyph belongs to the highest-ranked region whose instance mask owns its center
 * (see [LayoutRegion.glyphOwnershipRank]). A source line that crosses a region boundary, such as
 * a PDF text cell spanning a column gutter or ending in an equation number, is split so that each
 * region keeps only its own characters, in the source order. Glyphs outside every mask stay with
 * their line neighbours. Lines without aligned glyphs are owned as a whole by their center.
 */
internal fun assignLinesToRegions(
    regions: List<LayoutRegion>,
    lines: List<PositionedLine>,
): List<PositionedBlock> {
    val assigned = Array(regions.size) { mutableListOf<PositionedLine>() }
    lines.forEach { line ->
        line.splitByOwner(regions).forEach { (owner, part) ->
            if (regions[owner].requiresOcr || regions[owner].label == "display_formula") assigned[owner] += part
        }
    }
    return regions.mapIndexed { index, region ->
        PositionedBlock(
            // Both ML Kit OCR and the native PDF extractor already expose a source reading order.
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

private fun PositionedLine.splitByOwner(regions: List<LayoutRegion>): List<Pair<Int, PositionedLine>> {
    if (!hasAlignedGlyphs) {
        val owner = ownerAt(regions, (left + right) / 2f, (top + bottom) / 2f) ?: return emptyList()
        return listOf(owner to this)
    }
    val owners = glyphs.map { ownerAt(regions, it.centerX, it.centerY) }.toMutableList()
    if (owners.all { it == null }) return emptyList()
    // Unowned glyphs (mask edges, rounding) follow the preceding owned glyph, else the next one.
    for (index in owners.indices) if (owners[index] == null) owners[index] = owners.getOrNull(index - 1)
    for (index in owners.indices.reversed()) if (owners[index] == null) owners[index] = owners.getOrNull(index + 1)
    if (owners.distinct().size == 1) return listOf(requireNotNull(owners.first()) to this)
    val parts = mutableListOf<Pair<Int, PositionedLine>>()
    var runStart = 0
    for (index in 1..owners.size) {
        if (index == owners.size || owners[index] != owners[runStart]) {
            sliceGlyphs(runStart, index)?.let { parts += requireNotNull(owners[runStart]) to it }
            runStart = index
        }
    }
    return parts
}

private fun ownerAt(regions: List<LayoutRegion>, x: Float, y: Float): Int? =
    regions.indices
        .filter { regions[it].glyphOwnershipRank > 0 && regions[it].ownsPoint(x, y) }
        .maxWithOrNull(compareBy<Int> { regions[it].glyphOwnershipRank }.thenBy { regions[it].score })

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
