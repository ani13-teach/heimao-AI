package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.generateTextWithModelTransport
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
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
    fun `fallbacks belonging to another provider are allowed`() {
        val a = model("a")
        val b = model("b")
        val outsider = model("outsider")
        val providers = listOf(provider("p", a, b), provider("q", outsider))

        val candidates = fallbackCandidates(providers, a, listOf(outsider.id, b.id)) { true }

        assertEquals(listOf(a.id, outsider.id, b.id), candidates.map { it.id })
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
    fun `a primary nobody owns can still use configured backups`() {
        val stranger = model("stranger")
        val b = model("b")
        val providers = listOf(provider("p", b))

        val candidates = fallbackCandidates(
            providers = providers,
            primaryModel = stranger,
            fallbackModelIds = listOf(b.id),
            accept = { true },
        )

        assertEquals(listOf(stranger.id, b.id), candidates.map { it.id })
    }

    @Test
    fun `same model id at different providers remains distinct by uuid`() {
        val primary = model("same")
        val backup = model("same")
        val p = provider("p", primary)
        val q = provider("q", backup)
        val candidates = fallbackCandidates(listOf(p, q), primary, listOf(backup.id)) { true }

        assertEquals(listOf(primary.id, backup.id), candidates.map { it.id })
        assertEquals(q, candidates.last().findProvider(listOf(p, q)))
    }

    @Test
    fun `disabled deleted and non chat fallbacks are skipped`() {
        val primary = model("primary")
        val disabled = model("disabled")
        val deleted = model("deleted")
        val image = model("image", ModelType.IMAGE)
        val valid = model("valid")
        val candidates = fallbackCandidates(
            listOf(provider("p", primary, valid, image), provider("q", disabled).copy(enabled = false)),
            primary, listOf(disabled.id, deleted.id, image.id, valid.id),
        ) { true }

        assertEquals(listOf(primary.id, valid.id), candidates.map { it.id })
    }

    @Test
    fun `ocr filters backups without image input`() {
        val primary = model("primary").copy(inputModalities = listOf(Modality.IMAGE))
        val text = model("text")
        val vision = model("vision").copy(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val candidates = fallbackCandidates(
            listOf(provider("p", primary, text, vision)), primary, listOf(text.id, vision.id),
        ) { Modality.IMAGE in it.inputModalities }

        assertEquals(listOf(primary.id, vision.id), candidates.map { it.id })
    }

    @Test
    fun `ten backups are allowed and an eleventh is ignored without rewriting settings`() {
        val primary = model("primary")
        val backups = (1..11).map { model("backup$it") }
        val ids = backups.map { it.id }
        val providers = listOf(provider("p", primary, *backups.toTypedArray()))
        for (count in listOf(10, 11)) {
            val configured = ids.take(count)
            val candidates = fallbackCandidates(providers, primary, configured) { true }
            assertEquals(listOf(primary.id) + ids.take(10), candidates.map { it.id })
            assertEquals(count, configured.size)
        }
        val duplicated = listOf(primary.id, ids[0], ids[0]) + ids
        assertEquals(11, fallbackCandidates(providers, primary, duplicated) { true }.size)
    }

    @Test
    fun `fast chain is inherited only without dedicated primary or fallbacks`() {
        val fast = model("fast")
        val dedicated = model("dedicated")
        val fastBackup = model("fast backup")
        val dedicatedBackup = model("dedicated backup")
        val settings = Settings(
            fastModelId = fast.id, fastFallbackModelIds = listOf(fastBackup.id),
            providers = listOf(provider("p", fast, dedicated, fastBackup, dedicatedBackup)),
        )
        assertEquals(listOf(fast.id, fastBackup.id), settings.titleModelCandidates().map { it.id })
        assertEquals(listOf(fast.id, fastBackup.id), settings.suggestionModelCandidates().map { it.id })
        val ownChain = settings.copy(
            titleFallbackModelIds = listOf(dedicatedBackup.id),
            suggestionFallbackModelIds = listOf(dedicatedBackup.id),
        )
        assertEquals(listOf(fast.id, dedicatedBackup.id), ownChain.titleModelCandidates().map { it.id })
        assertEquals(listOf(fast.id, dedicatedBackup.id), ownChain.suggestionModelCandidates().map { it.id })
        val explicit = settings.copy(titleModelId = dedicated.id, suggestionModelId = dedicated.id)
        assertEquals(listOf(dedicated.id), explicit.titleModelCandidates().map { it.id })
        assertEquals(listOf(dedicated.id), explicit.suggestionModelCandidates().map { it.id })
        val missing = model("missing").id
        val invalidExplicit = settings.copy(titleModelId = missing, suggestionModelId = missing)
        assertEquals(listOf(fast.id), invalidExplicit.titleModelCandidates().map { it.id })
        assertEquals(listOf(fast.id), invalidExplicit.suggestionModelCandidates().map { it.id })
        val explicitChain = ownChain.copy(titleModelId = dedicated.id, suggestionModelId = dedicated.id)
        assertEquals(listOf(dedicated.id, dedicatedBackup.id), explicitChain.titleModelCandidates().map { it.id })
        assertEquals(listOf(dedicated.id, dedicatedBackup.id), explicitChain.suggestionModelCandidates().map { it.id })
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
    fun `empty batch and streaming replies never echo the request and move to backup`() = runBlocking {
        val emptyProvider = object : Provider<ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
            override suspend fun generateText(
                providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
            ) = TextGenerationResult("empty", params.model.modelId, UIMessage.assistant(""))
            override suspend fun streamText(
                providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams,
            ): Flow<StreamChunk> = emptyFlow()
        }
        for (stream in listOf(true, false)) {
            val primary = model("primary").copy(streamOutput = stream)
            val backup = model("backup")
            val tried = mutableListOf<Model>()
            val result = runWithModelFallback(listOf(primary, backup)) { candidate ->
                tried += candidate
                if (candidate == backup) "answer" else {
                    val messages = emptyProvider.generateTextWithModelTransport(
                        providerSetting = provider("p", primary),
                        messages = listOf(UIMessage.user("request must not become answer")),
                        params = TextGenerationParams(model = candidate),
                        responseMessages = listOf(UIMessage.assistant("")),
                        retryPolicy = Settings(autoRetryMaxAttempts = 1).toRetryPolicy(),
                    )
                    messages.lastOrNull()?.toText()?.trim()?.takeIf { it.isNotBlank() }
                }
            }
            assertEquals("answer", result)
            assertEquals(listOf(primary, backup), tried)
        }
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
