package io.github.tangyuan1129.chataside.jev

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets a plain OpenAI-compatible chat model stand in for the Jev decision model.
 *
 * The judge route normally speaks Jev's own protocol (`{model, state, questions}`
 * in, `{answers: …}` out) and only OpenRouter or TypeSafe serve it. That makes a
 * Jev endpoint mandatory, which shuts out anyone who only has, say, a DeepSeek
 * key — the whole app then does nothing.
 *
 * The trick here is that the questions are already *written* as prompt material:
 * every question carries its instructions and, for choices, one description per
 * allowed key. So the same definitions that drive the Jev API can drive a chat
 * prompt, and if we make the model answer in the same shape, none of the parsing
 * downstream has to change.
 *
 * Both halves — rendering the prompt and recovering JSON from whatever the model
 * actually said — are pure functions and are unit-tested in `JevChatTest`.
 */
object JevChat {

    /**
     * The system message. Deliberately strict about output format: chat models
     * like to add a friendly sentence or wrap the object in a markdown fence, and
     * [extractJson] cleans that up, but the less there is to clean the better.
     */
    fun systemPrompt(): String = buildString {
        appendLine("You are a conversation analyst. You are given a chat transcript and a set of")
        appendLine("judgment questions about it. Answer every question.")
        appendLine()
        appendLine("Output rules:")
        appendLine("- Reply with ONE JSON object and nothing else. No prose, no markdown fences.")
        appendLine("- Shape: {\"answers\": {\"<question_id>\": <answer>, ...}}")
        appendLine("- The key of each answer MUST be the question's id, copied exactly from the")
        appendLine("  line that starts with '==='. Never use a position, number, or paraphrase.")
        appendLine("- A \"choice\" question is answered {\"choice\": \"<key>\", \"confidence\": 0.0-1.0}.")
        appendLine("  The key MUST be copied exactly from that question's allowed keys. Never invent one.")
        appendLine("- A \"noul\" question is answered {\"noul\": 0.0-1.0} (1.0 = clearly yes).")
        appendLine("- A \"score\" question is answered {\"score\": <integer>} using that question's scale.")
        appendLine()
        appendLine("Judge only what the transcript shows. Do not invent facts that are not present.")
    }

    /**
     * Renders the question set as text, including every allowed key so the model
     * cannot answer with a key we have no meaning for.
     *
     * Deliberately **unnumbered**. An earlier version listed the questions as
     * "1. true_intent", "2. danger_level", … and the model answered with the keys
     * `1,2,3,4,5,6,7` instead of the ids — a real run reached the API fine and
     * then produced an empty panel because nothing could be read back out. The id
     * is the only anchor worth giving it.
     */
    fun questionsBlock(questions: JSONObject): String = buildString {
        appendLine("Questions:")
        appendLine()
        // Sorted, and [orderedIds] sorts the same way: the positional fallback
        // maps an answer key like "3" onto a position in THIS order, so the two
        // must agree. Sorting is what makes that safe — JSONObject key order is
        // not even stable across org.json implementations (Android uses a
        // LinkedHashMap, the JVM library a HashMap), so relying on it would
        // silently attach a judgement to the wrong question.
        questions.keys().asSequence().sorted().forEach { id ->
            val q = questions.optJSONObject(id) ?: return@forEach
            val type = q.optString("type")
            appendLine("=== $id ===")
            appendLine("type: $type")
            appendLine("question: ${q.optString("instructions").trim()}")

            when (type) {
                "choice" -> {
                    appendLine("allowed keys (copy one exactly):")
                    val criteria = q.optJSONObject("criteria")
                    criteria?.keys()?.asSequence()?.forEach { key ->
                        appendLine("  - $key: ${criteria.optString(key).trim()}")
                    }
                }
                "score" -> {
                    appendLine("scale:")
                    val levels = q.optJSONArray("criteria") ?: JSONArray()
                    for (n in 0 until levels.length()) {
                        appendLine("  ${n + 1}: ${levels.optString(n).trim()}")
                    }
                }
                "noul" -> {
                    val criteria = q.optJSONObject("criteria")
                    if (criteria != null) {
                        appendLine("true  means: ${criteria.optString("true").trim()}")
                        appendLine("false means: ${criteria.optString("false").trim()}")
                    }
                }
            }
            appendLine()
        }
    }

