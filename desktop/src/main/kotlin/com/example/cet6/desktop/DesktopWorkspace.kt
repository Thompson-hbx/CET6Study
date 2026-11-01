package com.example.cet6.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val skills = listOf("WRITING", "TRANSLATION", "READING")

@Composable
internal fun DesktopWorkspace(model: DesktopModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth = if (maxWidth >= 1250.dp) 236.dp else 208.dp
        val compact = maxHeight < 820.dp
        val narrow = maxWidth < 1250.dp
        Row(Modifier.fillMaxSize()) {
            Sidebar(model, Modifier.width(sidebarWidth).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (model.section) {
                    "TODAY" -> TodayPage(model)
                    "REVIEW" -> ReviewPage(model)
                    "SYNC" -> SyncSettings(model)
                    "WORDS" -> WordList(model)
                    else -> PracticeArea(model, compact, narrow)
                }
                DesktopStatus(model, compact)
            }
        }
    }
}

/** The phone's four tabs as a sidebar; 练习 and 我的 list their pages beneath them. */
@Composable
private fun Sidebar(model: DesktopModel, modifier: Modifier) {
    val reviewsWaiting = remember(model.document) {
        practices(model.document).any { (_, row) -> row.getLong("submittedAt") > 0 && reviewNotes(model.document, row).any { it.must } }
    }
    fun go(section: String) { model.section = section; model.selected = null }
    Column(modifier.background(DeskColors.sidebar).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 26.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource("brand/zhupi.svg"), "朱批", Modifier.size(32.dp))
            Text("朱批", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp))
        }
        val selected = model.section
        NavigationRow("今日", { ink, accent -> DeskIcons.today(ink, accent) }, selected == "TODAY") { go("TODAY") }
        NavigationRow("练习", { ink, accent -> DeskIcons.practice(ink, accent) }, selected in skills) { if (selected !in skills) go("WRITING") }
        skills.forEach { key -> SubRow(sectionName(key), selected == key) { go(key) } }
        NavigationRow("复盘", { ink, accent -> DeskIcons.review(ink, accent) }, selected == "REVIEW", dot = reviewsWaiting) { go("REVIEW") }
        NavigationRow("我的", { ink, _ -> DeskIcons.me(ink) }, selected == "WORDS" || selected == "SYNC") { if (selected != "SYNC") go("WORDS") }
        SubRow("生词", selected == "WORDS") { go("WORDS") }
        SubRow("设备同步", selected == "SYNC") { go("SYNC") }
    }
}

@Composable
private fun NavigationRow(label: String, icon: (Color, Color) -> ImageVector, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tint = if (selected) DeskColors.ink else Color(0xFF3A3D44)
    val vector = remember(selected) { icon(tint, if (selected) DeskColors.brand else tint) }
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp)
        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) DeskColors.sheet else if (hovered) DeskColors.hover else Color.Transparent)
        .border(1.5.dp, if (focused) DeskColors.brand else Color.Transparent, RoundedCornerShape(10.dp))
        .hoverable(interaction).selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(vector, null, Modifier.size(20.dp), tint = Color.Unspecified)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tint, modifier = Modifier.weight(1f))
        if (dot) Box(Modifier.size(8.dp).background(DeskColors.brand, CircleShape))
    }
}

@Composable
private fun SubRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.padding(start = 32.dp).fillMaxWidth().height(34.dp)
        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
        .clip(RoundedCornerShape(9.dp))
        .background(if (selected) DeskColors.sheet else if (hovered) DeskColors.hover else Color.Transparent)
        .border(1.5.dp, if (focused) DeskColors.brand else Color.Transparent, RoundedCornerShape(9.dp))
        .hoverable(interaction).selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) DeskColors.ink else DeskColors.secondary)
    }
}

