package com.example.cet6

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import androidx.room.Room
import com.example.cet6.ai.AiProvider
import com.example.cet6.ai.ReasoningEffort
import com.example.cet6.ai.ReasoningFormat
import com.example.cet6.ai.DeepReviewRejectedException
import org.json.JSONObject
import okio.Buffer
import com.example.cet6.data.EssayEntity
import com.example.cet6.data.StudyDatabase
import com.example.cet6.ui.AiConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** All HTTP is intercepted; only uniquely named test preferences and an in-memory DB are used. */
class AiConnectionStateChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val prefix = "ai-state-test-${UUID.randomUUID()}-"
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name in setOf("study_ui", "secure_sessions", "host_identity", "secure_api_profiles"))
                return super.getSharedPreferences(prefix + name, mode).also { preferenceNames += prefix + name }
            }
            override fun startActivity(intent: Intent) = error("Offline check must never open OAuth")
        }
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val mode = AtomicReference("success")
        val generations = AtomicInteger()
        val lastPayload = AtomicReference<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val models = request.url.encodedPath.endsWith("/models")
            check(request.url.host in setOf("api.deepseek.com", "fixture.invalid")) { "Unexpected offline destination" }
            if (!models) {
                generations.incrementAndGet()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                lastPayload.set(JSONObject(buffer.readUtf8()))
            }
            val code = if (!models) mode.get().toIntOrNull() ?: 200 else 200
            val body = if (models) """{"data":[{"id":"listed-model"},{"id":"second-model"}]}""" else if (code == 429) "{}" else {
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Original reply.\"}}]}\n\n" +
                    if (mode.get() == "partial") "" else
                        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
            val type = if (models || code != 200) "application/json" else "text/event-stream"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("offline fixture")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        fun state() = AiConnectionState(isolated, scope, http) { essay, feedback ->
            db.dao().addEssay(EssayEntity(topic = essay.first, content = essay.second, feedback = feedback))
        }
        suspend fun idle(value: AiConnectionState) = withTimeout(5000) { while (value.busy) yield() }
        try {
            val prefs = isolated.getSharedPreferences("study_ui", Context.MODE_PRIVATE)
            check(prefs.edit().putString("model", "legacy-chatgpt-model").commit())
            val ai = state()
            check(ai.provider == AiProvider.CHATGPT && !ai.aiReady && !ai.hasPlan && ai.sessionEmail.isEmpty())
            check(ai.chosenModel == "legacy-chatgpt-model")
            ai.selectProvider(AiProvider.DEEPSEEK)
            ai.selectModel("  manual-deep-model  ")
            check(ai.saveApiConnection("https://ignored.invalid/", "original-fixture-key"))
            check(ai.apiBaseUrl == "https://api.deepseek.com/" && ai.aiReady)
            ai.refreshModels()
            idle(ai)
            check(ai.models.map { it.slug } == listOf("listed-model", "second-model"))
            check(ai.chosenModel == "manual-deep-model") { "Manual compatible model was replaced" }
            ai.ask("Original offline prompt", "Original topic" to "Original draft")
            idle(ai)
            val saved = db.dao().essays().first().single()
            check(saved.content == "Original draft" && saved.feedback == "Original reply.")
            check(ai.result == saved.feedback && ai.status.contains("完整回复"))
            check(generations.get() == 1)

            for (failure in listOf("partial", "429")) {
                mode.set(failure)
                ai.ask("Original failure prompt", "Failed topic" to "Must not save")
                idle(ai)
                check(ai.status.contains("调用失败") && ai.status.contains("不视为完成"))
                check(ai.result == if (failure == "partial") "Original reply." else "")
                check(db.dao().essays().first() == listOf(saved)) { "Failed request changed offline history" }
                check(ai.aiReady && ai.chosenModel == "manual-deep-model")
            }
            check(generations.get() == 3) { "Failed generation retried automatically" }
            mode.set("success")
            val started = CompletableDeferred<Unit>()
            val request = scope.async {
                ai.streamCoach("Original cancel prompt") {
                    check(Looper.myLooper() == Looper.getMainLooper()) { "Delta escaped the main thread" }
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5000) { started.await() }
            check(ai.busy)
            ai.selectProvider(AiProvider.CUSTOM)
            ai.selectModel("must-not-change")
            ai.clearApiConnection()
            check(!ai.saveApiConnection("", "must-not-save"))
            check(ai.provider == AiProvider.DEEPSEEK && ai.hasApiKey && ai.chosenModel == "manual-deep-model")
            request.cancelAndJoin()
            check(!ai.busy && ai.aiReady && generations.get() == 4)
            check(db.dao().essays().first() == listOf(saved))

            ai.selectProvider(AiProvider.CUSTOM)
            ai.selectModel("custom-model")
            check(ai.saveApiConnection("https://fixture.invalid/v1", "original-custom-fixture-key"))
            check(!ai.saveApiConnection("https://fixture.invalid/other", ""))
            check(ai.apiBaseUrl == "https://fixture.invalid/v1/") { "Changed destination reused the old key" }
            check(ai.saveApiConnection("https://fixture.invalid/v1", ""))
            val reopened = state()
            idle(reopened)
            check(reopened.provider == AiProvider.CUSTOM && reopened.hasApiKey && reopened.chosenModel == "custom-model")
            check(reopened.apiBaseUrl == "https://fixture.invalid/v1/")
            reopened.clearApiConnection()
            check(!reopened.aiReady && reopened.models.isEmpty() && reopened.chosenModel.isEmpty())
            reopened.selectProvider(AiProvider.DEEPSEEK)
            idle(reopened)
            check(reopened.aiReady && reopened.chosenModel == "manual-deep-model")
            reopened.selectProvider(AiProvider.CHATGPT)
            check(!reopened.aiReady && reopened.chosenModel == "legacy-chatgpt-model")
            check(generations.get() == 4) { "Provider restoration sent a generation request" }
            check(db.dao().essays().first() == listOf(saved))

            // Deep-review preferences survive reopening and stay isolated by provider, URL and model.
            reopened.selectProvider(AiProvider.DEEPSEEK)
            idle(reopened)
            check(!reopened.deepReviewEnabled && reopened.reviewFormat == ReasoningFormat.DEEPSEEK)
            reopened.setDeepReview(true)
            reopened.selectReviewEffort(ReasoningEffort.MAX)
            val deep = state()
            idle(deep)
            check(deep.deepReviewEnabled && deep.reviewEffort == ReasoningEffort.MAX)
            check(deep.reviewFormat == ReasoningFormat.DEEPSEEK)
            deep.streamCoach("Offline review", deepReview = true) {}
            check(lastPayload.get().getString("reasoning_effort") == "max")
            check(lastPayload.get().getJSONObject("thinking").getString("type") == "enabled")
            deep.streamCoach("Offline hint") {}
            check(!lastPayload.get().has("reasoning_effort") && !lastPayload.get().has("thinking"))
            deep.selectModel("other-model")
            check(!deep.deepReviewEnabled && deep.reviewEffort == ReasoningEffort.HIGH)
            deep.selectModel("manual-deep-model")
            check(deep.deepReviewEnabled && deep.reviewEffort == ReasoningEffort.MAX)
            deep.setDeepReview(false)
            deep.streamCoach("Disabled review", deepReview = true) {}
            check(!lastPayload.get().has("reasoning_effort"))
            deep.setDeepReview(true)
            val callsBeforeRejected = generations.get()
            mode.set("400")
            val rejected = runCatching { deep.streamCoach("Rejected review", deepReview = true) {} }.exceptionOrNull()
            check(rejected is DeepReviewRejectedException && !deep.busy)
            check(generations.get() == callsBeforeRejected + 1) { "Rejected depth retried automatically" }
            mode.set("success")
            deep.selectProvider(AiProvider.CUSTOM)
            deep.selectModel("shared-model")
            check(deep.saveApiConnection("https://fixture.invalid/v1", "original-custom-fixture-key"))
            check(!deep.deepReviewEnabled && deep.reviewFormat == ReasoningFormat.OPENAI)
            deep.setDeepReview(true)
            deep.selectReviewEffort(ReasoningEffort.XHIGH)
            deep.streamCoach("OpenAI-compatible review", deepReview = true) {}
            check(lastPayload.get().getString("reasoning_effort") == "xhigh")
            check(!lastPayload.get().has("thinking"))
            deep.selectReviewFormat(ReasoningFormat.DEEPSEEK)
            check(deep.reviewEffort == ReasoningEffort.HIGH)
            deep.selectReviewEffort(ReasoningEffort.MAX)
            check(deep.saveApiConnection("https://fixture.invalid/other", "other-fixture-key"))
            check(!deep.deepReviewEnabled && deep.reviewFormat == ReasoningFormat.OPENAI)
            check(deep.saveApiConnection("https://fixture.invalid/v1", "original-custom-fixture-key"))
            check(deep.deepReviewEnabled && deep.reviewFormat == ReasoningFormat.DEEPSEEK && deep.reviewEffort == ReasoningEffort.MAX)
            deep.selectProvider(AiProvider.CHATGPT)
            check(!deep.deepReviewEnabled && deep.reviewFormat == ReasoningFormat.OPENAI)
            check(db.dao().essays().first() == listOf(saved)) { "Depth configuration changed learner history" }

        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            for (name in preferenceNames) {
                check(name.startsWith(prefix))
                check(context.deleteSharedPreferences(name)) { "Could not remove isolated test preferences" }
            }
        }
    }
}
