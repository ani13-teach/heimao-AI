# 黑猫 AI 缓存设计思路

## 1. 文档目的

本文给出黑猫 AI Android 项目的统一缓存设计思路。重点不是“把所有数据都缓存起来”，而是回答四个问题：

1. 哪些结果值得复用；
2. 缓存放在哪里；
3. 数据变化后怎样避免读到旧结果；
4. 怎样证明缓存真的节省了时间、流量或模型费用。

本文同时记录当前实现和后续建议。标注为“现状”的内容已经能在代码中找到；标注为“建议”的内容是后续演进方向，不代表已经完成。

## 2. 先划清边界

项目中的数据可以分为三类：

| 类型 | 例子 | 是否可直接清除 | 定位 |
| --- | --- | --- | --- |
| 业务真数据 | Room 中的会话、消息、记忆、工作区记录；`filesDir` 中的附件、图片和工作区文件 | 否 | 唯一可信来源 |
| 可重建缓存 | OCR 结果、网页渲染内容、正则编译结果、历史总结上下文 | 是，但可能需要重新计算或重新请求模型 | 性能优化 |
| 临时中间文件 | 归档打包目录、恢复解密文件、分享导出文件 | 是，前提是没有事务或恢复日志正在使用 | 一次性工作区 |

最重要的原则是：**Room、DataStore、附件目录和 workspace 不是缓存。** 缓存丢失只能让操作变慢，不能导致业务数据丢失、助手串数据或工作区无法恢复。

## 3. 设计目标

缓存设计按以下优先级取舍：

1. **正确性优先**：宁可少命中一次，也不能把旧记忆、旧会话分支或其他助手的数据发给模型。
2. **隔离优先**：缓存键必须包含助手、会话、模型或工作区等必要作用域。
3. **可重建**：本地缓存损坏、过期或被系统清理后，应用仍能从真数据恢复。
4. **小范围失效**：一条记忆变化时，只让必要的后缀失效，不重建整个提示词前缀。
5. **容量有上限**：内存缓存按条目数限制，文件缓存按时间和空间限制。
6. **效果可观测**：区分“尝试使用缓存”和“实际命中缓存”。
7. **不缓存秘密**：API Key、授权头、备份密码、解密明文和完整请求日志不得进入普通缓存。

## 4. 总体分层

```text
界面和业务调用
    │
    ├─ L0：单次调用复用
    │      同一个请求内去重、同键并发合并
    │
    ├─ L1：进程内缓存
    │      正则编译、余额短缓存、记忆快照状态
    │
    ├─ L2：本地可重建缓存
    │      OCR、WebView 内容、图片库缓存
    │
    ├─ L3：持久化派生结果
    │      历史会话总结上下文
    │
    └─ L4：模型服务商提示词缓存
           Claude 显式断点、OpenAI 会话键、用量统计

真数据层（不属于缓存）
    ├─ Room：会话、消息、记忆、workspace 元数据和快照索引
    ├─ DataStore：用户设置
    └─ filesDir：附件、图片、workspace、归档和快照文件
```

读取顺序采用“由近到远”：先看进程内，再看本地文件，最后读取真数据或访问网络。写入顺序则相反：**先成功写入真数据，再更新或失效缓存。**

## 5. 当前缓存地图

