package com.samreader.app.ui.reflow

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.samreader.app.data.ReadingFont
import com.samreader.app.data.ReadingSettings
import com.samreader.app.data.ReadingTheme
import com.samreader.app.data.TapTarget
import com.samreader.app.document.PdfPageRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Asks the reading view to scroll to a sentence; [nonce] makes repeated requests distinct. */
data class ReflowScrollRequest(val sentenceId: String, val nonce: Long = System.nanoTime())

/**
 * The reading view: the parsed paper as one HTML page in a [WebView]. Page assets come from
 * `assets/reflow` and figure/equation crops are rendered from the PDF on request, both through
 * [WebViewAssetLoader]. Taps are reported back by the page script.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReflowReader(
    document: ReflowDocument,
    filePath: String,
    translations: Map<String, String>,
    style: ReadingSettings,
    systemDark: Boolean,
    selectedSentenceId: String?,
    scrollRequest: ReflowScrollRequest?,
    onSentence: (String?) -> Unit,
    onWord: (word: String, sentenceId: String) -> Unit,
    onFigure: (PageCrop) -> Unit,
    onTopSentence: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val theme = style.resolvedTheme(systemDark)
    val currentStyle by rememberUpdatedState(style.toJson(theme))
    val currentSelection by rememberUpdatedState(selectedSentenceId)
    val currentTranslations by rememberUpdatedState(translations)
    val callbacks by rememberUpdatedState(Callbacks(onSentence, onWord, onFigure, onTopSentence))
    var topSentence by remember { mutableStateOf(scrollRequest?.sentenceId) }
    var shownTranslations by remember { mutableStateOf(emptyMap<String, String>()) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            settings.setSupportZoom(false)
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler("/pdf/") { path -> cropResponse(filePath, path) }
                .build()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun initialState(): String = JSONObject()
                    .put("style", currentStyle)
                    .put("selected", currentSelection ?: JSONObject.NULL)
                    .put("anchor", topSentence ?: JSONObject.NULL)
                    .toString()

                @JavascriptInterface
                fun onSentence(id: String) = post { callbacks.onSentence(id.ifEmpty { null }) }

                @JavascriptInterface
                fun onWord(word: String, sentenceId: String) = post { callbacks.onWord(word, sentenceId) }

                @JavascriptInterface
                fun onFigure(crop: String) = post { PageCrop.decode(crop)?.let(callbacks.onFigure) }

                @JavascriptInterface
                fun onTopSentence(id: String) = post {
                    topSentence = id
                    callbacks.onTopSentence(id)
                }
            }, "SamReaderHost")
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }

    LaunchedEffect(theme) { webView.setBackgroundColor(theme.background.toArgb()) }
    LaunchedEffect(document) {
        val snapshot = currentTranslations
        shownTranslations = snapshot
        webView.loadDataWithBaseURL(PAGE_URL, ReflowHtml.render(document, snapshot, ::cropUrl), "text/html", "utf-8", null)
    }
    LaunchedEffect(style, theme) { webView.call("applyStyle", style.toJson(theme).toString()) }
    LaunchedEffect(selectedSentenceId) {
        webView.call("select", JSONObject.quote(selectedSentenceId.orEmpty()))
        if (selectedSentenceId == null) webView.call("clearWord")
    }
    LaunchedEffect(scrollRequest) {
        scrollRequest?.let { request -> webView.call("scrollToSentence", JSONObject.quote(request.sentenceId), "true") }
    }
    LaunchedEffect(translations) {
        val changed = translations.filter { (id, text) -> shownTranslations[id] != text }
        val removed = shownTranslations.keys - translations.keys
        if (changed.isEmpty() && removed.isEmpty()) return@LaunchedEffect
        val update = JSONObject()
        changed.forEach { (id, text) -> update.put(id, text) }
        removed.forEach { update.put(it, "") }
        webView.call("setTranslations", update.toString())
        shownTranslations = translations
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

private data class Callbacks(
    val onSentence: (String?) -> Unit,
    val onWord: (String, String) -> Unit,
    val onFigure: (PageCrop) -> Unit,
    val onTopSentence: (String) -> Unit,
)

/** A reading theme with "follow system" resolved; colours match `reflow.css`. */
enum class ResolvedReadingTheme(val key: String, val background: Color, val foreground: Color) {
    LIGHT("light", Color(0xFFFFFDF8), Color(0xFF1F2421)),
    SEPIA("sepia", Color(0xFFF4ECD8), Color(0xFF3B2F22)),
    DARK("dark", Color(0xFF141816), Color(0xFFDFE4DF)),
}

fun ReadingSettings.resolvedTheme(systemDark: Boolean) = when (theme) {
    ReadingTheme.SYSTEM -> if (systemDark) ResolvedReadingTheme.DARK else ResolvedReadingTheme.LIGHT
    ReadingTheme.LIGHT -> ResolvedReadingTheme.LIGHT
    ReadingTheme.SEPIA -> ResolvedReadingTheme.SEPIA
    ReadingTheme.DARK -> ResolvedReadingTheme.DARK
}

private fun ReadingSettings.toJson(theme: ResolvedReadingTheme) = JSONObject()
    .put("theme", theme.key)
    .put("tone", translationTone.name.lowercase())
    .put("font", if (font == ReadingFont.SANS) "sans" else "serif")
    .put("fontSize", fontSize.toDouble())
    .put("lineHeight", lineHeight.toDouble())
    .put("translationScale", translationScale.toDouble())
    .put("bilingual", showTranslation)
    .put("tap", if (tapTarget == TapTarget.WORD) "word" else "sentence")

private fun WebView.call(function: String, vararg arguments: String) =
    evaluateJavascript("window.SamReflow && SamReflow.$function(${arguments.joinToString(",")})", null)

private fun post(action: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(action)
}

private const val HOST = "https://appassets.androidplatform.net"
private const val PAGE_URL = "$HOST/assets/reflow/index.html"
private const val CROP_WIDTH_PIXELS = 1400

private fun cropUrl(crop: PageCrop) = "$HOST/pdf/${crop.encode()}.png"

/** Serves `/pdf/<page>_<l>_<t>_<r>_<b>.png` by rendering that region of the PDF page. */
private fun cropResponse(filePath: String, path: String): WebResourceResponse? {
    val crop = PageCrop.decode(Uri.decode(path).removeSuffix(".png")) ?: return null
    val png = runBlocking {
        val bitmap = PdfPageRenderer.renderRegion(filePath, crop.page, crop.rect, CROP_WIDTH_PIXELS)
        try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
    return WebResourceResponse("image/png", null, ByteArrayInputStream(png))
}
