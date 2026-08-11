package com.myvideolibrary.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun `latest release notes describe version 1 0 173`() {
        val notes = ReleaseNotes.latest()

        assertEquals("1.0.173", notes.version)
        assertFalse(notes.changes.isEmpty())
    }
}
