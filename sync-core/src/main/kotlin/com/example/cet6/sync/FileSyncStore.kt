package com.example.cet6.sync

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic checkpoints and conflict archives; these contain study data, never connection secrets. */
class FileSyncStateStore(private val directory: File) : SyncStateStore {
    override fun load(): RemoteSnapshot? = File(directory, "checkpoint.json").takeIf(File::exists)?.let {
        RemoteSnapshot.parse(JSONObject(it.readText(Charsets.UTF_8)))
    }
    override fun save(snapshot: RemoteSnapshot) = atomicWrite(File(directory, "checkpoint.json"), snapshot.json().toString())
    override fun archive(local: SyncDocument, remote: RemoteSnapshot) {
        val json = JSONObject().put("local", local.json()).put("remote", remote.json())
        // Content addressing makes retries idempotent, and preserves every distinct conflict pair.
        val text = json.toString()
        val file = File(directory, "versions/${digest(text)}.json")
        if (!file.exists()) atomicWrite(file, text)
    }
}

fun atomicWrite(file: File, text: String) {
    check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "无法创建本地数据目录" }
    val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
