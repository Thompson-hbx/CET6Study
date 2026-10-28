package com.example.cet6

import com.example.cet6.data.ReviewDecisions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReviewDecisionStateTest {
    private fun cases(): List<JSONObject> {
        val root = JSONObject(File("testdata/coach-decision-state-v1.json").readText())
        assertEquals(1, root.getInt("version"))
        val rows = root.getJSONArray("cases")
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    @Test fun invalidStateCannotTurnIntoAnEmptyWriteSnapshot() {
        cases().filter { !it.getBoolean("valid") }.forEach { case ->
            assertTrue(case.getString("id"), runCatching {
                ReviewDecisions.decodeForUpdate(case.getString("state"), setOf(0, 1))
            }.isFailure)
        }
    }

    @Test fun emptyAndLegacyStatesKeepTheirExistingDecisions() {
        cases().filter { it.getBoolean("valid") }.forEach { case ->
            fun ids(key: String): Set<Int> {
                val values = case.getJSONArray(key)
                return (0 until values.length()).map { values.getInt(it) }.toSet()
            }
            assertEquals(case.getString("id"), ReviewDecisions(ids("accepted"), ids("dismissed")),
                ReviewDecisions.decodeForUpdate(case.getString("state"), setOf(0, 1)))
        }
    }
}
