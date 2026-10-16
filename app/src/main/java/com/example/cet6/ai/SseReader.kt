package com.example.cet6.ai

import java.io.BufferedReader

internal data class SseRecord(val event: String, val data: String)

/** Frames SSE without interpreting JSON; EOF also dispatches the pending record. */
internal class SseReader(private val reader: BufferedReader) {
    fun next(): SseRecord? {
        var event = ""
        val data = mutableListOf<String>()
        var characters = 0
        while (true) {
            val line = reader.readLine()
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) return SseRecord(event, data.joinToString("\n"))
                if (line == null) return null
                event = ""
                characters = 0
                continue
            }
            characters += line.length
            check(characters <= 1_048_576) { "SSE 事件过大" }
            if (line.startsWith(':')) continue
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> data.add(value)
            }
        }
    }
}