@Composable
private fun ColumnScope.PracticeArea(model: DesktopModel, compact: Boolean, narrow: Boolean) {
    val rows = remember(model.document, model.section) {
        practices(model.document).filter { it.second.getString("kind") == model.section }
    }
    val active = rows.firstOrNull { it.first == model.selected } ?: rows.firstOrNull()
    val notes = remember(model.document, active?.first) { active?.let { reviewNotes(model.document, it.second) }.orEmpty() }
    var libraryOpen by remember { mutableStateOf(false) }
    var feedbackOpen by remember { mutableStateOf(false) }
    val submitted = active?.second?.getLong("submittedAt")?.let { it > 0 } == true
    fun primaryAction() { if (active == null) model.create() else if (submitted) model.revise(active.second) else model.submit(active.first) }
    Column(Modifier.weight(1f).onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown || !it.isCtrlPressed) false else when (it.key) {
            Key.N -> { model.create(); true }
            Key.F -> { libraryOpen = true; true }
            Key.Enter -> { primaryAction(); true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxWidth().height(if (compact) 64.dp else 72.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                DeskButton("练习记录", Icons.Outlined.KeyboardArrowDown, trailingIcon = true) { libraryOpen = !libraryOpen }
                if (libraryOpen) PracticeLibrary(rows, active?.first, model) { libraryOpen = false }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sectionName(model.section), color = DeskColors.secondary, style = MaterialTheme.typography.labelLarge)
                Text("/", color = DeskColors.muted)
                Text(active?.second?.getString("title")?.ifBlank { "未命名练习" } ?: "新的开始", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active != null) {
                if (!narrow) Stages(submitted, notes.isNotEmpty())
                if (!narrow) Text(if (model.saveFailed) "请检查提示" else "本机已保存", color = if (model.saveFailed) DeskColors.danger else DeskColors.secondary,
                    style = MaterialTheme.typography.bodySmall)
                DeskButton("批改面板", Icons.Outlined.VerticalSplit, selected = feedbackOpen) { feedbackOpen = !feedbackOpen }
            }
            DeskButton(if (active == null) "新建练习" else if (submitted) "修改一版" else "提交作答", primary = true, onClick = ::primaryAction)
        }
        HorizontalDivider(color = DeskColors.line)
        if (active == null) EmptyPractice(model) else {
            Editor(model, active.first, active.second, notes.takeIf { !narrow }.orEmpty(), Modifier.weight(1f))
            FeedbackDrawer(model, active.second, feedbackOpen, compact) { feedbackOpen = !feedbackOpen }
        }
    }
}

/** 作答 → 批改 → 修订, as on the phone: a check when done, a vermilion dot on the current stage. */
@Composable
private fun Stages(submitted: Boolean, reviewed: Boolean) {
    val current = if (!submitted) 0 else 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("作答", "批改", "修订").forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(20.dp).height(1.dp).background(DeskColors.line))
            val done = index < current || (index == 1 && reviewed)
            when {
                index == current -> Box(Modifier.size(8.dp).background(DeskColors.brand, CircleShape))
                done -> Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = DeskColors.secondary)
                else -> Box(Modifier.size(8.dp).border(1.5.dp, DeskColors.muted, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                color = if (index == current) DeskColors.ink else DeskColors.secondary)
        }
    }
}

@Composable
private fun PracticeLibrary(rows: List<Pair<String, JSONObject>>, selected: String?, model: DesktopModel, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    val matching = remember(rows, search, filter) { rows.filter { (_, row) ->
        (filter == 0 || (row.getLong("submittedAt") == 0L) == (filter == 1)) &&
            listOf(row.getString("title"), row.getString("material"), row.getString("answer")).any { it.contains(search, ignoreCase = true) }
    } }
    Popup(alignment = Alignment.TopStart, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(Modifier.width(392.dp).padding(top = 48.dp), color = DeskColors.sheet, shape = RoundedCornerShape(16.dp),
            shadowElevation = 18.dp, border = BorderStroke(1.dp, DeskColors.line)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("练习记录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    DeskButton("新建", Icons.Outlined.Add) { model.create(); onDismiss() }
                }
                OutlinedTextField(search, { search = it }, placeholder = { Text("搜索标题、材料或作答") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) }, singleLine = true,
                    shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth())
                DeskTabs(listOf("全部", "草稿", "已提交"), filter, { filter = it })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (matching.isEmpty()) item { Text("没有找到练习", Modifier.padding(20.dp), color = DeskColors.secondary) }
                    items(matching, key = { it.first }) { (key, row) ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (key == selected) DeskColors.chip else Color.Transparent)
                            .clickable { model.selected = key; onDismiss() }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.getString("title").ifBlank { "未命名练习" }, style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (row.getLong("submittedAt") == 0L) "草稿" else "已提交"} · ${time(row.getLong("updatedAt"))}",
                                style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyPractice(model: DesktopModel) {
    Box(Modifier.weight(1f).fillMaxWidth().background(DeskColors.canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource("brand/ring.svg"), null, Modifier.size(72.dp, 62.dp))
            Text("从一份${sectionName(model.section)}草稿开始", style = MaterialTheme.typography.headlineSmall)
            Text("独立作答，慢慢打磨。也可以同步手机上已有的练习。", color = DeskColors.secondary)
            DeskButton("新建练习", Icons.Outlined.Add, primary = true, onClick = model::create)
        }
    }
}

