package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.NormalizedRect

interface SentenceBoundaryScorer {
    val threshold: Float get() = .5f

    /** Returns the probability that a sentence ends after each UTF-16 character in [text]. */
    fun probabilities(text: String): FloatArray
}

object SemanticTextRole {
    const val BODY = "BODY"
    const val ABSTRACT = "ABSTRACT"
    const val REFERENCE = "REFERENCE"
    const val CAPTION = "CAPTION"
    const val TITLE = "TITLE"
    const val AUTHOR = "AUTHOR"
    const val CONTENTS = "CONTENTS"
    const val FOOTNOTE = "FOOTNOTE"
    const val SIDEBAR = "SIDEBAR"
    const val HEADER = "HEADER"
    const val FOOTER = "FOOTER"
    const val OTHER = "OTHER"
}

data class PageSentenceRegion(val pageNumber: Int, val rect: NormalizedRect)

data class DocumentSentence(
    val firstPage: Int,
    /** Reading-order ordinal of the sentence's first character on [firstPage]; unique per page. */
    val position: Int,
    val text: String,
    /** One rectangle per source line fragment, in reading order. */
    val regions: List<PageSentenceRegion>,
    val confidence: Float,
    val semanticRole: String,
)

/**
 * Document-level sentence segmentation in the way text-first readers do it: every semantic role
 * is one continuous text stream in layout reading order; the text is rebuilt once (dehyphenated,
 * with a character-to-glyph map) and split by the sentence model over the whole stream, exactly
 * like `wtpsplit`'s `split`, then each sentence is mapped back to its page geometry.
 *
 * - Body, abstract and references flow across columns and pages. Figures, tables, captions,
 *   footnotes and page furniture are floats: they never interrupt these streams.
 * - A document or section title ends every open stream and is kept verbatim as one unit, like
 *   each contents entry. Other roles (captions, footnotes, ...) are segmented per block.
 * - Formulas are atoms. The model reads a natural-language placeholder plus the formula's own
 *   terminal punctuation, and a boundary can only fall after the whole formula.
 *
 * Pages are fed in order. After each page every stream commits all sentences but its last one,
 * which stays open because the next page may continue it; [finish] commits the rest.
 */
class SentenceStream(private val scorer: SentenceBoundaryScorer) {
    private val openFlows = linkedMapOf<String, Flow>()

    /** First page that still holds uncommitted text, or null when everything is committed. */
    val firstOpenPage: Int? get() = openFlows.values.mapNotNull(Flow::firstPage).minOrNull()

    fun addPage(pageNumber: Int, blocks: List<PositionedBlock>): List<DocumentSentence> {
        val committed = mutableListOf<DocumentSentence>()
        val cursor = PageCursor(pageNumber)
        blocks.sortedBy(PositionedBlock::readingOrder).forEach { block ->
            if (!block.selectableBody || block.lines.isEmpty()) return@forEach
            when (val role = semanticRole(block.type)) {
                SemanticTextRole.CONTENTS -> block.lines.forEach { line ->
                    committed += Flow(role).apply { append(block, line, blocks, cursor) }.commitVerbatim()
                }
                SemanticTextRole.TITLE -> {
                    committed += closeOpenFlows()
                    committed += Flow(role).apply { appendAll(block, blocks, cursor) }.commitVerbatim()
                }
                in CONTINUOUS_ROLES -> openFlows.getOrPut(role) { Flow(role) }.appendAll(block, blocks, cursor)
                else -> committed += Flow(role).apply { appendAll(block, blocks, cursor) }.commit(keepLast = false)
            }
        }
        openFlows.values.forEach { committed += it.commit(keepLast = true) }
        return committed.sortedWith(compareBy(DocumentSentence::firstPage, DocumentSentence::position))
    }

    fun finish(): List<DocumentSentence> =
        closeOpenFlows().sortedWith(compareBy(DocumentSentence::firstPage, DocumentSentence::position))

    private fun closeOpenFlows(): List<DocumentSentence> {
        val committed = openFlows.values.flatMap { it.commit(keepLast = false) }
        openFlows.clear()
        return committed
    }

