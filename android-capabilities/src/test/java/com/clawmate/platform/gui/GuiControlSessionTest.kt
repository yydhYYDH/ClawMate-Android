package com.clawmate.platform.gui

import org.junit.Assert.*
import org.junit.Test

class GuiControlSessionTest {
    @Test fun grantIsExactAppAndExpiresWithoutPersistence() {
        val grant=GuiControlSession("com.example.calculator",1000)
        grant.requireTarget(1000,"com.example.calculator",true,1)
        assertThrows(IllegalStateException::class.java){grant.requireTarget(1000,"com.example.other",true,1)}
        assertFalse(grant.available(1000))
        val timed=GuiControlSession("com.example.calculator",1000)
        assertTrue(timed.available(120999));assertFalse(timed.available(121000));assertFalse(timed.available(999))
    }
    @Test fun lockAndSplitScreenPermanentlyRevokeAuthorization() {
        for ((unlocked, windows) in listOf(false to 1, true to 0, true to 2, true to 3)) {
            val grant=GuiControlSession("com.example.calculator",0)
            assertThrows(IllegalStateException::class.java){grant.consume(1,grant.target,unlocked,windows)}
            assertFalse(grant.available(2))
            assertThrows(IllegalStateException::class.java){grant.consume(2,grant.target,true,1)}
        }
    }
    @Test fun twentyActionsThenStopRequireNewGrant() {
        val grant=GuiControlSession("com.example.calculator",0)
        repeat(20){grant.consume(1,grant.target,true,1)}
        assertFalse(grant.available(1));assertThrows(IllegalStateException::class.java){grant.consume(1,grant.target,true,1)}
        val stopped=GuiControlSession("com.example.calculator",0);stopped.stop()
        assertThrows(IllegalStateException::class.java){stopped.requireTarget(1,stopped.target,true,1)}
    }
    @Test fun fitCenterCoordinatesExcludeLetterboxAndMapToOriginalPixels() {
        assertEquals(0f,GuiControlSession.mapPoint(50f,0f,200,200,100,200).first)
        assertEquals(100f,GuiControlSession.mapPoint(100f,100f,200,200,100,200).second)
        val scaled=GuiControlSession.mapPoint(200f,100f,400,200,100,200)
        assertEquals(50f,scaled.first);assertEquals(100f,scaled.second)
        for(point in listOf(0f to 0f,150f to 1f,100f to 200f,Float.NaN to 1f))
            assertThrows(IllegalArgumentException::class.java){GuiControlSession.mapPoint(point.first,point.second,200,200,100,200)}
    }
    @Test fun invalidScopeAndGeometryCannotBeGranted() {
        for(pkg in listOf("","*","com.*","com/example","package"))assertThrows(IllegalArgumentException::class.java){GuiControlSession(pkg,0)}
        assertThrows(IllegalArgumentException::class.java){GuiControlSession.mapPoint(0f,0f,0,200,100,200)}
    }
}
