package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GooglePromptCacheRequestTest {
    @Test
    fun `Gemini keeps normal ordered contents and sends no explicit cache controls`() {
        val provider = GoogleProvider(OkHttpClient())
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("system")),
            history = listOf(UIMessage.user("history")),
            transientContext = listOf(UIMessage.user("retrieval")),
            memory = listOf(UIMessage.system("memory")),
            latestUser = UIMessage.user("latest"),
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test"),
            promptCacheEnabled = true,
            promptCacheKey = "must-not-be-sent-to-gemini",
            logicalPrompt = prompt,
        )
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        val body = method.invoke(provider, prompt.toMessages(), params) as JsonObject

        assertFalse(body.containsKey("prompt_cache_key"))
        assertFalse(body.containsKey("cache_control"))
        assertFalse(body.toString().contains("cachedContent"))
        assertFalse(body.toString().contains("cache_control"))
        assertEquals("system", body["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)

        val texts = body["contents"]!!.jsonArray.flatMap { content ->
            content.jsonObject["parts"]!!.jsonArray.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content
            }
        }
        assertEquals(listOf("memory", "history", "retrieval", "latest"), texts)
    }
}
