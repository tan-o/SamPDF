package com.samreader.app.document

import com.samreader.app.data.SentenceSpanEntity
import com.samreader.app.data.SentenceSpanKind

/** Keeps formula markup atomic while preserving its exact position inside a sentence. */
internal object SentenceSpanParser {
    fun parse(sentenceId: String, text: String): List<SentenceSpanEntity> {
        val spans = mutableListOf<SentenceSpanEntity>()
        var cursor = 0
        var position = 0
        while (cursor < text.length) {
            val formulaStart = text.indexOf("\\[", cursor)
            if (formulaStart < 0) {
                add(spans, sentenceId, position, SentenceSpanKind.TEXT, text.substring(cursor))
                break
            }
            val formulaEnd = text.indexOf("\\]", formulaStart + 2)
            if (formulaEnd < 0) {
                add(spans, sentenceId, position, SentenceSpanKind.TEXT, text.substring(cursor))
                break
            }
            if (formulaStart > cursor) {
                add(spans, sentenceId, position++, SentenceSpanKind.TEXT, text.substring(cursor, formulaStart))
            }
            add(
                spans,
                sentenceId,
                position++,
                if (text.isDisplayFormula(formulaStart, formulaEnd + 2)) {
                    SentenceSpanKind.DISPLAY_FORMULA
                } else {
                    SentenceSpanKind.INLINE_FORMULA
                },
                text.substring(formulaStart, formulaEnd + 2),
            )
            cursor = formulaEnd + 2
        }
        return spans
    }

    private fun String.isDisplayFormula(start: Int, endExclusive: Int): Boolean {
        val beforeOnLine = substring(0, start).substringAfterLast('\n')
        val afterOnLine = substring(endExclusive).substringBefore('\n')
        return beforeOnLine.isBlank() && afterOnLine.isBlank()
    }

    private fun add(
        destination: MutableList<SentenceSpanEntity>,
        sentenceId: String,
        position: Int,
        kind: String,
        text: String,
    ) {
        if (text.isNotEmpty()) destination += SentenceSpanEntity(sentenceId, position, kind, text)
    }
}
