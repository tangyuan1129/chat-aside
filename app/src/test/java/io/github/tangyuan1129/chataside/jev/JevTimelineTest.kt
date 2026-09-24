package io.github.tangyuan1129.chataside.jev

import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing/prompt tests for the per-message annotation timeline. */
class JevTimelineTest {

    private fun snap(vararg pairs: Pair<String, String>) =
        ChatSnapshot("测试", pairs.map { Msg(it.first, it.second) })

    @Test
    fun `prompt numbers every message and annotates only the other side`() {
        val p = JevTimeline.userPrompt(snap("other" to "在吗", "me" to "在"), "对方是伴侣")
        assertTrue(p.contains("1. other: 在吗"))
        assertTrue(p.contains("2. me: 在"))
        assertTrue(p.contains("from=other"))
    }

    @Test
    fun `the system prompt pins the output shape and forbids identical notes`() {
        val p = JevTimeline.systemPrompt()
        assertTrue(p.contains("\"timeline\""))
        assertTrue(p.contains("from=other"))
        assertTrue(p.contains("一字不差"))
    }

    @Test
    fun `parse recovers items and quotes text from the snapshot not the model`() {
        val s = snap("other" to "你今天是不是又忘了", "me" to "记得", "other" to "那你说")
        val raw = """
            ```json
            {"timeline":[
              {"i":1,"note":["当前真实意图：确认你在不在乎她 72%","危险等级 9/10"]},
              {"i":3,"note":["该不该给实质：不该 82%"]}
            ]}
            ```
        """.trimIndent()
        val items = JevTimeline.parse(raw, s)!!
        assertEquals(2, items.size)
        // Quoted text comes from the snapshot — even if the model echoed a
        // paraphrase, we never render its version of the message.
        assertEquals("你今天是不是又忘了", items[0].text)
        assertEquals("那你说", items[1].text)
        assertEquals(2, items[0].lines.size)
        assertTrue(items[0].lines[0].contains("72%"))
    }

    @Test
    fun `out-of-range duplicated and from-me indexes are dropped`() {
        val s = snap("other" to "你好", "me" to "嗨")
        val raw = """{"timeline":[
            {"i":0,"note":["越界"]},
            {"i":2,"note":["这是我自己的消息"]},
            {"i":2,"note":["重复编号"]},
            {"i":9,"note":["超出范围"]},
            {"i":1,"note":["有效批注"]}
        ]}"""
        val items = JevTimeline.parse(raw, s)!!
        assertEquals(1, items.size)
        assertEquals("有效批注", items[0].lines[0])
    }

    @Test
    fun `missing timeline or garbage returns null so the caller can error`() {
        val s = snap("other" to "在吗")
        assertNull(JevTimeline.parse("这不是一段 JSON，只是普通文本", s))
        assertNull(JevTimeline.parse("""{"answers":{"true_intent":{}}}""", s))
        assertNull(JevTimeline.parse("""{"timeline":[]}""", s))
    }
}
