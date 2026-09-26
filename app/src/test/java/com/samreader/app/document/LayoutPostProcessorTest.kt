package com.samreader.app.document

import com.samreader.app.document.LayoutPostProcessor.Candidate
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPostProcessorTest {
    @Test
    fun oneQueryPredictedAsTwoClassesKeepsOnlyTheStrongerClass() {
        val text = candidate("text", .8f, .1f, .1f, .5f, .3f)
        val abstract = candidate("abstract", .6f, .1f, .1f, .5f, .3f)

        assertEquals(listOf(text), LayoutPostProcessor.nms(listOf(abstract, text)))
    }

    @Test
    fun distinctClassesThatMerelyOverlapAreBothKept() {
        val caption = candidate("figure_title", .9f, .1f, .50f, .9f, .55f)
        val text = candidate("text", .8f, .1f, .52f, .9f, .70f)

        assertEquals(2, LayoutPostProcessor.nms(listOf(caption, text)).size)
    }

    @Test
    fun boxesInsideALargeModeClassAreDropped() {
        val title = candidate("paragraph_title", .9f, .1f, .1f, .6f, .15f)
        val number = candidate("number", .7f, .1f, .1f, .15f, .15f)
        val paragraph = candidate("text", .9f, .1f, .2f, .9f, .5f)
        val formulaInParagraph = candidate("inline_formula", .9f, .3f, .3f, .4f, .32f)

        assertEquals(
            listOf(title, paragraph, formulaInParagraph),
            LayoutPostProcessor.mergeContainedBoxes(listOf(title, number, paragraph, formulaInParagraph)),
        )
    }

    @Test
    fun fullPageImageIsDroppedOnlyWhenOtherRegionsExist() {
        val page = candidate("image", .9f, 0f, 0f, 1f, 1f)
        val text = candidate("text", .9f, .1f, .1f, .9f, .2f)

        assertEquals(listOf(text), LayoutPostProcessor.filterFullPageImages(listOf(page, text), pageAspectRatio = .77f))
        assertEquals(listOf(page), LayoutPostProcessor.filterFullPageImages(listOf(page), pageAspectRatio = .77f))
    }

    @Test
    fun decodedRegionsFollowTheModelReadingOrder() {
        val queries = LayoutPostProcessor.QUERY_COUNT
        val classes = LayoutRegion.LABELS.size
        val logits = FloatArray(queries * classes) { -20f }
        val boxes = FloatArray(queries * 4)
        val order = FloatArray(queries * queries) { -20f }
        val text = LayoutRegion.LABELS.indexOf("text")
        // Query 0 is the lower box and query 1 the upper one; the order head says 1 follows 0.
        logits[0 * classes + text] = 5f
        logits[1 * classes + text] = 5f
        floatArrayOf(.5f, .7f, .8f, .2f, .5f, .3f, .8f, .2f).copyInto(boxes)
        order[0 * queries + 1] = 20f

        val regions = LayoutPostProcessor.decode(
            FloatBuffer.wrap(logits), FloatBuffer.wrap(boxes),
            FloatBuffer.wrap(FloatArray(queries * LayoutRegion.MASK_SIZE * LayoutRegion.MASK_SIZE)),
            FloatBuffer.wrap(order), threshold = .5f, pageAspectRatio = .77f,
        )

        assertEquals(listOf(.6f, .2f), regions.map { Math.round(it.top * 100) / 100f })
        assertEquals(listOf("text", "text"), regions.map(LayoutRegion::label))
    }

    private fun candidate(label: String, score: Float, left: Float, top: Float, right: Float, bottom: Float) =
        Candidate(0, LayoutRegion.LABELS.indexOf(label), score, left, top, right, bottom)
}
