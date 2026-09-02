package com.samreader.app.document

/**
 * Places every confirmed formula into one semantic block and assembles text/formula spans once.
 * Matching is based only on typed model output and source glyph coordinates. It never inspects
 * token counts, punctuation, or formula-looking characters.
 */
internal fun assembleTypedSpans(
    canonicalText: CanonicalPageText,
    regions: List<LayoutRegion>,
    formulaOwners: Map<Int, List<RecognizedFormula>>,
): List<PositionedBlock> {
    val placements = formulaOwners.entries.flatMap { (owner, formulas) ->
        formulas.map { formula -> FormulaPlacement(owner, formula) }
    }
    val consumed = BooleanArray(placements.size)
    val inserted = BooleanArray(placements.size)
    val intersectsUnconsumedText = BooleanArray(placements.size)
    val formulaNumbers = regions.filter { it.label == "formula_number" }

    val assembled = canonicalText.blocks.mapIndexed { blockIndex, block ->
        val source = canonicalText.sources.getOrElse(blockIndex) { CanonicalBlockSource.NONE }
        block.copy(lines = buildList {
            block.lines.forEach { line ->
                if (formulaNumbers.any { number -> line.isFullyOwnedBy(number) }) {
                    return@forEach
                }
                val owner = placements.indices
                    .filter { index ->
                        placements[index].ownerIndex == blockIndex &&
                            line.isFullyOwnedBy(placements[index].formula.region)
                    }
                    .maxByOrNull { index -> placements[index].formula.confidence }
                if (owner != null) {
                    consumed[owner] = true
                    if (!inserted[owner] && placements[owner].formula.region.type == FormulaRegionType.INLINE) {
                        add(placements[owner].formula.toPositionedLine())
                        inserted[owner] = true
                    }
                    return@forEach
                }

                val split = if (source == CanonicalBlockSource.VISUAL_OCR) {
                    line.replaceOwnedOcrGlyphs(blockIndex, placements, consumed, inserted)
                } else {
                    null
                }
                if (split != null) {
                    addAll(split)
                    return@forEach
                }

                placements.indices.forEach { index ->
                    if (placements[index].ownerIndex == blockIndex &&
                        line.intersects(placements[index].formula.region)
                    ) {
                        intersectsUnconsumedText[index] = true
                    }
                }
                add(line)
            }
        })
    }.toMutableList()

    placements.forEachIndexed { index, placement ->
        if (placement.formula.region.type != FormulaRegionType.INLINE) return@forEachIndexed
        if (inserted[index]) return@forEachIndexed
        if (!consumed[index] && intersectsUnconsumedText[index]) return@forEachIndexed
        val block = assembled.getOrNull(placement.ownerIndex) ?: return@forEachIndexed
        val formulaLine = placement.formula.toPositionedLine()
        val lines = block.lines.toMutableList()
        val insertionIndex = lines.indexOfFirst { it.top >= formulaLine.bottom }
            .let { if (it < 0) lines.size else it }
        lines.add(insertionIndex, formulaLine)
        assembled[placement.ownerIndex] = block.copy(lines = lines)
    }
    return assembled
}

/**
 * OCR commonly returns prose and an inline formula as one line. In that case the formula model
 * owns a contiguous glyph range inside the OCR line. Replace only that owned range, in source
 * order, and keep the surrounding OCR text untouched. Native PDF text is intentionally excluded:
 * a readable embedded text layer remains authoritative unless complete native cells are owned.
 */
private fun PositionedLine.replaceOwnedOcrGlyphs(
    blockIndex: Int,
    placements: List<FormulaPlacement>,
    consumed: BooleanArray,
    inserted: BooleanArray,
): List<PositionedLine>? {
    if (glyphs.isEmpty()) return null
    val characterIndices = text.indices.filterNot { text[it].isWhitespace() }
    if (characterIndices.size != glyphs.size) return null
    val ranges = placements.indices.mapNotNull { index ->
        val placement = placements[index]
        if (placement.ownerIndex != blockIndex || placement.formula.region.type != FormulaRegionType.INLINE) {
            return@mapNotNull null
        }
        val owned = glyphs.indices.filter { glyphIndex ->
            val glyph = glyphs[glyphIndex]
            placement.formula.region.contains(glyph.centerX, glyph.centerY)
        }
        if (owned.isEmpty()) return@mapNotNull null
        val firstGlyph = owned.first()
        val lastGlyph = owned.last()
        if (owned.size != lastGlyph - firstGlyph + 1) return@mapNotNull null
        FormulaTextRange(index, characterIndices[firstGlyph], characterIndices[lastGlyph])
    }.sortedBy(FormulaTextRange::start)
    if (ranges.isEmpty() || ranges.zipWithNext().any { (a, b) -> a.end >= b.start }) return null

    return buildList {
        var cursor = 0
        ranges.forEach { range ->
            addTextSlice(this@replaceOwnedOcrGlyphs, characterIndices, cursor, range.start)
            if (!inserted[range.placementIndex]) {
                add(placements[range.placementIndex].formula.toPositionedLine())
                inserted[range.placementIndex] = true
            }
            consumed[range.placementIndex] = true
            cursor = range.end + 1
        }
        addTextSlice(this@replaceOwnedOcrGlyphs, characterIndices, cursor, text.length)
    }
}

