package com.example.cet6.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

/** 今日: the week's submissions on an ink sheet, the latest correction, drafts to continue and recurring problems. */
@Composable
fun ColumnScope.TodayPage(model: DesktopModel) {
    val all = remember(model.document) { practices(model.document) }
    val reviewed = remember(model.document) { noted(model.document) }
    val since = System.currentTimeMillis() - WEEK_MILLIS
    val week = all.filter { it.second.getLong("submittedAt") >= since }.map { it.second.getString("kind") }
    val drafts = all.filter { it.second.getLong("submittedAt") == 0L }.take(4)
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PageTitle("今日", LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(Modifier.weight(2f).fillMaxHeight(), color = DeskColors.ink, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(horizontal = 32.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("近 7 天提交", color = Color(0xFFB9B5AB), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.Bottom) {
                            // The ring stretches with the number, so two or three digits stay circled.
                            Box(contentAlignment = Alignment.Center) {
                                Image(DeskIcons.ring(DeskColors.brand), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                                Text(
                                    "${week.size}",
                                    style = Numerals.copy(fontSize = 72.sp, lineHeight = 80.sp),
                                    color = Color(0xFFF5F1E8),
                                    modifier = Modifier.padding(horizontal = 34.dp, vertical = 16.dp),
                                )
                            }
                            Text(
                                "次",
                                color = Color(0xFFF5F1E8),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 4.dp, bottom = 22.dp),
                            )
                        }
                    }
                    listOf("篇作文" to "WRITING", "篇翻译" to "TRANSLATION", "次阅读核对" to "READING").forEach { (label, kind) ->
                        Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${week.count { it == kind }}", style = Numerals, color = Color(0xFFF5F1E8))
                            Text(label, color = Color(0xFFB9B5AB), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Sheet(Modifier.weight(1f).fillMaxHeight()) {
                Text("最近一条朱批", style = MaterialTheme.typography.titleSmall)
                val latest = reviewed.firstOrNull()
                if (latest == null) {
                    Text("提交后在手机完成批改，朱批会出现在这里。", color = DeskColors.secondary)
                } else {
                    NoteLine(latest.notes.first(), MaterialTheme.typography.bodyLarge.copy(fontFamily = SerifFamily, fontSize = 18.sp))
                    Text(
                        "${latest.notes.first().category} · ${latest.row.getString("title").ifBlank { "未命名练习" }}",
                        color = DeskColors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "去订正 →",
                        color = DeskColors.good,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { open(model, latest.key, latest.row) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("接着写", style = MaterialTheme.typography.titleMedium)
                Sheet(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
                    if (drafts.isEmpty()) Text("没有未完成的草稿。从左侧选一项开始。", color = DeskColors.secondary, modifier = Modifier.padding(20.dp))
                    drafts.forEachIndexed { index, (key, row) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 78.dp), color = DeskColors.line)
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { open(model, key, row) }.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            KindGlyph(row.getString("kind"))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.getString("title").ifBlank { "未命名练习" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${sectionName(row.getString("kind"))}草稿 · ${words(row.getString("answer"))} 词",
                                    color = DeskColors.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("继续", color = DeskColors.ink, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("常犯的问题", style = MaterialTheme.typography.titleMedium)
                Sheet(Modifier.fillMaxWidth()) { CategoryBars(reviewed.flatMap { it.notes }) }
            }
        }
    }
}
