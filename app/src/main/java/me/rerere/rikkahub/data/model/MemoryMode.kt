package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility

internal enum class MemoryMode(val toolNames: Set<String>) {
    OFF(emptySet()),
    ORDINARY(setOf("memory_tool")),
    LAYERED(setOf("memory_read", "memory_write", "memory_consolidate"));

    fun supportsModel(model: Model?): Boolean =
        this != LAYERED || model?.abilities?.contains(ModelAbility.TOOL) == true

    fun requireSupportedModel(model: Model, message: () -> String) {
        check(supportsModel(model), message)
    }
}

internal val Assistant.memoryMode: MemoryMode
    get() = when {
        !enableMemory -> MemoryMode.OFF
        useLayeredMemory -> MemoryMode.LAYERED
        else -> MemoryMode.ORDINARY
    }
