package io.github.tangyuan1129.chataside.jev

import org.json.JSONObject

/**
 * Detects which models an API endpoint actually offers, so the user picks from a
 * real list instead of typing a model id from memory.
 *
 * The two hard parts — working out where the model list lives, and reading
 * whichever shape the provider returns — are pure functions and are unit-tested
 * in `ModelCatalogTest`. Detection is best-effort by design: every adapted
 * provider is OpenAI-compatible *enough*, but not all of them expose `/models`,
 * so a failure has to leave the user able to type the name by hand.
 */
object ModelCatalog {

    /**
     * Where to ask a provider for its model list, given whatever the user typed
     * into the Base URL box.
     *
     * Rules, in order:
     * - already ends in `/models` → use as-is
     * - contains `/v1` → everything up to and including the **last** `/v1`, then
     *   `/models`. Covers `/v1`, `/api/v1`, `/compatible-mode/v1`, and the
     *   endpoint-suffixed shapes the judge route uses (`/v1/systemone`,
     *   `/api/v1/alpha/decisions`).
     * - no version segment → the host root plus `/v1/models`, which is the
     *   convention every OpenAI-compatible gateway follows.
     *
     * Returns null when there is nothing usable to work with — either an empty
     * box, or an address missing its scheme. Both are better reported as "fix
     * your Base URL" than turned into a malformed URL that fails later with a
     * message about protocols.
     */
    fun modelsUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains("://")) return null
        val url = trimmed.trimEnd('/')
        if (url.endsWith("/models")) return url

        val marker = "/v1"
        val idx = url.lastIndexOf(marker)
        if (idx >= 0) return url.substring(0, idx + marker.length) + "/models"

        val schemeEnd = url.indexOf("://")
        val hostEnd = url.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return "$url/v1/models"
        return url.substring(0, hostEnd) + "/v1/models"
    }

    /**
     * Model ids out of a `/models` response, sorted and de-duplicated.
     *
     * Handles the three shapes seen in the wild: the OpenAI list of objects
     * (`{"data":[{"id":…}]}`), a bare list of strings (`{"data":["…"]}`), and the
     * Ollama-style `{"models":[{"name":…}]}`.
     */
    fun parseModels(json: JSONObject): List<String> {
        val out = LinkedHashSet<String>()

        json.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val id = if (obj != null) obj.optString("id") else arr.optString(i)
                val clean = id.trim()
                if (clean.isNotEmpty()) out.add(clean)
            }
        }

        if (out.isEmpty()) {
            json.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val clean = obj.optString("name").ifBlank { obj.optString("id") }.trim()
                    if (clean.isNotEmpty()) out.add(clean)
                }
            }
        }

        return out.sorted()
    }

    /**
     * Model ids available at [baseUrl]. Throws [ApiException] with a message the
     * settings page can show verbatim; the caller keeps the manual model box
     * usable either way.
     */
    fun fetch(baseUrl: String, key: String, route: String = Route.MODELS): List<String> {
        val url = modelsUrl(baseUrl)
            ?: throw ApiException(route, null, "Base URL 要填完整，需要以 http:// 或 https:// 开头")
        val json = HttpJson.get(url, key, route, HttpJson.headersFor(url))
        val models = parseModels(json)
        if (models.isEmpty()) {
            throw ApiException(route, null, "该地址返回了响应，但里面没有模型列表")
        }
        return models
    }
}
