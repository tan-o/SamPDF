package com.samreader.app.document

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import org.json.JSONObject

interface SentenceBoundaryScorer {
    val threshold: Float get() = .5f

    /** Returns the probability that a sentence ends after each UTF-16 character in [text]. */
    fun probabilities(text: String): FloatArray
}

/**
 * Local sentence-boundary inference using the official WtP BERT mini ONNX export.
 *
 * WtP consumes Unicode code points through eight deterministic hash functions. Predictions from
 * overlapping 512-code-point windows are averaged exactly as in the reference implementation.
 */
class WtpSentenceBoundaryModel(context: Context) : SentenceBoundaryScorer {
    private val appContext = context.applicationContext
    private val adapter by lazy { loadAdapter(appContext) }

    override val threshold: Float get() = adapter.threshold

    override fun probabilities(text: String): FloatArray {
        if (text.isEmpty()) return FloatArray(0)
        val encoded = encodeCodePoints(text)
        val starts = chunkStarts(encoded.codePoints.size)
        val sums = FloatArray(encoded.codePoints.size * adapter.coefficients.size)
        val counts = IntArray(encoded.codePoints.size)
        starts.chunked(BATCH_SIZE).forEach { batchStarts ->
            runBatch(encoded.codePoints, batchStarts).forEachIndexed { batchIndex, logits ->
                val start = batchStarts[batchIndex]
                val length = minOf(BLOCK_SIZE, encoded.codePoints.size - start)
                repeat(length) { offset ->
                    val sourceOffset = offset * adapter.coefficients.size
                    val targetOffset = (start + offset) * adapter.coefficients.size
                    repeat(adapter.coefficients.size) { label ->
                        sums[targetOffset + label] += logits[sourceOffset + label]
                    }
                    counts[start + offset]++
                }
            }
        }
        val utf16Probabilities = FloatArray(text.length)
        encoded.utf16EndIndices.forEachIndexed { codePointIndex, utf16Index ->
            val count = counts[codePointIndex].coerceAtLeast(1)
            val sourceOffset = codePointIndex * adapter.coefficients.size
            var adaptedLogit = adapter.intercept
            adapter.coefficients.forEachIndexed { label, coefficient ->
                adaptedLogit += coefficient * sums[sourceOffset + label] / count
            }
            utf16Probabilities[utf16Index] = sigmoid(adaptedLogit)
        }
        return utf16Probabilities
    }

