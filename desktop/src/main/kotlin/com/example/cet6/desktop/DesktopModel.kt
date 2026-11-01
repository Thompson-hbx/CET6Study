package com.example.cet6.desktop

import androidx.compose.runtime.*
import com.example.cet6.sync.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DesktopModel(val store: DesktopStore, private val scope: CoroutineScope) {
    var document by mutableStateOf(store.snapshot()); private set
    var selected by mutableStateOf<String?>(null)
    var section by mutableStateOf("TODAY")
    var status by mutableStateOf("所有编辑自动保存在这台电脑")
    var busy by mutableStateOf(false); private set
    var saveFailed by mutableStateOf(false); private set
    var conflicts by mutableStateOf<List<SyncConflict>>(emptyList()); private set
    var address by mutableStateOf(""); private set
    var enabled by mutableStateOf(false); private set
    private val secrets = DesktopSecrets(store.directory)
    private var connection: SyncConnection? = null
    private val choices = mutableListOf<ConflictChoice>()
    private val preferences = File(store.directory, "preferences.json")
    private var generation = "initial"

    init {
        try {
            connection = secrets.load()
            address = connection?.baseUrl.orEmpty()
            preferences.takeIf { it.exists() }?.let {
                val saved = JSONObject(it.readText())
                enabled = saved.getBoolean("enabled")
                generation = saved.optString("generation", "initial")
            }
        } catch (_: Exception) { status = "同步配置读取失败，请重新保存连接；学习数据仍保留" }
        scope.launch {
            while (isActive) {
                if (enabled && conflicts.isEmpty()) synchronize()
                delay(30_000)
            }
        }
    }
    fun create() = attempt {
        selected = store.create(section)
        document = store.snapshot()
        status = "新草稿已保存到本机"
    }
    fun revise(row: JSONObject) = attempt {
        selected = store.create(row.getString("kind"), row)
        document = store.snapshot()
        status = "修改稿已保存，原作答仍保留"
    }
    fun edit(key: String, field: String, value: String) = attempt {
        store.edit(key) { row ->
            check(row.getLong("submittedAt") == 0L) { "已提交记录请先创建修改稿" }
            row.put(field, value).put("updatedAt", System.currentTimeMillis())
        }
        document = store.snapshot()
        status = "已保存到这台电脑"
    }
    fun submit(key: String) = attempt {
        store.edit(key) { row ->
            require(row.getString("material").isNotBlank() && row.getString("answer").isNotBlank()) { "请先填写题目材料与作答" }
            val now = System.currentTimeMillis()
            row.put("submittedAt", now).put("updatedAt", now)
        }
        document = store.snapshot()
        status = "作答已提交并保存在本机"
    }
    fun configure(url: String, secret: String): Boolean {
        if (busy) return false
        return try {
            val next = SyncConnection.create(url, secret)
            secrets.save(next)
            connection = next; address = next.baseUrl
            conflicts = emptyList(); choices.clear()
            generation = UUID.randomUUID().toString()
            setAutoSync(true)
            synchronize()
            true
        } catch (e: IllegalArgumentException) { status = e.message.orEmpty(); false
        } catch (_: Exception) { status = "连接保存失败，请检查 Windows 用户加密存储和数据目录"; false }
    }
    fun setAutoSync(value: Boolean) = attempt {
        atomicWrite(preferences, JSONObject().put("enabled", value).put("generation", generation).toString())
        enabled = value
    }
    fun resolve(conflict: SyncConflict, useRemote: Boolean) {
        choices.removeAll { it.conflict.key == conflict.key }
        choices += ConflictChoice(conflict, useRemote)
        synchronize()
    }
    fun synchronize() {
        val selectedConnection = connection ?: return
        if (busy) return
        busy = true; status = "正在同步……"
        val selectedGeneration = generation
        val decisions = choices.toList()
        scope.launch {
            try {
                val adapter = object : SyncLocalStore {
                    override fun snapshot() = store.snapshot()
                    override fun apply(expected: SyncDocument, updated: SyncDocument): Boolean = runBlocking(Dispatchers.Main.immediate) {
                        store.apply(expected, updated).also { document = store.snapshot() }
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    SyncEngine(adapter, FileSyncStateStore(File(store.directory, "sync/${digest(selectedConnection.baseUrl)}/$selectedGeneration")),
                        HttpSyncTransport(selectedConnection)).synchronize(decisions)
                }
                conflicts = result.conflicts; status = result.message
                if (conflicts.isEmpty()) choices.clear()
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { status = "同步未完成：${e.message}；本机记录已保留"
            } catch (e: IllegalStateException) { status = "同步未完成：${e.message}；本机记录已保留"
            } catch (_: Exception) { status = "同步未完成，请检查网络与服务器；本机记录已保留"
            } finally { busy = false }
        }
    }
    private fun attempt(action: () -> Unit) {
        try { action(); saveFailed = false
        } catch (e: IllegalArgumentException) { saveFailed = true; status = e.message.orEmpty()
        } catch (e: IllegalStateException) { saveFailed = true; status = e.message.orEmpty()
        } catch (_: Exception) { saveFailed = true; status = "本机保存失败，请保留窗口和输入内容，检查数据目录后重试" }
    }
}
