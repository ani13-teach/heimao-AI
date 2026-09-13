package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.testing.withFakeGateway
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关返回 HTML 错误页（不是 JSON）时，流式请求也必须抛出带状态码的错误。
 *
 * 之前这条路径会以「没有异常」的方式结束：上层拿不到任何报错，把它当成空回复，
 * 界面既不显示回答也不显示错误，只会白白重试到超时。
 */
class ClaudeStreamFailureTest {
    private val provider = ClaudeProvider(OkHttpClient())
    private val params = TextGenerationParams(model = Model(modelId = "claude-test"))

    @Test
    fun `html error page from the gateway becomes an http failure`() {
        val html = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"

        withFakeGateway(502, "text/html", html) { baseUrl ->
            val error = assertThrows(HttpException::class.java) {
                runBlocking { stream(baseUrl).toList() }
            }

            assertEquals(502, error.status)
            assertNotNull(error.message)
            assertTrue(
                "错误提示里要带上网关返回的内容，实际是 ${error.message}",
                error.message!!.contains("<title>502 Bad Gateway</title>"),
            )
        }
    }

    @Test
    fun `json error body keeps the gateway message and status`() {
        val json =
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""

        withFakeGateway(529, "application/json", json) { baseUrl ->
            val error = assertThrows(HttpException::class.java) {
                runBlocking { stream(baseUrl).toList() }
            }

            assertEquals(529, error.status)
            assertEquals("Overloaded", error.message)
        }
    }

    private suspend fun stream(baseUrl: String) = provider.streamText(
        providerSetting = ProviderSetting.Claude(baseUrl = baseUrl),
        messages = listOf(UIMessage.user("hi")),
        params = params,
    )
}
