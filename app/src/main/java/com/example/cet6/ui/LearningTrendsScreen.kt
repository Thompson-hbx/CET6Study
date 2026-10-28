package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.LearningTrends
import com.example.cet6.ui.components.*
import java.time.LocalDate

@Composable
fun LearningTrendsScreen(vm: StudyViewModel) {
    val trend = remember(vm.coach.history, vm.dailyProgress, vm.today) {
        LearningTrends.weeks(vm.coach.history, vm.dailyProgress, LocalDate.parse(vm.today))
    }
    val totalSubmissions = trend.weeks.sumOf { it.submissions }
    val totalRevisions = trend.weeks.sumOf { it.revisions }
    val totalReadingCorrect = trend.weeks.sumOf { it.readingCorrect }
    val totalReadingKnown = trend.weeks.sumOf { it.readingKnown }
    val totalRemembered = trend.weeks.sumOf { it.remembered }
    val totalWordRatings = trend.weeks.sumOf { it.wordRatings }
    SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        CardTitle("近 8 周累计概览")
        Text(
            "累计提交 $totalSubmissions 次（写译修改稿 $totalRevisions 次） · 阅读核对 $totalReadingCorrect / $totalReadingKnown 题 · 单词记住 $totalRemembered / $totalWordRatings 次",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Note("最近 8 周，按本机日期统计。阅读只计有来源参考键的题目，包含重练，未答计入分母；不同周的题目难度可能不同。")
    }
    val reversed = trend.weeks.asReversed()
    reversed.forEachIndexed { index, week ->
        val active = week.submissions > 0 || week.readingKnown > 0 || week.wordRatings > 0
        SectionCard(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${week.start} 起", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (index == 0) Tag("本周", Tone.Ink)
                else if (active) Tag("有记录", Tone.Good)
                else Note("无记录")
            }
            Text("提交 ${week.submissions} 次 · 其中写译修改稿 ${week.revisions} 次")
            Text("阅读与参考一致：${week.readingCorrect} / ${week.readingKnown} 题")
            Text("单词自评记住：${week.remembered} / ${week.wordRatings} 次")
        }
    }
    Note("修改稿次数反映练习行为；单词自评含同词重复评分。这些指标不能证明作文质量提升或长期保持率，AI 模拟档位不作为效果评分。")
    if (trend.unreadableReading > 0) Note("${trend.unreadableReading} 条阅读快照无法解析，未计入正确率，请保留原记录核对。", tone = Tone.Must)
}
