package com.samreader.app.ui

import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.TextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceHitTest {
    @Test
    fun exactGlyphHitSelectsSentence() {
        val sentence = sentence("0,0.10,0.20,0.15,0.24|0,0.16,0.20,0.20,0.24")

        assertEquals(sentence, hitSentence(listOf(sentence), 0, .12f, .22f, 1000f, 1400f, 24f))
    }

    @Test
    fun smallGapBetweenGlyphsStillSelectsSentence() {
        val sentence = sentence("0,0.10,0.20,0.12,0.24|0,0.15,0.20,0.18,0.24")

        assertEquals(sentence, hitSentence(listOf(sentence), 0, .135f, .22f, 1000f, 1400f, 18f))
    }

    @Test
    fun distantBlankAreaDoesNotSelectAnything() {
        val sentence = sentence("0,0.10,0.20,0.18,0.24")

        assertNull(hitSentence(listOf(sentence), 0, .50f, .50f, 1000f, 1400f, 24f))
    }

    @Test
    fun crossPageSentenceCanBeHitOnItsSecondPage() {
        val sentence = sentence("0,0.10,0.80,0.30,0.84|1,0.10,0.10,0.25,0.14")

        assertEquals(sentence, hitSentence(listOf(sentence), 1, .15f, .12f))
        assertNull(hitSentence(listOf(sentence), 0, .15f, .12f))
    }

    private fun sentence(regions: String) = SentenceEntity(
        id = "sentence", documentId = "document", pageNumber = 0, position = 0,
        originalText = "Example sentence.", regions = regions,
        source = TextSource.HYBRID_PDF_VISUAL, confidence = .9f,
    )
}
