package com.example.cet6.auth

/** Test seam for offline auth checks across module boundaries; production remains backed by Android Keystore. */
interface SessionStorage {
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
