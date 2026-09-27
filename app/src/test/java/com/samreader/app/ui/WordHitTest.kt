package com.samreader.app.ui

import com.samreader.app.data.EvidenceChannel
import com.samreader.app.data.EvidenceKind
import com.samreader.app.data.PageEvidenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordHitTest {
    @Test
    fun nativeWordsLoseSurroundingPunctuation() {
        val words = pageWords(listOf(evidence("1", EvidenceKind.WORD, "(memory),", .1f, .2f), evidence("2", EvidenceKind.WORD, "—", .3f, .4f)))

        assertEquals(listOf("memory"), words.map(PageWord::text))
    }

    @Test
    fun ocrLinesAreSplitIntoWordsSpanningTheirOwnGlyphs() {
        val line = evidence("line", EvidenceKind.OCR_LINE, "fast model", .1f, .6f)
        val glyphs = "fastmodel".mapIndexed { index, character ->
            val left = if (index < 4) .1f + index * .04f else .4f + (index - 4) * .04f
            evidence("g$index", EvidenceKind.OCR_GLYPH, character.toString(), left, left + .04f, parent = "line", position = index)
        }

        val words = pageWords(listOf(line) + glyphs)

        assertEquals(listOf("fast", "model"), words.map(PageWord::text))
        assertEquals(.4f, words[1].rect.left, 1e-6f)
    }

    @Test
    fun tapPicksTheNearestWordWithinTheTouchRadius() {
        val words = listOf(PageWord("left", rect(.10f, .20f)), PageWord("right", rect(.30f, .40f)))

        assertEquals("right", hitWord(words, .29f, .5f, 1000f, 1000f, 20f)?.text)
        assertNull(hitWord(words, .70f, .5f, 1000f, 1000f, 20f))
    }

    private fun rect(left: Float, right: Float) = com.samreader.app.data.NormalizedRect(left, .48f, right, .52f)

    private fun evidence(
        id: String,
        kind: String,
        text: String,
        left: Float,
        right: Float,
        parent: String? = null,
        position: Int = 0,
    ) = PageEvidenceEntity(
        id = id, documentId = "d", pageNumber = 0, channel = EvidenceChannel.VISUAL_OCR, kind = kind, position = position,
        parentId = parent, blockType = null, modelId = null, text = text, left = left, top = .48f, right = right,
        bottom = .52f, confidence = 1f, imagePng = null,
    )
}
