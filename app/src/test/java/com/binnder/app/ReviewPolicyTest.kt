package com.binnder.app

import org.junit.Assert.*
import org.junit.Test

class ReviewPolicyTest {
    @Test fun undoUsesElapsedTimeWithinTheSameBoot() {
        assertEquals(5_000L,ReviewPolicy.undoRemaining(15_000L,4,4,10_000L))
        assertEquals(1L,ReviewPolicy.undoRemaining(15_000L,4,4,14_999L))
        assertEquals(0L,ReviewPolicy.undoRemaining(15_000L,4,4,15_000L))
    }
    @Test fun rebootDoesNotReuseThePreviousBootsMonotonicDeadline() {
        assertEquals(4_000L,ReviewPolicy.undoRemaining(500_000_000L,4,5,1_000L))
        assertEquals(0L,ReviewPolicy.undoRemaining(500_000_000L,4,5,20_000L))
    }
    @Test fun keptFileReturnsAtSevenDaysExactly() {
        val kept=1_000_000L
        assertFalse(ReviewPolicy.isEligible(kept,kept+ReviewPolicy.KEEP_MS-1))
        assertTrue(ReviewPolicy.isEligible(kept,kept+ReviewPolicy.KEEP_MS))
        assertTrue(ReviewPolicy.isEligible(null,kept))
    }
    @Test fun backwardClockDoesNotPrematurelyReshowKeptFile() {
        assertFalse(ReviewPolicy.isEligible(10_000L,1_000L))
    }
    @Test fun binExpiresAtThirtyDaysExactly() {
        val binned=1_000_000L
        assertFalse(ReviewPolicy.isExpired(binned,binned+ReviewPolicy.TRASH_MS-1))
        assertTrue(ReviewPolicy.isExpired(binned,binned+ReviewPolicy.TRASH_MS))
    }
    @Test fun undoCannotRaceTheCommitDeadline() {
        val deadline=10_000L
        assertTrue(ReviewPolicy.canUndo(deadline,deadline-1))
        assertFalse(ReviewPolicy.canUndo(deadline,deadline))
        assertFalse(ReviewPolicy.canUndo(deadline,deadline+1))
    }
    @Test fun repositoryIsAnOwnerAndNameNotAnArbitraryUrl() {
        assertTrue(ReviewPolicy.validRepository("srinidhibhat/Binnder"))
        assertTrue(ReviewPolicy.validRepository("a-b/c_d.e"))
        listOf("", "https://github.com/a/b", "../b", "a/..", "a/b/c", "a/b?x=1", "a/b#x", "a/b\n").forEach {
            assertFalse(it,ReviewPolicy.validRepository(it))
        }
    }
}
