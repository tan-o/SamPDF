package com.samreader.app.ui.reflow

import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.NormalizedRect
import com.samreader.app.data.PageEntity
import com.samreader.app.data.PageLayoutBlockEntity
import com.samreader.app.data.SentenceEntity
import com.samreader.app.document.SemanticTextRole
import java.util.Locale

/**
 * A region of one PDF page, shown in the reading view as an image cropped from the page.
 * [widthEm] is its width in multiples of the paper's body font size, so typeset content such as
 * an equation appears at the same size as the reading text; [aspectRatio] is width / height.
 */
data class PageCrop(val page: Int, val rect: NormalizedRect, val widthEm: Float = 0f, val aspectRatio: Float = 1f) {
    /** `page_left_top_right_bottom`, the form used in crop URLs and figure tap events. */
    fun encode(): String =
        String.format(Locale.US, "%d_%.4f_%.4f_%.4f_%.4f", page, rect.left, rect.top, rect.right, rect.bottom)

    companion object {
        fun decode(encoded: String): PageCrop? {
            val parts = encoded.split('_')
            if (parts.size != 5) return null
            val page = parts[0].toIntOrNull() ?: return null
            val values = parts.drop(1).map { it.toFloatOrNull() ?: return null }
            return PageCrop(page, NormalizedRect(values[0], values[1], values[2], values[3]))
        }
    }
}

sealed interface ReflowNode {
    data class Heading(val sentence: SentenceEntity, val documentTitle: Boolean) : ReflowNode

    /** Sentences of one paragraph; [role] is a [SemanticTextRole]. */
    data class Paragraph(val role: String, val sentences: List<SentenceEntity>) : ReflowNode

    /** Figures, charts or tables with their captions, in source reading order. */
    data class Figure(val parts: List<FigurePart>) : ReflowNode
}

sealed interface FigurePart {
    data class Visual(val crop: PageCrop, val type: String) : FigurePart
    data class Caption(val sentence: SentenceEntity) : FigurePart
}

/**
 * The parsed paper as a reflowable document, following the reading order of the layout model.
 *
 * [nodes] is the reading flow: title, authors, abstract, headings, body paragraphs, figures,
 * tables and equations, then the references. Page furniture (running heads and feet, page
 * numbers, journal logos, sidebars, publication metadata above the title) is left out. As in
 * EPUB conversion, [footnotes] are collected as endnotes, and [backMatter] holds what follows the
 * references before any new heading (author biographies and their photos).
 *
 * Paragraphs come from the layout blocks sentences start in: a sentence joins the open paragraph
 * when it starts in the same block, or in the block the previous sentence ran into (a paragraph
 * that continues across a column or page). Figures and tables with their captions are floats, as
 * in LaTeX or EPUB conversion: they are held back and placed at the next paragraph break, so they
 * never cut a sentence, and never split the reference list. [displayEquations] maps each display
 * formula's LaTeX to its page region, because the typeset PDF rendering is the faithful form.
 */
