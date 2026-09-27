package com.samreader.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningFurnitureTest {
    @Test
    fun marginLinesRepeatedAcrossPagesAreFurnitureDespiteChangingNumbers() {
        val pages = (1..6).map { page ->
            listOf(
                line("KDD ’22, August 14–18, 2022, Washington, DC, USA · $page", .02f),
                line("Body text that is different on page $page and ${"x".repeat(page)}.", .50f),
            )
        }

        val furniture = RunningFurniture.detect(pages)

        assertTrue(furniture.isFurniture(line("KDD ’22, August 14–18, 2022, Washington, DC, USA · 9", .02f)))
        assertFalse(furniture.isFurniture(pages[0][1]))
    }

    @Test
    fun repeatedTextOutsideTheMarginsAndOneOffMarginLinesStay() {
        val pages = (1..6).map { listOf(line("Algorithm 1 continues", .5f), line("Received 3 May; accepted 9 June", if (it == 1) .95f else .5f)) }

        val furniture = RunningFurniture.detect(pages)

        assertFalse(furniture.isFurniture(line("Algorithm 1 continues", .5f)))
        assertFalse(furniture.isFurniture(line("Received 3 May; accepted 9 June", .95f)))
    }

    @Test
    fun aBarePageNumberInTheMarginIsFurniture() {
        assertTrue(RunningFurniture.NONE.isFurniture(line("– 12 –", .96f)))
        assertFalse(RunningFurniture.NONE.isFurniture(line("12", .5f)))
    }

    @Test
    fun parserDropsRunningHeadsTheLayoutModelReadAsText() {
        val head = line("Journal of Things, Vol. 3", .03f)
        val furniture = RunningFurniture.detect(List(5) { listOf(head) })
        val regions = listOf(
            LayoutRegion(LayoutRegion.LABELS.indexOf("text"), .9f, .1f, .01f, .9f, .06f, 0),
            LayoutRegion(LayoutRegion.LABELS.indexOf("text"), .9f, .1f, .2f, .9f, .4f, 1),
        )

        val blocks = PageParser.parse(0, regions, listOf(head, line("Real body text.", .3f)), emptyList(), emptyList(), furniture)

        assertEquals(listOf("Real body text."), blocks.filter { it.selectableBody }.flatMap { it.lines }.map { it.text })
    }

    private fun line(text: String, centerY: Float) = PositionedLine(text, .1f, centerY - .01f, .9f, centerY + .01f, 1f)
}
