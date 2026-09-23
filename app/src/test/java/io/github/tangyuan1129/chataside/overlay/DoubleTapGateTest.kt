package io.github.tangyuan1129.chataside.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "second tap sends" rule.
 *
 * The failure modes are asymmetric and both matter: sending when the user only
 * meant to draft is unacceptable, and a gesture that never fires is a broken
 * feature. These cases pin both.
 */
class DoubleTapGateTest {

    private val window = 2500L
    private val gate = DoubleTapGate()

    @Test
    fun `a fresh gate is not armed`() {
        assertEquals(-1, gate.armed)
        assertFalse(gate.isArmed(0))
    }

    @Test
    fun `the first tap fills and arms, it never sends`() {
        val sent = gate.onTap(index = 0, nowMs = 1_000, enabled = true, windowMs = window)
        assertFalse("the first tap must never send", sent)
        assertEquals(0, gate.armed)
        assertTrue(gate.isArmed(0))
    }

    @Test
    fun `a second tap on the same option sends`() {
        gate.onTap(index = 1, nowMs = 1_000, enabled = true, windowMs = window)
        val sent = gate.onTap(index = 1, nowMs = 2_000, enabled = true, windowMs = window)
        assertTrue("the second tap inside the window must send", sent)
        assertEquals("sending must disarm", -1, gate.armed)
    }

    @Test
    fun `a second tap exactly at the deadline still sends`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        assertTrue(gate.onTap(index = 0, nowMs = window, enabled = true, windowMs = window))
    }

    @Test
    fun `a second tap after the deadline fills instead of sending`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        val sent = gate.onTap(index = 0, nowMs = window + 1, enabled = true, windowMs = window)
        assertFalse("a lapsed window must not send", sent)
        assertEquals("it should re-arm onto the same option", 0, gate.armed)
    }

    @Test
    fun `tapping a different option re-arms instead of firing the old one`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        val sent = gate.onTap(index = 2, nowMs = 500, enabled = true, windowMs = window)
        assertFalse("tapping a different option must not send the armed one", sent)
        assertEquals(2, gate.armed)
        assertFalse(gate.isArmed(0))
    }

    @Test
    fun `after a send the gesture starts over`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        assertTrue(gate.onTap(index = 0, nowMs = 100, enabled = true, windowMs = window))
        // The very next tap is a first tap again.
        assertFalse(gate.onTap(index = 0, nowMs = 200, enabled = true, windowMs = window))
        assertEquals(0, gate.armed)
    }

    // ------------------------------------------------------- switch disabled

    @Test
    fun `with the switch off, tapping twice never sends`() {
        for (i in 0..5) {
            val sent = gate.onTap(index = 0, nowMs = 1_000L * i, enabled = false, windowMs = window)
            assertFalse("disabled gate must never send", sent)
        }
        assertEquals("disabled gate must never arm", -1, gate.armed)
    }

    @Test
    fun `turning the switch off mid-gesture drops the arm`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        assertEquals(0, gate.armed)

        // User flips the switch off before tapping again.
        assertFalse(gate.onTap(index = 0, nowMs = 100, enabled = false, windowMs = window))
        assertEquals(-1, gate.armed)

        // Flipping it back on must not resurrect the arm and instantly fire.
        val sent = gate.onTap(index = 0, nowMs = 200, enabled = true, windowMs = window)
        assertFalse("a re-enabled gate must not send on the next tap", sent)
    }

    // ------------------------------------------------------------- expiry

    @Test
    fun `expire does nothing while the window is still open`() {
        gate.onTap(index = 0, nowMs = 1_000, enabled = true, windowMs = window)
        assertFalse(gate.expire(1_000 + window))
        assertEquals(0, gate.armed)
    }

    @Test
    fun `expire disarms once the window has lapsed`() {
        gate.onTap(index = 0, nowMs = 1_000, enabled = true, windowMs = window)
        assertTrue("a lapsed arm should report that it was dropped", gate.expire(1_000 + window + 1))
        assertEquals(-1, gate.armed)
    }

    @Test
    fun `expire on an idle gate reports nothing`() {
        assertFalse(gate.expire(9_999))
    }

    @Test
    fun `expire is not reported twice`() {
        gate.onTap(index = 0, nowMs = 0, enabled = true, windowMs = window)
        assertTrue(gate.expire(window + 1))
        assertFalse("the second expire has nothing left to drop", gate.expire(window + 2))
    }

    @Test
    fun `clear drops the arm`() {
        gate.onTap(index = 3, nowMs = 0, enabled = true, windowMs = window)
        gate.clear()
        assertEquals(-1, gate.armed)
        assertFalse(gate.expire(999_999))
    }
}
