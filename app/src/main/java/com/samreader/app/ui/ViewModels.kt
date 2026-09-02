package com.samreader.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import com.samreader.app.data.*
import java.util.Locale
import java.util.UUID
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: DocumentRepository) : ViewModel() {
    val documents = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashedDocuments = repository.trashedDocuments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders = repository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val documentTags = repository.documentTags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun importPdf(uri: Uri, onImported: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.importPdf(uri) }.onSuccess { result ->
            if (result.restoredFromTrash) _message.value = "已从回收站恢复《${result.title}》"
            onImported(result.documentId)
        }
            .onFailure { _message.value = it.message ?: "导入失败" }
    }
    fun deletePermanently(id: String) = viewModelScope.launch {
        runCatching { repository.deleteDocument(id) }.onFailure { _message.value = it.message ?: "删除失败" }
    }
    fun trash(id: String) = viewModelScope.launch { repository.trashDocument(id) }
    fun restore(id: String) = viewModelScope.launch {
        runCatching { repository.restoreDocument(id) }.onSuccess { _message.value = "已恢复到原位置" }
            .onFailure { _message.value = it.message ?: "恢复失败" }
    }
    fun restoreDocuments(ids: List<String>) = viewModelScope.launch {
        runCatching { repository.restoreDocuments(ids) }.onSuccess { _message.value = "已恢复 ${ids.size} 篇论文" }
            .onFailure { _message.value = it.message ?: "恢复失败" }
    }
    fun deletePermanently(ids: List<String>) = viewModelScope.launch {
        runCatching { repository.deleteDocuments(ids) }.onFailure { _message.value = it.message ?: "删除失败" }
    }
    fun trashDocuments(ids: List<String>) = viewModelScope.launch { repository.trashDocuments(ids) }
    fun createFolder(name: String) = viewModelScope.launch {
        runCatching { repository.createFolder(name) }.onFailure { _message.value = it.message }
    }
    fun organize(id: String, folderId: String?, tags: List<String>) = viewModelScope.launch { repository.organizeDocument(id, folderId, tags) }
    fun moveDocuments(ids: List<String>, folderId: String?) = viewModelScope.launch {
        runCatching { repository.moveDocuments(ids, folderId) }.onFailure { _message.value = it.message }
    }
    fun addTags(ids: List<String>, tags: List<String>) = viewModelScope.launch {
        runCatching { repository.addTagsToDocuments(ids, tags) }.onFailure { _message.value = it.message }
    }
    fun reparse(id: String, aiContextEnabled: Boolean) = viewModelScope.launch {
        runCatching { repository.reparseDocument(id, aiContextEnabled) }.onSuccess {
            _message.value = if (aiContextEnabled) "已开始本地解析，完成后将进行 AI 上下文解析" else "已开始仅本地解析"
        }
            .onFailure { _message.value = it.message ?: "重新解析失败" }
    }
    fun consumeMessage() { _message.value = null }
}

sealed interface TranslationState {
    data object Hidden : TranslationState
    data object Loading : TranslationState
    data class Ready(val result: TranslationResult) : TranslationState
    data class Failed(val message: String) : TranslationState
}

