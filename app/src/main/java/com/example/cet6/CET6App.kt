package com.example.cet6

import android.app.Application
import com.example.cet6.diagnostics.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/** Installs local diagnostics before starting Hilt and the application services. */
@HiltAndroidApp
class CET6App : Application() {
    val crashReporter by lazy { CrashReporter(File(noBackupFilesDir, "diagnostics"), "android-${packageManager.getPackageInfo(packageName, 0).versionName}") }

    override fun onCreate() {
        crashReporter.install()
        super.onCreate()
    }
}
