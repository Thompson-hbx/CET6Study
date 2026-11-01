package com.example.cet6.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(subtitle, color = DeskColors.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun Sheet(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(24.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = DeskColors.sheet, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Count(label: String, value: Int, color: Color, modifier: Modifier) {
    Sheet(modifier, PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        Text(label, color = DeskColors.secondary, style = MaterialTheme.typography.bodySmall)
        Text("$value", style = Numerals, color = color)
    }
}

/** Top categories as quiet bars; the most frequent one in vermilion. Counts only what was saved. */
@Composable
fun CategoryBars(notes: List<MarginNote>) {
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
fun NoteLine(note: MarginNote, style: TextStyle) {
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
fun NoteBadge(number: Int, must: Boolean) {
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
fun KindGlyph(kind: String) {
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

val Numerals = TextStyle(
    fontFamily = SerifFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 32.sp,
    lineHeight = 38.sp,
    color = DeskColors.ink,
)

fun words(text: String) = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(text).count()
