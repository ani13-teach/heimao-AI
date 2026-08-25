# Prompt Cache Optimization 实施规划

## 1. 目标与范围

将当前提示词构建和 Provider 缓存参数收敛为：

- 一个统一的逻辑提示词结构。
- 两个 Provider 无关的逻辑缓存边界：`HISTORY_END`、`MEMORY_END`。
- Claude 映射为两个 explicit `cache_control` 断点。
- OpenAI 首版只使用稳定的 `prompt_cache_key`，依赖 implicit caching。
- Gemini 首版不参与请求构建侧缓存控制，只保持语义顺序并读取 usage。
- 一个统一的缓存用量模型，区分 cache read、cache write 和普通输入 token。

本阶段不实现通用 `CachePlanner`，不根据收益自动选择断点，也不为当前工具循环增加第三个断点。

## 2. 最终逻辑结构

所有 Provider 先接收同一套逻辑顺序：

```text
Tool Definitions
System
Historical Conversation
----------------------- Breakpoint A: HISTORY_END

Transient Context (optional)
Memory
----------------------- Breakpoint B: MEMORY_END

Latest User
Current Tool Loop...
```

其中：

- `Tool Definitions + System + History` 是第一段稳定、可追加的前缀。
- `Memory` 是第二个显式缓存边界；`Transient Context` 必须位于 `Memory` 前。
- `Latest User` 后只追加本轮生成的 `tool_call` 和 `tool_result`。
- 历史工具调用及其结果保留在历史时间线中，不重新排序或提取到其他区域。
- Provider Adapter 只负责把逻辑结构转换为目标 API 的合法 wire format。

## 3. 第一阶段：重构 RequestBuilder

### 3.1 统一内部模型

在 AI 请求构建层引入等价于以下定义的内部结构。具体类型应复用现有 `Message`、内容部件和工具定义类型，不重复创建 Provider 专属消息模型：

```kotlin
interface LogicalPrompt {
    val tools: List<ToolDefinition>
    val system: SystemContent
    val history: List<Message>
    val transientContext: Content?
    val memory: Content?
    val latestUser: Message
}

enum class CacheBoundary {
    HISTORY_END,
    MEMORY_END,
}
```

如现有代码使用 `data class` 或 sealed 类型更符合项目风格，可采用等价实现；关键是保持上述字段语义，不要求最终 JSON 与该结构一致。

### 3.2 RequestBuilder 必须保证的规则

1. Tool definitions 顺序稳定、可预测。
2. System 内容在构建过程中保持确定，不被历史或 memory 插入逻辑改写。
3. History 严格按照时间顺序输出。
4. 历史 `tool_call`/`tool_result` 原样保留在历史时间线中。
5. `transientContext`（如果存在）位于 `memory` 前。
6. `memory`（如果存在）永远位于 `latestUser` 前。
7. `latestUser` 后面只允许出现当前轮产生的 tool loop。
8. 没有 memory 时不生成 `MEMORY_END` 的逻辑边界。

### 3.3 不引入的抽象

首版不要引入以下概念：

- `StableMemory`
- `DynamicMemory`
- `ToolLoopTail`
- `CacheCandidate`
- `CacheStrategy`
- 通用 `CachePlanner`

这些概念会把 Provider 无关的顺序表达和 Provider 具体的缓存收益判断混在一起，当前需求不需要它们。

### 3.4 搜索结果的归类

RequestBuilder 必须区分两种搜索材料：

- **模型主动搜索**：模型发起 tool call，应用返回 tool result。它属于当前 tool loop，追加在 `latestUser` 后。
- **应用预取搜索**：应用在请求模型前完成 RAG、Web Search 或 Document Retrieval。它作为 `transientContext`，位于 History 与 Memory 之间。

无论是哪种搜索材料，都不能破坏 `Memory` 位于最新用户消息前的规则。

## 4. 第二阶段：Claude explicit breakpoints

### 4.1 总体策略

Claude 首版关闭 automatic caching，只使用两个 explicit breakpoint：

- `HISTORY_END`：缓存 tools、system 和 historical conversation。
- `MEMORY_END`：缓存 transient context 和 memory。

逻辑映射：

```text
Tools + System + History -> HISTORY_END
Transient + Memory       -> MEMORY_END
Latest User              -> no breakpoint
```

TTL 继续复用现有 Claude 配置能力；本计划不扩大 TTL 选项，也不把 TTL 判断混入 RequestBuilder。

### 4.2 `applyCacheBoundary`

在 Claude Adapter 内实现小型、局部的辅助函数：

```kotlin
applyCacheBoundary(request, CacheBoundary.HISTORY_END)
applyCacheBoundary(request, CacheBoundary.MEMORY_END)
```

