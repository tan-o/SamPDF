package com.samreader.app.ui.reflow

import com.samreader.app.data.NormalizedRect
import com.samreader.app.data.SentenceEntity
import com.samreader.app.document.SemanticTextRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowHtmlTest {
    @Test
    fun everySentenceHasItsTranslationSlotAndTextIsEscaped() {
        val html = render(listOf(ReflowNode.Paragraph(SemanticTextRole.BODY, listOf(sentence("a", "x < y & \"z\".")))), mapOf("a" to "译文"))

        assertTrue("<span class=\"s\" id=\"s-a\"><span class=\"o\">x &lt; y &amp; &quot;z&quot;.</span><span class=\"t\">译文</span></span>" in html)
    }

    @Test
    fun inlineMathIsLeftForKatexAndDisplayMathUsesThePdfCrop() {
        val crop = PageCrop(2, NormalizedRect(.1f, .2f, .9f, .3f), widthEm = 20f, aspectRatio = 8f)
        val document = ReflowDocument(
            listOf(ReflowNode.Paragraph(SemanticTextRole.BODY, listOf(sentence("a", "Let \\[x_i\\] be\n\\[E=mc^2\\]\nhere.")))),
            mapOf("\\[E=mc^2\\]" to crop),
        )

        val html = ReflowHtml.render(document, emptyMap()) { "crop:${it.encode()}" }

        assertTrue("<span class=\"m\" data-tex=\"x_i\">" in html)
        assertTrue("src=\"crop:2_0.1000_0.2000_0.9000_0.3000\"" in html)
        assertTrue("width:20.000em;aspect-ratio:8.000" in html)
    }

    @Test
    fun abstractAndReferencesAreGroupedIntoSections() {
        val html = render(
            listOf(
                ReflowNode.Paragraph(SemanticTextRole.ABSTRACT, listOf(sentence("a", "Abstract text."))),
                ReflowNode.Paragraph(SemanticTextRole.BODY, listOf(sentence("b", "Body."))),
                ReflowNode.Paragraph(SemanticTextRole.REFERENCE, listOf(sentence("c", "[1] Ref one."))),
                ReflowNode.Paragraph(SemanticTextRole.REFERENCE, listOf(sentence("d", "[2] Ref two."))),
            ),
        )

        assertEquals(1, Regex("<section class=\"references\">").findAll(html).count())
        assertTrue(html.indexOf("<section class=\"abstract\">") < html.indexOf("s-a"))
        assertTrue(html.indexOf("</section>") < html.indexOf("s-b"))
    }

    @Test
    fun cropEncodingRoundTrips() {
        val crop = PageCrop(3, NormalizedRect(.125f, .25f, .5f, .75f))

        assertEquals(crop.rect, PageCrop.decode(crop.encode())!!.rect)
        assertEquals(3, PageCrop.decode(crop.encode())!!.page)
    }

    private fun render(nodes: List<ReflowNode>, translations: Map<String, String> = emptyMap()) =
        ReflowHtml.render(ReflowDocument(nodes, emptyMap()), translations) { "crop" }

    private fun sentence(id: String, text: String) = SentenceEntity(
        id = id, documentId = "d", pageNumber = 0, position = 0, originalText = text,
        regions = "0,0.1,0.1,0.9,0.12", source = "HYBRID_PDF_VISUAL:BODY", confidence = 1f,
    )
}
