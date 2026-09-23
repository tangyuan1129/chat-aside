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
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals("request_action", ans!!.getJSONObject("true_intent").getString("choice"))
        assertEquals(4, ans.getJSONObject("danger_level").getInt("score"))
        assertEquals(0.9, ans.getJSONObject("should_reply_now").getDouble("noul"), 0.001)
        assertFalse(ans.has("nonexistent"))
    }

    // --------------------------------------------------- normalisation
    // Chat models flatten the shape they were asked for. Measured on a real
    // DeepSeek run: the request succeeded in 957ms but the panel showed "意图=?"
    // because the answer did not match the nested form. These cases cover the
    // variants worth accepting rather than failing on.

    private fun judgeQuestions() = JevQuestions.judge()

    @Test
    fun `a bare string is taken as the choice`() {
        val raw = """{"answers":{"true_intent":"request_action"}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals("request_action", ans!!.getJSONObject("true_intent").getString("choice"))
        assertEquals(
            "confidence is unknown, so it must not claim certainty",
            0.5,
            ans.getJSONObject("true_intent").getDouble("confidence"),
            0.001
        )
    }

    @Test
    fun `a bare number on a score question becomes a score`() {
        val raw = """{"answers":{"danger_level":4}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(4, ans!!.getJSONObject("danger_level").getInt("score"))
    }

    @Test
    fun `a bare number on a noul question becomes a noul`() {
        val raw = """{"answers":{"should_reply_now":0.8}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(0.8, ans!!.getJSONObject("should_reply_now").getDouble("noul"), 0.001)
    }

    @Test
    fun `a boolean becomes a noul`() {
        val raw = """{"answers":{"tension_resolved":false}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(0.0, ans!!.getJSONObject("tension_resolved").getDouble("noul"), 0.001)
    }

    @Test
    fun `noul values are clamped to zero and one`() {
        val raw = """{"answers":{"should_reply_now":7}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(1.0, ans!!.getJSONObject("should_reply_now").getDouble("noul"), 0.001)
    }

    @Test
    fun `an answer key is accepted in place of choice`() {
        val raw = """{"answers":{"best_action":{"answer":"apologize","confidence":0.6}}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        val a = ans!!.getJSONObject("best_action")
        assertEquals("apologize", a.getString("choice"))
        assertEquals(0.6, a.getDouble("confidence"), 0.001)
    }

    @Test
    fun `a value key carrying a number on a score question becomes a score`() {
        val raw = """{"answers":{"danger_level":{"value":6}}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(6, ans!!.getJSONObject("danger_level").getInt("score"))
    }

    @Test
    fun `a canonical answer is passed through untouched`() {
        val raw = """{"answers":{"true_intent":{"choice":"casual_chat","confidence":0.9,"probabilities":{"casual_chat":0.9}}}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(
            0.9,
            ans!!.getJSONObject("true_intent").getJSONObject("probabilities").getDouble("casual_chat"),
            0.001
        )
    }

    @Test
    fun `an unrecognised key survives normalisation instead of being dropped`() {
        val raw = """{"answers":{"true_intent":"casual_chat","some_extra":"keep me"}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertTrue("unknown fields must not vanish", ans!!.has("some_extra"))
    }

    @Test
    fun `without types the answer is returned as parsed`() {
        val raw = """{"answers":{"true_intent":"request_action"}}"""
        val ans = JevChat.parseAnswers(raw)
        assertNotNull(ans)
        // Not normalised, so this is still a string — documents the default.
        assertEquals("request_action", ans!!.getString("true_intent"))
    }

    @Test
    fun `the diagnostic shape lists key names only`() {
        val ans = JSONObject("""{"true_intent":{"choice":"x"},"danger_level":{"score":3}}""")
        assertEquals("danger_level,true_intent", JevChat.describeShape(ans))
    }

    @Test
    fun `question types are derived from the questions asked`() {
        val types = JevChat.questionTypes(judgeQuestions())
        assertEquals("choice", types["true_intent"])
        assertEquals("score", types["danger_level"])
        assertEquals("noul", types["tension_resolved"])
    }

    // ------------------------------------------- regression: the numbering bug
    // The first version of the prompt listed the questions as "1. true_intent",
    // "2. danger_level", … A real DeepSeek run then answered with the keys
    // `1,2,3,4,5,6,7`: the request succeeded in 957ms and the panel still showed
    // "意图=?" because none of it could be read back. The numbering was the cause.

    @Test
    fun `the prompt does not number the questions`() {
        val block = JevChat.questionsBlock(judgeQuestions())
        assertFalse(
            "numbering makes the model key its answers by position",
            block.contains("1. true_intent")
        )
        assertFalse("no leading ordinals at all", Regex("(?m)^\\s*\\d+\\. ").containsMatchIn(block))
        assertTrue("the id must be the visible anchor", block.contains("=== true_intent ==="))
    }

    @Test
    fun `the prompt never numbers the ranking question either`() {
        val q = JSONObject().put(
            "best_reply",
            JevQuestions.rankQuestion(listOf("a", "b", "c")).getJSONObject("best_reply")
        )
        val block = JevChat.questionsBlock(q)
        assertFalse(Regex("(?m)^\\s*\\d+\\. ").containsMatchIn(block))
        assertTrue(block.contains("=== best_reply ==="))
    }

    @Test
    fun `the prompt presents the questions in a deterministic order`() {
        // The positional fallback maps an answer key like "3" onto a position in
        // the prompt, so that order has to be stable across runs AND platforms.
        assertEquals(
            listOf(
                "best_action", "danger_level", "literal_question",
                "she_needs", "should_reply_now", "tension_resolved", "true_intent"
            ),
            JevChat.orderedIds(judgeQuestions())
        )
    }

    @Test
    fun `a positional key is mapped back onto its question`() {
        // What the real run returned: answers keyed "1".."7". In the prompt's
        // order true_intent is the seventh question.
        val raw = """{"answers":{"7":"request_action"}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals("request_action", ans!!.getJSONObject("true_intent").getString("choice"))
    }

    @Test
    fun `a positional key on a score question keeps the score type`() {
        // Position 2 is danger_level, a score question.
        val raw = """{"answers":{"2":4}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertEquals(4, ans!!.getJSONObject("danger_level").getInt("score"))
    }

    @Test
    fun `a positional key out of range is left alone rather than misassigned`() {
        val raw = """{"answers":{"99":"mystery"}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertTrue("an unknown position must not be guessed onto a question", ans!!.has("99"))
    }

    // ------------------------------------- the 0% ranking lines (real-device bug)
    // A WeChat run rendered every candidate as "#1 · 0%" because the model named
    // its pick without a distribution and the ranker reads `probabilities`.

    @Test
    fun `a named pick gets a one-hot probabilities map`() {
        val raw = """{"answers":{"true_intent":{"choice":"request_action"}}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        val probs = ans!!.getJSONObject("true_intent").getJSONObject("probabilities")
        assertEquals(1.0, probs.getDouble("request_action"), 0.001)
        assertEquals(1, probs.length())
    }

    @Test
    fun `an existing distribution is left untouched`() {
        val raw = """{"answers":{"true_intent":{"choice":"vent_anger","probabilities":{"vent_anger":0.7,"care":0.3}}}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        val probs = ans!!.getJSONObject("true_intent").getJSONObject("probabilities")
        assertEquals(0.3, probs.getDouble("care"), 0.001)
        assertEquals("a one-hot map must not overwrite the model's own spread", 2, probs.length())
    }

    @Test
    fun `a bare-string choice also gets a one-hot map`() {
        val raw = """{"answers":{"best_action":"apologize"}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        val a = ans!!.getJSONObject("best_action")
        assertEquals("apologize", a.getString("choice"))
        assertEquals(1.0, a.getJSONObject("probabilities").getDouble("apologize"), 0.001)
    }

    @Test
    fun `the ranking answer becomes rankable`() {
        // What the rank call actually gets back: {"answers":{"best_reply": ...}}
        val questions = JSONObject().put(
            "best_reply",
            JevQuestions.rankQuestion(listOf("甲", "乙", "丙")).getJSONObject("best_reply")
        )
        val raw = """{"answers":{"best_reply":"reply_b"}}"""
        val ans = JevChat.parseAnswers(raw, questions)
        assertNotNull(ans)
        val probs = ans!!.getJSONObject("best_reply").getJSONObject("probabilities")
        assertEquals("the picked candidate must outrank the others", 1.0, probs.getDouble("reply_b"), 0.001)
        assertEquals(0.0, probs.optDouble("reply_a", 0.0), 0.001)
    }

    @Test
    fun `score and noul answers are not given probabilities`() {
        val raw = """{"answers":{"danger_level":4,"tension_resolved":false}}"""
        val ans = JevChat.parseAnswers(raw, judgeQuestions())
        assertNotNull(ans)
        assertFalse(ans!!.getJSONObject("danger_level").has("probabilities"))
        assertFalse(ans.getJSONObject("tension_resolved").has("probabilities"))
    }
}