职责仅限于：

1. 找到指定逻辑区域最后一个 Claude 允许添加 `cache_control` 的 content block。
2. 跳过 thinking/reasoning block 以及协议不允许添加 `cache_control` 的 block。
3. 找不到合法 block 时静默不添加该断点，并返回明确的 applied/skipped 结果供调试输出。
4. 只增加 `cache_control`，不修改消息角色、文本、工具调用参数、工具结果或 thinking 元数据。
5. `MEMORY_END` 在 memory 为空时直接跳过，不退化到 latest user。

Claude wire format 不是简单的统一消息数组，因此边界定位必须在 Adapter 对已生成的 Claude request 上完成，不能让 RequestBuilder 直接写入 Claude 专属 block 字段。

### 4.3 工具循环行为

历史工具调用归入 History，并允许 `HISTORY_END` 在其后设置断点：

```text
User 1
Assistant tool_call
Tool result
Assistant final
---------------- Breakpoint A
Memory
---------------- Breakpoint B
User 2
```

当前轮首次请求：

```text
History + A + Memory + B + User 2
```

模型返回 tool call 后，下一次请求追加本轮 assistant tool call 和 tool result：

```text
History + A + Memory + B + User 2 + Assistant tool_call + Tool result
```

不新增第三个 breakpoint。当前工具循环尾部没有显式缓存是首版已接受的成本；只有真实 usage 显示该成本显著时，后续再单独规划 ToolLoop breakpoint。

## 5. 第三阶段：UsageNormalizer

与 Claude 第一阶段同步建立统一用量模型：

```kotlin
data class CacheUsage(
    val totalInputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val provider: Provider,
    val model: String,
    val raw: ProviderUsage? = null,
)
```

实际类型可根据现有 usage 类型调整，但必须保留以下语义：

- `totalInputTokens`：服务商报告的总输入 token。
- `cacheReadTokens`：本次从缓存读取的 token。
- `cacheWriteTokens`：本次写入缓存的 token。
- `provider` 和 `model`：用于比较和诊断。
- `raw`：仅用于调试；默认不要求持久化。

Provider 字段映射：

- Claude：`input_tokens`、`cache_read_input_tokens`、`cache_creation_input_tokens`。
- OpenAI：Responses 或 Chat Completions 对应的 input/prompt token 与 cached token 字段；兼容现有中转站字段映射。
- Gemini：输入 token 与 `cachedContentTokenCount`。

现有 `TokenUsage.cachedTokens` 等业务展示字段可以继续保留作为兼容层；不要在首版同时让 UI 直接依赖多个 Provider 专属 usage 类型。归一化完成后，再决定是否将 read/write 细分持久化到消息或统计数据库。

### 5.1 调试输出

增加可控的调试输出，示例：

```text
[PromptCache]
provider: claude
model: claude-xxx
input:       18,421
cache read:  13,022
cache write: 2,941
historyBreakpoint: applied
memoryBreakpoint: applied
```

`historyBreakpoint`/`memoryBreakpoint` 只表示请求构建时断点是否成功应用，不能称为 cache hit。实际 hit 必须来自服务商 usage，例如 `cacheReadTokens > 0`。

## 6. 第四阶段：OpenAI

OpenAI 首版不映射 `HISTORY_END` 或 `MEMORY_END`，只使用 implicit caching 和稳定的 `prompt_cache_key`：

```text
LogicalPrompt
    -> OpenAI Adapter
    -> normal Responses/Chat format + prompt_cache_key
```

推荐生成方式：

```text
prompt_cache_key = "chat_" + sha256(appId + conversationId)
```

最低要求：

- 同一会话的 key 稳定。
- 不同会话隔离。
- 不包含 turn id、request id、timestamp 等每轮变化的字段。
- 不直接暴露原始内部 conversation ID。

关闭优化时不发送 `prompt_cache_key`。是否仍发生供应商内部 implicit caching 不由应用控制，也不在首版承诺范围内。

首版先观察 `cached_tokens`/等价 usage，以及 memory 变化对 prefix 复用的影响。只有数据证明 implicit caching 不足时，才规划 OpenAI 的 explicit breakpoint 映射。

## 7. 第五阶段：Gemini

Gemini 首版保持正常请求格式：

```text
LogicalPrompt -> Gemini Adapter -> normal Gemini request
```

只保证 system、history、transient、memory、latest user 的最终语义顺序稳定，并读取 Gemini 返回的 cached token usage。

首版不要：

- 创建 explicit context cache。
- 在应用侧计算 Gemini breakpoint。
- 引入 Gemini cache key。
- 增加特殊缓存生命周期管理。

