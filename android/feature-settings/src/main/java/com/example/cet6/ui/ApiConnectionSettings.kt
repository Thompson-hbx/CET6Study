package com.example.cet6.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ReasoningFormat
import com.example.cet6.domain.AiState
import com.example.cet6.ui.components.*
import kotlinx.coroutines.flow.Flow

@Composable
fun ApiConnectionSettings(state: AiState, effects: Flow<ConnectionEffect>, onIntent: (ConnectionIntent) -> Unit) {
    var baseUrl by remember(state.provider, state.apiBaseUrl) { mutableStateOf(state.apiBaseUrl) }
    // Keys must never enter rememberSaveable / saved instance state.
    var apiKey by remember(state.provider, state.hasApiKey) { mutableStateOf("") }
    LaunchedEffect(effects) { effects.collect { if (it == ConnectionEffect.ApiKeySaved) apiKey = "" } }
    Group(
        header = if (state.hasApiKey) "API 配置 · 已加密保存" else "API 配置 · 尚未配置 API Key",
        footer = if (state.provider == AiProvider.CUSTOM) {
            "支持 Chat Completions 流式接口；填写服务商提供的 Base URL，不含 /chat/completions。"
        } else {
            "使用 DeepSeek 平台 API Key，与网页版登录及 ChatGPT 套餐独立。"
        },
    ) {
        if (state.provider == AiProvider.CUSTOM) {
            FieldRow(
                "Base URL",
                baseUrl,
                { baseUrl = it },
                placeholder = "https://服务地址/v1",
                enabled = !state.busy,
                keyboardType = KeyboardType.Uri,
            )
        } else {
            ValueRow("服务地址", AiProvider.DEEPSEEK.defaultUrl)
        }
        GroupDivider()
        FieldRow(
            "API Key",
            apiKey,
            { apiKey = it },
            placeholder = if (state.hasApiKey) "已保存；留空保留，输入可替换" else "输入服务商 API Key",
            enabled = !state.busy,
            password = true,
        )
    }
    if (baseUrl != state.apiBaseUrl || apiKey.isNotBlank()) GroupFooter("配置尚未保存，当前请求仍使用已保存的配置。")
    Button(
        onClick = { onIntent(ConnectionIntent.SaveApi(baseUrl, apiKey)) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("加密保存配置") }
    Group(header = "模型 ID", footer = "可从模型列表选择；服务不提供模型列表时可手动填写模型 ID。") {
        FieldRow(
            "模型名称",
            state.chosenModel,
            { onIntent(ConnectionIntent.SelectModel(it)) },
            placeholder = "例如 deepseek-chat",
            enabled = !state.busy,
        )
        GroupDivider()
        ActionRow("刷新模型", { onIntent(ConnectionIntent.RefreshModels) }, enabled = state.aiReady && !state.busy)
    }
    Group {
        ActionRow("清除当前服务的本机配置", { onIntent(ConnectionIntent.ClearApi) }, enabled = !state.busy, destructive = true)
    }
}

@Composable
fun DeepReviewSettings(state: AiState, onIntent: (ConnectionIntent) -> Unit) {
    Group(
        header = "深度批改",
        footer = "仅用于作文批改、翻译批改、阅读分析及下方测试请求。普通追问、构思和单句提示保持默认档位。设置按服务地址和模型分别记住。",
    ) {
        ToggleRow(
            "深度批改",
            state.deepReviewEnabled,
            { onIntent(ConnectionIntent.SetDeepReview(it)) },
            enabled = !state.busy && state.chosenModel.isNotBlank(),
        )
    }
    if (!state.deepReviewEnabled) return
    if (state.provider == AiProvider.CUSTOM) {
        Group(header = "请求格式", footer = "按服务商文档选择请求格式。") {
            ReasoningFormat.entries.forEachIndexed { i, format ->
                if (i > 0) GroupDivider()
                CheckRow(
                    format.label,
                    state.reviewFormat == format,
                    { onIntent(ConnectionIntent.SelectReviewFormat(format)) },
                    enabled = !state.busy,
                )
            }
        }
    }
    Group(
        header = "思考档位",
        footer = "只选择当前模型支持的档位；最大档通常等待更久、消耗更多额度。请求被拒绝时不会自动降档或重发；可关闭此开关后手动重试。兼容服务是否实际执行所选档位，由服务端决定。",
    ) {
        state.reviewFormat.efforts.forEachIndexed { i, effort ->
            if (i > 0) GroupDivider()
            CheckRow(
                "${effort.label} (${effort.wire})",
                state.reviewEffort == effort,
                { onIntent(ConnectionIntent.SelectReviewEffort(effort)) },
                enabled = !state.busy,
            )
        }
    }
}