| 场景 | 当前实现 | 缓存键与生命周期 | 现状评价 |
| --- | --- | --- | --- |
| 正则编译 | `app/.../data/model/Assistant.kt` + `utils/CacheUtil.kt` | 正则文本；10 分钟 | 能避免流式输出反复编译；编译失败也短期缓存，属于合理的负缓存 |
| Provider 余额 | `ui/components/ai/ProviderBalanceText.kt` | Provider ID + 余额配置；2 分钟 | 不缓存错误结果，方向正确 |
| OCR | `data/ai/transformers/OcrTransformer.kt` + `common/cache` | 当前使用图片 URL；64 条、3 天、持久化到 `cacheDir/ocr_cache.json` | 有容量和过期控制，但同一路径内容变化时可能误命中 |
| WebView 内容 | `ui/components/webview/WebViewContentCache.kt` | 内容 SHA-256；最近访问后保留 7 天 | 内容寻址、路径校验和去重较完整；尚无总空间上限 |
| 图片加载 | `RouteActivity.kt` 中 Coil + OkHttp + `CacheControlCacheStrategy` | 由图片请求与 HTTP 缓存头决定 | 已接入缓存策略；项目未统一展示容量、命中率和清理入口 |
| 历史总结上下文 | `ChatService.kt`、`ConversationEntity.summaryContext` | 绑定总结会话及来源列表；持久化 | 能避免重复压缩大段历史；来源后续编辑后的失效信息还不够显式 |
| 记忆提示词快照 | `GenerationHandler.kt` | 会话/助手作用域；进程内 LRU，最多 32 个状态 | 只用于优化提示词构造，Room 仍是真数据；重启丢失是正常行为 |
| Claude 提示词缓存 | `ai/provider/Prompt.kt`、`ClaudeProvider.kt` | `SYSTEM_END`、`MEMORY_END`、`HISTORY_END`；TTL 由 Provider 设置 | 已按稳定前缀和可变后缀拆分，并支持记忆 Base/Patch |
| OpenAI 提示词缓存 | `PromptCacheKey.forSession`、OpenAI Provider | 会话 ID 经 SHA-256 生成稳定键 | 不直接暴露内部会话 ID，不同会话隔离 |
| 缓存用量 | `ai/core/Usage.kt` | Provider + Model；记录 read/write token | 已统一读取和写入 token 语义，旧 `cachedTokens` 保留兼容 |
| Key 轮询状态 | `ai/util/KeyRoulette.kt` | Provider ID + API Key；24 小时 | 文件实际包含 Key，不应继续作为普通可清理缓存看待，应迁移到受保护状态存储 |
| 归档/恢复中间物 | `WorkspaceArchiveService.kt`、`WorkspaceArchiveCleanup.kt` | 随事务创建，启动后安全清理 | 是临时文件，不是可复用缓存；必须服从恢复日志，不能粗暴全删 |

## 6. 核心缓存策略

### 6.1 进程内缓存

适合体积小、计算频繁、进程结束后无需保留的对象，例如编译后的正则、短时余额和记忆快照状态。

建议统一满足：

- 同时支持 TTL（存活时间）和最大条目数；
- 缓存键是不可变值，不引用可变对象的临时 `hashCode`；
- 修改相关设置时主动失效，不只等待超时；
- 对昂贵操作提供“同键单航班”：多个并发请求只计算一次，其他请求等待同一个结果；
- 只对确定性失败做很短的负缓存，例如非法正则；网络超时和鉴权失败不缓存。

当前 `SimpleCache` 有 TTL，但没有容量上限。建议后续逐步收敛到 `common/cache/LruCache.kt` 或增加 `maximumSize`，避免键种类持续增长时占满内存。

### 6.2 本地文件缓存

适合结果较大、重算较贵、跨进程仍有复用价值的数据，例如 OCR 文本和 WebView HTML。

每类文件缓存都应登记以下元数据：

```text
namespace        缓存类别，例如 ocr、webview
key              稳定键或内容哈希
createdAt        创建时间
lastAccessAt     最近访问时间
expiresAt        过期时间
sizeBytes        文件大小
schemaVersion    缓存格式版本
producerVersion  生成算法、模型或提示词版本
sensitivity      普通、隐私、禁止落盘
```

文件写入采用“临时文件 + 原子替换”，避免应用被杀后留下半个 JSON。文件名只使用受控前缀或哈希，不直接拼接 URL、会话标题和用户输入。

#### OCR 键建议

当前 OCR 以 `part.url` 为键。更稳妥的键应为：

```text
sha256(
    图片内容指纹
    + OCR Provider ID
    + 模型 ID
    + OCR 提示词版本
    + 输出格式版本
)
```

这样即使文件路径没变，只要图片内容、模型或提示词变化，就不会返回旧结果。图片很大时可以先组合规范路径、文件大小、修改时间；对正确性要求高的入口再计算内容 SHA-256。

#### WebView 内容建议

现有内容 SHA-256 方案可以保留。后续补充：

- 单类空间上限；
- 启动或后台空闲时批量清理，而不是每次写入都扫描整个目录；
- 文件读取失败时删除损坏项；
- 手动“清理缓存”入口。

### 6.3 持久化派生缓存：历史总结上下文

`summaryContext` 是由来源会话计算得到的派生结果。它可以持久化，但不能只靠“字段非空”判断有效。

建议把有效性描述为一个明确版本：

```text
SummaryCacheVersion = sha256(
    来源助手 ID
    + 按稳定顺序排列的来源会话 ID
    + 每个来源当前可见分支的内容版本
    + 总结模型 ID
    + 总结提示词版本
    + 分块算法版本
)
```

读取流程：

1. 校验来源会话仍全部属于指定助手；
2. 计算轻量版本，命中后直接使用 `summaryContext`；
3. 未命中时读取完整来源并重新生成；
4. 生成结束后再次读取版本；
5. 前后版本一致才写入缓存，否则本次结果只用于当前请求，不长期保存。

