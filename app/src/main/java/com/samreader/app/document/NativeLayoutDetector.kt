package com.samreader.app.document

internal object NativeLayoutDetector {
    init { System.loadLibrary("samreader_layout") }

    external fun filterRegions(regions: FloatArray): FloatArray

    external fun extractPdfText(path: String): String
}
