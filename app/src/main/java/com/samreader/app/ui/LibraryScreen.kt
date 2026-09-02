package com.samreader.app.ui

import android.net.Uri
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samreader.app.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val library by viewModel.documents.collectAsStateWithLifecycle()
    val trash by viewModel.trashedDocuments.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val tags by viewModel.documentTags.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showTrash by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var pendingReparse by remember { mutableStateOf<DocumentEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var pendingManage by remember { mutableStateOf<DocumentEntity?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }
    var documentActionMode by remember { mutableStateOf<ActionMode?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val hostView = LocalView.current
    fun closeDocumentMenu() {
        documentActionMode?.finish()
        documentActionMode = null
    }
    fun openDocument(id: String) {
        closeDocumentMenu()
        onOpen(id)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importPdf(it, ::openDocument) }
    }
    DisposableEffect(Unit) { onDispose(::closeDocumentMenu) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    fun leaveSelection() { selectionMode = false; selected = emptySet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "已选择 ${selected.size} 项" else "SamReader", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (selectionMode) TextButton(onClick = ::leaveSelection) { Text("取消") } },
                actions = {
                    if (!selectionMode) {
                        TextButton(onClick = { closeDocumentMenu(); showTrash = !showTrash; folderFilter = null }) { Text(if (showTrash) "返回文库" else "回收站") }
                        TextButton(onClick = { closeDocumentMenu(); onSettings() }) { Text("设置") }
                    } else TextButton(onClick = {
                        val visible = (if (showTrash) trash else library).map { it.id }.toSet()
                        selected = if (selected.containsAll(visible)) emptySet() else visible
                    }) { Text("全选") }
                },
            )
        },
        bottomBar = {
            if (selectionMode) BottomAppBar {
                if (showTrash) {
                    TextButton(onClick = { batchAction = BatchAction.RESTORE }, enabled = selected.isNotEmpty()) { Text("恢复") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { batchAction = BatchAction.DELETE }, enabled = selected.isNotEmpty()) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { batchAction = BatchAction.MOVE }, enabled = selected.isNotEmpty()) { Text("移动到") }
                    TextButton(onClick = { batchAction = BatchAction.TAG }, enabled = selected.isNotEmpty()) { Text("加标签") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { batchAction = BatchAction.TRASH }, enabled = selected.isNotEmpty()) { Text("移到回收站", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (!showTrash && !selectionMode) FloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("导入") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).pointerInput(documentActionMode) {
            if (documentActionMode == null) return@pointerInput
            awaitEachGesture {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) closeDocumentMenu()
            }
        }) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("搜索标题或标签") }, singleLine = true)
            if (!showTrash) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文件夹", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { showNewFolder = true }, enabled = !selectionMode) { Text("＋ 新建文件夹") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp, 6.dp)) {
                    FilterChip(folderFilter == null, { folderFilter = null }, { Text("全部") }); Spacer(Modifier.width(8.dp))
                    folders.forEach { folder -> FilterChip(folderFilter == folder.id, { folderFilter = folder.id }, { Text(folder.name) }); Spacer(Modifier.width(8.dp)) }
                }
            }
            val source = if (showTrash) trash else library
            val filtered = source.filter { doc ->
                (folderFilter == null || doc.folderId == folderFilter) &&
                    (query.isBlank() || doc.title.contains(query, true) || tags.any { it.documentId == doc.id && it.name.contains(query, true) })
            }
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (showTrash) "回收站为空" else "没有匹配的论文") }
            else LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { doc ->
                    LibraryDocumentCard(
                        doc, folders.firstOrNull { it.id == doc.folderId }?.name,
                        tags.filter { it.documentId == doc.id }.map { it.name }, doc.id in selected,
                        onClick = { if (selectionMode) selected = selected.toggle(doc.id) else if (!showTrash) openDocument(doc.id) },
                        onSwipeSelect = { selectionMode = true; selected = selected + doc.id },
                        onLongClick = { anchorInWindow ->
                            if (selectionMode) selected = selected.toggle(doc.id)
                            else {
                                closeDocumentMenu()
                                documentActionMode = showDocumentActionMode(
                                view = hostView,
                                anchorInWindow = anchorInWindow,
                                trashed = showTrash,
                                onOpen = { openDocument(doc.id) },
                                onManage = { pendingManage = doc },
                                onReparse = { pendingReparse = doc },
                                onTrash = { viewModel.trash(doc.id) },
                                onRestore = { viewModel.restore(doc.id) },
                                onDelete = { pendingDelete = doc },
                                onDestroyed = { documentActionMode = null },
                            )
                            }
                        },
                    )
                }
            }
        }
    }

    pendingReparse?.let { doc -> AlertDialog(
        onDismissRequest = { pendingReparse=null },
        title = { Text("如何重新解析《${doc.title}》？", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text("两种方式都会先在设备端重建版面、OCR 和句子。只有选择 AI 时，才会在本地解析完成后调用 DeepSeek 生成全文上下文。") },
        confirmButton = { TextButton(onClick = { viewModel.reparse(doc.id, true); pendingReparse=null }) { Text("本地 + AI") } },
        dismissButton = { TextButton(onClick = { viewModel.reparse(doc.id, false); pendingReparse=null }) { Text("仅本地解析") } },
    ) }
    pendingDelete?.let { doc -> AlertDialog(
        onDismissRequest = { pendingDelete=null }, title = { Text("彻底删除《${doc.title}》？") },
        text = { Text("将删除 PDF 源文件和全部阅读数据，无法恢复。") },
        confirmButton = { TextButton(onClick = { viewModel.deletePermanently(doc.id); pendingDelete=null }) { Text("删除源文件和数据", color=MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick={pendingDelete=null}) { Text("取消") } },
    ) }
    pendingManage?.let { doc -> OrganizeDialog(folders, doc.folderId, tags.filter { it.documentId == doc.id }.joinToString(",") { it.name }, { pendingManage=null }) { folder,tagsText -> viewModel.organize(doc.id, folder, tagsText.split(',', '，')); pendingManage=null } }
    if (showNewFolder) NewFolderDialog({ showNewFolder=false }) { viewModel.createFolder(it); showNewFolder=false }
    when (batchAction) {
        BatchAction.MOVE -> FolderPickerDialog(folders, { batchAction=null }) { folder -> viewModel.moveDocuments(selected.toList(), folder); batchAction=null; leaveSelection() }
        BatchAction.TAG -> BatchTagDialog({ batchAction=null }) { value -> viewModel.addTags(selected.toList(), value.split(',', '，')); batchAction=null; leaveSelection() }
        BatchAction.TRASH -> AlertDialog(onDismissRequest={batchAction=null}, title={Text("移动 ${selected.size} 篇论文到回收站？")}, confirmButton={TextButton(onClick={viewModel.trashDocuments(selected.toList());batchAction=null;leaveSelection()}){Text("移动",color=MaterialTheme.colorScheme.error)}}, dismissButton={TextButton(onClick={batchAction=null}){Text("取消")}})
        BatchAction.RESTORE -> AlertDialog(onDismissRequest={batchAction=null}, title={Text("恢复 ${selected.size} 篇论文？")}, text={Text("论文将恢复到原来的文件夹，并保留标签、批注和解析数据。")}, confirmButton={TextButton(onClick={viewModel.restoreDocuments(selected.toList());batchAction=null;leaveSelection()}){Text("恢复")}}, dismissButton={TextButton(onClick={batchAction=null}){Text("取消")}})
        BatchAction.DELETE -> AlertDialog(onDismissRequest={batchAction=null}, title={Text("彻底删除 ${selected.size} 篇论文？")}, text={Text("将删除 PDF 源文件和全部阅读数据，无法恢复。")}, confirmButton={TextButton(onClick={viewModel.deletePermanently(selected.toList());batchAction=null;leaveSelection()}){Text("彻底删除",color=MaterialTheme.colorScheme.error)}}, dismissButton={TextButton(onClick={batchAction=null}){Text("取消")}})
        null -> Unit
    }
}

private enum class BatchAction { MOVE, TAG, TRASH, RESTORE, DELETE }
private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable private fun MenuItem(title:String, support:String?=null, color:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.onSurface, onClick:()->Unit) { ListItem(headlineContent={Text(title,color=color)},supportingContent=support?.let{{Text(it)}},modifier=Modifier.combinedClickable(onClick=onClick,onLongClick={})) }

@Composable
private fun LibraryDocumentCard(doc: DocumentEntity, folder: String?, tags: List<String>, selected: Boolean, onClick:()->Unit, onSwipeSelect:()->Unit, onLongClick:(IntRect)->Unit) {
    val dismissState = rememberSwipeToDismissBoxState(positionalThreshold = { it * .28f })
    var cardBoundsInWindow by remember { mutableStateOf(IntRect.Zero) }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            onSwipeSelect()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                Text("选择", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        ElevatedCard(modifier=Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                cardBoundsInWindow = IntRect(
                    bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.right.roundToInt(), bounds.bottom.roundToInt(),
                )
            }
            .combinedClickable(onClick=onClick,onLongClick={ onLongClick(cardBoundsInWindow) }), colors=CardDefaults.elevatedCardColors(containerColor=if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
                if (selected) Checkbox(true, null)
                Column(Modifier.weight(1f)) {
                    Text(doc.title, style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold, maxLines=2, overflow=TextOverflow.Ellipsis)
                    Text(listOfNotNull(folder?.let{"📁 $it"}, tags.takeIf{it.isNotEmpty()}?.joinToString(" · ")).joinToString("  "), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${doc.pageCount} 页 · ${documentStatusLabel(doc)}", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if (doc.status == DocumentStatus.INDEXING) LinearProgressIndicator(progress={doc.processedPages.toFloat()/doc.pageCount.coerceAtLeast(1)}, modifier=Modifier.fillMaxWidth().padding(top=8.dp))
                }
            }
        }
    }
}

private fun showDocumentActionMode(
    view: View,
    anchorInWindow: IntRect,
    trashed: Boolean,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    onReparse: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDestroyed: () -> Unit,
): ActionMode? = view.startActionMode(object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (trashed) {
                menu.add(Menu.NONE, 5, 1, "恢复")
                menu.add(Menu.NONE, 6, 2, "彻底删除")
            } else {
                menu.add(Menu.NONE, 1, 1, "打开")
                menu.add(Menu.NONE, 2, 2, "整理")
                menu.add(Menu.NONE, 3, 3, "重新解析")
                menu.add(Menu.NONE, 4, 4, "回收站")
            }
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
            val action = when (item.itemId) {
                1 -> onOpen; 2 -> onManage; 3 -> onReparse; 4 -> onTrash
                5 -> onRestore; 6 -> onDelete; else -> return false
            }
            // ActionMode is window-global. Finish it before navigation/dialog state changes so it
            // cannot survive the Library -> Reader transition.
            mode.finish()
            action()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = onDestroyed()
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.setFromWindowRect(view, anchorInWindow)
        }
    }, ActionMode.TYPE_FLOATING)

