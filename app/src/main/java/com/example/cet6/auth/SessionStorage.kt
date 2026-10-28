package com.example.cet6.auth

/** Internal seam for offline auth checks; production remains backed by Android Keystore. */
internal interface SessionStorage {
    fun load(): ChatGptSession?
    fun save(session: ChatGptSession)
    fun clear()
    fun hostId(): String
}

internal class KeystoreSessionStorage(private val store: SecureSessionStore) : SessionStorage {
    override fun load() = store.load()
    override fun save(session: ChatGptSession) = store.save(session)
    override fun clear() = store.clear()
    override fun hostId() = store.hostId()
}