/**
 * The paper: material and answer side by side on a white sheet, with the saved corrections in the right margin
 * once a review exists. The marked words in the answer are underlined in the note's tone; picking a note
 * highlights its words.
 */
@Composable
private fun Editor(model: DesktopModel, key: String, row: JSONObject, notes: List<MarginNote>, modifier: Modifier) {
    val submitted = row.getLong("submittedAt") > 0
    var focused by remember(key) { mutableStateOf(0) }
    Row(modifier.fillMaxWidth().background(DeskColors.canvas).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().shadow(2.dp, RoundedCornerShape(8.dp)).background(DeskColors.sheet, RoundedCornerShape(8.dp))) {
            val wide = maxWidth >= 900.dp
            val padding = if (wide) 44.dp else 28.dp
            val english = TextStyle(fontFamily = SerifFamily, fontSize = if (wide) 19.sp else 17.sp, lineHeight = if (wide) 34.sp else 30.sp,
                color = DeskColors.ink)
            val chinese = MaterialTheme.typography.bodyLarge.copy(fontSize = if (wide) 18.sp else 16.sp, lineHeight = if (wide) 34.sp else 30.sp)
            val compact = maxHeight < 450.dp
            Column(Modifier.fillMaxSize().padding(horizontal = padding)) {
                Column(Modifier.fillMaxWidth().padding(top = if (compact) 16.dp else 32.dp, bottom = if (compact) 16.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentInput(row.getString("title"), { model.edit(key, "title", it) }, "未命名练习",
                        readOnly = submitted, style = MaterialTheme.typography.headlineLarge, singleLine = true)
                    Text("${if (submitted) "已提交" else "草稿"} · ${when (model.section) {
                        "TRANSLATION" -> "中译英"; "READING" -> "阅读练习"; else -> "英语写作"
                    }}", style = MaterialTheme.typography.bodyMedium, color = DeskColors.secondary)
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    DocumentPane(Modifier.weight(1f), if (model.section == "TRANSLATION") "中文原文" else "题目与材料") {
                        DocumentInput(row.getString("material"), { model.edit(key, "material", it) },
                            if (model.section == "TRANSLATION") "在这里输入要翻译的中文段落……" else "在这里输入题目或阅读材料……",
                            submitted, if (model.section == "READING") english else chinese, modifier = Modifier.heightIn(min = 180.dp))
                        if (model.section == "READING") {
                            Spacer(Modifier.height(30.dp))
                            Text("题目与选项", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(18.dp))
                            if (row.getString("questions").trimStart().startsWith('{')) SelectionContainer {
                                Text(questions(row.getString("questions")), style = MaterialTheme.typography.bodyMedium)
                            } else DocumentInput(row.getString("questions"), { model.edit(key, "questions", it) },
                                "添加题目和选项……", submitted, MaterialTheme.typography.bodyMedium)
                        }
                    }
                    VerticalDivider(Modifier.fillMaxHeight().padding(bottom = 24.dp), color = DeskColors.line)
                    Spacer(Modifier.width(36.dp))
                    DocumentPane(Modifier.weight(1f), if (model.section == "TRANSLATION") "我的译文" else "我的作答") {
                        DocumentInput(row.getString("answer"), { model.edit(key, "answer", it) },
                            if (model.section == "READING") "按题号录入答案，例如 46. A，每题一行。" else "在这里开始独立作答……",
                            submitted, english, modifier = Modifier.fillMaxWidth().heightIn(min = 330.dp),
                            marks = if (submitted) MarkTransformation(notes, focused) else VisualTransformation.None)
                    }
                }
            }
        }
        if (notes.isNotEmpty()) Margin(notes, focused) { focused = it }
    }
}

