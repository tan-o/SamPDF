package com.samreader.app.document

/**
 * Turns one page's observations (layout regions, native PDF lines, OCR lines and recognized
 * formulas) into typed layout blocks in the layout model's reading order.
 */
internal object PageParser {
    fun parse(
        pageNumber: Int,
        regions: List<LayoutRegion>,
        nativeLines: List<PositionedLine>,
        ocrLines: List<PositionedLine>,
        formulas: List<RecognizedFormula>,
    ): List<PositionedBlock> {
        val canonicalText = resolveCanonicalText(regions, nativeLines, ocrLines)
        val nativeBlocks = assignLinesToRegions(regions, nativeLines)
        val ownedFormulas = assignFormulasToRegions(regions, formulas)
        val blocks = assembleTypedSpans(canonicalText, ownedFormulas).mapIndexed { index, block ->
            if (block.layoutLabel != "display_formula") return@mapIndexed block
            val owned = ownedFormulas[index].orEmpty()
            val ownedElsewhere = owned.isEmpty() && formulas.any { overlap(block, it.region) >= MIN_FORMULA_OVERLAP }
            if (ownedElsewhere) {
                block.copy(lines = emptyList(), selectableBody = false)
            } else {
                encodeDisplayFormula(block, nativeBlocks[index].lines, owned)
            }
        }
        return PageSemanticRefiner.refine(blocks, pageNumber)
    }

    /** A display formula becomes one `\[LaTeX\]` line: visual recognition first, else its native PDF text. */
    private fun encodeDisplayFormula(
        block: PositionedBlock,
        nativeLines: List<PositionedLine>,
        formulas: List<RecognizedFormula>,
    ): PositionedBlock {
        val visual = formulas
            .filter { it.region.type == FormulaRegionType.DISPLAY }
            .maxByOrNull { overlap(block, it.region) }
            ?.takeIf { overlap(block, it.region) >= MIN_FORMULA_OVERLAP }
        if (visual != null) {
            val region = visual.region
            return block.copy(lines = listOf(PositionedLine(
                text = visual.latex,
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                confidence = visual.confidence,
                isFormula = true,
            )))
        }
        val latex = FormulaLatexEncoder.encode(nativeLines.joinToString(" ", transform = PositionedLine::text))
        if (latex.isBlank()) return block.copy(lines = emptyList())
        return block.copy(lines = listOf(PositionedLine(
            text = latex,
            left = nativeLines.minOf(PositionedLine::left),
            top = nativeLines.minOf(PositionedLine::top),
            right = nativeLines.maxOf(PositionedLine::right),
            bottom = nativeLines.maxOf(PositionedLine::bottom),
            confidence = 1f,
            isFormula = true,
        )))
    }

    /** Fraction of the formula box covered by the block. */
    private fun overlap(block: PositionedBlock, formula: FormulaRegion): Float {
        val intersection = (minOf(block.right, formula.right) - maxOf(block.left, formula.left)).coerceAtLeast(0f) *
            (minOf(block.bottom, formula.bottom) - maxOf(block.top, formula.top)).coerceAtLeast(0f)
        val formulaArea = (formula.right - formula.left) * (formula.bottom - formula.top)
        return if (formulaArea <= 0f) 0f else intersection / formulaArea
    }

    private const val MIN_FORMULA_OVERLAP = .2f
}
