package com.samreader.app.document

import com.samreader.app.data.LayoutBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceAssemblerTest {
    @Test
    fun joinsHyphenatedLineAndSplitsSentences() {
        val result = SentenceAssembler.assemble(
            listOf(
                line("The proposed mem-", 0.1f),
                line("ory system works. It is fast.", 0.2f),
            ),
        )

        assertEquals(listOf("The proposed memory system works.", "It is fast."), result.map { it.text })
        assertEquals(2, result.first().regions.size)
        assertTrue(result.first().regions[0].top < result.first().regions[1].top)
    }

    @Test
    fun readsLeftColumnBeforeRightColumn() {
        val result = SentenceAssembler.assemble(
            listOf(
                line("Right one.", 0.1f, left = 0.55f, right = 0.9f),
                line("Left one.", 0.1f, left = 0.1f, right = 0.45f),
                line("Right two.", 0.2f, left = 0.55f, right = 0.9f),
                line("Left two.", 0.2f, left = 0.1f, right = 0.45f),
                line("Right three.", 0.3f, left = 0.55f, right = 0.9f),
                line("Left three.", 0.3f, left = 0.1f, right = 0.45f),
            ),
        )

        assertEquals(
            listOf("Left one.", "Left two.", "Left three.", "Right one.", "Right two.", "Right three."),
            result.map { it.text },
        )
    }

    @Test
    fun keepsLongSentenceTogetherUntilPeriod() {
        val lines = (0 until 8).map { index ->
            line(if (index == 7) "final words." else "continuation words", .05f + index * .06f)
        }
        val result = SentenceAssembler.assemble(lines)
        assertEquals(1, result.size)
        assertEquals(8, result.single().regions.size)
        assertTrue(result.single().text.endsWith("final words."))
    }

    @Test
    fun preservesOcrLineOrderWhenNeighboringRowsOverlapVertically() {
        val block = PositionedBlock(
            lines = listOf(
                line("Thus the nature of the acceleration", .10f, left = .31f, right = .90f),
                line("at a point inside the box", .14f, left = .10f, right = .80f),
                line("can be established by exploring it.", .18f, left = .22f, right = .88f),
            ),
            left = .10f,
            top = .10f,
            right = .90f,
            bottom = .23f,
            readingOrder = 0,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(block))

        assertEquals(
            "Thus the nature of the acceleration at a point inside the box can be established by exploring it.",
            result.single().text,
        )
    }

    @Test
    fun contentsRemainIndependentEntriesAndBypassTheSentenceModel() {
        var scorerCalls = 0
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String): FloatArray {
                scorerCalls++
                return FloatArray(text.length) { 1f }
            }
        }
        val contents = PositionedBlock(
            lines = listOf(
                line("I. Absolute Motion versus Relative Motion ........ 475", .10f),
                line("II. Sagnac-Type Experimentation ................. 476", .16f),
            ),
            left = .10f,
            top = .10f,
            right = .90f,
            bottom = .21f,
            type = LayoutBlockType.CONTENTS,
            readingOrder = 0,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(contents), scorer)

        assertEquals(contents.lines.map(PositionedLine::text), result.map(PositionedSentence::text))
        assertTrue(result.all { it.semanticRole == SemanticTextRole.CONTENTS && !it.canContinueFromPrevious })
        assertEquals(0, scorerCalls)
    }

    @Test
    fun followsReadingOrderAcrossColumns() {
        val left = PositionedBlock(
            listOf(line("Left column continues", .1f, .08f, .44f), line("and ends here.", .16f, .08f, .44f)),
            .08f, .1f, .44f, .21f,
        )
        val right = PositionedBlock(
            listOf(line("Right column starts here.", .1f, .56f, .92f), line("Another sentence.", .16f, .56f, .92f)),
            .56f, .1f, .92f, .21f,
        )
        val left2 = left.copy(lines = listOf(line("Second left block.", .3f, .08f, .44f)), top=.3f, bottom=.35f)
        val right2 = right.copy(lines = listOf(line("Second right block.", .3f, .56f, .92f)), top=.3f, bottom=.35f)

        val result = SentenceAssembler.assembleBlocks(listOf(right, left2, right2, left))

        assertEquals("Left column continues and ends here.", result.first().text)
        assertTrue(result.indexOfFirst { it.text == "Second left block." } < result.indexOfFirst { it.text == "Right column starts here." })
    }

    @Test
    fun joinsSentenceFromLeftColumnBottomToRightColumnTop() {
        val left = PositionedBlock(
            listOf(line("A sentence starts in the left", .8f, .08f, .44f)),
            .08f, .8f, .44f, .86f,
        )
        val right = PositionedBlock(
            listOf(line("column and finishes here.", .1f, .56f, .92f)),
            .56f, .1f, .92f, .16f,
        )
        val leftEarlier = left.copy(
            lines = listOf(line("Earlier sentence.", .1f, .08f, .44f)), top = .1f, bottom = .16f,
        )
        val rightLater = right.copy(
            lines = listOf(line("Later sentence.", .3f, .56f, .92f)), top = .3f, bottom = .36f,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(right, left, rightLater, leftEarlier))

        assertEquals(
            "A sentence starts in the left column and finishes here.",
            result.first { it.text.startsWith("A sentence") }.text,
        )
    }

    @Test
    fun joinsSentenceAcrossPageBoundaryAndKeepsBothPageRegions() {
        val firstPage = SentenceAssembler.assemble(listOf(line("A sentence crosses the", .8f)))
        val secondPage = SentenceAssembler.assemble(listOf(line("page and ends here.", .1f)))

        val result = SentenceAssembler.mergePages(listOf(0 to firstPage, 1 to secondPage))

        assertEquals(1, result.size)
        assertEquals("A sentence crosses the page and ends here.", result.single().text)
        assertEquals(setOf(0, 1), result.single().regions.map { it.pageNumber }.toSet())
    }

    @Test
    fun continuationAfterDisplayFormulaStaysOnANewLineAcrossPages() {
        val firstPage = listOf(
            PositionedSentence(
                text = "The relation\n\\[E=mc^2\\]",
                regions = listOf(com.samreader.app.data.NormalizedRect(.1f, .8f, .9f, .9f)),
                confidence = 1f,
                terminated = false,
            ),
        )
        val secondPage = SentenceAssembler.assemble(listOf(line("is useful in this case.", .1f)))

        val result = SentenceAssembler.mergePages(listOf(0 to firstPage, 1 to secondPage))

        assertEquals("The relation\n\\[E=mc^2\\]\nis useful in this case.", result.single().text)
    }

    @Test
    fun incrementalPublishWithholdsOnlyTheOpenTrailingSentence() {
        val page = SentenceAssembler.assemble(listOf(
            line("The first sentence is ready. The second sentence continues", .1f),
        ))

        val published = SentenceAssembler.mergePages(
            listOf(0 to page),
            includeTrailingIncomplete = false,
        )

        assertEquals(listOf("The first sentence is ready."), published.map { it.text })
    }

    @Test
    fun doesNotSplitScientificAbbreviationsOrAuthorInitials() {
        val result = SentenceAssembler.assemble(
            listOf(
                line("As shown in Fig.", .1f),
                line("2, the method by J.", .2f),
                line("Smith is stable. A new result follows.", .3f),
            ),
        )

        assertEquals(
            listOf("As shown in Fig. 2, the method by J. Smith is stable.", "A new result follows."),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun keepsDecimalTogetherWhenOcrSplitsItAcrossLines() {
        val result = SentenceAssembler.assemble(
            listOf(line("The measured value is 3.", .1f), line("14 units. Next sentence.", .2f)),
        )

        assertEquals(listOf("The measured value is 3.14 units.", "Next sentence."), result.map { it.text })
    }

    @Test
    fun versionNumberInsideParenthesesIsNotAFalseBoundary() {
        val result = SentenceAssembler.assemble(listOf(
            line("This is people (only paper OC 173.4.2 said that). The next sentence.", .1f),
        ))

        assertEquals(
            listOf("This is people (only paper OC 173.4.2 said that).", "The next sentence."),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun versionNumberSplitByOcrLineIsNotAFalseBoundary() {
        val result = SentenceAssembler.assemble(listOf(
            line("This is people (only paper OC 173.", .1f),
            line("4.2 said that). The next sentence.", .16f),
        ))

        assertEquals(
            listOf("This is people (only paper OC 173.4.2 said that).", "The next sentence."),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun captionCannotConsumeAnIncompleteSentenceFromPreviousPage() {
        val previous = SentenceAssembler.assembleBlocks(listOf(
            PositionedBlock(listOf(line("The body continues on the", .8f)), .1f, .8f, .9f, .86f),
        ))
        val caption = SentenceAssembler.assembleBlocks(listOf(
            PositionedBlock(
                listOf(line("Figure 2. Model overview.", .1f)), .1f, .1f, .9f, .16f,
                isCaption = true, selectableBody = true, type = LayoutBlockType.CAPTION,
            ),
        ))

        val result = SentenceAssembler.mergePages(listOf(0 to previous, 1 to caption))

        assertEquals("The body continues on the", result.first().text)
        assertTrue(result.none { "the Figure" in it.text })
    }

    @Test
    fun crossPageBodySkipsLeadingFigureCaptionBeforeFindingContinuation() {
        val previous = SentenceAssembler.assembleBlocks(listOf(
            PositionedBlock(
                listOf(line("Linear accelerations can be measured by a differential frequency shift of loaded vibrating", .84f)),
                .1f, .84f, .9f, .89f,
            ),
        ))
        val caption = PositionedBlock(
            listOf(line("FIG. 1. Schematic of Sagnac's interferometer.", .42f, .12f, .48f, height = .025f)),
            .12f, .42f, .48f, .445f,
            isCaption = true, selectableBody = true, type = LayoutBlockType.CAPTION,
        )
        val continuation = PositionedBlock(
            listOf(line("strings. The gyroscope responds to rotation.", .54f, .1f, .48f)),
            .1f, .54f, .48f, .59f,
        )
        val current = SentenceAssembler.assembleBlocks(listOf(caption, continuation))

        val result = SentenceAssembler.mergePages(listOf(0 to previous, 1 to current))

        assertEquals(
            "Linear accelerations can be measured by a differential frequency shift of loaded vibrating strings.",
            result.first().text,
        )
        assertTrue(result.none { "vibrating FIG" in it.text })
        assertTrue(result.any { it.text == "FIG. 1." })
        assertTrue(result.any { it.text == "The gyroscope responds to rotation." })
        assertEquals(setOf(0, 1), result.first().regions.map(PageSentenceRegion::pageNumber).toSet())
    }

    @Test
    fun hierarchicalNumberCanContinueAfterLeadingCaptionOnNextPage() {
        val previous = SentenceAssembler.assemble(listOf(line("This is people (only paper OC 173.", .84f)))
        val caption = SentenceAssembler.assembleBlocks(listOf(
            PositionedBlock(
                listOf(line("FIG. 1. Model overview.", .2f)), .1f, .2f, .9f, .25f,
                isCaption = true, selectableBody = true, type = LayoutBlockType.CAPTION,
            ),
            PositionedBlock(listOf(line("4.2 said that).", .6f)), .1f, .6f, .9f, .65f),
        ))

        val result = SentenceAssembler.mergePages(listOf(0 to previous, 1 to caption))

        assertEquals("This is people (only paper OC 173.4.2 said that).", result.first().text)
        assertTrue(result.none { "173. FIG" in it.text })
    }

    @Test
    fun abbreviationAtPageEndUsesTheNextPageBeforeDeciding() {
        val firstPage = SentenceAssembler.assemble(listOf(line("The result is shown in Fig.", .8f)))
        val secondPage = SentenceAssembler.assemble(listOf(line("3 and remains valid. Next sentence.", .1f)))

        val result = SentenceAssembler.mergePages(listOf(0 to firstPage, 1 to secondPage))

        assertEquals(
            listOf("The result is shown in Fig. 3 and remains valid.", "Next sentence."),
            result.map { it.text },
        )
        assertEquals(setOf(0, 1), result.first().regions.map(PageSentenceRegion::pageNumber).toSet())
    }

    @Test
    fun ordinaryPeriodStillEndsTheSentence() {
        assertTrue(SentenceAssembler.isScientificSentenceBoundary("The method converged.", "The next test"))
    }

    @Test
    fun citationBracketsRemainOpenAcrossOcrLines() {
        val result = SentenceAssembler.assemble(listOf(
            line("Fock calls this the distinguishability in \"the large\" of", .1f),
            line("acceleration and gravitation [Fock", .16f),
            line("(1959), p.", .22f),
            line("208]. A state of kinematical acceleration follows.", .28f),
        ))

        assertEquals(
            listOf(
                "Fock calls this the distinguishability in \"the large\" of acceleration and gravitation [Fock (1959), p. 208].",
                "A state of kinematical acceleration follows.",
            ),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun citationBracketsRemainOpenAcrossPages() {
        val firstPage = SentenceAssembler.assemble(listOf(
            line("Acceleration and gravitation [Fock (1959), p.", .84f),
        ))
        val secondPage = SentenceAssembler.assemble(listOf(
            line("208]. A new sentence follows.", .1f),
        ))

        val result = SentenceAssembler.mergePages(listOf(0 to firstPage, 1 to secondPage))

        assertEquals(
            listOf("Acceleration and gravitation [Fock (1959), p. 208].", "A new sentence follows."),
            result.map(DocumentPositionedSentence::text),
        )
    }

    @Test
    fun smallFooterFontCannotBeJoinedIntoBodySentence() {
        val body = PositionedBlock(
            listOf(line("The body sentence continues", .84f, .08f, .44f)), .08f, .84f, .44f, .89f,
        )
        val footer = PositionedBlock(
            listOf(line("Journal Vol. 31.", .95f, .08f, .92f, height = .012f)), .08f, .95f, .92f, .965f,
        )
        val nextColumn = PositionedBlock(
            listOf(line("and finishes in the next column.", .1f, .56f, .92f)), .56f, .1f, .92f, .15f,
        )
        val extraLeft = body.copy(lines = listOf(line("Earlier sentence.", .1f, .08f, .44f)), top = .1f, bottom = .15f)
        val extraRight = nextColumn.copy(lines = listOf(line("Later sentence.", .3f, .56f, .92f)), top = .3f, bottom = .35f)

        val result = SentenceAssembler.assembleBlocks(listOf(body, footer, nextColumn, extraLeft, extraRight))

        assertEquals("The body sentence continues and finishes in the next column.", result.first { it.text.startsWith("The body") }.text)
        assertTrue(result.none { "Journal" in it.text })
    }

    @Test
    fun latexEquationRemainsInsideTheSurroundingSentence() {
        val before = PositionedBlock(
            listOf(line("This relation", .2f)), .1f, .2f, .9f, .25f,
        )
        val equation = PositionedBlock(
            listOf(line("\\[E=mc^2\\]", .27f)), .1f, .27f, .9f, .32f,
            selectableBody = true, type = LayoutBlockType.EQUATION, layoutLabel = "display_formula",
        )
        val after = PositionedBlock(
            listOf(line("is well known.", .34f)), .1f, .34f, .9f, .39f,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(before, equation, after))

        assertEquals(listOf("This relation\n\\[E=mc^2\\]\nis well known."), result.map { it.text })
    }

    @Test
    fun periodInsideDisplayLatexCannotSplitItsClosingDelimiter() {
        val before = PositionedBlock(listOf(line("That is, we let", .2f)), .1f, .2f, .9f, .25f)
        val equation = PositionedBlock(
            listOf(line("\\[\\partial p / \\partial t = \\nabla \\phi.\\]", .27f)),
            .1f, .27f, .9f, .32f, selectableBody = true, type = LayoutBlockType.EQUATION,
            layoutLabel = "display_formula",
        )
        val after = PositionedBlock(listOf(line("Unlike the prior field, it is smooth.", .34f)), .1f, .34f, .9f, .39f)

        val result = SentenceAssembler.assembleBlocks(listOf(before, equation, after))

        assertEquals(
            listOf("That is, we let\n\\[\\partial p / \\partial t = \\nabla \\phi.\\]\nUnlike the prior field, it is smooth."),
            result.map { it.text },
        )
    }

    @Test
    fun numberedDisplayFormulaKeepsItsTerminalPunctuationInTheBoundaryModel() {
        var projectedText = ""
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String): FloatArray {
                projectedText = text
                return FloatArray(text.length).also { scores ->
                    val marker = "equation (1)."
                    scores[text.indexOf(marker) + marker.lastIndex] = .99f
                }
            }
        }
        val before = block(
            "Langevin took the particular value which makes transformation (11) an absolute time Galilean-type rotation",
            top = .10f,
            bottom = .18f,
            order = 0,
        )
        val equation = equation("\\[\\gamma=1.\\]", top = .19f, bottom = .23f, order = 1)
        val number = formulaNumber(top = .19f, bottom = .23f, order = 2)
        val after = block(
            "The calculated value is then the same for stationary and moving observers.",
            top = .24f,
            bottom = .32f,
            order = 3,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(before, equation, number, after), scorer)

        assertTrue("equation (1). The calculated value" in projectedText)
        assertEquals(
            listOf(
                "Langevin took the particular value which makes transformation (11) an absolute time Galilean-type rotation\n\\[\\gamma=1.\\]",
                "The calculated value is then the same for stationary and moving observers.",
            ),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun learnedModelCanSplitAfterAPunctuationlessDisplayFormula() {
        var projectedText = ""
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String): FloatArray {
                projectedText = text
                return FloatArray(text.length).also { scores ->
                    val marker = "equation (1)"
                    scores[text.indexOf(marker) + marker.lastIndex] = .99f
                }
            }
        }
        val before = block(
            "By integrating over clockwise and counterclockwise beams, respectively, one obtains",
            top = .10f,
            bottom = .18f,
            order = 0,
        )
        val equation = equation("\\[\\Delta \\tau = 4 \\Omega A / c^2\\]", .19f, .23f, 1)
        val number = formulaNumber(.19f, .23f, 2)
        val after = block(
            "Equation (29) is a recasting of the first-order Lorentz transformation.",
            top = .24f,
            bottom = .32f,
            order = 3,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(before, equation, number, after), scorer)

        assertTrue("one obtains equation (1) Equation (29)" in projectedText)
        assertEquals(2, result.size)
        assertTrue(result.first().text.endsWith("\\[\\Delta \\tau = 4 \\Omega A / c^2\\]"))
        assertEquals("Equation (29) is a recasting of the first-order Lorentz transformation.", result.last().text)
    }

    @Test
    fun displayFormulaCanRemainInsideOneSentenceWhenTheModelFindsNoBoundary() {
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String) = FloatArray(text.length)
        }
        val before = block("The transformation", .10f, .18f, 0)
        val equation = equation("\\[d s^2 = c^2 d t^2 - d r^2\\]", .19f, .23f, 1)
        val number = formulaNumber(.19f, .23f, 2)
        val after = block("converts Eq. (10) into the rotating frame.", .24f, .32f, 3)

        val result = SentenceAssembler.assembleBlocks(listOf(before, equation, number, after), scorer)

        assertEquals(
            listOf("The transformation\n\\[d s^2 = c^2 d t^2 - d r^2\\]\nconverts Eq. (10) into the rotating frame."),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun assignsOnlyTheOwnedGlyphsToEachSentence() {
        val text = "First. Second."
        val glyphs = text.filterNot(Char::isWhitespace).mapIndexed { index, char ->
            PositionedGlyph(char.toString(), index / 20f, .1f, (index + 1) / 20f, .15f, 1f)
        }

        val result = SentenceAssembler.assemble(
            listOf(PositionedLine(text, 0f, .1f, .7f, .15f, 1f, glyphs)),
        )

        assertEquals(2, result.size)
        assertEquals(6, result[0].regions.size)
        assertEquals(7, result[1].regions.size)
        assertTrue(result[0].regions.maxOf { it.right } <= result[1].regions.minOf { it.left })
    }

    @Test
    fun excludesNonBodyLayoutBlocksBeforeSentenceAssembly() {
        val paragraph = PositionedBlock(listOf(line("Body sentence.", .2f)), .1f, .2f, .9f, .25f)
        val figureLabel = PositionedBlock(
            listOf(line("Accuracy 95 percent.", .5f)), .2f, .5f, .7f, .55f,
            selectableBody = false, type = "HEADER",
        )

        val result = SentenceAssembler.assembleBlocks(listOf(figureLabel, paragraph))

        assertEquals(listOf("Body sentence."), result.map(PositionedSentence::text))
    }

    @Test
    fun learnedScorerControlsCandidatePeriodsWithoutAbbreviationRules() {
        val scorer = object : SentenceBoundaryScorer {
            override val threshold = .5f
            override fun probabilities(text: String) = FloatArray(text.length).also { scores ->
                val boundary = text.indexOf("stable.") + "stable.".lastIndex
                scores[boundary] = .99f
            }
        }

        val result = SentenceAssembler.assembleBlocks(
            listOf(PositionedBlock(
                lines = listOf(line("As shown in Fig. 2, the method by J. Smith is stable. A new result follows.", .1f)),
                left = .1f, top = .1f, right = .9f, bottom = .15f,
            )),
            scorer,
        )

        assertEquals(
            listOf("As shown in Fig. 2, the method by J. Smith is stable.", "A new result follows."),
            result.map(PositionedSentence::text),
        )
    }

    @Test
    fun learnedScorerIsNotVetoedByAnOcrDroppedClosingBracket() {
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String) = FloatArray(text.length).also { scores ->
                text.indexOf("complete.").takeIf { it >= 0 }?.let { start ->
                    scores[start + "complete.".lastIndex] = .99f
                }
            }
        }

        val result = SentenceAssembler.assembleBlocks(
            listOf(PositionedBlock(
                lines = listOf(
                    line("The observation (with damaged OCR is complete.", .1f),
                    line("A new semantic sentence follows.", .2f),
                ),
                left = .1f, top = .1f, right = .9f, bottom = .25f,
            )),
            scorer,
        )

        assertEquals(2, result.size)
        assertEquals("The observation (with damaged OCR is complete.", result.first().text)
    }

    @Test
    fun learnedScorerReadsProbabilityAtPeriodBeforeClosingQuote() {
        val scorer = object : SentenceBoundaryScorer {
            override fun probabilities(text: String) = FloatArray(text.length).also { scores ->
                scores[text.indexOf('.')] = .99f
            }
        }

        val result = SentenceAssembler.assembleBlocks(
            listOf(PositionedBlock(
                lines = listOf(line("The result is called “stable.” Another result follows.", .1f)),
                left = .1f, top = .1f, right = .9f, bottom = .15f,
            )),
            scorer,
        )

        assertEquals(listOf("The result is called “stable.”", "Another result follows."), result.map { it.text })
    }

    @Test
    fun abstractAndBodyRemainSeparateSemanticStreams() {
        val abstract = PositionedBlock(
            lines = listOf(line("Abstract text without terminal punctuation", .1f)),
            left = .1f, top = .1f, right = .9f, bottom = .15f,
            type = LayoutBlockType.ABSTRACT,
        )
        val body = PositionedBlock(
            lines = listOf(line("Body text starts here.", .2f)),
            left = .1f, top = .2f, right = .9f, bottom = .25f,
            type = LayoutBlockType.PARAGRAPH,
        )

        val result = SentenceAssembler.assembleBlocks(listOf(abstract, body))

        assertEquals(listOf(SemanticTextRole.ABSTRACT, SemanticTextRole.BODY), result.map { it.semanticRole })
        assertEquals(2, result.size)
    }

    @Test
    fun crossPageBodySkipsAContinuousStreamWithAnotherRole() {
        val bodyStart = PositionedSentence(
            "The body crosses the", listOf(com.samreader.app.data.NormalizedRect(.1f, .8f, .9f, .85f)),
            1f, terminated = false, semanticRole = SemanticTextRole.BODY,
        )
        val abstract = PositionedSentence(
            "Abstract text.", listOf(com.samreader.app.data.NormalizedRect(.1f, .1f, .9f, .15f)),
            1f, terminated = true, semanticRole = SemanticTextRole.ABSTRACT,
        )
        val bodyEnd = PositionedSentence(
            "page and ends.", listOf(com.samreader.app.data.NormalizedRect(.1f, .2f, .9f, .25f)),
            1f, terminated = true, semanticRole = SemanticTextRole.BODY,
        )

        val merged = SentenceAssembler.mergePages(listOf(0 to listOf(bodyStart), 1 to listOf(abstract, bodyEnd)))

        assertTrue(merged.any { it.text == "The body crosses the page and ends." })
        assertTrue(merged.any { it.text == "Abstract text." })
        assertTrue(merged.none { "body crosses the Abstract" in it.text })
    }

    private fun line(
        text: String,
        top: Float,
        left: Float = 0.1f,
        right: Float = 0.9f,
        height: Float = 0.05f,
    ) = PositionedLine(text, left, top, right, top + height, 1f)

    private fun block(text: String, top: Float, bottom: Float, order: Int) = PositionedBlock(
        lines = listOf(line(text, top, left = .08f, right = .84f)),
        left = .08f,
        top = top,
        right = .84f,
        bottom = bottom,
        readingOrder = order,
        layoutLabel = "text",
    )

    private fun equation(text: String, top: Float, bottom: Float, order: Int) = PositionedBlock(
        lines = listOf(line(text, top, left = .25f, right = .75f)),
        left = .25f,
        top = top,
        right = .75f,
        bottom = bottom,
        selectableBody = true,
        type = LayoutBlockType.EQUATION,
        readingOrder = order,
        layoutLabel = "display_formula",
    )

    private fun formulaNumber(top: Float, bottom: Float, order: Int) = PositionedBlock(
        lines = emptyList(),
        left = .82f,
        top = top,
        right = .88f,
        bottom = bottom,
        selectableBody = false,
        type = LayoutBlockType.EQUATION,
        readingOrder = order,
        layoutLabel = "formula_number",
    )
}