/** The 朱批 margin: one card per saved correction, the picked one expanded with its reason. */
@Composable
private fun Margin(notes: List<MarginNote>, focused: Int, onFocus: (Int) -> Unit) {
    Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("朱批", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black))
            Text("${notes.size}", style = Numerals.copy(fontSize = 18.sp, lineHeight = 24.sp), color = DeskColors.must)
            Spacer(Modifier.weight(1f))
            Text("必改 ${notes.count { it.must }} · 润色 ${notes.count { !it.must }}", color = DeskColors.secondary,
                style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(notes) { index, note ->
                val picked = index == focused
                val tone = if (note.must) DeskColors.must else DeskColors.better
                Surface(color = DeskColors.sheet, shape = RoundedCornerShape(16.dp),
                    border = if (picked) BorderStroke(1.5.dp, if (note.must) DeskColors.brand else DeskColors.better) else null,
                    shadowElevation = if (picked) 6.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onFocus(index) }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NoteBadge(index + 1, note.must)
                            Text("${if (note.must) "必改" else "润色"} · ${note.category}", color = tone,
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        NoteLine(note, TextStyle(fontFamily = SerifFamily, fontSize = 16.sp, lineHeight = 24.sp, color = DeskColors.ink))
                        if (picked && note.reason.isNotBlank()) SelectionContainer {
                            Text(note.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFF3A3D44))
                        }
                    }
                }
            }
        }
    }
}

/** Underlines each note's quoted words in the answer; the focused note also gets its soft background. */
private class MarkTransformation(private val notes: List<MarginNote>, private val focused: Int) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(buildAnnotatedString {
        append(text)
        notes.forEachIndexed { index, note ->
            val at = if (note.quote.isBlank()) -1 else text.text.indexOf(note.quote)
            if (at < 0) return@forEachIndexed
            addStyle(SpanStyle(color = if (note.must) DeskColors.must else DeskColors.better, textDecoration = TextDecoration.Underline,
                background = if (index == focused) (if (note.must) DeskColors.mustSoft else DeskColors.betterSoft) else Color.Unspecified),
                at, at + note.quote.length)
        }
    }, OffsetMapping.Identity)

    override fun equals(other: Any?) = other is MarkTransformation && other.notes == notes && other.focused == focused
    override fun hashCode() = notes.hashCode() * 31 + focused
}

@Composable
private fun DocumentPane(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = DeskColors.secondary)
        Spacer(Modifier.height(16.dp))
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 22.dp, bottom = 30.dp), content = content)
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 5.dp))
        }
    }
}

@Composable
private fun DocumentInput(value: String, onValueChange: (String) -> Unit, placeholder: String, readOnly: Boolean,
    style: TextStyle, modifier: Modifier = Modifier, singleLine: Boolean = false, marks: VisualTransformation = VisualTransformation.None) {
    BasicTextField(value, onValueChange, readOnly = readOnly, singleLine = singleLine, visualTransformation = marks,
        textStyle = style, cursorBrush = SolidColor(DeskColors.brand), modifier = modifier.fillMaxWidth(),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = DeskColors.muted); inner() } })
}

