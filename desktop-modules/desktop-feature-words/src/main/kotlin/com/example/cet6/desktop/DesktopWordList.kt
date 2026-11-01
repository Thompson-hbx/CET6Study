package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ColumnScope.WordList(model: DesktopModel) {
    var search by remember { mutableStateOf("") }
    val words = remember(model.document, search) {
        model.document.records.filterKeys { it.startsWith("vocabulary:") }.values.map { JSONObject(it).getJSONObject("word") }
            .filter { it.getString("word").contains(search, true) || it.getString("meaning").contains(search, true) }.sortedBy { it.getString("word") }
    }
    Column(Modifier.weight(1f).fillMaxWidth().padding(44.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("生词", style = MaterialTheme.typography.headlineLarge)
                Text("从练习中积累，再回到语境里。", color = DeskColors.secondary)
            }
            OutlinedTextField(
                search,
                { search = it },
                placeholder = { Text("搜索单词或释义") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.width(280.dp),
            )
        }
        Text("查看从手机同步的词条与进度，复习评分请在手机完成。", style = MaterialTheme.typography.bodySmall, color = DeskColors.secondary)
        LazyColumn(Modifier.fillMaxWidth()) {
            if (words.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Article, null, Modifier.size(44.dp), tint = DeskColors.muted)
                        Text(if (search.isBlank()) "还没有同步的生词" else "没有找到这个词", style = MaterialTheme.typography.titleMedium)
                        Text("在手机练习中收藏生词，再同步到这台电脑。", color = DeskColors.secondary)
                    }
                }
            }
            items(words, key = { it.getString("word") }) { word ->
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(word.getString("word"), style = MaterialTheme.typography.titleLarge)
                    Text(word.getString("meaning"))
                    if (word.getString(
                            "example",
                        ).isNotBlank()
                    ) {
                        SelectionContainer { Text(word.getString("example"), style = MaterialTheme.typography.bodyMedium) }
                    }
                    Text(
                        "${word.getInt("repetitions")} 次复习 · ${word.getString("source")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeskColors.secondary,
                    )
                }
                HorizontalDivider(color = DeskColors.line)
            }
        }
    }
}
