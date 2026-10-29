package com.example.cet6.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ReasoningFormat

@Composable
fun ApiConnectionSettings(vm: StudyViewModel) {
    var baseUrl by remember(vm.provider, vm.apiBaseUrl) { mutableStateOf(vm.apiBaseUrl) }
    // Keys must never enter rememberSaveable / saved instance state.
    var apiKey by remember(vm.provider, vm.hasApiKey) { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (vm.hasApiKey) "API 配置已加密保存" else "尚未配置 API Key",
                style = MaterialTheme.typography.titleSmall)
            if (vm.provider == AiProvider.CUSTOM) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") },
                    placeholder = { Text("https://服务地址/v1") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
                Text("支持 Chat Completions 流式接口；填写服务商提供的 Base URL，不含 /chat/completions。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("服务地址：${AiProvider.DEEPSEEK.defaultUrl}", style = MaterialTheme.typography.bodySmall)
                Text("使用 DeepSeek 平台 API Key，与网页版登录及 ChatGPT 套餐独立。",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") },
                placeholder = { Text(if (vm.hasApiKey) "已加密保存；留空保留，输入可替换" else "输入服务商 API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (vm.saveApiConnection(baseUrl, apiKey)) apiKey = "" },
                enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("加密保存配置") }
            if (baseUrl != vm.apiBaseUrl || apiKey.isNotBlank()) Text("配置尚未保存，当前请求仍使用已保存的配置。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(vm::refreshModels, enabled = vm.aiReady && !vm.busy,
                modifier = Modifier.fillMaxWidth()) { Text("刷新模型") }
            OutlinedTextField(vm.chosenModel, vm::selectModel, label = { Text("模型名称") },
                supportingText = { Text("可从下方列表选择；服务不提供模型列表时可手动填写模型 ID。") },
                singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            OutlinedButton(vm::clearApiConnection, enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()) { Text("清除当前服务的本机配置") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepReviewSettings(vm: StudyViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("深度批改", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = vm.deepReviewEnabled, onCheckedChange = vm::setDeepReview,
                    modifier = Modifier.semantics { contentDescription = "深度批改" },
                    enabled = !vm.busy && vm.chosenModel.isNotBlank())
            }
            Text("仅用于作文批改、翻译批改、阅读分析及下方测试请求。普通追问、构思和单句提示保持默认档位。设置按服务地址和模型分别记住。",
                style = MaterialTheme.typography.bodySmall)
            if (vm.deepReviewEnabled) {
                if (vm.provider == AiProvider.CUSTOM) {
                    Text("按服务商文档选择请求格式", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReasoningFormat.entries.forEach { format ->
                            FilterChip(selected = vm.reviewFormat == format, onClick = { vm.selectReviewFormat(format) },
                                enabled = !vm.busy, label = { Text(format.label) })
                        }
                    }
                }
                Text("思考档位", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.reviewFormat.efforts.forEach { effort ->
                        FilterChip(selected = vm.reviewEffort == effort, onClick = { vm.selectReviewEffort(effort) },
                            enabled = !vm.busy, label = { Text("${effort.label} (${effort.wire})") })
                    }
                }
                Text("只选择当前模型支持的档位；最大档通常等待更久、消耗更多额度。请求被拒绝时不会自动降档或重发；可关闭此开关后手动重试。兼容服务是否实际执行所选档位，由服务端决定。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
