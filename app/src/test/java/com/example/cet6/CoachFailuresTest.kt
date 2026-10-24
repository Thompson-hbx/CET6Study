package com.example.cet6

import com.example.cet6.data.CoachFailures
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class CoachFailuresTest {
    @Test fun distinguishesTransportTimeoutAndHttpFailures() {
        assertTrue(CoachFailures.describe(IOException("connection reset")).contains("检查网络"))
        assertTrue(CoachFailures.describe(SocketTimeoutException()).contains("超时"))
        assertTrue(CoachFailures.describe(IllegalStateException("AI 请求失败：HTTP 401")).contains("重新连接"))
        assertTrue(CoachFailures.describe(IllegalStateException("AI 请求失败：HTTP 403")).contains("拒绝访问"))
        assertTrue(CoachFailures.describe(IllegalStateException("AI 请求失败：HTTP 503")).contains("暂时不可用"))
    }

    @Test fun distinguishesQuotaFromRateLimitEvenWithSameStatus() {
        assertTrue(CoachFailures.describe(IllegalStateException("AI 请求失败：HTTP 429，code=rate_limit_exceeded")).contains("稍后手动重试"))
        for (code in listOf("insufficient_quota", "credit_balance_exhausted", "usage_limit_exceeded")) {
            val text = CoachFailures.describe(IllegalStateException("AI 请求失败：HTTP 429，code=$code"))
            assertTrue(text.contains("用量或额度限制") && text.contains("反复重试通常无法解决"))
        }
    }

    @Test fun recognizesRefreshFailureWithoutChangingAuthentication() {
        assertTrue(CoachFailures.describe(IllegalStateException("OAuth Token 请求失败：HTTP 400，invalid_grant")).contains("重新连接"))
        assertTrue(CoachFailures.describe(IllegalStateException("网络流提前中断，没有收到 response.completed")).contains("尚未完整结束"))
    }

    @Test fun neverEchoesUntrustedErrorText() {
        val privateText = "private-test-marker"
        listOf(IOException(privateText), IllegalStateException(privateText), IllegalArgumentException(privateText)).forEach {
            assertFalse(CoachFailures.describe(it).contains(privateText))
        }
    }

    @Test fun apiFailuresOfferProviderNeutralRecovery() {
        val unauthorized = CoachFailures.describe(IllegalStateException("API 请求失败：HTTP 401"))
        assertTrue(unauthorized.contains("API Key"))
        assertFalse(unauthorized.contains("ChatGPT"))
        assertTrue(CoachFailures.describe(IllegalStateException("API 请求失败：HTTP 402")).contains("余额不足"))
        assertTrue(CoachFailures.describe(IllegalStateException("网络流提前中断，没有收到 [DONE]")).contains("尚未完整结束"))
    }
}
