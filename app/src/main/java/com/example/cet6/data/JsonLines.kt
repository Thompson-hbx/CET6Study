package com.example.cet6.data

import org.json.JSONObject

/**
 * Extracts complete top-level JSON objects from model output. The coach asks for one object per
 * line, but models occasionally pretty-print or wrap the lines in a code fence, so this scans
 * braces instead of trusting newlines. An object still being streamed is simply not returned yet.
 */
object JsonLines {
    private data class Scan(val objects: List<JSONObject>, val malformed: Boolean)
    fun objects(text: String): List<JSONObject> = scan(text).objects
    fun hasMalformedObjects(text: String): Boolean = scan(text).let {
        it.malformed || it.objects.any { value -> value.opt("type") !is String }
    }

    private fun scan(text: String): Scan {
        val found = mutableListOf<JSONObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        var malformed = false
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
                        val parsed = runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                        if (parsed == null) malformed = true else found.add(parsed)
                        start = -1
                    }
                }
            }
        }
        return Scan(found, malformed || depth != 0 || inString)
    }

    /** Objects with a recognised `type`; anything else in the output is ignored. */
    fun typed(text: String, types: Set<String>) = objects(text).filter { it.optString("type") in types }
}