当前代码已经在首次构建前后比较消息节点内容版本，能防住准备期间的大部分并发变化。下一步重点是把来源版本和生成器版本随缓存一起持久化，使来源在以后被编辑、切换分支或更换总结模型时也能明确失效。

### 6.4 模型提示词缓存

模型提示词缓存与本地文件缓存不同：实际缓存保存在服务商或中转站，应用主要负责让请求前缀稳定，并读取服务商返回的用量。

逻辑结构保持：

```text
稳定工具定义 + 稳定系统提示词       -> SYSTEM_END
记忆 Base + 追加 Patch              -> MEMORY_END
历史消息（持续向后增长）            -> HISTORY_END
临时上下文 + 本轮记忆变化 + 最新用户 -> 动态尾部，不主动缓存
当前工具循环                         -> 动态尾部
```

关键约束：

- 历史工具调用和工具结果保持原时间线，不为缓存而重排；
- 临时搜索材料、历史总结上下文不提升为 system 指令；
- 一条记忆变化先作为动态 delta 发送一次，成功后再提交到稳定 Base/Patch；
- 提交失败、取消或会话状态已变化时，不推进快照；
- Claude 断点“已添加”不等于命中，只有 `cacheReadTokens > 0` 才算实际命中；
- OpenAI 的 `prompt_cache_key` 按会话稳定，不包含轮次、时间戳和请求 ID；
- Google 当前只读取服务商返回的缓存用量，不由应用虚构显式缓存能力。

提示词可能包含聊天、记忆和工具定义。全局“缓存优化”关闭时，应用不主动发送缓存控制参数；界面文案不应暗示应用能删除服务商后台缓存。

## 7. 失效规则

| 变化事件 | 必须失效或换键的缓存 |
| --- | --- |
| 助手系统提示词、工具配置变化 | 对应提示词 `SYSTEM_END` 之后的复用；相关会话下一次请求自然换前缀 |
| 新增、编辑、删除记忆 | 进程内记忆快照的相应会话/助手状态；Claude 只追加最小 Patch |
| 切换助手或记忆作用域 | 必须使用新的助手/全局作用域键，禁止复用旧作用域 |
| 来源会话内容或当前分支变化 | 对应 `summaryContext` |
| 总结模型、提示词或分块算法变化 | 对应 `summaryContext` |
| OCR 图片内容、模型或提示词变化 | OCR 缓存 |
| Provider 余额配置变化 | 余额缓存 |
| WebView 内容变化 | 内容哈希自然变化，无需全量清理 |
| 用户退出、导入备份或恢复 workspace | 清空相关进程内缓存；恢复完成后从新 Room/DataStore 状态重建 |
| 缓存格式升级 | 旧 `schemaVersion` 项统一视为未命中并安全删除 |

禁止使用“删除整个 `cacheDir`”作为通用失效手段。归档恢复可能正在使用带事务含义的中间文件，清理必须经过 `WorkspaceArchiveCleanup` 的安全规则。

## 8. 容量与清理

建议建立统一的 `CacheRegistry`，每类缓存自行登记，不要求把所有实现改成同一个大类：

```kotlin
data class CachePolicy(
    val namespace: String,
    val maxBytes: Long?,
    val maxEntries: Int?,
    val ttlMillis: Long?,
    val clearOnLogout: Boolean,
    val containsSensitiveData: Boolean,
)
```

清理顺序：

1. 先删过期项；
2. 再按最近最少使用删除；
3. 优先清理可快速重建、体积大的内容；
4. 不碰 Room、DataStore、附件、图片真数据、workspace、快照和恢复日志；
5. 归档/恢复中间物走专用事务清理；
6. 用户手动清理时显示“会清什么”和“不会清什么”。

建议提供三种入口：

- 启动后的低优先级清理；
- 系统低存储回调触发的紧急清理；
- 设置页中的手动清理及各类别占用展示。

## 9. 并发与一致性

缓存最容易出错的地方不是读取，而是“算完准备写回时，原数据已经变了”。统一采用以下模式：

```text
读取真数据版本 V1
    -> 计算结果
    -> 再读取版本 V2
    -> V1 == V2：写缓存
    -> V1 != V2：放弃写缓存，必要时只用于当前请求
```

另外要求：

- 同一个键的昂贵任务使用单航班，避免同时发出多次 OCR 或总结请求；
- 文件缓存使用进程内锁和原子替换；
- 删除与写入竞争时，以新真数据版本为准；
- 取消的协程不能提交新快照；
- 导入、恢复和数据库交换完成后，统一递增一个应用数据代次，使旧进程缓存整体失效。

