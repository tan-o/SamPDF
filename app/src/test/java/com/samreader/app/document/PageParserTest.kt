package com.samreader.app.document

import org.junit.Assert.assertEquals
import org.junit.Test

class PageParserTest {
    @Test
    fun displayFormulaReadAfterTheTextBelowItIsMovedUnderTheParagraphAboveIt() {
        val regions = listOf(
            region("text", 0, .10f, .30f),
            region("text", 1, .45f, .60f),
            region("display_formula", 2, .32f, .42f),
        )
        val lines = listOf(line("by using", .20f), line("where z is noise.", .50f), line("E = mc2", .37f))

        val blocks = PageParser.parse(0, regions, lines, emptyList(), emptyList())

        assertEquals(listOf("by using", "formula", "where z is noise."), readingSequence(blocks))
    }

    @Test
    fun textBlockEnclosingADisplayFormulaIsSplitAroundIt() {
        val regions = listOf(region("text", 0, .10f, .60f), region("display_formula", 1, .32f, .42f))
        val lines = listOf(line("by using", .20f), line("E = mc2", .37f), line("where z is noise.", .50f))

        val blocks = PageParser.parse(0, regions, lines, emptyList(), emptyList())

        assertEquals(listOf("by using", "formula", "where z is noise."), readingSequence(blocks))
        assertEquals(blocks.indices.toList(), blocks.map(PositionedBlock::readingOrder))
    }

    private fun readingSequence(blocks: List<PositionedBlock>) = blocks
        .filter { it.lines.isNotEmpty() }
        .map { block -> if (block.layoutLabel == "display_formula") "formula" else block.lines.joinToString(" ") { it.text } }

    private fun region(label: String, order: Int, top: Float, bottom: Float) =
        LayoutRegion(LayoutRegion.LABELS.indexOf(label), .9f, .1f, top, .9f, bottom, order)

    private fun line(text: String, centerY: Float) = PositionedLine(text, .15f, centerY - .01f, .85f, centerY + .01f, 1f)
}
