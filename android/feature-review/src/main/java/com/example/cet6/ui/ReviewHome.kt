package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Style
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.LocalMarks
import com.example.cet6.ui.theme.NumeralText

/** 复盘: everything that looks back across skills — mistakes, trends, records and the word queue. */
@Composable
fun ReviewHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    val reviews = WeaknessReview.records(vm.coach.history)
    val due = vm.words.count { it.dueAt in 1..vm.now }
    LazyColumn(
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        item { TabTitle("复盘") }
        item {
            Row(Modifier.fillMaxWidth().padding(start = CardMargin, end = CardMargin, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewCount("待回顾错因", reviews.size, LocalMarks.current.must.takeIf { reviews.isNotEmpty() }, Modifier.weight(1f))
                ReviewCount("已提交", vm.coach.history.count { it.submittedAt > 0 }, null, Modifier.weight(1f))
                ReviewCount("待复习词", due, null, Modifier.weight(1f))
            }
        }
        item {
            SectionLabel("错题与错因")
            RowCard {
                ListRow(
                    "错因回顾",
                    subtitle = if (reviews.isEmpty()) "记录过错因的练习会出现在这里" else null,
                    trailing = if (reviews.isEmpty()) null else "${reviews.size} 条",
                    leading = { IconTile(Icons.Outlined.ErrorOutline, TileColors.Red) },
                    onClick = { onRoute("me/review") },
                )
                GroupDivider(TiledRowDividerStart)
                ListRow("练习记录", leading = { IconTile(Icons.Outlined.History, TileColors.Gray) }, onClick = { onRoute("me/records") })
            }
        }
        item {
            SectionLabel("词汇")
            RowCard {
                ListRow(
                    "六级词汇",
                    subtitle = if (vm.words.isEmpty()) "导入词库或在练习中加入生词" else "词库 ${vm.words.size} 词",
                    trailing = if (due > 0) "待复习 $due" else if (vm.words.isNotEmpty()) "已完成" else null,
                    trailingTone = if (due > 0) Tone.Better else Tone.Good,
                    leading = { IconTile(Icons.Outlined.Style, TileColors.Teal) },
                    onClick = { onRoute("me/words") },
                )
            }
        }
        item {
            SectionLabel("趋势")
            RowCard {
                ListRow(
                    "学习趋势",
                    subtitle = "按周查看阅读核对、修改稿与复习自评",
                    leading = { IconTile(Icons.AutoMirrored.Outlined.ShowChart, TileColors.Green) },
                    onClick = { onRoute("me/trends") },
                )
            }
            GroupFooter("趋势只统计本机记录，含重练与样本难度差异，不代表成绩预测。", Modifier.padding(horizontal = CardMargin))
        }
    }
}

/** One count on a white sheet, set in the serif numerals; vermilion only when it asks for attention. */
@Composable
private fun ReviewCount(label: String, count: Int, tone: Color?, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("$count", style = NumeralText, color = tone ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}
