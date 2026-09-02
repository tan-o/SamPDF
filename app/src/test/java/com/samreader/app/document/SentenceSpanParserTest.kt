package com.samreader.app.document

import com.samreader.app.data.SentenceSpanKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSpanParserTest {
    @Test
    fun preservesInlineFormulaBetweenTextSpans() {
        val spans = SentenceSpanParser.parse(
            sentenceId = "sentence",
            text = "That is, we let \\[\\frac{\\partial p}{\\partial t}=\\nabla\\phi\\], too.",
        )

        assertEquals(
            listOf(SentenceSpanKind.TEXT, SentenceSpanKind.INLINE_FORMULA, SentenceSpanKind.TEXT),
            spans.map { it.kind },
        )
        assertEquals("That is, we let ", spans[0].text)
        assertEquals("\\[\\frac{\\partial p}{\\partial t}=\\nabla\\phi\\]", spans[1].text)
        assertEquals(", too.", spans[2].text)
        assertEquals(listOf(0, 1, 2), spans.map { it.position })
    }

    @Test
    fun unmatchedFormulaDelimiterRemainsText() {
        val spans = SentenceSpanParser.parse("sentence", "value \\[x + y")

        assertEquals(1, spans.size)
        assertEquals(SentenceSpanKind.TEXT, spans.single().kind)
        assertEquals("value \\[x + y", spans.single().text)
    }

    @Test
    fun displayFormulaOnItsOwnLineGetsDisplaySpanType() {
        val spans = SentenceSpanParser.parse("sentence", "The relation\n\\[E=mc^2\\]\nis useful.")

        assertEquals(3, spans.size)
        assertEquals(SentenceSpanKind.DISPLAY_FORMULA, spans[1].kind)
        assertEquals("\\[E=mc^2\\]", spans[1].text)
    }
}
