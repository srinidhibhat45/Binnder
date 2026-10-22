package com.binnder.app

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun releaseBuildRepositoryWorksWithoutManualSetup() {
        assertEquals("owner/Binnder",UpdatePolicy.repository(null,"owner/Binnder"))
        assertEquals("owner/Binnder",UpdatePolicy.repository("","owner/Binnder"))
        assertEquals("other/Binnder",UpdatePolicy.repository("other/Binnder","owner/Binnder"))
    }
    @Test fun automaticChecksAreThrottledButRepoChangesCheckImmediately() {
        assertTrue(UpdatePolicy.shouldCheck("a/b","",null,1))
        assertFalse(UpdatePolicy.shouldCheck("a/b","a/b",100,100+UpdatePolicy.CHECK_INTERVAL_MS-1))
        assertTrue(UpdatePolicy.shouldCheck("a/b","a/b",100,100+UpdatePolicy.CHECK_INTERVAL_MS))
        assertTrue(UpdatePolicy.shouldCheck("a/c","a/b",100,101))
        assertTrue(UpdatePolicy.shouldCheck("a/b","a/b",100,1))
    }
    @Test fun githubCanonicalCasingDoesNotRejectAnOtherwiseTrustedUpdate() {
        assertTrue(UpdatePolicy.trustedAsset("https://github.com/Owner/Binnder/releases/download/build-1/binnder.apk","owner/binnder"))
    }
    @Test fun rejectUpdatesFromAnotherRepositoryOrHost() {
        listOf("http://github.com/a/b/releases/download/x/binnder.apk",
            "https://github.com.evil.test/a/b/releases/download/x/binnder.apk",
            "https://github.com/a/c/releases/download/x/binnder.apk",
            "https://github.com/a/b-other/releases/download/x/binnder.apk",
            "https://github.com:444/a/b/releases/download/x/binnder.apk",
            "https://github.com/a/b/releases").forEach { assertFalse(it,UpdatePolicy.trustedAsset(it,"a/b")) }
    }
}
