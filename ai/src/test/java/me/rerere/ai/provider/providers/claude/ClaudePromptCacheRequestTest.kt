package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudeMemoryCacheBlock
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ClaudePromptCacheRequestTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun buildRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(provider, providerSetting, messages, params, stream) as JsonObject
    }

    @Test
    fun `diagnostics report applied boundaries without treating them as hits`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("system")),
            memorySnapshot = listOf(UIMessage.system("memory")),
            history = listOf(UIMessage.user("old"), UIMessage.assistant("answer")),
            latestUser = UIMessage.user("latest"),
        )
        var snapshot: me.rerere.ai.provider.PromptCacheSnapshot? = null
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test"), logicalPrompt = prompt,
            onPromptCacheSnapshot = { snapshot = it },
        )
        buildRequest(ProviderSetting.Claude(promptCaching = true), prompt.toMessages(), params)
        assertEquals(
            listOf(me.rerere.ai.provider.CacheBoundary.SYSTEM_END, me.rerere.ai.provider.CacheBoundary.MEMORY_END, me.rerere.ai.provider.CacheBoundary.HISTORY_END),
            snapshot!!.appliedBoundaries,
        )
        assertFalse(snapshot!!.cacheKeySent)
        buildRequest(ProviderSetting.Claude(promptCaching = false), prompt.toMessages(), params)
        assertTrue(snapshot!!.appliedBoundaries.isEmpty())
        buildRequest(ProviderSetting.Claude(promptCaching = true), prompt.toMessages(), params.copy(
            customBody = listOf(me.rerere.ai.provider.CustomBody("messages", JsonArray(emptyList()))),
        ))
        assertTrue(snapshot!!.layoutSkipped)
        assertTrue(snapshot!!.appliedBoundaries.isEmpty())
    }

    private fun dummyTool(): Tool {
        return Tool(
            name = "dummy_tool",
            description = "dummy",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    @Test
    fun `promptCaching=false should not add cache_control anywhere`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = false)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should not have cache_control
        val system = request["system"]?.jsonArray
        assertNotNull(system)
        assertTrue(system!!.isNotEmpty())
        assertNull(system.last().jsonObject["cache_control"])

        // tools should not have cache_control
        val tools = request["tools"]?.jsonArray
        assertNotNull(tools)
        assertTrue(tools!!.isNotEmpty())
        assertNull(tools.last().jsonObject["cache_control"])

        // messages should not have cache_control
        val msgs = request["messages"]!!.jsonArray
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }

    @Test
    fun `promptCaching=true should add system boundary to the end of stable prefix`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        assertNull(request["cache_control"])

        // With no history or memory, SYSTEM_END is the final system block.
        val system = request["system"]!!.jsonArray
        val systemCacheControl = system.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", systemCacheControl["type"]!!.jsonPrimitive.content)
        assertNull(systemCacheControl["ttl"])

        // The first phase has only SYSTEM_END; tools are part of the same prefix.
        // MEMORY_END and HISTORY_END are absent when their sections are absent.
        val tools = request["tools"]!!.jsonArray
        assertNull(tools.last().jsonObject["cache_control"])
        assertEquals(
            1,
            request["system"]!!.jsonArray.count { it.jsonObject.containsKey("cache_control") } +
                tools.count { it.jsonObject.containsKey("cache_control") },
        )
    }

    @Test
    fun `dynamic system memory must not move cache breakpoint past stable prefix`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("stable instructions and tools")),
            memorySnapshot = listOf(UIMessage.system("dynamic memories")),
            latestUser = UIMessage.user("hello"),
        )
        val messages = prompt.toMessages()
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList(),
            logicalPrompt = prompt
        )

        val system = buildRequest(providerSetting, messages, params)["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertNotNull(system[0].jsonObject["cache_control"])
        assertNotNull(system[1].jsonObject["cache_control"])

        val history = buildRequest(providerSetting, messages, params)["messages"]!!.jsonArray
        assertEquals(1, history.size)
    }
    @Test
    fun `promptCaching=true without system should add cache_control to last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        assertNull(request["system"])

        val tools = request["tools"]!!.jsonArray
        val cacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
        assertNull(cacheControl["ttl"])
    }

    @Test
    fun `promptCaching=true with one hour ttl should add ttl to cache_control`() {
        val providerSetting = ProviderSetting.Claude(
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR
        )
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // SYSTEM_END remains on the stable system while HISTORY_END advances through history.
        assertNotNull(request["system"]!!.jsonArray.last().jsonObject["cache_control"])
        assertNull(request["tools"]!!.jsonArray.last().jsonObject["cache_control"])

        val messagesJson = request["messages"]!!.jsonArray
        val historicalAssistant = messagesJson.first { msg ->
            msg.jsonObject["role"]?.jsonPrimitive?.content == "assistant" &&
                msg.jsonObject["content"]?.jsonArray?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "text" &&
                        it.jsonObject["text"]?.jsonPrimitive?.content == "first answer"
                } == true
        }.jsonObject
        val messageCacheControl = historicalAssistant["content"]!!
            .jsonArray
            .last { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .jsonObject["cache_control"]!!
            .jsonObject
        assertEquals("ephemeral", messageCacheControl["type"]!!.jsonPrimitive.content)
        assertEquals("1h", messageCacheControl["ttl"]!!.jsonPrimitive.content)

        // No memory section was supplied, so messages contain only HISTORY_END.
        assertEquals(
            1,
            messagesJson.sumOf { message ->
                message.jsonObject["content"]?.jsonArray?.count {
                    it.jsonObject.containsKey("cache_control")
                } ?: 0
            },
        )
    }

    @Test
    fun `promptCaching=true should add cache_control after historical timeline`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question"),
            UIMessage.assistant("second answer"),
            UIMessage.user("third question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // HISTORY_END is after all historical messages, not before the latest user turn.
        val historicalAssistant = msgs.first { msg ->
            msg.jsonObject["role"]?.jsonPrimitive?.content == "assistant" &&
                msg.jsonObject["content"]?.jsonArray?.any {
                    it.jsonObject["text"]?.jsonPrimitive?.content == "second answer"
                } == true
        }.jsonObject
        assertNotNull(
            historicalAssistant["content"]!!.jsonArray.last().jsonObject["cache_control"]
        )

        val latestUser = msgs.last { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            .jsonObject
        assertNull(latestUser["content"]!!.jsonArray.last().jsonObject["cache_control"])

        val firstUser = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            .jsonObject
        assertNull(firstUser["content"]!!.jsonArray.last().jsonObject["cache_control"])
    }

    @Test
    fun `transient context and memory stay between history and latest user`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("stable")),
            history = listOf(
                UIMessage.user("old question"),
                UIMessage.assistant("old answer"),
            ),
            transientContext = listOf(UIMessage.user("retrieved evidence")),
            memory = listOf(UIMessage.system("memory")),
            latestUser = UIMessage.user("latest question"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test"),
                logicalPrompt = prompt,
            ),
        )

        val messages = request["messages"]!!.jsonArray
        val textOrder = messages.flatMap { message ->
            message.jsonObject["content"]!!.jsonArray.mapNotNull { block ->
                block.jsonObject["text"]?.jsonPrimitive?.content
            }
        }
        assertEquals(
            listOf("old question", "old answer", "retrieved evidence", "memory", "latest question"),
            textOrder,
        )

        val cachedBlocks = buildList {
            request["system"]?.jsonArray?.forEach { addAll(it.jsonObject["content"]?.jsonArray.orEmpty()) }
            messages.forEach { addAll(it.jsonObject["content"]?.jsonArray.orEmpty()) }
        }.filter { it.jsonObject.containsKey("cache_control") }
        assertEquals("old answer", cachedBlocks[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("memory", cachedBlocks[1].jsonObject["text"]?.jsonPrimitive?.content)
        assertNull(messages.last().jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"])
    }

    @Test
    fun `empty Claude memory emits no Base or MEMORY_END on wire`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("stable system")),
            memorySnapshot = listOf(UIMessage.system("complete current memory")),
            claudeMemoryCacheBlocks = emptyList(),
            claudeMemoryDelta = listOf(UIMessage.user("Memory Patch: create id=1")),
            latestUser = UIMessage.user("latest"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test"),
                logicalPrompt = prompt,
            ),
        )

        val system = request["system"]!!.jsonArray
        assertEquals(listOf("stable system"), system.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertTrue(request.toString().contains("Memory Base").not())
        assertTrue(request.toString().contains("complete current memory").not())
        assertTrue(request.toString().contains("Memory Patch: create id=1"))
        assertTrue(request.toString().contains("MEMORY_END").not())
        assertEquals(1, system.count { it.jsonObject.containsKey("cache_control") })
        val messageControls = request["messages"]!!.jsonArray.sumOf { message ->
            message.jsonObject["content"]!!.jsonArray.count {
                it.jsonObject.containsKey("cache_control")
            }
        }
        assertEquals(0, messageControls)
    }

    @Test
    fun `memory empty skips MEMORY_END and thinking is skipped for HISTORY_END`() {
        val thinkingHistory = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("cacheable history"),
                UIMessagePart.Reasoning("do not cache this block"),
            ),
        )
        val prompt = LogicalPrompt(
            history = listOf(thinkingHistory),
            latestUser = UIMessage.user("latest"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test"),
                logicalPrompt = prompt,
            ),
        )
        val blocks = request["messages"]!!.jsonArray.flatMap {
            it.jsonObject["content"]!!.jsonArray
        }
        assertNotNull(blocks.first { it.jsonObject["text"]?.jsonPrimitive?.content == "cacheable history" }
            .jsonObject["cache_control"])
        assertNull(blocks.first { it.jsonObject["type"]?.jsonPrimitive?.content == "thinking" }
            .jsonObject["cache_control"])
        assertEquals(1, blocks.count { it.jsonObject.containsKey("cache_control") })
    }

    @Test
    fun `region with only thinking blocks skips boundary without changing request`() {
        val prompt = LogicalPrompt(
            history = listOf(
                UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Reasoning("thinking only")),
                ),
            ),
            latestUser = UIMessage.user("latest"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test"),
                logicalPrompt = prompt,
            ),
        )
        val blocks = request["messages"]!!.jsonArray.flatMap {
            it.jsonObject["content"]!!.jsonArray
        }
        assertEquals(0, blocks.count { it.jsonObject.containsKey("cache_control") })
        assertEquals(
            "thinking only",
            blocks.first { it.jsonObject["type"]?.jsonPrimitive?.content == "thinking" }
                .jsonObject["thinking"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `historical tool call and result stay adjacent and in order`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "lookup",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val prompt = LogicalPrompt(
            history = listOf(
                UIMessage.user("find it"),
                UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("calling"), tool),
                ),
            ),
            latestUser = UIMessage.user("next"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test"),
                logicalPrompt = prompt,
            ),
        )
        val messages = request["messages"]!!.jsonArray
        val assistantIndex = messages.indexOfFirst { message ->
            message.jsonObject["content"]?.jsonArray?.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            } == true
        }
        assertTrue(assistantIndex >= 0)
        assertEquals("assistant", messages[assistantIndex].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", messages[assistantIndex + 1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "tool_result",
            messages[assistantIndex + 1].jsonObject["content"]!!.jsonArray.single()
                .jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertEquals("next", messages.last().jsonObject["content"]!!.jsonArray.single()
            .jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Claude renders tools system Base Patch and messages with exactly three boundaries`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("stable system")),
            history = listOf(
                UIMessage.user("old question"),
                UIMessage.assistant("old answer"),
            ),
            memorySnapshot = listOf(UIMessage.system("complete flat memory")),
            claudeMemoryCacheBlocks = listOf(
                ClaudeMemoryCacheBlock("Memory Base: id=1 content=before"),
                ClaudeMemoryCacheBlock("Memory Patch: upsert id=1 content=after"),
            ),
            claudeMemoryDelta = listOf(UIMessage.user("Memory Patch: deleted id=1")),
            latestUser = UIMessage.user("latest question"),
        )
        val request = buildRequest(
            providerSetting = ProviderSetting.Claude(promptCaching = true),
            messages = prompt.toMessages(),
            params = TextGenerationParams(
                model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(dummyTool()),
                logicalPrompt = prompt,
            ),
        )

        val tools = request["tools"]!!.jsonArray
        assertEquals("dummy_tool", tools.single().jsonObject["name"]?.jsonPrimitive?.content)
        assertNull(tools.single().jsonObject["cache_control"])

        val system = request["system"]!!.jsonArray
        assertEquals(
            listOf(
                "stable system",
                "Memory Base: id=1 content=before",
                "Memory Patch: upsert id=1 content=after",
            ),
            system.map { it.jsonObject["text"]?.jsonPrimitive?.content },
        )
        assertNotNull(system[0].jsonObject["cache_control"])
        assertNull(system[1].jsonObject["cache_control"])
        assertNotNull(system[2].jsonObject["cache_control"])

        val messages = request["messages"]!!.jsonArray
        val messageTexts = messages.flatMap { message ->
            message.jsonObject["content"]!!.jsonArray.mapNotNull { block ->
                block.jsonObject["text"]?.jsonPrimitive?.content
            }
        }
        assertEquals(
            listOf(
                "old question",
                "old answer",
                "Memory Patch: deleted id=1",
                "latest question",
            ),
            messageTexts,
        )
        val oldAnswer = messages.flatMap { it.jsonObject["content"]!!.jsonArray }
            .single { it.jsonObject["text"]?.jsonPrimitive?.content == "old answer" }
        assertNotNull(oldAnswer.jsonObject["cache_control"])
        assertEquals(
            3,
            system.count { it.jsonObject.containsKey("cache_control") } +
                tools.count { it.jsonObject.containsKey("cache_control") } +
                messages.sumOf { message ->
                    message.jsonObject["content"]!!.jsonArray.count {
                        it.jsonObject.containsKey("cache_control")
                    }
                },
        )
    }

    @Test
    fun `promptCaching=true with only one user message should not add cache_control to messages`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("only question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Only one user message, so no cache_control on messages
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }
}
