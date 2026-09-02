package com.samreader.app.ui

import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.samreader.app.data.*
import java.util.UUID
import kotlin.math.*

data class RenderStroke(
    val id: String,
    val points: List<InkPoint>,
    val color: Color,
    val widthNormalized: Float,
    val pressureEnabled: Boolean,
    val tool: InkTool = InkTool.PEN,
    val controlPoints: List<InkPoint> = emptyList(),
)

data class InkCommit(
    val id: String,
    val points: List<InkPoint>,
    val settings: InkSettings,
    val tool: InkTool,
    val controlPoints: List<InkPoint>,
)

private enum class GestureMode {
    DRAW, ERASE, SELECT_BOX, MOVE_SELECTION, RESIZE_SELECTION, ROTATE_SELECTION, SHAPE_HANDLE,
}

@Composable
fun InkSurface(
    strokes: List<RenderStroke>,
    settings: InkSettings,
    modifier: Modifier = Modifier,
    sideButtonAction: SpenButtonAction = SpenButtonAction.ERASER,
    onStroke: (InkCommit) -> Unit,
    onUpdateStrokes: (List<RenderStroke>) -> Unit = {},
    onStrokeEraseAt: (Float, Float) -> Unit,
    onAreaErase: (List<String>, List<RenderStroke>) -> Unit,
    onSentenceShortcut: ((Float, Float) -> Unit)? = null,
) {
    val adapter = remember { deviceStylusAdapter() }
    val disallowIntercept = remember { RequestDisallowInterceptTouchEvent() }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var eraserPath by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var eraserAt by remember { mutableStateOf<InkPoint?>(null) }
    var gestureTool by remember { mutableStateOf(InkTool.PEN) }
    var gestureMode by remember { mutableStateOf(GestureMode.DRAW) }
    var selectionStart by remember { mutableStateOf<InkPoint?>(null) }
    var selectionEnd by remember { mutableStateOf<InkPoint?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<Map<String, RenderStroke>>(emptyMap()) }
    var gestureOriginal by remember { mutableStateOf<List<RenderStroke>>(emptyList()) }
    var gestureStart by remember { mutableStateOf<InkPoint?>(null) }
    var draggedHandle by remember { mutableIntStateOf(-1) }
    var fixedPoint by remember { mutableStateOf<InkPoint?>(null) }
    var originalBounds by remember { mutableStateOf<FloatArray?>(null) }
    var gestureBadge by remember { mutableStateOf<Pair<String, InkPoint>?>(null) }
    val liveSettings by rememberUpdatedState(settings)
    val liveStrokes by rememberUpdatedState(strokes)

    LaunchedEffect(settings.tool) {
        if (settings.tool != InkTool.SELECT) {
            selectedIds = emptySet()
            preview = emptyMap()
        }
    }

    fun currentStrokes(): List<RenderStroke> = liveStrokes.map { preview[it.id] ?: it }
    fun selectedCurrent(): List<RenderStroke> = currentStrokes().filter { it.id in selectedIds }
    fun commitPreview() {
        val changed = selectedIds.mapNotNull(preview::get)
        if (changed.isNotEmpty()) onUpdateStrokes(changed)
        preview = emptyMap()
    }
    fun updateSelected(transform: (RenderStroke) -> RenderStroke) {
        val updated = selectedCurrent().map(transform)
        if (updated.isNotEmpty()) onUpdateStrokes(updated)
    }

    Box(modifier.onSizeChanged { surfaceSize = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInteropFilter(
                requestDisallowInterceptTouchEvent = disallowIntercept,
            ) { event ->
                if (!adapter.isStylus(event) || !liveSettings.penEnabled || surfaceSize.width == 0 || surfaceSize.height == 0) {
                    return@pointerInteropFilter false
                }
                val point = InkPoint(
                    (event.x / surfaceSize.width).coerceIn(0f, 1f),
                    (event.y / surfaceSize.height).coerceIn(0f, 1f),
                    adapter.pressure(event),
                )
                val sidePressed = adapter.sideButtonPressed(event)
                if (adapter.isCanceled(event)) {
                    active = emptyList(); eraserPath = emptyList(); eraserAt = null
                    selectionStart = null; selectionEnd = null; preview = emptyMap()
                    gestureBadge = null
                    disallowIntercept(false)
                    return@pointerInteropFilter true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        val erasing = liveSettings.tool == InkTool.ERASER || sidePressed && sideButtonAction == SpenButtonAction.ERASER
                        eraserAt = point.takeIf { erasing }
                        false
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> { eraserAt = null; false }
                    MotionEvent.ACTION_DOWN -> {
                        disallowIntercept(true)
                        gestureStart = point
                        if (sidePressed && sideButtonAction == SpenButtonAction.SENTENCE_NOTE && liveSettings.tool != InkTool.ERASER) {
                            onSentenceShortcut?.invoke(point.x, point.y)
                            return@pointerInteropFilter true
                        }
                        val physicalEraser = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER
                        gestureTool = if (physicalEraser || liveSettings.tool == InkTool.ERASER || sidePressed && sideButtonAction == SpenButtonAction.ERASER) InkTool.ERASER else liveSettings.tool
                        if (gestureTool == InkTool.SELECT) {
                            val chosen = selectedCurrent()
                            val single = chosen.singleOrNull()
                            val shapeHandle = single?.editableControls()?.withIndex()?.minByOrNull { (_, value) -> distance(value, point) }
                            val bounds = selectionBounds(chosen)
                            val rotate = bounds?.let(::rotationHandle)
                            val corners = bounds?.let(::cornerHandles).orEmpty()
                            val resize = corners.withIndex().minByOrNull { (_, value) -> distance(value, point) }
                            gestureOriginal = chosen
                            originalBounds = bounds
                            when {
                                shapeHandle != null && distance(shapeHandle.value, point) < .032f -> {
                                    gestureMode = GestureMode.SHAPE_HANDLE
                                    draggedHandle = shapeHandle.index
                                }
                                rotate != null && distance(rotate, point) < .035f -> gestureMode = GestureMode.ROTATE_SELECTION
                                resize != null && distance(resize.value, point) < .035f -> {
                                    gestureMode = GestureMode.RESIZE_SELECTION
                                    draggedHandle = resize.index
                                    fixedPoint = corners[(resize.index + 2) % 4]
                                }
                                bounds != null && point.x in bounds[0]..bounds[2] && point.y in bounds[1]..bounds[3] -> gestureMode = GestureMode.MOVE_SELECTION
                                else -> {
                                    gestureMode = GestureMode.SELECT_BOX
                                    selectionStart = point; selectionEnd = point; selectedIds = emptySet(); preview = emptyMap()
                                }
                            }
                        } else if (gestureTool == InkTool.ERASER) {
                            gestureMode = GestureMode.ERASE
                            eraserAt = point
                            if (liveSettings.eraserMode == EraserMode.STROKE) onStrokeEraseAt(point.x, point.y) else eraserPath = listOf(point)
                        } else {
                            gestureMode = GestureMode.DRAW
                            active = listOf(point)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val points = buildList {
                            repeat(event.historySize) { index -> add(InkPoint(
                                event.getHistoricalX(index) / surfaceSize.width,
                                event.getHistoricalY(index) / surfaceSize.height,
                                adapter.pressure(event, index),
                            )) }
                            add(point)
                        }
                        when (gestureMode) {
                            GestureMode.ERASE -> {
                                eraserAt = point
                                if (liveSettings.eraserMode == EraserMode.STROKE) points.forEach { onStrokeEraseAt(it.x, it.y) } else eraserPath += points
                            }
                            GestureMode.DRAW -> active += points
                            GestureMode.SELECT_BOX -> selectionEnd = point
                            GestureMode.MOVE_SELECTION -> {
                                val start = gestureStart ?: point
                                val delta = Offset(point.x - start.x, point.y - start.y)
                                preview = gestureOriginal.associate { it.id to it.translate(delta) }
                            }
                            GestureMode.RESIZE_SELECTION -> {
                                val fixed = fixedPoint ?: point
                                val start = cornerHandles(originalBounds ?: floatArrayOf(0f,0f,1f,1f)).getOrElse(draggedHandle) { point }
                                val sx = safeScale(point.x-fixed.x, start.x-fixed.x)
                                val sy = safeScale(point.y-fixed.y, start.y-fixed.y)
                                preview = gestureOriginal.associate { it.id to it.scale(fixed, sx, sy) }
                            }
                            GestureMode.ROTATE_SELECTION -> {
                                val bounds = originalBounds
                                val start = gestureStart
                                if (bounds != null && start != null) {
                                    val center = InkPoint((bounds[0]+bounds[2])/2f, (bounds[1]+bounds[3])/2f, 1f)
                                    val angle = atan2(point.y-center.y, point.x-center.x) - atan2(start.y-center.y, start.x-center.x)
                                    preview = gestureOriginal.associate { it.id to it.rotate(center, angle) }
                                    gestureBadge = "${(angle * 180f / PI.toFloat()).roundToInt()}°" to point
                                }
                            }
                            GestureMode.SHAPE_HANDLE -> {
                                val original = gestureOriginal.singleOrNull()
                                if (original != null) {
                                    val controls = original.editableControls().toMutableList()
                                    if (draggedHandle == 0 && original.tool in listOf(InkTool.RECTANGLE,InkTool.ELLIPSE) && controls.size == 3) {
                                        val delta=Offset(point.x-controls[0].x,point.y-controls[0].y)
                                        controls.indices.forEach { index -> controls[index]=controls[index].shift(delta) }
                                    } else if (draggedHandle in controls.indices) controls[draggedHandle] = point
                                    val adjusted = constrainEditedControls(original.tool, controls, liveSettings.shapeSnapEnabled, draggedHandle)
                                    preview = mapOf(original.id to original.copy(
                                        controlPoints = adjusted,
                                        points = shapeFromControls(original.tool, adjusted, original.points),
                                    ))
                                    gestureBadge = when (original.tool) {
                                        InkTool.LINE -> drawingBadge(original.tool, adjusted)
                                        InkTool.RECTANGLE -> if (adjusted.size == 3 && isStandardShape(adjusted)) "□ 正方形" to point else null
                                        InkTool.ELLIPSE -> if (adjusted.size == 3 && isStandardShape(adjusted)) "○ 正圆" to point else null
                                        else -> null
                                    }
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        when (gestureMode) {
                            GestureMode.ERASE -> if (liveSettings.eraserMode == EraserMode.AREA) {
                                val result = eraseArea(liveStrokes, eraserPath + point, liveSettings.eraserRadiusNormalized)
                                if (result.first.isNotEmpty()) onAreaErase(result.first, result.second)
                            }
                            GestureMode.DRAW -> if (active.size >= 2) {
                                val raw = active + point
                                val controls = drawingControls(gestureTool, raw, liveSettings)
                                val id = UUID.randomUUID().toString()
                                onStroke(InkCommit(
                                    id, shapeFromControls(gestureTool, controls, raw), liveSettings, gestureTool, controls,
                                ))
                            }
                            GestureMode.SELECT_BOX -> {
                                selectionEnd = point
                                val box = normalizedBox(selectionStart, selectionEnd)
                                selectedIds = liveStrokes.filter { strokeIntersects(it, box) }.map(RenderStroke::id).toSet()
                            }
                            GestureMode.MOVE_SELECTION, GestureMode.RESIZE_SELECTION,
                            GestureMode.ROTATE_SELECTION, GestureMode.SHAPE_HANDLE -> commitPreview()
                        }
                        active = emptyList(); eraserPath = emptyList(); eraserAt = null
                        selectionStart = null; selectionEnd = null; gestureStart = null
                        gestureOriginal = emptyList(); draggedHandle = -1; fixedPoint = null; originalBounds = null
                        gestureBadge = null
                        disallowIntercept(false)
                        true
                    }
                    else -> true
                }
            },
        ) {
            val visible = currentStrokes()
            visible.forEach { drawInk(it.points, it.color, it.widthNormalized, it.pressureEnabled) }
            val activeControls = drawingControls(gestureTool, active, liveSettings)
            val activeShape = shapeFromControls(gestureTool, activeControls, active)
            drawInk(activeShape, Color(liveSettings.colorArgb), liveSettings.widthNormalized, liveSettings.pressureEnabled)
            if (eraserPath.isNotEmpty()) drawInk(eraserPath, Color(0x885E6C72), .0015f, false)
            eraserAt?.let { value -> drawCircle(
                Color(0xFF5E6C72), liveSettings.eraserRadiusNormalized * size.width,
                Offset(value.x*size.width, value.y*size.height),
                style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f,7f))),
            ) }
            normalizedBox(selectionStart, selectionEnd)?.let { drawSelectionBox(it, false) }
            val selected = visible.filter { it.id in selectedIds }
            selectionBounds(selected)?.let { bounds ->
                drawSelectionBox(bounds, true)
                cornerHandles(bounds).forEach { drawHandle(it, Color.White) }
                val rotate = rotationHandle(bounds)
                val centerX = (bounds[0]+bounds[2])/2f
                val centerY = (bounds[1]+bounds[3])/2f
                drawLine(Color(0xFF1976D2), Offset(centerX*size.width,bounds[1]*size.height), Offset(rotate.x*size.width,rotate.y*size.height), 2f)
                drawCircle(Color(0xFF1976D2), 5f, Offset(centerX*size.width, centerY*size.height))
                drawHandle(rotate, Color(0xFFFFB300))
            }
            selected.singleOrNull()?.takeIf { it.tool in SHAPE_TOOLS }?.let { shape ->
                val controls = shape.editableControls()
                if (shape.tool == InkTool.BEZIER && controls.size == 4) {
                    drawLine(Color(0xFF1976D2), controls[0].toOffset(size), controls[1].toOffset(size), 2f)
                    drawLine(Color(0xFF1976D2), controls[3].toOffset(size), controls[2].toOffset(size), 2f)
                }
                controls.forEachIndexed { index, control -> drawHandle(control, if (shape.tool==InkTool.BEZIER && index in 1..2) Color(0xFF1976D2) else Color.White) }
            }
            drawingBadge(gestureTool, activeControls)?.let { (label, anchor) ->
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(
                    label, anchor.x*size.width+14f, anchor.y*size.height-14f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(25,118,210); textSize=30f; typeface=android.graphics.Typeface.DEFAULT_BOLD },
                ) }
            }
            gestureBadge?.let { (label, anchor) ->
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(
                    label, anchor.x*size.width+14f, anchor.y*size.height-14f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(245,124,0); textSize=30f; typeface=android.graphics.Typeface.DEFAULT_BOLD },
                ) }
            }
        }

        if (selectedIds.isNotEmpty() && settings.tool == InkTool.SELECT) {
            SelectionStyleBar(
                count = selectedIds.size,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                onColor = { color -> updateSelected { it.copy(color = Color(color)) } },
                onThinner = { updateSelected { it.copy(widthNormalized = (it.widthNormalized*.8f).coerceAtLeast(.001f)) } },
                onThicker = { updateSelected { it.copy(widthNormalized = (it.widthNormalized*1.25f).coerceAtMost(.02f)) } },
                onDelete = { onAreaErase(selectedIds.toList(), emptyList()); selectedIds = emptySet(); preview = emptyMap() },
            )
        }
    }
}