class ReaderViewModel(
    val documentId: String,
    private val repository: DocumentRepository,
    private val translations: TranslationRepository,
    settingsRepository: DeepSeekSettingsRepository,
    private val inkRepository: InkSettingsRepository,
    private val parsingDebugRepository: ParsingDebugSettingsRepository,
) : ViewModel() {
    val settings = settingsRepository.settings
    val inkSettings = inkRepository.settings
    val parsingDebugSettings = parsingDebugRepository.settings
    val layoutConfidence = parsingDebugRepository.documentLayoutConfidence(documentId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ParsingTuning.DEFAULT_LAYOUT_CONFIDENCE,
        )
    val document = repository.observeDocument(documentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sentences = repository.observeDocumentSentences(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val aiCorrectionReviews = repository.observeAiCorrectionReviews(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sentenceIdsWithNotes = repository.observeSentenceIdsWithNotes(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _pageNumber = MutableStateFlow(0)
    val pageNumber = _pageNumber.asStateFlow()
    private val _translation = MutableStateFlow<TranslationState>(TranslationState.Hidden)
    val translation = _translation.asStateFlow()
    private val _selectedSentence = MutableStateFlow<SentenceEntity?>(null)
    val selectedSentence = _selectedSentence.asStateFlow()
    private val _selectedSentenceIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSentenceIds = _selectedSentenceIds.asStateFlow()
    private val _selectionAnchorPage = MutableStateFlow<Int?>(null)
    val selectionAnchorPage = _selectionAnchorPage.asStateFlow()
    private var translationJob: Job? = null

    init {
        viewModelScope.launch { repository.markOpened(documentId) }
        viewModelScope.launch {
            document.filterNotNull().collect { doc ->
                if (doc.status == DocumentStatus.READY && doc.aiContextStatus == AiContextStatus.PENDING && repository.beginAiContext(doc.id)) {
                    try {
                        val result = translations.analyzeDocument(doc.title, repository.documentSentences(doc.id))
                        repository.finishAiContext(doc.id, result)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.e("SamReaderAI", "Document analysis failed: ${error.message}", error)
                        repository.failAiContext(doc.id, error.message ?: "AI 解析失败")
                    }
                }
            }
        }
    }

    fun strokes(page: Int) = repository.observeStrokes(documentId, page)
    fun layoutBlocks(page: Int) = repository.observePageLayoutBlocks(documentId, page)
    fun debugEvidence(page: Int) = repository.observePageDebugEvidence(documentId, page)
    fun noteStrokes(sentenceId: String) = repository.observeSentenceNoteStrokes(sentenceId)
    fun setPage(page: Int) {
        _pageNumber.value = page.coerceIn(0, ((document.value?.pageCount ?: 1) - 1).coerceAtLeast(0))
    }
    fun selectSentence(sentence: SentenceEntity, page: Int) {
        translationJob?.cancel()
        _selectedSentenceIds.value = emptySet()
        _selectedSentence.value = sentence
        _selectionAnchorPage.value = page
        _translation.value = TranslationState.Hidden
        translationJob = viewModelScope.launch {
            val cached = translations.cachedTranslation(sentence) ?: return@launch
            if (_selectedSentence.value?.id == sentence.id) {
                _translation.value = TranslationState.Ready(cached)
            }
        }
    }
    fun beginSentenceSelection(sentence: SentenceEntity, page: Int) {
        translationJob?.cancel()
        _selectedSentence.value = null
        _selectedSentenceIds.value = setOf(sentence.id)
        _selectionAnchorPage.value = page
        _translation.value = TranslationState.Hidden
    }
    fun toggleSentenceSelection(sentence: SentenceEntity, page: Int) {
        val next = _selectedSentenceIds.value.toggle(sentence.id)
        _selectedSentenceIds.value = next
        _selectionAnchorPage.value = page.takeIf { next.isNotEmpty() }
        if (next.isEmpty()) _translation.value = TranslationState.Hidden
    }
    fun selectAllSentencesOnPage(page: Int) {
        _selectedSentenceIds.value = sentences.value.filter { it.decodedRegions(page).isNotEmpty() }.mapTo(mutableSetOf()) { it.id }
        _selectionAnchorPage.value = page
    }
    fun selectedSentenceText(): String = selectedSentences().joinToString("\n") { it.displayText }
    fun translateSentenceSelection() {
        val selected = selectedSentences()
        if (selected.isEmpty()) return
        translationJob?.cancel()
        _translation.value = TranslationState.Loading
        translationJob = viewModelScope.launch {
            try {
                _translation.value = TranslationState.Ready(
                    translations.translateSelection(selected, document.value?.aiContextSummary.orEmpty()),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _translation.value = TranslationState.Failed(error.message ?: "翻译不可用")
            }
        }
    }
    private fun selectedSentences(): List<SentenceEntity> {
        val ids = _selectedSentenceIds.value
        return sentences.value.filter { it.id in ids }
    }
    fun translateSelected() {
        val sentence = _selectedSentence.value ?: return
        translationJob?.cancel()
        _translation.value = TranslationState.Loading
        translationJob = viewModelScope.launch {
            val related = repository.translationContext(sentence)
            try {
                val result = translations.translate(sentence, document.value?.aiContextSummary.orEmpty(), related)
                if (_selectedSentence.value?.id == sentence.id) _translation.value = TranslationState.Ready(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (_selectedSentence.value?.id == sentence.id) {
                    _translation.value = TranslationState.Failed(error.message ?: "翻译不可用")
                }
            }
        }
    }
    fun dismissSentence() {
        translationJob?.cancel()
        _selectedSentence.value = null
        _selectedSentenceIds.value = emptySet()
        _selectionAnchorPage.value = null
        _translation.value = TranslationState.Hidden
    }
    fun retryTranslation() = if (_selectedSentenceIds.value.isNotEmpty()) translateSentenceSelection() else translateSelected()
    fun retryAiContext() = viewModelScope.launch { repository.setAiContextRequested(documentId, true) }
    fun startFullTranslation() = viewModelScope.launch { repository.startFullTranslation(documentId) }
    fun resolveAiCorrectionReview(review: AiCorrectionReviewEntity, accept: Boolean) = viewModelScope.launch {
        repository.resolveAiCorrectionReview(review, accept)
    }
    fun chooseAiContext(enabled: Boolean) = viewModelScope.launch {
        repository.setAiContextRequested(documentId, enabled)
    }
    fun retryLocalIndex() = viewModelScope.launch { repository.retryLocalIndex(documentId) }
    fun pauseIndex() = viewModelScope.launch { repository.pauseIndex(documentId) }
    fun resumeIndex() = viewModelScope.launch { repository.resumeIndex(documentId) }
    fun cancelIndex() = viewModelScope.launch { repository.cancelIndex(documentId) }
    fun correctSentence(sentence: SentenceEntity, text: String) = viewModelScope.launch {
        repository.correctSentence(sentence, text)
        _selectedSentence.value = sentence.copy(
            correctedText = text.trim().takeUnless { it == sentence.originalText || it.isEmpty() },
        )
        translationJob?.cancel()
        _translation.value = TranslationState.Hidden
    }
    fun addVocabulary(word: String, note: String, sentenceId: String?) = viewModelScope.launch {
        repository.addVocabulary(word, note, sentenceId)
    }
    fun updateInk(value: InkSettings) = viewModelScope.launch { inkRepository.update(value) }
    fun addStroke(page: Int, commit: InkCommit) {
        if (commit.points.size < 2) return
        viewModelScope.launch {
            repository.addStroke(
                AnnotationStrokeEntity(
                    commit.id, documentId, page, encodePoints(commit.points), commit.settings.colorArgb,
                    commit.settings.widthNormalized, commit.settings.pressureEnabled, commit.tool.name,
                    encodePoints(commit.controlPoints), System.currentTimeMillis(),
                ),
            )
        }
    }
    fun updateStrokes(page: Int, strokes: List<RenderStroke>) = viewModelScope.launch {
        repository.updateStrokes(strokes.map { stroke -> AnnotationStrokeEntity(
            stroke.id, documentId, page, encodePoints(stroke.points), stroke.color.toArgb().toLong() and 0xFFFFFFFFL,
            stroke.widthNormalized, stroke.pressureEnabled, stroke.tool.name, encodePoints(stroke.controlPoints), System.currentTimeMillis(),
        ) })
    }
    fun undoStroke(page: Int) = viewModelScope.launch { repository.undoStroke(documentId, page) }
    fun deleteStroke(id: String) = viewModelScope.launch { repository.deleteStroke(id) }
    fun areaErase(page: Int, deletedIds: List<String>, fragments: List<RenderStroke>) = viewModelScope.launch {
        repository.replaceStrokes(deletedIds, fragments.map { fragment ->
            AnnotationStrokeEntity(
                UUID.randomUUID().toString(), documentId, page, encodePoints(fragment.points), fragment.color.toArgb().toLong() and 0xFFFFFFFFL,
                fragment.widthNormalized, fragment.pressureEnabled, fragment.tool.name, encodePoints(fragment.controlPoints), System.currentTimeMillis(),
            )
        })
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

data class SettingsUiState(
    val settings: DeepSeekSettings,
    val indexing: IndexingSettings = IndexingSettings(),
    val parsingDebug: ParsingDebugSettings = ParsingDebugSettings(),
    val currentDocumentId: String? = null,
    val layoutConfidence: Float = ParsingTuning.DEFAULT_LAYOUT_CONFIDENCE,
    val balance: DeepSeekBalance? = null,
    val models: List<String> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val repository: DeepSeekSettingsRepository,
    private val indexingRepository: IndexingSettingsRepository,
    private val parsingDebugRepository: ParsingDebugSettingsRepository,
    private val documents: DocumentRepository,
    private val translations: TranslationRepository,
    private val currentDocumentId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(
        settings = repository.settings.value,
        currentDocumentId = currentDocumentId,
    ))
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch { repository.settings.collect { _state.value = _state.value.copy(settings = it) } }
        viewModelScope.launch { indexingRepository.settings.collect { _state.value = _state.value.copy(indexing = it) } }
        viewModelScope.launch { parsingDebugRepository.settings.collect { value ->
            _state.value = _state.value.copy(parsingDebug = value)
        } }
        currentDocumentId?.let { documentId ->
            viewModelScope.launch {
                parsingDebugRepository.documentLayoutConfidence(documentId).collect { value ->
                    _state.value = _state.value.copy(layoutConfidence = value)
                }
            }
        }
    }
    fun save(
        apiKey: String, model: String, prompt: String, action: SpenButtonAction,
        aiCorrectionEnabled: Boolean, aiCorrectionMaxChangeRatio: Float,
        background: Boolean, notificationProgress: Boolean,
        debugOverlay: Boolean, layoutConfidence: Float,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.save(
                    apiKey, model, prompt, action,
                    aiCorrectionEnabled, aiCorrectionMaxChangeRatio,
                )
                indexingRepository.update(IndexingSettings(background, notificationProgress))
                parsingDebugRepository.updateOverlay(debugOverlay)
                currentDocumentId?.let { documentId ->
                    parsingDebugRepository.updateDocumentLayoutConfidence(documentId, layoutConfidence)
                }
            }
                .onSuccess { _state.value = _state.value.copy(message = "设置已保存") }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "保存失败") }
        }
    }
    fun applyLayoutConfidenceAndReparse(value: Float) {
        val documentId = currentDocumentId ?: return
        viewModelScope.launch {
            runCatching {
                parsingDebugRepository.updateDocumentLayoutConfidence(documentId, value)
                documents.reparseDocument(documentId, aiContextEnabled = false)
            }
                .onSuccess {
                    _state.value = _state.value.copy(message = "已按新阈值开始本地重析")
                }
                .onFailure {
                    _state.value = _state.value.copy(message = it.message ?: "应用阈值失败")
                }
        }
    }
    fun setDebugOverlay(enabled: Boolean) = viewModelScope.launch {
        parsingDebugRepository.updateOverlay(enabled)
    }
    fun clearApiKey() = viewModelScope.launch { repository.clearApiKey(); _state.value = _state.value.copy(balance = null, models = emptyList()) }
    fun refreshRemote() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            runCatching { translations.models() to translations.balance() }
                .onSuccess { _state.value = _state.value.copy(models = it.first, balance = it.second, busy = false) }
                .onFailure { _state.value = _state.value.copy(busy = false, message = it.message ?: "DeepSeek 查询失败") }
        }
    }
}

