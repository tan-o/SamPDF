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
