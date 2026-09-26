package com.samreader.app.document

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.samreader.app.data.ParsingTuning
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Runs PP-DocLayoutV3 with ONNX Runtime; decoding lives in [LayoutPostProcessor]. */
object DocumentLayoutModel {
    const val MODEL_ID = "pp-doclayout-v3-fp32"
    private const val MODEL_ASSET = "models/pp_doclayout_v3_fp32.onnx"
    private const val INPUT_SIZE = 800
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionLock = Any()
    @Volatile private var session: OrtSession? = null

    fun detect(
        context: Context,
        bitmap: Bitmap,
        scoreThreshold: Float = ParsingTuning.DEFAULT_LAYOUT_CONFIDENCE,
    ): List<LayoutRegion> {
        val normalizedThreshold = ParsingTuning.normalizeLayoutConfidence(scoreThreshold)
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        return try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val imageBuffer = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(3) { channel ->
                val shift = 16 - channel * 8
                pixels.forEach { pixel -> imageBuffer.put(((pixel ushr shift) and 0xff) / 255f) }
            }
            imageBuffer.rewind()
            OnnxTensor.createTensor(
                environment,
                imageBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { input ->
                getSession(context).run(mapOf("pixel_values" to input)).use { output ->
                    LayoutPostProcessor.decode(
                        logits = (output[0] as OnnxTensor).floatBuffer,
                        boxes = (output[1] as OnnxTensor).floatBuffer,
                        masks = (output[2] as OnnxTensor).floatBuffer,
                        orderLogits = (output[3] as OnnxTensor).floatBuffer,
                        threshold = normalizedThreshold,
                        pageAspectRatio = bitmap.width.toFloat() / bitmap.height,
                    )
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun release() = synchronized(sessionLock) {
        session?.close()
        session = null
    }

    private fun getSession(context: Context): OrtSession = session ?: synchronized(sessionLock) {
        session ?: context.assets.openFd(MODEL_ASSET).use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                val model = channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
                OrtSession.SessionOptions().use { options ->
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.setCPUArenaAllocator(false)
                    options.setMemoryPatternOptimization(false)
                    options.setIntraOpNumThreads(4)
                    options.setInterOpNumThreads(1)
                    environment.createSession(model, options).also { session = it }
                }
            }
        }
    }
}
