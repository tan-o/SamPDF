package com.samreader.app.document

/**
 * Running heads, running feet and page numbers found from the PDF text layer before parsing:
 * text lines in the top or bottom page margin whose letters repeat on several pages, the classic
 * header/footer detection of PDF text extractors. The layout model usually labels these as
 * header/footer; this catches the ones it reads as ordinary text, so they never enter a sentence.
 */
internal class RunningFurniture private constructor(private val signatures: Set<String>) {
    fun isFurniture(line: PositionedLine): Boolean =
        line.inMarginBand() && (signature(line.text) in signatures || PAGE_NUMBER.matches(line.text.trim()))

    companion object {
        val NONE = RunningFurniture(emptySet())

        /** [pages] holds each page's native text lines, in page order. */
        fun detect(pages: List<List<PositionedLine>>): RunningFurniture {
            val pagesBySignature = mutableMapOf<String, MutableSet<Int>>()
            pages.forEachIndexed { page, lines ->
                lines.filter { it.inMarginBand() }.forEach { line ->
                    val signature = signature(line.text)
                    if (signature.length >= MIN_SIGNATURE_LETTERS) pagesBySignature.getOrPut(signature) { mutableSetOf() } += page
                }
            }
            val minimumPages = if (pages.size <= 4) 2 else 3
            return RunningFurniture(pagesBySignature.filterValues { it.size >= minimumPages }.keys)
        }

        /** Letters only: page numbers, dates and volume numbers change from page to page. */
        private fun signature(text: String) = text.filter(Char::isLetter).lowercase()

        private fun PositionedLine.inMarginBand() = bottom <= MARGIN || top >= 1f - MARGIN

        private const val MARGIN = .1f
        private const val MIN_SIGNATURE_LETTERS = 3
        private val PAGE_NUMBER = Regex("""[\p{P}\s]*\d{1,4}[\p{P}\s]*""")
    }
}
