package io.github.tangyuan1129.chataside.jev

import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.TimelineNote
import org.json.JSONArray

/**
 * The per-message timeline (方案 B): instead of one summary for the whole
 * thread, each incoming message gets its own short annotation, the way the
 * upstream demo sheet shows it.
 *
 * Like [JevChat], this exists so a plain OpenAI-compatible chat model can do
 * the work: the Jev decision protocol only accepts typed questions
 * (choice/noul/score), so a "annotate each message" request has no Jev
 * representation. The transcript is numbered 1..N, the model is asked to
 * annotate the from=other messages, and every note line is free-form Chinese
 * rendered verbatim in the panel — one JSON array of strings is the most
 * fence-proof shape a chat model can be asked to produce.
 *
 * The quoted message text is copied from the snapshot by [parse], never from
 * the model's echo, so a model that paraphrases or truncates cannot put words
 * in anyone's mouth.
 */
object JevTimeline {

    /** How many trailing messages are sent for annotation. Cost scales with
     *  this; 8 covers a screenful of most chats without ballooning output. */
    private const val WINDOW = 8

    fun systemPrompt(): String = buildString {
        appendLine("你是聊天对话的逐条批注者。给你一段编号的聊天记录，只批注 from=other 的消息。")
        appendLine()
        appendLine("输出规则：")
        appendLine("- 只回复一个 JSON 对象，不要任何解释、不要 markdown 代码块。")
        appendLine("- 形状：{\"timeline\":[{\"i\":<消息编号>,\"note\":[\"<一行批注>\",\"...\"]}]}")
        appendLine("- i 必须原样复制消息编号。只批注 from=other 的消息，from=me 的不要出现。")
        appendLine("- 每条消息 2-4 行批注，每行一个角度，行内直接写中文结论和百分比，例如：")
        appendLine("  \"字面还是话里有话：话里有话 88%\"")
        appendLine("  \"当前真实意图：确认你在不在乎她 72% / 生气想吵架 20%\"")
        appendLine("  \"危险等级 9/10\"")
        appendLine("  \"该怎么接：先翻聊天记录再回应，别急着道歉\"")
        appendLine("- 概率是你自己的估计，写百分比；没有把握的角度就不要写数字，写判断理由。")
        appendLine("- 相似的消息也要区分措辞差异，两批注不允许一字不差。")
        appendLine("- 只用 transcript 里出现的内容，不要编造没有的事实。")
    }

    /** Numbers the trailing [WINDOW] messages 1..N and asks for annotations. */
    fun userPrompt(snapshot: ChatSnapshot, relationship: String): String = buildString {
        val msgs = snapshot.messages.takeLast(WINDOW)
        if (relationship.isNotBlank()) appendLine("Context: $relationship")
        appendLine("Transcript（最新在最后，已编号）：")
        msgs.forEachIndexed { i, m ->
            appendLine("${i + 1}. ${m.side}: ${m.text}")
        }
        appendLine("批注上表中所有 from=other 的消息。")
    }

    /**
     * Recovers the timeline from whatever the model returned. Returns null when
     * there is no usable JSON/timeline at all so the caller can show a real
     * error; entries with an out-of-range or from=me index are dropped, not
     * trusted. The quoted text always comes from the snapshot.
     */
    fun parse(raw: String, snapshot: ChatSnapshot): List<TimelineNote>? {
        val obj = JevChat.extractJson(raw) ?: return null
        val arr = obj.optJSONArray("timeline") ?: return null
        val msgs = snapshot.messages.takeLast(WINDOW)
        val out = ArrayList<TimelineNote>()
        val seen = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val idx = e.optInt("i", -1) - 1 // model sees 1-based numbers
            if (idx < 0 || idx >= msgs.size || !seen.add(idx)) continue
            val m = msgs[idx]
            if (m.side != "other") continue
            val note = e.optJSONArray("note") ?: continue
            val lines = ArrayList<String>()
            for (j in 0 until note.length()) {
                val s = note.optString(j).trim()
                if (s.isNotEmpty()) lines.add(s)
            }
            if (lines.isNotEmpty()) out.add(TimelineNote(idx + 1, m.text, lines))
        }
        return out.ifEmpty { null }
    }
}