@Composable
private fun SelectionStyleBar(
    count: Int, modifier: Modifier, onColor: (Long)->Unit, onThinner:()->Unit, onThicker:()->Unit, onDelete:()->Unit,
) {
    Surface(modifier, shape=RoundedCornerShape(20.dp), shadowElevation=8.dp, tonalElevation=5.dp) {
        Row(Modifier.padding(horizontal=8.dp), verticalAlignment=Alignment.CenterVertically) {
            Text("已选 $count", style=MaterialTheme.typography.labelMedium)
            PALETTE.forEach { color -> TextButton(onClick={onColor(color)}, contentPadding=PaddingValues(4.dp)) { Text("●", color=Color(color)) } }
            TextButton(onClick=onThinner) { Text("细") }
            TextButton(onClick=onThicker) { Text("粗") }
            TextButton(onClick=onDelete) { Text("删除", color=MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun InkPreview(strokes:List<RenderStroke>, modifier:Modifier=Modifier) {
    Canvas(modifier) { strokes.forEach { drawInk(it.points,it.color,it.widthNormalized,it.pressureEnabled) } }
}

private fun drawingControls(tool: InkTool, raw: List<InkPoint>, settings: InkSettings): List<InkPoint> {
    if (raw.size < 2 || tool !in SHAPE_TOOLS) return emptyList()
    val start = raw.first()
    var end = raw.last()
    val pressure = raw.map(InkPoint::pressure).average().toFloat()
    if (tool == InkTool.LINE) {
        if (settings.shapeSnapEnabled) end = snapLine(start, end)
        return listOf(start, end)
    }
    if (tool == InkTool.BEZIER) return bezierControls(raw)
    val dx = end.x-start.x; val dy=end.y-start.y
    if (settings.shapeSnapEnabled && abs(abs(dx)-abs(dy)) <= max(abs(dx),abs(dy))*.12f) {
        val length=max(abs(dx),abs(dy));end=InkPoint(start.x+sign(dx)*length,start.y+sign(dy)*length,pressure)
    }
    val corners = if (settings.shapeFromCenter) {
        listOf(InkPoint(start.x-abs(end.x-start.x),start.y-abs(end.y-start.y),pressure),InkPoint(start.x+abs(end.x-start.x),start.y+abs(end.y-start.y),pressure))
    } else listOf(
        InkPoint(min(start.x,end.x),min(start.y,end.y),pressure),
        InkPoint(max(start.x,end.x),max(start.y,end.y),pressure),
    )
    val center=InkPoint((corners[0].x+corners[1].x)/2f,(corners[0].y+corners[1].y)/2f,pressure)
    return listOf(center,InkPoint(corners[1].x,center.y,pressure),InkPoint(center.x,corners[1].y,pressure))
}

private fun shapeFromControls(tool:InkTool, controls:List<InkPoint>, raw:List<InkPoint>):List<InkPoint> {
    if (tool !in SHAPE_TOOLS) return raw
    if (controls.size < 2) return raw
    val pressure=(controls.map(InkPoint::pressure).average().toFloat()).coerceAtLeast(.1f)
    return when(tool) {
        InkTool.LINE -> listOf(controls[0],controls[1])
        InkTool.RECTANGLE -> { if(controls.size<3)return raw;val c=controls[0];val vx=Offset(controls[1].x-c.x,controls[1].y-c.y);val vy=Offset(controls[2].x-c.x,controls[2].y-c.y);listOf(InkPoint(c.x-vx.x-vy.x,c.y-vx.y-vy.y,pressure),InkPoint(c.x+vx.x-vy.x,c.y+vx.y-vy.y,pressure),InkPoint(c.x+vx.x+vy.x,c.y+vx.y+vy.y,pressure),InkPoint(c.x-vx.x+vy.x,c.y-vx.y+vy.y,pressure),InkPoint(c.x-vx.x-vy.x,c.y-vx.y-vy.y,pressure)) }
        InkTool.ELLIPSE -> { if(controls.size<3)return raw;val c=controls[0];val vx=Offset(controls[1].x-c.x,controls[1].y-c.y);val vy=Offset(controls[2].x-c.x,controls[2].y-c.y);(0..72).map{i->val angle=i/72f*2f*PI.toFloat();InkPoint(c.x+vx.x*cos(angle)+vy.x*sin(angle),c.y+vx.y*cos(angle)+vy.y*sin(angle),pressure)} }
        InkTool.BEZIER -> bezierPoints(controls)
        else -> raw
    }
}

private fun RenderStroke.editableControls():List<InkPoint> = when {
    controlPoints.isNotEmpty() -> controlPoints
    tool == InkTool.LINE && points.size>=2 -> listOf(points.first(),points.last())
    tool in listOf(InkTool.RECTANGLE,InkTool.ELLIPSE) -> selectionBounds(listOf(this))?.let { val c=InkPoint((it[0]+it[2])/2,(it[1]+it[3])/2,1f);listOf(c,InkPoint(it[2],c.y,1f),InkPoint(c.x,it[3],1f)) }.orEmpty()
    else -> emptyList()
}

private fun constrainEditedControls(tool:InkTool, controls:List<InkPoint>, snap:Boolean, moved:Int):List<InkPoint> {
    if (!snap || controls.size<2) return controls
    if (tool==InkTool.LINE) { val fixed=controls[if(moved==0)1 else 0];val mobile=controls[moved.coerceIn(0,1)];return controls.toMutableList().also{it[moved.coerceIn(0,1)]=snapLine(fixed,mobile)} }
    if (tool in listOf(InkTool.RECTANGLE,InkTool.ELLIPSE) && controls.size==3 && moved in 1..2) {
        val center=controls[0];val mobile=controls[moved];val other=controls[if(moved==1)2 else 1]
        val mobileLength=distance(center,mobile);val otherLength=distance(center,other)
        val result=controls.toMutableList()
        if(abs(mobileLength-otherLength)<=max(mobileLength,otherLength)*.12f && mobileLength>.0001f){val scale=otherLength/mobileLength;result[moved]=mobile.copy(x=center.x+(mobile.x-center.x)*scale,y=center.y+(mobile.y-center.y)*scale)}
        return result
    }
    return controls
}

private fun snapLine(start:InkPoint,end:InkPoint):InkPoint {
    val dx=end.x-start.x;val dy=end.y-start.y;val length=hypot(dx,dy);if(length<.0001f)return end
    val angle=atan2(dy,dx);val step=PI.toFloat()/4f;val snapped=(angle/step).roundToInt()*step
    val difference=abs(normalizeAngle(angle-snapped))*180f/PI.toFloat()
    return if(difference<=6f) end.copy(x=start.x+cos(snapped)*length,y=start.y+sin(snapped)*length) else end
}

private fun drawingBadge(tool:InkTool,controls:List<InkPoint>):Pair<String,InkPoint>? {
    if(controls.size<2)return null;val a=controls[0];val b=controls[1]
    return when(tool){
        InkTool.LINE -> {val degrees=((atan2(b.y-a.y,b.x-a.x)*180f/PI.toFloat()+360f)%180f).roundToInt();"${degrees}°" to b}
        InkTool.RECTANGLE -> if(controls.size==3&&isStandardShape(controls))"□ 正方形" to b else null
        InkTool.ELLIPSE -> if(controls.size==3&&isStandardShape(controls))"○ 正圆" to b else null
        else -> null
    }
}

private fun isStandardShape(c:List<InkPoint>):Boolean{val x=Offset(c[1].x-c[0].x,c[1].y-c[0].y);val y=Offset(c[2].x-c[0].x,c[2].y-c[0].y);val lx=x.getDistance();val ly=y.getDistance();return abs(lx-ly)<=max(lx,ly)*.02f&&abs(x.x*y.x+x.y*y.y)<=lx*ly*.03f}
private fun bezierControls(raw:List<InkPoint>):List<InkPoint>{val start=raw.first();val end=raw.last();val p=raw.map(InkPoint::pressure).average().toFloat();return listOf(start,raw.getOrElse((raw.lastIndex/3).coerceAtLeast(1)){InkPoint(start.x+(end.x-start.x)/3,start.y,p)},raw.getOrElse((raw.lastIndex*2/3).coerceAtMost(raw.lastIndex-1)){InkPoint(start.x+(end.x-start.x)*2/3,end.y,p)},end)}
private fun bezierPoints(c:List<InkPoint>):List<InkPoint>{if(c.size!=4)return emptyList();val p=c.map(InkPoint::pressure).average().toFloat();return(0..72).map{i->val t=i/72f;val u=1f-t;InkPoint(u.pow(3)*c[0].x+3*u.pow(2)*t*c[1].x+3*u*t.pow(2)*c[2].x+t.pow(3)*c[3].x,u.pow(3)*c[0].y+3*u.pow(2)*t*c[1].y+3*u*t.pow(2)*c[2].y+t.pow(3)*c[3].y,p)}}
private fun RenderStroke.translate(d:Offset)=copy(points=points.map{it.shift(d)},controlPoints=controlPoints.map{it.shift(d)})
private fun RenderStroke.scale(fixed:InkPoint,sx:Float,sy:Float)=copy(points=points.map{it.scaleAround(fixed,sx,sy)},controlPoints=controlPoints.map{it.scaleAround(fixed,sx,sy)})
private fun RenderStroke.rotate(center:InkPoint,angle:Float)=copy(points=points.map{it.rotateAround(center,angle)},controlPoints=controlPoints.map{it.rotateAround(center,angle)})
private fun InkPoint.shift(d:Offset)=copy(x=(x+d.x).coerceIn(0f,1f),y=(y+d.y).coerceIn(0f,1f))
private fun InkPoint.scaleAround(fixed:InkPoint,sx:Float,sy:Float)=copy(x=(fixed.x+(x-fixed.x)*sx).coerceIn(0f,1f),y=(fixed.y+(y-fixed.y)*sy).coerceIn(0f,1f))
private fun InkPoint.rotateAround(center:InkPoint,a:Float):InkPoint{val dx=x-center.x;val dy=y-center.y;return copy(x=(center.x+dx*cos(a)-dy*sin(a)).coerceIn(0f,1f),y=(center.y+dx*sin(a)+dy*cos(a)).coerceIn(0f,1f))}
private fun normalizedBox(a:InkPoint?,b:InkPoint?):FloatArray?=if(a==null||b==null)null else floatArrayOf(min(a.x,b.x),min(a.y,b.y),max(a.x,b.x),max(a.y,b.y))
private fun selectionBounds(strokes:List<RenderStroke>):FloatArray?{val p=strokes.flatMap{it.points};return if(p.isEmpty())null else floatArrayOf(p.minOf{it.x},p.minOf{it.y},p.maxOf{it.x},p.maxOf{it.y})}
private fun strokeIntersects(stroke:RenderStroke,box:FloatArray?)=box!=null&&stroke.points.any{it.x in box[0]..box[2]&&it.y in box[1]..box[3]}
private fun cornerHandles(b:FloatArray)=listOf(InkPoint(b[0],b[1],1f),InkPoint(b[2],b[1],1f),InkPoint(b[2],b[3],1f),InkPoint(b[0],b[3],1f))
private fun rotationHandle(b:FloatArray)=InkPoint((b[0]+b[2])/2f,(b[1]-.055f).coerceAtLeast(.01f),1f)
private fun distance(a:InkPoint,b:InkPoint)=hypot(a.x-b.x,a.y-b.y)
private fun safeScale(value:Float,base:Float)=if(abs(base)<.0001f)1f else (value/base).coerceIn(-8f,8f)
private fun normalizeAngle(value:Float):Float{var result=value;while(result>PI)result-=2f*PI.toFloat();while(result< -PI)result+=2f*PI.toFloat();return result}
private fun InkPoint.toOffset(size:androidx.compose.ui.geometry.Size)=Offset(x*size.width,y*size.height)
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionBox(b:FloatArray,handles:Boolean){drawRect(Color(0xFF1976D2),Offset(b[0]*size.width,b[1]*size.height),androidx.compose.ui.geometry.Size((b[2]-b[0])*size.width,(b[3]-b[1])*size.height),style=Stroke(if(handles)2.5f else 2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(9f,7f))))}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(point:InkPoint,fill:Color){drawCircle(fill,9f,point.toOffset(size));drawCircle(Color(0xFF1976D2),9f,point.toOffset(size),style=Stroke(3f))}
private fun eraseArea(strokes:List<RenderStroke>,path:List<InkPoint>,radius:Float):Pair<List<String>,List<RenderStroke>>{if(path.isEmpty())return emptyList<String>() to emptyList();val deleted=mutableListOf<String>();val fragments=mutableListOf<RenderStroke>();strokes.forEach{stroke->val keep=stroke.points.map{p->path.none{e->hypot(p.x-e.x,p.y-e.y)<=radius}};if(keep.all{it})return@forEach;deleted+=stroke.id;var group=mutableListOf<InkPoint>();stroke.points.zip(keep).forEach{(point,retained)->if(retained)group+=point else{if(group.size>=2)fragments+=stroke.copy(id="",points=group.toList(),tool=InkTool.PEN,controlPoints=emptyList());group=mutableListOf()}};if(group.size>=2)fragments+=stroke.copy(id="",points=group,tool=InkTool.PEN,controlPoints=emptyList())};return deleted to fragments}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(points:List<InkPoint>,color:Color,baseWidth:Float,pressureEnabled:Boolean){points.zipWithNext().forEach{(a,b)->val pressure=if(pressureEnabled)((a.pressure+b.pressure)/2).coerceIn(.2f,1.5f)else 1f;drawLine(color,a.toOffset(size),b.toOffset(size),(baseWidth*pressure*size.width).coerceAtLeast(1.5f),StrokeCap.Round)}}

@Composable
fun InkToolMenu(settings:InkSettings,onChange:(InkSettings)->Unit){
    var expanded by remember{mutableStateOf(false)}
    TextButton(onClick={expanded=true}){Text(if(!settings.penEnabled)"画笔关"else toolLabel(settings.tool))}
    DropdownMenu(expanded,{expanded=false}){
        DropdownMenuItem(text={Text(if(settings.penEnabled)"关闭画笔（S Pen 导航）"else"开启画笔")},onClick={onChange(settings.copy(penEnabled=!settings.penEnabled));expanded=false})
        listOf(InkTool.SELECT,InkTool.PEN,InkTool.LINE,InkTool.BEZIER,InkTool.ELLIPSE,InkTool.RECTANGLE,InkTool.ERASER).forEach{tool->DropdownMenuItem(text={Text(toolLabel(tool))},onClick={onChange(settings.copy(tool=tool,penEnabled=true));expanded=false})}
        Column(Modifier.padding(horizontal=16.dp).width(300.dp)){
            Text("画笔粗细 ${(settings.widthNormalized*1000).toInt()}");Slider(settings.widthNormalized,{onChange(settings.copy(widthNormalized=it))},valueRange=.001f.. .012f)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(settings.pressureEnabled,{onChange(settings.copy(pressureEnabled=it))});Text("启用压感")}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(settings.shapeFromCenter,{onChange(settings.copy(shapeFromCenter=it))});Text("形状从中心绘制")}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(settings.shapeSnapEnabled,{onChange(settings.copy(shapeSnapEnabled=it))});Text("吸附正圆/正方形/标准角度")}
            Text("橡皮大小 ${(settings.eraserRadiusNormalized*1000).toInt()}");Slider(settings.eraserRadiusNormalized,{onChange(settings.copy(eraserRadiusNormalized=it))},valueRange=.008f.. .08f)
            Row{RadioButton(settings.eraserMode==EraserMode.STROKE,{onChange(settings.copy(eraserMode=EraserMode.STROKE))});Text("笔画",Modifier.padding(top=12.dp));RadioButton(settings.eraserMode==EraserMode.AREA,{onChange(settings.copy(eraserMode=EraserMode.AREA))});Text("区域",Modifier.padding(top=12.dp))}
            Text("颜色");Row{PALETTE.forEach{color->TextButton({onChange(settings.copy(colorArgb=color))}){Text("●",color=Color(color))}}}
        }
    }
}

private val SHAPE_TOOLS=setOf(InkTool.LINE,InkTool.BEZIER,InkTool.ELLIPSE,InkTool.RECTANGLE)
private val PALETTE=listOf(0xFF1F5E66,0xFF111111,0xFFD32F2F,0xFF1565C0,0xFF7B1FA2)
private fun toolLabel(tool:InkTool)=when(tool){InkTool.SELECT->"选择";InkTool.PEN->"画笔";InkTool.ERASER->"橡皮";InkTool.LINE->"直线";InkTool.BEZIER->"贝塞尔";InkTool.ELLIPSE->"圆/椭圆";InkTool.RECTANGLE->"方形"}
