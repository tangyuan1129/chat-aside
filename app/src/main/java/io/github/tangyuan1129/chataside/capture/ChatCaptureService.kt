package io.github.tangyuan1129.chataside.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.tangyuan1129.chataside.capture.ocr.MlKitOcr
import io.github.tangyuan1129.chataside.capture.ocr.OcrLine
import io.github.tangyuan1129.chataside.capture.ocr.ScreenCapture
import io.github.tangyuan1129.chataside.core.BubbleRect
import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.Msg
import io.github.tangyuan1129.chataside.core.Prefs
import io.github.tangyuan1129.chataside.core.kb.ContextBuilder
import io.github.tangyuan1129.chataside.core.kb.KbStore
import io.github.tangyuan1129.chataside.jev.JevClient
import io.github.tangyuan1129.chataside.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * Sending: by default the only write action is ACTION_SET_TEXT (or a clipboard
 * PASTE fallback) filling the chat input box. Chooser mode adds one opt-in
 * exception — a second tap on an already-filled option presses the chat app's
 * send button ([sendInput]). It is gated on the box verifying as containing
 * exactly what we wrote, on the button being an explicitly labelled "send" node
 * in the input row, and on payment surfaces being refused outright.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(
        WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter(),
        DouyinAdapter("com.ss.android.ugc.aweme"),
        DouyinAdapter("com.ss.android.ugc.aweme.lite")
    ).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    /** Identity of the conversation whose judgment is currently on screen:
     *  package + (stabilized) title. Used to decide between "wipe the old
     *  judgment" (different conversation) and "keep it" (new message in the
     *  same chat). See maybeCapture(). */
    private var activeConvKey: String? = null
    private var analyzing = false

    /** Last known-good (non-transient) title per package. See [isTransientTitle]:
     *  a page like X's DM thread briefly shows "连接中…" as `snapshot.title`
     *  right after opening, which must never overwrite a real conversation
     *  title or get saved as a contact name. Never cleared on app switch — the
     *  next real title for that package simply replaces it. */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private val debounce = Runnable { runAnalysis() }

    /** Coalesces bursts of content-changed/scroll events into ONE capture.
     *  Each event used to run a full tree DFS on the main thread — see the
     *  when-block in [onAccessibilityEvent]. The capture always reads the
     *  CURRENT tree when it finally runs, so deferring it is safe. */
    private val captureRunnable = Runnable { maybeCapture() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Bubble menu: switched chooser/advisor. Redo the parked snapshot so the
        // panel takes the new shape — and so advisor mode stops paying for
        // candidates it is not going to show.
        overlay?.onModeChanged = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Chooser mode: the second tap of a double tap. The overlay only fires
        // this when chooserDoubleTapSend is on and the option is still armed.
        overlay?.onSend = { text -> sendInput(text) }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val r0 = rootInActiveWindow
            val fg = r0?.packageName?.toString()
            r0?.recycle()
            if (fg != null && fg !in adapters) {
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                main.post { if (drop) overlay?.hide() else overlay?.showIdle(null) }
                return
            }
        }

        when (type) {
            // App switch must stay instant: it decides whether the bubble is shown
            // at all, and users notice a laggy bubble far more than a laggy refresh.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> maybeCapture()
            // Content/scroll events arrive in bursts — caret blink, "typing…",
            // animation ticks, scroll momentum can fire dozens per second. Each
            // used to run a full tree DFS on the main thread (hundreds of binder
            // IPC round-trips into the chat app's process), which was the single
            // biggest source of UI jank. Collapse each burst into ONE capture
            // shortly after the last event settles.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                main.removeCallbacks(captureRunnable)
                main.postDelayed(captureRunnable, CAPTURE_DEBOUNCE_MS)
            }
        }
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString()
            // Apps with no adapter are never handled automatically (v1.3 revision):
            // the only way in for them is the bubble menu's "截屏识别一次".
            val adapter = adapters[pkg] ?: return
            // Only act inside a chat window (the adapter returns null elsewhere).
            val rawSnapshot = adapter.extract(root, resources) ?: return
            // Stabilize the title BEFORE anything below reads it: some apps (X) show
            // a transient "连接中…" title for a moment right after opening a thread.
            val snapshot = stabilizeTitle(pkg ?: "", rawSnapshot)
            if (!prefs.isAllowed(snapshot.title)) { main.post { overlay?.hide() }; return }
            // In a chat window but the tree holds no text (Feishu draws its bodies,
            // WeChat hides them when the disguise fails) → screenshot + OCR, subject
            // to ScreenCapture's own >=1s throttle and failure backoff.
            if (snapshot.messages.isEmpty()) {
                if (prefs.ocrFallback) {
                    // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                    // on every content-changed event, and a successful shot resets the
                    // failure backoff — so without this the caret blinking or an
                    // "online" badge flipping keeps a screenshot going out every
                    // second forever. The picture can only differ if the bubbles moved
                    // or the conversation changed, and that is exactly what the
                    // signature measures.
                    val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                    if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                    lastOcrSignature = sig
                    ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
                }
                return
            }

            // Track the active package for adapter lookup; switching app also forces a
            // re-evaluation of the dedupe signature.
            if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

            currentSnapshot = snapshot
            val sig = snapshot.signature()
            val showing = overlay?.isShowing() == true

            // Conversation identity = package + (stabilized) title. A *different*
            // conversation must wipe the previous judgment so it cannot leak across
            // chats. A *new message in the same* conversation must NOT — otherwise the
            // result blinks back to the "分析当前对话" button on every content change.
            // That blink is the "分析出来后页面经常消失" bug: any incoming message,
            // "对方正在输入", a read receipt, or a scroll flips the signature, and the
            // old code treated it as a brand-new conversation and wiped the panel.
            val convKey = "$pkg:${snapshot.title}"
            if (convKey != activeConvKey) {
                activeConvKey = convKey
                lastSignature = ""          // re-evaluate this chat from scratch
                main.post { overlay?.resetForNewConversation() }
            }

            // Same content and the bubble is already up → nothing to do.
            if (sig == lastSignature && showing) return
            // Same content but the bubble is gone (killed by MIUI, or we left and came
            // back) → restore the previous judgment for THIS conversation instead of
            // dropping the user back to the "分析当前对话" button. No re-analysis,
            // no re-spend — the result was already paid for.
            if (sig == lastSignature && !showing) { main.post { overlay?.showRestored() }; return }
            // Content changed within the SAME conversation: keep the previous judgment
            // on screen and re-evaluate. The loading/result states below replace it
            // without ever flashing the idle "分析当前对话" button.
            lastSignature = sig
            Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
                snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

            // Trigger only when the newest message is from the other person, and only
            // if auto-analyze is on. Otherwise keep/restore whatever this
            // conversation already has (a previous judgment, or the idle bubble).
            if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
                main.post { overlay?.showRestored() }; return
            }

            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            main.postDelayed(debounce, ANALYSIS_DEBOUNCE_MS)
        } finally {
            root.recycle()
        }
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Replace a transient title with the last known-good one for this package
     *  (if any), and otherwise remember the current title as the new good one. */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing) return
        if (!prefs.hasKey()) { main.post { overlay?.showError("未设置判断接口密钥，去设置里填") }; return }
        analyzing = true
        // 方案 B: per-message annotations ride along with the judgment —
        // chat-judge route only (the Jev protocol has no such question type),
        // advisor mode only, user-toggleable because it is one extra call.
        val wantsTimeline = prefs.isAdvisor && prefs.advisorTimeline &&
            prefs.judgeProvider == Prefs.PROVIDER_CHAT
        if (wantsTimeline) main.post { overlay?.beginTimeline() }
        main.post { overlay?.showLoading(); overlay?.setNote(snapshot.note) }
        val client = JevClient(prefs)
        val rel = prefs.relationship
        val pkg = activePkg ?: ""
        // Chooser mode always wants candidates — picking one is the whole
        // interaction. Advisor mode only wants them if the user turned them on;
        // skipping the draft leaves out both the generative call and the Jev
        // ranking call, about two thirds of the cost of a round.
        val wantsReplies = if (prefs.isAdvisor) prefs.advisorGenerateReplies else true
        // Knowledge context first (local file reads only, a few ms), then the two
        // network calls in parallel on the pool. A failure here must never stop
        // the analysis — it just means no extra context this round.
        submit {
            val ctx = try {
                ContextBuilder.build(this, snapshot, pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post { overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0) }

            // Judgment is fast (~1s) — show it immediately.
            submit {
                val judgment = client.judge(snapshot, rel, ctx)
                main.post {
                    when {
                        judgment.error != null -> { analyzing = false; overlay?.showError(judgment.error) }
                        else -> {
                            overlay?.showJudgment(judgment)
                            // Nothing else is coming, so this round is over as
                            // soon as the sheet is up. Leaving `analyzing` set
                            // would block every later analysis.
                            if (!wantsReplies) analyzing = false
                        }
                    }
                }
            }
            if (!wantsReplies) return@submit
            // Candidate replies are slower (generative + rank) — fill in when ready.
            submit {
                var replyError: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                main.post {
                    analyzing = false
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text) }
                }
            }
            // Per-message annotations run in parallel with the draft; they land
            // whenever they land (the panel shows 生成中… until then).
            if (wantsTimeline) {
                submit {
                    val tl = client.judgeTimeline(snapshot, rel)
                    main.post { overlay?.showTimeline(tl.items, tl.error) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        try {
            val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
            // Top bar text, if this app has one we can read; else the first OCR line.
            val title = root?.let {
                findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
            }
            ocrCapture(title, emptyList(), pkg, manual = true)
        } finally {
            root?.recycle()
        }
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy) return
        ocrBusy = true
        screenCapture.capture { res ->
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val r1 = rootInActiveWindow
                        val fresh = r1?.let { collectFeishuBubbleRects(it, resources) }
                        r1?.recycle()
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean) {
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showRestored()
            return
        }
        // Only wipe when we've actually moved to a different conversation; a new
        // message in the same chat must keep the previous judgment (same bug as
        // the tree path — otherwise the result blinks back to the idle button).
        val convKey = "$pkg:${snapshot.title}"
        if (convKey != activeConvKey) {
            activeConvKey = convKey
            lastSignature = ""
            overlay?.resetForNewConversation()
        }
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submit {
            // Fast path: SET_TEXT works when the box already has input focus and no
            // IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (pops the keyboard) and retry SET_TEXT;
                // if the IME composing region still swallows it (WeChat), PASTE from
                // the clipboard. The box is cleared before PASTE so a SET_TEXT that
                // silently took (but failed verification) never gets doubled.
                // Never clicks send.
                val r1 = rootInActiveWindow
                val edit = r1?.let { findEditable(it) }
                r1?.recycle()
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(text)
                    if (!ok) {
                        copyToClipboard(text)
                        val r2 = rootInActiveWindow
                        val focused = r2?.let { findEditable(it) }
                        r2?.recycle()
                        val used = focused ?: edit
                        setTextRaw(used, "")
                        val pasted = used.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        val after = readInput()
                        ok = (after != null && after.contains(text)) || (pasted && after == null)
                        Log.i(TAG, "fill: paste=$pasted readback=${after?.length ?: -1}")
                        focused?.recycle()
                    }
                    edit.recycle()
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    /** Set text on the chat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val r = rootInActiveWindow
        val edit = r?.let { findEditable(it) }
        r?.recycle()
        if (edit == null) return false
        if (!setTextRaw(edit, text)) { edit.recycle(); return false }
        // SET_TEXT can report success without filling an unfocused box; verify.
        // Read back through refresh() — the node cache can still hold the old
        // (empty) text right after the action, which made Feishu look like a
        // failure and triggered a second PASTE on top.
        Thread.sleep(150)
        val after = readInput()
        val ok = after == text
        edit.recycle()
        Log.i(TAG, "fill: setText readback=${after?.length ?: -1} want=${text.length}")
        return ok
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Current text of the input box, fetched fresh (bypassing the node cache). */
    private fun readInput(): String? {
        val r = rootInActiveWindow
        val edit = r?.let { findEditable(it) }
        r?.recycle()
        if (edit == null) return null
        runCatching { edit.refresh() }
        val t = edit.text?.toString()
        edit.recycle()
        return t
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) { found = node; break }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            if (node !== root) node.recycle()
        }
        while (stack.isNotEmpty()) stack.removeLast().recycle()
        return found
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    // --------------------------------------------------------------- sending

    /**
     * Chooser mode's second tap: put [text] in the box, then press the chat
     * app's send button.
     *
     * This is the only irreversible action in the app, so every step is
     * guarded: the box must verify as holding exactly what we just wrote (a
     * stale draft, or a partial paste, must never be what goes out), the button
     * must be an explicitly labelled "send" node inside the input row, and
     * anything money-related is refused. Any doubt means we stop and hand the
     * send key back to the user.
     */
    private fun sendInput(text: String) {
        submit {
            // If the user edited the box between the two taps, their wording
            // wins: the second tap means "send what I have", not "send your
            // suggestion verbatim". Only an untouched box gets the candidate.
            val existing = readInput()
            val toSend = if (!existing.isNullOrBlank() && existing != text) existing else text
            // 1. Write. trySetText only — the clipboard PASTE path is async and
            //    we cannot confirm the box's contents before clicking.
            var ok = trySetText(toSend)
            if (!ok) {
                val r1 = rootInActiveWindow
                val edit = r1?.let { findEditable(it) }
                r1?.recycle()
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(toSend)
                }
                edit?.recycle()
            }
            if (!ok || readInput() != toSend) {
                Log.w(TAG, "send: aborted, input did not verify")
                main.post { overlay?.toast("没写进输入框，已停下，没有发送") }
                return@submit
            }
            // 2. Find the button.
            val r2 = rootInActiveWindow
            val edit2 = r2?.let { findEditable(it) }
            val btn = if (r2 != null) findSendButton(r2, edit2) else null
            if (btn == null) {
                Log.i(TAG, "send: no send button found")
                edit2?.recycle()
                r2?.recycle()
                main.post { overlay?.toast("已填入，但没找到发送按钮，你自己按一下") }
                return@submit
            }
            // 3. Press it.
            val label = btn.text?.toString() ?: btn.contentDescription?.toString() ?: "?"
            val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "send: clicked=$clicked label=$label")
            main.post { overlay?.toast(if (clicked) "已发送" else "发送按钮点不动，你自己按一下") }
            btn.recycle()
            edit2?.recycle()
            r2?.recycle()
        }
    }

    /**
     * The chat app's send button, found by label rather than by resource id:
     * ids differ per app and change per release, but every one of them labels
     * the button 发送 / Send.
     *
     * Only the tree walk lives here; every decision about what may be pressed is
     * in [SendButtonRules], which is pure and unit-tested. A node whose label
     * mentions money is discarded before anything else is considered, so no
     * mislabelled payment control can be pressed by this path.
     */
    private fun findSendButton(
        root: AccessibilityNodeInfo,
        edit: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val screen = SendButtonRules.Screen(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val editRect = Rect().also { edit?.getBoundsInScreen(it) }
        val input = if (edit != null) {
            SendButtonRules.InputBox(editRect.left, editRect.top, editRect.right, editRect.bottom)
        } else null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            val b = Rect(); node.getBoundsInScreen(b)
            val candidate = SendButtonRules.Candidate(label, b.left, b.top, b.right, b.bottom)
            if (!SendButtonRules.isEligible(candidate, screen, input)) {
                if (node !== root && node !== best) node.recycle()
                continue
            }
            // The label is usually a TextView inside the clickable button; walk
            // up a little to find what actually handles the tap.
            var target: AccessibilityNodeInfo = node
            var up = 0
            while (!target.isClickable && up < 3) {
                target = target.parent ?: break
                up++
            }
            if (!target.isClickable) {
                if (node !== root && node !== best) node.recycle()
                if (target !== node && target !== best) target.recycle()
                continue
            }
            val score = SendButtonRules.score(candidate, input, screen)
            if (score > bestScore) {
                val prev = best
                bestScore = score
                best = target
                if (prev != null && prev !== target && prev !== root) prev.recycle()
            }
            if (node !== root && node !== best) node.recycle()
            if (target !== node && target !== best) target.recycle()
        }
        while (stack.isNotEmpty()) stack.removeLast().recycle()
        return best
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onModeChanged = null
        overlay?.onSend = null
        overlay?.hide()
        overlay = null
        main.removeCallbacks(captureRunnable)
        main.removeCallbacks(debounce)
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Quiet period that must elapse after the last content-changed/scroll
         *  event before a capture runs. Small enough that the bubble appears
         *  promptly after a new message, yet still collapses a burst of dozens
         *  of events into a single traversal (the anti-jank brake). */
        private const val CAPTURE_DEBOUNCE_MS = 150L

        /** Quiet period before ANALYSIS starts after a capture. The capture
         *  debounce already ensures the snapshot itself is settled, so this
         *  only needs to cover "a message is still arriving in pieces" —
         *  300ms on top of the capture delay is plenty and was 800ms in an
         *  era when capture ran immediately (the two delays used to stack
         *  into a very sluggish popup). */
        private const val ANALYSIS_DEBOUNCE_MS = 300L

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )

        /**
         * Send-button labels and the money-surface blocklist now live in
         * [SendButtonRules], where they are covered by unit tests.
         */
    }
}
