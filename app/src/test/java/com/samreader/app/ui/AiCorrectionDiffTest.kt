package com.samreader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCorrectionDiffTest {
    @Test
    fun marksReplacedWordsInParsedAndProposedText() {
        val source = "The transforrner uses attention."
        val proposed = "The transformer uses attention."

        val diff = changedTextRanges(source, proposed)

        assertEquals("transforrner", source.slice(diff.sourceChangedRanges.single()))
        assertEquals("transformer", proposed.slice(diff.proposedChangedRanges.single()))
    }

    @Test
    fun marksInsertedTextOnlyOnProposalSide() {
        val source = "A model works."
        val proposed = "A robust model works."

        val diff = changedTextRanges(source, proposed)

        assertTrue(diff.sourceChangedRanges.isEmpty())
        assertEquals("robust ", proposed.slice(diff.proposedChangedRanges.single()))
    }
}
