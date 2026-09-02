package com.samreader.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(viewModel: VocabularyViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle(); val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }, title = { Text("生词本") }) }) { padding ->
        if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("还没有生词。在句子卡片中选中文字后点“查词”。") }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp)) {
                    Column(Modifier.weight(1f)) { Text(item.word, style = MaterialTheme.typography.titleMedium); if (item.note.isNotBlank()) Text(item.note) }
                    TextButton(onClick = { lookupSamsungDictionary(context, item.word) }) { Text("词典") }
                    TextButton(onClick = { viewModel.delete(item) }) { Text("删除") }
                } }
            }
        }
    }
}
