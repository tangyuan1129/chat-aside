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
     */
    fun questionsBlock(questions: JSONObject): String = buildString {
        appendLine("Questions:")
        val ids = questions.keys().asSequence().toList()
        ids.forEachIndexed { i, id ->
            val q = questions.optJSONObject(id) ?: return@forEachIndexed
            val type = q.optString("type")
            appendLine("${i + 1}. $id  (type: $type)")
            appendLine("   question: ${q.optString("instructions").trim()}")

            when (type) {
                "choice" -> {
                    appendLine("   allowed keys:")
                    val criteria = q.optJSONObject("criteria")
                    criteria?.keys()?.asSequence()?.forEach { key ->
                        appendLine("     - $key: ${criteria.optString(key).trim()}")
                    }
                }
                "score" -> {
                    appendLine("   scale:")
                    val levels = q.optJSONArray("criteria") ?: JSONArray()
                    for (n in 0 until levels.length()) {
                        appendLine("     ${n + 1}: ${levels.optString(n).trim()}")
                    }
                }
                "noul" -> {
                    val criteria = q.optJSONObject("criteria")
                    if (criteria != null) {
                        appendLine("   true  means: ${criteria.optString("true").trim()}")
                        appendLine("   false means: ${criteria.optString("false").trim()}")
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
     */
    fun parseAnswers(raw: String): JSONObject? {
        val obj = extractJson(raw) ?: return null
        return obj.optJSONObject("answers") ?: obj
    }

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
