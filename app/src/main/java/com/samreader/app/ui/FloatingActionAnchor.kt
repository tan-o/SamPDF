package com.samreader.app.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/** Anchors Android's native floating ActionMode to the selected target, never to the finger. */
internal fun Rect.setAroundScreenPoint(view: View, pointOnScreen: IntOffset) {
    val viewLocation = IntArray(2)
    view.getLocationOnScreen(viewLocation)
    val x = (pointOnScreen.x - viewLocation[0]).coerceIn(0, view.width)
    val y = (pointOnScreen.y - viewLocation[1]).coerceIn(0, view.height)
    set(x - ANCHOR_RADIUS_PX, y - ANCHOR_RADIUS_PX, x + ANCHOR_RADIUS_PX, y + ANCHOR_RADIUS_PX)
}

/** Positions a native floating toolbar above the selected composable's full bounds. */
internal fun Rect.setFromWindowRect(view: View, rectInWindow: IntRect) {
    val viewLocation = IntArray(2)
    view.getLocationInWindow(viewLocation)
    set(
        (rectInWindow.left - viewLocation[0]).coerceIn(0, view.width),
        (rectInWindow.top - viewLocation[1]).coerceIn(0, view.height),
        (rectInWindow.right - viewLocation[0]).coerceIn(0, view.width),
        (rectInWindow.bottom - viewLocation[1]).coerceIn(0, view.height),
    )
}

private const val ANCHOR_RADIUS_PX = 2
