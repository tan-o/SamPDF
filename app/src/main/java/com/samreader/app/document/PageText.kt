package com.samreader.app.document

data class PositionedGlyph(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

/**
 * One source line (PDF text cell, OCR line, or recognized formula) in normalized page
 * coordinates. When [glyphs] is non-empty it holds exactly one glyph per non-whitespace
 * character of [text], in order. [isFormula] marks a line whose text is `\[LaTeX\]`.
 */
data class PositionedLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val glyphs: List<PositionedGlyph> = emptyList(),
    val isFormula: Boolean = false,
) {
    /** Glyphs are only usable when they align one-to-one with the non-whitespace characters. */
    val hasAlignedGlyphs: Boolean get() = glyphs.isNotEmpty() && glyphs.size == text.count { !it.isWhitespace() }

    /** The part of this line covered by glyphs `[from, until)`, keeping the source spacing. */
    fun sliceGlyphs(from: Int, until: Int): PositionedLine? {
        if (!hasAlignedGlyphs || from >= until) return null
        val characterIndices = text.indices.filterNot { text[it].isWhitespace() }
        val start = characterIndices[from]
        val end = characterIndices[until - 1] + 1
        val sliceGlyphs = glyphs.subList(from, until)
        return copy(
            text = text.substring(start, end),
            left = sliceGlyphs.minOf(PositionedGlyph::left),
            top = sliceGlyphs.minOf(PositionedGlyph::top),
            right = sliceGlyphs.maxOf(PositionedGlyph::right),
            bottom = sliceGlyphs.maxOf(PositionedGlyph::bottom),
            confidence = sliceGlyphs.map(PositionedGlyph::confidence).average().toFloat(),
            glyphs = sliceGlyphs.toList(),
        )
    }
}

data class PositionedBlock(
    val lines: List<PositionedLine>,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isCaption: Boolean = false,
    val selectableBody: Boolean = true,
    val type: String = "PARAGRAPH",
    val readingOrder: Int = Int.MAX_VALUE,
    val layoutLabel: String = "",
)

internal val PositionedGlyph.centerX: Float get() = (left + right) / 2f
internal val PositionedGlyph.centerY: Float get() = (top + bottom) / 2f
