package com.example.cet6.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
// import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
// import androidx.compose.ui.semantics.semantics
import com.example.cet6.core.ui.R
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

/** The 我的 tab as a settings list: an overview cell, then connection, data and older screens. */
@Composable
fun MeHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        item { TabTitle("我的") }
        item {
            Spacer(Modifier.height(12.dp))
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_study), contentDescription = null, modifier = Modifier.size(56.dp))
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("朱批", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${vm.coach.history.count { it.submittedAt > 0 }} 次提交 · ${if (vm.deviceSync.enabled) "设备同步已开启" else "学习数据保存在本机"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SectionLabel("外观")
            RowCard {
                ListRow(
                    "界面风格",
                    trailing = vm.uiStyle.label,
                    leading = { IconTile(Icons.Outlined.Palette, TileColors.Indigo) },
                ) { onRoute("me/style") }
            }
        }
        item {
            SectionLabel("AI 连接")
            RowCard {
                ListRow(
                    "AI 服务",
                    subtitle = when {
                        !vm.aiReady -> "未连接"
                        vm.chosenModel.isBlank() -> "已连接 · 未选择模型"
                        else -> "已连接 · ${vm.models.firstOrNull { it.slug == vm.chosenModel }?.displayName ?: vm.chosenModel}"
                    },
                    trailing = vm.provider.label,
                    leading = { IconTile(Icons.Outlined.Hub, TileColors.Blue) },
                ) { onRoute("me/connection") }
            }
        }
        item {
            SectionLabel("数据")
            RowCard {
                ListRow(
                    "设备同步",
                    subtitle = if (vm.deviceSync.enabled) "已开启自动同步" else "手机与电脑接着学",
                    leading = { IconTile(Icons.Outlined.Hub, TileColors.Blue) },
                    onClick = { onRoute("me/sync") },
                )
                GroupDivider(TiledRowDividerStart)
                ListRow("备份与恢复", leading = { IconTile(Icons.Outlined.Backup, TileColors.Green) }, onClick = { onRoute("me/backup") })
                GroupDivider(TiledRowDividerStart)
                ListRow(
                    "题库管理",
                    trailing = "${vm.examPapers.size} 套",
                    leading = { IconTile(Icons.AutoMirrored.Outlined.LibraryBooks, TileColors.Orange) },
                    onClick = { onRoute("me/exams") },
                )
            }
            GroupFooter("完整备份包含学习记录与个人题库，不含登录凭证或 API Key。", Modifier.padding(horizontal = CardMargin))
        }
        item {
            SectionLabel("其他")
            RowCard {
                ListRow("崩溃诊断", leading = { IconTile(Icons.Outlined.Description, TileColors.Gray) }, onClick = { onRoute("me/diagnostics") })
                GroupDivider(TiledRowDividerStart)
                ListRow("单词与旧版功能", leading = { IconTile(Icons.AutoMirrored.Outlined.MenuBook, TileColors.Indigo) }, onClick = { onRoute("me/legacy") })
                GroupDivider(TiledRowDividerStart)
                ListRow("旧版作文记录", leading = { IconTile(Icons.Outlined.Description, TileColors.Gray) }, onClick = { onRoute("me/essays") })
            }
        }
    }
}
