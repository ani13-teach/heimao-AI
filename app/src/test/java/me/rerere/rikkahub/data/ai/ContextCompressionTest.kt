package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ClaudeMemoryCacheBlock
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.generateTextWithModelTransport
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant as json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class ContextCompressionTest {
    private fun history() = listOf(UIMessage.user("old question " + "中".repeat(400)), UIMessage.assistant("old answer"))
    private fun prompt(messages: List<UIMessage>) = LogicalPrompt.fromMessages(messages)
    private fun target(prompt: LogicalPrompt) = estimatePromptTokens(
        prompt.copy(history = listOf(contextCompressionMessage("")))
    ) + 40

    private suspend fun compact(prompt: LogicalPrompt, messages: List<UIMessage>,
        summarize: suspend (String, Int) -> String = { _, _ -> "保留的事实" },
    ) = compactContextIfNeeded(prompt, messages, estimatePromptTokens(prompt), target(prompt), summarize = summarize)

    @Test
    fun `bottom user injection cannot move current turn into compression history`() = runBlocking {
        val currentUser = UIMessage.user("current question")
        val firstTool = UIMessage.assistant("first tool result")
        val secondTool = UIMessage.assistant("second tool result")
        val originals = history() + listOf(currentUser, firstTool, secondTool)
        val injection = PromptInjection.ModeInjection(
            name = "current turn reminder",
            content = "injected reminder",
            position = InjectionPosition.BOTTOM_OF_CHAT,
            role = MessageRole.USER,
        )
        val transformed = transformMessages(
            messages = originals,
            assistant = Assistant(modeInjectionIds = setOf(injection.id)),
            modeInjections = listOf(injection),
        )
        val currentIndex = transformed.latestRealUserIndex()
        assertEquals(currentUser.id, transformed[currentIndex].id)
        assertTrue(transformed.drop(currentIndex + 1).any { it.role == MessageRole.USER && it.isSynthetic })
        val request = LogicalPrompt(
            history = transformed.take(currentIndex),
            latestUser = transformed[currentIndex],
            currentToolLoop = transformed.drop(currentIndex + 1),
        )
        val result = compact(request, originals) { source, _ ->
            assertFalse(source.contains("current question"))
            assertFalse(source.contains("first tool result"))
            assertFalse(source.contains("second tool result"))
            "保留的事实"
        }
        assertEquals(currentUser, result.prompt.latestUser)
        assertEquals(request.currentToolLoop, result.prompt.currentToolLoop)
        assertTrue(result.messages[1].contextCompression != null)
        assertTrue(result.messages.drop(2).all { it.contextCompression == null })
        val reused = result.messages.withStoredContextCompression()
        assertEquals(listOf(currentUser, firstTool, secondTool), reused.drop(1))
    }

    @Test
    fun `synthetic users alone do not define a real current turn`() {
        assertEquals(-1, listOf(UIMessage.user("reminder").copy(isSynthetic = true)).latestRealUserIndex())
    }

    @Test
    fun `selected compression model is used across providers and missing selections fail`() {
        val chat = Model(modelId = "chat")
        val compression = Model(modelId = "summary")
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(name = "chat provider", models = listOf(chat)),
                ProviderSetting.OpenAI(name = "summary provider", models = listOf(compression)),
            ),
            compressModelId = compression.id,
        )
        assertSame(compression, settings.compressionModelFor(chat))
        assertSame(chat, settings.copy(compressModelId = DEFAULT_AUTO_MODEL_ID).compressionModelFor(chat))
        val error = runCatching { settings.copy(providers = emptyList()).compressionModelFor(chat) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("指定的压缩模型不可用"))
    }

    @Test
    fun `non idempotent output transformation does not invalidate historical summary`() = runBlocking {
        val originals = history() + UIMessage.user("now")
        val saved = compact(prompt(originals), originals).messages
        val response = saved + UIMessage.assistant("a")
        val transformed = response.transformLatestResponse { latest ->
            latest.map { it.copy(parts = listOf(UIMessagePart.Text(it.toText().replace("a", "aa")))) }
        }
        assertEquals("aa", transformed.last().toText())
        assertEquals(saved, transformed.dropLast(1))
        assertTrue(transformed.withStoredContextCompression().first().isSynthetic)
        assertTrue(saved.all { it.parts == originals[saved.indexOf(it)].parts })
    }

    @Test
    fun `compacted request keeps original response history in streaming and batch transports`() = runBlocking {
        val original = history() + UIMessage.user("now")
        val compacted = compact(prompt(original), original)
        val provider = RecordingProvider()
        for (stream in listOf(true, false)) {
            val result = provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = compacted.prompt.toMessages(),
                params = TextGenerationParams(model = Model(modelId = "chat", streamOutput = stream), logicalPrompt = compacted.prompt),
                responseMessages = compacted.messages,
            )
            assertEquals(compacted.messages, result.dropLast(1))
            assertEquals("response", result.last().toText())
            assertTrue(result.withStoredContextCompression().first().isSynthetic)
            assertEquals(compacted.prompt.toMessages().map { it.parts }, provider.received.map { it.parts })
        }
    }

    private class RecordingProvider : Provider<ProviderSetting.OpenAI> {
        var received = emptyList<UIMessage>()
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            received = messages
            return TextGenerationResult(id = "batch", model = params.model.modelId, message = UIMessage.assistant("response"))
        }
        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> {
            received = messages
            return flowOf(StreamChunk.TextStart("text"), StreamChunk.TextDelta("text", "response"), StreamChunk.TextEnd("text"), StreamChunk.Finish())
        }
    }

    @Test
    fun `ASCII segments and UTF8 bytes give deterministic conservative estimates`() {
        assertEquals(0, estimateTextTokens(""))
        assertEquals(1, estimateTextTokens("abcd"))
        assertEquals(2, estimateTextTokens("abcde"))
        assertEquals(5, estimateTextTokens("ab中cd"))
        assertEquals(4, estimateTextTokens("😀"))
        assertEquals(2, estimateTextTokens("é"))
        assertEquals(11, estimateTextTokens("中😀😀"))
    }

    @Test
    fun `disabled compression and below threshold return identical inputs without summarizing`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val p = prompt(messages)
        val disabled = compactContextIfNeeded(p, messages, 0, 1) { _, _ -> error("called") }
        val below = compactContextIfNeeded(p, messages, estimatePromptTokens(p) + 1, target(p)) { _, _ -> error("called") }
        listOf(disabled, below).forEach {
            assertFalse(it.compressed)
            assertSame(p, it.prompt)
            assertSame(messages, it.messages)
        }
    }

    @Test
    fun `exact threshold and over threshold both compress and preserve all originals`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val p = prompt(messages)
        for (threshold in listOf(estimatePromptTokens(p), estimatePromptTokens(p) - 1)) {
            var calls = 0
            val result = compactContextIfNeeded(p, messages, threshold, target(p)) { _, budget ->
                calls++
                assertTrue(budget > 0)
                "facts"
            }
            assertTrue(result.compressed)
            assertEquals(1, calls)
            assertTrue(estimatePromptTokens(result.prompt) <= target(p))
            assertEquals(messages.size, result.messages.size)
            messages.zip(result.messages).forEach { (before, after) ->
                assertEquals(before.id, after.id)
                assertEquals(before.parts, after.parts)
                assertEquals(before.role, after.role)
            }
            assertTrue(messages.all { it.contextCompression == null })
            assertNull(result.messages.last().contextCompression)
            assertTrue(result.messages[result.messages.lastIndex - 1].contextCompression != null)
            assertEquals(listOf("facts"), result.prompt.history.map { it.toText().substringAfter('\n').substringBeforeLast('\n') })
            assertTrue(result.prompt.history.single().isSynthetic)
            assertEquals(MessageRole.USER, result.prompt.history.single().role)
        }
    }

    @Test
    fun `full budget includes systems schemas memory temporary sources and whole current tool chain`() = runBlocking {
        val messages = history() + UIMessage.user("current") + UIMessage.assistant("tool step")
        val tool = Tool("lookup", "find an item", parameters = {
            InputSchema.Obj(buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }, listOf("query"))
        }, execute = { emptyList() })
        val loop = listOf(UIMessage.assistant("tool step"), UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("t1", "lookup", "{\"query\":\"x\"}", listOf(
                UIMessagePart.Text("result"), UIMessagePart.Tool("t2", "nested", "{}", listOf(UIMessagePart.Text("nested result")))
            )))
        ))
        val p = prompt(messages).copy(
            system = listOf(UIMessage.system("system rules")), tools = listOf(tool),
            memorySnapshot = listOf(UIMessage.user("memory snapshot")), memoryDelta = listOf(UIMessage.user("memory patch")),
            transientContext = listOf(UIMessage.user("temporary historical source")), currentToolLoop = loop,
        )
        val result = compact(p, messages) { source, budget ->
            assertFalse(source.contains("system rules"))
            assertFalse(source.contains("memory snapshot"))
            assertFalse(source.contains("temporary historical source"))
            assertFalse(source.contains("nested result"))
            assertEquals(target(p) - estimatePromptTokens(p.copy(history = listOf(contextCompressionMessage("")))), budget)
            "facts"
        }
        assertEquals(p.copy(history = result.prompt.history), result.prompt)
        assertTrue(estimatePromptTokens(result.prompt) <= target(p))
        val reserved = p.copy(history = emptyList())
        assertTrue(estimatePromptTokens(reserved) > estimatePromptTokens(reserved.copy(tools = emptyList())))
        assertTrue(estimatePromptTokens(reserved) > estimatePromptTokens(reserved.copy(currentToolLoop = loop.take(1))))
        assertTrue(estimatePromptTokens(reserved) > estimatePromptTokens(reserved.copy(latestUser = null)))
    }

    @Test
    fun `memory representations are counted by maximum rather than addition`() {
        val ordinary = LogicalPrompt(memorySnapshot = listOf(UIMessage.user("ordinary snapshot")),
            memoryDelta = listOf(UIMessage.user("ordinary delta")))
        val claude = ordinary.copy(memorySnapshot = emptyList(),
            claudeMemoryCacheBlocks = listOf(ClaudeMemoryCacheBlock("中".repeat(100))),
            claudeMemoryDelta = listOf(UIMessage.user("patch")))
        val both = claude.copy(memorySnapshot = ordinary.memorySnapshot)
        assertEquals(maxOf(estimatePromptTokens(ordinary), estimatePromptTokens(claude)), estimatePromptTokens(both))
        val largeOrdinary = ordinary.copy(memorySnapshot = listOf(UIMessage.user("中".repeat(300))))
        assertEquals(estimatePromptTokens(largeOrdinary), estimatePromptTokens(both.copy(memorySnapshot = largeOrdinary.memorySnapshot)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `all part types tools and media reserves are included and local attachment payloads ignored`() {
        fun count(part: UIMessagePart) = estimatePromptTokens(LogicalPrompt(history = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))
        )))
        val blank = count(UIMessagePart.Text(""))
        assertEquals(blank + 1024, count(UIMessagePart.Image("data:image/png;base64," + "x".repeat(100_000))))
        assertEquals(blank + 4096, count(UIMessagePart.Video("file:///huge.mp4")))
        assertEquals(blank + 2048, count(UIMessagePart.Audio("content://audio")))
        assertEquals(count(UIMessagePart.Document("data:huge", "doc.txt")), count(UIMessagePart.Document("file:///local", "doc.txt")))
        assertTrue(count(UIMessagePart.Text("expanded document " + "中".repeat(1000))) > blank + 3000)
        assertTrue(count(UIMessagePart.Reasoning("中".repeat(20))) >= blank + 60)
        val server = UIMessagePart.ServerTool("id", "search", JsonPrimitive("input"), JsonPrimitive("中".repeat(50)), ServerToolStatus.COMPLETED)
        assertTrue(count(server) >= blank + 150)
        assertTrue(count(UIMessagePart.ToolCall("id", "name", "中".repeat(20))) >= blank + 60)
        assertTrue(count(UIMessagePart.ToolResult("id", "name", JsonPrimitive("中".repeat(20)), JsonPrimitive("args"))) >= blank + 60)
        assertTrue(count(UIMessagePart.Search) > blank)
    }

    @Test
    fun `marker survives JSON storage and preserves system current user and tool continuation`() = runBlocking {
        val system = UIMessage.system("rules")
        val current = UIMessage.user("now")
        val loop = UIMessage.assistant("current tool result")
        val messages = listOf(system) + history() + current + loop
        val result = compact(prompt(messages), messages)
        val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(result.messages))
        val reused = restored.withStoredContextCompression()
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT), reused.map { it.role })
        assertEquals(system, reused.first())
        assertEquals(listOf(current, loop), reused.takeLast(2))
        assertTrue(reused[1].isSynthetic)
        assertEquals(result.prompt.history.single().toText(), reused[1].toText())
        val bookkeeping = restored.map { it.copy(finishedAt = it.createdAt, translation = "translated",
            usage = TokenUsage(promptTokens = Int.MAX_VALUE, completionTokens = Int.MAX_VALUE)) }
        assertEquals(estimatePromptTokens(prompt(restored)), estimatePromptTokens(prompt(bookkeeping)))
        assertEquals(reused.map { it.parts }, bookkeeping.withStoredContextCompression().map { it.parts })
        assertTrue(messages.all { it.contextCompression == null })
    }

    @Test
    fun `edit deletion changed branch role or ID invalidate stored summary`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val stored = compact(prompt(messages), messages).messages
        val variants = listOf(
            stored.mapIndexed { index, message -> if (index == 0) message.copy(parts = listOf(UIMessagePart.Text("edited"))) else message },
            stored.drop(1),
            stored.mapIndexed { index, message -> if (index == 0) message.copy(id = Uuid.random()) else message },
            stored.mapIndexed { index, message -> if (index == 0) message.copy(role = MessageRole.ASSISTANT) else message },
        )
        variants.forEach { assertSame(it, it.withStoredContextCompression()) }
    }

    @Test
    fun `appending users keeps old summary usable and newest valid marker wins`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val first = compact(prompt(messages), messages).messages
        val appended = first + UIMessage.assistant("new answer " + "中".repeat(100)) + UIMessage.user("new question")
        val reused = appended.withStoredContextCompression()
        assertEquals(appended.takeLast(3), reused.takeLast(3))
        assertEquals(4, reused.size)
        val next = compact(prompt(appended), appended) { _, _ -> "updated facts" }
        assertTrue(next.messages.withStoredContextCompression().first().toText().contains("updated facts"))
        assertEquals(next.messages.last(), next.messages.withStoredContextCompression().last())
        val invalidNewest = next.messages.mapIndexed { index, message ->
            if (index == next.messages.lastIndex - 1) message.copy(parts = listOf(UIMessagePart.Text("edited new answer"))) else message
        }
        assertTrue(invalidNewest.withStoredContextCompression().first().toText().contains("保留的事实"))
    }

    @Test
    fun `oversized summaries merge again and fail after four rounds without mutating originals`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val p = prompt(messages)
        var calls = 0
        val result = compact(p, messages) { _, _ -> if (++calls == 1) "中".repeat(100) else "facts" }
        assertEquals(2, calls)
        assertTrue(estimatePromptTokens(result.prompt) <= target(p))
        calls = 0
        val failure = runCatching { compact(p, messages) { _, _ -> calls++; "中".repeat(100) } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("4 轮"))
        assertEquals(4, calls)
        assertTrue(messages.all { it.contextCompression == null })
    }

    @Test
    fun `blank summaries fail and exceptions or cancellation propagate without markers`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val p = prompt(messages)
        val empty = runCatching { compact(p, messages) { _, _ -> "  \n" } }.exceptionOrNull()
        assertTrue(empty is IllegalStateException)
        assertTrue(empty!!.message!!.contains("空摘要"))
        for (expected in listOf(IOException("provider failure"), CancellationException("cancel"))) {
            val actual = runCatching { compact(p, messages) { _, _ -> throw expected } }.exceptionOrNull()
            assertSame(expected, actual)
        }
        // A failure in a later block must not leave a marker from the earlier successful block.
        var calls = 0
        val failure = runCatching {
            compactContextIfNeeded(p, messages, estimatePromptTokens(p), target(p), 60) { _, _ ->
                if (++calls == 2) throw IOException("second block")
                "facts"
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(2, calls)
        assertTrue(messages.all { it.contextCompression == null })
    }

    @Test
    fun `long Chinese emoji source splits without losing text and excludes hidden reasoning`() = runBlocking {
        val text = "开始" + "中文😀🧪abc\n".repeat(600) + "结束"
        val h = UIMessage.assistant(text).copy(parts = listOf(UIMessagePart.Text(text), UIMessagePart.Reasoning("hidden reasoning")))
        val messages = listOf(h, UIMessage.user("now"))
        val p = prompt(messages)
        val chunks = mutableListOf<String>()
        val initialChunks = mutableListOf<String>()
        val expectedSource = "[ASSISTANT]\n$text"
        var capturedCharacters = 0
        val result = compactContextIfNeeded(p, messages, estimatePromptTokens(p), target(p), 80) { source, budget ->
            assertTrue(estimateTextTokens(source) <= 80)
            assertTrue(budget >= 1)
            chunks += source
            if (capturedCharacters < expectedSource.length) {
                initialChunks += source
                capturedCharacters += source.length
            }
            "x"
        }
        assertTrue(result.compressed)
        assertEquals(expectedSource, initialChunks.joinToString(""))
        assertFalse(chunks.joinToString("").contains("hidden reasoning"))
        chunks.forEach { chunk ->
            assertFalse(chunk.firstOrNull()?.isLowSurrogate() == true)
            assertFalse(chunk.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test
    fun `historical tools retain full text expanded documents and filenames`() = runBlocking {
        val body = "expanded body " + "x".repeat(5000)
        val h = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("id", "lookup", "arguments", listOf(UIMessagePart.Text(body))),
            UIMessagePart.Document("data:secret-base64", "report.pdf"),
            UIMessagePart.Image("data:image/png;base64,secret-base64"),
        ))
        val messages = listOf(h, UIMessage.user("now"))
        compact(prompt(messages), messages) { source, _ ->
            assertTrue(source.contains(body))
            assertTrue(source.contains("arguments"))
            assertTrue(source.contains("report.pdf"))
            assertFalse(source.contains("secret-base64"))
            "facts"
        }
        Unit
    }

    @Test
    fun `small target and missing histories fail before calling summarizer`() = runBlocking {
        val messages = history() + UIMessage.user("now")
        val p = prompt(messages)
        val failures = listOf(
            runCatching { compactContextIfNeeded(p, messages, estimatePromptTokens(p), 1) { _, _ -> error("called") } }.exceptionOrNull(),
            runCatching { compactContextIfNeeded(p.copy(history = emptyList()), messages, 2, 1) { _, _ -> error("called") } }.exceptionOrNull(),
            runCatching { compactContextIfNeeded(p, listOf(messages.last()), estimatePromptTokens(p), target(p)) { _, _ -> error("called") } }.exceptionOrNull(),
        )
        failures.forEach { assertTrue(it is IllegalStateException) }
        assertTrue(failures.first()!!.message!!.contains("必留内容"))
    }

    @Test
    fun `settings are validated even if disabled`() = runBlocking {
        val failure = runCatching {
            compactContextIfNeeded(LogicalPrompt(), emptyList(), 0, 0) { _, _ -> error("called") }
        }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("压缩目标"))
    }

    @Test
    fun `SYSTEM inside historical fallback is retained and charged rather than summarized`() = runBlocking {
        val messages = history() + UIMessage.system("historical system rules") + UIMessage.user("now")
        val p = prompt(messages)
        val result = compact(p, messages) { source, _ ->
            assertFalse(source.contains("historical system rules"))
            "facts"
        }
        assertEquals(listOf(messages[messages.lastIndex - 1]), result.prompt.system)
        assertTrue(estimatePromptTokens(result.prompt) <= target(p))
        assertEquals(messages[messages.lastIndex - 1].parts, result.messages.withStoredContextCompression().first().parts)
    }

    @Test
    fun `without a latest user all original messages are history and marker is reusable`() = runBlocking {
        val messages = listOf(UIMessage.assistant("中".repeat(300)))
        val result = compact(prompt(messages), messages)
        assertTrue(result.messages.last().contextCompression != null)
        assertEquals(1, result.messages.withStoredContextCompression().size)
        assertEquals(MessageRole.USER, result.messages.withStoredContextCompression().single().role)
    }

    @Test
    fun `engine has no legacy message count limit and sends entire supplied history`() = runBlocking {
        val messages = (0..100).map { UIMessage.assistant("history entry $it") } + UIMessage.user("now")
        compact(prompt(messages), messages) { source, _ ->
            assertTrue(source.contains("history entry 0"))
            assertTrue(source.contains("history entry 100"))
            "facts"
        }
        Unit
    }
}
