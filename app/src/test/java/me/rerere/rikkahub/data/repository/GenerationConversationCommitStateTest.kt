package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationMessageSnapshot
import me.rerere.rikkahub.data.model.commitGenerationMessages
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationConversationCommitStateTest {
    private fun fresh() = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(UIMessage.user("original").toMessageNode()),
        newConversation = true,
    )

    @Test
    fun `first save accepts an unsaved conversation`() {
        val conversation = fresh()
        GenerationConversationCommitState(false).requireValidTarget(conversation, null)
    }

    @Test
    fun `existing conversation accepts its current owner`() {
        val conversation = fresh().copy(newConversation = false, conversationNumber = 1)
        GenerationConversationCommitState(true).requireValidTarget(conversation, conversation.assistantId.toString())
    }

    @Test
    fun `missing persisted target cancels generation`() {
        assertThrows(CancellationException::class.java) {
            GenerationConversationCommitState(true).requireValidTarget(fresh(), null)
        }
    }

    @Test
    fun `missing non-new conversation cannot be recreated`() {
        assertThrows(CancellationException::class.java) {
            GenerationConversationCommitState(false).requireValidTarget(fresh().copy(newConversation = false), null)
        }
    }

    @Test
    fun `missing numbered conversation cannot be recreated`() {
        assertThrows(CancellationException::class.java) {
            GenerationConversationCommitState(false).requireValidTarget(fresh().copy(conversationNumber = 1), null)
        }
    }

    @Test
    fun `first save prevents a stale new snapshot from recreating a deleted conversation`() {
        val conversation = fresh()
        val commitState = GenerationConversationCommitState(false)
        commitState.requireValidTarget(conversation, null)
        commitState.recordPersisted()

        assertThrows(CancellationException::class.java) {
            commitState.requireValidTarget(conversation, null)
        }
    }

    @Test
    fun `changed owner cancels generation`() {
        assertThrows(CancellationException::class.java) {
            GenerationConversationCommitState(true).requireValidTarget(fresh(), Uuid.random().toString())
        }
    }

    @Test
    fun `missing conversation cancels child without failing parent`() = runBlocking {
        var finished = false
        var continued = false
        val generation = launch {
            try {
                GenerationConversationCommitState(true).requireValidTarget(fresh(), null)
                continued = true
            } finally {
                finished = true
            }
        }
        generation.join()

        assertTrue(generation.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(finished)
        assertFalse(continued)
    }

    @Test
    fun `deletion during checkpoint compensation cancels without escaping or restoring deleted data`() = runBlocking {
        withTimeout(5_000) {
            val original = fresh()
            val state = MutableStateFlow(original)
            val commitState = GenerationConversationCommitState(false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var stored: Conversation? = null
            var attempts = 0
            var mainRequestSent = false
            val generation = launch {
                commitGenerationMessages(state, GenerationMessageSnapshot.capture(original), original.currentMessages) { value ->
                    attempts++
                    commitState.requireValidTarget(value, stored?.assistantId?.toString())
                    val saved = value.copy(conversationNumber = 1, newConversation = false)
                    stored = saved
                    commitState.recordPersisted()
                    if (attempts == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    saved
                }
                mainRequestSent = true
            }
            entered.await()
            stored = null
            generation.cancel()
            release.complete(Unit)
            generation.join()

            assertEquals(2, attempts)
            assertTrue(generation.isCancelled)
            assertTrue(currentCoroutineContext().isActive)
            assertFalse(mainRequestSent)
            assertNull(stored)
            assertEquals(original, state.value)
        }
    }
}
