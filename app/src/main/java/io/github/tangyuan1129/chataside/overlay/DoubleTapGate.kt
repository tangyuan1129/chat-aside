package io.github.tangyuan1129.chataside.overlay

/**
 * The double-tap state machine behind chooser mode's send gesture.
 *
 * Extracted from [OverlayController] so the rule "when does a second tap
 * actually send" can be unit-tested without a device. Both ways of getting it
 * wrong are bad in different ways: too eager and the app sends a message the
 * user only meant to draft; too strict and the gesture silently never works.
 *
 * Semantics, in full:
 * - A tap on the armed option, inside the window, with the feature enabled:
 *   **sends**, and disarms.
 * - Any other tap: does *not* send, and (when enabled) arms that option for
 *   `windowMs` from now. Tapping a different option therefore re-arms onto the
 *   new one rather than firing the old one.
 * - With the feature disabled the gate never arms and never sends.
 * - [expire] reports whether a lapsed window just disarmed something, so the
 *   caller knows to redraw.
 *
 * Time is passed in rather than read from the clock, which is what makes the
 * whole thing testable.
 */
internal class DoubleTapGate {

    private var armedIndex = -1
    private var armedUntilMs = 0L

    /** Index currently armed for a second tap, or -1. */
    val armed: Int get() = armedIndex

    /** Whether this option should render itself as "tap again to send". */
    fun isArmed(index: Int): Boolean = armedIndex == index

    /**
     * A tap landed on [index].
     *
     * @return true when it should send instead of fill.
     */
    fun onTap(index: Int, nowMs: Long, enabled: Boolean, windowMs: Long): Boolean {
        if (!enabled) {
            // Never leave a stale arm behind if the switch was turned off mid-gesture.
            if (armedIndex != -1) clear()
            return false
        }
        if (armedIndex == index && nowMs <= armedUntilMs) {
            clear()
            return true
        }
        armedIndex = index
        armedUntilMs = nowMs + windowMs
        return false
    }

    /**
     * The window may have lapsed. Returns true when an armed option was just
     * disarmed, which is the caller's cue to redraw the panel.
     */
    fun expire(nowMs: Long): Boolean {
        if (armedIndex != -1 && nowMs > armedUntilMs) {
            clear()
            return true
        }
        return false
    }

    fun clear() {
        armedIndex = -1
        armedUntilMs = 0L
    }
}
