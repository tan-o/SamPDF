package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceStreamTest {
    /** Stands in for WtP: a boundary after `.?!` that is followed by an upper-case word or the end. */
    private class PunctuationScorer : SentenceBoundaryScorer {
        val seen = mutableListOf<String>()
        override fun probabilities(text: String): FloatArray {
            seen += text
            return FloatArray(text.length) { index ->
                val next = text.drop(index + 1).trimStart().firstOrNull()
                if (text[index] in ".?!" && (next == null || next.isUpperCase())) 1f else 0f
            }
        }
    }

    @Test
    fun dehyphenatesLineBreaksAndReportsOneRegionPerLine() {
        val stream = SentenceStream(PunctuationScorer())

        val sentences = stream.addPage(0, listOf(block(0, line("The proposed mem-", .10f), line("ory system works. It is fast.", .14f)))) +
            stream.finish()

        assertEquals(listOf("The proposed memory system works.", "It is fast."), sentences.map { it.text })
        assertEquals(2, sentences.first().regions.size)
        assertTrue(sentences.first().regions[0].rect.top < sentences.first().regions[1].rect.top)
        assertEquals(1, sentences.last().regions.size)
    }

    @Test
    fun bodySentenceFlowsAroundAFigureAndItsCaptionIntoTheNextColumn() {
        val stream = SentenceStream(PunctuationScorer())
        val blocks = listOf(
            block(0, line("Earlier sentence. A sentence starts in the left", .80f, .08f, .44f)),
            block(1, type = LayoutBlockType.IMAGE, selectable = false),
            block(2, line("Figure 1. Model overview.", .40f, .56f, .92f), type = LayoutBlockType.CAPTION),
            block(3, line("column and finishes here.", .50f, .56f, .92f)),
        )

        val sentences = stream.addPage(0, blocks) + stream.finish()

        assertTrue(sentences.any { it.text == "A sentence starts in the left column and finishes here." })
        assertTrue(sentences.none { "left Figure" in it.text })
        assertTrue(sentences.filter { it.semanticRole == SemanticTextRole.CAPTION }.all { it.text.startsWith("Figure") || it.text == "Model overview." })
    }

    @Test
    fun bodySentenceContinuesOnTheNextPageAfterLeadingFloats() {
        val stream = SentenceStream(PunctuationScorer())

        val first = stream.addPage(0, listOf(
            block(0, line("Done here. The body continues on the", .85f)),
            block(1, line("1 A footnote.", .95f), type = LayoutBlockType.FOOTNOTE),
        ))
        val second = stream.addPage(1, listOf(
            block(0, line("FIG. 2. Caption.", .10f), type = LayoutBlockType.CAPTION),
            block(1, line("next page. New one.", .30f)),
        )) + stream.finish()

        assertEquals(listOf("Done here.", "1 A footnote."), first.map { it.text })
        val crossing = second.single { it.text == "The body continues on the next page." }
        assertEquals(setOf(0, 1), crossing.regions.map(PageSentenceRegion::pageNumber).toSet())
        assertEquals(0, crossing.firstPage)
    }

    @Test
    fun onlyTheLastSentenceOfEachStreamStaysOpenBetweenPages() {
        val stream = SentenceStream(PunctuationScorer())

        val committed = stream.addPage(0, listOf(block(0, line("First one. Second one. Third", .10f))))

        assertEquals(listOf("First one.", "Second one."), committed.map { it.text })
        assertEquals(0, stream.firstOpenPage)
        assertEquals(listOf("Third"), stream.finish().map { it.text })
        assertEquals(null, stream.firstOpenPage)
    }

    @Test
    fun sectionTitleEndsTheOpenStreamAndIsKeptWhole() {
        val stream = SentenceStream(PunctuationScorer())

        val sentences = stream.addPage(0, listOf(
            block(0, line("A paragraph without an ending", .10f)),
            block(1, line("III. METHODS", .20f), type = LayoutBlockType.SECTION_TITLE),
            block(2, line("New section text.", .30f)),
        )) + stream.finish()

        assertEquals(
            listOf("A paragraph without an ending", "III. METHODS", "New section text."),
            sentences.map { it.text },
        )
        assertEquals(SemanticTextRole.TITLE, sentences[1].semanticRole)
    }

    @Test
    fun abstractAndBodyAreIndependentStreams() {
        val stream = SentenceStream(PunctuationScorer())

        val sentences = stream.addPage(0, listOf(
            block(0, line("The abstract continues", .10f), type = LayoutBlockType.ABSTRACT),
            block(1, line("Body text starts.", .50f)),
            block(2, line("and ends.", .20f), type = LayoutBlockType.ABSTRACT),
        )) + stream.finish()

        assertEquals("The abstract continues and ends.", sentences.single { it.semanticRole == SemanticTextRole.ABSTRACT }.text)
        assertEquals("Body text starts.", sentences.single { it.semanticRole == SemanticTextRole.BODY }.text)
    }

    @Test
    fun displayFormulaIsOneAtomThatTheModelReadsAsAWord() {
        val scorer = PunctuationScorer()
        val stream = SentenceStream(scorer)

        val sentences = stream.addPage(0, listOf(
            block(0, line("This relation", .20f)),
            PositionedBlock(
                listOf(line("\\[E=mc^2\\]", .27f).copy(isFormula = true)), .1f, .27f, .9f, .32f,
                type = LayoutBlockType.EQUATION, readingOrder = 1, layoutLabel = "display_formula",
            ),
            block(2, line("is well known.", .34f)),
        )) + stream.finish()

        assertEquals(listOf("This relation\n\\[E=mc^2\\]\nis well known."), sentences.map { it.text })
        assertTrue("This relation equation is well known." in scorer.seen)
    }

    @Test
    fun boundaryPredictedInsideAFormulaFallsAfterTheWholeFormula() {
        val stream = SentenceStream(object : SentenceBoundaryScorer {
            override fun probabilities(text: String) = FloatArray(text.length) { if (text[it] == 'v') 1f else 0f }
        })

        val sentences = stream.addPage(0, listOf(block(
            0,
            line("so we get", .10f),
            line("\\[x_{n+1}.\\]", .10f).copy(isFormula = true),
            line("Then", .14f),
        ))) + stream.finish()

        assertEquals(listOf("so we get \\[x_{n+1}.\\]", "Then"), sentences.map { it.text })
    }

    @Test
    fun fragmentWithoutLettersStaysWithTheFollowingSentence() {
        val stream = SentenceStream(object : SentenceBoundaryScorer {
            override fun probabilities(text: String) = FloatArray(text.length) { if (text[it] in ".•") 1f else 0f }
        })

        val sentences = stream.addPage(0, listOf(block(0, line("End of item.", .10f), line("• Next item here.", .14f)))) +
            stream.finish()

        assertEquals(listOf("End of item.", "• Next item here."), sentences.map { it.text })
    }

    @Test
    fun contentsEntriesAreKeptVerbatimWithoutTheModel() {
        val scorer = PunctuationScorer()
        val stream = SentenceStream(scorer)
        val entries = listOf("I. Absolute Motion ........ 475", "II. Sagnac Experiments ..... 476")

        val sentences = stream.addPage(0, listOf(
            block(0, *entries.mapIndexed { index, text -> line(text, .1f + index * .05f) }.toTypedArray(), type = LayoutBlockType.CONTENTS),
        )) + stream.finish()

        assertEquals(entries, sentences.map { it.text })
        assertTrue(scorer.seen.isEmpty())
    }

    @Test
    fun positionsFollowReadingOrderAndAreUniquePerPage() {
        val stream = SentenceStream(PunctuationScorer())

        val sentences = stream.addPage(0, listOf(
            block(0, line("One here. Two here.", .10f)),
            block(1, line("Figure 1. Caption.", .50f), type = LayoutBlockType.CAPTION),
            block(2, line("Three here.", .60f)),
        )) + stream.finish()

        assertEquals(sentences.size, sentences.map { it.position }.toSet().size)
        assertEquals(
            listOf("One here.", "Two here.", "Figure 1.", "Caption.", "Three here."),
            sentences.sortedBy(DocumentSentence::position).map { it.text },
        )
    }

    @Test
    fun resumeMovesBackUntilNoCommittedSentenceStraddlesTheRestartPage() {
        val sentences = listOf(0 to setOf(0), 1 to setOf(1, 2), 2 to setOf(2, 3), 3 to setOf(3))

        assertEquals(1, SentenceStream.resumePage(3, sentences))
        assertEquals(0, SentenceStream.resumePage(0, emptyList()))
        assertEquals(4, SentenceStream.resumePage(4, sentences))
    }

    private fun block(
        order: Int,
        vararg lines: PositionedLine,
        type: String = LayoutBlockType.PARAGRAPH,
        selectable: Boolean = true,
    ) = PositionedBlock(
        lines = lines.toList(),
        left = lines.minOfOrNull { it.left } ?: .1f,
        top = lines.minOfOrNull { it.top } ?: .1f,
        right = lines.maxOfOrNull { it.right } ?: .9f,
        bottom = lines.maxOfOrNull { it.bottom } ?: .2f,
        selectableBody = selectable,
        type = type,
        readingOrder = order,
    )

    private fun line(text: String, top: Float, left: Float = .1f, right: Float = .9f): PositionedLine {
        val characters = text.filterNot(Char::isWhitespace)
        val step = (right - left) / characters.length
        return PositionedLine(
            text, left, top, right, top + .03f, .9f,
            glyphs = characters.mapIndexed { index, character ->
                PositionedGlyph(character.toString(), left + step * index, top, left + step * (index + 1), top + .03f, .9f)
            },
        )
    }
}
