package com.samreader.app.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormulaModelDeviceTest {
    @Test
    fun encoderAndAutoregressiveDecoderRunOnAndroid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(360, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText(
            "n = 2",
            28f,
            88f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f },
        )
        try {
            val method = Pix2TextFormulaModel::class.java.getDeclaredMethod(
                "recognizeBatch", Context::class.java, List::class.java,
            ).apply { isAccessible = true }
            val result = method.invoke(Pix2TextFormulaModel, context, listOf(bitmap)) as List<*>
            assertEquals(1, result.size)
        } finally {
            bitmap.recycle()
        }
    }
}