    private enum class AtomKind { TEXT, INLINE_MATH, DISPLAY_MATH }

    private class CharSource(
        val page: Int,
        val line: Int,
        val ordinal: Int,
        val rect: NormalizedRect,
        val confidence: Float,
    )

    /** A run of text or one formula; [sources] has one entry per character (null for spaces). */
    private class Atom(
        val kind: AtomKind,
        val text: String,
        val sources: List<CharSource?>,
        val numberedEquation: Boolean = false,
    ) {
        fun drop(count: Int) = Atom(kind, text.substring(count), sources.subList(count, sources.size), numberedEquation)
    }

    /** Assigns page-local line keys and reading-order character ordinals. */
    private class PageCursor(val page: Int) {
        private var lines = 0
        private var ordinals = 0
        fun nextLine() = lines++
        fun reserve(count: Int) = ordinals.also { ordinals += count }
    }

    private class Rendered(
        val display: String,
        val model: String,
        val displaySources: List<CharSource?>,
        /** Display index at which a sentence ending at each model character is cut. */
        val modelCuts: IntArray,
        /** Owning atom and offset of each display character; -1 for joiners. */
        val displayAtoms: IntArray,
        val displayOffsets: IntArray,
    )

    private inner class Flow(val role: String) {
        private var atoms = mutableListOf<Atom>()

        val firstPage: Int? get() = atoms.firstNotNullOfOrNull { atom -> atom.sources.firstNotNullOfOrNull { it?.page } }

        fun appendAll(block: PositionedBlock, pageBlocks: List<PositionedBlock>, cursor: PageCursor) =
            block.lines.forEach { append(block, it, pageBlocks, cursor) }

        fun append(block: PositionedBlock, line: PositionedLine, pageBlocks: List<PositionedBlock>, cursor: PageCursor) {
            val text = line.text.trim().replace(WHITESPACE, " ")
            if (text.isEmpty()) return
            val kind = when {
                block.layoutLabel == "display_formula" -> AtomKind.DISPLAY_MATH
                line.isFormula -> AtomKind.INLINE_MATH
                else -> AtomKind.TEXT
            }
            val lineKey = cursor.nextLine()
            val base = cursor.reserve(text.length)
            fun source(index: Int, rect: NormalizedRect, confidence: Float) =
                CharSource(cursor.page, lineKey, base + index, rect, confidence)
            val lineRect = NormalizedRect(line.left, line.top, line.right, line.bottom)
            val sources = when {
                kind != AtomKind.TEXT -> text.indices.map { source(it, lineRect, line.confidence) }
                line.hasAlignedGlyphs -> {
                    var glyph = 0
                    text.indices.map { index ->
                        if (text[index] == ' ') return@map null
                        val g = line.glyphs[glyph++]
                        source(index, NormalizedRect(g.left, g.top, g.right, g.bottom), g.confidence)
                    }
                }
                else -> text.indices.map { index ->
                    if (text[index] == ' ') return@map null
                    val width = line.right - line.left
                    source(index, NormalizedRect(
                        line.left + width * index / text.length, line.top,
                        line.left + width * (index + 1) / text.length, line.bottom,
                    ), line.confidence)
                }
            }
            atoms += Atom(
                kind = kind,
                text = text,
                sources = sources,
                numberedEquation = kind == AtomKind.DISPLAY_MATH && block.hasAdjacentFormulaNumber(pageBlocks),
            )
        }

        /** Commits the whole flow as one entry without consulting the model. */
        fun commitVerbatim(): List<DocumentSentence> {
            val rendered = render()
            atoms.clear()
            return listOfNotNull(sentence(rendered, 0, rendered.display.length))
        }

        fun commit(keepLast: Boolean): List<DocumentSentence> {
            if (atoms.isEmpty()) return emptyList()
            val rendered = render()
            val starts = sentenceStarts(rendered)
            val emitted = if (keepLast) starts.dropLast(1) else starts
            val sentences = emitted.mapIndexedNotNull { index, start ->
                sentence(rendered, start, starts.getOrElse(index + 1) { rendered.display.length })
            }
            if (keepLast) keepFrom(rendered, starts.last()) else atoms.clear()
            return sentences
        }

        /**
         * `wtpsplit.indices_to_sentences`: a sentence ends after every character above threshold.
         * A fragment without any letter (a list marker such as `•`, `3)` or `(4)`) is not a
         * sentence and stays with the text that follows it.
         */
        private fun sentenceStarts(rendered: Rendered): List<Int> {
            val probabilities = scorer.probabilities(rendered.model)
            val cuts = sortedSetOf(0)
            probabilities.indices.forEach { index ->
                if (probabilities[index] > scorer.threshold) {
                    var cut = rendered.modelCuts[index]
                    while (cut < rendered.display.length && rendered.display[cut].isWhitespace()) cut++
                    if (cut < rendered.display.length) cuts += cut
                }
            }
            val starts = mutableListOf(0)
            cuts.drop(1).forEach { cut ->
                if ((starts.last() until cut).any { rendered.display[it].isLetter() }) starts += cut
            }
            return starts
        }

        private fun keepFrom(rendered: Rendered, start: Int) {
            if (start == 0) return
            val atomIndex = rendered.displayAtoms[start]
            val offset = rendered.displayOffsets[start]
            val kept = atoms.subList(atomIndex, atoms.size).toMutableList()
            if (offset > 0) kept[0] = kept[0].drop(offset)
            atoms = kept
        }

        private fun sentence(rendered: Rendered, start: Int, end: Int): DocumentSentence? {
            val text = rendered.display.substring(start, end).trim()
            val sources = rendered.displaySources.subList(start, end).filterNotNull()
            if (text.isEmpty() || sources.isEmpty()) return null
            val regions = sources.groupBy { it.page to it.line }.values.map { line ->
                PageSentenceRegion(line.first().page, NormalizedRect(
                    line.minOf { it.rect.left }, line.minOf { it.rect.top },
                    line.maxOf { it.rect.right }, line.maxOf { it.rect.bottom },
                ))
            }
            return DocumentSentence(
                firstPage = sources.first().page,
                position = sources.first().ordinal,
                text = text,
                regions = regions,
                confidence = sources.map(CharSource::confidence).average().toFloat(),
                semanticRole = role,
            )
        }

        private fun render(): Rendered {
            val display = StringBuilder()
            val model = StringBuilder()
            val displaySources = mutableListOf<CharSource?>()
            val modelCuts = mutableListOf<Int>()
            val displayAtoms = mutableListOf<Int>()
            val displayOffsets = mutableListOf<Int>()
            fun joiner(displayChar: Char) {
                display.append(displayChar)
                displaySources += null
                displayAtoms += -1
                displayOffsets += 0
                model.append(' ')
                modelCuts += display.length
            }
            atoms.forEachIndexed { atomIndex, atom ->
                if (atomIndex > 0) {
                    val previous = atoms[atomIndex - 1]
                    when {
                        previous.kind == AtomKind.DISPLAY_MATH || atom.kind == AtomKind.DISPLAY_MATH -> joiner('\n')
                        previous.kind == AtomKind.TEXT && atom.kind == AtomKind.TEXT &&
                            display.endsWith('-') && atom.text.first().isLowerCase() -> {
                            // Line-end hyphenation: "mem-" + "ory" reads as "memory".
                            display.setLength(display.length - 1)
                            displaySources.removeAt(displaySources.lastIndex)
                            displayAtoms.removeAt(displayAtoms.lastIndex)
                            displayOffsets.removeAt(displayOffsets.lastIndex)
                            model.setLength(model.length - 1)
                            modelCuts.removeAt(modelCuts.lastIndex)
                        }
                        else -> joiner(' ')
                    }
                }
                val atomStart = display.length
                atom.text.forEachIndexed { offset, character ->
                    display.append(character)
                    displaySources += atom.sources[offset]
                    displayAtoms += atomIndex
                    displayOffsets += offset
                }
                if (atom.kind == AtomKind.TEXT) {
                    model.append(atom.text)
                    atom.text.indices.forEach { modelCuts += atomStart + it + 1 }
                } else {
                    val projection = atom.modelProjection()
                    model.append(projection)
                    repeat(projection.length) { modelCuts += display.length }
                }
            }
            return Rendered(
                display.toString(), model.toString(), displaySources,
                modelCuts.toIntArray(), displayAtoms.toIntArray(), displayOffsets.toIntArray(),
            )
        }
    }

