package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.testing.withFakeGateway
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 和 Claude 那边一样的坑：网关报错时流式请求必须以异常结束，
 * 不能拿 null 关流（上层会当成空回复，界面既不显示回答也不显示错误）。
 */
class OpenAIStreamFailureTest {
    private val params = TextGenerationParams(model = Model(modelId = "gpt-test"))
    private val html = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"

    @Test
    fun `chat completions html error page becomes an http failure`() {
        withFakeGateway(502, "text/html", html) { baseUrl ->
            val error = assertThrows(HttpException::class.java) {
                runBlocking { chatCompletions(baseUrl).toList() }
            }

            assertEquals(502, error.status)
            assertTrue(
                "错误提示里要带上网关返回的内容，实际是 ${error.message}",
                error.message!!.contains("<title>502 Bad Gateway</title>"),
            )
        }
    }

    @Test
    fun `chat completions empty error body becomes an http failure`() {
        withFakeGateway(503, "text/plain", "") { baseUrl ->
            val error = assertThrows(HttpException::class.java) {
                runBlocking { chatCompletions(baseUrl).toList() }
            }

            assertEquals(503, error.status)
            assertNotNull(error.message)
        }
    }

    @Test
    fun `response api html error page becomes an http failure`() {
        withFakeGateway(502, "text/html", html) { baseUrl ->
            val error = assertThrows(HttpException::class.java) {
                runBlocking { responseApi(baseUrl).toList() }
            }

            assertEquals(502, error.status)
            assertTrue(
                "错误提示里要带上网关返回的内容，实际是 ${error.message}",
                error.message!!.contains("<title>502 Bad Gateway</title>"),
            )
        }
    }

    private suspend fun chatCompletions(baseUrl: String) =
        ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()).streamText(
            providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl),
            messages = listOf(UIMessage.user("hi")),
            params = params,
        )

    private suspend fun responseApi(baseUrl: String) =
        ResponseAPI(OkHttpClient(), KeyRoulette.default()).streamText(
            providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl),
            messages = listOf(UIMessage.user("hi")),
            params = params,
        )
}
