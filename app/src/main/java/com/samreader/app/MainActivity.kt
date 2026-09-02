package com.samreader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.samreader.app.ui.LibraryScreen
import com.samreader.app.ui.LibraryViewModel
import com.samreader.app.ui.ReaderScreen
import com.samreader.app.ui.ReaderViewModel
import com.samreader.app.ui.SettingsScreen
import com.samreader.app.ui.SettingsViewModel
import com.samreader.app.ui.NoteCanvasScreen
import com.samreader.app.ui.NoteCanvasViewModel
import com.samreader.app.ui.VocabularyScreen
import com.samreader.app.ui.VocabularyViewModel
import com.samreader.app.ui.theme.SamReaderTheme
import com.samreader.app.ui.viewModelFactory

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as SamReaderApplication).container
        setContent {
            SamReaderTheme { SamReaderApp(container) }
        }
    }
}

internal sealed interface AppRoute
internal data object LibraryKey : AppRoute
internal data class ReaderKey(val documentId: String) : AppRoute
internal data class SettingsKey(val documentId: String?) : AppRoute
internal data class NoteCanvasKey(val sentenceId: String) : AppRoute
internal data object VocabularyKey : AppRoute

internal object AppRouteCodec {
    fun encode(route: AppRoute): String = when (route) {
        LibraryKey -> "library"
        is ReaderKey -> "reader|${route.documentId}"
        is SettingsKey -> route.documentId?.let { "settings|$it" } ?: "settings"
        is NoteCanvasKey -> "note|${route.sentenceId}"
        VocabularyKey -> "vocabulary"
    }

    fun decode(encoded: String): AppRoute {
        val type = encoded.substringBefore('|')
        val argument = encoded.substringAfter('|', missingDelimiterValue = "")
        return when (type) {
            "library" -> LibraryKey
            "reader" -> ReaderKey(argument.requiredRouteArgument(type))
            "settings" -> SettingsKey(argument.ifBlank { null })
            "note" -> NoteCanvasKey(argument.requiredRouteArgument(type))
            "vocabulary" -> VocabularyKey
            else -> error("Unknown saved navigation route: $type")
        }
    }

    private fun String.requiredRouteArgument(route: String): String =
        also { require(it.isNotBlank()) { "Missing argument for saved navigation route: $route" } }
}

private val AppBackStackSaver = Saver<SnapshotStateList<AppRoute>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map(AppRouteCodec::encode)) },
    restore = { encoded ->
        mutableStateListOf<AppRoute>().apply {
            encoded.forEach { add(AppRouteCodec.decode(it)) }
        }
    },
)

@Composable
private fun SamReaderApp(container: AppContainer) {
    val backStack = rememberSaveable(saver = AppBackStackSaver) {
        mutableStateListOf<AppRoute>(LibraryKey)
    }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        transitionSpec = {
            (slideInHorizontally(tween(240)) { it / 5 } + fadeIn(tween(180))) togetherWith
                (slideOutHorizontally(tween(240)) { -it / 8 } + fadeOut(tween(160)))
        },
        popTransitionSpec = {
            (slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(160))) togetherWith
                (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(160)))
        },
        predictivePopTransitionSpec = { _ ->
            (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith
                (slideOutHorizontally { it / 5 } + fadeOut())
        },
        entryProvider = { key ->
            when (key) {
                LibraryKey -> NavEntry(key) {
                    val model: LibraryViewModel = viewModel(
                        factory = viewModelFactory { LibraryViewModel(container.documents) },
                    )
                    LibraryScreen(
                        viewModel = model,
                        onOpen = { id -> backStack.add(ReaderKey(id)) },
                        onSettings = { backStack.add(SettingsKey(null)) },
                    )
                }

                is ReaderKey -> NavEntry(key) {
                    val model: ReaderViewModel = viewModel(
                        key = "reader-${key.documentId}",
                        factory = viewModelFactory {
                            ReaderViewModel(
                                documentId = key.documentId,
                                repository = container.documents,
                                translations = container.translations,
                                settingsRepository = container.deepSeekSettings,
                                inkRepository = container.inkSettings,
                                parsingDebugRepository = container.parsingDebugSettings,
                            )
                        },
                    )
                    ReaderScreen(
                        viewModel = model,
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                        onSettings = { backStack.add(SettingsKey(key.documentId)) },
                        onOpenNote = { sentenceId -> backStack.add(NoteCanvasKey(sentenceId)) },
                        onVocabulary = { backStack.add(VocabularyKey) },
                    )
                }

                is SettingsKey -> NavEntry(key) {
                    val model: SettingsViewModel = viewModel(
                        key = "settings-${key.documentId ?: "global"}",
                        factory = viewModelFactory {
                            SettingsViewModel(
                                repository = container.deepSeekSettings,
                                indexingRepository = container.indexingSettings,
                                parsingDebugRepository = container.parsingDebugSettings,
                                documents = container.documents,
                                translations = container.translations,
                                currentDocumentId = key.documentId,
                            )
                        },
                    )
                    SettingsScreen(model) { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                }

                is NoteCanvasKey -> NavEntry(key) {
                    val model: NoteCanvasViewModel = viewModel(
                        key = "note-${key.sentenceId}",
                        factory = viewModelFactory { NoteCanvasViewModel(key.sentenceId, container.documents, container.inkSettings) },
                    )
                    NoteCanvasScreen(model) { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                }

                VocabularyKey -> NavEntry(key) {
                    val model: VocabularyViewModel = viewModel(factory = viewModelFactory { VocabularyViewModel(container.documents) })
                    VocabularyScreen(model) { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                }

            }
        },
    )
}