private fun documentStatusLabel(doc: DocumentEntity) = when(doc.status) { DocumentStatus.QUEUED->"等待解析";DocumentStatus.INDEXING->"解析 ${doc.processedPages}/${doc.pageCount}";DocumentStatus.READY->"布局就绪";else->"解析失败，长按可重试" }

@Composable private fun NewFolderDialog(onDismiss:()->Unit,onCreate:(String)->Unit) { var name by remember{mutableStateOf("")}; AlertDialog(onDismissRequest=onDismiss,title={Text("新建文件夹")},text={OutlinedTextField(name,{name=it},label={Text("文件夹名称")},singleLine=true)},confirmButton={TextButton(onClick={onCreate(name)},enabled=name.isNotBlank()){Text("创建")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}}) }
@Composable private fun FolderPickerDialog(folders:List<FolderEntity>,onDismiss:()->Unit,onPick:(String?)->Unit) { AlertDialog(onDismissRequest=onDismiss,title={Text("移动到文件夹")},text={LazyColumn{item{MenuItem("未分类"){onPick(null)}};items(folders,key={it.id}){folder->MenuItem(folder.name){onPick(folder.id)}}}},confirmButton={},dismissButton={TextButton(onClick=onDismiss){Text("取消")}}) }
@Composable private fun BatchTagDialog(onDismiss:()->Unit,onSave:(String)->Unit) { var value by remember{mutableStateOf("")}; AlertDialog(onDismissRequest=onDismiss,title={Text("批量添加标签")},text={OutlinedTextField(value,{value=it},label={Text("用逗号分隔")})},confirmButton={TextButton(onClick={onSave(value)},enabled=value.isNotBlank()){Text("添加")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}}) }

@Composable
private fun OrganizeDialog(folders:List<FolderEntity>, initialFolder:String?, initialTags:String, onDismiss:()->Unit, onSave:(String?,String)->Unit) {
    var folder by remember { mutableStateOf(initialFolder) }; var tags by remember { mutableStateOf(initialTags) }
    AlertDialog(onDismissRequest=onDismiss, title={Text("文件夹与标签")}, text={Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) { FilterChip(folder==null,{folder=null},{Text("未分类")}); folders.forEach { f -> Spacer(Modifier.width(6.dp)); FilterChip(folder==f.id,{folder=f.id},{Text(f.name)}) } }
        OutlinedTextField(tags,{tags=it},label={Text("标签，用逗号分隔")},modifier=Modifier.fillMaxWidth())
    }}, confirmButton={TextButton(onClick={onSave(folder,tags)}){Text("保存")}}, dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
