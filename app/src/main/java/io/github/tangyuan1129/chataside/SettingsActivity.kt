package io.github.tangyuan1129.chataside

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.Msg
import io.github.tangyuan1129.chataside.core.Prefs
import io.github.tangyuan1129.chataside.core.kb.KbSelfCheck
import io.github.tangyuan1129.chataside.core.kb.KbStore
import io.github.tangyuan1129.chataside.jev.JudgeClient
import io.github.tangyuan1129.chataside.jev.ModelCatalog
import io.github.tangyuan1129.chataside.jev.ReplyClient
import io.github.tangyuan1129.chataside.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 工作模式 ===================
        // Deliberately the first thing on the page: it decides what the overlay
        // shows at all, so it outranks the API keys in the user's mental model.
        // Unlike every other card here these switches apply immediately (the
        // bubble menu has always worked that way, and a mode that only takes
        // effect after Save would feel broken).
        root.addView(section("工作模式"))
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modeBox)

        fun renderModeCard() {
            modeBox.removeAllViews()
            val c = card()
            c.addView(cardTitle("怎么用这个助手"))
            c.addView(text(
                "两种模式共用同一套判断，区别只在最后给你什么。即改即生效，悬浮球菜单里也能随时切。",
                12f, sub))

            c.addView(modeOption(
                "选项模式",
                "像游戏里选台词：直接给三条能说的话，按合适度排好序。点一下填进输入框，连点两下直接发出去。",
                prefs.isChooser
            ) {
                prefs.overlayMode = Prefs.MODE_CHOOSER
                Toast.makeText(this, "已切到选项模式", Toast.LENGTH_SHORT).show()
                renderModeCard()
            })
            c.addView(modeOption(
                "参谋模式",
                "只给判断：对方真实意图、危险等级、对方想要什么、这一步该干什么。回复你自己写。",
                prefs.isAdvisor
            ) {
                prefs.overlayMode = Prefs.MODE_ADVISOR
                Toast.makeText(this, "已切到参谋模式", Toast.LENGTH_SHORT).show()
                renderModeCard()
            })

            if (prefs.isChooser) {
                c.addView(label("选项模式"))
                c.addView(toggleRow("连点两下直接发送", prefs.chooserDoubleTapSend) { now ->
                    prefs.chooserDoubleTapSend = now
                })
                c.addView(text(
                    "关掉就只填入输入框，永不发送。开启时第一下只填入并短暂待命，连点同一个选项第二下才真发出去；" +
                        "换一条选项或超过待命时长就取消待命。",
                    11f, sub))
                c.addView(label("双击待命时长"))
                val windows = listOf(1500, 2500, 4000)
                val wIdx = when {
                    prefs.chooserDoubleTapWindowMs <= 1800 -> 0
                    prefs.chooserDoubleTapWindowMs >= 3300 -> 2
                    else -> 1
                }
                c.addView(pills(listOf("1.5 秒", "2.5 秒", "4 秒"), wIdx) { i ->
                    prefs.chooserDoubleTapWindowMs = windows[i]
                })
            } else {
                c.addView(label("参谋模式"))
                c.addView(toggleRow("也生成三条候选回复（仅供参照）",
                    prefs.advisorGenerateReplies) { now ->
                    prefs.advisorGenerateReplies = now
                })
                c.addView(text(
                    "关闭时每次分析只调判断接口，省掉生成和排序两次调用，成本约为原来的三分之一。",
                    11f, sub))
            }
            modeBox.addView(c)
        }
        renderModeCard()

        // =================== 接口 ===================
        root.addView(section("接口"))

        // Asks the endpoint which models it actually offers, then lets the user
        // pick one. Best-effort on purpose: plenty of providers have no /models,
        // so a failure says so and leaves the model box editable by hand rather
        // than blocking configuration.
        fun detectModels(baseUrl: String, key: String, result: TextView, apply: (String) -> Unit) {
            if (key.isBlank()) { result.text = "请先填密钥，检测要用到它"; return }
            result.text = "检测模型中…"
            worker.execute {
                val models = try {
                    ModelCatalog.fetch(baseUrl, key)
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    main.post { result.text = "检测失败：$msg（仍可手动填模型名）" }
                    return@execute
                }
                main.post {
                    result.text = "检测到 ${models.size} 个模型"
                    showModelPicker(models, apply)
                }
            }
        }

        // --- 判断接口（Jev） ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("判断接口（Jev）"))
        judgeCard.addView(text("读对方消息、给意图判断和候选排序。必须配置。", 12f, sub))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
        judgeProviderIdx = when (prefs.judgeProvider) {
            Prefs.PROVIDER_TYPESAFE -> 1
            Prefs.PROVIDER_CUSTOM -> 2
            Prefs.PROVIDER_CHAT -> 3
            else -> 0
        }
        judgeCard.addView(pills(
            listOf("OpenRouter", "TypeSafe 直连", "自定义", "聊天模型兼任判断"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
                }
                // Custom POSTs the box verbatim, so a preset HOST left in the box
                // would hit the API root. Expand it into the full endpoint the
                // preset would have used; anything hand-typed is left alone.
                2 -> judgeBaseEdit.setText(expandJudgeUrl(judgeBaseEdit.text.toString()))
                // Chat-as-judge wears the same shape as the reply route, and most
                // people point both at one provider — so start from whatever the
                // reply route is already set to instead of making them retype it.
                3 -> {
                    judgeBaseEdit.setText(prefs.replyBaseUrl.trim().ifBlank { Prefs.DEFAULT_REPLY_BASE })
                    judgeModelEdit.setText(prefs.replyModel.trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL })
                }
            }
        })
        judgeCard.addView(label("Base URL"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text(
            "前两档拼各自的专用路径；「自定义」按原样 POST（要填到接口全路径）；" +
                "「聊天模型兼任判断」拼 /chat/completions，填到 /v1 为止即可。",
            11f, sub))
        judgeCard.addView(text(
            "只有 OpenRouter 和 TypeSafe 提供 Jev 判断模型。如果你手上只有国内模型的密钥，" +
                "就选「聊天模型兼任判断」——用同一个聊天模型顺带把判断也做了，只需一把密钥。",
            11f, sub))
        judgeCard.addView(label("密钥"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("检测可用模型") {
            val base = judgeBaseEdit.text.toString().trim()
            detectModels(
                baseUrl = base.ifBlank { defaultJudgeBase(resolveJudgeProvider(judgeProviderIdx, base)) },
                key = judgeKeyEdit.text.toString().trim(),
                result = judgeResult
            ) { judgeModelEdit.setText(it) }
        })
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            // Provider follows the address when it is still a known preset host,
            // so a stale pill selection cannot send a TypeSafe path to OpenRouter.
            val provider = resolveJudgeProvider(judgeProviderIdx, base)
            if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
                judgeResult.text = "自定义档要填完整 URL（带路径）"; return@cardBtn
            }
            // Custom means we know nothing about the endpoint — guessing a model
            // name here would test something the user never asked for.
            if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
                judgeResult.text = "请填写模型名"; return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = provider
                judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
                judgeKey = key
                judgeModel = model.ifBlank { defaultJudgeModel(provider) }
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。", 12f, sub))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val replyModelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0
            Prefs.DEEPSEEK_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        replyCard.addView(pills(
            listOf("OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        })
        replyCard.addView(label("Base URL"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "留空则用判断接口密钥", password = true).also { replyKeyEdit = it })
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("检测可用模型") {
            detectModels(
                baseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE },
                // Blank reply key means "reuse the judge key" everywhere else, so
                // detection has to look there too or it would refuse to run.
                key = replyKeyEdit.text.toString().trim().ifBlank {
                    judgeKeyEdit.text.toString().trim()
                },
                result = replyResult
            ) { replyModelEdit.setText(it) }
        })
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_REPLY) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥（或填判断接口密钥）"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可先不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。B 阶段才用到，现在填不填都不影响。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_VISION_BASE -> 0
            Prefs.DASHSCOPE_BASE -> 1
            else -> 2
        }
        visionCard.addView(pills(
            listOf("OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.DEFAULT_VISION_BASE })) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)

        // --- OCR 兜底（B 阶段）---
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的、微信伪装失效时也读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 关于 ===================
        // NOTICE 要求在产品内注明出处，这一节就是履行那条要求；
        // 顺带把数据流向写在用户看得到的地方，而不是只写在仓库 README 里。
        root.addView(section("关于"))
        val aboutCard = card()
        aboutCard.addView(cardTitle("旁白 · chat-aside"))
        aboutCard.addView(text("版本 ${appVersion()}", 12f, sub))

        aboutCard.addView(label("数据流向"))
        aboutCard.addView(text(
            "聊天内容只在你触发分析的那一刻，发给你在「接口」里自己配置的模型服务。" +
                "本项目没有自建服务器，不收集、不上报，也不把聊天内容写入磁盘。" +
                "密钥存在应用私有空间，不进日志。" +
                "聊天历史默认关闭，开启后也只存在本机，可在「分析」里一键清空。" +
                "截屏 OCR 使用随包的离线中文模型，图片不外传。",
            11.5f, sub).apply { setLineSpacing(dp(2).toFloat(), 1f) })

        aboutCard.addView(label("发送行为"))
        aboutCard.addView(text(
            "默认只把文字填进输入框，不发送。" +
                "选项模式下连点同一选项两下才会发送，该功能可在「工作模式」里关闭。",
            11.5f, sub).apply { setLineSpacing(dp(2).toFloat(), 1f) })

        aboutCard.addView(label("来源与许可"))
        aboutCard.addView(text(
            "本项目基于 Jev 聊天助手（Jev Chat Assistant）二次开发，以 MIT 协议开源，" +
                "并保留上游的 LICENSE 与 NOTICE。" +
                "本项目不是上游官方版本，与上游作者无隶属或背书关系，" +
                "也不使用「Jev 聊天助手」名称或相关域名作为自身标识。",
            11.5f, sub).apply { setLineSpacing(dp(2).toFloat(), 1f) })

        aboutCard.addView(cardBtn("查看上游项目") { openUrl("https://github.com/jev-chat/jev-chat-jarvis") })
        aboutCard.addView(cardBtn("查看本项目源码") { openUrl("https://github.com/tangyuan1129/chat-aside") })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            // Address wins over the pill: a preset HOST in the box means that
            // preset's provider (and so its path), whatever the pill last said.
            val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
            val judgeProv = resolveJudgeProvider(judgeProviderIdx, judgeBaseTyped)
            val judgeModelTyped = judgeModelEdit.text.toString().trim()
            prefs.judgeProvider = judgeProv
            // Blank falls back to THIS provider's preset — never OpenRouter's by
            // default. Custom is left exactly as typed (blank included): guessing
            // a URL for it would silently point somewhere the user did not choose.
            prefs.judgeBaseUrl = when {
                judgeBaseTyped.isNotBlank() -> judgeBaseTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeBase(judgeProv)
            }
            prefs.judgeKey = judgeKeyEdit.text.toString()
            prefs.judgeModel = when {
                judgeModelTyped.isNotBlank() -> judgeModelTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeModel(judgeProv)
            }

            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: false
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_TYPESAFE
        2 -> Prefs.PROVIDER_CUSTOM
        3 -> Prefs.PROVIDER_CHAT
        else -> Prefs.PROVIDER_OPENROUTER
    }

    /**
     * The provider actually implied by what is in the address box. A preset host
     * carries its own path (`/alpha/decisions`, `/v1/systemone`), so leaving that
     * host in the box while the pill says something else would POST the wrong
     * path — or, for custom, the bare API root.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            else -> providerOf(idx)
        }

    /** The full endpoint a preset host would have been expanded to. */
    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        // Chat-as-judge talks to the same kind of endpoint the reply route does,
        // so it borrows that default rather than inventing a third one.
        Prefs.PROVIDER_CHAT -> Prefs.DEFAULT_REPLY_BASE
        else -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
    }

    private fun defaultJudgeModel(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        Prefs.PROVIDER_CHAT -> Prefs.DEFAULT_REPLY_MODEL
        else -> Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    /**
     * A labelled on/off row. The current value lives in [LinearLayout.getTag] so
     * the Save button can read every row back in one sweep.
     *
     * [onToggle] is for switches that must take effect the moment they are
     * flipped (the overlay-mode card) instead of waiting for Save.
     */
    private fun toggleRow(
        labelText: String,
        initial: Boolean,
        onToggle: ((Boolean) -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
            onToggle?.invoke(now)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    /**
     * A radio-style card for a two-way choice: filled title plus a sentence of
     * explanation. Used for the overlay-mode pick, where the difference between
     * the options is not obvious from the label alone.
     */
    private fun modeOption(
        title: String,
        desc: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (selected) round(dp(12), Color.parseColor("#EAF1FF"), stroke = true)
        else round(dp(12), Color.parseColor("#F7F8FA"))
        setPadding(dp(13), dp(12), dp(13), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
        isClickable = true
        setOnClickListener { onClick() }
        addView(text((if (selected) "● " else "○ ") + title, 15f, if (selected) accent else ink, bold = true))
        addView(text(desc, 11.5f, sub).apply {
            setPadding(0, dp(5), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    /** Version name from the manifest, shown in the About card. */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "未知"

    /** Opening a link is a convenience; it must never take the settings page down. */
    private fun openUrl(url: String) {
        val ok = runCatching {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.isSuccess
        if (!ok) Toast.makeText(this, "打不开链接：$url", Toast.LENGTH_SHORT).show()
    }

    /**
     * Searchable list of detected model ids.
     *
     * OpenRouter alone offers several hundred, so a plain list would be unusable —
     * the filter box narrows it as you type. Picking a row writes it into the
     * model box; dismissing changes nothing.
     */
    private fun showModelPicker(models: List<String>, onPick: (String) -> Unit) {
        val filter = EditText(this).apply {
            hint = "筛选：输入几个字母，例如 deepseek"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        }
        val shown = ArrayList(models)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        list.adapter = adapter

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(filter)
            addView(list)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("检测到 ${models.size} 个模型，点一个填入")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()

        filter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                shown.clear()
                shown.addAll(
                    if (q.isEmpty()) models else models.filter { it.lowercase().contains(q) }
                )
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let {
                onPick(it)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "该接口不支持视觉（DeepSeek 官方没有 image_url），请换 OpenRouter 或通义兼容"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

    }
}
