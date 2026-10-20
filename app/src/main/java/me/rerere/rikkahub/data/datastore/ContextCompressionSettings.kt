package me.rerere.rikkahub.data.datastore

const val MAX_CONTEXT_TOKENS = 9_999_999

/** 阈值为 0 时关闭自动压缩，但仍保留合法的压缩目标。 */
fun validateContextCompressionSettings(triggerTokens: Int, targetTokens: Int): String? = when {
    triggerTokens !in 0..MAX_CONTEXT_TOKENS -> "自动压缩触发阈值必须为 0～9999999，0 表示关闭自动压缩"
    targetTokens !in 1..MAX_CONTEXT_TOKENS -> "自动压缩目标必须为 1～9999999，不能为 0"
    triggerTokens > 0 && targetTokens >= triggerTokens -> "开启自动压缩时，压缩目标必须小于触发阈值"
    else -> null
}
