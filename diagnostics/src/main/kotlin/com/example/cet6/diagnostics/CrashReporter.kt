package com.example.cet6.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/** Local, bounded metadata only: no exception messages, thread names, paths or user content. */
class CrashReporter(private val directory: File, private val version: String) {
    private val report get() = File(directory, "last-crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RecordingExceptionHandler) return
        // With no default handler the JVM would print the original exception to stderr.
        Thread.setDefaultUncaughtExceptionHandler(
            handler(previous ?: Thread.UncaughtExceptionHandler { _, error -> error.printStackTrace() }),
        )
    }

    internal fun handler(previous: Thread.UncaughtExceptionHandler): Thread.UncaughtExceptionHandler = RecordingExceptionHandler(this, previous)

    /** A failed/oversized read is reported to the caller rather than presented as an empty history. */
    @Throws(IOException::class)
    fun read(): String? {
        if (!report.exists()) return null
        require(report.length() <= MAX_REPORT_BYTES) { "Invalid crash report size" }
        return report.inputStream().use { stream ->
            val bytes = ByteArray(MAX_REPORT_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= MAX_REPORT_BYTES) { "Invalid crash report size" }
            String(bytes, 0, count, Charsets.UTF_8)
        }
    }

    @Synchronized
    internal fun record(error: Throwable) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostics directory" }
        val temporary = File(directory, "last-crash.tmp")
        temporary.outputStream().use { it.write(format(error).toByteArray(Charsets.UTF_8)) }
        try {
            Files.move(temporary.toPath(), report.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), report.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun format(error: Throwable): String = buildString {
        appendLine("Zhupi crash metadata v1")
        appendLine("time=${Instant.now()}")
        appendLine("version=${identifier(version)}")
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && seen.add(current) && depth < MAX_CAUSES) {
            appendLine("cause[$depth]=${identifier(current.javaClass.name)}")
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                appendLine("  ${identifier(frame.className)}.${identifier(frame.methodName)}:${frame.lineNumber}")
            }
            current = current.cause
            depth++
        }
        appendLine("Messages, thread names, file paths and user content are omitted.")
    }.take(MAX_REPORT_BYTES)

    private fun identifier(value: String): String = if (value.length <= MAX_IDENTIFIER && value.matches(Regex("[A-Za-z0-9_.$<>-]+"))) {
        value
    } else {
        "omitted"
    }

    companion object {
        private const val MAX_REPORT_BYTES = 32_768
        private const val MAX_IDENTIFIER = 160
        private const val MAX_CAUSES = 4
        private const val MAX_FRAMES = 24
    }
}

/** Saving must never intercept Android's process termination or the JVM's original error reporting. */
private class RecordingExceptionHandler(
    private val reporter: CrashReporter,
    private val previous: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            reporter.record(error)
        } catch (_: Exception) {
            // Disk/permission failure is secondary; always deliver the original crash to its handler.
        } finally {
            previous.uncaughtException(thread, error)
        }
    }
}