## 10. 安全与隐私

1. 不把 API Key、Authorization、Cookie、备份密码写入普通缓存和调试日志。
2. `lru_key_roulette.json` 当前会保存 API Key 使用时间，建议迁移为只存 Key 指纹，并放入受保护的状态存储。
3. OCR、WebView 和总结缓存可能包含用户内容，应只放应用私有目录，不进入备份交付物和 Git。
4. 缓存文件名使用哈希；读取时校验格式、规范路径并拒绝 `..`、符号链接逃逸和相邻目录前缀。
5. 远端提示词缓存需要显式设置入口和说明，让用户知道聊天内容可能被服务商短期缓存。
6. 调试信息只记录缓存键指纹、大小、耗时和 token 数，不记录完整提示词与响应正文。

## 11. 观测指标

建议按缓存类别记录以下指标，默认只保存在本机调试统计中：

- `hit`、`miss`、`expired`、`evicted`、`corrupted`；
- 条目数、磁盘字节数、单项最大值；
- 计算前后耗时、节省的网络请求数；
- 提示词 `inputTokens`、`cacheReadTokens`、`cacheWriteTokens`；
- Claude 各断点 applied/skipped 原因；
- 因版本变化放弃写回的次数；
- 清理成功、失败和被恢复事务保护的数量。

命中率只是参考，不能单独作为目标。一个命中率很高但长期返回旧总结的缓存，比完全没有缓存更糟。

## 12. 测试策略

### 单元测试

- 同键命中、TTL 过期、容量淘汰和主动失效；
- 并发同键只执行一次计算；
- 缓存文件损坏后按未命中处理；
- OCR 的图片、模型和提示词变化会换键；
- WebView 哈希去重和非法 ID 拒绝；
- 总结来源内容、分支、模型变化会失效；
- Prompt 各区域顺序及 Claude 断点位置；
- 取消、失败和旧版本提交不能推进记忆快照。

### 集成测试

- 进程重启后本地缓存可读取，内存缓存能安全重建；
- 导入/恢复后不读取旧进程缓存；
- 低空间清理不删除真数据和恢复事务文件；
- 两个助手、两个会话、全局/助手记忆之间没有串缓存；
- Provider 返回的 cache read/write 字段能正确归一化并展示。

### 真实链路验证

抓取脱敏后的请求结构，连续发送相同前缀，验证：

- 相同前缀哈希稳定；
- 记忆小改动只重建最小后缀；
- `cacheReadTokens` 在后续请求中出现；
- Provider 路由不稳定时，报告为服务端缓存可见性波动，不误判为应用命中。

## 13. 分阶段落地建议

### 第一阶段：补齐规则，不大改架构

- 建立缓存清单和命名空间；
- 为 `SimpleCache` 增加最大条目数，或迁移到已有 LRU；
- 给 OCR 键加入内容、模型和提示词版本；
- 给 `summaryContext` 增加明确版本字段；
- 补“清理缓存”入口，先覆盖 OCR、WebView 和分享/导出临时文件。

### 第二阶段：统一生命周期

- 引入 `CacheRegistry` 和各类别容量统计；
- 增加低存储清理和格式版本升级；
- 为昂贵任务加入单航班；
- 把 Key 轮询记录迁出普通 `cacheDir`。

### 第三阶段：用数据优化

- 根据真实命中率、耗时和 token 费用调整 TTL 与容量；
- 判断是否需要新的 Provider 缓存策略；
- 只有数据证明收益明显时，才增加新的提示词断点或更复杂的缓存规划器。

## 14. 相关代码与文档

- `app/src/main/java/me/rerere/rikkahub/utils/CacheUtil.kt`
- `common/src/main/java/me/rerere/common/cache/`
- `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/webview/WebViewContentCache.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- `ai/src/main/java/me/rerere/ai/provider/Prompt.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt`
- `ai/src/main/java/me/rerere/ai/core/Usage.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/WorkspaceArchiveCleanup.kt`
- `docs/prompt-cache-optimization-plan.md`
- `docs/MEMORY_PROMPT_CACHE_CAPTURE_REPORT.md`

## 15. 一句话总结

黑猫 AI 的缓存应当像“可随时重做的草稿纸”：能减少重复工作，但绝不能替代 Room、文件和 workspace 这些正式账本；每张草稿都要知道属于谁、何时过期、原内容是否变化，以及到底省下了多少时间和模型费用。
