package com.example.cet6

import com.example.cet6.ai.SseReader
import org.junit.Assert.*
import org.junit.Test

class SseReaderTest {
    private fun reader(value: String) = SseReader(value.reader().buffered())

    @Test fun multilineDataAndCrLf() {
        val frame = reader("event: delta\r\ndata: {\r\ndata:   \"delta\": \"你好\"}\r\n\r\n").next()!!
        assertEquals("delta", frame.event)
        assertEquals("{\n  \"delta\": \"你好\"}", frame.data)
    }

    @Test fun pendingRecordAtEof() {
        val stream = reader("data: last")
        assertEquals("last", stream.next()!!.data)
        assertNull(stream.next())
    }

    @Test fun commentAndUnknownFieldsAreIgnored() {
        val frame = reader(": heartbeat\nid: 10\nretry: 100\ndata: text\n\n").next()!!
        assertEquals("text", frame.data)
    }

    @Test fun emptyEventDoesNotLeakName() {
        val frame = reader("event: error\n\ndata: success\n\n").next()!!
        assertEquals("", frame.event)
    }

    @Test fun removesOnlyOneOptionalSpace() {
        assertEquals(" text", reader("data:  text\n\n").next()!!.data)
    }

    @Test fun emptyDataIsAnEvent() {
        assertEquals("", reader("data:\n\n").next()!!.data)
    }

    @Test fun successiveRecordsRemainSeparate() {
        val stream = reader("event: one\ndata: a\n\ndata: b\n\n")
        assertEquals("one", stream.next()!!.event)
        assertEquals("b", stream.next()!!.data)
        assertNull(stream.next())
    }

    @Test(expected = IllegalStateException::class) fun rejectsOversizedEvents() {
        reader("data:" + "a".repeat(1_048_576)).next()
    }
}
