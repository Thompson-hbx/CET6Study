package com.example.cet6

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import com.example.cet6.data.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/** Default suite uses isolated databases. Explicit fixture mode only adds a new practice; never reads credentials. */
class LocalDataInstrumentation : Instrumentation() {
    private var readingLocationScenario = false
    private var aiConnectionScenario = false
    private var manifestScenario = false
    private var draftProcessScenario = ""
    private var draftProcessMode = ""
    private var draftProcessRun = ""
    private var draftKilledPid = 0
    private var oauthRefreshScenario = false
    private var aiStreamScenario = false
    private var decisionStateScenario = false
    private var schemaScenario = false
    private var readingAccessMode = ""
    private var uiScenario = false
    private var restoreStartupScenario = ""
    private var restoreProcessScenario = ""
    private var restoreScenario = false
    private var lifecycleScenario = false
    private var evaluationMode = ""
    private var evaluationConsent = ""
    private var evaluationRunId = ""
    private var aiInteractionScenario = false
    private var translationFixture = false
    private var longFeedbackFixture = false
    private var examFixture = false
    private var reviewFixture = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        readingLocationScenario = arguments?.getString("scenario") == "reading-location"
        aiConnectionScenario = arguments?.getString("scenario") == "ai-connection"
        manifestScenario = arguments?.getString("scenario") == "backup-manifest"
        draftProcessScenario = arguments?.getString("scenario")?.takeIf {
            it in setOf("draft-process-prepare", "draft-process-verify", "draft-process-inspect")
        }.orEmpty()
        draftProcessMode = arguments?.getString("mode").orEmpty()
        draftProcessRun = arguments?.getString("run-id").orEmpty()
        draftKilledPid = arguments?.getString("killed-pid")?.toIntOrNull() ?: 0
        oauthRefreshScenario = arguments?.getString("scenario") == "oauth-refresh"
        aiStreamScenario = arguments?.getString("scenario") == "ai-stream"
        decisionStateScenario = arguments?.getString("scenario") == "coach-decision-state"
        schemaScenario = arguments?.getString("scenario") == "coach-output-schema"
        readingAccessMode = when {
            arguments?.getString("fixture") == "reading-accessibility" -> "seed"
            arguments?.getString("scenario") == "reading-accessibility-verify" -> "verify"
            else -> ""
        }
        evaluationMode = arguments?.getString("scenario")?.takeIf {
            it in setOf("coach-evaluation", "coach-evaluation-v2", "coach-evaluation-offline")
        }.orEmpty()
        evaluationConsent = arguments?.getString("consent").orEmpty()
        evaluationRunId = arguments?.getString("run-id").orEmpty()
        restoreStartupScenario = arguments?.getString("scenario")?.takeIf {
            it in setOf("restore-startup-prepare", "restore-startup-pending-prepare", "restore-startup-resume", "restore-startup-share-resume")
        }.orEmpty()
        restoreProcessScenario = arguments?.getString("scenario")?.takeIf {
            it == "restore-process-prepare" || it == "restore-process-resume"
        }.orEmpty()
        restoreScenario = arguments?.getString("scenario") == "restore-recovery"
        lifecycleScenario = arguments?.getString("scenario") == "lifecycle"
        uiScenario = arguments?.getString("scenario") == "ui"
        aiInteractionScenario = arguments?.getString("scenario") == "ai-interaction"
        translationFixture = arguments?.getString("fixture") == "translation"
        longFeedbackFixture = arguments?.getString("fixture") == "long-feedback"
        examFixture = arguments?.getString("fixture") == "g4-sample"
        reviewFixture = arguments?.getString("fixture") == "review-layout"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            if (readingLocationScenario) {
                runBlocking { ReadingLocationChecks(targetContext).verify(); verifyReleasedSchemas() }
                results.putString("stream", "PASS: exact AI quote persists across Room reopen and ZIP restore; invented quote rejected; v1-v8 migrations preserve records.\n")
                finish(-1, results)
                return
            }
            if (aiConnectionScenario) {
                runBlocking { AiConnectionStateChecks(targetContext).verify() }
                results.putString("stream", "PASS: isolated AI provider/model preferences, encrypted profile restoration, busy/cancellation, partial/429 failures and completed-only essay persistence.\n")
                finish(-1, results)
                return
            }
            if (manifestScenario) {
                val count = runBlocking { CompleteRestoreChecks(targetContext).verifyManifestRejection() }
                results.putString("stream", "PASS: $count invalid manifest types rejected before study/bank writes or a restore journal; valid and legacy backups preserved.\n")
                finish(-1, results)
                return
            }
            if (draftProcessScenario.isNotEmpty()) {
                val checks = DraftProcessChecks(this, draftProcessMode, draftProcessRun)
                val report = runBlocking {
                    when (draftProcessScenario) {
                        "draft-process-prepare" -> checks.prepare()
                        "draft-process-inspect" -> checks.inspectData()
                        else -> checks.verify(draftKilledPid)
                    }
                }
                results.putString("stream", report)
                finish(-1, results)
                return
            }
            if (oauthRefreshScenario) {
                runBlocking { OAuthRefreshChecks().verify() }
                results.putString("stream", "PASS: offline OAuth refresh rotation, cancellation, failure and malformed response preservation.\n")
                finish(-1, results)
                return
            }
            if (aiStreamScenario) {
                val count = AiStreamChecks(context).verify()
                results.putString("stream", "PASS: $count offline AI stream field types and completion cases validated on Android.\n")
                finish(-1, results)
                return
            }
            if (decisionStateScenario) {
                runBlocking { CoachDecisionStateChecks(targetContext, context).verify() }
                results.putString("stream", "PASS: invalid decision state preserved; legacy state supported; two repositories keep all 40 concurrent decisions.\n")
                finish(-1, results)
                return
            }
            if (schemaScenario) {
                val count = CoachOutputSchemaChecks(context).verify()
                results.putString("stream", "PASS: $count protocol cases validated with Android JSON; malformed fields preserve raw text and expose no edit actions.\n")
                finish(-1, results)
                return
            }
            if (evaluationMode == "coach-evaluation-offline") {
                val count = runBlocking { EvaluationOfflineChecks(this@LocalDataInstrumentation).verify() }
                results.putString("stream", "PASS: $count original prompts, gold isolation, once-only checkpoints, failures/cancellation; zero live requests.\n")
                finish(-1, results)
                return
            }
            if (evaluationMode.isNotEmpty()) {
                val expected = if (evaluationMode == "coach-evaluation-v2") "12-full-original-cases" else "11-original-cases"
                check(evaluationConsent == expected) { "Explicit matching evaluation consent required; no live requests sent" }
                val checks = AiEvaluationChecks(this)
                results.putString("stream", if (evaluationMode == "coach-evaluation-v2") checks.verifyV2(evaluationRunId) else checks.verify())
                finish(-1, results)
                return
            }
            if (restoreStartupScenario.isNotEmpty()) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Startup recovery fixture requires emulator" }
                val checks = CompleteRestoreStartupChecks(this)
                if (restoreStartupScenario.endsWith("-prepare")) runBlocking {
                    checks.prepare(commitStudy = restoreStartupScenario == "restore-startup-prepare")
                    results.putString("stream", "PREPARED: original restore task saved; selected commit stage waiting for process stop and real Activity startup.\n")
                    sendStatus(1, results)
                    awaitCancellation()
                } else checks.verify(shareReading = restoreStartupScenario == "restore-startup-share-resume")
                results.putString("stream", "PASS: real Activity/ViewModel startup resumes pending restore after force-stop; restored drafts loaded, existing practices/bank preserved and recovery-time sharing retained when requested.\n")
                finish(-1, results)
                return
            }
            if (restoreProcessScenario.isNotEmpty()) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Process recovery checks require emulator" }
                runBlocking {
                    val checks = CompleteRestoreChecks(targetContext)
                    if (restoreProcessScenario == "restore-process-prepare") {
                        checks.prepareProcessRetry()
                        results.putString("stream", "PREPARED: isolated database committed; bank not saved; durable task retained; waiting for external process stop.\n")
                        sendStatus(1, results)
                        awaitCancellation()
                    } else checks.resumeProcessRetry()
                }
                results.putString("stream", "PASS: fresh process resumes retained restore task; bank saved; imported history is not duplicated.\n")
                finish(-1, results)
                return
            }
            if (restoreScenario) {
                runBlocking { CompleteRestoreChecks(targetContext).verify() }
                results.putString("stream", "PASS: empty/legacy restore; durable retry after database rollback and post-commit bank failure; no duplicate history; conflicting/corrupt pending tasks preserve local data.\n")
                finish(-1, results)
                return
            }
            if (lifecycleScenario) {
                CoachLifecycleChecks(this).verify()
                results.putString("stream", "PASS: real ViewModel initializes idle; new original draft edits persist on background.\n")
                finish(-1, results)
                return
            }
            if (aiInteractionScenario) {
                runBlocking { AiInteractionChecks(targetContext).verify() }
                results.putString("stream", "PASS: atomic exchange rollback/retry; 40 rapid decisions retain prior choices; oversize replies rejected without truncation; stop/failure preserve complete replies.\n")
                finish(-1, results)
                return
            }
            if (readingAccessMode.isNotEmpty()) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Reading accessibility fixture requires emulator" }
                val checks = ReadingAccessibilityFixture(targetContext)
                runBlocking { if (readingAccessMode == "seed") checks.seed() else checks.verify() }
                results.putString("stream", if (readingAccessMode == "seed") "SEEDED: fresh labelled original reading attempt; existing drafts preserved.\n"
                    else "PASS: ten UI-entered answers and offline submission preserved for the original accessibility fixture.\n")
                finish(-1, results)
                return
            }
            if (examFixture) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Exam sample fixture requires emulator" }
                val input = File(targetContext.getExternalFilesDir(null), "exam-sample.json")
                val papers = ExamCodec.decode(input.readText())
                check(papers.all { it.sample && it.licenseStatus == "original" })
                val store = ExamStore(targetContext)
                store.save(store.merge(store.load(), papers))
                results.putString("stream", "SEEDED: labelled original workflow sample, not genuine exam content.\n")
                finish(-1, results)
                return
            }
            if (reviewFixture) {
                results.putString("stream", runBlocking { CoachTurnFixtures.seed(targetContext) })
                finish(-1, results)
                return
            }
            if (longFeedbackFixture) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) {
                    "Long layout fixture is restricted to an emulator"
                }
                val feedback = "# 模拟长反馈布局检查\n\n这是原创布局测试文本，不是 AI 请求结果或官方解析。\n\n" +
                    (1..100).joinToString("\n\n") { index ->
                        "## 第 $index 段：证据核对\n\n> Libraries provide both books and quiet rooms.\n\n" +
                            "- **原文证据**：both 表明两者都包含。\n- **练习提示**：请核对选项是否缩小范围。\n\n" +
                            "| 原表达 | 建议 |\n| --- | --- |\n| Libraries provides books. | Libraries provide books. |"
                    } + "\n\n## 布局测试结束标记\n\nLONG-FEEDBACK-END-20261018"
                val entry = CoachPractice(title = "模拟器长反馈布局检查 20261018", material = "Libraries provide both books and quiet rooms.",
                    questions = "1. What do libraries provide?", answer = "Books and quiet rooms.", feedback = feedback,
                    model = "layout-fixture (非 AI)", submittedAt = System.currentTimeMillis())
                runBlocking { CoachRepository(StudyDatabase.get(targetContext)).save(entry) }
                results.putString("stream", "SEEDED emulator-only labelled layout fixture: ${feedback.length} characters; ${entry.id}\n")
                finish(-1, results)
                return
            }
            if (translationFixture) {
                val entry = CoachPractice(kind = CoachKind.TRANSLATION.name,
                    title = "Device translation check 20261018-${System.currentTimeMillis()}",
                    material = "大学图书馆不仅提供书籍，也为学生创造安静的学习空间。随着数字资源日益丰富，图书馆仍然发挥着重要作用。")
                runBlocking { CoachRepository(StudyDatabase.get(targetContext)).save(entry) }
                results.putString("stream", "SEEDED original translation material only: ${entry.title}\n")
                finish(-1, results)
                return
            }
            if (uiScenario) {
                CoachUiInstrumentationChecks(this).verify()
                results.putString("stream", "PASS: emulator UI article entry, add question, option selection, offline submission, evidence lookup/save, error reason and background persistence.\n")
                finish(-1, results)
                return
            }
            runBlocking { EvaluationOfflineChecks(this@LocalDataInstrumentation).verify(); AiConnectionStateChecks(targetContext).verify(); OAuthRefreshChecks().verify(); AiStreamChecks(context).verify(); CoachOutputSchemaChecks(context).verify(); ExamInstrumentationChecks(targetContext).verify(); ReadingDiagnosisInstrumentationChecks(targetContext).verify(); verifyMigrationAndTransactions(); verifyReleasedSchemas(); BackupInstrumentationChecks(targetContext).verify(); CompleteRestoreChecks(targetContext).verify(); CoachInstrumentationChecks(targetContext).verify(); ReadingLocationChecks(targetContext).verify(); MicroPracticeInstrumentationChecks(targetContext).verify(); ShareInstrumentationChecks(targetContext).verify(); CoachFailureInstrumentationChecks(targetContext).verify(); AiInteractionChecks(targetContext).verify(); CoachDecisionStateChecks(targetContext, context).verify() }
            results.putString("stream", "PASS: v1-v8 to v9 preserves learning data; drafts/history/evidence survive reopen; generation failure and offline mode preserve source; generated drafts retain provenance and independent answers; shared text preserves drafts and running AI; timeout/429/refresh/stream failures preserve saved feedback and manual retry succeeds; backup v6 roundtrip with turns and v1-v4 imports; existing backup and scheduler checks.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            if (uiScenario) {
                val screenshot = uiAutomation.takeScreenshot()
                if (screenshot != null) java.io.File(targetContext.getExternalFilesDir(null), "g3-ui-failure.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            results.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            finish(0, results)
        }
    }

    private suspend fun verifyReleasedSchemas() {
        for (version in 1..8) {
            val file = File(targetContext.cacheDir, "released-$version-${System.nanoTime()}.db")
            val schema = context.assets.open("com.example.cet6.data.StudyDatabase/$version.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val table = entities.getJSONObject(i)
                    old.execSQL(table.getString("createSql").replace("\u0024{TABLE_NAME}", table.getString("tableName")))
                    val indices = table.optJSONArray("indices")
                    for (j in 0 until (indices?.length() ?: 0)) {
                        old.execSQL(indices!!.getJSONObject(j).getString("createSql")
                            .replace("\u0024{TABLE_NAME}", table.getString("tableName")))
                    }
                }
                val sourceColumn = if (version >= 3) ",source" else ""
                val sourceValue = if (version >= 3) ",'用户来源'" else ""
                old.execSQL("INSERT INTO words (word,meaning,example,dueAt,repetitions,intervalDays$sourceColumn) VALUES ('preserved','原释义','Original sentence.',1800000000000,5,30$sourceValue)")
                old.execSQL("INSERT INTO reading_attempts VALUES (10,'q1','C',0,1000)")
                old.execSQL("INSERT INTO essays VALUES (11,'topic','original draft','original feedback',1001)")
                if (version >= 5) old.execSQL("INSERT INTO coach_practices (id,kind,title,material,questions,answer,feedback,model,errorReason,submittedAt,updatedAt) VALUES ('preserved-coach','READING','title','Original material.','question','A','Original feedback.','test-model','逻辑推断',100,200)")
                if (version >= 6) old.execSQL("UPDATE coach_practices SET savedSentences='Original material.'")
                if (version >= 2) {
                    old.execSQL("INSERT INTO daily_plans VALUES ('2026-10-16',7,15)")
                    old.execSQL("INSERT INTO word_reviews (id,word,remembered,wasNew,studiedOn,createdAt,nextDueAt) VALUES ('old-log','preserved',1,0,'2026-10-16',1000,1800000000000)")
                }
                if (version >= 8) old.execSQL("INSERT INTO coach_turns VALUES ('preserved-turn','preserved-coach','q:46','read-explain/1','assistant','Original reply.','','test-model',100)")
                old.version = version
            }
            val upgraded = Room.databaseBuilder(targetContext, StudyDatabase::class.java, file.absolutePath)
                .addMigrations(*StudyDatabase.ALL_MIGRATIONS).build()
            try {
                val word = upgraded.dao().word("preserved")!!
                check(word.dueAt == 1_800_000_000_000L && word.repetitions == 5 && word.intervalDays == 30)
                check(word.source == if (version >= 3) "用户来源" else "项目原创示例")
                check(upgraded.dao().schedule(word.word) == null) { "migration fabricated FSRS memory" }
                check(upgraded.dao().readingHistory().first().single().answer == "C")
                check(upgraded.dao().essays().first().single().content == "original draft")
                if (version >= 8) {
                    val turn = upgraded.dao().allTurns().single()
                    check(turn.id == "preserved-turn" && turn.content == "Original reply." && turn.locatedQuote.isEmpty())
                } else check(upgraded.dao().allTurns().isEmpty()) { "migration fabricated coach turns" }
                if (version >= 5) {
                    val preserved = upgraded.dao().allCoach().single()
                    check(preserved.answer == "A" && preserved.feedback == "Original feedback.")
                    check(preserved.savedSentences == if (version >= 6) "Original material." else "")
                    check(preserved.sourcePracticeId.isBlank() && preserved.practiceGoal.isBlank())
                }
                if (version >= 2) {
                    val log = upgraded.dao().wordReviewHistory().first().single()
                    check(log.id == "old-log" && log.rating == 0 && log.algorithm == "legacy")
                    check(upgraded.dao().dailyPlan("2026-10-16")?.newGoal == 7)
                }
            } finally { upgraded.close() }
        }
    }

    private suspend fun verifyMigrationAndTransactions() {
        val file = File(targetContext.cacheDir, "migration-${System.nanoTime()}.db")
        val legacyWord = WordEntity("legacy", "保留释义", "Original example.", 123456789L, 4, 14)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE words (word TEXT NOT NULL PRIMARY KEY, meaning TEXT NOT NULL, example TEXT NOT NULL, dueAt INTEGER NOT NULL, repetitions INTEGER NOT NULL, intervalDays INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE reading_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, questionId TEXT NOT NULL, answer TEXT NOT NULL, correct INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE essays (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, topic TEXT NOT NULL, content TEXT NOT NULL, feedback TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("INSERT INTO words VALUES ('legacy','保留释义','Original example.',123456789,4,14)")
            old.execSQL("INSERT INTO reading_attempts VALUES (7,'reading_demo_1','B',1,123)")
            old.execSQL("INSERT INTO essays VALUES (8,'Topic','Draft','Feedback',456)")
            old.version = 1
        }
        fun open() = Room.databaseBuilder(targetContext, StudyDatabase::class.java, file.absolutePath)
            .addMigrations(*StudyDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            check(db.dao().word("legacy") == legacyWord) { "legacy word changed" }
            check(db.dao().readingHistory().first().single().id == 7L)
            check(db.dao().essays().first().single().feedback == "Feedback")
            var repository = StudyRepository(db)
            val date = LocalDate.of(2026, 10, 16)
            check(repository.plan(date) == DailyPlan(date.toString()))
            repository.savePlan(DailyPlan(date.toString(), 3, 5))
            check(repository.plan(date.plusDays(1)) == DailyPlan(date.plusDays(1).toString(), 3, 5))
            check(runCatching { repository.savePlan(DailyPlan(date.toString(), 0, 5)) }.isFailure)
            db.dao().addWords(listOf(WordEntity("new", "新词", "New word.")))
            val now = 1_800_000_000_000L
            val writes = coroutineScope {
                listOf(async { repository.review("new", WordRating.GOOD, now) },
                    async { repository.review("new", WordRating.AGAIN, now) }).awaitAll()
            }
            check(writes.count { it } == 1) { "double tap created multiple reviews" }
            check(db.dao().wordReviewHistory().first().single().wasNew)
            check(db.dao().dailyWordProgress().first().single().newCount == 1)
            check(!repository.review("missing", WordRating.GOOD, now))
            check(repository.review("legacy", WordRating.AGAIN, now))
            check(db.dao().word("legacy")?.repetitions == 0)
            check(db.dao().dailyWordProgress().first().single().reviewCount == 1)
            val scheduled = db.dao().word("new")!!
            val imported = VocabularyImport.parse("""[{"word":"new","meaning":"不要覆盖","example":"changed"},{"word":"imported","meaning":"导入词","example":"Original example."}]""", "测试原创授权")
            check(repository.importWords(imported) == 1)
            check(db.dao().word("new") == scheduled)
            check(db.dao().word("imported")!!.source == "测试原创授权")
            check(repository.importWords(imported) == 0)
            val bundled = targetContext.assets.open("academic_words.csv").use {
                VocabularyImport.parse(VocabularyImport.read(it), "项目原创测试")
            }
            check(bundled.size == 72)
            check(repository.importWords(bundled) == 72)
            check(repository.importWords(bundled) == 0)
            db.close()
            db = open()
            repository = StudyRepository(db)
            check(db.dao().wordReviewHistory().first().size == 2)
            check(db.dao().word("new")!!.dueAt > now)
            check(db.dao().schedule("new")!!.algorithm == WordScheduler.ALGORITHM)
            val due = db.dao().word("new")!!.dueAt
            val before = db.dao().schedule("new")!!
            check(repository.review("new", WordRating.GOOD, due))
            check(db.dao().schedule("new")!!.lastReviewedAt == due)
            check(db.dao().schedule("new")!!.stability >= before.stability)
            check(db.dao().dailyWordProgress().first().single().newCount == 1)
            check(db.dao().dailyWordProgress().first().single().reviewCount == 1)
            check(db.dao().dailyPlan(date.toString())?.newGoal == 3)
            check(db.dao().word("imported")!!.source == "测试原创授权")
        } finally { db.close() }
    }
}
