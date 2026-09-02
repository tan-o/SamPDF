package com.samreader.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samreader.app.data.SpenButtonAction
import com.samreader.app.data.ParsingTuning
import com.samreader.app.data.DeepSeekSettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(state.settings.model) }
    var prompt by remember { mutableStateOf(state.settings.promptTemplate) }
    var action by remember { mutableStateOf(state.settings.spenButtonAction) }
    var aiCorrectionEnabled by remember { mutableStateOf(state.settings.aiCorrectionEnabled) }
    var aiCorrectionMaxChangeRatio by remember { mutableStateOf(state.settings.aiCorrectionMaxChangeRatio) }
    var background by remember { mutableStateOf(state.indexing.keepRunningInBackground) }
    var notificationProgress by remember { mutableStateOf(state.indexing.showNotificationProgress) }
    var debugOverlay by remember { mutableStateOf(state.parsingDebug.overlayEnabled) }
    var layoutConfidence by remember { mutableStateOf(state.layoutConfidence) }
    var modelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.settings, state.indexing, state.parsingDebug, state.layoutConfidence) {
        model = state.settings.model
        prompt = state.settings.promptTemplate
        action = state.settings.spenButtonAction
        aiCorrectionEnabled = state.settings.aiCorrectionEnabled
        aiCorrectionMaxChangeRatio = state.settings.aiCorrectionMaxChangeRatio
        background = state.indexing.keepRunningInBackground
        notificationProgress = state.indexing.showNotificationProgress
        debugOverlay = state.parsingDebug.overlayEnabled
        layoutConfidence = state.layoutConfidence
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                title = { Text("设置") },
                actions = { Button(onClick = {
                    viewModel.save(
                        apiKey, model, prompt, action,
                        aiCorrectionEnabled, aiCorrectionMaxChangeRatio,
                        background, notificationProgress,
                        debugOverlay, layoutConfidence,
                    )
                }) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("DeepSeek", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.settings.hasApiKey) "API Key（已保存；留空不修改）" else "API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { if (state.models.isNotEmpty()) modelMenu = it }) {
                OutlinedTextField(
                    value = model, onValueChange = {}, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    label = { Text("模型（来自 DeepSeek /models）") }, singleLine = true, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                )
                ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    state.models.forEach { id ->
                        DropdownMenuItem(text = { Text(id) }, onClick = { model = id; modelMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("翻译提示词（必须保留 {text}）") }, minLines = 5,
            )
            Text("全文 AI 校正", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许 AI 辅助修复解析原文")
                    Text(
                        "关闭后全文任务只保存译文，不修改 OCR/解析原文",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(aiCorrectionEnabled, { aiCorrectionEnabled = it })
            }
            Text("允许的最大改动比例：${(aiCorrectionMaxChangeRatio * 100).roundToInt()}%")
            Slider(
                value = aiCorrectionMaxChangeRatio,
                onValueChange = { aiCorrectionMaxChangeRatio = it },
                enabled = aiCorrectionEnabled,
                valueRange = DeepSeekSettingsRepository.MIN_AI_CORRECTION_RATIO..
                    DeepSeekSettingsRepository.MAX_AI_CORRECTION_RATIO,
                steps = 18,
            )
            Text(
                "按插入、删除和替换字符的编辑距离计算。提高阈值可接受更大范围的断词或乱码修复；" +
                    "公式、句子 ID 和 JSON 完整性始终受到保护。设置会用于下一批全文翻译。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::refreshRemote, enabled = state.settings.hasApiKey && !state.busy) {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    else Text("刷新模型与余额")
                }
                if (state.settings.hasApiKey) TextButton(onClick = viewModel::clearApiKey) { Text("清除 API Key") }
            }
            state.balance?.let { balance ->
                Text(if (balance.isAvailable) "账户可用" else "账户不可用", color = if (balance.isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                balance.items.forEach { item ->
                    Text("${item.currency}：${item.totalBalance}（充值 ${item.toppedUpBalance} / 赠送 ${item.grantedBalance}）")
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            Text("S Pen 侧键", style = MaterialTheme.typography.titleLarge)
            listOf(
                SpenButtonAction.ERASER to "橡皮（默认）",
                SpenButtonAction.SENTENCE_NOTE to "打开句子画板",
                SpenButtonAction.DISABLED to "不执行操作",
            ).forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = action == value, onClick = { action = value })
                    Text(label)
                }
            }
            Text("本地解析", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("熄屏后继续解析")
                    Text("使用系统前台任务，避免每次亮屏后重头开始", style = MaterialTheme.typography.bodySmall)
                }
                Switch(background, { background = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知显示逐页进度")
                    Text("关闭后仍保留系统要求的运行中通知，但不显示页数", style = MaterialTheme.typography.bodySmall)
                }
                Switch(notificationProgress, { notificationProgress = it }, enabled = background)
            }
            Text("解析调试", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示模型识别框")
                    Text(
                        "在 PDF 上显示布局区域、OCR 行、公式位置、类型与置信度",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(debugOverlay, { enabled ->
                    debugOverlay = enabled
                    viewModel.setDebugOverlay(enabled)
                })
            }
            if (state.currentDocumentId != null) {
                Text("当前 PDF 的布局最低置信度：${(layoutConfidence * 100).roundToInt()}%")
                Slider(
                    value = layoutConfidence,
                    onValueChange = { layoutConfidence = it },
                    valueRange = ParsingTuning.MIN_LAYOUT_CONFIDENCE..ParsingTuning.MAX_LAYOUT_CONFIDENCE,
                    steps = 13,
                )
                Text(
                    "降低会保留更多候选区域，也会增加误框；提高会减少误框，但可能漏掉正文、题注或公式。阈值只对当前 PDF 生效。",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    onClick = { viewModel.applyLayoutConfidenceAndReparse(layoutConfidence) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("应用阈值并本地重析当前 PDF") }
            } else {
                Text("从某篇 PDF 的阅读界面进入设置后，才能调整该文档的布局阈值。", style = MaterialTheme.typography.bodySmall)
            }
            Text("阅读手势固定为：点按单句；长按进入多句选择。句子画板位于翻译悬浮窗。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