    private fun runBatch(codePoints: IntArray, starts: List<Int>): List<FloatArray> {
        val batchSize = starts.size
        val hashes = ByteBuffer.allocateDirect(batchSize * BLOCK_SIZE * HASH_COUNT * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        val mask = ByteBuffer.allocateDirect(batchSize * BLOCK_SIZE * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        starts.forEach { start ->
            repeat(BLOCK_SIZE) { offset ->
                val codePointIndex = start + offset
                val present = codePointIndex < codePoints.size
                val codePoint = if (present) codePoints[codePointIndex].toLong() else 0L
                PRIMES.forEach { prime ->
                    hashes.put(if (present) ((codePoint + 1L) * prime) % HASH_BUCKETS else 0L)
                }
                mask.put(if (present) FLOAT16_ONE else 0)
            }
        }
        hashes.rewind()
        mask.rewind()
        OnnxTensor.createTensor(
            environment,
            hashes,
            longArrayOf(batchSize.toLong(), BLOCK_SIZE.toLong(), HASH_COUNT.toLong()),
        ).use { hashedIds ->
            OnnxTensor.createTensor(
                environment,
                mask,
                longArrayOf(batchSize.toLong(), BLOCK_SIZE.toLong()),
                OnnxJavaType.FLOAT16,
            ).use { attentionMask ->
                getSession(appContext).run(mapOf(
                    "attention_mask" to attentionMask,
                    "hashed_ids" to hashedIds,
                )).use { output ->
                    val logits = output.get("logits").orElseThrow {
                        IllegalStateException("WtP ONNX output 'logits' is missing")
                    } as OnnxTensor
                    val tensorInfo = logits.info as TensorInfo
                    val labelCount = tensorInfo.shape.last().toInt()
                    require(labelCount == adapter.coefficients.size) {
                        "WtP output has $labelCount labels, adapter expects ${adapter.coefficients.size}"
                    }
                    val valuesPerBatch = BLOCK_SIZE * labelCount
                    return when (tensorInfo.type) {
                        OnnxJavaType.FLOAT16 -> {
                            val values = logits.shortBuffer
                            List(batchSize) {
                                FloatArray(valuesPerBatch) { halfToFloat(values.get()) }
                            }
                        }
                        OnnxJavaType.FLOAT -> {
                            val values = logits.floatBuffer
                            List(batchSize) { FloatArray(valuesPerBatch) { values.get() } }
                        }
                        else -> error("Unsupported WtP logits type: ${tensorInfo.type}")
                    }
                }
            }
        }
    }

    private data class EncodedText(
        val codePoints: IntArray,
        val utf16EndIndices: IntArray,
    )

    private data class Adapter(
        val coefficients: FloatArray,
        val intercept: Float,
        val threshold: Float,
    )

    private fun encodeCodePoints(text: String): EncodedText {
        val codePointCount = text.codePointCount(0, text.length)
        val codePoints = IntArray(codePointCount)
        val utf16EndIndices = IntArray(codePointCount)
        var utf16Index = 0
        var codePointIndex = 0
        while (utf16Index < text.length) {
            val codePoint = text.codePointAt(utf16Index)
            val width = Character.charCount(codePoint)
            codePoints[codePointIndex] = codePoint
            utf16EndIndices[codePointIndex] = utf16Index + width - 1
            utf16Index += width
            codePointIndex++
        }
        return EncodedText(codePoints, utf16EndIndices)
    }

    private fun chunkStarts(length: Int): List<Int> {
        if (length <= BLOCK_SIZE) return listOf(0)
        val result = mutableListOf<Int>()
        var cursor = 0
        while (true) {
            var start = cursor
            val end = cursor + BLOCK_SIZE
            val done = end >= length
            if (done) start = (length - BLOCK_SIZE).coerceAtLeast(0)
            if (result.lastOrNull() != start) result += start
            if (done) break
            cursor += STRIDE
        }
        return result
    }

    companion object {
        private const val MODEL_ASSET = "models/wtp_bert_mini.onnx"
        private const val ADAPTER_ASSET = "models/wtp_en_ersatz_adapter.json"
        private const val BLOCK_SIZE = 512
        private const val STRIDE = 256
        private const val BATCH_SIZE = 4
        private const val HASH_COUNT = 8
        private const val HASH_BUCKETS = 8192L
        private const val FLOAT16_ONE: Short = 0x3c00
        private val PRIMES = longArrayOf(31, 43, 59, 61, 73, 97, 103, 113)
        private val environment = OrtEnvironment.getEnvironment()
        private val sessionLock = Any()
        @Volatile private var session: OrtSession? = null

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

        private fun loadAdapter(context: Context): Adapter {
            val root = context.assets.open(ADAPTER_ASSET).bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
            val values = root.getJSONArray("coefficients")
            return Adapter(
                coefficients = FloatArray(values.length()) { values.getDouble(it).toFloat() },
                intercept = root.getDouble("intercept").toFloat(),
                threshold = root.getDouble("threshold").toFloat(),
            )
        }

        private fun sigmoid(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

        private fun halfToFloat(value: Short): Float {
            val bits = value.toInt() and 0xffff
            val sign = (bits ushr 15) and 1
            var exponent = (bits ushr 10) and 0x1f
            var fraction = bits and 0x3ff
            val floatBits = when (exponent) {
                0 -> {
                    if (fraction == 0) sign shl 31 else {
                        exponent = 1
                        while ((fraction and 0x400) == 0) {
                            fraction = fraction shl 1
                            exponent--
                        }
                        fraction = fraction and 0x3ff
                        (sign shl 31) or ((exponent + 112) shl 23) or (fraction shl 13)
                    }
                }
                0x1f -> (sign shl 31) or 0x7f800000 or (fraction shl 13)
                else -> (sign shl 31) or ((exponent + 112) shl 23) or (fraction shl 13)
            }
            return Float.fromBits(floatBits)
        }
    }
}
