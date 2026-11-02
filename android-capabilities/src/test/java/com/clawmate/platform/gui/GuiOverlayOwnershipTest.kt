package com.clawmate.platform.gui

import org.junit.Assert.*
import org.junit.Test

class GuiOverlayOwnershipTest {
    @Test fun onlyPreviouslyVerifiedOwnRootMayDisappear() {
        val tracker=GuiOverlayOwnership()
        assertFalse(tracker.isOwned(1,null,"host"))
        assertTrue(tracker.isOwned(1,"host","host"))
        assertTrue(tracker.isOwned(1,null,"host"))
        assertFalse(tracker.isOwned(2,null,"host"))
        assertFalse(tracker.isOwned(1,"other","host"))
        assertFalse(tracker.isOwned(1,null,"host"))
    }
    @Test fun absentWindowsAndEndedSessionsForgetOwnership() {
        val tracker=GuiOverlayOwnership()
        assertTrue(tracker.isOwned(1,"host","host"))
        assertTrue(tracker.isOwned(2,"host","host"))
        tracker.retain(setOf(2))
        assertFalse(tracker.isOwned(1,null,"host"))
        assertTrue(tracker.isOwned(2,null,"host"))
        tracker.retain(emptySet())
        assertFalse(tracker.isOwned(2,null,"host"))
    }
}
