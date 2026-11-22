package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.ui.mayPublishSessionAnalysis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionAnalysisOwnershipAndroidTest {
    @Test fun onlyCurrentExpandedSessionCanReceiveResults() {
        // B finishes first, then the cancelled or non-cooperative A read completes.
        assertTrue(mayPublishSessionAnalysis(2L, 2L, 202L, 202L))
        assertFalse(mayPublishSessionAnalysis(1L, 2L, 101L, 202L))
    }

    @Test fun collapseRejectsPendingResults() {
        assertFalse(mayPublishSessionAnalysis(3L, 4L, 101L, null))
    }

    @Test fun newerRequestForSameSessionInvalidatesOldResult() {
        assertFalse(mayPublishSessionAnalysis(5L, 6L, 101L, 101L))
        assertTrue(mayPublishSessionAnalysis(6L, 6L, 101L, 101L))
    }
}
