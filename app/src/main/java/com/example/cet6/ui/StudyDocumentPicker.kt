package com.example.cet6.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.result.contract.ActivityResultContracts

/** Prefer installed platform DocumentsUI: some OEM pickers omit JSON from recent files. */
class StudyDocumentPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        for (packageName in listOf("com.android.documentsui", "com.google.android.documentsui")) {
            val component = ComponentName(packageName, "com.android.documentsui.picker.PickActivity")
            @Suppress("DEPRECATION")
            val activity = runCatching { context.packageManager.getActivityInfo(component, 0) }.getOrNull() ?: continue
            val system = activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system && activity.enabled && activity.exported && activity.applicationInfo.enabled) {
                return intent.setComponent(component)
            }
        }
        return intent // Standard resolver when platform picker isn't available; never change system defaults.
    }
}
