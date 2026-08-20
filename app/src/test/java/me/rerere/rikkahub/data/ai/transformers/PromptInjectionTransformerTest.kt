package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    // region Helper functions
    private fun createAssistant(
        modeInjectionIds: Set<Uuid> = emptySet(),
        allowConversationPromptInjection: Boolean = false
    ) = Assistant(
        modeInjectionIds = modeInjectionIds,
        allowConversationPromptInjection = allowConversationPromptInjection
    )

    private fun createModeInjection(
        id: Uuid = Uuid.random(),
        name: String = "Test Injection",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "Injected content",
        injectDepth: Int = 4,
        role: MessageRole = MessageRole.USER
    ) = PromptInjection.ModeInjection(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        injectDepth = injectDepth,
        role = role
    )

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private fun createAssistantWithUnexecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = emptyList()
                )
            )
        )
    }

    private fun createAssistantWithExecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )
        )
    }
    // endregion

    // region No injection tests
    @Test
    fun `no injections should return original messages`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi there!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(),
            modeInjections = emptyList(),
        )

        assertEquals(messages, result)
    }

    @Test
    fun `disabled mode injection should not be applied`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            enabled = false,
            content = "Should not appear"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(messages, result)
    }

    @Test
    fun `unlinked mode injection should not be applied`() {
        val injection = createModeInjection(content = "Should not appear")

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(), // No linked injections
            modeInjections = listOf(injection),
        )

        assertEquals(messages, result)
    }

    @Test
    fun `conversation mode injection should apply only when assistant allows it`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            content = "Conversation content"
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val disabledResult = transformMessages(
            messages = messages,
            assistant = createAssistant(allowConversationPromptInjection = false),
            modeInjections = listOf(injection),
            conversationModeInjectionIds = setOf(injectionId)
        )
        val enabledResult = transformMessages(
            messages = messages,
            assistant = createAssistant(allowConversationPromptInjection = true),
            modeInjections = listOf(injection),
            conversationModeInjectionIds = setOf(injectionId)
        )

        assertEquals(messages, disabledResult)
        assertTrue(getMessageText(enabledResult.first()).contains("Conversation content"))
    }

    @Test
    fun `assistant mode injection should be ignored when conversation injection is allowed`() {
        val assistantInjectionId = Uuid.random()
        val conversationInjectionId = Uuid.random()
        val assistantInjection = createModeInjection(
            id = assistantInjectionId,
            content = "Assistant content"
        )
        val conversationInjection = createModeInjection(
            id = conversationInjectionId,
            content = "Conversation content"
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(
                modeInjectionIds = setOf(assistantInjectionId),
                allowConversationPromptInjection = true
            ),
            modeInjections = listOf(assistantInjection, conversationInjection),
            conversationModeInjectionIds = setOf(conversationInjectionId)
        )
        val systemText = getMessageText(result.first())

        assertFalse(systemText.contains("Assistant content"))
        assertTrue(systemText.contains("Conversation content"))
    }

    // endregion

    // region AFTER_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with AFTER_SYSTEM_PROMPT should append to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "Appended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Original system prompt"))
        assertTrue(systemText.endsWith("Appended content"))
    }
    // endregion

    // region BEFORE_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with BEFORE_SYSTEM_PROMPT should prepend to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Prepended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Prepended content"))
        assertTrue(systemText.contains("Original system prompt"))
    }

    @Test
    fun `injection without existing system message should create new system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "New system content"
        )

        val messages = listOf(
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(3, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("New system content", getMessageText(result[0]))
    }
    // endregion

    // region TOP_OF_CHAT tests
    @Test
    fun `mode injection with TOP_OF_CHAT should insert before first user message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.TOP_OF_CHAT,
            content = "Top of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("System prompt", getMessageText(result[0]))
        assertEquals(MessageRole.USER, result[1].role)
        assertEquals("Top of chat content", getMessageText(result[1]))
        assertEquals(MessageRole.USER, result[2].role)
    }
    // endregion

    // region BOTTOM_OF_CHAT tests
    @Test
    fun `mode injection with BOTTOM_OF_CHAT should insert before last message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom of chat content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(5, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("Bottom of chat content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("How are you?", getMessageText(result[4]))
    }
    // endregion

    // region AT_DEPTH tests
    @Test
    fun `mode injection with AT_DEPTH should insert at specified depth from end`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 2,
            content = "At depth 2 content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        // depth=2 means insert before the 2nd message from the end
        // Original: [System, User1, Asst1, User2, Asst2] (5 messages)
        // Insert at index 5-2=3, so: [System, User1, Asst1, Injected, User2, Asst2]
        assertEquals(6, result.size)
        assertEquals(MessageRole.USER, result[3].role)
        assertEquals("At depth 2 content", getMessageText(result[3]))
        assertEquals(MessageRole.USER, result[4].role)
        assertEquals("Message 2", getMessageText(result[4]))
    }

    @Test
    fun `AT_DEPTH with depth 1 should insert before last message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
            content = "Before last"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)
        assertEquals("Before last", getMessageText(result[2]))
        assertEquals("Hi!", getMessageText(result[3]))
    }

    @Test
    fun `AT_DEPTH with depth larger than message count should insert at beginning`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 100,
            content = "Large depth content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(3, result.size)
        assertEquals("Large depth content", getMessageText(result[0]))
    }

    @Test
    fun `multiple AT_DEPTH injections with different depths should all apply`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 1,
                content = "Depth 1"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 3,
                content = "Depth 3"
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Response 1"),
            UIMessage.user("Message 2"),
            UIMessage.assistant("Response 2")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
        )

        // Both should be inserted
        assertEquals(7, result.size)
        assertTrue(result.any { getMessageText(it).contains("Depth 1") })
        assertTrue(result.any { getMessageText(it).contains("Depth 3") })
    }

    @Test
    fun `multiple AT_DEPTH injections with same depth should be merged`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 2,
                priority = 10,
                content = "Higher priority"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AT_DEPTH,
                injectDepth = 2,
                priority = 5,
                content = "Lower priority"
            )
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
        )

        // Same depth injections should be merged into one message
        assertEquals(4, result.size)
        val injectedText = getMessageText(result[1])
        assertTrue(injectedText.contains("Higher priority"))
        assertTrue(injectedText.contains("Lower priority"))
        // Higher priority should come first
        assertTrue(injectedText.indexOf("Higher priority") < injectedText.indexOf("Lower priority"))
    }
    // endregion

    // region Priority tests
    @Test
    fun `injections should be ordered by priority descending`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()

        val injections = listOf(
            createModeInjection(id = id1, priority = 1, content = "Priority 1"),
            createModeInjection(id = id2, priority = 3, content = "Priority 3"),
            createModeInjection(id = id3, priority = 2, content = "Priority 2")
        )

        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2, id3)),
            modeInjections = injections,
        )

        val systemText = getMessageText(result[0])
        // Higher priority should come first when joining
        assertTrue(systemText.contains("Priority 3"))
        assertTrue(systemText.indexOf("Priority 3") < systemText.indexOf("Priority 2"))
        assertTrue(systemText.indexOf("Priority 2") < systemText.indexOf("Priority 1"))
    }
    // endregion

    // region Multiple injections tests
    @Test
    fun `multiple injections at different positions should all apply`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()

        val injections = listOf(
            createModeInjection(
                id = id1,
                position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                content = "Before"
            ),
            createModeInjection(
                id = id2,
                position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                content = "After"
            ),
            createModeInjection(
                id = id3,
                position = InjectionPosition.TOP_OF_CHAT,
                content = "Top"
            )
        )

        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2, id3)),
            modeInjections = injections,
        )

        assertEquals(3, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Before"))
        assertTrue(systemText.contains("System"))
        assertTrue(systemText.endsWith("After"))
        assertEquals("Top", getMessageText(result[1]))
    }

    // endregion

    // region collectInjections tests
    @Test
    fun `collectInjections should return empty for no matching conditions`() {
        val result = collectInjections(
            assistant = createAssistant(),
            modeInjections = listOf(createModeInjection()),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections should collect linked and enabled mode injections`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(id = id1, enabled = true),
            createModeInjection(id = id2, enabled = false)
        )

        val result = collectInjections(
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
        )

        assertEquals(1, result.size)
        assertEquals(id1, result[0].id)
    }
    // endregion

    // region applyInjections tests
    @Test
    fun `applyInjections with empty map should return original messages`() {
        val messages = listOf(
            UIMessage.system("System"),
            UIMessage.user("Hello")
        )

        val result = applyInjections(messages, emptyMap())

        assertEquals(messages, result)
    }

    @Test
    fun `applyInjections should handle messages without system message`() {
        val injection = createModeInjection(
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Before content"
        )

        val messages = listOf(
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        val result = applyInjections(
            messages,
            mapOf(InjectionPosition.BEFORE_SYSTEM_PROMPT to listOf(injection))
        )

        assertEquals(3, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("Before content", getMessageText(result[0]))
    }
    // endregion

    // region findSafeInsertIndex tests
    @Test
    fun `findSafeInsertIndex should not insert between USER and ASSISTANT with tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        // 尝试在索引 2（USER 和 ASSISTANT(tool) 之间）插入，应该移到 USER 之前
        val safeIndex = findSafeInsertIndex(messages, 2)
        assertEquals(1, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should allow insert before ASSISTANT without tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!")
        )

        // ASSISTANT 没有 tool，直接插入不受限制
        val safeIndex = findSafeInsertIndex(messages, 2)
        assertEquals(2, safeIndex)
    }

    @Test
    fun `findSafeInsertIndex should return original index when no tools`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi!"),
            UIMessage.user("How are you?")
        )

        assertEquals(3, findSafeInsertIndex(messages, 3))
        assertEquals(2, findSafeInsertIndex(messages, 2))
        assertEquals(0, findSafeInsertIndex(messages, 0))
    }

    @Test
    fun `BOTTOM_OF_CHAT should not inject between USER and ASSISTANT with tools`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)

        // 注入应该在 USER 之前，而不是 USER 和 ASSISTANT(tool) 之间
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val originalUserIndex = result.indexOfFirst { getMessageText(it).contains("Call a tool") }
        val assistantWithToolIndex = result.indexOfFirst { it.getTools().isNotEmpty() }

        assertTrue(injectedIndex < originalUserIndex)
        assertEquals(originalUserIndex + 1, assistantWithToolIndex)
    }

    @Test
    fun `AT_DEPTH should not inject between USER and ASSISTANT with tools`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            injectDepth = 1,
            content = "Depth injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(tool)
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithUnexecutedTool("call_1", "tool")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)

        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Depth injection") }
        val originalUserIndex = result.indexOfFirst { getMessageText(it).contains("Call a tool") }
        val assistantWithToolIndex = result.indexOfFirst { it.getTools().isNotEmpty() }

        assertTrue(injectedIndex < originalUserIndex)
        assertEquals(originalUserIndex + 1, assistantWithToolIndex)
    }

    @Test
    fun `injection after ASSISTANT with tools should work normally`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom injection"
        )

        // 消息序列: SYSTEM -> USER -> ASSISTANT(executed tool) -> ASSISTANT(final) -> USER
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Call a tool"),
            createAssistantWithExecutedTool("call_1", "tool"),
            UIMessage.assistant("Here's the result"),
            UIMessage.user("Thanks!")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(6, result.size)

        // 注入应该在最后一条用户消息之前
        val injectedIndex = result.indexOfFirst { getMessageText(it).contains("Bottom injection") }
        val lastUserIndex = result.indexOfLast { it.role == MessageRole.USER && getMessageText(it) == "Thanks!" }
        assertEquals(lastUserIndex - 1, injectedIndex)
    }
    // endregion
}
