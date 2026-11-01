package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cet6.ai.AiProvider
import com.example.cet6.domain.AiState
import com.example.cet6.ui.components.*
import kotlinx.coroutines.flow.Flow

/** Binds [ConnectionScreen] to its ViewModel; the gateway comes from the activity's [StudyViewModel]. */
@Composable
fun ConnectionRoute(vm: StudyViewModel) {
    val model = viewModel { ConnectionViewModel(vm.services.ai) }
    val state by model.state.collectAsStateWithLifecycle()
    ConnectionScreen(state, model.effect, model::onIntent)
}

/** AI service, model and deep-review settings as iOS-style grouped forms. */
@Composable
fun ConnectionScreen(state: AiState, effects: Flow<ConnectionEffect>, onIntent: (ConnectionIntent) -> Unit) {
    var message by rememberSaveable { mutableStateOf("请只回答：朱批 API 测试成功。") }
    Group(header = "服务", footer = "选择服务与模型，用于批改、讲解和追问。") {
        AiProvider.entries.forEachIndexed { i, provider ->
            if (i > 0) GroupDivider()
            CheckRow(provider.label, state.provider == provider, { onIntent(ConnectionIntent.SelectProvider(provider)) },
                enabled = !state.busy)
        }
    }
    if (state.provider == AiProvider.CHATGPT) ChatGptAccount(state, onIntent) else ApiConnectionSettings(state, effects, onIntent)
    if (state.provider == AiProvider.CHATGPT || state.models.isNotEmpty()) ModelPicker(state, onIntent)
    DeepReviewSettings(state, onIntent)
    Group(header = "测试请求") {
        FieldRow(null, message, { message = it }, placeholder = "测试消息", enabled = !state.busy, minLines = 3,
            accessibilityLabel = "测试消息")
        GroupDivider()
        ActionRow("发送一条测试请求", { onIntent(ConnectionIntent.SendTest(message)) },
            enabled = state.aiReady && state.chosenModel.isNotBlank() && message.isNotBlank() && !state.busy)
    }
    Group(header = "状态") {
        Column(Modifier.fillMaxWidth().padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            if (state.result.isNotBlank()) {
                Hairline()
                SelectionContainer { Text(state.result, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    GroupFooter(
        if (state.provider == AiProvider.CHATGPT) "授权过程由系统浏览器完成。此应用不收集密码、不使用 ChatGPT Cookie。"
        else "请求会把当前练习相关内容发送给所选服务，按服务商 API 规则计费。API Key 不包含在学习备份中。"
    )
}

@Composable
private fun ChatGptAccount(state: AiState, onIntent: (ConnectionIntent) -> Unit) {
    val signedIn = state.sessionEmail.isNotBlank()
    Group(header = "ChatGPT 账号", footer = if (state.hasPlan) "套餐 scope 已授予，实际调用待验证。" else "套餐 scope 未授予。") {
        ValueRow("账号", if (signedIn) state.sessionEmail else "未连接")
        GroupDivider()
        ValueRow("会话", if (signedIn) "有效" else "离线", if (signedIn) Tone.Good else Tone.Muted)
        GroupDivider()
        ValueRow("套餐权限", if (state.hasPlan) "已授予" else "未授予", if (state.hasPlan) Tone.Good else Tone.Must)
    }
    Group {
        ActionRow("Continue with ChatGPT", { onIntent(ConnectionIntent.SignIn) }, enabled = !state.busy)
        GroupDivider()
        ActionRow("刷新模型", { onIntent(ConnectionIntent.RefreshModels) }, enabled = state.hasPlan && !state.busy)
        GroupDivider()
        ActionRow("退出连接", { onIntent(ConnectionIntent.SignOut) }, enabled = !state.busy, destructive = true)
    }
}

@Composable
private fun ModelPicker(state: AiState, onIntent: (ConnectionIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Group(header = "模型") {
        Box {
            ListRow("推理模型",
                trailing = state.models.find { it.slug == state.chosenModel }?.displayName ?: state.chosenModel.ifBlank { "请先获取模型" },
                enabled = state.models.isNotEmpty() && !state.busy, onClick = { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.models.forEach { model ->
                    DropdownMenuItem(text = { Text(model.displayName) }, onClick = {
                        onIntent(ConnectionIntent.SelectModel(model.slug)); expanded = false
                    })
                }
            }
        }
    }
}
