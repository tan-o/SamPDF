package com.samreader.app.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfPageRenderer {
    suspend fun render(
        filePath: String,
        pageNumber: Int,
        widthPixels: Int = 1800,
        darkReading: Boolean = false,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(
                File(filePath),
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageNumber in 0 until renderer.pageCount)
                    renderer.openPage(pageNumber).use { page ->
                        val height = (widthPixels * page.height.toFloat() / page.width)
                            .roundToInt()
                            .coerceAtLeast(1)
                        val rendered = createBitmap(widthPixels, height, Bitmap.Config.ARGB_8888).also {
                            // PdfRenderer leaves unpainted paper transparent. An opaque white base
                            // prevents a dark Compose surface from becoming black paper with black text.
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        if (darkReading && isLightPaperPage(rendered)) rendered.toDarkReadingBitmap()
                        else rendered
                    }
                }
            }
        }

    private fun isLightPaperPage(bitmap: Bitmap): Boolean {
        val columns = 48
        val rows = (columns * bitmap.height.toFloat() / bitmap.width).roundToInt().coerceIn(48, 96)
        var light = 0
        var neutral = 0
        var total = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + .5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
            val y = ((row + .5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(x, y)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            if (luminance >= 205) light++
            if (maxOf(red, green, blue) - minOf(red, green, blue) <= 28) neutral++
            total++
        }
        return light * 100 >= total * 55 && neutral * 100 >= total * 78
    }

    private fun Bitmap.toDarkReadingBitmap(): Bitmap {
        val output = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val nightMatrix = ColorMatrix(floatArrayOf(
            -.90f, 0f, 0f, 0f, 245f,
            0f, -.90f, 0f, 0f, 245f,
            0f, 0f, -.90f, 0f, 245f,
            0f, 0f, 0f, 1f, 0f,
        ))
        Canvas(output).drawBitmap(
            this,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(nightMatrix)
            },
        )
        recycle()
        return output
    }
}
