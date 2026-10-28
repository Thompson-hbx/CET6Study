package com.example.cet6.data

import org.json.JSONObject

/** Monotonic within one boot; wall-clock estimation is explicit after a reboot. */
data class TrainingClock(val wallStartedAt: Long, val elapsedStartedAt: Long, val boot: Int) {
    fun elapsedMillis(wallNow: Long, elapsedNow: Long, currentBoot: Int): Long =
        (if (boot == currentBoot && elapsedNow >= elapsedStartedAt) elapsedNow - elapsedStartedAt
            else wallNow - wallStartedAt).coerceAtLeast(0)
    fun estimated(elapsedNow: Long, currentBoot: Int) = boot != currentBoot || elapsedNow < elapsedStartedAt
    fun encode(): String = JSONObject().put("wall", wallStartedAt).put("elapsed", elapsedStartedAt).put("boot", boot).toString()
    companion object {
        fun decode(value: String): TrainingClock {
            val obj = JSONObject(value)
            return TrainingClock(obj.getLong("wall"), obj.getLong("elapsed"), obj.getInt("boot")).also {
                require(it.wallStartedAt > 0 && it.elapsedStartedAt >= 0 && it.boot >= 0) { "计时记录无效" }
            }
        }
    }
}
