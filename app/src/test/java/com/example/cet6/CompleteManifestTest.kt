package com.example.cet6

import org.junit.Test

class CompleteManifestTest {
    @Test fun numericStringsFractionsAndWrappingCountsAreRejected() { CompleteManifestChecks().verifyInvalid() }
    @Test fun existingBackupsAndLegacyJsonStillRoundTrip() { CompleteManifestChecks().verifyValid() }
}
