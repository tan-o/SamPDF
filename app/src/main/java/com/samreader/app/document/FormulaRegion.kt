package com.samreader.app.document

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
