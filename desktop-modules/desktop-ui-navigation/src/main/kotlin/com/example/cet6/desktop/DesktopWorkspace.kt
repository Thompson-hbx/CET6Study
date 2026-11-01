package com.example.cet6.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.diagnostics.CrashReporter

private val skills = listOf("WRITING", "TRANSLATION", "READING")

@Composable
fun DesktopWorkspace(model: DesktopModel, diagnostics: CrashReporter) {
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
                    "DIAGNOSTICS" -> DiagnosticsPage(diagnostics)
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
    fun go(section: String) {
        model.section = section
        model.selected = null
    }
    Column(modifier.background(DeskColors.sidebar).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, top = 26.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource("brand/zhupi.svg"), "朱批", Modifier.size(32.dp))
            Text("朱批", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp))
        }
        val selected = model.section
        NavigationRow("今日", { ink, accent -> DeskIcons.today(ink, accent) }, selected == "TODAY") { go("TODAY") }
        NavigationRow("练习", { ink, accent -> DeskIcons.practice(ink, accent) }, selected in skills) { if (selected !in skills) go("WRITING") }
        skills.forEach { key -> SubRow(sectionName(key), selected == key) { go(key) } }
        NavigationRow("复盘", { ink, accent -> DeskIcons.review(ink, accent) }, selected == "REVIEW", dot = reviewsWaiting) { go("REVIEW") }
        NavigationRow(
            "我的",
            { ink, _ -> DeskIcons.me(ink) },
            selected in setOf("WORDS", "SYNC", "DIAGNOSTICS"),
        ) { if (selected != "SYNC") go("WORDS") }
        SubRow("生词", selected == "WORDS") { go("WORDS") }
        SubRow("设备同步", selected == "SYNC") { go("SYNC") }
        SubRow("崩溃诊断", selected == "DIAGNOSTICS") { go("DIAGNOSTICS") }
    }
}

@Composable
private fun NavigationRow(label: String, icon: (Color, Color) -> ImageVector, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tint = if (selected) DeskColors.ink else Color(0xFF3A3D44)
    val vector = remember(selected) { icon(tint, if (selected) DeskColors.brand else tint) }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp)
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DeskColors.sheet else if (hovered) DeskColors.hover else Color.Transparent)
            .border(1.5.dp, if (focused) DeskColors.brand else Color.Transparent, RoundedCornerShape(10.dp))
            .hoverable(interaction).selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(vector, null, Modifier.size(20.dp), tint = Color.Unspecified)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (dot) Box(Modifier.size(8.dp).background(DeskColors.brand, CircleShape))
    }
}

@Composable
private fun SubRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.padding(start = 32.dp).fillMaxWidth().height(34.dp)
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) DeskColors.sheet else if (hovered) DeskColors.hover else Color.Transparent)
            .border(1.5.dp, if (focused) DeskColors.brand else Color.Transparent, RoundedCornerShape(9.dp))
            .hoverable(interaction).selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) DeskColors.ink else DeskColors.secondary,
        )
    }
}

@Composable
fun DesktopStatus(model: DesktopModel, compact: Boolean = false) {
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = DeskColors.ink)
    Row(
        Modifier.fillMaxWidth().height(if (compact) 44.dp else 52.dp).background(DeskColors.canvas).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            model.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (model.saveFailed) DeskColors.danger else DeskColors.secondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable {
                if (model.address.isBlank()) model.section = "SYNC" else model.synchronize()
            }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(if (model.address.isNotBlank() && model.enabled) DeskColors.good else DeskColors.muted, CircleShape))
            Text(
                if (model.address.isBlank()) "尚未配置设备同步" else if (model.enabled) "自动同步已开启" else "立即同步",
                style = MaterialTheme.typography.bodySmall,
                color = DeskColors.secondary,
            )
        }
    }
}
