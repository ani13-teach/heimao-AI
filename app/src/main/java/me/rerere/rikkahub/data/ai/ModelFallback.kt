package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 一次后台生成按顺序尝试的模型：主模型在最前，备用模型按用户排的顺序跟在后面。
 *
 * 备用只从主模型所属的那个供应商里取。列表里指向别的供应商、类型不符（由 [accept] 判定）
 * 和重复的条目都会被丢掉，所以用户换了主模型之后，旧的备用不会被误用。
 */
internal fun fallbackCandidates(
    providers: List<ProviderSetting>,
    primaryModel: Model,
    fallbackModelIds: List<Uuid>,
    accept: (Model) -> Boolean,
): List<Model> {
    val owner = providers.firstOrNull { provider ->
        provider.models.any { it.id == primaryModel.id }
    } ?: return listOf(primaryModel)

    val fallbacks = fallbackModelIds
        .mapNotNull { id -> owner.models.firstOrNull { it.id == id } }
        .filter { it.id != primaryModel.id && accept(it) }

    return (listOf(primaryModel) + fallbacks).distinctBy { it.id }
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
