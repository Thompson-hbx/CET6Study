package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VocabularyImportCard(vm: StudyViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf("") }
    var permitted by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(StudyDocumentPicker()) { uri ->
        if (uri != null) vm.importVocabulary(uri, source)
    }
    OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起导入" else "导入我的词库") }
    if (expanded) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("支持 UTF-8 CSV 或 JSON，最大 2 MiB / 10000 词。CSV 表头：word,meaning,example；JSON：对象数组，每项含相同三个文本字段。例句可为空。")
                Text("已有单词会跳过，保留原释义、来源与复习进度。任一条无效则整份文件不导入。")
                OutlinedTextField(value = source, onValueChange = { source = it.take(300) },
                    label = { Text("来源与授权说明") }, placeholder = { Text("如：本人整理，例句原创，仅供个人学习") },
                    enabled = !vm.savingStudy, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = permitted, onCheckedChange = { permitted = it }, enabled = !vm.savingStudy)
                    Text("我确认有权使用该词库。", Modifier.padding(top = 12.dp))
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = permitted && source.trim().length >= 2 && !vm.savingStudy) { Text("选择文件并导入") }
            }
        }
    }
}
