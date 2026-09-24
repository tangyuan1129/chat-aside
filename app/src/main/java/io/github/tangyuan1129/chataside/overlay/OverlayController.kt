package io.github.tangyuan1129.chataside.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.tangyuan1129.chataside.core.Analysis
import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.Choice
import io.github.tangyuan1129.chataside.core.Prefs
import io.github.tangyuan1129.chataside.core.RankedReply
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /**
     * Bubble menu → the user flipped between chooser and advisor. The service
     * re-runs the parked snapshot so the panel redraws in the new shape (and,
     * going advisor-ward, stops paying for candidates it will not show).
     */
    var onModeChanged: (() -> Unit)? = null

    /**
     * Chooser mode, second tap of a double tap. The service owns this because
     * only it can reach the chat app's send button; null means sending is
     * unavailable and a second tap just re-fills.
     */
    var onSend: ((String) -> Unit)? = null

    /**
     * Double-tap state for chooser mode. The rule itself lives in
     * [DoubleTapGate] so it can be unit-tested; this class only schedules the
     * expiry and redraws.
     */
    private val gate = DoubleTapGate()
    private val armer = Handler(Looper.getMainLooper())
    private val disarm = Runnable {
        if (gate.expire(SystemClock.uptimeMillis())) {
            lastJudgment?.let { render(it, generating = false) }
        }
    }

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "旁白"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 58, 122, 254))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "旁白分析"; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("把当前会话存为联系人") { onSaveContact?.invoke(); root?.removeView(menu) })
        val switchToAdvisor = prefs.isChooser
        menu.addView(menuItem(if (switchToAdvisor) "切到参谋模式" else "切到选项模式") {
            prefs.overlayMode = if (switchToAdvisor) Prefs.MODE_ADVISOR else Prefs.MODE_CHOOSER
            root?.removeView(menu)
            toast(if (switchToAdvisor) "已切到参谋模式" else "已切到选项模式")
            onModeChanged?.invoke()
        })
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("隐藏助手（本次）") { hide() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "io.github.tangyuan1129.chataside.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot(); bubble?.alpha = 0.55f
        // Either there is genuinely nothing to show yet, or the panel is empty
        // for some other reason (root got rebuilt after hide(), leaving
        // contentBox with zero children while lastJudgment still points at a
        // stale conversation) — either way an empty panel must never stay
        // literally blank.
        if (lastJudgment == null || contentBox?.childCount == 0) {
            setContent(listOf(bigButton("分析当前对话") { onManualAnalyze?.invoke() }))
        }
    }

    /**
     * Re-show the panel after the overlay was torn down (app switch, launcher,
     * ColorOS kill) while the SAME conversation is still on screen. Restores
     * the stored judgment instead of dropping the user back to the
     * "分析当前对话" button — leaving and re-entering a chat must not throw
     * away a result they already paid for. Falls back to the idle button when
     * there is nothing stored (a different conversation resets it first).
     */
    fun showRestored() {
        val j = lastJudgment
        if (j != null) { render(j, generating = false); return }
        showIdle(null)
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        // An armed option must not survive into another conversation: its second
        // tap would send to whoever the chat window now shows.
        gate.clear()
        armer.removeCallbacks(disarm)
        contentBox?.removeAllViews()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#3A7AFE"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        ctxNotes = 0; ctxHistory = 0   // counts for the round that is starting
        replyError = null              // this round has not failed (yet)
        setContent(listOf(hint("分析中…")))
        if (!expanded) toggle()
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int) {
        ctxNotes = notes; ctxHistory = history
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun showError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg)))
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        armer.removeCallbacks(disarm)
        gate.clear()
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    /**
     * Entry point for both presentations. The two products share every model
     * call and every capture path; only this function differs, so the mode is
     * decided here and nowhere else.
     */
    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = if (prefs.isAdvisor) advisorViews(a, generating) else chooserViews(a, generating)
        setContent(views)
        if (!expanded) toggle()
    }

    /** What context this read was based on, plus how it was captured. Both
     *  modes show these — they change how much to trust everything below. */
    private fun contextLines(out: ArrayList<View>) {
        out.add(hint(
            if (ctxNotes == 0 && ctxHistory == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条"))
        noteText?.let { if (it.isNotBlank()) out.add(hint(it)) }
    }

    // ---------------------------------------------------------- chooser mode

    /**
     * Chooser mode. The panel is a strip of things you could say, not a report:
     * the judgment collapses to a danger chip plus one context line, and
     * everything under the divider exists to be tapped.
     *
     * A tap fills the input box. With chooserDoubleTapSend on, that tap also
     * arms the option; a second tap on the same option inside the window sends
     * instead. The panel deliberately stays open while armed — collapsing would
     * remove the second tap's target — and closes itself when the window lapses.
     */
    private fun chooserViews(a: Analysis, generating: Boolean): ArrayList<View> {
        val views = ArrayList<View>()
        contextLines(views)

        a.dangerLevel?.let {
            views.add(dangerBadge(it.score.roundToInt(), it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Exactly one line of "what is going on". Reading more than this is
        // what advisor mode is for.
        val bits = ArrayList<String>()
        a.trueIntent?.let { bits.add(INTENT[it.choice] ?: it.choice) }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#6B7280", 13f))

        views.add(divider())
        if (generating) {
            views.add(line("正在想三句话…", "#9CA3AF", 13f))
            views.add(reAnalyzeBtn())
            return views
        }
        val fill = lastFill ?: {}
        val replies = a.rankedReplies
        if (replies.isEmpty()) {
            views.add(hint(replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"))
        } else {
            replies.forEachIndexed { i, r -> views.add(choiceRow(i, r, fill)) }
            views.add(hint(
                if (prefs.chooserDoubleTapSend) "点一下填进输入框，连点两下直接发送"
                else "点一下填进输入框，发送由你按"))
        }
        views.add(reAnalyzeBtn())
        return views
    }

    /** One option in the chooser strip. The entire row is the tap target. */
    private fun choiceRow(index: Int, r: RankedReply, fill: (String) -> Unit): View {
        val top = index == 0
        val armed = gate.isArmed(index)
        val bg = when {
            armed -> Color.parseColor("#DDE9FF")
            top -> Color.parseColor("#EAF1FF")
            else -> Color.parseColor("#F3F4F6")
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(14, bg, stroke = armed)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            isClickable = true
            setOnClickListener { tapChoice(index, r.text, fill) }
        }
        // Numbered disc, so the panel reads as a set of options at a glance.
        row.addView(TextView(ctx).apply {
            text = "${index + 1}"
            textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (top) Color.parseColor("#3A7AFE") else Color.parseColor("#9CA3AF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = r.text; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        col.addView(TextView(ctx).apply {
            text = when {
                armed -> "再点一下直接发送"
                top -> "推荐 ${(r.prob * 100).roundToInt()}%"
                else -> "${(r.prob * 100).roundToInt()}%"
            }
            setTextColor(if (armed) Color.parseColor("#DC2626") else Color.parseColor("#9CA3AF"))
            textSize = 11.5f
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(col)
        return row
    }

    /**
     * Chooser tap handler. First tap on an option fills and arms it; a second
     * tap on the same option inside the window sends. Tapping a different
     * option simply arms that one instead. With the switch off, any tap is the
     * plain v1.3 fill-then-collapse.
     */
    private fun tapChoice(index: Int, text: String, fill: (String) -> Unit) {
        val shouldSend = gate.onTap(
            index = index,
            nowMs = SystemClock.uptimeMillis(),
            enabled = prefs.chooserDoubleTapSend,
            windowMs = prefs.chooserDoubleTapWindowMs.toLong()
        )
        if (shouldSend) {
            armer.removeCallbacks(disarm)
            val send = onSend
            if (send != null) send(text) else fill(text)
            if (expanded) toggle()
            return
        }
        fill(text)
        if (!prefs.chooserDoubleTapSend) {
            if (expanded) toggle()
            return
        }
        armer.removeCallbacks(disarm)
        armer.postDelayed(disarm, prefs.chooserDoubleTapWindowMs.toLong())
        lastJudgment?.let { render(it, generating = false) }
    }

    // ---------------------------------------------------------- advisor mode

    /**
     * Advisor mode. A read-only sheet: how dangerous this is, what the other
     * person actually wants, and what kind of move fits.
     *
     * Candidates are off by default — skipping the generative call and the Jev
     * ranking call is about two thirds of the per-analysis cost, and this mode
     * exists for people who have their own words. When the user does turn them
     * on they show folded and cannot be filled: this mode informs, it does not
     * type for you.
     */
    private fun advisorViews(a: Analysis, generating: Boolean): ArrayList<View> {
        val views = ArrayList<View>()
        contextLines(views)

        a.dangerLevel?.let {
            views.add(dangerBadge(it.score.roundToInt(), it.maxLevel))
            tintBubbleDanger(it.score)
        }
        a.trueIntent?.let {
            views.add(line("对方真实意图：${INTENT[it.choice] ?: it.choice}", "#111827", 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Jev answers this every round but v1.3 never surfaced it; in a
        // read-only sheet it is the single most useful line.
        a.literalQuestion?.let {
            val literal = it >= 0.5
            views.add(line(if (literal) "说的是字面意思" else "话里有话",
                if (literal) "#374151" else "#B45309", 13f))
        }
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("紧张已缓解", "#16A34A", 12f)) }
        // The full 7-question breakdown — the data already arrives in the one
        // judgment call; this just renders what the summary compresses away.
        detailBlock(a, views)

        views.add(divider())
        if (!prefs.advisorGenerateReplies) {
            views.add(hint("想直接拿现成的三句话，切到选项模式"))
        } else if (generating) {
            views.add(hint("生成中…"))
        } else {
            views.add(line("参考话术（仅供参照，不能一键填入）", "#9CA3AF", 12f))
            val replies = a.rankedReplies
            if (replies.isEmpty()) {
                views.add(hint(replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"))
            } else {
                replies.forEachIndexed { i, r -> views.add(referenceCard(i + 1, r)) }
            }
        }
        views.add(reAnalyzeBtn())
        return views
    }

    /**
     * The full 7-question breakdown, the way upstream's demo sheet shows it:
     * every judgment question with its probability distribution, not just the
     * compressed summary above. All of it already arrives in the single
     * judgment response — the summary simply didn't render it.
     */
    private fun detailBlock(a: Analysis, views: ArrayList<View>) {
        views.add(divider())
        views.add(hint("详细判断 · 每题概率"))
        // The chat-judge route's numbers are the model's own estimates, not the
        // calibrated distributions of a trained decision model. Say so once, so
        // identical-looking spreads on similar small talk are not mistaken for
        // a frozen or cached result.
        if (prefs.judgeProvider == Prefs.PROVIDER_CHAT) {
            views.add(hint("概率为聊天模型自行估计（非 Jev 校准值），仅供参考"))
        }
        a.literalQuestion?.let {
            val yes = (it * 100).roundToInt()
            views.add(detail("字面还是话里有话", "字面意思 $yes% / 话里有话 ${100 - yes}%"))
        }
        a.trueIntent?.let { views.add(detail("当前真实意图", dist(it, INTENT))) }
        a.dangerLevel?.let {
            views.add(detail("危险等级", "${it.score}/${it.maxLevel}（把握 ${(it.confidence * 100).roundToInt()}%）"))
        }
        a.shouldReplyNow?.let {
            val yes = (it * 100).roundToInt()
            views.add(detail("下一条该不该给实质", "该给 $yes% / 先别给 ${100 - yes}%"))
        }
        a.sheNeeds?.let { views.add(detail("她现在需要什么", dist(it, NEEDS))) }
        a.bestAction?.let { views.add(detail("最好的动作类型", dist(it, ACTION))) }
        a.tensionResolved?.let {
            val yes = (it * 100).roundToInt()
            views.add(detail("紧张是否已缓解", "已缓解 $yes% / 没有 ${100 - yes}%"))
        }
    }

    /** One question's distribution: sorted desc, Chinese labels, top 4. Falls
     *  back to the chosen option + confidence when the model gave no spread
     *  (the chat-judge route answers with a choice but no probabilities). */
    private fun dist(c: Choice, labels: Map<String, String>): String {
        if (c.probabilities.isEmpty()) {
            return "${labels[c.choice] ?: c.choice} ${(c.confidence * 100).roundToInt()}%"
        }
        return c.probabilities.entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString(" / ") { (k, v) -> "${labels[k] ?: k} ${(v * 100).roundToInt()}%" }
    }

    private fun detail(question: String, value: String): View = TextView(ctx).apply {
        text = "$question：$value"
        setTextColor(Color.parseColor("#374151")); textSize = 12f
        setPadding(0, dp(1), 0, dp(1))
    }

    /** Advisor mode's read-only look at a candidate: copy is fine, fill is not. */
    private fun referenceCard(rank: Int, r: RankedReply): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            text = "#${rank} · ${(r.prob * 100).roundToInt()}%"
            setTextColor(Color.parseColor("#6B7280")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            text = r.text; setTextColor(Color.parseColor("#374151")); textSize = 13.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(3), 0, 0)
        })
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        btns.addView(pill("复制", false) { copy(r.text) })
        c.addView(btns)
        return c
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#3A7AFE"))
        background = card(18, if (primary) Color.parseColor("#3A7AFE") else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