class ReflowDocument(
    val nodes: List<ReflowNode>,
    val displayEquations: Map<String, PageCrop>,
    val footnotes: List<SentenceEntity> = emptyList(),
    val backMatter: List<ReflowNode> = emptyList(),
) {
    val headings: List<SentenceEntity>
        get() = nodes.filterIsInstance<ReflowNode.Heading>().filterNot { it.documentTitle }.map { it.sentence }

    companion object {
        fun build(
            sentences: List<SentenceEntity>,
            blocks: List<PageLayoutBlockEntity>,
            pages: List<PageEntity>,
        ): ReflowDocument {
            val blocksByPage = blocks.groupBy(PageLayoutBlockEntity::pageNumber)
            val pageSizes = pages.associate { it.pageNumber to (it.widthPoints to it.heightPoints) }
            val bodyFont = bodyFontPoints(sentences, pageSizes)
            fun PageLayoutBlockEntity.crop(number: PageLayoutBlockEntity? = null): PageCrop {
                val rect = NormalizedRect(
                    (left - EDGE).coerceAtLeast(0f),
                    (minOf(top, number?.top ?: top) - EDGE).coerceAtLeast(0f),
                    (maxOf(right, number?.right ?: right) + EDGE).coerceAtMost(1f),
                    (maxOf(bottom, number?.bottom ?: bottom) + EDGE).coerceAtMost(1f),
                )
                val (pageWidth, pageHeight) = pageSizes[pageNumber] ?: LETTER
                val width = (rect.right - rect.left) * pageWidth
                val height = ((rect.bottom - rect.top) * pageHeight).coerceAtLeast(1f)
                return PageCrop(pageNumber, rect, width / bodyFont, width / height)
            }
            val items = buildList {
                sentences.forEach { sentence ->
                    val block = blocksByPage[sentence.pageNumber].orEmpty().firstBlockOf(sentence)
                    add(Item.Text(sentence, block, OrderKey(sentence.pageNumber, block?.position ?: -1, sentence.position)))
                }
                blocks.filter { it.type in VISUAL_TYPES }.forEach { block ->
                    add(Item.Visual(block, OrderKey(block.pageNumber, block.position, -1)))
                }
            }.sortedBy(Item::key)

            val footnotes = items.filterIsInstance<Item.Text>().filter { it.role == SemanticTextRole.FOOTNOTE }.map { it.sentence }
            val flow = items.filterNot { it is Item.Text && it.role in OUTSIDE_FLOW_ROLES }
            // Back matter: whatever follows the last reference entry, up to the next heading.
            val lastReference = flow.indexOfLast { it is Item.Text && it.role == SemanticTextRole.REFERENCE }
            val backEnd = if (lastReference < 0) lastReference else flow.withIndex()
                .firstOrNull { (index, item) -> index > lastReference && item is Item.Text && item.role == SemanticTextRole.TITLE }
                ?.index ?: flow.size
            val back = if (lastReference < 0) emptyList() else flow.subList(lastReference + 1, backEnd)
            val main = if (lastReference < 0) flow else flow.subList(0, lastReference + 1) + flow.subList(backEnd, flow.size)

            fun buildNodes(items: List<Item>): List<ReflowNode> {
                val nodes = mutableListOf<ReflowNode>()
                val floats = mutableListOf<Item>()
                var paragraph: OpenParagraph? = null

                fun flushFloats() {
                    var figure = mutableListOf<FigurePart>()
                    fun emitFigure() { if (figure.isNotEmpty()) nodes += ReflowNode.Figure(figure); figure = mutableListOf() }
                    floats.forEach { item ->
                        val part = when (item) {
                            is Item.Visual -> FigurePart.Visual(item.block.crop(), item.block.type)
                            is Item.Text -> FigurePart.Caption(item.sentence)
                        }
                        // One figure is visuals plus their caption; a new visual after a caption (or a
                        // new caption after a visual that followed a caption) starts the next figure.
                        val complete = figure.any { it is FigurePart.Visual } && figure.any { it is FigurePart.Caption }
                        if (complete && figure.last()::class != part::class) emitFigure()
                        figure += part
                    }
                    emitFigure()
                    floats.clear()
                }

                fun closeParagraph(flush: Boolean = true) {
                    paragraph?.let { nodes += ReflowNode.Paragraph(it.role, it.sentences) }
                    paragraph = null
                    if (flush) flushFloats()
                }

                items.forEach { item ->
                    when {
                        item is Item.Visual -> floats += item
                        item !is Item.Text -> Unit
                        item.role == SemanticTextRole.CAPTION -> floats += item
                        item.role == SemanticTextRole.TITLE -> {
                            closeParagraph()
                            nodes += ReflowNode.Heading(item.sentence, item.block?.type == LayoutBlockType.DOCUMENT_TITLE)
                        }
                        item.role == SemanticTextRole.CONTENTS -> {
                            closeParagraph()
                            nodes += ReflowNode.Paragraph(item.role, listOf(item.sentence))
                        }
                        else -> {
                            val open = paragraph
                            val continues = open != null && open.role == item.role && item.block != null &&
                                (item.block == open.block || open.sentences.last().touches(item.block))
                            if (continues) {
                                open!!.sentences += item.sentence
                                open.block = item.block
                            } else {
                                val withinReferences = open?.role == SemanticTextRole.REFERENCE && item.role == SemanticTextRole.REFERENCE
                                closeParagraph(flush = !withinReferences)
                                paragraph = OpenParagraph(item.role, mutableListOf(item.sentence), item.block)
                            }
                        }
                    }
                }
                closeParagraph()
                return nodes
            }

            val displayEquations = blocks
                .filter { it.type == LayoutBlockType.EQUATION && it.text.startsWith("\\[") }
                .associate { equation -> equation.text to equation.crop(numberOf(equation, blocksByPage[equation.pageNumber].orEmpty())) }
            return ReflowDocument(buildNodes(main), displayEquations, footnotes, buildNodes(back))
        }

        private data class OrderKey(val page: Int, val block: Int, val position: Int) : Comparable<OrderKey> {
            override fun compareTo(other: OrderKey) = compareValuesBy(this, other, OrderKey::page, OrderKey::block, OrderKey::position)
        }

        private sealed interface Item {
            val key: OrderKey

            data class Text(val sentence: SentenceEntity, val block: PageLayoutBlockEntity?, override val key: OrderKey) : Item {
                val role: String get() = sentence.source.substringAfter(':', SemanticTextRole.OTHER)
            }

            data class Visual(val block: PageLayoutBlockEntity, override val key: OrderKey) : Item
        }

        private class OpenParagraph(val role: String, val sentences: MutableList<SentenceEntity>, var block: PageLayoutBlockEntity?)

        /**
         * The text block holding the sentence's first line fragment: the smallest block containing
         * its center, else the nearest one. Inline-formula and equation-number boxes are spans
         * inside text, not paragraphs, and are skipped.
         */
        private fun List<PageLayoutBlockEntity>.firstBlockOf(sentence: SentenceEntity): PageLayoutBlockEntity? {
            val first = sentence.decodedRegions().firstOrNull() ?: return null
            val x = (first.left + first.right) / 2f
            val y = (first.top + first.bottom) / 2f
            val textBlocks = filter { it.type !in VISUAL_TYPES && !(it.type == LayoutBlockType.EQUATION && it.text.isEmpty()) }
            return textBlocks.filter { x in it.left..it.right && y in it.top..it.bottom }
                .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                ?: textBlocks.minByOrNull { block ->
                    val dx = maxOf(block.left - x, 0f, x - block.right)
                    val dy = maxOf(block.top - y, 0f, y - block.bottom)
                    dx * dx + dy * dy
                }
        }

        private fun SentenceEntity.touches(block: PageLayoutBlockEntity): Boolean =
            decodedRegions(block.pageNumber).any { region ->
                val x = (region.left + region.right) / 2f
                val y = (region.top + region.bottom) / 2f
                x in block.left..block.right && y in block.top..block.bottom
            }

        /** An equation number sits on the equation's row, just right of it. */
        private fun numberOf(equation: PageLayoutBlockEntity, pageBlocks: List<PageLayoutBlockEntity>): PageLayoutBlockEntity? =
            pageBlocks.firstOrNull { candidate ->
                val centerY = (candidate.top + candidate.bottom) / 2f
                candidate.type == LayoutBlockType.EQUATION && candidate.text.isEmpty() &&
                    candidate.left >= equation.right - EDGE && candidate.left - equation.right <= NUMBER_GAP &&
                    centerY in equation.top..equation.bottom
            }

        /**
         * Body font size in PDF points, from the median height of body line fragments (a glyph
         * box is about 1.15 em tall).
         */
        private fun bodyFontPoints(sentences: List<SentenceEntity>, pageSizes: Map<Int, Pair<Float, Float>>): Float {
            val heights = sentences.asSequence()
                .filter { it.source.endsWith(":${SemanticTextRole.BODY}") }
                .flatMap { sentence ->
                    sentence.regions.split('|').asSequence().mapNotNull { encoded ->
                        val values = encoded.split(',').mapNotNull(String::toFloatOrNull)
                        if (values.size != 5) return@mapNotNull null
                        (values[4] - values[2]) * (pageSizes[values[0].toInt()] ?: LETTER).second
                    }
                }
                .filter { it > 0f }
                .sorted()
                .toList()
            val median = heights.getOrNull(heights.size / 2) ?: return DEFAULT_FONT_POINTS
            return (median / GLYPH_BOX_EM).coerceIn(7f, 14f)
        }

        private val VISUAL_TYPES = setOf(LayoutBlockType.IMAGE, LayoutBlockType.CHART, LayoutBlockType.TABLE)

        /** Page furniture and notes: never part of the reading flow. */
        private val OUTSIDE_FLOW_ROLES = setOf(
            SemanticTextRole.FOOTNOTE, SemanticTextRole.HEADER, SemanticTextRole.FOOTER, SemanticTextRole.SIDEBAR,
        )
        private const val EDGE = .004f
        private const val NUMBER_GAP = .3f
        private const val GLYPH_BOX_EM = 1.15f
        private const val DEFAULT_FONT_POINTS = 10f
        private val LETTER = 612f to 792f
    }
}
