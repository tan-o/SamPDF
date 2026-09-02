package com.samreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FullTranslationResponseTest {
    @Test
    fun parsesItemsInRequestedOrder() {
        val raw = """
            ```json
            {"items":[
              {"id":"b","corrected_source":"second source","zh_translation":"第二句"},
              {"id":"a","corrected_source":"first source","zh_translation":"第一句"}
            ]}
            ```
        """.trimIndent()

        val result = parseFullTranslationResponse(raw, listOf("a", "b"))

        assertEquals(listOf("a", "b"), result.map(FullTranslationItem::sentenceId))
        assertEquals("第一句", result.first().translatedText)
    }

    @Test
    fun rejectsMissingOrUnexpectedIds() {
        val raw = """{"items":[{"id":"a","corrected_source":"source","zh_translation":"译文"}]}"""

        assertThrows(IllegalArgumentException::class.java) {
            parseFullTranslationResponse(raw, listOf("a", "b"))
        }
    }
}