    /** Renders the Jev state object as a readable transcript for the model. */
    fun stateBlock(state: JSONObject): String = buildString {
        appendLine("Conversation:")
        val chat = state.optJSONObject("chat")
        val relationship = chat?.optString("relationship").orEmpty()
        if (relationship.isNotBlank()) appendLine("Context: $relationship")

        val history = state.optJSONArray("history")
        if (history != null && history.length() > 0) {
            appendLine("Earlier:")
            for (i in 0 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                appendLine("  ${m.optString("from")}: ${m.optString("text")}")
            }
        }

        val background = state.optString("background")
        if (background.isNotBlank()) {
            appendLine("Background notes:")
            appendLine(background)
            appendLine()
        }

        appendLine("Recent messages (last one is the one being judged):")
        val msgs = chat?.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                appendLine("  ${m.optString("from")}: ${m.optString("text")}")
            }
        }
        val latestFrom = chat?.optString("latest_from").orEmpty()
        if (latestFrom.isNotBlank()) appendLine("The latest message is from: $latestFrom")
    }

    /**
     * Recovers the answer object from whatever the model returned.
     *
     * Returns the inner `answers` object when present, otherwise the whole parsed
     * object — a model that omits the wrapper is still usable. Returns null when
     * there is no JSON object to be found, so callers can report a clear error
     * instead of silently judging nothing.
     *
     * @param questions the question set that was asked. When supplied, answers
     *        are normalised (see [normalise]) and positional keys are resolved;
     *        without it the parsed object is returned as-is.
     */
    fun parseAnswers(raw: String, questions: JSONObject? = null): JSONObject? {
        val obj = extractJson(raw) ?: return null
        val inner = obj.optJSONObject("answers") ?: obj
        if (questions == null) return inner
        return normalise(inner, questionTypes(questions), orderedIds(questions))
    }

    /**
     * Question ids in the order [questionsBlock] presents them. Must stay in
     * step with that function — the positional fallback depends on it.
     */
    fun orderedIds(questions: JSONObject): List<String> =
        questions.keys().asSequence().sorted().toList()

    /** Question id -> answer type, for [normalise]. */
    fun questionTypes(questions: JSONObject): Map<String, String> =
        questions.keys().asSequence().associateWith { id ->
            questions.optJSONObject(id)?.optString("type").orEmpty()
        }

    /**
     * Coerces the shapes a chat model actually returns into the one the parsers
     * expect.
     *
     * The prompt asks for `{"true_intent": {"choice": "…", "confidence": …}}`,
     * but models flatten it — `{"true_intent": "request_action"}`, or
     * `{"danger_level": 4}`, or `{"answer": "…"}` instead of `{"choice": "…"}`.
     * Without this a perfectly good judgement parses to nothing and the panel
     * shows question marks, which looks like the model failed when it did not.
     *
     * Unknown ids are passed through untouched so nothing is silently dropped.
     */
    fun normalise(
        answers: JSONObject,
        types: Map<String, String>,
        order: List<String> = emptyList()
    ): JSONObject {
        val out = JSONObject()
        answers.keys().asSequence().forEach { rawKey ->
            val id = resolveId(rawKey, types, order)
            val type = types[id].orEmpty()
            val normalised = when (val value = answers.opt(rawKey)) {
                is JSONObject -> coerceObject(value, type)
                is String -> fromScalar(value, type)
                is Boolean -> JSONObject().put("noul", if (value) 1.0 else 0.0)
                is Number -> fromNumber(value.toDouble(), type)
                else -> null
            }
            when {
                normalised != null -> out.put(id, withProbabilities(normalised))
                answers.opt(rawKey) != null -> out.put(id, answers.opt(rawKey))
            }
        }
        return out
    }

    /**
     * Gives a choice answer a `probabilities` map when it has none.
     *
     * The ranking question is read back through `probabilities`, but a chat model
     * normally just names its pick. Without this every candidate scores 0% and the
     * panel shows two identical "0%" lines — seen on a real WeChat run.
     *
     * One-hot rather than a made-up spread: all we actually know is which
     * candidate was chosen, and inventing a distribution would dress a guess up
     * as a measurement.
     */
    private fun withProbabilities(obj: JSONObject): JSONObject {
        if (obj.has("probabilities")) return obj
        val choice = obj.optString("choice").trim()
        if (choice.isEmpty()) return obj
        return JSONObject(obj.toString()).put("probabilities", JSONObject().put(choice, 1.0))
    }

    /**
     * Maps a key that is a position rather than an id back onto the question it
     * stood for.
     *
     * Defensive only: the prompt no longer numbers the questions, but a model
     * that answers `{"3": …}` after all should still be understood rather than
     * silently discarded. Observed for real — see [questionsBlock].
     */
    private fun resolveId(key: String, types: Map<String, String>, order: List<String>): String {
        if (types.containsKey(key)) return key
        val n = key.trim().toIntOrNull() ?: return key
        return order.getOrNull(n - 1) ?: key
    }

    /** An object answer that used `answer` / `value` instead of `choice` / `score`. */
    private fun coerceObject(obj: JSONObject, type: String): JSONObject {
        if (obj.has("choice") || obj.has("score") || obj.has("noul")) return obj
        val loose = obj.opt("answer")?.takeIf { it != JSONObject.NULL }
            ?: obj.opt("value")?.takeIf { it != JSONObject.NULL }
            ?: return obj
        val confidence = obj.optDouble("confidence", 0.5)
        return when (loose) {
            is String -> JSONObject().put("choice", loose.trim()).put("confidence", confidence)
            is Boolean -> JSONObject().put("noul", if (loose) 1.0 else 0.0)
            is Number -> if (type == "score") {
                JSONObject().put("score", loose.toDouble()).put("confidence", confidence)
            } else {
                JSONObject().put("noul", loose.toDouble().coerceIn(0.0, 1.0))
            }
            else -> obj
        }
    }

    private fun fromScalar(text: String, type: String): JSONObject {
        val trimmed = text.trim()
        val asNumber = trimmed.toDoubleOrNull()
        return when {
            asNumber != null -> fromNumber(asNumber, type)
            trimmed.equals("true", ignoreCase = true) -> JSONObject().put("noul", 1.0)
            trimmed.equals("false", ignoreCase = true) -> JSONObject().put("noul", 0.0)
            // A bare word is a choice; confidence is unknown, so claim nothing.
            else -> JSONObject().put("choice", trimmed).put("confidence", 0.5)
        }
    }

    private fun fromNumber(n: Double, type: String): JSONObject = when (type) {
        "score" -> JSONObject().put("score", n)
        // A 0..1 weight for "noul"; anything larger is a score on a bigger scale.
        "noul" -> JSONObject().put("noul", n.coerceIn(0.0, 1.0))
        else -> if (n in 0.0..1.0) JSONObject().put("noul", n) else JSONObject().put("score", n)
    }

    /**
     * The ids present in [answers], for diagnostic logging. Key names only —
     * never values, so nothing from the conversation ends up in logcat.
     */
    fun describeShape(answers: JSONObject): String =
        answers.keys().asSequence().sorted().joinToString(",")

    /**
     * Finds the first complete JSON object in [raw] and parses it.
     *
     * Real model output looks like ```json\n{…}\n```, or "好的，这是结果：{…}",
     * or the object followed by a summary paragraph. So we scan for the first
     * `{` and its matching `}` — counting braces while ignoring any that appear
     * inside string literals, and honouring backslash escapes. Naively taking
     * the last `}` in the text would cut into a string like `{"a":"}"}`.
     */
    fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                c == '{' && !inString -> depth++
                c == '}' && !inString -> {
                    depth--
                    if (depth == 0) {
                        val candidate = raw.substring(start, i + 1)
                        return try {
                            JSONObject(candidate)
                        } catch (_: Exception) {
                            // Braces balanced but not valid JSON here; try the
                            // next object rather than giving up.
                            extractJson(raw.substring(i + 1))
                        }
                    }
                }
            }
        }
        return null
    }
}
