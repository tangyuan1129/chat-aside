package io.github.tangyuan1129.chataside.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for model detection.
 *
 * The URL derivation is the part most likely to be wrong in a way nobody
 * notices until a user's provider silently 404s, so it is pinned against the
 * exact base-URL shapes the settings presets produce.
 */
class ModelCatalogTest {

    // ------------------------------------------------------------ modelsUrl

    @Test
    fun `blank input yields no url`() {
        assertNull(ModelCatalog.modelsUrl(""))
        assertNull(ModelCatalog.modelsUrl("   "))
    }

    @Test
    fun `an existing models url is left alone`() {
        assertEquals(
            "https://example.com/v1/models",
            ModelCatalog.modelsUrl("https://example.com/v1/models")
        )
    }

    @Test
    fun `a plain v1 base gets models appended`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com/v1")
        )
    }

    @Test
    fun `trailing slashes do not produce a doubled slash`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com/v1///")
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("  https://api.deepseek.com/v1  ")
        )
    }

    @Test
    fun `a nested api prefix is preserved`() {
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            ModelCatalog.modelsUrl("https://openrouter.ai/api/v1")
        )
    }

    @Test
    fun `an endpoint-suffixed url is cut back to the api root`() {
        // This is the shape the judge route posts to.
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            ModelCatalog.modelsUrl("https://openrouter.ai/api/v1/alpha/decisions")
        )
        assertEquals(
            "https://api.typesafe.example/v1/models",
            ModelCatalog.modelsUrl("https://api.typesafe.example/v1/systemone")
        )
    }

    @Test
    fun `a compatible-mode base is preserved`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            ModelCatalog.modelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
    }

    @Test
    fun `a url with no version segment falls back to the host root`() {
        assertEquals(
            "https://example.com/v1/models",
            ModelCatalog.modelsUrl("https://example.com")
        )
        assertEquals(
            "https://example.com/v1/models",
            ModelCatalog.modelsUrl("https://example.com/chat/completions")
        )
    }

    @Test
    fun `something that is not a url at all yields nothing`() {
        assertNull(ModelCatalog.modelsUrl("not-a-url"))
        assertNull(ModelCatalog.modelsUrl("api.deepseek.com/v1"))
    }

    // ---------------------------------------------------------- parseModels

    @Test
    fun `the openai object shape is parsed`() {
        val json = JSONObject(
            """{"data":[{"id":"gpt-4o"},{"id":"deepseek-chat"}]}"""
        )
        assertEquals(listOf("deepseek-chat", "gpt-4o"), ModelCatalog.parseModels(json))
    }

    @Test
    fun `a bare list of strings is parsed`() {
        val json = JSONObject("""{"data":["a-model","b-model"]}""")
        assertEquals(listOf("a-model", "b-model"), ModelCatalog.parseModels(json))
    }

    @Test
    fun `the ollama shape is parsed`() {
        val json = JSONObject("""{"models":[{"name":"qwen2.5:7b"},{"name":"llama3:8b"}]}""")
        assertEquals(listOf("llama3:8b", "qwen2.5:7b"), ModelCatalog.parseModels(json))
    }

    @Test
    fun `results are sorted and de-duplicated`() {
        val json = JSONObject(
            """{"data":[{"id":"z"},{"id":"a"},{"id":"z"},{"id":"m"}]}"""
        )
        assertEquals(listOf("a", "m", "z"), ModelCatalog.parseModels(json))
    }

    @Test
    fun `blank identifiers are dropped`() {
        val json = JSONObject("""{"data":[{"id":""},{"id":"  "},{"id":"ok"}]}""")
        assertEquals(listOf("ok"), ModelCatalog.parseModels(json))
    }

    @Test
    fun `an empty response yields an empty list rather than throwing`() {
        assertTrue(ModelCatalog.parseModels(JSONObject("{}")).isEmpty())
        assertTrue(ModelCatalog.parseModels(JSONObject("""{"data":[]}""")).isEmpty())
    }

    @Test
    fun `an unexpected body does not throw`() {
        // Some gateways answer with an object where entries are expected.
        assertTrue(ModelCatalog.parseModels(JSONObject("""{"data":{"id":"x"}}""")).isEmpty())
        assertTrue(ModelCatalog.parseModels(JSONObject("""{"error":"nope"}""")).isEmpty())
    }

    @Test
    fun `the ollama shape is used when data is absent`() {
        val json = JSONObject("""{"models":[{"id":"fallback-id"}]}""")
        assertEquals(listOf("fallback-id"), ModelCatalog.parseModels(json))
    }
}
