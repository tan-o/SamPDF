package com.samreader.app.document

import com.samreader.app.data.EvidenceChannel
import com.samreader.app.data.EvidenceKind
import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.PageEvidenceEntity
import java.util.UUID

/** Converts every observation available during indexing into normalized SQLite rows. */
internal class ParseEvidenceBuilder(
    private val documentId: String,
    private val pageNumber: Int,
    pageWidth: Int,
    pageHeight: Int,
) {
    private val rows = mutableListOf<PageEvidenceEntity>()
    private val nextPosition = mutableMapOf<String, Int>()

    fun addRustPage(page: RustPdfTextPage?) {
        if (page == null || page.width <= 0f || page.height <= 0f) return
        page.lines.forEach { cell -> addRustCell(cell, page, EvidenceKind.TEXT_REGION) }
        page.words.forEach { cell -> addRustCell(cell, page, EvidenceKind.WORD) }
    }

    fun addVisual(
        regions: List<LayoutRegion>,
        ocrBlocks: List<PositionedBlock>,
        formulaRegions: List<FormulaRegion>,
        formulas: List<RecognizedFormula>,
    ) {
        regions.zip(ocrBlocks).forEach { (region, block) ->
            val blockId = add(
                channel = EvidenceChannel.VISUAL_LAYOUT,
                kind = EvidenceKind.LAYOUT_BLOCK,
                blockType = region.blockType,
                text = region.label,
                rect = NormalizedBox(region.left, region.top, region.right, region.bottom),
                confidence = region.score,
                modelId = DocumentLayoutModel.MODEL_ID,
            )
            block.lines.forEach { line ->
                val lineId = add(
                    channel = EvidenceChannel.VISUAL_OCR,
                    kind = EvidenceKind.OCR_LINE,
                    parentId = blockId,
                    blockType = region.blockType,
                    text = line.text,
                    rect = line.rect(),
                    confidence = line.confidence,
                    modelId = "mlkit-latin-16.0.1",
                )
                line.glyphs.forEach { glyph ->
                    add(
                        channel = EvidenceChannel.VISUAL_OCR,
                        kind = EvidenceKind.OCR_GLYPH,
                        parentId = lineId,
                        blockType = region.blockType,
                        text = glyph.text,
                        rect = glyph.rect(),
                        confidence = glyph.confidence,
                        modelId = "mlkit-latin-16.0.1",
                    )
                }
            }
        }

        val detectionIds = formulaRegions.associateWith { region ->
            add(
                channel = EvidenceChannel.VISUAL_FORMULA,
                kind = EvidenceKind.FORMULA_REGION,
                blockType = LayoutBlockType.EQUATION,
                text = region.type,
                rect = NormalizedBox(region.left, region.top, region.right, region.bottom),
                confidence = region.confidence,
                modelId = DocumentLayoutModel.MODEL_ID,
            )
        }
        formulas.forEach { formula ->
            val region = formula.region
            add(
                channel = EvidenceChannel.VISUAL_FORMULA,
                kind = EvidenceKind.FORMULA_LATEX,
                parentId = detectionIds[region],
                blockType = LayoutBlockType.EQUATION,
                text = formula.latex,
                rect = NormalizedBox(region.left, region.top, region.right, region.bottom),
                confidence = formula.confidence,
                modelId = formula.modelId,
                imagePng = formula.imagePng,
            )
        }
    }

    fun build(): List<PageEvidenceEntity> = rows.toList()

    private fun addRustCell(cell: RustPdfTextCell, page: RustPdfTextPage, kind: String) {
        add(
            channel = EvidenceChannel.PDF_RUST,
            kind = kind,
            text = cell.text,
            rect = NormalizedBox(
                cell.left / page.width,
                cell.top / page.height,
                cell.right / page.width,
                cell.bottom / page.height,
            ),
            confidence = 1f,
            modelId = "docling-pdf-1.15.0",
        )
    }

    private fun add(
        channel: String,
        kind: String,
        text: String,
        rect: NormalizedBox,
        confidence: Float,
        parentId: String? = null,
        blockType: String? = null,
        modelId: String? = null,
        imagePng: ByteArray? = null,
    ): String {
        val position = nextPosition.getOrDefault(channel, 0)
        nextPosition[channel] = position + 1
        val id = UUID.nameUUIDFromBytes(
            "$documentId:$pageNumber:$channel:$position".toByteArray(),
        ).toString()
        rows += PageEvidenceEntity(
            id = id,
            documentId = documentId,
            pageNumber = pageNumber,
            channel = channel,
            kind = kind,
            position = position,
            parentId = parentId,
            blockType = blockType,
            modelId = modelId,
            text = text,
            left = rect.left.coerceIn(0f, 1f),
            top = rect.top.coerceIn(0f, 1f),
            right = rect.right.coerceIn(0f, 1f),
            bottom = rect.bottom.coerceIn(0f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
            imagePng = imagePng,
        )
        return id
    }

    private fun PositionedLine.rect() = NormalizedBox(left, top, right, bottom)
    private fun PositionedGlyph.rect() = NormalizedBox(left, top, right, bottom)
}

private data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
