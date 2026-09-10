package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.ai.tools.ExactReplacer
import me.rerere.rikkahub.data.ai.tools.LineTrimmedReplacer
import me.rerere.rikkahub.data.ai.tools.TextReplacer

/**
 * 记忆正文的一次局部修改：把唯一命中的 [oldText] 换成 [newText]。
 * [newText] 允许为空串，表示删除命中处。
 */
data class MemoryEdit(
    val oldText: String,
    val newText: String,
)

/**
 * 记忆补丁使用的替换器，按顺序逐级尝试：精确匹配 → 逐行 trim。
 *
 * 刻意不包含 `BlockAnchorReplacer`：首尾行相同就命中的模糊匹配可能把改动贴到错误区块，
 * 而记忆正文会进入之后所有对话的提示词，贴错的代价比文件编辑高得多。
 * 也不提供 replace_all —— 记忆里"全文替换"几乎总是误操作。
 */
internal val MemoryEditReplacers: List<TextReplacer> = listOf(ExactReplacer, LineTrimmedReplacer)

/**
 * 顺序应用记忆补丁，返回替换后的正文。
 *
 * 每个补丁必须在当前正文里唯一命中：无命中和命中多处都抛 [IllegalArgumentException]，
 * 调用方据此拒绝写库，保证失败时记录逐字节不变（含前面的补丁也不生效）。
 * 错误消息面向模型，说明失败原因与下一步动作（重新读取后逐字复制 old_text）。
 */
internal fun applyMemoryEdits(content: String, edits: List<MemoryEdit>): String {
    require(edits.isNotEmpty()) { "edits must not be empty" }
    var current = content
    edits.forEachIndexed { index, edit ->
        require(edit.oldText.isNotEmpty()) { "edits[$index].old_text must not be empty" }
        current = applyMemoryEdit(current, edit, index)
    }
    return current
}

private fun applyMemoryEdit(content: String, edit: MemoryEdit, index: Int): String {
    for (replacer in MemoryEditReplacers) {
        val matches = replacer.findMatches(content, edit.oldText, edit.newText)
        if (matches.isEmpty()) continue
        if (matches.size > 1) {
            throw IllegalArgumentException(
                "edits[$index].old_text matches ${matches.size} places in the record; " +
                    "include more surrounding lines so it matches exactly once"
            )
        }
        val match = matches.first()
        return content.replaceRange(match.start, match.endExclusive, match.replacement)
    }
    throw IllegalArgumentException(
        "edits[$index].old_text was not found in the record; " +
            "re-read the record with memory_read and copy old_text exactly from its current content"
    )
}
