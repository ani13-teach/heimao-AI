package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

internal const val MAX_MODEL_FALLBACKS = 10

internal fun Settings.titleFallbackIds(): List<Uuid> =
    if (titleModelId == null && titleFallbackModelIds.isEmpty()) fastFallbackModelIds else titleFallbackModelIds

internal fun Settings.suggestionFallbackIds(): List<Uuid> =
    if (suggestionModelId == null && suggestionFallbackModelIds.isEmpty()) fastFallbackModelIds else suggestionFallbackModelIds

internal fun Settings.titleModelCandidates(): List<Model> =
    findModelById(titleModelId, fallback = fastModelId)?.let { primary ->
        fallbackCandidates(providers, primary, titleFallbackIds()) {
            it.type == ModelType.CHAT && !ModelRegistry.QWEN_MT.match(it.modelId)
        }
    }.orEmpty()

internal fun Settings.suggestionModelCandidates(): List<Model> =
    findModelById(suggestionModelId, fallback = fastModelId)?.let { primary ->
        fallbackCandidates(providers, primary, suggestionFallbackIds()) {
            it.type == ModelType.CHAT && !ModelRegistry.QWEN_MT.match(it.modelId)
        }
    }.orEmpty()

/** 主模型优先，备用按 UUID 从所有启用的供应商中解析，不改写原始配置。 */
internal fun fallbackCandidates(
    providers: List<ProviderSetting>,
    primaryModel: Model,
    fallbackModelIds: List<Uuid>,
    accept: (Model) -> Boolean,
): List<Model> {
    val available = providers.filter { it.enabled }.flatMap { it.models }
    val fallbacks = fallbackModelIds.asSequence()
        .distinct()
        .filter { it != primaryModel.id }
        .mapNotNull { id -> available.firstOrNull { it.id == id } }
        .filter { it.type == ModelType.CHAT && accept(it) }
        .take(MAX_MODEL_FALLBACKS)
        .toList()

    return listOf(primaryModel) + fallbacks
}

/**
 * 依次尝试候选模型，返回第一个拿到结果的那次尝试的结果。
 *
 * [attempt] 返回 null 表示这次不算成功（例如只回了空正文），会接着试下一个候选。
 * 每个候选各自按调用方的重试策略请求，因此「最多尝试 N 次」是每个模型各自的上限，
 * 不是整个任务共享的上限。
 *
 * 候选全部失败时抛出最后一次的失败原因，原来的报错入口照旧生效。
 * 用户取消原样抛出，不会当成失败去换模型。
 */
internal suspend fun <R> runWithModelFallback(
    candidates: List<Model>,
    onCandidateFailed: (Model, Throwable) -> Unit = { _, _ -> },
    attempt: suspend (Model) -> R?,
): R {
    require(candidates.isNotEmpty()) { "runWithModelFallback needs at least one candidate" }

    var lastFailure: Throwable? = null
    candidates.forEach { model ->
        try {
            val result = attempt(model)
            if (result != null) return result
            lastFailure = IllegalStateException(
                "Model ${model.displayName.ifBlank { model.modelId }} returned no text"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            lastFailure = error
        }
        onCandidateFailed(model, lastFailure)
    }

    throw lastFailure ?: IllegalStateException("No model available")
}
