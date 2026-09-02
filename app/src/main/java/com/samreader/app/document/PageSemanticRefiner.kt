package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType

/** Adds document-level roles that PP-DocLayoutV3 does not expose as a native class. */
object PageSemanticRefiner {
    fun refine(
        blocks: List<PositionedBlock>,
        pageNumber: Int = 0,
    ): List<PositionedBlock> {
        val ordered = if (blocks.all { it.readingOrder != Int.MAX_VALUE }) {
            blocks.sortedBy(PositionedBlock::readingOrder)
        } else {
            blocks
        }
        return classifyContents(classifyFrontMatter(ordered, pageNumber))
    }

    /**
     * PP-DocLayout can correctly find a CONTENTS heading while classifying its entry list as a
     * generic paragraph. Promote only paragraphs in the same visual column, bounded by the next
     * section heading. This is document-role inference; sentence punctuation is not involved.
     */
    private fun classifyContents(blocks: List<PositionedBlock>): List<PositionedBlock> {
        val contentsHeadings = blocks.filter { block ->
            block.type == LayoutBlockType.SECTION_TITLE && block.isContentsHeading()
        }
        if (contentsHeadings.isEmpty()) return blocks
        return blocks.map { block ->
            if (block.type != LayoutBlockType.PARAGRAPH) return@map block
            val heading = contentsHeadings.lastOrNull { candidate ->
                candidate.precedes(block) && candidate.overlapsColumn(block)
            } ?: return@map block
            val nextHeading = blocks.firstOrNull { candidate ->
                candidate.type == LayoutBlockType.SECTION_TITLE &&
                    heading.precedes(candidate) &&
                    heading.overlapsColumn(candidate)
            }
            if (nextHeading == null || block.precedes(nextHeading)) {
                block.copy(type = LayoutBlockType.CONTENTS)
            } else {
                block
            }
        }
    }

    private fun PositionedBlock.isContentsHeading(): Boolean {
        val normalized = lines.joinToString(" ", transform = PositionedLine::text)
            .trim().replace(Regex("\\s+"), " ").uppercase()
        return normalized == "CONTENTS" || normalized == "TABLE OF CONTENTS"
    }

    private fun PositionedBlock.precedes(other: PositionedBlock): Boolean =
        if (readingOrder != Int.MAX_VALUE && other.readingOrder != Int.MAX_VALUE) {
            readingOrder < other.readingOrder
        } else {
            top < other.top
        }

    private fun PositionedBlock.overlapsColumn(other: PositionedBlock): Boolean {
        val overlap = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val narrowerWidth = minOf(right - left, other.right - other.left).coerceAtLeast(.001f)
        return overlap / narrowerWidth >= .5f
    }

    private fun classifyFrontMatter(
        blocks: List<PositionedBlock>,
        pageNumber: Int,
    ): List<PositionedBlock> {
        if (pageNumber != 0) return blocks
        val titleOrder = blocks.filter { it.type == LayoutBlockType.DOCUMENT_TITLE }
            .minOfOrNull(PositionedBlock::readingOrder) ?: return blocks
        val abstractOrder = blocks.filter {
            it.type == LayoutBlockType.ABSTRACT && it.readingOrder > titleOrder
        }.minOfOrNull(PositionedBlock::readingOrder) ?: return blocks
        return blocks.map { block ->
            if (block.readingOrder < titleOrder && block.type == LayoutBlockType.PARAGRAPH) {
                block.copy(type = LayoutBlockType.HEADER)
            } else if (
                block.readingOrder in (titleOrder + 1) until abstractOrder &&
                block.type == LayoutBlockType.PARAGRAPH
            ) {
                block.copy(type = LayoutBlockType.AUTHOR)
            } else {
                block
            }
        }
    }
}
