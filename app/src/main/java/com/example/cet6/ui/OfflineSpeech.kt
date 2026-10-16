package com.example.cet6.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/** Uses an installed offline English voice only. Never launches voice installation/download. */
class OfflineSpeech(context: Context, private val update: (Boolean, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var closed = false
    private var currentId = ""
    private var ready = false
    init {
        engine = TextToSpeech(context.applicationContext) { result -> main.post {
            if (!closed) runCatching { initialize(result) }.onFailure {
                ready = false
                update(false, "无法读取本机离线英文语音，学习功能仍可用。")
            }
        } }
    }
    private fun initialize(result: Int) {
        val tts = engine
        if (result != TextToSpeech.SUCCESS || tts == null) {
            update(false, "系统语音引擎不可用，仍可离线看词义与音标。")
            return
        }
        val voice = tts.voices?.filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }?.sortedWith(compareBy({ it.locale.country != "US" }, { it.name }))?.firstOrNull()
        if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            update(false, "未找到已安装的离线英文语音；可在系统语音设置准备后重开 App。学习功能仍可用。")
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = report(utteranceId, "正在朗读（系统离线英文语音）")
            override fun onDone(utteranceId: String?) = report(utteranceId, "朗读完成（系统离线英文语音）")
            @Deprecated("Android callback")
            override fun onError(utteranceId: String?) = report(utteranceId, "朗读失败，可再次点击；词义与音标仍可用。")
        })
        ready = true
        update(true, "系统离线英文语音就绪")
    }
    private fun report(id: String?, text: String) { main.post {
        if (!closed && id == currentId) update(ready, text)
    } }
    fun speak(text: String) {
        if (!ready || closed || text.isBlank()) return
        currentId = UUID.randomUUID().toString()
        if (runCatching { engine?.speak(text.take(2000), TextToSpeech.QUEUE_FLUSH, null, currentId) }.getOrNull() != TextToSpeech.SUCCESS)
            update(ready, "朗读请求失败，请重试。")
    }
    fun stop() {
        val wasSpeaking = currentId.isNotBlank()
        currentId = ""
        runCatching { engine?.stop() }
        if (!closed && ready && wasSpeaking) update(true, "朗读已停止，可再次点击")
    }
    fun close() { closed = true; stop(); runCatching { engine?.shutdown() }; engine = null }
}
