package com.samreader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InkCodecTest {
    @Test fun roundTripsPressurePoints() {
        val points = listOf(InkPoint(.1f, .2f, .35f), InkPoint(.8f, .9f, 1.2f))
        val decoded = parsePoints(encodePoints(points))
        assertEquals(points.size, decoded.size)
        assertEquals(.35f, decoded.first().pressure, .0001f)
        assertEquals(1.2f, decoded.last().pressure, .0001f)
    }
}
