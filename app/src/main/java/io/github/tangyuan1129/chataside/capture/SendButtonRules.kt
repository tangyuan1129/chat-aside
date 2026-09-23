package io.github.tangyuan1129.chataside.capture

/**
 * The decision rules behind "which node is the chat app's send button".
 *
 * Split out of [ChatCaptureService] on purpose. Pressing send is the only
 * irreversible thing this app ever does on the user's behalf, and the rule that
 * decides *what* may be pressed is a pure function of labels and rectangles — so
 * it does not need a phone, a chat app and a live conversation to be tested.
 * See `SendButtonRulesTest`.
 *
 * The tree walk stays in the service; only the judgement lives here.
 */
internal object SendButtonRules {

    /**
     * The only labels that may be pressed. Exact matches, not substrings:
     * "发送给朋友" / "Send to" belong to share sheets, and entering one of those
     * by accident is far worse than failing to find the button.
     */
    val SEND_WORDS = setOf("发送", "Send", "send", "SEND")

    /**
     * Refused before anything else is considered. These appear on payment and
     * red-packet surfaces, and no amount of label-matching luck justifies this
     * app being able to press one.
     */
    val MONEY_WORDS = listOf(
        "转账", "红包", "收款", "支付", "付款", "收钱", "零钱", "理财", "银行卡"
    )

    /** One node as these rules see it — a label and a screen rectangle. */
    data class Candidate(
        val label: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerY: Int get() = (top + bottom) / 2
    }

    /** The chat input box, used only to rank candidates. */
    data class InputBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Screen(val width: Int, val height: Int)

    /** A money surface: never pressable by this path, whatever else it says. */
    fun isMoneyLabel(label: String): Boolean = MONEY_WORDS.any { label.contains(it) }

    /** Exact "send" label after trimming. */
    fun hasSendLabel(label: String): Boolean = label.trim() in SEND_WORDS

    /**
     * Whether a node may be considered at all.
     *
     * Order matters: money is checked first, so a payment control can never
     * reach the label test. Then the exact label, then sane bounds, then the
     * bottom half of the screen — every adapted app puts its input row there,
     * and nothing above the chat area is ever a send button.
     */
    fun isEligible(c: Candidate, screen: Screen): Boolean {
        val label = c.label.trim()
        if (label.isEmpty()) return false
        if (isMoneyLabel(label)) return false
        if (!hasSendLabel(label)) return false
        if (c.width <= 0 || c.height <= 0) return false
        if (c.centerY < screen.height / 2) return false
        return true
    }

    /**
     * Ranking among eligible candidates; higher wins.
     *
     * Prefers a button sharing the input box's row and sitting to its right
     * (the layout all four adapted apps use), then the smaller node, since a
     * full-width "send" is more likely a container than the button itself.
     */
    fun score(c: Candidate, input: InputBox?, screen: Screen): Int {
        var s = 0
        if (input != null) {
            if (c.top < input.bottom && c.bottom > input.top) s += 2
            if (c.left >= input.right - screen.width / 20) s += 2
        }
        s -= c.width / 10
        return s
    }
}
