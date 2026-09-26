package com.samreader.app.document

import org.json.JSONObject

internal data class RustPdfTextPage(
    val width: Float,
    val height: Float,
    val lines: List<RustPdfTextCell>,
    val words: List<RustPdfTextCell>,
)

internal data class RustPdfTextCell(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal object RustPdfTextExtractor {
    fun extract(path: String): List<RustPdfTextPage> = parse(NativeLayoutDetector.extractPdfText(path))

    internal fun parse(json: String): List<RustPdfTextPage> {
        val root = JSONObject(json)
        (root.opt("error") as? String)?.takeIf(String::isNotBlank)?.let(::error)
        val pages = root.getJSONArray("pages")
        return List(pages.length()) { pageIndex ->
            val page = pages.getJSONObject(pageIndex)
            RustPdfTextPage(
                width = page.getDouble("width").toFloat(),
                height = page.getDouble("height").toFloat(),
                lines = page.cells("lines"),
                words = page.cells("words"),
            )
        }
    }

    private fun JSONObject.cells(key: String): List<RustPdfTextCell> {
        val values = getJSONArray(key)
        return List(values.length()) { index ->
            val value = values.getJSONObject(index)
            RustPdfTextCell(
                text = value.getString("text"),
                left = value.getDouble("left").toFloat(),
                top = value.getDouble("top").toFloat(),
                right = value.getDouble("right").toFloat(),
                bottom = value.getDouble("bottom").toFloat(),
            )
        }
    }
}

/** Native text cells as normalized lines, with one glyph per non-whitespace character when the word cells align. */
internal fun RustPdfTextPage.toPositionedLines(): List<PositionedLine> {
    if (width <= 0f || height <= 0f) return emptyList()
    return lines.mapNotNull { cell ->
        val text = cell.text.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val wordGlyphs = words.asSequence()
            .filter { word ->
                val centerY = (word.top + word.bottom) / 2f
                centerY in (cell.top - 1f)..(cell.bottom + 1f) &&
                    minOf(word.right, cell.right) > maxOf(word.left, cell.left)
            }
            .sortedBy(RustPdfTextCell::left)
            .flatMap { word ->
                val characters = word.text.filterNot(Char::isWhitespace).toList()
                val step = (word.right - word.left) / characters.size.coerceAtLeast(1)
                characters.mapIndexed { index, character ->
                    PositionedGlyph(
                        text = character.toString(),
                        left = (word.left + step * index) / width,
                        top = word.top / height,
                        right = (word.left + step * (index + 1)) / width,
                        bottom = word.bottom / height,
                        confidence = 1f,
                    )
                }
            }
            .toList()
        val glyphs = wordGlyphs.takeIf {
            it.joinToString("", transform = PositionedGlyph::text) == text.filterNot(Char::isWhitespace)
        }.orEmpty()
        PositionedLine(
            text = text,
            left = cell.left / width,
            top = cell.top / height,
            right = cell.right / width,
            bottom = cell.bottom / height,
            confidence = 1f,
            glyphs = glyphs,
        )
    }
}
