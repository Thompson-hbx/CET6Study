package com.example.cet6.desktop

import com.example.cet6.sync.SyncConnection
import com.example.cet6.sync.atomicWrite
import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Pairing credentials are protected for the current Windows user by DPAPI. */
class DesktopSecrets(private val directory: File) {
    private val file get() = File(directory, "connection.protected")
    fun load(): SyncConnection? {
        if (!file.exists()) return null
        val bytes = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(file.readText(Charsets.UTF_8)))
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return SyncConnection.create(json.getString("url"), json.getString("secret"))
        } finally {
            bytes.fill(0)
        }
    }
    fun save(connection: SyncConnection) {
        val bytes = JSONObject().put("url", connection.baseUrl).put("secret", connection.secret).toString().toByteArray(Charsets.UTF_8)
        try {
            atomicWrite(file, Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(bytes)))
        } finally {
            bytes.fill(0)
        }
    }
}
