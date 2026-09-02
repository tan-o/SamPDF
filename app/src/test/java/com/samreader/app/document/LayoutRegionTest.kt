package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import com.samreader.app.data.EvidenceKind
import com.samreader.app.data.ParsingTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRegionTest {
    @Test
    fun v3SemanticClassesMapWithoutLegacyLabelTranslation() {
        assertEquals(LayoutBlockType.PARAGRAPH, region(22).blockType)
        assertEquals(LayoutBlockType.EQUATION, region(5).blockType)
        assertEquals(LayoutBlockType.CONTENTS, region(4).blockType)
        assertEquals(LayoutBlockType.CAPTION, region(7).blockType)
        assertEquals(LayoutBlockType.CHART, region(3).blockType)
        assertEquals(LayoutBlockType.REFERENCE, region(19).blockType)
    }

    @Test
    fun standaloneFormulaIsEncodedByFormulaPipelineInsteadOfTakingOcrLine() {
        val formula = region(5)

        assertFalse(formula.requiresOcr)
        assertTrue(formula.selectableBody)
    }

    @Test
    fun inlineFormulaAndFormulaNumberCannotBecomeStandaloneBody() {
        assertFalse(region(15).selectableBody)
        assertFalse(region(11).selectableBody)
    }

    @Test
    fun overlappingBoxesUseInstanceMasksForExclusiveOwnership() {
        val body = region(22, mask = horizontalMask(.10f, .40f))
        val caption = region(7, mask = horizontalMask(.42f, .55f))
        val line = PositionedLine("Figure 1. Result.", .12f, .44f, .88f, .48f, .95f)

        val blocks = assignLinesToRegions(listOf(body, caption), listOf(line))

        assertEquals(1, blocks.sumOf { it.lines.size })
        assertTrue(blocks.first { it.type == LayoutBlockType.PARAGRAPH }.lines.isEmpty())
        assertEquals(listOf(line), blocks.first { it.type == LayoutBlockType.CAPTION }.lines)
    }

    @Test
    fun visualOnlyMaskCannotTakeOwnershipOfOcrText() {
        val image = region(14, mask = horizontalMask(.1f, .9f))
        val paragraph = region(22, mask = horizontalMask(.6f, .9f))
        val line = PositionedLine("Readable body line", .12f, .65f, .88f, .7f, .95f)

        val blocks = assignLinesToRegions(listOf(image, paragraph), listOf(line))

        assertTrue(blocks.first { it.type == LayoutBlockType.IMAGE }.lines.isEmpty())
        assertEquals(listOf(line), blocks.first { it.type == LayoutBlockType.PARAGRAPH }.lines)
    }

    @Test
    fun canonicalOcrKeepsTheRecognizerSourceOrder() {
        val layout = listOf(region(22))
        val sourceOrder = listOf(
            positionedLine("Thus the nature of the acceleration", .20f, .25f),
            positionedLine("at a point inside the box", .24f, .29f),
            positionedLine("can be established by exploring it.", .28f, .33f),
        )

        val resolved = resolveCanonicalText(layout, emptyList(), sourceOrder)

        assertEquals(CanonicalBlockSource.VISUAL_OCR, resolved.sources.single())
        assertEquals(sourceOrder, resolved.blocks.single().lines)
    }

    @Test
    fun standaloneFormulaNumberCannotLeakIntoBodyText() {
        val body = region(22, mask = horizontalMask(.1f, .9f))
        val formulaNumber = region(11, mask = horizontalMask(.40f, .50f))
        val number = positionedLine("(4)", .44f, .48f)

        val canonical = resolveCanonicalText(listOf(body, formulaNumber), listOf(number), emptyList())
        val blocks = assembleTypedSpans(canonical, listOf(body, formulaNumber), emptyMap())

        assertTrue(blocks.all { it.lines.isEmpty() })
    }

    @Test
    fun formulaPixelsHaveOneHighestConfidenceSemanticOwner() {
        val body = region(22, score = .70f, mask = horizontalMask(.40f, .60f))
        val caption = region(7, score = .95f, mask = horizontalMask(.40f, .60f))
        val formula = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .9f, .2f, .45f, .3f, .5f),
            "\\[D_2\\]", .9f, "test", byteArrayOf(),
        )

        val owners = assignFormulasToRegions(listOf(body, caption), listOf(formula))

        assertEquals(emptyList<RecognizedFormula>(), owners[0].orEmpty())
        assertEquals(listOf(formula), owners[1])
        assertEquals(1, owners.values.sumOf(List<RecognizedFormula>::size))
    }

    @Test
    fun autoregressiveFormulaMustReachEndTokenBeforeItCanEnterTheTextLayer() {
        assertFalse(isCompleteFormulaRecognition(false, "x=x+x=x"))
        assertTrue(isCompleteFormulaRecognition(true, "x=y"))
    }

    @Test
    fun failedFormulaDecodeStillKeepsItsDetectorBoxForDebugging() {
        val region = FormulaRegion(FormulaRegionType.INLINE, .77f, .2f, .3f, .4f, .35f)
        val evidence = ParseEvidenceBuilder("doc", 0, 100, 100).apply {
            addVisual(emptyList(), emptyList(), listOf(region), emptyList())
        }.build()

        assertEquals(1, evidence.size)
        assertEquals(EvidenceKind.FORMULA_REGION, evidence.single().kind)
        assertEquals(.77f, evidence.single().confidence)
    }

    @Test
    fun documentLayoutConfidenceHasOneExplicitSupportedRange() {
        assertEquals(.20f, ParsingTuning.normalizeLayoutConfidence(.05f))
        assertEquals(.90f, ParsingTuning.normalizeLayoutConfidence(1.2f))
        assertEquals(.50f, ParsingTuning.normalizeLayoutConfidence(null))
    }

    @Test
    fun readablePdfTextRemainsCanonicalEvenWhenVisualOcrDisagrees() {
        val layout = listOf(region(22))
        val ocr = listOf(PositionedLine("Completely wrong visual text.", .2f, .3f, .8f, .35f, .8f))
        val native = listOf(
            PositionedLine("The method is stable.", .2f, .3f, .8f, .35f, 1f),
        )

        val resolved = resolveCanonicalText(layout, native, ocr)

        assertEquals(CanonicalBlockSource.NATIVE_PDF, resolved.sources.single())
        assertEquals("The method is stable.", resolved.blocks.single().lines.single().text)
    }

    @Test
    fun unreadableNativeEncodingSelectsVisualOcrAsCanonicalSource() {
        val layout = listOf(region(22))
        val ocr = listOf(
            PositionedLine("Readable scientific sentence.", .2f, .3f, .8f, .35f, .9f),
        )
        val corruptNative = listOf(
            PositionedLine("������", .2f, .3f, .8f, .35f, 1f),
        )

        val resolved = resolveCanonicalText(layout, corruptNative, ocr)

        assertEquals(CanonicalBlockSource.VISUAL_OCR, resolved.sources.single())
        assertEquals("Readable scientific sentence.", resolved.blocks.single().lines.single().text)
    }

    @Test
    fun visualFormulaCanNeverRewriteReadablePdfSourceText() {
        val layout = listOf(region(22))
        val original = "That is, we start from x = u and y = v."
        val native = positionedLine(original, .30f, .35f)
        val resolved = resolveCanonicalText(layout, listOf(native), emptyList())
        val falsePositive = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .99f, .10f, .30f, .80f, .35f),
            "\\[x = u\\]", .99f, "test", byteArrayOf(),
        )

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(falsePositive)))

        assertTrue(result.single().lines.any { it.text == original })
    }

    @Test
    fun confirmedFormulaOwnsItsPdfFragmentsAndIsInsertedOnceAsLatex() {
        val layout = listOf(region(22))
        val prose = positionedLine("using the equation", .30f, .35f)
        val formulaFragments = listOf("p", "(", "t", "n", "+1", ") =").map {
            positionedLine(it, .36f, .40f)
        }
        val formula = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .90f, .10f, .36f, .90f, .40f),
            "\\[p(t_{n+1})=p(t_n)\\]", .90f, "test", byteArrayOf(),
        )
        val resolved = resolveCanonicalText(
            layout,
            listOf(prose) + formulaFragments,
            emptyList(),
        )

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(formula)))

        assertEquals(listOf("using the equation", formula.latex), result.single().lines.map { it.text })
    }

    @Test
    fun formulaOwnershipDoesNotDependOnMathCharactersOrTokenCount() {
        val layout = listOf(region(22))
        val nativeFragments = listOf(
            positionedLine("arbitrary", .36f, .40f),
            positionedLine("source words", .41f, .45f),
        )
        val formula = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .90f, .10f, .35f, .90f, .46f),
            "\\[f(x)\\]", .90f, "test", byteArrayOf(),
        )
        val resolved = resolveCanonicalText(layout, nativeFragments, emptyList())

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(formula)))

        assertEquals(listOf(formula.latex), result.single().lines.map(PositionedLine::text))
    }

    @Test
    fun partialFormulaOverlapPreservesExactPdfLineWithoutDuplicateLatex() {
        val layout = listOf(region(22))
        val original = positionedLine("The mapping uses x and y coordinates.", .30f, .35f)
        val formula = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .95f, .28f, .30f, .38f, .35f),
            "\\[x\\]", .95f, "test", byteArrayOf(),
        )
        val resolved = resolveCanonicalText(layout, listOf(original), emptyList())

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(formula)))

        assertEquals(listOf(original.text), result.single().lines.map(PositionedLine::text))
    }

    @Test
    fun inlineFormulaReplacesOnlyItsOwnedGlyphsInsideAnOcrLine() {
        val layout = listOf(region(22))
        val ocr = positionedLine("using x, where n is the step", .30f, .35f)
        val formula = RecognizedFormula(
            FormulaRegion(FormulaRegionType.INLINE, .95f, .195f, .29f, .225f, .36f),
            "\\[x_{n+1}\\]", .95f, "test", byteArrayOf(),
        )
        val resolved = resolveCanonicalText(layout, emptyList(), listOf(ocr))

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(formula)))

        assertEquals(
            listOf("using", formula.latex, ", where n is the step"),
            result.single().lines.map(PositionedLine::text),
        )
        assertTrue(result.single().lines.first().right < result.single().lines.last().right)
    }

    @Test
    fun actualPdfFormulaFragmentsBecomeOneSpanAtTheCorrectPlaceOnTheVisualLine() {
        val layout = listOf(region(22))
        val pageWidth = 612f
        val pageHeight = 792f
        val native = listOf(
            sourceCell("using the equation", 316.7f, 382.2f, 383.1f, 390.4f, pageWidth, pageHeight),
            sourceCell("p", 385.6f, 381.8f, 391.5f, 390.7f, pageWidth, pageHeight),
            sourceCell("(", 391.5f, 381.8f, 395.1f, 390.7f, pageWidth, pageHeight),
            sourceCell("t", 395.1f, 382.2f, 398.4f, 388.5f, pageWidth, pageHeight),
            sourceCell("n", 398.4f, 385.3f, 403.0f, 391.3f, pageWidth, pageHeight),
            sourceCell("+1", 403.0f, 385.3f, 412.3f, 391.3f, pageWidth, pageHeight),
            sourceCell(") =", 412.7f, 381.7f, 426.5f, 390.7f, pageWidth, pageHeight),
            sourceCell("p", 429.6f, 381.7f, 435.5f, 390.7f, pageWidth, pageHeight),
            sourceCell("(", 435.5f, 381.7f, 439.1f, 390.7f, pageWidth, pageHeight),
            sourceCell("t", 439.1f, 382.2f, 442.4f, 388.5f, pageWidth, pageHeight),
            sourceCell("n", 442.4f, 385.3f, 447.0f, 391.3f, pageWidth, pageHeight),
            sourceCell(") +", 447.5f, 381.7f, 460.4f, 390.7f, pageWidth, pageHeight),
            sourceCell("∇", 462.7f, 381.7f, 470.1f, 390.7f, pageWidth, pageHeight),
            sourceCell("φ", 470.1f, 382.2f, 475.6f, 388.5f, pageWidth, pageHeight),
            sourceCell("Δ", 475.6f, 381.7f, 483.3f, 390.7f, pageWidth, pageHeight),
            sourceCell("t", 483.3f, 382.2f, 486.6f, 388.5f, pageWidth, pageHeight),
            sourceCell(", where", 486.6f, 382.2f, 513.3f, 390.4f, pageWidth, pageHeight),
            sourceCell("n", 515.9f, 382.2f, 521.4f, 388.5f, pageWidth, pageHeight),
            sourceCell("indicates", 524.0f, 382.2f, 555.8f, 390.4f, pageWidth, pageHeight),
        )
        val formula = RecognizedFormula(
            FormulaRegion(
                FormulaRegionType.INLINE,
                .796f,
                1131.2f / 1800f,
                1118.3f / 2329f,
                1431.1f / 1800f,
                1149.7f / 2329f,
            ),
            "\\[\\mathbf{p}(t_{n+1})=\\mathbf{p}(t_n)+\\nabla\\phi\\Delta t\\]",
            .928f,
            "test",
            byteArrayOf(),
        )
        val resolved = resolveCanonicalText(layout, native, emptyList())

        val result = assembleTypedSpans(resolved, layout, mapOf(0 to listOf(formula)))

        assertEquals(
            listOf("using the equation", formula.latex, ", where", "n", "indicates"),
            result.single().lines.map(PositionedLine::text),
        )
    }


    private fun region(
        classId: Int,
        score: Float = .9f,
        mask: ByteArray = horizontalMask(.1f, .9f),
    ) = LayoutRegion(classId, score, .1f, .1f, .9f, .9f, classId, mask)

    private fun positionedLine(text: String, top: Float, bottom: Float): PositionedLine {
        val glyphs = text.filterNot(Char::isWhitespace).mapIndexed { index, character ->
            PositionedGlyph(
                character.toString(),
                .1f + index * .02f,
                top,
                .115f + index * .02f,
                bottom,
                .9f,
            )
        }
        return PositionedLine(text, .1f, top, .9f, bottom, .9f, glyphs)
    }

    private fun sourceCell(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): PositionedLine {
        val characters = text.filterNot(Char::isWhitespace)
        val glyphs = characters.mapIndexed { index, character ->
            PositionedGlyph(
                character.toString(),
                (left + (right - left) * index / characters.length.coerceAtLeast(1)) / pageWidth,
                top / pageHeight,
                (left + (right - left) * (index + 1) / characters.length.coerceAtLeast(1)) / pageWidth,
                bottom / pageHeight,
                1f,
            )
        }
        return PositionedLine(
            text,
            left / pageWidth,
            top / pageHeight,
            right / pageWidth,
            bottom / pageHeight,
            1f,
            glyphs,
        )
    }

    companion object {
        private fun horizontalMask(top: Float, bottom: Float) =
            ByteArray(LayoutRegion.MASK_SIZE * LayoutRegion.MASK_SIZE).also { mask ->
                val y0 = (top * LayoutRegion.MASK_SIZE).toInt()
                val y1 = (bottom * LayoutRegion.MASK_SIZE).toInt()
                for (y in y0 until y1) for (x in 0 until LayoutRegion.MASK_SIZE) {
                    mask[y * LayoutRegion.MASK_SIZE + x] = 1
                }
            }
    }
}
