package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationMessageSnapshot
import me.rerere.rikkahub.data.model.commitGenerationMessages
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class ConversationSessionInitializationTest {
    @Test
    fun `concurrent callers share one initialization and keep the session in use`() = runBlocking {
        withSession { session, scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val loaded = session.state.value.copy(title = "loaded")
            val first = scope.async {
                session.initialize {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    session.state.value = loaded
                }
            }
            entered.await()
            val second = scope.async {
                session.initialize { calls.incrementAndGet() }
            }
            val sender = scope.async { session.awaitInitialized() }

            assertEquals(1, calls.get())
            assertTrue(session.isInUse)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(sender.isCompleted)

            release.complete(Unit)
            first.await()
            second.await()
            sender.await()
            assertEquals(1, calls.get())
            assertSame(loaded, session.state.value)
            assertFalse(session.isInUse)
        }
    }

    @Test
    fun `reopening a streaming session preserves its state and next commit in the same job`() = runBlocking {
        withSession { session, scope ->
            val persisted = session.state.value.copy(
                messageNodes = listOf(UIMessage.user("question").toMessageNode()),
            )
            var reads = 0
            session.initialize {
                reads++
                session.state.value = persisted
            }
            val firstChunk = CompletableDeferred<Unit>()
            val continueStream = CompletableDeferred<Unit>()
            var saved = persisted
            val response = UIMessage.assistant("first chunk")
            val finalResponse = response.copy(parts = listOf(UIMessagePart.Text("complete answer")))
            val generation = scope.launch {
                var request = GenerationMessageSnapshot.capture(session.state.value)
                request = commitGenerationMessages(session.state, request, request.input + response) {
                    saved = it
                    it
                }
                firstChunk.complete(Unit)
                continueStream.await()
                commitGenerationMessages(session.state, request, request.input + finalResponse) {
                    saved = it
                    it
                }
            }
            assertTrue(session.setJob(generation))
            firstChunk.await()
            val streamingState = session.state.value

            session.initialize {
                reads++
                session.state.value = persisted
            }

            assertEquals(1, reads)
            assertSame(streamingState, session.state.value)
            assertSame(generation, session.getJob())
            assertEquals(response, session.state.value.currentMessages.last())
            continueStream.complete(Unit)
            generation.join()
            assertFalse(generation.isCancelled)
            assertEquals(finalResponse, session.state.value.currentMessages.last())
            assertEquals(session.state.value, saved)
        }
    }

    @Test
    fun `cancelling the first page does not cancel initialization for another page`() = runBlocking {
        withSession { session, scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val loaded = session.state.value.copy(title = "loaded after page closed")
            val firstPage = scope.async {
                session.initialize {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    session.state.value = loaded
                }
            }
            entered.await()
            firstPage.cancelAndJoin()
            assertTrue(firstPage.isCancelled)
            assertTrue(session.isInUse)
            val secondPage = scope.async {
                session.initialize { calls++ }
                session.state.value
            }
            assertFalse(secondPage.isCompleted)

            release.complete(Unit)
            assertSame(loaded, secondPage.await())
            session.awaitInitialized()
            assertEquals(1, calls)
        }
    }

    @Test
    fun `failed waiters share one failure and only explicit initialization retries`() = runBlocking {
        withSession { session, scope ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("load failed")
            var calls = 0
            val first = scope.async {
                runCatching {
                    session.initialize {
                        calls++
                        entered.complete(Unit)
                        release.await()
                        throw failure
                    }
                }
            }
            entered.await()
            val second = scope.async { runCatching { session.initialize { calls++ } } }
            val sender = scope.async { runCatching { session.awaitInitialized() } }

            release.complete(Unit)
            val failures = listOf(
                first.await().exceptionOrNull(),
                second.await().exceptionOrNull(),
                sender.await().exceptionOrNull(),
                runCatching { session.awaitInitialized() }.exceptionOrNull(),
            )
            failures.forEach { error ->
                assertTrue(error is IllegalStateException)
                assertEquals(failure.message, error?.message)
            }
            assertEquals(1, calls)

            val retryEntered = CompletableDeferred<Unit>()
            val retryRelease = CompletableDeferred<Unit>()
            val loaded = session.state.value.copy(title = "recovered")
            val retry = scope.async {
                session.initialize {
                    calls++
                    retryEntered.complete(Unit)
                    retryRelease.await()
                    session.state.value = loaded
                }
            }
            retryEntered.await()
            val retryPage = scope.async { session.initialize { calls++ } }
            val retrySender = scope.async { session.awaitInitialized() }
            assertEquals(2, calls)
            assertFalse(retry.isCompleted)
            assertFalse(retryPage.isCompleted)
            assertFalse(retrySender.isCompleted)

            retryRelease.complete(Unit)
            retry.await()
            retryPage.await()
            retrySender.await()
            session.initialize { calls++ }
            assertEquals(2, calls)
            assertSame(loaded, session.state.value)
        }
    }

    @Test
    fun `old attempt completion and cancellation cannot finish a newer attempt`() = runBlocking {
        for (cancelOldAttempt in listOf(false, true)) {
            withSession { session, scope ->
                val oldEntered = CompletableDeferred<Job>()
                val finishOld = CompletableDeferred<Unit>()
                val failure = IllegalStateException("old attempt failed")
                var calls = 0
                val first = scope.async {
                    runCatching {
                        session.initialize {
                            calls++
                            oldEntered.complete(currentCoroutineContext()[Job]!!)
                            finishOld.await()
                            if (cancelOldAttempt) {
                                currentCoroutineContext().cancel(CancellationException("old attempt cancelled"))
                                awaitCancellation()
                            }
                        }
                    }
                }
                val oldTask = oldEntered.await()
                session.failInitialization(failure)
                assertEquals(failure.message, first.await().exceptionOrNull()?.message)

                val retryEntered = CompletableDeferred<Unit>()
                val finishRetry = CompletableDeferred<Unit>()
                val retry = scope.async {
                    session.initialize {
                        calls++
                        retryEntered.complete(Unit)
                        finishRetry.await()
                    }
                }
                retryEntered.await()
                val sender = scope.async { session.awaitInitialized() }

                finishOld.complete(Unit)
                oldTask.join()
                assertEquals(cancelOldAttempt, oldTask.isCancelled)
                assertEquals(2, calls)
                assertFalse(retry.isCompleted)
                assertFalse(sender.isCompleted)
                assertTrue(session.isInUse)

                finishRetry.complete(Unit)
                retry.await()
                sender.await()
                session.initialize { calls++ }
                assertEquals(2, calls)
            }
        }
    }

    @Test
    fun `cleanup cancels shared initialization and releases its waiters`() = runBlocking {
        withSession { session, scope ->
            val entered = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val page = scope.async {
                runCatching {
                    session.initialize {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            exited.complete(Unit)
                        }
                    }
                }
            }
            entered.await()
            val sender = scope.async { runCatching { session.awaitInitialized() } }
            session.cleanup()

            exited.await()
            assertTrue(page.await().exceptionOrNull() is CancellationException)
            assertTrue(sender.await().exceptionOrNull() is CancellationException)
            var restarted = false
            val revisit = runCatching { session.initialize { restarted = true } }
            assertTrue(revisit.exceptionOrNull() is CancellationException)
            assertFalse(restarted)
            assertFalse(session.isInUse)
        }
    }

    @Test
    fun `cancelled application scope fails initialization without running the loader`() = runBlocking {
        withSession { session, scope ->
            scope.cancel()
            var loaded = false
            val result = runCatching { session.initialize { loaded = true } }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(loaded)
            assertTrue(runCatching { session.awaitInitialized() }.exceptionOrNull() is CancellationException)
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession, CoroutineScope) -> Unit) {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val initial = Conversation.ofId(Uuid.random())
                val session = ConversationSession(initial.id, initial, scope, onIdle = {})
                block(session, scope)
            } finally {
                scope.cancel()
            }
        }
    }
}
