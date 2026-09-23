package io.github.tangyuan1129.chataside.capture

import io.github.tangyuan1129.chataside.capture.SendButtonRules.Candidate
import io.github.tangyuan1129.chataside.capture.SendButtonRules.InputBox
import io.github.tangyuan1129.chataside.capture.SendButtonRules.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rule that decides which node may be pressed as "send".
 *
 * This is the only place in the app that takes an irreversible action, so the
 * cases below are written as a list of things that must never happen.
 */
class SendButtonRulesTest {

    private val screen = Screen(width = 1200, height = 2670)

    /** A plausible send button: small, bottom-right, on the input row. */
    private fun sendButton(
        label: String = "发送",
        left: Int = 1040,
        top: Int = 2400,
        right: Int = 1150,
        bottom: Int = 2500
    ) = Candidate(label, left, top, right, bottom)

    /** The input box those coordinates sit next to. */
    private val input = InputBox(left = 40, top = 2400, right = 1010, bottom = 2500)

    // ---------------------------------------------------------------- labels

    @Test
    fun `exact send labels are accepted`() {
        for (label in listOf("发送", "Send", "send", "SEND")) {
            assertTrue("expected '$label' to qualify", SendButtonRules.hasSendLabel(label))
        }
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(SendButtonRules.hasSendLabel("  发送 "))
        assertTrue(SendButtonRules.hasSendLabel("\nSend\t"))
    }

    @Test
    fun `share-sheet labels must never match`() {
        // Substring matching here would open a share sheet and then press its
        // confirm button. Exact match only.
        val dangerous = listOf(
            "发送给朋友", "发送到", "Send to", "Send message", "发送图片", "转发"
        )
        for (label in dangerous) {
            assertFalse("'$label' must not be treated as send", SendButtonRules.hasSendLabel(label))
            assertFalse(SendButtonRules.isEligible(sendButton(label = label), screen))
        }
    }

    @Test
    fun `blank labels are refused`() {
        assertFalse(SendButtonRules.isEligible(sendButton(label = ""), screen))
        assertFalse(SendButtonRules.isEligible(sendButton(label = "   "), screen))
    }

    // ------------------------------------------------------- money surfaces

    @Test
    fun `money surfaces are refused`() {
        for (word in SendButtonRules.MONEY_WORDS) {
            assertTrue("'$word' should be recognised as money", SendButtonRules.isMoneyLabel(word))
        }
    }

    @Test
    fun `a money label is refused even when it also says send`() {
        // e.g. a red-packet sheet whose confirm button reads "发送红包".
        val candidates = listOf(
            "发送红包", "确认转账", "发送收款", "发送付款", "支付并发送", "发送零钱"
        )
        for (label in candidates) {
            assertTrue("'$label' must be caught by the money filter", SendButtonRules.isMoneyLabel(label))
            assertFalse("'$label' must never be pressable", SendButtonRules.isEligible(sendButton(label = label), screen))
        }
    }

    @Test
    fun `money is checked before the label test`() {
        // '发送红包' is not an exact send label either, so this pins the order:
        // regardless of which check fires, the answer must be "not eligible".
        assertFalse(SendButtonRules.isEligible(sendButton(label = "发送红包"), screen))
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun `a send button above the chat input row is refused`() {
        // Top half of the screen. A "发送" up here is a share sheet or a
        // contact picker, never the chat composer.
        val topHalf = sendButton(top = 200, bottom = 300)
        assertFalse(SendButtonRules.isEligible(topHalf, screen))
    }

    @Test
    fun `a send button just above the midpoint is still refused`() {
        val justAbove = sendButton(top = 1200, bottom = 1330)   // centerY = 1265 < 1335
        assertFalse(SendButtonRules.isEligible(justAbove, screen))
    }

    @Test
    fun `a degenerate rectangle is refused`() {
        assertFalse(SendButtonRules.isEligible(sendButton(left = 500, right = 500), screen))
        assertFalse(SendButtonRules.isEligible(sendButton(top = 2400, bottom = 2400), screen))
    }

    @Test
    fun `an ordinary chat send button is eligible`() {
        assertTrue(SendButtonRules.isEligible(sendButton(), screen))
    }

    // -------------------------------------------------------------- ranking

    @Test
    fun `the button on the input row beats one further down`() {
        val onRow = sendButton(top = 2400, bottom = 2500)
        val lower = sendButton(top = 2600, bottom = 2660)
        assertTrue(
            "on-row candidate should outrank a lower one",
            SendButtonRules.score(onRow, input, screen) > SendButtonRules.score(lower, input, screen)
        )
    }

    @Test
    fun `the button to the right of the input box beats one to its left`() {
        val right = sendButton(left = 1040, right = 1150)
        val left = sendButton(left = 100, right = 210)
        assertTrue(
            SendButtonRules.score(right, input, screen) > SendButtonRules.score(left, input, screen)
        )
    }

    @Test
    fun `among equals the smaller node wins`() {
        val small = sendButton(left = 1040, right = 1150)      // width 110
        val wide = sendButton(left = 40, right = 1150)         // width 1110, a container
        assertTrue(
            "a full-width node is more likely a container than the button",
            SendButtonRules.score(small, input, screen) > SendButtonRules.score(wide, input, screen)
        )
    }

    @Test
    fun `scoring still works when the input box is unknown`() {
        // Some screens report no editable node; the smaller node should still win.
        val small = sendButton(left = 1040, right = 1150)
        val wide = sendButton(left = 40, right = 1150)
        assertTrue(SendButtonRules.score(small, null, screen) > SendButtonRules.score(wide, null, screen))
    }

    @Test
    fun `candidate geometry is derived correctly`() {
        val c = Candidate("发送", left = 10, top = 20, right = 110, bottom = 60)
        assertEquals(100, c.width)
        assertEquals(40, c.height)
        assertEquals(40, c.centerY)
    }
}
