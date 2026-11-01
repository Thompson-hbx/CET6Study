package com.example.cet6.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class ReviewDecision { ACCEPT, DISMISS, RESET }

/** The learner's verdict on individual suggestions; stored on the review turn, never inferred. */
data class ReviewDecisions(val accepted: Set<Int> = emptySet(), val dismissed: Set<Int> = emptySet()) {
    fun accept(id: Int) = copy(accepted = accepted + id, dismissed = dismissed - id)
    fun dismiss(id: Int) = copy(dismissed = dismissed + id, accepted = accepted - id)
    fun reset(id: Int) = copy(accepted = accepted - id, dismissed = dismissed - id)
    fun encode(): String = JSONObject().put("accepted", JSONArray(accepted.sorted())).put("dismissed", JSONArray(dismissed.sorted())).toString()

    companion object {
        /** A failed read must never become an empty state during a database write. */
        internal fun decodeForUpdate(state: String, issueIds: Set<Int>): ReviewDecisions {
            require(state.length <= CoachTurns.MAX_STATE) { "建议状态过长，原状态已保留" }
            if (state.isBlank()) return ReviewDecisions()
            val input = JSONTokener(state)
            require(input.nextClean() == '{') { "建议状态格式无效，原状态已保留" }
            val fields = mutableMapOf<String, Set<Int>>()
            if (input.nextClean() != '}') {
                input.back()
                while (true) {
                    require(input.nextClean() == '\"') { "建议状态字段无效，原状态已保留" }
                    val key = input.nextString('\"')
                    require(key in setOf("accepted", "dismissed") && key !in fields) { "建议状态字段未知或重复，原状态已保留" }
                    require(input.nextClean() == ':') { "建议状态格式无效，原状态已保留" }
                    val array = requireNotNull(input.nextValue() as? JSONArray) { "建议状态必须是编号列表，原状态已保留" }
                    val ids = mutableSetOf<Int>()
                    for (i in 0 until array.length()) {
                        val id = when (val value = array.opt(i)) {
                            is Int -> value
                            is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                            else -> null
                        }
                        require(id != null && id in issueIds && ids.add(id)) { "建议状态编号无效或重复，原状态已保留" }
                    }
                    fields[key] = ids
                    val next = input.nextClean()
                    if (next == '}') break
                    require(next == ',') { "建议状态格式无效，原状态已保留" }
                }
            }
            require(input.nextClean().code == 0) { "建议状态存在多余内容，原状态已保留" }
            val accepted = fields["accepted"].orEmpty()
            val dismissed = fields["dismissed"].orEmpty()
            require(accepted.intersect(dismissed).isEmpty()) { "建议状态互相冲突，原状态已保留" }
            return ReviewDecisions(accepted, dismissed)
        }

        fun decode(state: String): ReviewDecisions = runCatching {
            val o = JSONObject(state)
            fun ints(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
            ReviewDecisions(ints("accepted"), ints("dismissed"))
        }.getOrDefault(ReviewDecisions())
    }
}
