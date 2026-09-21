package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/**
 * 把 JSON 里漏写转义的英文引号补上。
 *
 * 模型有时会把内容里的引号原样写进参数，比如 content 写成 `挂名"高级测试"`，
 * 这样整段参数都解析不了。这里从左往右扫一遍，只在「这个引号后面接不上合法 JSON」
 * 的时候，才把它当成内容、补上反斜杠。本来就合法的 JSON 原样返回。
 */
fun repairUnescapedQuotes(input: String): String {
    val output = StringBuilder(input.length + 16)
    // 记住每一层是对象还是数组：对象里逗号后面得跟键，数组里跟值
    val containers = ArrayDeque<Char>()
    var inString = false
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (inString && char == '\\') {
            // 转义过的整对抄过去，别让反斜杠后面的引号被当成字符串结束
            output.append(char)
            if (index + 1 < input.length) output.append(input[index + 1])
            index += 2
            continue
        }
        when (char) {
            '"' -> when {
                !inString -> {
                    inString = true
                    output.append(char)
                }

                endsStringValue(input, index, containers.lastOrNull()) -> {
                    inString = false
                    output.append(char)
                }

                else -> output.append('\\').append('"')
            }

            '{' -> {
                if (!inString) containers.addLast('{')
                output.append(char)
            }

            '[' -> {
                if (!inString) containers.addLast('[')
                output.append(char)
            }

            '}', ']' -> {
                if (!inString) containers.removeLastOrNull()
                output.append(char)
            }

            else -> output.append(char)
        }
        index++
    }
    return output.toString()
}

/**
 * 引号后面跟 }、] 或 : 才算结束。跟逗号时看后面接不接得上下一个字段：
 * 对象里要求逗号后面是「键」开头，数组里要求是「值」开头。接不上，
 * 就说明这个引号其实是内容的一部分。
 */
private fun endsStringValue(input: String, quoteIndex: Int, container: Char?): Boolean {
    var next = quoteIndex + 1
    while (next < input.length && input[next].isWhitespace()) next++
    if (next >= input.length) return true
    return when (input[next]) {
        '}', ']', ':' -> true
        ',' -> if (container == '{') startsKey(input, next + 1) else startsValue(input, next + 1)
        else -> false
    }
}

/** 逗号后面是不是一个值的开头。 */
private fun startsValue(input: String, from: Int): Boolean {
    var next = from
    while (next < input.length && input[next].isWhitespace()) next++
    if (next >= input.length) return true
    return when (val char = input[next]) {
        '"', '{', '[', '-', 't', 'f', 'n' -> true
        else -> char in '0'..'9'
    }
}

/** 对象里逗号后面得是一个键，也就是 "名字": 这个形状。 */
private fun startsKey(input: String, from: Int): Boolean {
    var next = from
    while (next < input.length && input[next].isWhitespace()) next++
    if (next >= input.length) return true
    if (input[next] != '"') return false
    next++
    while (next < input.length && input[next] != '"') next++
    if (next >= input.length) return true // 键还没写完，当作键的收尾
    next++
    while (next < input.length && input[next].isWhitespace()) next++
    if (next >= input.length) return true
    return input[next] == ':'
}
