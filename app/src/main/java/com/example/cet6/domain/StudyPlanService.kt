package com.example.cet6.domain

import android.content.Context
import android.net.Uri
import com.example.cet6.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StudyPlanState(
    val now: Long = System.currentTimeMillis(),
    val plan: DailyPlan? = null,
    val words: List<WordEntity> = emptyList(),
    val wordReviews: List<WordReview> = emptyList(),
    val dailyProgress: List<DailyWordProgress> = emptyList(),
    val schedules: Map<String, WordSchedule> = emptyMap(),
    val pronunciations: Map<String, String> = emptyMap(),
    val status: String = "",
    val saving: Boolean = false
) {
    val today: String get() = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

/** Vocabulary, FSRS reviews and the daily plan. Nothing loads until [startupReady] so a pending restore lands first. */
class StudyPlanService(
    private val context: Context,
    private val study: StudyRepository,
    private val scope: CoroutineScope,
    private val startupReady: Deferred<Unit>,
    private val backupBusy: () -> Boolean
) {
    val state = ServiceState(StudyPlanState())
    private val current get() = state.value
    private fun setStatus(text: String) { state.update { it.copy(status = text) } }

    init {
        scope.launch {
            startupReady.await()
            try {
                study.initialize()
                val bundled = withContext(Dispatchers.IO) {
                    context.assets.open("academic_words.csv").use {
                        VocabularyImport.parse(VocabularyImport.read(it), "项目原创学术词汇练习；释义与例句原创；项目 LICENSE 授权")
                    }
                }
                study.importWords(bundled)
                val pronunciations = withContext(Dispatchers.IO) {
                    context.assets.open("pronunciations.tsv").bufferedReader().useLines { lines ->
                        lines.drop(1).map { it.split('\t', limit = 2) }.filter { it.size == 2 }
                            .associate { it[0] to it[1] }
                    }
                }
                state.update { it.copy(pronunciations = pronunciations) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setStatus("无法读取本地词库，请重启应用；不要卸载或清除数据。") }
        }
        scope.launch {
            try { study.words.collectLatest { words -> state.update { it.copy(words = words) } }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setStatus("无法读取本地词库，请重启应用；不要卸载或清除数据。") }
        }
        scope.launch { study.wordReviews.collectLatest { reviews -> state.update { it.copy(wordReviews = reviews) } } }
        scope.launch { study.dailyProgress.collectLatest { progress -> state.update { it.copy(dailyProgress = progress) } } }
        scope.launch { study.schedules.collectLatest { list -> state.update { it.copy(schedules = list.associateBy(WordSchedule::word)) } } }
        scope.launch {
            startupReady.await()
            while (true) { refreshDay(); delay(30_000) }
        }
    }

    /** Claims the saving slot; false when another study write already holds it. */
    private fun tryStartSaving(): Boolean {
        var started = false
        state.update { if (it.saving) it else { started = true; it.copy(saving = true) } }
        return started
    }

    internal fun setSaving(value: Boolean) { state.update { it.copy(saving = value) } }

    fun review(word: WordEntity, rating: WordRating) {
        if (!tryStartSaving()) return
        scope.launch {
            try {
                val recorded = study.review(word.word, rating)
                setStatus(if (recorded) "已保存：${word.word} · ${rating.label}" else
                    "该词已记录或尚未到期，无需重复提交。")
                refreshDay()
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus("未保存：${e.message}")
            } catch (_: Exception) { setStatus("学习记录保存失败，请重试；本次不计入完成数量。")
            } finally { setSaving(false) }
        }
    }

    fun refreshClock() { scope.launch { startupReady.await(); refreshDay() } }

    suspend fun refreshDay() {
        val today = state.update { it.copy(now = System.currentTimeMillis()) }.today
        if (current.plan?.date != today) {
            try { val plan = study.plan(LocalDate.parse(today)); state.update { it.copy(plan = plan) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { setStatus("每日计划读取失败，请重启应用重试。") }
        }
    }

    /** Re-reads today's plan even when the date has not changed, e.g. after a restore adopted backup plans. */
    internal suspend fun reloadPlan() {
        refreshDay()
        val plan = study.plan(LocalDate.parse(current.today))
        state.update { it.copy(plan = plan) }
    }

    fun savePlan(newGoal: Int, reviewGoal: Int) {
        if (!tryStartSaving()) return
        scope.launch {
            try {
                refreshDay()
                val updated = DailyPlan(current.today, newGoal, reviewGoal)
                study.savePlan(updated)
                state.update { it.copy(plan = updated, status = "今日目标已保存，后续日期沿用此目标。") }
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus(e.message.orEmpty())
            } catch (_: Exception) { setStatus("目标保存失败，请重试。")
            } finally { setSaving(false) }
        }
    }

    fun importVocabulary(uri: Uri, source: String) {
        if (!tryStartSaving()) return
        setStatus("正在校验词库，校验全部通过后才会写入……")
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        VocabularyImport.parse(VocabularyImport.read(it), source)
                    } ?: error("无法读取所选文件")
                }
                val added = study.importWords(imported)
                setStatus("导入成功：新增 $added 条，已有 ${imported.size - added} 条已跳过并保留原进度。")
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { setStatus("词库未导入：${e.message}")
            } catch (_: Exception) { setStatus("词库未导入：请检查 UTF-8 编码、CSV/JSON 格式与文件读取权限。")
            } finally { setSaving(false) }
        }
    }

    suspend fun savePracticeWord(entry: CoachPractice, word: String, meaning: String, quote: String): String {
        check(!current.saving && !backupBusy()) { "正在保存，请稍后重试" }
        val candidate = PracticeVocabulary.word(entry, word, meaning, quote)
        setSaving(true)
        return try {
            if (study.importWords(listOf(candidate)) == 1) "已加入新词复习，来源练习与原句已保留。"
            else "词条已存在，原释义与复习进度已保留。"
        } finally { setSaving(false) }
    }
}