## 8. 配置语义

全局配置：

```text
promptCacheEnabled: Boolean
```

Provider 行为：

| Provider | 开启 | 关闭 |
| --- | --- | --- |
| Claude | 添加 explicit A+B；继续使用现有 TTL | 不发送 `cache_control` |
| OpenAI | 发送稳定 `prompt_cache_key`，依赖 implicit caching | 不发送 `prompt_cache_key` |
| Gemini | 请求格式不变 | 请求格式不变 |

UI 文案建议使用“缓存优化”或 `Prompt Cache Optimization`，不要使用会让用户误以为可以完全控制供应商后台缓存的“缓存开关”。关闭的准确语义是：应用不主动添加缓存相关参数。

现有 Claude 专属 prompt caching 设置需要在重构后与全局开关保持一致，避免出现全局关闭但 Claude 仍写入 `cache_control` 的路径。

## 9. 测试矩阵

第一批测试覆盖以下 8 个场景：

| 场景 | 验证重点 |
| --- | --- |
| 单轮普通聊天 | 请求合法，字段顺序正确 |
| 连续 5 轮聊天 | History A 正常向后增长 |
| Memory 不变 | B 结构稳定，具备复用机会 |
| Memory 每轮变化 | A 仍可复用，Memory 不错误并入 History |
| 无 Memory | 不生成 B，不把 B 放到 latest user |
| 当前轮 5 次 tool loop | 追加顺序正确，未生成第三个断点 |
| 历史包含 tool results | tool call/result 不移动，A 可正常设置 |
| transient 搜索材料 | 位于 History 与 Memory 之间 |

Claude 额外覆盖：

- 含 thinking/reasoning block 的请求。
- 最后一个 block 不合法时向前查找合法 block。
- 区域内没有合法 block 时不添加断点且请求仍可发送。
- memory 为空时不生成 `MEMORY_END`。
- 验证返回 usage 中 read/write 字段能正确归一化。

建议优先在 `ai` 模块为 LogicalPrompt、Claude boundary 定位和 UsageNormalizer 编写 JVM 单元测试，再执行 Provider 层的请求快照/解析测试。

## 10. 实施顺序

1. 盘点并拆分现有 Prompt 构建路径，明确 RequestBuilder 与 Provider Adapter 的边界。
2. 重构 RequestBuilder，引入固定的 `LogicalPrompt` 顺序。
3. 增加 `CacheBoundary.HISTORY_END` 和 `CacheBoundary.MEMORY_END` 两个逻辑边界。
4. 实现 Claude `applyCacheBoundary`，落地 explicit A+B。
5. 实现 `UsageNormalizer`，并接入 Claude usage 调试输出。
6. 完成 Claude 普通聊天、thinking 和 tool loop 测试。
7. 为 OpenAI 加入稳定的 `prompt_cache_key`。
8. 确认 Gemini 只保持纯 implicit caching 和稳定语义顺序。
9. 收集真实的 cache read/write usage，区分“断点已应用”和“实际命中”。
10. 根据数据决定是否为 OpenAI 增加 explicit A/B；本阶段不预先实现。

## 11. 验收标准

- 所有 Provider 的请求都由同一套 LogicalPrompt 语义顺序生成。
- 历史消息和历史工具调用结果没有被缓存逻辑重排或丢失。
- Claude 最多存在两个 explicit breakpoint；无 memory 时不存在 B。
- 当前 tool loop 不增加第三个 breakpoint。
- OpenAI 首版只发送稳定 session-scoped `prompt_cache_key`。
- Gemini 请求格式和缓存生命周期保持不变。
- Usage 能分别展示 input、cache read、cache write，并保留 Provider/model 维度。
- 日志不会把 breakpoint applied 误报为 cache hit。
- 全局“缓存优化”关闭时，应用不主动发送缓存控制参数。
- 完成 `ai` 模块相关单元测试，并通过项目既有编译/测试命令。

## 12. 明确的后续决策点

以下事项留到真实 usage 数据之后，不进入首发实现：

- OpenAI 是否值得增加 explicit A/B 映射。
- 当前工具循环是否需要第三个临时断点。
- 是否需要把 cache read/write 细分持久化到消息和统计数据库。
- 是否需要针对不同模型或上下文长度调整断点策略。

首发缓存模块保持如下结构：

```text
Prompt
├── RequestBuilder
│   ├── History End
│   └── Memory End
│
├── ClaudeAdapter
│   └── explicit breakpoint x 2
│
├── OpenAIAdapter
│   └── prompt_cache_key
│
├── GeminiAdapter
│   └── nothing
│
└── UsageNormalizer
```
