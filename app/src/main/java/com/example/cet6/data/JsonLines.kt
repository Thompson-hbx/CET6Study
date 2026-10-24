package com.example.cet6.data

import org.json.JSONObject

/**
 * Extracts complete top-level JSON objects from model output. The coach asks for one object per
 * line, but models occasionally pretty-print or wrap the lines in a code fence, so this scans
 * braces instead of trusting newlines. An object still being streamed is simply not returned yet.
 */
object JsonLines {
    fun objects(text: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()?.let(found::add)
                        start = -1
                    }
                }
            }
        }
        return found
    }

    /** Objects with a recognised `type`; anything else in the output is ignored. */
    fun typed(text: String, types: Set<String>) = objects(text).filter { it.optString("type") in types }
}
