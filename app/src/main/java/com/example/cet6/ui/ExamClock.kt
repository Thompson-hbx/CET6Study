package com.example.cet6.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cet6.data.TrainingClock
import com.example.cet6.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A 30-minute exam clock; it is a study aid and never locks the editor. */
@Composable
fun ExamClock(key: String, minutes: Int = 30) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("study_sessions", android.content.Context.MODE_PRIVATE) }
    val stored = remember(key) { runCatching { prefs.getString("clock_$key", null)?.let(TrainingClock::decode) } }
    var clock by remember(key) { mutableStateOf(stored.getOrNull()) }
    var saveFailed by remember(key) { mutableStateOf(stored.isFailure) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val boot = remember { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(clock) { while (clock != null) {
        now = System.currentTimeMillis(); elapsedNow = SystemClock.elapsedRealtime(); delay(1000)
    } }
    val startedAt = clock?.wallStartedAt ?: 0L
    val left = minutes * 60 - (clock?.elapsedMillis(now, elapsedNow, boot) ?: 0L) / 1000

    // The face alone ("30 分钟", "12:04") does not say it is a timer or what tapping does.
    val estimated = clock?.estimated(elapsedNow, boot) == true
    val spoken = (if (saveFailed) "计时未保存，请点按重试。" else if (estimated) "设备重启后按系统时间估算。" else "") + when {
        startedAt == 0L -> "考试计时 $minutes 分钟，点按开始"
        left >= 0 -> "计时中，剩余 ${left / 60} 分 ${left % 60} 秒，点按停止"
        else -> "已超时 ${-left / 60} 分 ${-left % 60} 秒，点按停止"
    }
    Column {
        TextButton(onClick = {
            if (!saving) {
                saving = true
                val next = if (clock == null) TrainingClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot) else null
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        val editor = prefs.edit()
                        if (next == null) editor.remove("clock_$key") else editor.putString("clock_$key", next.encode())
                        editor.commit()
                    }
                    saveFailed = !saved
                    if (saved) { clock = next; now = System.currentTimeMillis(); elapsedNow = SystemClock.elapsedRealtime() }
                    saving = false
                }
            }
        }, enabled = !saving,
            modifier = Modifier.semantics { contentDescription = spoken }) {
            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text((if (estimated) "约 " else "") + when {
                saveFailed -> "计时未保存"
                startedAt == 0L -> "$minutes 分钟"
                left >= 0 -> "%d:%02d".format(left / 60, left % 60)
                else -> "超时 %d:%02d".format(-left / 60, -left % 60)
            }, style = MaterialTheme.typography.labelLarge, color = when {
                startedAt == 0L -> MaterialTheme.colorScheme.primary
                left < 0 -> Tone.Must.color()
                left < 300 -> Tone.Better.color()
                else -> MaterialTheme.colorScheme.primary
            })
        }
    }
}

