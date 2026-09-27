package com.samreader.app.ui

import com.samreader.app.data.EvidenceKind
import com.samreader.app.data.NormalizedRect
import com.samreader.app.data.PageEvidenceEntity

/** A word on a PDF page, in normalized page coordinates. */
data class PageWord(val text: String, val rect: NormalizedRect)

/**
 * Words of one page from the parsing evidence: the native PDF word boxes when the page has a
 * text layer, otherwise OCR lines split at spaces, each word spanning its own glyph boxes.
 */
internal fun pageWords(evidence: List<PageEvidenceEntity>): List<PageWord> {
    val native = evidence.filter { it.kind == EvidenceKind.WORD }
    if (native.isNotEmpty()) {
        return native.flatMap { word ->
            word.text.lookupWord()?.let { listOf(PageWord(it, NormalizedRect(word.left, word.top, word.right, word.bottom))) }.orEmpty()
        }
    }
    val glyphsByLine = evidence.filter { it.kind == EvidenceKind.OCR_GLYPH }.groupBy { it.parentId }
    return evidence.filter { it.kind == EvidenceKind.OCR_LINE }.flatMap { line ->
        val glyphs = glyphsByLine[line.id].orEmpty().sortedBy(PageEvidenceEntity::position)
        val tokens = line.text.split(WHITESPACE).filter(String::isNotEmpty)
        if (glyphs.size != tokens.sumOf { it.length }) return@flatMap emptyList()
        var cursor = 0
        tokens.mapNotNull { token ->
            val owned = glyphs.subList(cursor, cursor + token.length)
            cursor += token.length
            token.lookupWord()?.let { word ->
                PageWord(word, NormalizedRect(owned.minOf { it.left }, owned.minOf { it.top }, owned.maxOf { it.right }, owned.maxOf { it.bottom }))
            }
        }
    }
}

/** The word nearest to a tap, within [radiusPx] of its box. */
internal fun hitWord(
    words: List<PageWord>,
    x: Float,
    y: Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
    radiusPx: Float,
): PageWord? = words.map { word ->
    val dx = maxOf(word.rect.left - x, 0f, x - word.rect.right) * pageWidthPx
    val dy = maxOf(word.rect.top - y, 0f, y - word.rect.bottom) * pageHeightPx
    word to dx * dx + dy * dy
}.filter { it.second <= radiusPx * radiusPx }.minByOrNull { it.second }?.first

/** The word to look up: surrounding punctuation removed; null when nothing word-like remains. */
internal fun String.lookupWord(): String? =
    trim().trim { !it.isLetterOrDigit() }.takeIf { token -> token.any(Char::isLetter) }

private val WHITESPACE = Regex("\\s+")
