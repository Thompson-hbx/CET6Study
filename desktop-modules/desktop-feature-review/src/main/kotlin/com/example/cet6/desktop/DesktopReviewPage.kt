package com.example.cet6.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 复盘: every saved correction across practices, newest first, with the categories that keep coming back. */
@Composable
fun ColumnScope.ReviewPage(model: DesktopModel) {
    val all = remember(model.document) { practices(model.document) }
    val reviewed = remember(model.document) { noted(model.document) }
    val notes = reviewed.flatMap { it.notes }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PageTitle("复盘", "批改后的朱批汇总在这里，点开回到原稿对照修改。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Count("必改", notes.count { it.must }, DeskColors.must, Modifier.weight(1f))
            Count("可润色", notes.count { !it.must }, DeskColors.better, Modifier.weight(1f))
            Count("已提交练习", all.count { it.second.getLong("submittedAt") > 0 }, DeskColors.ink, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reviewed.isEmpty()) {
                    Sheet(Modifier.fillMaxWidth()) {
                        Text("还没有朱批", style = MaterialTheme.typography.titleSmall)
                        Text("提交作答并在手机完成批改后，内容会同步到这里。", color = DeskColors.secondary)
                    }
                }
                reviewed.forEach { practice ->
                    Sheet(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { open(model, practice.key, practice.row) }) {
                        Text(
                            "${sectionName(practice.row.getString("kind"))} · ${practice.row.getString("title").ifBlank { "未命名练习" }}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        practice.notes.forEachIndexed { index, note ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NoteBadge(index + 1, note.must)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    NoteLine(note, MaterialTheme.typography.bodyMedium.copy(fontFamily = SerifFamily, fontSize = 16.sp))
                                    Text(note.category, color = DeskColors.secondary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("常犯的问题", style = MaterialTheme.typography.titleMedium)
                Sheet(Modifier.fillMaxWidth()) { CategoryBars(notes) }
            }
        }
    }
}
