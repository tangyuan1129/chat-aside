package io.github.tangyuan1129.chataside.jev

import android.util.Log
import io.github.tangyuan1129.chataside.core.Analysis
import io.github.tangyuan1129.chataside.core.ChatSnapshot
import io.github.tangyuan1129.chataside.core.Choice
import io.github.tangyuan1129.chataside.core.Prefs
import io.github.tangyuan1129.chataside.core.RankedReply
import io.github.tangyuan1129.chataside.core.Score
import io.github.tangyuan1129.chataside.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs]; nothing generative here.
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** Ask Jev which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live `alpha/decisions` endpoint accepts the
     * new `background` / `history` state fields or rejects unknown ones with a
     * 4xx is not verified against production yet (see the A-stage report). If a
     * request carrying them comes back 4xx, it is sent again once without them,
     * so an unverified field can degrade the analysis but never break it.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        // One branch, right here: everything above builds the questions, and
        // everything below parses the answers, so neither has to care which
        // protocol carried them.
        if (prefs.judgeProvider == Prefs.PROVIDER_CHAT) return sendViaChat(url, state, questions)

        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    /**
     * Sends the same questions as an ordinary chat completion instead of a Jev
     * decision request, so a plain model (DeepSeek, Qwen, anything OpenAI-shaped)
     * can do the judging.
     *
     * `temperature` is pinned to 0: this is a classification task, and a model
     * that wanders will produce keys we have no meaning for.
     *
     * No `response_format` is sent even though some providers accept it — an
     * unsupported parameter turns into a 400 on the providers that do not, and
     * [JevChat.extractJson] already copes with a fenced or padded reply.
     */
    private fun sendViaChat(url: String, state: JSONObject, questions: JSONObject): JSONObject {
        val user = JevChat.stateBlock(state) + "\n" + JevChat.questionsBlock(questions)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", JevChat.systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("messages", messages)
            .put("temperature", 0.0)

        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        val content = resp.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

        if (content.isBlank()) {
            throw ApiException(Route.JUDGE, null, "聊天模型返回了空内容")
        }
        val answers = JevChat.parseAnswers(content, questions)
            ?: throw ApiException(Route.JUDGE, null, "模型没有按要求返回 JSON：${content.take(80)}")

        // Warn only when NONE of the questions we asked came back. The first
        // version of this checked for "true_intent" specifically, which made the
        // ranking call — where true_intent is never asked for — log a false alarm
        // on every single analysis.
        val noneAnswered = questions.keys().asSequence().none { answers.has(it) }
        if (noneAnswered) {
            // Key names only — values are never logged, so nothing said in the
            // conversation ends up in logcat. This is the difference between
            // "the model disagreed" and "we could not read its answer", which
            // look identical in the panel.
            Log.w(TAG, "chat judge answered nothing we asked for; keys=${JevChat.describeShape(answers)}")
        }
        return answers
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
