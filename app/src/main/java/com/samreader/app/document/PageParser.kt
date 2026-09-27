package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType

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
        return anchorDisplayFormulas(splitAroundDisplayFormulas(PageSemanticRefiner.refine(blocks, pageNumber)))
    }

    /**
     * A display formula continues the paragraph above it in its column. The layout model's
     * reading order occasionally places it after the text that follows it; each formula is moved
     * to directly after the nearest paragraph or formula above it in the same column.
     */
    private fun anchorDisplayFormulas(blocks: List<PositionedBlock>): List<PositionedBlock> {
        val result = blocks.toMutableList()
        blocks.filter { it.layoutLabel == "display_formula" && it.lines.isNotEmpty() }.sortedBy(PositionedBlock::top).forEach { formula ->
            val above = result
                .filter { block ->
                    block !== formula && block.lines.isNotEmpty() &&
                        (block.type == LayoutBlockType.PARAGRAPH || block.layoutLabel == "display_formula") &&
                        block.bottom <= formula.top + VERTICAL_TOLERANCE && horizontalOverlap(block, formula) >= .5f
                }
                .maxByOrNull(PositionedBlock::bottom) ?: return@forEach
            result.remove(formula)
            result.add(result.indexOf(above) + 1, formula)
        }
        return result.mapIndexed { index, block -> block.copy(readingOrder = index) }
    }

    /**
     * The layout model sometimes returns one text box that encloses a display formula, so the
     * lines after the formula would be read before it. As MinerU does for interline equations,
     * such a block is split at the formula: its lines above precede the formula and its lines
     * below follow it. Reading orders are renumbered to the resulting sequence.
     */
    private fun splitAroundDisplayFormulas(blocks: List<PositionedBlock>): List<PositionedBlock> {
        val result = blocks.toMutableList()
        blocks.filter { it.layoutLabel == "display_formula" && it.lines.isNotEmpty() }.forEach { formula ->
            val enclosing = result.firstOrNull { block ->
                block !== formula && block.selectableBody && block.layoutLabel != "display_formula" &&
                    block.lines.any { (it.top + it.bottom) / 2f < formula.top } &&
                    block.lines.any { (it.top + it.bottom) / 2f > formula.bottom } &&
                    horizontalOverlap(block, formula) >= .5f
            } ?: return@forEach
            val (below, above) = enclosing.lines.partition { (it.top + it.bottom) / 2f > formula.bottom }
            val enclosingIndex = result.indexOf(enclosing)
            val readBeforeFormula = enclosingIndex < result.indexOf(formula)
            result.removeAt(enclosingIndex)
            val aboveIndex = if (readBeforeFormula) enclosingIndex else result.indexOf(formula)
            result.add(aboveIndex, enclosing.copy(lines = above, bottom = minOf(enclosing.bottom, formula.top)))
            result.add(result.indexOf(formula) + 1, enclosing.copy(lines = below, top = maxOf(enclosing.top, formula.bottom)))
        }
        return result
    }

    /** Fraction of [formula]'s width that [block] covers. */
    private fun horizontalOverlap(block: PositionedBlock, formula: PositionedBlock): Float {
        val overlap = (minOf(block.right, formula.right) - maxOf(block.left, formula.left)).coerceAtLeast(0f)
        return overlap / (formula.right - formula.left).coerceAtLeast(.001f)
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
    private const val VERTICAL_TOLERANCE = .01f
}
