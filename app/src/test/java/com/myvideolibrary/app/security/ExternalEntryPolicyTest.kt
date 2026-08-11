package com.myvideolibrary.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEntryPolicyTest {

    @Test
    fun `defers an external entry only while a configured lock is unauthenticated`() {
        assertTrue(ExternalEntryPolicy.requiresUnlock(lockConfigured = true, authenticated = false))
        assertFalse(ExternalEntryPolicy.requiresUnlock(lockConfigured = false, authenticated = false))
        assertFalse(ExternalEntryPolicy.requiresUnlock(lockConfigured = true, authenticated = true))
    }
}
