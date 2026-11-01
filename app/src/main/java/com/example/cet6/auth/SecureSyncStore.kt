package com.example.cet6.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.cet6.sync.SyncConnection
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A separate Keystore alias prevents sync pairing from accessing AI credentials. */
class SecureSyncStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_sync_secure", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val alias = "cet6_device_sync_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun load(): SyncConnection? {
        val text = prefs.getString("connection", null) ?: return null
        val saved = JSONObject(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(saved.getString("iv"), Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(saved.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        return SyncConnection.create(json.getString("url"), json.getString("secret"))
    }
    fun save(connection: SyncConnection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(JSONObject().put("url", connection.baseUrl).put("secret", connection.secret).toString().toByteArray())
        val json = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        check(prefs.edit().putString("connection", json.toString()).commit()) { "同步配置未保存" }
    }
}
