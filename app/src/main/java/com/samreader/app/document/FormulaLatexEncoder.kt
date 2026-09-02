package com.samreader.app.document

/** Serializes the text layer of a detected equation region as display LaTeX. */
object FormulaLatexEncoder {
    private val commands = mapOf(
        'α' to "\\alpha", 'β' to "\\beta", 'γ' to "\\gamma", 'δ' to "\\delta",
        'ε' to "\\epsilon", 'θ' to "\\theta", 'λ' to "\\lambda", 'μ' to "\\mu",
        'π' to "\\pi", 'ρ' to "\\rho", 'σ' to "\\sigma", 'τ' to "\\tau",
        'φ' to "\\phi", 'ψ' to "\\psi", 'ω' to "\\omega", 'Γ' to "\\Gamma",
        'Δ' to "\\Delta", 'Θ' to "\\Theta", 'Λ' to "\\Lambda", 'Π' to "\\Pi",
        'Σ' to "\\Sigma", 'Φ' to "\\Phi", 'Ψ' to "\\Psi", 'Ω' to "\\Omega",
        '×' to "\\times", '·' to "\\cdot", '±' to "\\pm", '∓' to "\\mp",
        '≤' to "\\le", '≥' to "\\ge", '≠' to "\\ne", '≈' to "\\approx",
        '∞' to "\\infty", '∑' to "\\sum", '∏' to "\\prod", '∫' to "\\int",
        '√' to "\\sqrt{}", '∂' to "\\partial", '∇' to "\\nabla",
        '→' to "\\to", '←' to "\\leftarrow", '↔' to "\\leftrightarrow",
    )
    private val superscripts = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
        '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
    )
    private val subscripts = mapOf(
        '₀' to '0', '₁' to '1', '₂' to '2', '₃' to '3', '₄' to '4',
        '₅' to '5', '₆' to '6', '₇' to '7', '₈' to '8', '₉' to '9',
    )

    fun encode(raw: String): String {
        val source = raw.trim().replace(Regex("\\s+"), " ")
        if (source.isEmpty()) return ""
        if (source.startsWith("\\[") && source.endsWith("\\]")) return source
        val body = buildString {
            var index = 0
            while (index < source.length) {
                val character = source[index]
                val raised = superscripts[character]
                val lowered = subscripts[character]
                when {
                    raised != null -> index = appendScript(source, index, superscripts, "^")
                    lowered != null -> index = appendScript(source, index, subscripts, "_")
                    commands[character] != null -> {
                        append(commands.getValue(character)).append(' ')
                        index++
                    }
                    character == '%' || character == '#' || character == '&' -> {
                        append('\\').append(character)
                        index++
                    }
                    else -> {
                        append(character)
                        index++
                    }
                }
            }
        }.trim().replace(Regex("\\s+"), " ")
        return "\\[$body\\]"
    }

    /**
     * Produces semantic LaTeX rather than attempting to reproduce the source font. Formula OCR
     * models commonly emit a long tail of font and spacing commands; keeping those tokens makes
     * the result noisier without changing the mathematical structure used by this application.
     */
    fun normalize(raw: String): String = simplifyPresentation(raw.trim())
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
        .replace(Regex("(?<=\\d)\\s*\\.\\s*(?=\\d)"), ".")
        .replace(Regex("\\s*([_^])\\s*"), "$1")
        .replace(Regex("\\s+([\\(\\[])"), "$1")
        .replace(Regex("([\\(\\[])\\s+"), "$1")
        .replace(Regex("\\s+([\\)\\]])"), "$1")
        .replace(Regex("\\s+([,.;:])"), "$1")
        .replace(Regex("\\{\\s+"), "{")
        .replace(Regex("\\s+\\}"), "}")
        .replace(Regex("\\\\([A-Za-z]+) (?=\\{)")) { "\\${it.groupValues[1]}" }
        .replace("} {", "}{")
        .trim()

    private fun simplifyPresentation(source: String): String = buildString {
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '\\') {
                append(source[cursor++])
                continue
            }
            val commandStart = cursor
            cursor++
            if (cursor >= source.length) {
                append('\\')
                break
            }
            if (!source[cursor].isLetter()) {
                val symbol = source[cursor++]
                if (symbol !in PRESENTATION_SPACING_SYMBOLS) append('\\').append(symbol)
                continue
            }
            val nameStart = cursor
            while (cursor < source.length && source[cursor].isLetter()) cursor++
            val command = source.substring(nameStart, cursor)
            when {
                command in FRACTION_COMMANDS -> append("\\frac")
                command in DROP_DIMENSION_COMMANDS -> cursor = skipDimension(source, cursor)
                command in FONT_DECLARATIONS || command in PRESENTATION_COMMANDS -> Unit
                command in FONT_GROUP_COMMANDS || command in UNWRAP_GROUP_COMMANDS -> {
                    cursor = skipWhitespace(source, cursor)
                    if (cursor < source.length && source[cursor] == '{') {
                        val end = matchingBrace(source, cursor)
                        if (end > cursor) {
                            val content = simplifyPresentation(source.substring(cursor + 1, end))
                            append(if (command in FONT_GROUP_COMMANDS) compactStyledContent(content) else content)
                            cursor = end + 1
                        }
                    }
                }
                command in COMPACT_GROUP_COMMANDS -> {
                    append('\\').append(command)
                    if (cursor < source.length && source[cursor] == '*') append(source[cursor++])
                    cursor = skipWhitespace(source, cursor)
                    if (cursor < source.length && source[cursor] == '{') {
                        val end = matchingBrace(source, cursor)
                        if (end > cursor) {
                            val content = simplifyPresentation(source.substring(cursor + 1, end))
                            append('{').append(compactStyledContent(content)).append('}')
                            cursor = end + 1
                        }
                    }
                }
                command in DROP_GROUP_COMMANDS -> {
                    cursor = skipWhitespace(source, cursor)
                    if (cursor < source.length && source[cursor] == '{') {
                        val end = matchingBrace(source, cursor)
                        if (end > cursor) cursor = end + 1
                    }
                }
                command == "left" || command == "right" -> {
                    cursor = skipWhitespace(source, cursor)
                    if (cursor < source.length && source[cursor] == '.') cursor++
                }
                else -> append(source, commandStart, cursor)
            }
        }
    }

    private fun compactStyledContent(source: String): String {
        val pieces = source.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return if (pieces.size > 1 && pieces.all { it.length == 1 && it[0] != '\\' }) {
            pieces.joinToString("")
        } else {
            pieces.joinToString(" ")
        }
    }

    private fun skipWhitespace(source: String, start: Int): Int {
        var cursor = start
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun matchingBrace(source: String, start: Int): Int {
        var depth = 0
        var cursor = start
        while (cursor < source.length) {
            when (source[cursor]) {
                '\\' -> cursor++
                '{' -> depth++
                '}' -> if (--depth == 0) return cursor
            }
            cursor++
        }
        return -1
    }

    private fun skipDimension(source: String, start: Int): Int {
        var cursor = skipWhitespace(source, start)
        if (cursor < source.length && source[cursor] in "+-") cursor++
        cursor = skipWhitespace(source, cursor)
        if (cursor < source.length && source[cursor] == '\\') {
            cursor++
            while (cursor < source.length && source[cursor].isLetter()) cursor++
            return cursor
        }
        while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '.')) cursor++
        return cursor
    }

    private fun StringBuilder.appendScript(
        source: String,
        start: Int,
        alphabet: Map<Char, Char>,
        operator: String,
    ): Int {
        var cursor = start
        append(operator).append('{')
        while (cursor < source.length) {
            val mapped = alphabet[source[cursor]] ?: break
            append(mapped)
            cursor++
        }
        append('}')
        return cursor
    }

    private val FONT_GROUP_COMMANDS = setOf(
        "mathrm", "textrm", "textnormal", "textup", "textit", "textsl", "textsc",
        "textsf", "texttt", "textbf", "mathbf", "boldsymbol", "mathit", "mathsf",
        "mathtt", "mathnormal", "mathbb", "mathcal", "mathscr", "mathfrak", "bm", "pmb",
    )
    private val FONT_DECLARATIONS = setOf(
        "rm", "bf", "it", "cal", "sf", "tt", "boldmath", "unboldmath",
    )
    private val PRESENTATION_COMMANDS = setOf(
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
        "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
        "biggl", "biggr", "Biggl", "Biggr", "quad", "qquad", "enspace",
        "thinspace", "medspace", "thickspace", "negthinspace", "negmedspace",
        "negthickspace", "nulldelimiterspace",
    )
    private val DROP_DIMENSION_COMMANDS = setOf("kern", "mkern")
    private val PRESENTATION_SPACING_SYMBOLS = setOf('!', ',', ';', ':', ' ')
    private val FRACTION_COMMANDS = setOf("dfrac", "tfrac", "cfrac")
    private val UNWRAP_GROUP_COMMANDS = setOf(
        "mathord", "mathrel", "mathbin", "mathinner", "mathopen", "mathclose", "ensuremath",
    )
    private val COMPACT_GROUP_COMMANDS = setOf("operatorname", "text", "mbox")
    private val DROP_GROUP_COMMANDS = setOf("phantom", "hphantom", "vphantom", "hspace", "vspace")
}
