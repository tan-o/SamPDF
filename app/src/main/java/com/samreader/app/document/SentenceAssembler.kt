package com.samreader.app.document

import com.samreader.app.data.NormalizedRect
import com.samreader.app.data.LayoutBlockType
import kotlin.math.max
import kotlin.math.min

data class PositionedLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val glyphs: List<PositionedGlyph> = emptyList(),
)

data class PositionedGlyph(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

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

data class PositionedSentence(
    val text: String,
    val regions: List<NormalizedRect>,
    val confidence: Float,
    val terminated: Boolean,
    val canContinueFromPrevious: Boolean = true,
    val semanticRole: String = SemanticTextRole.BODY,
)

data class PageSentenceRegion(val pageNumber: Int, val rect: NormalizedRect)

data class DocumentPositionedSentence(
    val firstPage: Int,
    val text: String,
    val regions: List<PageSentenceRegion>,
    val confidence: Float,
    val semanticRole: String = SemanticTextRole.BODY,
)

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

object SentenceAssembler {
    private enum class FlowAtomKind { PROSE, DISPLAY_MATH }

    private data class FlowLine(
        val source: PositionedLine,
        val kind: FlowAtomKind,
        val numberedEquation: Boolean = false,
    )

    private data class OwnedSegment(
        val text: String,
        val regions: List<NormalizedRect>,
        val confidences: List<Float>,
        val boundaryAfter: Boolean?,
        val kind: FlowAtomKind,
        val numberedEquation: Boolean,
    )

    private data class DelimiterState(
        var roundDepth: Int = 0,
        var squareDepth: Int = 0,
        var curlyDepth: Int = 0,
        var inLatex: Boolean = false,
    ) {
    }

    private data class ScannedSegment(val text: String, val boundaryAfter: Boolean?)

    fun assemble(input: List<PositionedLine>): List<PositionedSentence> =
        assembleInOrder(readingOrder(input.filter { it.text.isNotBlank() }).map {
            FlowLine(it, FlowAtomKind.PROSE)
        })

    private fun assembleInOrder(
        input: List<FlowLine>,
        boundaryScorer: SentenceBoundaryScorer? = null,
    ): List<PositionedSentence> {
        val result = mutableListOf<PositionedSentence>()
        val text = StringBuilder()
        val regions = mutableListOf<NormalizedRect>()
        val confidences = mutableListOf<Float>()
        var previousKind: FlowAtomKind? = null

        fun flush(terminated: Boolean) {
            val value = text.toString().trim()
            if (value.length >= 2 && regions.isNotEmpty()) {
                result += PositionedSentence(
                    text = value,
                    regions = regions.distinct(),
                    confidence = confidences.average().toFloat(),
                    terminated = terminated,
                )
            }
            text.clear()
            regions.clear()
            confidences.clear()
            previousKind = null
        }

        val delimiterState = DelimiterState()
        val segments = buildList {
            input.filter { it.source.text.isNotBlank() }.forEach { flowLine ->
            val line = flowLine.source
            val normalized = line.text.trim().replace(Regex("\\s+"), " ")
            var glyphCursor = 0
            var textCursor = 0
            scanLine(normalized, delimiterState).forEach { scanned ->
                val segmentStart = normalized.indexOf(scanned.text, textCursor).coerceAtLeast(textCursor)
                val segmentEnd = (segmentStart + scanned.text.length).coerceAtMost(normalized.length)
                val glyphCount = scanned.text.count { !it.isWhitespace() }
                val ownedGlyphs = line.glyphs.drop(glyphCursor).take(glyphCount)
                glyphCursor += ownedGlyphs.size
                if (ownedGlyphs.isNotEmpty()) {
                    add(OwnedSegment(
                        scanned.text,
                        ownedGlyphs.map { NormalizedRect(it.left, it.top, it.right, it.bottom) },
                        ownedGlyphs.map(PositionedGlyph::confidence),
                        scanned.boundaryAfter,
                        flowLine.kind,
                        flowLine.numberedEquation,
                    ))
                } else {
                    val width = line.right - line.left
                    add(OwnedSegment(
                        scanned.text,
                        listOf(NormalizedRect(
                            line.left + width * segmentStart / normalized.length.coerceAtLeast(1), line.top,
                            line.left + width * segmentEnd / normalized.length.coerceAtLeast(1), line.bottom,
                        )),
                        listOf(line.confidence),
                        scanned.boundaryAfter,
                        flowLine.kind,
                        flowLine.numberedEquation,
                    ))
                }
                textCursor = segmentEnd
            }
        }
        }
        val modelText = StringBuilder()
        val modelEndIndices = mutableListOf<Int>()
        segments.forEach { segment ->
            val semanticText = segment.text.forBoundaryModel(segment.kind, segment.numberedEquation)
            modelEndIndices += appendBoundaryProjection(modelText, semanticText)
        }
        val boundaryProbabilities = boundaryScorer?.probabilities(modelText.toString())
        segments.forEachIndexed { index, segment ->
            if (text.isNotEmpty()) {
                if (segment.kind == FlowAtomKind.DISPLAY_MATH || previousKind == FlowAtomKind.DISPLAY_MATH) {
                    text.trimEndInPlace()
                    text.append('\n')
                } else if (text.endsWith("-") && segment.text.firstOrNull()?.isLowerCase() == true) text.deleteCharAt(text.lastIndex)
                else if (isSplitDecimal(text, segment.text)) Unit
                else text.append(' ')
            }
            text.append(segment.text)
            previousKind = segment.kind
            regions += segment.regions
            confidences += segment.confidences
            val boundary = segment.boundaryAfter ?: boundaryProbabilities?.let { probabilities ->
                probabilities.getOrElse(modelEndIndices[index]) { 0f } >= boundaryScorer.threshold
            } ?: isScientificSentenceBoundary(text.toString(), segments.getOrNull(index + 1)?.text)
            if (boundary) flush(true)
        }
        flush(false)
        return result
    }

    /**
     * Orders layout blocks as a document reader would, then assembles one continuous text stream.
     * A sentence may therefore continue from the bottom of the left column to the top of the
     * right column instead of being forcibly split at a block or column boundary.
     */
    fun assembleBlocks(
        blocks: List<PositionedBlock>,
        boundaryScorer: SentenceBoundaryScorer? = null,
    ): List<PositionedSentence> {
        val candidates = blocks.filter { it.lines.isNotEmpty() && it.selectableBody }
        val bodyFontHeight = candidates.flatMap(PositionedBlock::lines)
            .map(::estimatedFontHeight).filter { it > 0f }.medianOrZero()
        val usable = candidates.filterNot { block ->
            val fontHeight = block.lines.map(::estimatedFontHeight).medianOrZero()
            val inMarginBand = block.bottom <= .055f || block.top >= .925f
            inMarginBand && (bodyFontHeight == 0f || fontHeight <= bodyFontHeight * 1.15f)
        }
        if (usable.isEmpty()) return emptyList()
        val modelOrdered = usable.all { it.readingOrder != Int.MAX_VALUE }
        val twoColumn = !modelOrdered &&
            usable.count { it.right <= .56f } >= 2 && usable.count { it.left >= .44f } >= 2
        val ordered = when {
            modelOrdered -> usable.sortedBy(PositionedBlock::readingOrder)
            twoColumn -> orderTwoColumnBlocks(usable)
            else -> usable.sortedBy { it.top }
        }
        val result = mutableListOf<PositionedSentence>()
        val flowingLines = mutableListOf<FlowLine>()
        var previousFlowingBlock: PositionedBlock? = null
        var flowingRole: String? = null
        fun flushFlow() {
            if (flowingLines.isNotEmpty()) {
                val role = requireNotNull(flowingRole)
                result += assembleInOrder(flowingLines, boundaryScorer).map {
                    it.copy(semanticRole = role)
                }
            }
            flowingLines.clear()
            previousFlowingBlock = null
            flowingRole = null
        }
        ordered.forEach { block ->
            // A block's source (ML Kit or native PDF) already owns its line order. Geometry is
            // used to order semantic blocks, never to reshuffle lines inside a block.
            val lines = block.lines
            val role = semanticRole(block.type)
            if (role == SemanticTextRole.CONTENTS) {
                flushFlow()
                result += lines.mapNotNull { it.asStructuralEntry(role) }
            } else if (role in CONTINUOUS_ROLES) {
                if (flowingRole != null && flowingRole != role) flushFlow()
                previousFlowingBlock?.let { previous ->
                    if (!canContinueBodyFlow(previous, block, blocks, twoColumn)) flushFlow()
                }
                flowingRole = role
                val displayMath = block.layoutLabel == "display_formula"
                val numberedEquation = displayMath && block.hasAdjacentFormulaNumber(blocks)
                flowingLines += lines.map { line ->
                    FlowLine(
                        source = line,
                        kind = if (displayMath) FlowAtomKind.DISPLAY_MATH else FlowAtomKind.PROSE,
                        numberedEquation = numberedEquation,
                    )
                }
                previousFlowingBlock = block
            } else {
                flushFlow()
                val structural = assembleInOrder(lines.map { FlowLine(it, FlowAtomKind.PROSE) }, boundaryScorer)
                result += structural.mapIndexed { index, sentence ->
                    sentence.copy(
                        terminated = index == structural.lastIndex || sentence.terminated,
                        canContinueFromPrevious = false,
                        semanticRole = role,
                    )
                }
            }
        }
        flushFlow()
        return result
    }

    /** Formula numbers are structural metadata. They guide sentence inference but never enter the original text. */
    private fun PositionedBlock.hasAdjacentFormulaNumber(allBlocks: List<PositionedBlock>): Boolean {
        val height = (bottom - top).coerceAtLeast(.001f)
        return allBlocks.any { candidate ->
            if (candidate.layoutLabel != "formula_number") return@any false
            val candidateHeight = (candidate.bottom - candidate.top).coerceAtLeast(.001f)
            val verticalOverlap = (minOf(bottom, candidate.bottom) - maxOf(top, candidate.top))
                .coerceAtLeast(0f)
            val sameRow = verticalOverlap / minOf(height, candidateHeight) >= .35f
            val horizontalGap = when {
                candidate.left > right -> candidate.left - right
                left > candidate.right -> left - candidate.right
                else -> 0f
            }
            sameRow && horizontalGap <= .28f
        }
    }

    /** Contents are list entries, not prose sentences; preserve each recognized entry verbatim. */
    private fun PositionedLine.asStructuralEntry(role: String): PositionedSentence? {
        val value = text.trim().replace(Regex("\\s+"), " ")
        if (value.isBlank()) return null
        val entryRegions = glyphs.map { glyph ->
            NormalizedRect(glyph.left, glyph.top, glyph.right, glyph.bottom)
        }.ifEmpty { listOf(NormalizedRect(left, top, right, bottom)) }
        val entryConfidences = glyphs.map(PositionedGlyph::confidence).ifEmpty { listOf(confidence) }
        return PositionedSentence(
            text = value,
            regions = entryRegions.distinct(),
            confidence = entryConfidences.average().toFloat(),
            terminated = true,
            canContinueFromPrevious = false,
            semanticRole = role,
        )
    }

    private fun canContinueBodyFlow(
        previous: PositionedBlock,
        current: PositionedBlock,
        allBlocks: List<PositionedBlock>,
        twoColumn: Boolean,
    ): Boolean {
        if (
            previous.readingOrder != Int.MAX_VALUE &&
            current.readingOrder != Int.MAX_VALUE
        ) {
            val firstOrder = min(previous.readingOrder, current.readingOrder)
            val lastOrder = max(previous.readingOrder, current.readingOrder)
            return allBlocks.none { barrier ->
                barrier !== previous && barrier !== current &&
                    barrier.readingOrder in (firstOrder + 1) until lastOrder &&
                    !barrier.selectableBody && barrier.layoutLabel != "formula_number"
            }
        }
        val previousSpansColumns = previous.left < .44f && previous.right > .56f
        val currentSpansColumns = current.left < .44f && current.right > .56f
        if (previous.type == LayoutBlockType.EQUATION || current.type == LayoutBlockType.EQUATION) {
            if (previousSpansColumns || currentSpansColumns) {
                return current.top - previous.bottom in -.025f..0.12f
            }
        }
        val previousFont = previous.lines.map(::estimatedFontHeight).medianOrZero()
        val currentFont = current.lines.map(::estimatedFontHeight).medianOrZero()
        if (previousFont > 0f && currentFont > 0f) {
            val fontRatio = max(previousFont, currentFont) / min(previousFont, currentFont)
            if (fontRatio > 1.35f) return false
        }

        val previousCenter = (previous.left + previous.right) / 2f
        val currentCenter = (current.left + current.right) / 2f
        val sameColumn = !twoColumn || (previousCenter < .5f) == (currentCenter < .5f)
        if (sameColumn) {
            val verticalGap = current.top - previous.bottom
            val allowedGap = max(max(previousFont, currentFont) * 3.5f, .035f)
            if (verticalGap < -.025f || verticalGap > allowedGap) return false
            val horizontalOverlap = min(previous.right, current.right) - max(previous.left, current.left)
            if (horizontalOverlap <= .04f) return false
            val blocked = allBlocks.any { barrier ->
                barrier !== previous && barrier !== current &&
                    (barrier.type !in FLOWING_BLOCK_TYPES || !barrier.selectableBody) &&
                    barrier.top >= previous.bottom - .01f && barrier.bottom <= current.top + .01f &&
                    min(previous.right, barrier.right) - max(previous.left, barrier.left) > .04f
            }
            return !blocked
        }

        return previousCenter < .5f && currentCenter >= .5f &&
            previous.bottom >= .58f && current.top <= .42f
    }

    private fun orderTwoColumnBlocks(blocks: List<PositionedBlock>): List<PositionedBlock> {
        val spanning = blocks.filter { it.left < .44f && it.right > .56f }.sortedBy(PositionedBlock::top)
        val columns = blocks.filter { it !in spanning }
        val remaining = columns.toMutableSet()
        return buildList {
            spanning.forEach { separator ->
                val before = remaining.filter { (it.top + it.bottom) / 2f < separator.top }
                addAll(before.filter { (it.left + it.right) / 2f < .5f }.sortedBy(PositionedBlock::top))
                addAll(before.filter { (it.left + it.right) / 2f >= .5f }.sortedBy(PositionedBlock::top))
                remaining.removeAll(before.toSet())
                add(separator)
            }
            addAll(remaining.filter { (it.left + it.right) / 2f < .5f }.sortedBy(PositionedBlock::top))
            addAll(remaining.filter { (it.left + it.right) / 2f >= .5f }.sortedBy(PositionedBlock::top))
        }
    }

    private fun estimatedFontHeight(line: PositionedLine): Float =
        line.glyphs.map { it.bottom - it.top }.filter { it > 0f }.medianOrZero()
            .takeIf { it > 0f } ?: (line.bottom - line.top).coerceAtLeast(0f)

    private fun List<Float>.medianOrZero(): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    fun mergePages(
        pages: List<Pair<Int, List<PositionedSentence>>>,
        includeTrailingIncomplete: Boolean = true,
        boundaryScorer: SentenceBoundaryScorer? = null,
    ): List<DocumentPositionedSentence> {
        data class Pending(
            val firstPage: Int,
            var text: String,
            val regions: MutableList<PageSentenceRegion>,
            val confidences: MutableList<Float>,
            var terminated: Boolean,
            val semanticRole: String,
        )

        val result = mutableListOf<DocumentPositionedSentence>()
        var pending: Pending? = null
        fun emit() {
            val value = pending ?: return
            result += DocumentPositionedSentence(
                value.firstPage, value.text.trim(), value.regions.distinct(),
                value.confidences.average().toFloat(),
                value.semanticRole,
            )
            pending = null
        }

        fun appendIndependent(page: Int, sentence: PositionedSentence) {
            result += DocumentPositionedSentence(
                firstPage = page,
                text = sentence.text.trim(),
                regions = sentence.regions.distinct().map { PageSentenceRegion(page, it) },
                confidence = sentence.confidence,
                semanticRole = sentence.semanticRole,
            )
        }

        val orderedPages = pages.sortedBy { it.first }
        orderedPages.forEachIndexed { pageIndex, (page, sentences) ->
            val lastFlowingIndexByRole = sentences.withIndex()
                .filter { it.value.canContinueFromPrevious }
                .associate { it.value.semanticRole to it.index }
            sentences.forEachIndexed { sentenceIndex, original ->
                val firstFlowingOnLaterPage = orderedPages.asSequence()
                    .drop(pageIndex + 1)
                    .mapNotNull { (_, laterSentences) ->
                        laterSentences.firstOrNull {
                            it.canContinueFromPrevious && it.semanticRole == original.semanticRole
                        }?.text
                    }
                    .firstOrNull()
                val sentence = if (
                    sentenceIndex == lastFlowingIndexByRole[original.semanticRole] &&
                    firstFlowingOnLaterPage != null
                ) {
                    original.copy(terminated = isBoundaryBetween(
                        original.text,
                        firstFlowingOnLaterPage,
                        boundaryScorer,
                    ))
                } else original
                val current = pending
                val pendingComesFromEarlierPage = current != null &&
                    current.regions.none { it.pageNumber == page }
                if (current != null && !current.terminated && pendingComesFromEarlierPage &&
                    (!sentence.canContinueFromPrevious || sentence.semanticRole != current.semanticRole)) {
                    // A new page may start with a header, figure or caption before the body resumes.
                    // Persist that structure independently without allowing it to consume or close
                    // the open body sentence from the previous page.
                    appendIndependent(page, sentence)
                    return@forEachIndexed
                }
                if (current == null) {
                    pending = Pending(
                        page, sentence.text,
                        sentence.regions.mapTo(mutableListOf()) { PageSentenceRegion(page, it) },
                        mutableListOf(sentence.confidence), sentence.terminated, sentence.semanticRole,
                    )
                } else if (current.terminated) {
                    emit()
                    pending = Pending(
                        page, sentence.text,
                        sentence.regions.mapTo(mutableListOf()) { PageSentenceRegion(page, it) },
                        mutableListOf(sentence.confidence), sentence.terminated, sentence.semanticRole,
                    )
                } else if (!sentence.canContinueFromPrevious || sentence.semanticRole != current.semanticRole) {
                    emit()
                    pending = Pending(
                        page, sentence.text,
                        sentence.regions.mapTo(mutableListOf()) { PageSentenceRegion(page, it) },
                        mutableListOf(sentence.confidence), sentence.terminated, sentence.semanticRole,
                    )
                } else {
                    current.text = joinContinuation(current.text, sentence.text)
                    current.regions += sentence.regions.map { PageSentenceRegion(page, it) }
                    current.confidences += sentence.confidence
                    current.terminated = sentence.terminated
                }
                if (pending?.terminated == true) emit()
            }
        }
        if (includeTrailingIncomplete) emit()
        // Cross-page body sentences are emitted after any leading structure that was skipped while
        // searching for their continuation. Stable page sorting restores logical document order.
        return result.sortedBy(DocumentPositionedSentence::firstPage)
    }

    private fun joinContinuation(first: String, second: String): String = when {
        first.endsWith('-') && second.firstOrNull()?.isLowerCase() == true -> first.dropLast(1) + second
        isSplitDecimal(first, second) -> first + second.trimStart()
        first.endsWithDisplayMathLine() || second.startsWithDisplayMathLine() ->
            first.trimEnd() + "\n" + second.trimStart()
        else -> "$first $second"
    }

    private fun String.endsWithDisplayMathLine(): Boolean =
        lineSequence().lastOrNull()?.trim().isWrappedLatexFormula()

    private fun String.startsWithDisplayMathLine(): Boolean =
        lineSequence().firstOrNull()?.trim().isWrappedLatexFormula()

    private fun String?.isWrappedLatexFormula(): Boolean =
        this != null && startsWith("\\[") && endsWith("\\]")

    private fun StringBuilder.trimEndInPlace() {
        while (isNotEmpty() && last().isWhitespace()) deleteCharAt(lastIndex)
    }

    private fun isSplitDecimal(first: CharSequence, second: String): Boolean =
        Regex("\\d\\.$").containsMatchIn(first) && second.trimStart().firstOrNull()?.isDigit() == true

    private fun appendContinuation(target: StringBuilder, next: String) {
        if (target.isNotEmpty()) {
            if (target.endsWith("-") && next.firstOrNull()?.isLowerCase() == true) {
                target.deleteCharAt(target.lastIndex)
            } else if (!isSplitDecimal(target, next)) {
                target.append(' ')
            }
        }
        target.append(next)
    }

    /** Appends a whitespace-normalized model atom and returns its last meaningful character. */
    private fun appendBoundaryProjection(target: StringBuilder, next: String): Int {
        val normalized = next.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return target.indexOfLast { !it.isWhitespace() }.coerceAtLeast(0)
        appendContinuation(target, normalized)
        val segmentStart = (target.length - normalized.length).coerceAtLeast(0)
        val terminal = normalized.indexOfLast { it in ".?!" }
        if (terminal >= 0) return segmentStart + terminal
        return target.indexOfLast { !it.isWhitespace() }.coerceAtLeast(0)
    }

    private fun isBoundaryBetween(
        first: String,
        second: String,
        boundaryScorer: SentenceBoundaryScorer?,
    ): Boolean {
        if (boundaryScorer == null) return isScientificSentenceBoundary(first, second)
        val combined = "$first $second"
        val semantic = combined.forBoundaryModel(FlowAtomKind.PROSE, numberedEquation = false)
        val firstSemantic = first.forBoundaryModel(FlowAtomKind.PROSE, numberedEquation = false)
        val probe = firstSemantic.indexOfLast { it in ".?!" }
            .takeIf { it >= 0 } ?: firstSemantic.lastIndex
        val probabilities = boundaryScorer.probabilities(semantic)
        return probabilities.getOrElse(probe) { 0f } >= boundaryScorer.threshold
    }

    private fun scanLine(text: String, state: DelimiterState): List<ScannedSegment> {
        val result = mutableListOf<ScannedSegment>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (index + 1 < text.length && text[index] == '\\' && text[index + 1] == '[') {
                state.inLatex = true
                index += 2
                continue
            }
            if (index + 1 < text.length && text[index] == '\\' && text[index + 1] == ']') {
                state.inLatex = false
                index += 2
                continue
            }
            when (text[index]) {
                '(' -> state.roundDepth++
                '[' -> state.squareDepth++
                '{' -> state.curlyDepth++
                ')' -> state.roundDepth = (state.roundDepth - 1).coerceAtLeast(0)
                ']' -> state.squareDepth = (state.squareDepth - 1).coerceAtLeast(0)
                '}' -> state.curlyDepth = (state.curlyDepth - 1).coerceAtLeast(0)
            }
            if (!state.inLatex && text[index] in ".?!") {
                var end = index + 1
                var futureRound = state.roundDepth
                var futureSquare = state.squareDepth
                var futureCurly = state.curlyDepth
                while (end < text.length && text[end] in "\"')]}…”)’」』") {
                    when (text[end]) {
                        ')' -> futureRound = (futureRound - 1).coerceAtLeast(0)
                        ']' -> futureSquare = (futureSquare - 1).coerceAtLeast(0)
                        '}' -> futureCurly = (futureCurly - 1).coerceAtLeast(0)
                    }
                    end++
                }
                val prefix = text.substring(start, end)
                val suffix = text.substring(end)
                val hasFollowingTextOnLine = suffix.any { !it.isWhitespace() }
                if (hasFollowingTextOnLine) {
                    prefix.trim().takeIf(String::isNotEmpty)?.let {
                        result += ScannedSegment(it, boundaryAfter = null)
                    }
                    start = end
                    index = end
                    state.roundDepth = futureRound
                    state.squareDepth = futureSquare
                    state.curlyDepth = futureCurly
                    continue
                }
            }
            index++
        }
        text.substring(start).trim().takeIf(String::isNotEmpty)?.let {
            result += ScannedSegment(
                text = it,
                // OCR can drop a closing delimiter for the rest of a page. Delimiter state helps
                // locate candidates but must never override the learned semantic boundary model.
                boundaryAfter = null,
            )
        }
        return result.ifEmpty { listOf(ScannedSegment(text, boundaryAfter = false)) }
    }

    /**
     * Formula syntax has its own channel. WtP sees a natural-language atom plus any terminal
     * punctuation rendered by the formula; the exact LaTeX remains untouched in the user text.
     */
    private fun String.forBoundaryModel(kind: FlowAtomKind, numberedEquation: Boolean): String {
        if (kind == FlowAtomKind.DISPLAY_MATH) {
            val marker = if (numberedEquation) "equation (1)" else "equation"
            return marker + (latexTerminalDelimiter()?.toString().orEmpty())
        }
        return replace(DISPLAY_LATEX) { match ->
            " variable${match.value.latexTerminalDelimiter()?.toString().orEmpty()} "
        }.replace(INLINE_LATEX) { match ->
            " variable${match.value.latexTerminalDelimiter()?.toString().orEmpty()} "
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun String.latexTerminalDelimiter(): Char? {
        val body = removePrefix("\\[").removeSuffix("\\]")
            .removePrefix("\\(").removeSuffix("\\)")
            .replace(TRAILING_LATEX_SPACING, "")
            .trimEnd()
        if (body.endsWith("\\right.") || body.endsWith("\\left.")) return null
        return body.lastOrNull()?.takeIf { it in ".,;:?!" }
    }

    internal fun isScientificSentenceBoundary(text: String, next: String?): Boolean {
        if (hasUnclosedDelimiter(text)) return false
        val stripped = text.trimEnd().trimEnd('"', '\'', ')', ']', '}')
        val terminal = stripped.lastOrNull() ?: return false
        if (terminal == '!' || terminal == '?') return true
        if (terminal != '.') return false
        val token = Regex("([A-Za-z](?:[A-Za-z.-]*[A-Za-z])?|[A-Za-z]|\\d+)\\.$")
            .find(stripped)?.groupValues?.get(1).orEmpty()
        val normalizedToken = token.lowercase().trimEnd('.')
        if (normalizedToken in ALWAYS_CONTINUING_ABBREVIATIONS) return false
        if (next == null) return true

        val nextText = next.trimStart()
        val nextCharacter = nextText.firstOrNull { it.isLetterOrDigit() }
        if (normalizedToken in CONTEXTUAL_ABBREVIATIONS && nextCharacter?.isLowerCase() == true) return false
        if (token.length == 1 && token.singleOrNull()?.isUpperCase() == true && nextCharacter?.isUpperCase() == true) return false
        if (Regex("(?:[A-Za-z]\\.){2,}$").containsMatchIn(stripped) && nextCharacter?.isLowerCase() == true) return false
        if (token.all(Char::isDigit) && nextCharacter?.isDigit() == true) return false
        if (nextCharacter?.isLowerCase() == true) return false
        if (Regex("(?:https?://|www\\.|doi\\s*:?)\\S*$", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) return false
        return true
    }

    private fun hasUnclosedDelimiter(text: String): Boolean {
        var round = 0
        var square = 0
        var curly = 0
        text.forEach { character ->
            when (character) {
                '(' -> round++
                '[' -> square++
                '{' -> curly++
                ')' -> round = (round - 1).coerceAtLeast(0)
                ']' -> square = (square - 1).coerceAtLeast(0)
                '}' -> curly = (curly - 1).coerceAtLeast(0)
            }
        }
        return round > 0 || square > 0 || curly > 0
    }

    private fun readingOrder(lines: List<PositionedLine>): List<PositionedLine> {
        if (lines.size < 6) return lines.sortedWith(compareBy(PositionedLine::top, PositionedLine::left))
        val leftColumn = lines.filter { it.right <= 0.58f }
        val rightColumn = lines.filter { it.left >= 0.42f }
        if (leftColumn.size < 3 || rightColumn.size < 3) {
            return lines.sortedWith(compareBy(PositionedLine::top, PositionedLine::left))
        }
        val top = min(leftColumn.minOf(PositionedLine::top), rightColumn.minOf(PositionedLine::top))
        val spanning = lines.filter { it !in leftColumn && it !in rightColumn }
        val header = spanning.filter { it.bottom <= top + 0.02f }
        return buildList {
            addAll(header.sortedWith(compareBy(PositionedLine::top, PositionedLine::left)))
            addAll(leftColumn.sortedWith(compareBy(PositionedLine::top, PositionedLine::left)))
            addAll(rightColumn.sortedWith(compareBy(PositionedLine::top, PositionedLine::left)))
            addAll((spanning - header.toSet()).sortedWith(compareBy(PositionedLine::top, PositionedLine::left)))
        }
    }

    private val FLOWING_BLOCK_TYPES = setOf(
        LayoutBlockType.PARAGRAPH,
        LayoutBlockType.ABSTRACT,
        LayoutBlockType.REFERENCE,
        LayoutBlockType.EQUATION,
    )

    private val CONTINUOUS_ROLES = setOf(
        SemanticTextRole.BODY,
        SemanticTextRole.ABSTRACT,
        SemanticTextRole.REFERENCE,
    )

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
    private val ALWAYS_CONTINUING_ABBREVIATIONS = setOf(
        "fig", "figs", "eq", "eqs", "sec", "secs", "ref", "refs", "vol", "no", "nos",
        "dr", "prof", "mr", "mrs", "ms", "vs", "cf", "approx", "resp", "dept", "univ",
        "e.g", "i.e",
    )
    private val CONTEXTUAL_ABBREVIATIONS = setOf("etc", "al")
    private val DISPLAY_LATEX = Regex("\\\\\\[.*?(?:\\\\\\]|$)")
    private val INLINE_LATEX = Regex("\\\\\\(.*?(?:\\\\\\)|$)")
    private val TRAILING_LATEX_SPACING = Regex(
        "(?:\\\\[!,;:]|\\\\(?:quad|qquad|enspace|thinspace|medspace|thickspace))+(?:\\s*)$",
    )
}
