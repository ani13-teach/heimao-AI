package me.rerere.rikkahub.data.ai.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The actual tool-call identity is supplied by the executor, never by model arguments. */
internal class WorkspaceAptToolCall(val id: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceAptToolCall>
}
