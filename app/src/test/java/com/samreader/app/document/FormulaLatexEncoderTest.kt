package com.samreader.app.document

import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaLatexEncoderTest {
    @Test
    fun wrapsUnicodeFormulaAndConvertsMathSymbols() {
        assertEquals("\\[E = mc^{2} \\pm \\epsilon\\]", FormulaLatexEncoder.encode("E = mc² ± ε"))
    }

    @Test
    fun alreadyWrappedLatexIsStable() {
        assertEquals("\\[x^2\\]", FormulaLatexEncoder.encode("\\[x^2\\]"))
    }

    @Test
    fun normalizesModelWhitespaceAroundScriptsAndBraces() {
        assertEquals(
            "\\frac{a}{b_{0}}",
            FormulaLatexEncoder.normalize(" \\frac { a } { b _ { 0 } } "),
        )
    }

    @Test
    fun removesFontAndSizeCommandsButKeepsMathematicalStructure() {
        assertEquals(
            "\\int_{0}^{1} f(x) d x + \\frac{a}{b} + \\hat{x}",
            FormulaLatexEncoder.normalize(
                "\\displaystyle \\int _ { 0 } ^ { 1 } \\mathbf { f } ( x ) \\, " +
                    "\\mathrm { d } x + \\cfrac { a } { b } + \\hat { x }",
            ),
        )
    }

    @Test
    fun compactsCharactersInsideFontGroupsAndSplitDigits() {
        assertEquals(
            "R = 180 \\lambda_{0}, n_{eff} \\in R",
            FormulaLatexEncoder.normalize(
                "R = 1 8 0 \\lambda _ { 0 } , n _ { \\mathrm { e f f } } " +
                    "\\in \\mathbb { R }",
            ),
        )
    }

    @Test
    fun removesPhantomsAndKerningWithoutDroppingSemanticTokens() {
        assertEquals(
            "[\\partial \\varphi / {\\partial \\omega}]",
            FormulaLatexEncoder.normalize(
                "\\left [ \\partial \\varphi \\mathord { \\left / " +
                    "\\vphantom { \\partial \\omega } \\right . \\kern - " +
                    "\\nulldelimiterspace } { \\partial \\omega } \\right ]",
            ),
        )
    }

    @Test
    fun canonicalizesRealFormulaRecognizerOutput() {
        assertEquals(
            "p_{0} = 330 \\lambda_{0}, \\operatorname*{min}_{i} \\Delta t_{i}",
            FormulaLatexEncoder.normalize(
                "\\boldsymbol { p } _ { \\, 0 } = 3 3 0 \\lambda _ { 0 } , " +
                    "\\operatorname* { m i n } _ { i } \\Delta t _ { i }",
            ),
        )
    }
}
