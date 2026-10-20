package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.LAYERED_MEMORY_OUTPUT_LIMIT
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudeMemoryCacheBlock
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.buildLayeredMemoryTools
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LayeredMemoryPromptTest {
    private val model = Model(abilities = listOf(ModelAbility.TOOL))
    private val stableSystem = UIMessage.system("Stable assistant instructions").copy(isSynthetic = true)
    private val question = UIMessage.user("Current question")
    private val snapshot = listOf(AssistantMemory(id = 1, content = "DATABASE SECRET"))
    private val delta = listOf(UIMessage.system("ORDINARY DELTA"))
    private val patches = listOf(UIMessage.system("ORDINARY PATCH"))
    private val cache = listOf(ClaudeMemoryCacheBlock("ORDINARY CACHE"))

    private fun prompt(
        mode: MemoryMode,
        memories: List<AssistantMemory> = snapshot,
        conversation: List<UIMessage> = listOf(question),
        tools: List<Tool> = emptyList(),
        committedPatches: List<UIMessage> = patches,
        systems: List<UIMessage> = listOf(stableSystem),
    ) = buildMemoryGenerationPrompt(
        memoryMode = mode,
        tools = tools,
        stableSystems = systems,
        conversationMessages = conversation,
        additionalContextMessages = emptyList(),
        memorySnapshot = memories,
        claudeMemoryCacheBlocks = cache,
        memoryDelta = delta,
        claudeMemoryPatches = committedPatches,
    )

    private fun historicalRead(content: String) = UIMessage.assistant("").copy(
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = content,
                toolName = "memory_read",
                input = """{"keywords":"parameters"}""",
                output = listOf(UIMessagePart.Text(content)),
            )
        )
    )

    private fun toolsWithoutDatabaseReads(scope: String): List<Tool> {
        val dao = Proxy.newProxyInstance(
            MemoryDAO::class.java.classLoader,
            arrayOf(MemoryDAO::class.java),
        ) { _, method, _ ->
            error("Tool registration must not read the database: ${method.name}")
        } as MemoryDAO
        return buildLayeredMemoryTools(Json, scope, MemoryRepository(dao))
    }

    @Test
    fun `generation failure reply bounds invalid input without database access`() {
        val tool = toolsWithoutDatabaseReads("assistant-a").single { it.name == "memory_write" }
        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                tool.execute(buildJsonObject {
                    put("action", "edit"); put("id", 1)
                    put("category", "\u0001😀".repeat(20_000))
                })
            }
        }.exceptionOrNull() ?: error("invalid category must fail")
        assertTrue(error is IllegalArgumentException)
        val output = requireNotNull(layeredMemoryFailureOutput(MemoryMode.LAYERED, tool.name, error))
        assertTrue(output.length <= LAYERED_MEMORY_OUTPUT_LIMIT)
        val payload = Json.parseToJsonElement(output).jsonObject
        assertFalse(payload.getValue("success").jsonPrimitive.boolean)
        assertTrue(payload.getValue("error_truncated").jsonPrimitive.boolean)
        assertEquals("IllegalArgumentException", payload.getValue("error_type").jsonPrimitive.content)
        assertFalse(payload.containsKey("stack_trace"))
        assertNull(layeredMemoryFailureOutput(MemoryMode.ORDINARY, "memory_tool", error))
        assertNull(layeredMemoryFailureOutput(MemoryMode.OFF, "memory_read", error))
        assertNull(layeredMemoryFailureOutput(MemoryMode.LAYERED, "workspace_shell", error))
    }

    @Test
    fun `layered and off reject all ordinary memory channels even with populated inputs`() {
        for (mode in listOf(MemoryMode.LAYERED, MemoryMode.OFF)) {
            for (memories in listOf(emptyList(), snapshot)) {
                val request = prompt(mode, memories)
                assertEquals(listOf(stableSystem), request.system)
                assertTrue(request.memorySnapshot.isEmpty())
                assertTrue(request.memoryDelta.isEmpty())
                assertTrue(request.claudeMemoryDelta.isEmpty())
                assertNull(request.claudeMemoryCacheBlocks)
                assertFalse(request.hasMemory)
                assertEquals(listOf(stableSystem, question), request.toMessages())
                val text = request.toMessages().joinToString { it.toText() }
                assertFalse(text.contains("[Index]"))
                assertFalse(text.contains("[ACTIVE]"))
                assertFalse(text.contains("DATABASE SECRET"))
            }
        }
    }

    @Test
    fun `ordinary memory keeps its snapshot and committed patches without layered instructions`() {
        val request = prompt(MemoryMode.ORDINARY)
        assertEquals(buildMemoryPrompt(snapshot), request.memorySnapshot.single().toText())
        assertTrue(request.memorySnapshot.single().isSynthetic)
        assertSame(cache, request.claudeMemoryCacheBlocks)
        assertSame(patches, request.claudeMemoryDelta)
        assertTrue(request.memoryDelta.isEmpty())
        assertEquals(listOf(stableSystem), request.system)
        assertFalse(request.toMessages().joinToString { it.toText() }.contains("memory_read"))
        assertSame(delta, prompt(MemoryMode.ORDINARY, committedPatches = emptyList()).claudeMemoryDelta)
    }

    @Test
    fun `empty ordinary memory keeps existing empty snapshot behavior`() {
        assertTrue(prompt(MemoryMode.ORDINARY, emptyList()).memorySnapshot.isEmpty())
        assertTrue(prompt(MemoryMode.ORDINARY, listOf(AssistantMemory(1, " "))).memorySnapshot.isEmpty())
    }

    @Test
    fun `history and current tool results stay unchanged across mode and scope changes`() {
        val oldQuestion = UIMessage.user("Old question")
        val oldRead = historicalRead("OLD SCOPE RESULT")
        val latestRead = historicalRead("CURRENT RESULT")
        val conversation = listOf(oldQuestion, oldRead, question, latestRead)
        for (mode in MemoryMode.entries) {
            val request = prompt(mode, conversation = conversation)
            assertEquals(listOf(oldQuestion, oldRead), request.history)
            assertSame(oldRead, request.history.last())
            assertSame(question, request.latestUser)
            assertEquals(listOf(latestRead), request.currentToolLoop)
            assertSame(latestRead, request.currentToolLoop.single())
            assertEquals("OLD SCOPE RESULT", oldRead.getTools().single().output.single().let {
                (it as UIMessagePart.Text).text
            })
        }
        assertTrue(prompt(MemoryMode.LAYERED).history.isEmpty())
    }

    @Test
    fun `memory prompt preserves all systems and the real user through compression context`() {
        val extraSystem = UIMessage.system("Additional required instructions")
        val summary = contextCompressionMessage("Earlier conversation summary")
        val reminder = UIMessage.user("Synthetic reminder").copy(isSynthetic = true)
        val result = historicalRead("CURRENT TOOL RESULT")
        for (mode in MemoryMode.entries) {
            val request = prompt(
                mode,
                conversation = listOf(summary, question, result, reminder),
                systems = listOf(stableSystem, extraSystem),
            )
            assertEquals(listOf(stableSystem, extraSystem), request.system)
            assertEquals(listOf(summary), request.history)
            assertSame(question, request.latestUser)
            assertEquals(listOf(result, reminder), request.currentToolLoop)
        }
    }

    @Test
    fun `tool continuation only appends results and does not change the stable prefix`() {
        val tools = toolsWithoutDatabaseReads("assistant-a")
        val before = prompt(MemoryMode.LAYERED, tools = tools)
        val result = historicalRead("NEW TOOL RESULT")
        val after = prompt(MemoryMode.LAYERED, conversation = listOf(question, result), tools = tools)
        assertSame(before.system.single(), after.system.single())
        assertSame(before.tools, after.tools)
        assertEquals(before.toMessages() + result, after.toMessages())
        assertTrue(after.memorySnapshot.isEmpty())
        assertNull(after.claudeMemoryCacheBlocks)
    }

    @Test
    fun `fixed layered definitions need no database and do not vary with scope or history`() {
        val assistantTools = toolsWithoutDatabaseReads("assistant-a")
        val globalTools = toolsWithoutDatabaseReads(MemoryRepository.GLOBAL_MEMORY_ID)
        assertEquals(MemoryMode.LAYERED.toolNames, assistantTools.map { it.name }.toSet())
        for ((assistantTool, globalTool) in assistantTools.zip(globalTools)) {
            assertEquals(assistantTool.description, globalTool.description)
            assertEquals(assistantTool.parameters(), globalTool.parameters())
            assertEquals(
                assistantTool.systemPrompt(model, listOf(question)),
                globalTool.systemPrompt(model, listOf(question, historicalRead("database changed"))),
            )
            assertFalse(assistantTool.description.contains("[Index]"))
            assertFalse(assistantTool.description.contains("[ACTIVE]"))
            assertFalse(assistantTool.description.contains("only injects a compact index"))
        }
    }

    @Test
    fun `write rules stay in the fixed tool description rather than generated system messages`() {
        val tools = toolsWithoutDatabaseReads("assistant-a")
        val description = tools.single { it.name == "memory_write" }.description
        assertTrue(description.contains("Updating is LOCAL by default"))
        assertTrue(description.contains("action=edit"))
        assertTrue(description.contains("memory_read first"))
        assertTrue(description.contains("must occur exactly once"))
        assertTrue(description.contains("all-or-nothing"))
        assertTrue(description.contains("later turn"))
        assertTrue(description.contains("action=replace"))
        assertTrue(description.contains("Do not store sensitive information"))
        assertTrue(description.contains("unless the user explicitly asks"))
        assertEquals(listOf(stableSystem), prompt(MemoryMode.LAYERED, tools = tools).system)
    }

    @Test
    fun `read tool preserves the fixed stale record confirmation rule`() {
        val description = toolsWithoutDatabaseReads("assistant-a").single { it.name == "memory_read" }.description
        assertTrue(description.contains("older than 7 days"))
        assertTrue(description.contains("confirmed with the user"))
        assertTrue(description.contains("禁止凭印象编造参数细节"))
    }
}
