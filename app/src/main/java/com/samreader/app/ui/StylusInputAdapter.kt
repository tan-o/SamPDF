package com.samreader.app.ui

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

interface StylusInputAdapter {
    val name: String
    fun isStylus(event: MotionEvent): Boolean
    fun pressure(event: MotionEvent, historicalIndex: Int? = null): Float
    fun sideButtonPressed(event: MotionEvent): Boolean
    fun isCanceled(event: MotionEvent): Boolean
}

fun deviceStylusAdapter(): StylusInputAdapter =
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) SamsungSpenInputAdapter else AndroidStylusInputAdapter

private object SamsungSpenInputAdapter : StylusInputAdapter {
    override val name = "Samsung S Pen"
    override fun isStylus(event: MotionEvent): Boolean {
        val type = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER ||
            event.isFromSource(InputDevice.SOURCE_STYLUS) || event.isFromSource(InputDevice.SOURCE_BLUETOOTH_STYLUS)
    }
    override fun pressure(event: MotionEvent, historicalIndex: Int?): Float {
        val raw = if (historicalIndex == null) event.getAxisValue(MotionEvent.AXIS_PRESSURE)
        else event.getHistoricalAxisValue(MotionEvent.AXIS_PRESSURE, historicalIndex)
        // Samsung EMR pens report a broad nonlinear range; a mild gamma curve preserves light strokes.
        return raw.coerceIn(0f, 1.5f).let { kotlin.math.sqrt(it) }.coerceAtLeast(.03f)
    }
    override fun sideButtonPressed(event: MotionEvent) = event.buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
    override fun isCanceled(event: MotionEvent) = event.actionMasked == MotionEvent.ACTION_CANCEL ||
        event.flags and MotionEvent.FLAG_CANCELED != 0
}

private object AndroidStylusInputAdapter : StylusInputAdapter {
    override val name = "Android Stylus"
    override fun isStylus(event: MotionEvent): Boolean {
        val type = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }
    override fun pressure(event: MotionEvent, historicalIndex: Int?) =
        (if (historicalIndex == null) event.pressure else event.getHistoricalPressure(historicalIndex)).coerceAtLeast(.05f)
    override fun sideButtonPressed(event: MotionEvent) = event.buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
    override fun isCanceled(event: MotionEvent) = event.actionMasked == MotionEvent.ACTION_CANCEL ||
        event.flags and MotionEvent.FLAG_CANCELED != 0
}