    /**
     * The model reads formulas as words: inline math as `variable`, display math as `equation`
     * (with its structural number), each followed by the formula's own terminal punctuation.
     */
    private fun Atom.modelProjection(): String {
        val word = when {
            kind == AtomKind.INLINE_MATH -> "variable"
            numberedEquation -> "equation (1)"
            else -> "equation"
        }
        return word + text.latexTerminalDelimiter()?.toString().orEmpty()
    }

    private fun String.latexTerminalDelimiter(): Char? {
        val body = removePrefix("\\[").removeSuffix("\\]")
            .replace(TRAILING_LATEX_SPACING, "")
            .trimEnd()
        if (body.endsWith("\\right.") || body.endsWith("\\left.")) return null
        return body.lastOrNull()?.takeIf { it in ".,;:?!" }
    }

    /** Formula numbers are structural metadata; they inform the model but never enter the text. */
    private fun PositionedBlock.hasAdjacentFormulaNumber(pageBlocks: List<PositionedBlock>): Boolean {
        val height = (bottom - top).coerceAtLeast(.001f)
        return pageBlocks.any { candidate ->
            if (candidate.layoutLabel != "formula_number") return@any false
            val candidateHeight = (candidate.bottom - candidate.top).coerceAtLeast(.001f)
            val verticalOverlap = (minOf(bottom, candidate.bottom) - maxOf(top, candidate.top)).coerceAtLeast(0f)
            val horizontalGap = when {
                candidate.left > right -> candidate.left - right
                left > candidate.right -> left - candidate.right
                else -> 0f
            }
            verticalOverlap / minOf(height, candidateHeight) >= .35f && horizontalGap <= .28f
        }
    }

