package com.example.cet6

import kotlinx.coroutines.runBlocking
import org.junit.Test

class OAuthRefreshTest {
    private val checks = OAuthRefreshChecks()
    @Test fun liveSessionDoesNotRefresh() = runBlocking { checks.liveSessionDoesNotRefresh() }
    @Test fun concurrentRequestsRotateExactlyOnce() = runBlocking { checks.concurrentRequestsRotateExactlyOnce() }
    @Test fun temporaryHttpAndTransportFailuresPreserveSavedSession() = runBlocking { checks.temporaryHttpAndTransportFailuresPreserveSavedSession() }
    @Test fun terminalRefreshFailureInvalidatesOnlySessionCredentials() = runBlocking { checks.terminalRefreshFailureInvalidatesOnlySessionCredentials() }
    @Test fun cancellingCallerStillPersistsRotatedSession() = runBlocking { checks.cancellingCallerStillPersistsRotatedSession() }
    @Test fun failedLocalSaveCannotReportRefreshSuccess() = runBlocking { checks.failedLocalSaveCannotReportRefreshSuccess() }
    @Test fun malformedSuccessfulReplyDoesNotReplaceSession() = runBlocking { checks.malformedSuccessfulReplyDoesNotReplaceSession() }
    @Test fun missingSessionOrReplacementDoesNotRequest() = runBlocking { checks.missingSessionOrReplacementDoesNotRequest() }
    @Test fun refreshedOptionalFieldsKeepVerifiedIdentity() = runBlocking { checks.refreshedOptionalFieldsKeepVerifiedIdentity() }
    @Test fun cancelledWaitingCallerDoesNotStartAnotherRefresh() = runBlocking { checks.cancelledWaitingCallerDoesNotStartAnotherRefresh() }
}
