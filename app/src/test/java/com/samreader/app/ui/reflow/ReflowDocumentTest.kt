package com.samreader.app.ui.reflow

import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.PageEntity
import com.samreader.app.data.PageLayoutBlockEntity
import com.samreader.app.data.SentenceEntity
import com.samreader.app.document.SemanticTextRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowDocumentTest {
    @Test
    fun sentencesOfOneBlockFormOneParagraphAndANewBlockStartsTheNext() {
        val first = block(0, .10f, .20f)
        val second = block(1, .25f, .35f)
        val document = build(
            listOf(sentence("a", 0, 0, line(0, .12f)), sentence("b", 0, 10, line(0, .16f)), sentence("c", 0, 20, line(0, .27f))),
            listOf(first, second),
        )

        assertEquals(listOf(listOf("a", "b"), listOf("c")), paragraphs(document))
    }

    @Test
    fun paragraphContinuesIntoTheBlockItsLastSentenceRanInto() {
        val left = block(0, .70f, .90f, left = .05f, right = .48f)
        val right = block(1, .05f, .30f, left = .52f, right = .95f)
        val crossing = sentence("a", 0, 0, line(0, .85f, .05f, .48f), line(0, .07f, .52f, .95f))
        val next = sentence("b", 0, 10, line(0, .12f, .52f, .95f))

        val document = build(listOf(crossing, next), listOf(left, right))

        assertEquals(listOf(listOf("a", "b")), paragraphs(document))
    }

    @Test
    fun figureAndCaptionWaitForTheParagraphBreak() {
        val paragraph = block(0, .10f, .30f)
        val image = block(1, .32f, .60f, type = LayoutBlockType.IMAGE)
        val caption = block(2, .61f, .65f, type = LayoutBlockType.CAPTION)
        val continuation = block(3, .66f, .80f)
        val document = build(
            listOf(
                sentence("a", 0, 0, line(0, .12f), line(0, .68f)),
                sentence("cap", 0, 5, line(0, .62f), role = SemanticTextRole.CAPTION),
                sentence("b", 0, 20, line(0, .72f)),
            ),
            listOf(paragraph, image, caption, continuation),
        )

        assertTrue(document.nodes[0] is ReflowNode.Paragraph)
        assertEquals(listOf("a", "b"), (document.nodes[0] as ReflowNode.Paragraph).sentences.map { it.id })
        val figure = document.nodes[1] as ReflowNode.Figure
        assertTrue(figure.parts[0] is FigurePart.Visual)
        assertEquals("cap", (figure.parts[1] as FigurePart.Caption).sentence.id)
    }

    @Test
    fun twoFiguresWithCaptionsStaySeparate() {
        val document = build(
            listOf(
                sentence("cap1", 0, 5, line(0, .32f), role = SemanticTextRole.CAPTION),
                sentence("cap2", 0, 50, line(0, .72f), role = SemanticTextRole.CAPTION),
            ),
            listOf(
                block(0, .10f, .30f, type = LayoutBlockType.IMAGE),
                block(1, .31f, .34f, type = LayoutBlockType.CAPTION),
                block(2, .40f, .70f, type = LayoutBlockType.CHART),
                block(3, .71f, .74f, type = LayoutBlockType.CAPTION),
            ),
        )

        assertEquals(2, document.nodes.count { it is ReflowNode.Figure })
    }

    @Test
    fun titlesBecomeHeadingsAndFootersAreDropped() {
        val document = build(
            listOf(
                sentence("title", 0, 0, line(0, .05f), role = SemanticTextRole.TITLE),
                sentence("intro", 0, 5, line(0, .12f), role = SemanticTextRole.TITLE),
                sentence("body", 0, 10, line(0, .20f)),
                sentence("footer", 0, 90, line(0, .95f), role = SemanticTextRole.FOOTER),
            ),
            listOf(
                block(0, .03f, .08f, type = LayoutBlockType.DOCUMENT_TITLE),
                block(1, .10f, .14f, type = LayoutBlockType.SECTION_TITLE),
                block(2, .18f, .30f),
                block(3, .94f, .97f, type = LayoutBlockType.FOOTER),
            ),
        )

        assertEquals(true, (document.nodes[0] as ReflowNode.Heading).documentTitle)
        assertEquals(listOf("intro"), document.headings.map { it.id })
        assertTrue(document.nodes.none { node -> node is ReflowNode.Paragraph && node.sentences.any { it.id == "footer" } })
    }

    @Test
    fun displayEquationCropIncludesItsNumberAndIsSizedInBodyEms() {
        val equation = block(1, .40f, .45f, left = .30f, right = .60f, type = LayoutBlockType.EQUATION, text = "\\[E=mc^2\\]")
        val number = block(2, .41f, .44f, left = .85f, right = .90f, type = LayoutBlockType.EQUATION, text = "")
        val document = build(
            listOf(sentence("a", 0, 0, line(0, .30f, height = 10f / 792f))),
            listOf(block(0, .28f, .38f), equation, number),
        )

        val crop = document.displayEquations.getValue("\\[E=mc^2\\]")
        assertTrue(crop.rect.right >= .90f)
        // (0.904 - 0.296) * 612 pt at a 10 / 1.15 pt body font.
        assertEquals((.904f - .296f) * 612f / (10f / 1.15f), crop.widthEm, .2f)
    }

    @Test
    fun pageFurnitureAndFootnotesStayOutOfTheReadingFlow() {
        val document = build(
            listOf(
                sentence("journal", 0, 0, line(0, .02f), role = SemanticTextRole.HEADER),
                sentence("body", 0, 10, line(0, .20f)),
                sentence("note", 0, 20, line(0, .85f), role = SemanticTextRole.FOOTNOTE),
                sentence("stamp", 0, 30, line(0, .50f, .01f, .03f), role = SemanticTextRole.SIDEBAR),
                sentence("more", 0, 40, line(0, .25f)),
            ),
            listOf(
                block(0, .01f, .05f, type = LayoutBlockType.HEADER),
                block(1, .18f, .30f),
                block(2, .84f, .88f, type = LayoutBlockType.FOOTNOTE),
                block(3, .40f, .60f, left = .0f, right = .04f, type = LayoutBlockType.SIDEBAR),
            ),
        )

        assertEquals(listOf(listOf("body", "more")), paragraphs(document))
        assertEquals(listOf("note"), document.footnotes.map { it.id })
    }

    @Test
    fun referencesAreNotSplitByFloatsAndBiographiesBecomeBackMatter() {
        val document = build(
            listOf(
                sentence("ref1", 0, 0, line(0, .10f), role = SemanticTextRole.REFERENCE),
                sentence("cap", 0, 5, line(0, .32f), role = SemanticTextRole.CAPTION),
                sentence("ref2", 0, 10, line(0, .40f), role = SemanticTextRole.REFERENCE),
                sentence("bio", 0, 20, line(0, .80f)),
            ),
            listOf(
                block(0, .08f, .14f, type = LayoutBlockType.REFERENCE),
                block(1, .16f, .30f, type = LayoutBlockType.TABLE),
                block(2, .31f, .34f, type = LayoutBlockType.CAPTION),
                block(3, .38f, .44f, type = LayoutBlockType.REFERENCE),
                block(4, .60f, .75f, type = LayoutBlockType.IMAGE),
                block(5, .78f, .90f),
            ),
        )

        assertEquals(listOf(listOf("ref1"), listOf("ref2")), paragraphs(document))
        assertTrue(document.nodes.last() is ReflowNode.Figure)
        assertEquals(listOf("bio"), document.backMatter.filterIsInstance<ReflowNode.Paragraph>().flatMap { p -> p.sentences.map { it.id } })
        assertTrue(document.backMatter.first() is ReflowNode.Figure)
    }

    @Test
    fun anAppendixHeadingAfterTheReferencesReturnsToTheReadingFlow() {
        val document = build(
            listOf(
                sentence("ref", 0, 0, line(0, .10f), role = SemanticTextRole.REFERENCE),
                sentence("appendix", 0, 10, line(0, .30f), role = SemanticTextRole.TITLE),
                sentence("proof", 0, 20, line(0, .40f)),
            ),
            listOf(
                block(0, .08f, .14f, type = LayoutBlockType.REFERENCE),
                block(1, .28f, .33f, type = LayoutBlockType.SECTION_TITLE),
                block(2, .38f, .50f),
            ),
        )

        assertEquals(listOf(listOf("ref"), listOf("proof")), paragraphs(document))
        assertTrue(document.backMatter.isEmpty())
    }

    private fun build(sentences: List<SentenceEntity>, blocks: List<PageLayoutBlockEntity>) =
        ReflowDocument.build(sentences, blocks, listOf(PageEntity("d", 0, 612f, 792f, "", 1f)))

    private fun paragraphs(document: ReflowDocument) =
        document.nodes.filterIsInstance<ReflowNode.Paragraph>().map { paragraph -> paragraph.sentences.map { it.id } }

    private fun sentence(id: String, page: Int, position: Int, vararg lines: String, role: String = SemanticTextRole.BODY) =
        SentenceEntity(
            id = id, documentId = "d", pageNumber = page, position = position, originalText = "Sentence $id.",
            regions = lines.joinToString("|"), source = "HYBRID_PDF_VISUAL:$role", confidence = 1f,
        )

    private fun line(page: Int, top: Float, left: Float = .1f, right: Float = .9f, height: Float = .02f) =
        "$page,$left,$top,$right,${top + height}"

    private fun block(
        position: Int,
        top: Float,
        bottom: Float,
        left: Float = .05f,
        right: Float = .95f,
        type: String = LayoutBlockType.PARAGRAPH,
        text: String = "text",
    ) = PageLayoutBlockEntity("d", 0, position, type, left, top, right, bottom, text)
}
