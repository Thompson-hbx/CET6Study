package com.example.cet6.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ChatGptSession(
    val clientId: String, val subject: String, val email: String,
    val accessToken: String, val refreshToken: String, val idToken: String,
    val scopes: String, val expiresAtMillis: Long
) {
    val canUsePlan: Boolean get() = scopes.split(' ').contains("chatgpt.tokens.use.direct")
    fun toJson() = JSONObject().apply {
        put("clientId", clientId); put("subject", subject); put("email", email)
        put("accessToken", accessToken); put("refreshToken", refreshToken)
        put("idToken", idToken); put("scopes", scopes); put("expiresAtMillis", expiresAtMillis)
    }
    companion object {
        fun fromJson(j: JSONObject) = ChatGptSession(
            j.getString("clientId"), j.getString("subject"), j.optString("email"),
            j.getString("accessToken"), j.optString("refreshToken"),
            j.getString("idToken"), j.optString("scopes"), j.getLong("expiresAtMillis")
        )
    }
}

/** AndroidKeyStore AES-GCM; credentials are never saved in plaintext. */
class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_sessions", Context.MODE_PRIVATE)
    private val hostPrefs = context.getSharedPreferences("host_identity", Context.MODE_PRIVATE)
    private val alias = "cet6_chatgpt_credentials_v1"

    @Synchronized fun hostId(): String {
        hostPrefs.getString("ext_agent_host_id", null)?.let { return it }
        val next = "urn:uuid:${UUID.randomUUID()}"
        check(hostPrefs.edit().putString("ext_agent_host_id", next).commit())
        return next
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    @Synchronized fun load(): ChatGptSession? {
        val saved = prefs.getString("single_account", null) ?: return null
        return try {
            val j = JSONObject(saved)
            val iv = Base64.decode(j.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(j.getString("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            ChatGptSession.fromJson(JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8)))
        } catch (_: Exception) {
            null // Invalid key/session is treated as signed out, never trusted.
        }
    }

    @Synchronized fun save(session: ChatGptSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(session.toJson().toString().toByteArray(Charsets.UTF_8))
        val payload = JSONObject().apply {
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        check(prefs.edit().putString("single_account", payload.toString()).commit())
    }

    /** Retain the verified registration, but remove every reusable credential and hint. */
    @Synchronized fun clear() {
        val saved = load()
        if (saved == null) {
            check(prefs.edit().remove("single_account").commit())
        } else {
            save(saved.copy(accessToken = "", refreshToken = "", idToken = "",
                expiresAtMillis = 0L))
        }
    }
}
