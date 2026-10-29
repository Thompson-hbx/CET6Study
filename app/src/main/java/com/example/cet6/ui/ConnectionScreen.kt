package com.example.cet6.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cet6.ai.AiProvider
import com.example.cet6.domain.AiState
import kotlinx.coroutines.flow.Flow

/** Binds [ConnectionScreen] to its ViewModel; the gateway comes from the activity's [StudyViewModel]. */
@Composable
fun ConnectionRoute(vm: StudyViewModel) {
    val model = viewModel { ConnectionViewModel(vm.ai) }
    val state by model.state.collectAsStateWithLifecycle()
    ConnectionScreen(state, model.effect, model::onIntent)
}

@Composable
fun ConnectionScreen(state: AiState, effects: Flow<ConnectionEffect>, onIntent: (ConnectionIntent) -> Unit) {
    var message by rememberSaveable { mutableStateOf("请只回答：六级研习室 API 测试成功。") }
    Text(
        "选择服务与模型，用于批改、讲解和追问",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiProvider.entries.forEach { provider ->
            FilterChip(selected = state.provider == provider, onClick = { onIntent(ConnectionIntent.SelectProvider(provider)) },
                enabled = !state.busy, label = { Text(provider.label) })
        }
    }
    if (state.provider != AiProvider.CHATGPT) {
        ApiConnectionSettings(state, effects, onIntent)
    } else {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.sessionEmail.isBlank()) "未连接" else "已登录：${state.sessionEmail}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (state.sessionEmail.isNotBlank()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (state.sessionEmail.isNotBlank()) "有效会话" else "离线",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.sessionEmail.isNotBlank()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    if (state.hasPlan) "套餐 scope：已授予（实际调用待验证）" else "套餐 scope：未授予",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.hasPlan) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = { onIntent(ConnectionIntent.SignIn) },
                    enabled = !state.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Continue with ChatGPT", fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIntent(ConnectionIntent.RefreshModels) },
                        enabled = state.hasPlan && !state.busy,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新模型")
                    }
                    OutlinedButton(
                        onClick = { onIntent(ConnectionIntent.SignOut) },
                        enabled = !state.busy,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("退出连接")
                    }
                }
            }
        }
    }
    if (state.provider == AiProvider.CHATGPT || state.models.isNotEmpty()) {
        ModelSelector(state, onIntent)
    }
    DeepReviewSettings(state, onIntent)
    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        label = { Text("测试消息") },
        minLines = 3,
        enabled = !state.busy,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { onIntent(ConnectionIntent.SendTest(message)) },
        enabled = state.aiReady && state.chosenModel.isNotBlank() && message.isNotBlank() && !state.busy,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("发送一条测试请求", fontWeight = FontWeight.SemiBold)
    }
    StatusCard(state)
    Text(
        if (state.provider == AiProvider.CHATGPT) "授权过程由系统浏览器完成。此应用不收集密码、不使用 ChatGPT Cookie。"
        else "请求会把当前练习相关内容发送给所选服务，按服务商 API 规则计费。API Key 不包含在学习备份中。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ModelSelector(state: AiState, onIntent: (ConnectionIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("推理模型", fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = state.models.isNotEmpty() && !state.busy) {
                Text(state.models.find { it.slug == state.chosenModel }?.displayName ?: state.chosenModel.ifBlank { "请先获取模型" })
                Text(" ▾")
            }
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

@Composable
private fun StatusCard(state: AiState) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("状态：${state.status}", style = MaterialTheme.typography.bodyMedium)
            if (state.result.isNotBlank()) {
                HorizontalDivider()
                SelectionContainer {
                    Text(state.result, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
