package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoachOutputSchemaTest {
    private fun cases(): List<JSONObject> {
        val root = JSONObject(File("testdata/coach-protocol-regression-v1.json").readText())
        assertEquals(1, root.getInt("version"))
        val rows = root.getJSONArray("cases")
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private fun verify(case: JSONObject) {
        val id = case.getString("id")
        val raw = case.getString("response")
        val expected = case.getBoolean("structured")
        when (case.getString("kind")) {
            "review" -> CoachReview.parse(raw).let {
                assertEquals(id, expected, it.structured)
                assertEquals(id, raw, it.raw)
                if (!expected) assertTrue("$id exposed actionable issues", it.issues.isEmpty())
            }
            "reading" -> ReadingExplanation.parse(raw).let {
                assertEquals(id, expected, it.structured)
                assertEquals(id, raw, it.raw)
            }
            "overview" -> PassageOverview.parse(raw, case.getInt("paragraphCount")).let {
                assertEquals(id, expected, it.structured)
                assertEquals(id, raw, it.raw)
            }
            else -> error("Unknown regression case kind")
        }
    }

    @Test fun malformedReviewFieldsPreserveRawAndDisableActions() {
        cases().filter { it.getString("kind") == "review" && !it.getBoolean("structured") }.forEach(::verify)
    }
    @Test fun malformedReadingFieldsAreNeverInferredAsWrongAnswers() {
        cases().filter { it.getString("kind") == "reading" && !it.getBoolean("structured") }.forEach(::verify)
    }
    @Test fun malformedOverviewReferencesCannotLookComplete() {
        cases().filter { it.getString("kind") == "overview" && !it.getBoolean("structured") }.forEach(::verify)
    }
    @Test fun validLegacyShapesAndRecordedRepliesRemainReadableOffline() {
        cases().filter { it.getBoolean("structured") }.forEach(::verify)
    }
}
