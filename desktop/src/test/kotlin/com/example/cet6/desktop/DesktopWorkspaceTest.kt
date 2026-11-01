@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.cet6.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

class DesktopWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    // The selected mock's original paragraph lives only in this isolated UI fixture.
    private val source = "我们不必每天制定复杂的学习计划。把困惑写下来，再用自己的话解释，往往更容易发现问题。长期坚持这种习惯，学习会变得更加主动。"
    private val answer = "We do not need a complicated study plan every day. Writing down our questions and explaining them in our own words can help us understand what we need to learn."

    @Test fun editingSubmissionRevisionAndHistoryWorkWithoutOpeningWindows() {
        val directory = temporary.newFolder()
        lateinit var key: String
        onEdt {
            DesktopStore(directory).use { store ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                val model = DesktopModel(store, scope)
                val scene = OffscreenScene(1440, 1024) {
                    DesktopTheme { Surface(Modifier.fillMaxSize(), color = DeskColors.canvas) { DesktopWorkspace(model) } }
                }
                try {
                    scene.frame()
                    scene.click("翻译")
                    scene.click("新建练习", occurrence = 1)
                    key = model.selected!!
                    scene.replace("", "学习日志")
                    scene.replace("", source, fieldIndex = 0)
                    scene.replace("", answer, fieldIndex = 0)
                    assertEquals(answer, JSONObject(store.snapshot().records.getValue(key)).getString("answer"))
                    scene.capture("translation-draft.png")
                    scene.click("批改面板")
                    assertTrue(scene.hasText("暂无批改记录"))
                    scene.capture("translation-feedback.png")
                    scene.click("批改面板")
                    assertFalse(scene.hasText("暂无批改记录"))
                    scene.click("提交作答")
                    assertTrue(JSONObject(store.snapshot().records.getValue(key)).getLong("submittedAt") > 0)
                    assertFalse(scene.nodes().any { it.config.contains(SemanticsActions.SetText) })
                    scene.click("修改一版")
                    assertEquals(2, model.document.records.size)
                    val revisionKey = model.selected!!
                    scene.replace(answer, "$answer Revised.")
                    scene.click("练习记录")
                    scene.replace("", "学习日志")
                    assertTrue(scene.hasText("已提交"))
                    scene.capture("translation-history.png")
                    scene.click("已提交")
                    val originalLabel = scene.nodes().single { node ->
                        node.config.getOrNull(SemanticsProperties.Text)
                            ?.any { it.text.contains("已提交 ·") } == true
                    }
                    val original = generateSequence(originalLabel) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(original.config[SemanticsActions.OnClick].action!!.invoke())
                    scene.frame()
                    assertEquals(key, model.selected)
                    assertEquals(answer, JSONObject(store.snapshot().records.getValue(key)).getString("answer"))
                    assertEquals("$answer Revised.", JSONObject(store.snapshot().records.getValue(revisionKey)).getString("answer"))
                    scene.click("设备同步")
                    assertTrue(scene.hasText("连接你的个人服务器"))
                    val footer = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == model.status } == true }
                    assertTrue(
                        "Sync status is not visible: ${footer.boundsInRoot}",
                        footer.boundsInRoot.width > 0 && footer.boundsInRoot.height > 0 && footer.boundsInRoot.top >= 0 && footer.boundsInRoot.bottom <= 1024,
                    )
                    scene.capture("device-sync.png")
                    scene.click("生词")
                    assertTrue(scene.hasText("还没有同步的生词"))
                    scene.click("写作")
                    assertTrue(scene.hasText("从一份写作草稿开始"))
                } finally {
                    scene.close()
                    scope.cancel()
                }
            }
            DesktopStore(directory).use { store ->
                assertEquals(2, store.snapshot().records.size)
                assertEquals(answer, JSONObject(store.snapshot().records.getValue(key)).getString("answer"))
            }
        }
    }

    @Test fun compactLayoutKeepsPrimaryControlsAndFeedbackUsable() = onEdt {
        DesktopStore(temporary.newFolder()).use { store ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val model = DesktopModel(store, scope)
            model.section = "READING"
            model.create()
            val scene = OffscreenScene(1000, 680) {
                DesktopTheme { Surface(Modifier.fillMaxSize(), color = DeskColors.canvas) { DesktopWorkspace(model) } }
            }
            try {
                scene.frame()
                scene.click("提交作答")
                assertEquals("请先填写题目材料与作答", model.status)
                scene.click("批改面板")
                assertTrue(scene.hasText("暂无批改记录"))
                scene.capture("reading-compact-feedback.png")
                val submit = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "提交作答" } == true }
                assertTrue(submit.boundsInRoot.left >= 0 && submit.boundsInRoot.right <= 1000)
                assertTrue(submit.boundsInRoot.top >= 0 && submit.boundsInRoot.bottom <= 680)
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    @Test fun savedCorrectionsReachTodayReviewAndTheMargin() = onEdt {
        DesktopStore(temporary.newFolder()).use { store ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val model = DesktopModel(store, scope)
            model.section = "WRITING"
            model.create()
            val key = model.selected!!
            val essay = "Everyone in our class have a different way of learning, and a journal help us notice patterns."
            model.edit(key, "title", "The Value of Reflection")
            model.edit(key, "material", "以 reflection 为话题写一篇 150–200 词的短文。")
            model.edit(key, "answer", essay)
            model.submit(key)
            // An original sample review in the saved protocol, as the phone would sync it.
            val practiceId = JSONObject(store.snapshot().records.getValue(key)).getString("id")
            val review = listOf(
                """{"type":"issue","quote":"have","fix":"has","reason":"everyone 作主语时谓语用单数。","category":"主谓一致"}""",
                """{"type":"issue","quote":"help","fix":"helps","reason":"a journal 为单数主语。","category":"主谓一致"}""",
                """{"type":"issue","quote":"notice patterns","fix":"spot recurring patterns","level":"better","category":"用词"}""",
            ).joinToString("\n")
            val turn = JSONObject().put("id", "t1").put("practiceId", practiceId).put("anchor", "review").put("kind", "write-review/1")
                .put("role", "assistant").put("content", review).put("state", "done").put("model", "sample").put("createdAt", 1L)
            val before = store.snapshot()
            assertTrue(store.apply(before, com.example.cet6.sync.SyncDocument(before.records + ("turns:t1" to turn.toString()))))
            // A fresh model reads the synced turn the way the app does after a restart.
            val synced = DesktopModel(store, scope)
            val scene = OffscreenScene(1440, 1024) {
                DesktopTheme { Surface(Modifier.fillMaxSize(), color = DeskColors.canvas) { DesktopWorkspace(synced) } }
            }
            try {
                scene.frame()
                assertTrue(scene.hasText("最近一条朱批"))
                scene.capture("today.png")
                scene.click("复盘")
                assertTrue(scene.hasText("写作 · The Value of Reflection"))
                scene.capture("review.png")
                scene.click("写作 · The Value of Reflection")
                assertEquals(key, synced.selected)
                assertTrue(scene.hasText("必改 2 · 润色 1"))
                scene.capture("writing-margin.png")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun OffscreenScene.frame() {
        // Advance actual Compose frames; no wall-clock delay or desktop window is needed.
        repeat(3) { render(System.nanoTime()).close() }
    }

    private fun OffscreenScene.nodes(): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return owners.flatMap { descend(it.unmergedRootSemanticsNode) }
    }

    private fun OffscreenScene.hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun OffscreenScene.click(text: String, occurrence: Int? = null) {
        val matches = nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        val leaf = if (occurrence == null) matches.single() else matches[occurrence]
        val target = generateSequence(leaf) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
        frame()
    }

    private fun OffscreenScene.replace(old: String, value: String, fieldIndex: Int = 0) {
        val fields = nodes().filter { it.config.contains(SemanticsActions.SetText) && it.config.getOrNull(SemanticsProperties.EditableText)?.text == old }
        assertTrue("No editable field '$old' found", fields.size > fieldIndex)
        assertTrue(fields[fieldIndex].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        frame()
    }

    private fun OffscreenScene.capture(name: String) {
        val directory = File("build/design-evidence").apply { mkdirs() }
        render(System.nanoTime()).use { image ->
            image.encodeToData()!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }

    private class OffscreenScene(width: Int, height: Int, content: @Composable () -> Unit) : AutoCloseable {
        val owners = mutableListOf<SemanticsOwner>()
        private val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height)
        private val platform = object : PlatformContext by PlatformContext.Empty {
            override val windowInfo = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(width, height)
            }
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                    owners += semanticsOwner
                }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                    owners -= semanticsOwner
                }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
            }
        }
        private val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(width, height),
            composeSceneContext = object : ComposeSceneContext {
                override val platformContext = platform
            },
        )
        init {
            scene.setContent(content)
        }
        fun render(nanos: Long): org.jetbrains.skia.Image {
            scene.render(surface.canvas.asComposeCanvas(), nanos)
            return surface.makeImageSnapshot()
        }
        override fun close() {
            scene.close()
            surface.close()
        }
    }
}
