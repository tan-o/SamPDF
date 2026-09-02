package com.samreader.app.document

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import org.json.JSONObject

internal object FormulaRegionType {
    const val INLINE = "INLINE"
    const val DISPLAY = "DISPLAY"
}

internal data class FormulaRegion(
    val type: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class RecognizedFormula(
    val region: FormulaRegion,
    val latex: String,
    val confidence: Float,
    val modelId: String,
    val imagePng: ByteArray,
)

internal data class FormulaPageRecognition(
    val regions: List<FormulaRegion>,
    val formulas: List<RecognizedFormula>,
)

/** Pix2Text 1.5 formula detection and image-to-LaTeX, executed entirely with Android ONNX Runtime. */
internal object Pix2TextFormulaModel {
    const val RECOGNIZER_ID = "pix2text-mfr-1.5-semantic"

    private const val ENCODER_ASSET = "models/pix2text_mfr_encoder_1_5.onnx"
    private const val DECODER_ASSET = "models/pix2text_mfr_decoder_1_5.onnx"
    private const val TOKENIZER_ASSET = "models/pix2text_mfr_tokenizer_1_5.json"
    private const val RECOGNIZER_SIZE = 384
    private const val VOCABULARY_SIZE = 1868
    private const val MAX_NEW_TOKENS = 256
    private const val BATCH_SIZE = 2
    private const val START_TOKEN = 1L
    private const val END_TOKEN = 2L

    private val environment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var vocabulary: Array<String>? = null

    fun recognizePage(
        context: Context,
        bitmap: Bitmap,
        layoutRegions: List<LayoutRegion>,
    ): FormulaPageRecognition = synchronized(lock) {
        val detected = layoutRegions.mapNotNull { region ->
            val type = when (region.label) {
                "inline_formula" -> FormulaRegionType.INLINE
                "display_formula" -> FormulaRegionType.DISPLAY
                else -> return@mapNotNull null
            }
            FormulaRegion(
                type = type,
                confidence = region.score,
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
            )
        }
        if (detected.isEmpty()) {
            return@synchronized FormulaPageRecognition(emptyList(), emptyList())
        }
        val crops = detected.map { crop(bitmap, it) }
        try {
            val recognized = crops.chunked(BATCH_SIZE).flatMap { recognizeBatch(context, it) }
            val formulas = detected.zip(crops).zip(recognized).mapNotNull { (formulaAndCrop, result) ->
                val (region, crop) = formulaAndCrop
                val latex = FormulaLatexEncoder.normalize(result.text)
                if (!isCompleteFormulaRecognition(result.terminated, latex)) {
                    Log.w(
                        "SamReaderFormula",
                        "Discarded incomplete formula decode at " +
                            "${region.left},${region.top},${region.right},${region.bottom}",
                    )
                    return@mapNotNull null
                }
                RecognizedFormula(
                    region = region,
                    latex = "\\[$latex\\]",
                    confidence = result.confidence,
                    modelId = RECOGNIZER_ID,
                    imagePng = crop.toPng(),
                )
            }
            FormulaPageRecognition(detected, formulas)
        } finally {
            crops.forEach(Bitmap::recycle)
        }
    }

    fun release() = synchronized(lock) {
        encoder?.close()
        decoder?.close()
        encoder = null
        decoder = null
    }

    private fun recognizeBatch(context: Context, crops: List<Bitmap>): List<FormulaResult> {
        val pixels = directFloatBuffer(crops.size * 3 * RECOGNIZER_SIZE * RECOGNIZER_SIZE)
        crops.forEach { crop ->
            val scaled = Bitmap.createScaledBitmap(crop, RECOGNIZER_SIZE, RECOGNIZER_SIZE, true)
            try {
                val colors = IntArray(RECOGNIZER_SIZE * RECOGNIZER_SIZE)
                scaled.getPixels(colors, 0, RECOGNIZER_SIZE, 0, 0, RECOGNIZER_SIZE, RECOGNIZER_SIZE)
                repeat(3) { channel ->
                    val shift = 16 - channel * 8
                    colors.forEach { color -> pixels.put((((color ushr shift) and 0xff) / 127.5f) - 1f) }
                }
            } finally {
                if (scaled !== crop) scaled.recycle()
            }
        }
        pixels.rewind()
        OnnxTensor.createTensor(
            environment, pixels,
            longArrayOf(crops.size.toLong(), 3, RECOGNIZER_SIZE.toLong(), RECOGNIZER_SIZE.toLong()),
        ).use { input ->
            encoder(context).run(mapOf("pixel_values" to input)).use { encoded ->
                val hidden = encoded[0] as OnnxTensor
                val sequences = List(crops.size) { mutableListOf(START_TOKEN) }
                val probabilities = List(crops.size) { mutableListOf<Float>() }
                val ended = BooleanArray(crops.size)
                for (step in 0 until MAX_NEW_TOKENS) {
                    if (ended.all { it }) break
                    val sequenceLength = sequences.first().size
                    val ids = ByteBuffer.allocateDirect(crops.size * sequenceLength * Long.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder()).asLongBuffer()
                    sequences.forEach { sequence -> sequence.forEach(ids::put) }
                    ids.rewind()
                    OnnxTensor.createTensor(
                        environment, ids, longArrayOf(crops.size.toLong(), sequenceLength.toLong()),
                    ).use { tokenTensor ->
                        decoder(context).run(mapOf(
                            "input_ids" to tokenTensor,
                            "encoder_hidden_states" to hidden,
                        )).use { decoded ->
                            val logits = (decoded[0] as OnnxTensor).floatBuffer
                            sequences.indices.forEach { batch ->
                                if (ended[batch]) {
                                    sequences[batch] += 0L
                                    return@forEach
                                }
                                val offset = (batch * sequenceLength + sequenceLength - 1) * VOCABULARY_SIZE
                                var best = 0
                                var maximum = Float.NEGATIVE_INFINITY
                                repeat(VOCABULARY_SIZE) { token ->
                                    val value = logits[offset + token]
                                    if (value > maximum) { maximum = value; best = token }
                                }
                                var sum = 0.0
                                repeat(VOCABULARY_SIZE) { token -> sum += exp((logits[offset + token] - maximum).toDouble()) }
                                probabilities[batch] += (1.0 / sum).toFloat()
                                if (best.toLong() == END_TOKEN) ended[batch] = true
                                sequences[batch] += best.toLong()
                            }
                        }
                    }
                }
                val tokens = vocabulary(context)
                return sequences.indices.map { index ->
                    val confidence = probabilities[index].takeIf(List<Float>::isNotEmpty)?.let { values ->
                        exp(values.sumOf { ln(max(it, 1e-8f).toDouble()) } / values.size).toFloat()
                    } ?: 0f
                    FormulaResult(decode(sequences[index], tokens), confidence, ended[index])
                }
            }
        }
    }

    private fun crop(bitmap: Bitmap, region: FormulaRegion): Bitmap {
        val formulaHeight = (region.bottom - region.top) * bitmap.height
        // Inline equations sit next to prose: a wide crop leaks letters into the formula decoder.
        // A small vertical pad keeps integral limits, accents and scripts intact without touching
        // the neighboring text lines. Display equations have whitespace and can use even padding.
        val horizontalRatio = if (region.type == FormulaRegionType.INLINE) .04f else .10f
        val verticalRatio = if (region.type == FormulaRegionType.INLINE) .08f else .10f
        val horizontalMargin = max(2, (formulaHeight * horizontalRatio).toInt())
        val verticalMargin = max(2, (formulaHeight * verticalRatio).toInt())
        val left = (region.left * bitmap.width).toInt().minus(horizontalMargin).coerceIn(0, bitmap.width - 1)
        val top = (region.top * bitmap.height).toInt().minus(verticalMargin).coerceIn(0, bitmap.height - 1)
        val right = (region.right * bitmap.width).toInt().plus(horizontalMargin).coerceIn(left + 1, bitmap.width)
        val bottom = (region.bottom * bitmap.height).toInt().plus(verticalMargin).coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun vocabulary(context: Context): Array<String> = vocabulary ?: synchronized(lock) {
        vocabulary ?: context.assets.open(TOKENIZER_ASSET).bufferedReader().use { reader ->
            val vocab = JSONObject(reader.readText()).getJSONObject("model").getJSONObject("vocab")
            Array(VOCABULARY_SIZE) { "" }.also { tokens ->
                vocab.keys().forEach { token -> tokens[vocab.getInt(token)] = token }
            }.also { vocabulary = it }
        }
    }

    private fun decode(sequence: List<Long>, tokens: Array<String>): String {
        val encoded = buildString {
            sequence.filter { it > 4 && it < tokens.size }.forEach { append(tokens[it.toInt()]) }
        }
        val byteValues = byteDecoder()
        val output = ByteArrayOutputStream(encoded.length)
        encoded.forEach { character -> byteValues[character]?.let(output::write) }
        return output.toByteArray().toString(Charsets.UTF_8).trim()
    }

    private fun byteDecoder(): Map<Char, Int> {
        val bytes = (33..126).toMutableList().apply { addAll(161..172); addAll(174..255) }
        val characters = bytes.toMutableList()
        var extra = 0
        repeat(256) { value ->
            if (value !in bytes) { bytes += value; characters += 256 + extra++ }
        }
        return bytes.indices.associate { index -> characters[index].toChar() to bytes[index] }
    }

    private fun encoder(context: Context) = encoder ?: session(context, ENCODER_ASSET).also { encoder = it }
    private fun decoder(context: Context) = decoder ?: session(context, DECODER_ASSET).also { decoder = it }

    private fun session(context: Context, assetName: String): OrtSession = context.assets.openFd(assetName).use { asset ->
        FileInputStream(asset.fileDescriptor).channel.use { channel ->
            val model = channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                environment.createSession(model, options)
            }
        }
    }

    private fun directFloatBuffer(size: Int) = ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use { output ->
        compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
}

internal fun isCompleteFormulaRecognition(terminated: Boolean, latex: String): Boolean =
    terminated && latex.isNotBlank()

private data class FormulaResult(
    val text: String,
    val confidence: Float,
    val terminated: Boolean,
)