private fun MutableList<PositionedLine>.addTextSlice(
    source: PositionedLine,
    characterIndices: List<Int>,
    start: Int,
    endExclusive: Int,
) {
    var contentStart = start
    var contentEnd = endExclusive
    while (contentStart < contentEnd && source.text[contentStart].isWhitespace()) contentStart++
    while (contentEnd > contentStart && source.text[contentEnd - 1].isWhitespace()) contentEnd--
    if (contentStart == contentEnd) return
    val sliceGlyphs = source.glyphs.filterIndexed { index, _ ->
        characterIndices[index] in contentStart until contentEnd
    }
    if (sliceGlyphs.isEmpty()) return
    add(source.copy(
        text = source.text.substring(contentStart, contentEnd),
        left = sliceGlyphs.minOf(PositionedGlyph::left),
        top = sliceGlyphs.minOf(PositionedGlyph::top),
        right = sliceGlyphs.maxOf(PositionedGlyph::right),
        bottom = sliceGlyphs.maxOf(PositionedGlyph::bottom),
        confidence = sliceGlyphs.map(PositionedGlyph::confidence).average().toFloat(),
        glyphs = sliceGlyphs,
    ))
}

private data class FormulaTextRange(
    val placementIndex: Int,
    val start: Int,
    val end: Int,
)

/** Gives every visual formula one semantic owner using the detected instance under its center. */
internal fun assignFormulasToRegions(
    regions: List<LayoutRegion>,
    formulas: List<RecognizedFormula>,
): Map<Int, List<RecognizedFormula>> = buildMap {
    formulas.forEach { formula ->
        val centerX = (formula.region.left + formula.region.right) / 2f
        val centerY = (formula.region.top + formula.region.bottom) / 2f
        val owner = regions.indices.mapNotNull { index ->
            val region = regions[index]
            val compatible = when (formula.region.type) {
                FormulaRegionType.INLINE -> region.requiresOcr && region.selectableBody
                FormulaRegionType.DISPLAY -> region.label == "display_formula"
                else -> false
            }
            if (!compatible || centerX !in region.left..region.right || centerY !in region.top..region.bottom) {
                null
            } else {
                FormulaOwner(index, region.ownsPoint(centerX, centerY), region.score)
            }
        }.maxWithOrNull(compareBy(FormulaOwner::maskOwnsCenter, FormulaOwner::regionConfidence))
            ?.regionIndex ?: return@forEach
        put(owner, get(owner).orEmpty() + formula)
    }
}

private data class FormulaPlacement(
    val ownerIndex: Int,
    val formula: RecognizedFormula,
)

private data class FormulaOwner(
    val regionIndex: Int,
    val maskOwnsCenter: Boolean,
    val regionConfidence: Float,
)

private fun RecognizedFormula.toPositionedLine(): PositionedLine = PositionedLine(
    text = latex,
    left = region.left,
    top = region.top,
    right = region.right,
    bottom = region.bottom,
    confidence = confidence,
    glyphs = listOf(PositionedGlyph(latex, region.left, region.top, region.right, region.bottom, confidence)),
)

private fun PositionedLine.isFullyOwnedBy(region: FormulaRegion): Boolean =
    glyphs.isNotEmpty() && region.containsBounds(left, top, right, bottom) &&
        glyphs.all { glyph -> region.contains(glyph.centerX, glyph.centerY) }

private fun PositionedLine.isFullyOwnedBy(region: LayoutRegion): Boolean =
    glyphs.isNotEmpty() && region.containsBounds(left, top, right, bottom) &&
        glyphs.all { glyph -> region.ownsPoint(glyph.centerX, glyph.centerY) }

private fun PositionedLine.intersects(region: FormulaRegion): Boolean = if (glyphs.isNotEmpty()) {
    glyphs.any { glyph -> region.contains(glyph.centerX, glyph.centerY) }
} else {
    minOf(right, region.right) > maxOf(left, region.left) &&
        minOf(bottom, region.bottom) > maxOf(top, region.top)
}

private fun FormulaRegion.contains(x: Float, y: Float): Boolean =
    x in left..right && y in top..bottom

private fun FormulaRegion.containsBounds(
    candidateLeft: Float,
    candidateTop: Float,
    candidateRight: Float,
    candidateBottom: Float,
): Boolean = candidateLeft >= left - MODEL_COORDINATE_TOLERANCE &&
    candidateTop >= top - MODEL_COORDINATE_TOLERANCE &&
    candidateRight <= right + MODEL_COORDINATE_TOLERANCE &&
    candidateBottom <= bottom + MODEL_COORDINATE_TOLERANCE

private fun LayoutRegion.containsBounds(
    candidateLeft: Float,
    candidateTop: Float,
    candidateRight: Float,
    candidateBottom: Float,
): Boolean = candidateLeft >= left - MODEL_COORDINATE_TOLERANCE &&
    candidateTop >= top - MODEL_COORDINATE_TOLERANCE &&
    candidateRight <= right + MODEL_COORDINATE_TOLERANCE &&
    candidateBottom <= bottom + MODEL_COORDINATE_TOLERANCE

private val PositionedGlyph.centerX: Float get() = (left + right) / 2f
private val PositionedGlyph.centerY: Float get() = (top + bottom) / 2f

// PP-DocLayoutV3 predicts on an 800x800 grid. Two grid cells absorb raster/box rounding only.
private const val MODEL_COORDINATE_TOLERANCE = 2f / 800f
