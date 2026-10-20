package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.model.memoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryModeTest {
    private val noTools = Model(abilities = emptyList())
    private val withTools = Model(abilities = listOf(ModelAbility.TOOL))

    @Test
    fun `mode is determined only by enableMemory and useLayeredMemory`() {
        for (enabled in listOf(false, true)) {
            for (layered in listOf(false, true)) {
                for (global in listOf(false, true)) {
                    val assistant = Assistant(
                        enableMemory = enabled,
                        useLayeredMemory = layered,
                        useGlobalMemory = global,
                    )
                    assertEquals(
                        when {
                            !enabled -> MemoryMode.OFF
                            layered -> MemoryMode.LAYERED
                            else -> MemoryMode.ORDINARY
                        },
                        assistant.memoryMode,
                    )
                }
            }
        }
    }

    @Test
    fun `tool name protection follows the effective mode`() {
        assertEquals(emptySet<String>(), MemoryMode.OFF.toolNames)
        assertEquals(setOf("memory_tool"), MemoryMode.ORDINARY.toolNames)
        assertEquals(setOf("memory_read", "memory_write", "memory_consolidate"), MemoryMode.LAYERED.toolNames)
    }

    @Test
    fun `only layered mode blocks models without tool ability and leaves settings unchanged`() {
        for (mode in listOf(MemoryMode.OFF, MemoryMode.ORDINARY)) {
            assertTrue(mode.supportsModel(noTools))
            mode.requireSupportedModel(noTools) { error("Must not request an error message") }
        }
        val assistant = Assistant(enableMemory = true, useLayeredMemory = true)
        val original = assistant.copy()
        assertFalse(assistant.memoryMode.supportsModel(noTools))
        assertFalse(assistant.memoryMode.supportsModel(null))
        val failure = assertThrows(IllegalStateException::class.java) {
            assistant.memoryMode.requireSupportedModel(noTools) { "Choose a model with tool calls" }
        }
        assertEquals("Choose a model with tool calls", failure.message)
        assistant.memoryMode.requireSupportedModel(withTools) { error("Tool model must pass") }
        assertEquals(original, assistant)
    }

    @Test
    fun `capability responds to default model changes and assistant override`() {
        val assistant = Assistant(enableMemory = true, useLayeredMemory = true)
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(noTools, withTools))),
            chatModelId = noTools.id,
        )
        fun supported(assistant: Assistant, settings: Settings) = assistant.memoryMode.supportsModel(
            settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        )
        assertFalse(supported(assistant, settings))
        assertTrue(supported(assistant, settings.copy(chatModelId = withTools.id)))
        assertTrue(supported(assistant.copy(chatModelId = withTools.id), settings))
        assertFalse(supported(assistant.copy(chatModelId = noTools.id), settings.copy(chatModelId = withTools.id)))
        assertTrue(supported(assistant.copy(enableMemory = false), settings))
        assertTrue(supported(assistant.copy(useLayeredMemory = false), settings))
    }
}