@Composable
private fun FeedbackDrawer(model: DesktopModel, row: JSONObject, expanded: Boolean, compact: Boolean, onToggle: () -> Unit) {
    HorizontalDivider(color = DeskColors.line)
    Row(Modifier.fillMaxWidth().height(if (compact) 52.dp else 60.dp).background(DeskColors.sheet).clickable(role = Role.Button, onClick = onToggle)
        .padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = DeskColors.secondary)
        Text("批改与复盘", style = MaterialTheme.typography.titleSmall)
        Text(if (expanded) "收起面板，继续专注作答" else "手机完成批改后可在这里对照修改", color = DeskColors.secondary, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (expanded) {
        var tab by remember { mutableStateOf(0) }
        val turns = remember(model.document, row.getString("id")) {
            model.document.records.filterKeys { it.startsWith("turns:") }.values.map(::JSONObject)
                .filter { it.getString("practiceId") == row.getString("id") }.sortedBy { it.getLong("createdAt") }
        }
        Column(Modifier.fillMaxWidth().height(if (compact) 160.dp else 240.dp).background(DeskColors.canvas).padding(horizontal = 32.dp, vertical = 16.dp)) {
            DeskTabs(listOf("批改", "追问"), tab, { tab = it }, Modifier.width(220.dp))
            Spacer(Modifier.height(14.dp))
            val shown = turns.filter { (it.getString("kind") == "chat/1") == (tab == 1) }
            val legacy = if (tab == 0) row.getString("feedback") else ""
            if (shown.isEmpty() && legacy.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (tab == 0) "暂无批改记录" else "暂无追问记录", style = MaterialTheme.typography.titleSmall)
                    Text("在手机完成后，内容会同步到这里。", color = DeskColors.secondary)
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (legacy.isNotBlank()) item { SelectionContainer { Text(readableFeedback(legacy)) } }
                items(shown, key = { it.getString("id") }) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (turn.getString("role") == "user") "我的追问" else "AI · ${turn.getString("model")}",
                            style = MaterialTheme.typography.labelMedium, color = DeskColors.secondary)
                        var original by remember(turn.getString("id")) { mutableStateOf(false) }
                        SelectionContainer { Text(if (original) turn.getString("content") else readableFeedback(turn.getString("content"))) }
                        TextButton({ original = !original }) { Text(if (original) "显示排版" else "查看原始回复", color = DeskColors.good) }
                    }
                }
                if (tab == 0 && row.getString("errorReason").isNotBlank()) item {
                    SelectionContainer { Text("错因记录\n${row.getString("errorReason")}") }
                }
            }
        }
    }
}

@Composable
internal fun DesktopStatus(model: DesktopModel, compact: Boolean = false) {
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = DeskColors.ink)
    Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 52.dp).background(DeskColors.canvas).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(model.status, style = MaterialTheme.typography.bodySmall, color = if (model.saveFailed) DeskColors.danger else DeskColors.secondary,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.clip(RoundedCornerShape(50)).clickable {
            if (model.address.isBlank()) model.section = "SYNC" else model.synchronize()
        }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (model.address.isNotBlank() && model.enabled) DeskColors.good else DeskColors.muted, CircleShape))
            Text(if (model.address.isBlank()) "尚未配置设备同步" else if (model.enabled) "自动同步已开启" else "立即同步",
                style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
        }
    }
}

internal fun sectionName(key: String) = when (key) {
    "TODAY" -> "今日"; "REVIEW" -> "复盘"; "WRITING" -> "写作"; "TRANSLATION" -> "翻译"; "READING" -> "阅读"; "WORDS" -> "生词"; else -> "设备同步"
}
private fun time(value: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

private fun questions(value: String): String {
    if (!value.trimStart().startsWith('{')) return value
    val meta = try { JSONObject(value) } catch (_: org.json.JSONException) { return value }
    if (meta.optString("format") != "CET6ExamAttempt") return value
    val passages = meta.optJSONArray("passages") ?: JSONArray().apply { meta.optJSONObject("passage")?.let(::put) }
    return (0 until passages.length()).joinToString("\n\n") { index ->
        val rows = passages.getJSONObject(index).getJSONArray("questions")
        (0 until rows.length()).joinToString("\n\n") { i ->
            val q = rows.getJSONObject(i); val options = q.getJSONArray("options")
            "${q.getInt("number")}. ${q.getString("stem")}\n" + (0 until options.length()).joinToString("\n") { j ->
                options.getJSONObject(j).let { "${it.getString("label")}. ${it.getString("text")}" }
            }
        }
    }
}
