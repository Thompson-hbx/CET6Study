package com.example.cet6.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.sync.SyncDocument
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
private val reviewKinds = setOf("write-review/1", "trans-review/1")

/** Practices in the synced document, newest first. */
internal fun practices(document: SyncDocument): List<Pair<String, JSONObject>> =
    document.records.filterKeys { it.startsWith("coach:") }.map { (key, value) -> key to JSONObject(value) }
        .sortedByDescending { it.second.getLong("updatedAt") }

/** Corrections from the newest complete review of [row]; older saves keep theirs in the row's own feedback field. */
internal fun reviewNotes(document: SyncDocument, row: JSONObject): List<MarginNote> {
    val latest = document.records.filterKeys { it.startsWith("turns:") }.values.map(::JSONObject)
        .filter { it.getString("practiceId") == row.getString("id") && it.optString("kind") in reviewKinds && it.optString("role") != "user" }
        .maxByOrNull { it.getLong("createdAt") }
    return marginNotes(latest?.getString("content") ?: row.optString("feedback"))
}

private data class NotedPractice(val key: String, val row: JSONObject, val notes: List<MarginNote>)

private fun noted(document: SyncDocument) = practices(document).filter { it.second.getLong("submittedAt") > 0 }
    .map { (key, row) -> NotedPractice(key, row, reviewNotes(document, row)) }.filter { it.notes.isNotEmpty() }

private fun open(model: DesktopModel, key: String, row: JSONObject) {
    model.section = row.getString("kind")
    model.selected = key
}

/** 今日: the week's submissions on an ink sheet, the latest correction, drafts to continue and recurring problems. */
@Composable
internal fun ColumnScope.TodayPage(model: DesktopModel) {
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

/** 复盘: every saved correction across practices, newest first, with the categories that keep coming back. */
@Composable
internal fun ColumnScope.ReviewPage(model: DesktopModel) {
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

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(subtitle, color = DeskColors.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun Sheet(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(24.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = DeskColors.sheet, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Count(label: String, value: Int, color: Color, modifier: Modifier) {
    Sheet(modifier, PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        Text(label, color = DeskColors.secondary, style = MaterialTheme.typography.bodySmall)
        Text("$value", style = Numerals, color = color)
    }
}

/** Top categories as quiet bars; the most frequent one in vermilion. Counts only what was saved. */
@Composable
private fun CategoryBars(notes: List<MarginNote>) {
    val counts = notes.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.take(5)
    if (counts.isEmpty()) {
        Text("批改记录多起来后，这里会显示反复出现的问题。", color = DeskColors.secondary)
        return
    }
    val top = counts.first().value.toFloat()
    counts.forEachIndexed { index, (category, count) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(category, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f).height(8.dp).background(DeskColors.chip, RoundedCornerShape(4.dp))) {
                Box(
                    Modifier.fillMaxWidth(count / top).fillMaxHeight()
                        .background(if (index == 0) DeskColors.brand else DeskColors.ink, RoundedCornerShape(4.dp)),
                )
            }
            Text("$count", style = Numerals.copy(fontSize = 16.sp, lineHeight = 20.sp), modifier = Modifier.widthIn(min = 20.dp))
        }
    }
}

/** The quoted words struck through in vermilion, then the fix; polish uses ochre. */
@Composable
internal fun NoteLine(note: MarginNote, style: TextStyle) {
    val tone = if (note.must) DeskColors.must else DeskColors.better
    Text(
        buildAnnotatedString {
            if (note.quote.isNotBlank()) {
                withStyle(SpanStyle(color = DeskColors.secondary, textDecoration = TextDecoration.LineThrough)) { append(note.quote) }
                append("  ")
            }
            withStyle(SpanStyle(color = tone, fontWeight = FontWeight.SemiBold)) { append(note.fix.ifBlank { "删去" }) }
        },
        style = style,
    )
}

/** Numbered marker matching the underline: solid for must-fix, outlined for polish. */
@Composable
internal fun NoteBadge(number: Int, must: Boolean) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.size(22.dp).then(
            if (must) {
                Modifier.background(DeskColors.brand, shape)
            } else {
                Modifier.background(DeskColors.sheet, shape).border(1.5.dp, DeskColors.better, shape)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            color = if (must) Color.White else DeskColors.better,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun KindGlyph(kind: String) {
    Box(Modifier.size(44.dp).background(DeskColors.canvas, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(
            when (kind) {
                "TRANSLATION" -> "译"
                "READING" -> "读"
                else -> "写"
            },
            style = TextStyle(fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
            color = DeskColors.ink,
        )
    }
}

internal val Numerals = TextStyle(
    fontFamily = SerifFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 32.sp,
    lineHeight = 38.sp,
    color = DeskColors.ink,
)

private fun words(text: String) = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(text).count()
