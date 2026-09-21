package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFallbackTest {
    private fun model(name: String, type: ModelType = ModelType.CHAT) = Model(
        modelId = name,
        displayName = name,
        type = type,
    )

    private fun provider(name: String, vararg models: Model) = ProviderSetting.OpenAI(
        name = name,
        models = models.toList(),
    )

    @Test
    fun `primary comes first and fallbacks keep the user order`() {
        val a = model("a")
        val b = model("b")
        val c = model("c")
        val providers = listOf(provider("p", a, b, c))

        val candidates = fallbackCandidates(providers, a, listOf(c.id, b.id)) { true }

        assertEquals(listOf(a.id, c.id, b.id), candidates.map { it.id })
    }

    @Test
    fun `fallbacks belonging to another provider are dropped`() {
        val a = model("a")
        val b = model("b")
        val outsider = model("outsider")
        val providers = listOf(provider("p", a, b), provider("q", outsider))

        val candidates = fallbackCandidates(providers, a, listOf(outsider.id, b.id)) { true }

        assertEquals(listOf(a.id, b.id), candidates.map { it.id })
    }

    @Test
    fun `fallbacks the caller rejects are dropped`() {
        val a = model("a")
        val b = model("b")
        val image = model("img", ModelType.IMAGE)
        val providers = listOf(provider("p", a, b, image))

        val candidates = fallbackCandidates(providers, a, listOf(image.id, b.id)) {
            it.type == ModelType.CHAT
        }

        assertEquals(listOf(a.id, b.id), candidates.map { it.id })
    }

    @Test
    fun `duplicates and the primary itself are dropped`() {
        val a = model("a")
        val b = model("b")
        val providers = listOf(provider("p", a, b))

        val candidates = fallbackCandidates(providers, a, listOf(b.id, a.id, b.id)) { true }

        assertEquals(listOf(a.id, b.id), candidates.map { it.id })
    }

    @Test
    fun `a primary nobody owns falls back to itself only`() {
        val stranger = model("stranger")
        val b = model("b")
        val providers = listOf(provider("p", b))

        val candidates = fallbackCandidates(
            providers = providers,
            primaryModel = stranger,
            fallbackModelIds = listOf(b.id),
            accept = { true },
        )

        assertEquals(listOf(stranger.id), candidates.map { it.id })
    }

    @Test
    fun `a failing model switches to the next candidate`() = runBlocking {
        val primary = model("primary")
        val backup = model("backup")
        val tried = mutableListOf<String>()

        val result = runWithModelFallback(listOf(primary, backup)) { candidate ->
            tried += candidate.modelId
            if (candidate == primary) throw IOException("connection reset") else "ok"
        }

        assertEquals(listOf("primary", "backup"), tried)
        assertEquals("ok", result)
    }

    @Test
    fun `a candidate that returns nothing counts as failed`() = runBlocking {
        val primary = model("primary")
        val backup = model("backup")
        val tried = mutableListOf<String>()

        val result = runWithModelFallback(listOf(primary, backup)) { candidate ->
            tried += candidate.modelId
            if (candidate == primary) null else "ok"
        }

        assertEquals(listOf("primary", "backup"), tried)
        assertEquals("ok", result)
    }

    @Test
    fun `when every candidate fails the last failure is thrown`() = runBlocking {
        val primary = model("primary")
        val backup = model("backup")
        val reported = mutableListOf<String>()

        val error = runCatching {
            runWithModelFallback(
                candidates = listOf(primary, backup),
                onCandidateFailed = { candidate, _ -> reported += candidate.modelId },
            ) { candidate ->
                throw IOException("${candidate.modelId} down")
            }
        }.exceptionOrNull()

        assertEquals(listOf("primary", "backup"), reported)
        assertTrue(error is IOException)
        assertEquals("backup down", error?.message)
    }

    @Test
    fun `an empty result on the only candidate still reports a failure`() = runBlocking {
        val primary = model("primary")

        val error = runCatching {
            runWithModelFallback(listOf(primary)) { null }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("primary"))
    }

    @Test
    fun `cancellation goes straight through and never tries the backup`() = runBlocking {
        val primary = model("primary")
        val backup = model("backup")
        val tried = mutableListOf<String>()

        val error = runCatching {
            runWithModelFallback(listOf(primary, backup)) { candidate ->
                tried += candidate.modelId
                throw CancellationException("user stopped")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(listOf("primary"), tried)
    }

    @Test
    fun `candidates must not be empty`() = runBlocking {
        val error = runCatching {
            runWithModelFallback(emptyList<Model>()) { "never" }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
