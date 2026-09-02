package com.samreader.app.document

import com.samreader.app.data.SentenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullDocumentTranslationWorkerTest {
    @Test
    fun batchesByCharacterLimitWithoutDroppingAnOversizedSentence() {
        val sentences = listOf(sentence("a", "1234"), sentence("b", "5678"), sentence("c", "x"))

        assertEquals(1, fullTranslationBatchEnd(sentences, start = 0, maxSentences = 10, maxCharacters = 6))
        assertEquals(2, fullTranslationBatchEnd(sentences, start = 1, maxSentences = 10, maxCharacters = 2))
    }

    @Test
    fun rejectsCorrectionsThatDeleteFormulaOrRewriteMostOfTheSentence() {
        assertNull(acceptedAiCorrection("The value is \\[x^2\\].", "The value is x squared.", 1f))
        assertNull(acceptedAiCorrection("A sufficiently long academic sentence.", "短句", .25f))
        assertNull(acceptedAiCorrection("abcdefghij", "jihgfedcba", .25f))
    }

    @Test
    fun acceptsSmallContextualOcrCorrection() {
        assertEquals(
            "The transformer uses attention.",
            acceptedAiCorrection("The transforrner uses attention.", "The transformer uses attention.", .25f),
        )
    }

    private fun sentence(id: String, text: String) = SentenceEntity(
        id = id,
        documentId = "doc",
        pageNumber = 0,
        position = 0,
        originalText = text,
        regions = "",
        source = "test",
        confidence = 1f,
    )
}
