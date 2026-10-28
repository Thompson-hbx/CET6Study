package com.example.cet6

import android.content.Context
import com.example.cet6.data.*
import org.json.JSONObject

/** Exercise the same protocol corpus against Android's own org.json implementation. */
class CoachOutputSchemaChecks(private val context: Context) {
    fun verify(): Int {
        val root = context.assets.open("coach-protocol-regression-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getInt("version") == 1)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val raw = case.getString("response")
            val expected = case.getBoolean("structured")
            val actual = when (case.getString("kind")) {
                "review" -> CoachReview.parse(raw).let {
                    check(it.raw == raw && (expected || it.issues.isEmpty())) { "Review raw/action guard failed" }
                    it.structured
                }
                "reading" -> ReadingExplanation.parse(raw).let { check(it.raw == raw); it.structured }
                "overview" -> PassageOverview.parse(raw, case.getInt("paragraphCount")).let { check(it.raw == raw); it.structured }
                else -> error("Unknown protocol fixture kind")
            }
            check(actual == expected) { "Protocol case ${case.getString("id")}: expected $expected, got $actual" }
        }
        return cases.length()
    }
}
