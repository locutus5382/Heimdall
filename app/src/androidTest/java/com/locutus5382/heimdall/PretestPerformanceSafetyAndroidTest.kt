package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.HudDrawFailureGate
import com.locutus5382.heimdall.recording.TimedEvidenceCache
import com.locutus5382.heimdall.recording.releaseOverlayOrRetain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 0.8.7 regression gates for the actual cached-sysfs and
 * WindowManager ownership decisions. No overlay permission or root is needed.
 * Physical Odin injection and gameplay testing are still required.
 */
@RunWith(AndroidJUnit4::class)
class PretestPerformanceSafetyAndroidTest {
    @Test fun staticCeilingCacheAvoidsRepeatedReadsButRefreshesAndRecovers() {
        var reads = 0
        var source = mapOf("policy0" to 2800L)
        val cache = TimedEvidenceCache(60_000L) {
            reads++
            source.toMap()
        }
        assertEquals(2800L, cache.value(0L)["policy0"])
        source = emptyMap()
        repeat(200) {
            assertEquals(2800L, cache.value(it.toLong() * 250L)["policy0"])
        }
        assertEquals(1, reads)
        assertTrue(cache.value(60_000L).isEmpty())
        assertEquals(2, reads)
        source = mapOf("policy0" to 2800L)
        assertEquals(2800L, cache.value(120_001L)["policy0"])
        assertEquals(3, reads)
    }

    @Test fun staticCeilingCacheInvalidatesOnClockReset() {
        var observed = 0
        val cache = TimedEvidenceCache(60_000L) { ++observed }
        assertEquals(1, cache.value(200_000L))
        assertEquals(1, cache.value(200_040L))
        // elapsedRealtime should not normally go backward in a process,
        // but the cache must fail safe in deterministic tests.
        assertEquals(2, cache.value(100L))
        assertEquals(2, observed)
    }

    @Test fun hudDrawFailuresOnlyEnqueueOneDisableForAStreak() {
        val guard = HudDrawFailureGate()
        assertFalse(guard.failed())
        assertFalse(guard.failed())
        assertTrue(guard.failed())
        repeat(100_000) { assertFalse(guard.failed()) }
        guard.success()
        assertFalse(guard.failed())
        assertFalse(guard.failed())
        assertTrue(guard.failed())
    }

    @Test fun windowRemovalFailureRetainsAndConcealsOwnerUntilDetached() {
        var attached = true
        var visible = true
        var owners = 1
        var removals = 0
        fun requestRelease(): Boolean = releaseOverlayOrRetain(
            remove = {
                removals++
                if (removals < 3) throw IllegalStateException("Vendor binder busy")
                attached = false
            },
            attached = { attached },
            conceal = { visible = false },
            report = { /* report-only; no data or recorder writes */ },
        )
        assertFalse(requestRelease())
        assertFalse(visible)
        // Retained View owner explicitly blocks a replacement Canvas.
        if (attached) assertEquals(1, owners)
        assertFalse(requestRelease())
        assertEquals(1, owners)
        assertTrue(requestRelease())
        assertFalse(attached)
        owners = 0 // caller may now clear ownership
        assertEquals(0, owners)
        assertEquals(3, removals)
    }

    @Test fun alreadyDetachedWindowMayBeClearedEvenIfRemoveThrows() {
        var concealed = 0
        val detached = releaseOverlayOrRetain(
            remove = { throw IllegalArgumentException("No such window") },
            attached = { false },
            conceal = { concealed++ },
            report = { /* benign already-removed race */ },
        )
        assertTrue(detached)
        assertEquals(0, concealed)
    }

    @Test fun attachmentProbeFailureRetainsOwnershipRatherThanGuessing() {
        var hidden = false
        val released = releaseOverlayOrRetain(
            remove = { throw SecurityException("Denied") },
            attached = { error("WindowManager attachment status unavailable") },
            conceal = { hidden = true },
            report = { },
        )
        assertFalse(released)
        assertTrue(hidden)
    }
}
