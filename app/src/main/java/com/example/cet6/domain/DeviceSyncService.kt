package com.example.cet6.domain

import android.content.Context
import com.example.cet6.auth.SecureSyncStore
import com.example.cet6.data.DeviceSyncRepository
import com.example.cet6.sync.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

data class DeviceSyncState(val address: String = "", val enabled: Boolean = false, val busy: Boolean = false,
    val status: String = "尚未配置设备同步", val conflicts: List<SyncConflict> = emptyList())

class DeviceSyncService(private val context: Context, private val repository: DeviceSyncRepository,
    private val practice: PracticeService, private val scope: CoroutineScope, startupReady: Deferred<Unit>,
    private val canApply: () -> Boolean) {
    private val secure = SecureSyncStore(context)
    private val prefs = context.getSharedPreferences("device_sync", Context.MODE_PRIVATE)
    private var connection: SyncConnection? = null
    private val choices = mutableListOf<ConflictChoice>()
    val state = ServiceState(DeviceSyncState())

    init {
        try {
            connection = secure.load()
            state.update { it.copy(address = connection?.baseUrl.orEmpty(), enabled = prefs.getBoolean("enabled", false),
                status = if (connection == null) "尚未配置设备同步" else "已配置；联网后可同步") }
        } catch (_: Exception) { state.update { it.copy(status = "同步配置读取失败，请重新保存服务器地址与配对密钥") } }
        scope.launch {
            startupReady.await()
            while (isActive) {
                if (state.value.enabled && state.value.conflicts.isEmpty()) synchronize()
                delay(30_000) // Foreground polling; no background-service or instant-delivery guarantee.
            }
        }
    }

    fun configure(address: String, secret: String): Boolean {
        if (state.value.busy) return false
        return try {
            val next = SyncConnection.create(address, secret)
            secure.save(next)
            connection = next
            choices.clear()
            check(prefs.edit().putBoolean("enabled", true).putString("generation", UUID.randomUUID().toString()).commit())
            state.update { it.copy(address = next.baseUrl, enabled = true, conflicts = emptyList(), status = "连接已加密保存；正在同步") }
            synchronize()
            true
        } catch (e: IllegalArgumentException) { state.update { it.copy(status = e.message.orEmpty()) }; false
        } catch (_: Exception) { state.update { it.copy(status = "同步配置保存失败，请重试") }; false }
    }

    fun setEnabled(enabled: Boolean) {
        if (state.value.busy) return
        check(prefs.edit().putBoolean("enabled", enabled).commit())
        state.update { it.copy(enabled = enabled) }
        if (enabled) synchronize()
    }

    fun resolve(conflict: SyncConflict, useRemote: Boolean) {
        choices.removeAll { it.conflict.key == conflict.key }
        choices += ConflictChoice(conflict, useRemote)
        synchronize()
    }

    fun synchronize() {
        val selected = connection ?: return
        if (state.value.busy || !canApply()) return
        state.update { it.copy(busy = true, status = "正在同步……") }
        val generation = prefs.getString("generation", "initial") ?: "initial"
        val decisions = choices.toList()
        scope.launch {
            try {
                check(practice.flush()) { "请等待练习保存或 AI 请求结束后再同步" }
                val store = object : SyncLocalStore {
                    override fun snapshot() = runBlocking { repository.snapshot() }
                    override fun apply(expected: SyncDocument, updated: SyncDocument) = runBlocking(Dispatchers.Main.immediate) {
                        if (!canApply()) false else practice.applyExternal { repository.apply(expected, updated) }
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    SyncEngine(store, FileSyncStateStore(File(context.noBackupFilesDir, "sync/${digest(selected.baseUrl)}/$generation")),
                        HttpSyncTransport(selected)).synchronize(decisions)
                }
                state.update { it.copy(status = result.message, conflicts = result.conflicts) }
                if (result.conflicts.isEmpty()) choices.clear()
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { state.update { it.copy(status = "同步未完成：${e.message}。本机记录已保留。") }
            } catch (e: IllegalStateException) { state.update { it.copy(status = "同步未完成：${e.message}。本机记录已保留。") }
            } catch (_: Exception) { state.update { it.copy(status = "同步未完成，请检查网络与服务器；本机记录已保留，稍后会重试。") }
            } finally { state.update { it.copy(busy = false) } }
        }
    }
}
