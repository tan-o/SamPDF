package com.samreader.app.ui.reflow

import com.samreader.app.data.SentenceEntity
import com.samreader.app.data.SentenceSpanKind
import com.samreader.app.document.SemanticTextRole
import com.samreader.app.document.SentenceSpanParser
import java.util.Locale

/**
 * Renders a [ReflowDocument] as one HTML page for the reading view. Every sentence is a
 * `span.s` with its original text in `span.o` and a translation slot `span.t`, so the page
 * script can select sentences, pick words and update translations in place. Inline math is
 * typeset by KaTeX; display equations are page crops served through [cropUrl].
 */
object ReflowHtml {
    fun render(
        document: ReflowDocument,
        translations: Map<String, String>,
        cropUrl: (PageCrop) -> String,
    ): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<link rel=\"stylesheet\" href=\"katex/katex.min.css\"><link rel=\"stylesheet\" href=\"reflow.css\">")
        append("</head><body><article id=\"doc\">")
        var openSection: String? = null
        fun section(name: String?) {
            if (openSection == name) return
            if (openSection != null) append("</section>")
            if (name != null) append("<section class=\"").append(name).append("\">")
            openSection = name
        }
        document.nodes.forEach { node ->
            section(
                when {
                    node is ReflowNode.Paragraph && node.role == SemanticTextRole.ABSTRACT -> "abstract"
                    node is ReflowNode.Paragraph && node.role == SemanticTextRole.REFERENCE -> "references"
                    node is ReflowNode.Paragraph && node.role == SemanticTextRole.CONTENTS -> "contents"
                    else -> null
                },
            )
            when (node) {
                is ReflowNode.Heading -> {
                    val tag = if (node.documentTitle) "h1" else "h2"
                    append('<').append(tag).append('>')
                    sentence(node.sentence, document, translations, cropUrl)
                    append("</").append(tag).append('>')
                }
                is ReflowNode.Paragraph -> {
                    append("<p").append(PARAGRAPH_CLASSES[node.role]?.let { " class=\"$it\"" }.orEmpty()).append('>')
                    node.sentences.forEachIndexed { index, sentence ->
                        if (index > 0) append(' ')
                        sentence(sentence, document, translations, cropUrl)
                    }
                    append("</p>")
                }
                is ReflowNode.Figure -> {
                    append("<figure>")
                    var captionOpen = false
                    node.parts.forEach { part ->
                        when (part) {
                            is FigurePart.Visual -> {
                                if (captionOpen) append("</figcaption>")
                                captionOpen = false
                                append("<img class=\"visual\" loading=\"lazy\" alt=\"\" style=\"aspect-ratio:")
                                    .append(part.crop.aspectRatio.css()).append("\" src=\"").append(cropUrl(part.crop).escape())
                                    .append("\" data-crop=\"").append(part.crop.encode()).append("\">")
                            }
                            is FigurePart.Caption -> {
                                if (captionOpen) append(' ') else append("<figcaption>")
                                captionOpen = true
                                sentence(part.sentence, document, translations, cropUrl)
                            }
                        }
                    }
                    if (captionOpen) append("</figcaption>")
                    append("</figure>")
                }
                is ReflowNode.Note -> {
                    append("<aside class=\"note\">")
                    node.sentences.forEachIndexed { index, sentence ->
                        if (index > 0) append(' ')
                        sentence(sentence, document, translations, cropUrl)
                    }
                    append("</aside>")
                }
            }
        }
        section(null)
        append("</article><script src=\"katex/katex.min.js\"></script><script src=\"reflow.js\"></script></body></html>")
    }

    private fun StringBuilder.sentence(
        sentence: SentenceEntity,
        document: ReflowDocument,
        translations: Map<String, String>,
        cropUrl: (PageCrop) -> String,
    ) {
        append("<span class=\"s\" id=\"s-").append(sentence.id).append("\"><span class=\"o\">")
        SentenceSpanParser.parse(sentence.id, sentence.displayText).forEach { span ->
            when (span.kind) {
                SentenceSpanKind.DISPLAY_FORMULA -> {
                    val crop = document.displayEquations[span.text]
                    if (crop != null) {
                        append("<span class=\"eq\"><img loading=\"lazy\" alt=\"").append(span.text.escape())
                            .append("\" style=\"width:").append(crop.widthEm.css()).append("em;aspect-ratio:")
                            .append(crop.aspectRatio.css()).append("\" src=\"").append(cropUrl(crop).escape()).append("\"></span>")
                    } else {
                        append("<span class=\"m d\" data-tex=\"").append(span.text.tex().escape()).append("\">")
                            .append(span.text.escape()).append("</span>")
                    }
                }
                SentenceSpanKind.INLINE_FORMULA -> append("<span class=\"m\" data-tex=\"").append(span.text.tex().escape())
                    .append("\">").append(span.text.escape()).append("</span>")
                else -> append(span.text.replace('\n', ' ').escape())
            }
        }
        append("</span><span class=\"t\">").append(translations[sentence.id].orEmpty().escape()).append("</span></span>")
    }

    private fun Float.css() = String.format(Locale.US, "%.3f", this)

    private fun String.tex() = removePrefix("\\[").removeSuffix("\\]").trim()

    private fun String.escape(): String = buildString(length) {
        this@escape.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    private val PARAGRAPH_CLASSES = mapOf(
        SemanticTextRole.AUTHOR to "authors",
        SemanticTextRole.HEADER to "meta",
        SemanticTextRole.REFERENCE to "ref",
        SemanticTextRole.CONTENTS to "entry",
    )
}