class NoteCanvasViewModel(
    val sentenceId: String,
    private val repository: DocumentRepository,
    private val inkRepository: InkSettingsRepository,
) : ViewModel() {
    val sentence = repository.observeSentence(sentenceId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val strokes = repository.observeSentenceNoteStrokes(sentenceId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val inkSettings = inkRepository.settings
    fun updateInk(value: InkSettings) = viewModelScope.launch { inkRepository.update(value) }
    fun addStroke(commit: InkCommit) {
        if (commit.points.size < 2) return
        viewModelScope.launch {
            repository.addSentenceNoteStroke(
                SentenceNoteStrokeEntity(
                    commit.id, sentenceId, encodePoints(commit.points), commit.settings.colorArgb,
                    commit.settings.widthNormalized, commit.settings.pressureEnabled, commit.tool.name,
                    encodePoints(commit.controlPoints), System.currentTimeMillis(),
                ),
            )
        }
    }
    fun updateStrokes(strokes: List<RenderStroke>) = viewModelScope.launch {
        repository.updateSentenceNoteStrokes(strokes.map { stroke -> SentenceNoteStrokeEntity(
            stroke.id, sentenceId, encodePoints(stroke.points), stroke.color.toArgb().toLong() and 0xFFFFFFFFL,
            stroke.widthNormalized, stroke.pressureEnabled, stroke.tool.name, encodePoints(stroke.controlPoints), System.currentTimeMillis(),
        ) })
    }
    fun deleteStroke(id: String) = viewModelScope.launch { repository.deleteSentenceNoteStroke(id) }
    fun areaErase(deletedIds: List<String>, fragments: List<RenderStroke>) = viewModelScope.launch {
        repository.replaceSentenceNoteStrokes(deletedIds, fragments.map { fragment ->
            SentenceNoteStrokeEntity(
                UUID.randomUUID().toString(), sentenceId, encodePoints(fragment.points), fragment.color.toArgb().toLong() and 0xFFFFFFFFL,
                fragment.widthNormalized, fragment.pressureEnabled, fragment.tool.name, encodePoints(fragment.controlPoints), System.currentTimeMillis(),
            )
        })
    }
    fun undo() = viewModelScope.launch { repository.undoSentenceNoteStroke(sentenceId) }
    fun clear() = viewModelScope.launch { repository.clearSentenceNote(sentenceId) }
}

class VocabularyViewModel(private val repository: DocumentRepository) : ViewModel() {
    val items = repository.vocabulary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun delete(item: VocabularyEntity) = viewModelScope.launch { repository.deleteVocabulary(item) }
}

data class InkPoint(val x: Float, val y: Float, val pressure: Float)
fun encodePoints(points: List<InkPoint>) = points.joinToString(";") {
    String.format(Locale.US, "%.6f,%.6f,%.4f", it.x, it.y, it.pressure)
}
fun parsePoints(encoded: String): List<InkPoint> = encoded.split(';').mapNotNull { pair ->
    val values = pair.split(',')
    val x = values.getOrNull(0)?.toFloatOrNull(); val y = values.getOrNull(1)?.toFloatOrNull(); val p = values.getOrNull(2)?.toFloatOrNull()
    if (x == null || y == null || p == null) null else InkPoint(x, y, p)
}

fun <T : ViewModel> viewModelFactory(create: () -> T): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
