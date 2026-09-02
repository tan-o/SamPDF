package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import org.junit.Assert.assertEquals
import org.junit.Test

class PageSemanticRefinerTest {
    @Test
    fun keepsModelCaptionSeparateWithoutTypographyReclassification() {
        val caption = block(LayoutBlockType.CAPTION, "Figure 3. Results.", 30)
        val body = block(LayoutBlockType.PARAGRAPH, "Normal body text.", 20)

        val refined = PageSemanticRefiner.refine(listOf(caption, body))

        assertEquals(listOf(body, caption), refined)
    }

    @Test
    fun firstPageBlocksBetweenTitleAndAbstractBecomeAuthorMatterByReadingOrder() {
        val abstract = block(LayoutBlockType.ABSTRACT, "This study presents a result.", 40)
        val authors = block(LayoutBlockType.PARAGRAPH, "A. Researcher and B. Scholar", 20)
        val affiliation = block(LayoutBlockType.PARAGRAPH, "Institute of Physics", 30)
        val title = block(LayoutBlockType.DOCUMENT_TITLE, "A Paper Title", 10)
        val journalMasthead = block(LayoutBlockType.PARAGRAPH, "PHOTONICS RESEARCH", 5)

        val refined = PageSemanticRefiner.refine(
            listOf(abstract, authors, affiliation, title, journalMasthead),
            pageNumber = 0,
        )

        assertEquals(
            listOf(
                LayoutBlockType.HEADER,
                LayoutBlockType.DOCUMENT_TITLE,
                LayoutBlockType.AUTHOR,
                LayoutBlockType.AUTHOR,
                LayoutBlockType.ABSTRACT,
            ),
            refined.map(PositionedBlock::type),
        )
    }

    @Test
    fun contentsHeadingPromotesOnlyTheEntryBlockInItsColumn() {
        val heading = block(LayoutBlockType.SECTION_TITLE, "CONTENTS", 10)
            .copy(left = .08f, right = .46f, top = .10f, bottom = .14f)
        val entries = block(LayoutBlockType.PARAGRAPH, "I. Introduction ........ 1", 20)
            .copy(left = .08f, right = .48f, top = .15f, bottom = .40f)
        val rightColumnBody = block(LayoutBlockType.PARAGRAPH, "Normal body text.", 30)
            .copy(left = .54f, right = .92f, top = .15f, bottom = .40f)
        val nextHeading = block(LayoutBlockType.SECTION_TITLE, "1. INTRODUCTION", 40)
            .copy(left = .08f, right = .48f, top = .42f, bottom = .46f)
        val laterBody = block(LayoutBlockType.PARAGRAPH, "Later body text.", 50)
            .copy(left = .08f, right = .48f, top = .47f, bottom = .60f)

        val refined = PageSemanticRefiner.refine(
            listOf(heading, entries, rightColumnBody, nextHeading, laterBody),
            pageNumber = 2,
        )

        assertEquals(LayoutBlockType.CONTENTS, refined[1].type)
        assertEquals(LayoutBlockType.PARAGRAPH, refined[2].type)
        assertEquals(LayoutBlockType.PARAGRAPH, refined[4].type)
    }

    private fun block(type: String, text: String, order: Int): PositionedBlock {
        val line = PositionedLine(text, .1f, order / 100f, .9f, order / 100f + .03f, 1f)
        return PositionedBlock(
            lines = listOf(line), left = line.left, top = line.top, right = line.right,
            bottom = line.bottom, type = type, readingOrder = order,
        )
    }
}