    companion object {
        /**
         * First page to re-parse after an interruption, given the committed page count
         * ([firstOpenPage] when the run stopped) and each committed sentence's first page and
         * pages. It moves back until no committed sentence straddles the restart page, so every
         * sentence from there on is rebuilt from complete input.
         */
        fun resumePage(committedPages: Int, sentencePages: List<Pair<Int, Set<Int>>>): Int {
            var page = committedPages
            while (true) {
                page = sentencePages
                    .filter { (firstPage, pages) -> firstPage < page && pages.any { it >= page } }
                    .minOfOrNull { it.first } ?: return page
            }
        }

        private val WHITESPACE = Regex("\\s+")
        private val TRAILING_LATEX_SPACING = Regex(
            "(?:\\\\[!,;:]|\\\\(?:quad|qquad|enspace|thinspace|medspace|thickspace))+(?:\\s*)$",
        )
        private val CONTINUOUS_ROLES = setOf(SemanticTextRole.BODY, SemanticTextRole.ABSTRACT, SemanticTextRole.REFERENCE)

        private fun semanticRole(blockType: String): String = when (blockType) {
            LayoutBlockType.PARAGRAPH, LayoutBlockType.EQUATION -> SemanticTextRole.BODY
            LayoutBlockType.ABSTRACT -> SemanticTextRole.ABSTRACT
            LayoutBlockType.REFERENCE -> SemanticTextRole.REFERENCE
            LayoutBlockType.CAPTION -> SemanticTextRole.CAPTION
            LayoutBlockType.DOCUMENT_TITLE, LayoutBlockType.SECTION_TITLE -> SemanticTextRole.TITLE
            LayoutBlockType.AUTHOR -> SemanticTextRole.AUTHOR
            LayoutBlockType.CONTENTS -> SemanticTextRole.CONTENTS
            LayoutBlockType.FOOTNOTE -> SemanticTextRole.FOOTNOTE
            LayoutBlockType.SIDEBAR -> SemanticTextRole.SIDEBAR
            LayoutBlockType.HEADER -> SemanticTextRole.HEADER
            LayoutBlockType.FOOTER, LayoutBlockType.PAGE_NUMBER -> SemanticTextRole.FOOTER
            else -> SemanticTextRole.OTHER
        }
    }
}
