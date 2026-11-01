package com.example.cet6

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cet6.data.SharedReading
import com.example.cet6.ui.AppRoot
import com.example.cet6.ui.StudyViewModel
import com.example.cet6.ui.theme.CET6Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: StudyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CET6Theme(style = vm.uiStyle) {
                AppRoot(vm, (application as CET6App).crashReporter)
            }
        }
        if (savedInstanceState == null) receiveShare(intent)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferSmoothRefreshRate()
    }

    private fun preferSmoothRefreshRate() {
        val display = window.decorView.display ?: return
        val current = display.mode
        val refreshRate = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: return
        // Request smooth UI rendering without forcing a resolution or overriding system power policies.
        window.attributes = window.attributes.apply { preferredRefreshRate = refreshRate }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }
    private fun receiveShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        try {
            val input = SharedReading.from(
                incoming.type,
                incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
                incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            )
            vm.coach.importReading(input)
        } catch (e: IllegalArgumentException) {
            vm.coach.shareFailed(e.message ?: "分享内容无法导入")
        } catch (_: Exception) {
            vm.coach.shareFailed("分享内容无法读取，请重新分享纯文本。")
        } finally {
            incoming.action = Intent.ACTION_MAIN
            incoming.removeExtra(Intent.EXTRA_TEXT)
            incoming.removeExtra(Intent.EXTRA_SUBJECT)
        }
    }
    override fun onResume() {
        super.onResume()
        preferSmoothRefreshRate()
        vm.refreshStudyClock()
    }
    override fun onStop() {
        vm.saveDrafts()
        super.onStop()
    }
}
