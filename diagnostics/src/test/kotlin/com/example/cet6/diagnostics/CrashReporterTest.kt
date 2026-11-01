package com.example.cet6.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class CrashReporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reportsOnlyBoundedMetadataAndOverwritesThePreviousReport() {
        val directory = temporary.newFolder()
        val reporter = CrashReporter(directory, "test-build")
        assertNull(reporter.read())
        val error = IllegalStateException("PRIVATE_MESSAGE", IllegalArgumentException("PRIVATE_CAUSE"))
        error.stackTrace = arrayOf(StackTraceElement("example.Sample", "run", "PRIVATE_PATH", 42))
        reporter.record(error)
        val first = reporter.read()!!
        assertTrue(first.contains("java.lang.IllegalStateException"))
        assertTrue(first.contains("example.Sample.run:42"))
        assertFalse(first.contains("PRIVATE_"))
        reporter.record(UnsupportedOperationException("PRIVATE_NEXT"))
        val next = reporter.read()!!
        assertTrue(next.contains("java.lang.UnsupportedOperationException"))
        assertFalse(next.contains("java.lang.IllegalStateException"))
        assertEquals(listOf("last-crash.txt"), directory.listFiles()!!.map { it.name })
    }

    @Test fun deeplyNestedAndHostileMetadataStaysBounded() {
        val reporter = CrashReporter(temporary.newFolder(), "PRIVATE_VERSION/path")
        var error: Throwable = IllegalArgumentException("PRIVATE_MESSAGE")
        repeat(20) { error = IllegalStateException("PRIVATE_MESSAGE", error) }
        error.stackTrace = Array(1000) { StackTraceElement("bad/path", "bad\nmethod", "PRIVATE_FILE", 1) }
        reporter.record(error)
        val text = reporter.read()!!
        assertTrue(text.toByteArray().size <= 32_768)
        assertEquals(4, Regex("cause\\[\\d+]").findAll(text).count())
        assertFalse(text.contains("PRIVATE_"))
        assertFalse(text.contains("bad/path"))
        assertFalse(text.contains("bad\nmethod"))
    }

    @Test fun delegatesTheOriginalCrashOnceEvenWhenStorageIsUnavailable() {
        val notDirectory = temporary.newFile()
        val reporter = CrashReporter(notDirectory, "test")
        val original = IllegalStateException("test")
        val thread = Thread("PRIVATE_THREAD")
        var calls = 0
        val handler = reporter.handler { receivedThread, receivedError ->
            assertSame(thread, receivedThread)
            assertSame(original, receivedError)
            calls++
        }
        handler.uncaughtException(thread, original)
        assertEquals(1, calls)
    }

    @Test fun successfulRecordingStillDelegatesTheOriginalCrash() {
        val reporter = CrashReporter(temporary.newFolder(), "test")
        val error = IllegalStateException("test")
        var received: Throwable? = null
        reporter.handler { _, failure -> received = failure }.uncaughtException(Thread.currentThread(), error)
        assertSame(error, received)
        assertNotNull(reporter.read())
    }

    @Test fun installedHandlerRecordsARealChildJvmCrashAndPreservesTermination() {
        val directory = temporary.newFolder()
        val classpath = listOf(CrashProbe::class.java, CrashReporter::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp",
            classpath,
            CrashProbe::class.java.name,
            directory.absolutePath,
        ).redirectErrorStream(true).redirectOutput(File(temporary.root, "child-output.txt")).start()
        try {
            assertTrue("Child JVM did not terminate", process.waitFor(10, TimeUnit.SECONDS))
            assertNotEquals(0, process.exitValue())
            val report = CrashReporter(directory, "test").read()!!
            assertTrue(report.contains("java.lang.IllegalStateException"))
            assertFalse(report.contains("SYNTHETIC_MESSAGE"))
            assertEquals(1, Regex("Zhupi crash metadata").findAll(report).count())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun oversizedReportsAreRejectedWithoutUnboundedReads() {
        val directory = temporary.newFolder()
        File(directory, "last-crash.txt").writeBytes(ByteArray(32_769))
        assertTrue(runCatching { CrashReporter(directory, "test").read() }.isFailure)
    }
}

/** Runs only in a disposable JVM so the test never replaces Gradle's own crash handler. */
object CrashProbe {
    @JvmStatic fun main(args: Array<String>) {
        val reporter = CrashReporter(File(args.single()), "test")
        reporter.install()
        reporter.install()
        error("SYNTHETIC_MESSAGE")
    }
}
