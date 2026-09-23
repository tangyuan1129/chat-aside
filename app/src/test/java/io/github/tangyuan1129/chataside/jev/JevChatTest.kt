package io.github.tangyuan1129.chataside.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the chat-model-as-judge adapter.
 *
 * Two things can go wrong here and both are silent: a prompt that omits an
 * allowed key lets the model answer with a key we cannot interpret, and a reply
 * we fail to parse looks exactly like the model refusing to answer.
 */
class JevChatTest {

    // ------------------------------------------------------------- prompt

    @Test
    fun `the system prompt states the required shape and forbids extra output`() {
        val p = JevChat.systemPrompt()
        assertTrue(p.contains("\"answers\""))
        assertTrue(p.contains("choice"))
        assertTrue(p.contains("noul"))
        assertTrue(p.contains("score"))
        assertTrue("must forbid prose and fences", p.contains("nothing else"))
    }

    @Test
    fun `every judgment question appears in the prompt`() {
        val block = JevChat.questionsBlock(JevQuestions.judge())
        for (id in listOf(
            "literal_question", "true_intent", "danger_level",
            "should_reply_now", "best_action", "she_needs", "tension_resolved"
        )) {
            assertTrue("missing question: $id", block.contains(id))
        }
    }

    @Test
    fun `every allowed choice key is listed so the model cannot invent one`() {
        val questions = JevQuestions.judge()
        val block = JevChat.questionsBlock(questions)

        for (id in listOf("true_intent", "best_action", "she_needs")) {
            val criteria = questions.getJSONObject(id).getJSONObject("criteria")
            criteria.keys().forEach { key ->
                assertTrue("$id is missing allowed key '$key'", block.contains(key))
            }
        }
    }

    @Test
    fun `the danger scale is rendered level by level`() {
        val questions = JevQuestions.judge()
        val block = JevChat.questionsBlock(questions)
        val levels = questions.getJSONObject("danger_level").getJSONArray("criteria")
        assertTrue("scale should be non-trivial", levels.length() >= 9)
        assertTrue(block.contains("1:"))
        assertTrue(block.contains("${levels.length()}:"))
    }

    @Test
    fun `a noul question shows what true and false mean`() {
        val block = JevChat.questionsBlock(JevQuestions.judge())
        assertTrue(block.contains("true  means:"))
        assertTrue(block.contains("false means:"))
    }

    @Test
    fun `the ranking question is rendered with its candidate keys`() {
        val q = JSONObject().put(
            "best_reply",
            JevQuestions.rankQuestion(listOf("好的", "我错了", "马上来")).getJSONObject("best_reply")
        )
        val block = JevChat.questionsBlock(q)
        assertTrue(block.contains("best_reply"))
        assertTrue(block.contains("reply_a"))
        assertTrue(block.contains("reply_b"))
        assertTrue(block.contains("reply_c"))
    }

    @Test
    fun `the state block renders the transcript and the relationship`() {
        val state = JevQuestions.buildState(
            io.github.tangyuan1129.chataside.core.ChatSnapshot(
                null,
                listOf(
                    io.github.tangyuan1129.chataside.core.Msg("other", "在吗"),
                    io.github.tangyuan1129.chataside.core.Msg("me", "在")
                )
            ),
            relationship = "对方是我的伴侣"
        )
        val block = JevChat.stateBlock(state)
        assertTrue(block.contains("对方是我的伴侣"))
        assertTrue(block.contains("在吗"))
        assertTrue(block.contains("other:"))
        assertTrue(block.contains("me:"))
        assertTrue("should name who spoke last", block.contains("latest message is from"))
    }

    // ------------------------------------------------------------ parsing

    @Test
    fun `a bare object parses`() {
        val obj = JevChat.extractJson("""{"answers":{"a":1}}""")
        assertNotNull(obj)
        assertEquals(1, obj!!.getJSONObject("answers").getInt("a"))
    }

    @Test
    fun `a markdown fenced reply parses`() {
        val raw = "```json\n{\"answers\":{\"true_intent\":{\"choice\":\"casual_chat\"}}}\n```"
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals("casual_chat", ans!!.getJSONObject("true_intent").getString("choice"))
    }

    @Test
    fun `a reply with a friendly preamble parses`() {
        val raw = "好的，这是分析结果：\n{\"answers\":{\"danger_level\":{\"score\":3}}}\n希望有帮助！"
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals(3, ans!!.getJSONObject("danger_level").getInt("score"))
    }

    @Test
    fun `braces inside a string value do not truncate the object`() {
        // Braces need no escaping in JSON, which is exactly why the scanner has
        // to track string state: scanning to the last '}' in the text happens to
        // work here, but scanning to the first one would cut the object in half.
        val raw = """{"answers":{"note":"他说 {这里} 有括号","ok":true}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertTrue(ans!!.getBoolean("ok"))
    }

    @Test
    fun `a closing brace inside a string does not end the object early`() {
        // The decisive case: the text after the fake '}' proves we did not stop
        // there. Scanning to the first unbalanced '}' would return {"answers":{}
        // and lose "ok".
        val raw = """{"answers":{"note":"}","ok":true}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertTrue("the trailing field must survive", ans!!.getBoolean("ok"))
    }

    @Test
    fun `an escaped quote inside a string does not end the string early`() {
        val raw = """{"answers":{"q":"他问：\"你还记得吗\"","ok":1}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals(1, ans!!.getInt("ok"))
    }

    @Test
    fun `nested objects are handled`() {
        val raw = """{"answers":{"true_intent":{"choice":"vent_anger","confidence":0.8,"probabilities":{"vent_anger":0.8,"care":0.2}}}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals(
            0.8,
            ans!!.getJSONObject("true_intent").getJSONObject("probabilities").getDouble("vent_anger"),
            0.001
        )
    }

    @Test
    fun `an object without the answers wrapper is still used`() {
        val ans = JevChat.parseAnswers("""{"true_intent":{"choice":"casual_chat"}}""")
        assertNotNull("a model that forgot the wrapper should still be usable", ans)
        assertEquals("casual_chat", ans!!.getJSONObject("true_intent").getString("choice"))
    }

    @Test
    fun `prose with no json at all yields nothing`() {
        assertNull(JevChat.parseAnswers("我无法判断这段对话。"))
        assertNull(JevChat.extractJson(""))
    }

    @Test
    fun `an unbalanced brace yields nothing rather than throwing`() {
        assertNull(JevChat.extractJson("""{"answers":{"a":1}"""))
    }

    @Test
    fun `a later valid object is found when the first braces are not json`() {
        val raw = """用 {这个} 说明一下：{"answers":{"ok":1}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals(1, ans!!.getInt("ok"))
    }

    @Test
    fun `the real expected answer shape survives a round trip`() {
        val raw = """
            ```json
            {
              "answers": {
                "literal_question": {"noul": 0.15},
                "true_intent": {"choice": "request_action", "confidence": 0.72},
                "danger_level": {"score": 4},
                "should_reply_now": {"noul": 0.9},
                "best_action": {"choice": "make_plan", "confidence": 0.66},
                "she_needs": {"choice": "action", "confidence": 0.7},
                "tension_resolved": {"noul": 0.2}
              }
            }
            ```
        """.trimIndent()
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        assertEquals("request_action", ans!!.getJSONObject("true_intent").getString("choice"))
        assertEquals(4, ans.getJSONObject("danger_level").getInt("score"))
        assertEquals(0.9, ans.getJSONObject("should_reply_now").getDouble("noul"), 0.001)
        assertFalse(ans.has("nonexistent"))
    }
}
