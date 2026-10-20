# 黑猫ai Android Fork 维护交接文档

> **历史记录，不是当前操作手册。** 本文保留旧电脑 `C:/Users/WJZN/` 路径、当时分支/提交、数据库版本、APK 与设备验收结果；后续功能和环境已经变化。当前入口为 [开发与维护指南](PROJECT_GUIDE.md) 与 [文档目录](README.md)。不要照本文旧路径构建、清理或恢复数据，也不要把历史“通过”套到当前源码。本文原有内容保持作为历史证据。

更新时间：2026-08-17 14:45 +0800

## 1. 文档目的

本文用于交接 `C:/Users/WJZN/rikkahub-fork` 中的“黑猫ai” Android fork。它记录：

- 已提交的定制功能和当前工作树内尚未提交的后续改造。
- 已完成的源码精简、性能优化、品牌资源和真机验收结果。
- 历史总结、记忆和全文索引链路的性能结论。
- 兼容性边界、构建环境、设备状态、交付 APK 和剩余外部条件。

第 3 节明确区分已提交基线与工作树改造；第 14 节记录最终真机证据。不得把未提交工作误报为已进入 Git 历史。

## 2. 仓库身份与保护边界

- 可写目标仓库：`C:/Users/WJZN/rikkahub-fork`
- 原并行仓库路径：`C:/Users/WJZN/rikkahub`（已由用户改名，无需恢复或处理；所有工作仍只在本 fork 中进行）
- 当前分支：`master`
- 当前本地提交：`0a4460da5ccf326a53ec44f924ce411a48c4efc9`
- 提交标题：`feat: secure workspace and add conversation summaries`
- `upstream/master`：`0c52b62b377260c483dba8f7b385cdc586b6cf6f`
- `origin/master`：`8dc3ebce3f422a422013097447234e4a97db4210`
- `origin`：`https://github.com/ani13-teach/rikkahub.git`
- `upstream`：`https://github.com/rikkahub/rikkahub.git`

`0a4460da` 尚未推送到 `origin/master`。此前直接推送默认分支被自动模式阻止，需要用户明确授权。

禁止对 `C:/Users/WJZN/rikkahub` 执行写入、构建、清理、回退或补丁套用。不要使用 `reset --hard`、`checkout --`、`restore`、`clean` 或未经授权的 `stash`。

## 3. 已完成并提交的功能

以下内容已经包含在 `0a4460da`：

1. 记忆查询统一按 `id DESC` 返回，最新记忆优先。
2. AI 文件和 Shell 被限制到 `/workspace`，用户手动终端仍保留完整 Rootfs。
3. AI `conversation_search` 只搜索当前助手；全局 App 搜索语义不变。
4. 数据库升级到版本 25，新增永久、全局、单调递增且删除后不复用的会话编号。
5. 历史总结支持日期、永久编号、编号范围、UUID、逐项选择和全选。
6. 总结来源只能属于当前来源助手；总结 AI 可以由用户另选。
7. 总结要求继续使用普通聊天输入框，不存在单独的总结提示词编辑器。
8. 总结来源上下文排除 system、隐藏 reasoning、原始工具输出和附件正文。
9. 大来源支持分块、递归归并、进度、取消、硬上限和缓存。
10. Android、WebUI、构建名称和用户可见文案已改为“黑猫ai”，兼容标识保持不变。

兼容标识包括 `applicationId`、Java/Kotlin 包名、数据库名、深链和备份关键路径。

### 后续精简改造（工作树内，未提交）

以下需求已在工作树中实现并通过编译/单测，尚未提交到 `master`：

- 更新检查、赞助、整个 About 分组与请求日志已从源码、UI、导航、DI、资源、字符串、依赖和 baseline 中移除；`afdian.xml`/`kofi.webp` 等仅赞助页资源一并删除。
- 快捷消息已完整删除（模型、DataStore、迁移、页面/VM、导航、DI、Web DTO/路由、WebUI、字符串、baseline）；新增 `PreferenceStoreV4Migration` 清理旧 `quick_messages` 键与助手内嵌字段。
- SillyTavern 角色卡 PNG/JSON 导入与整个 Lorebook/RegexInjection 模块已删除；ModeInjection 全功能保留；Room 升 26，`Migration_25_26` 重建 `conversationentity` 仅删 `lorebook_ids`，schema 26 与 `Migration_25_26_Test` 已建。
- 语音/TTS/ASR 与 `:speech` 模块已完整删除（权限、DI、本地工具、DataStore、字符串、baseline）；新增 `PreferenceStoreV5Migration` 清理旧语音键与助手 `"tts"` 本地工具。
- 本地 Ktor Web server、`:web` 模块与 `web-ui` 已删除；Android `WebViewPage`（Mermaid/代码/内容预览）保留。
- 启动图标已用 `C:/Users/WJZN/Pictures/微信图片_2026-08-16_004201_862.jpg` 替换，生成 legacy 各密度、Adaptive 分层与主题单色图层；源图已复制到 `app/launcher-source/ic_launcher_source.jpg`；`Export.kt` 聊天导出 Logo 已解耦为 `R.drawable.chat_export_logo`；通知 `R.drawable.small_icon` 未改动。
- 修复了上游 `VersionTest.kt` 引用缺失 `Version` 类的既有编译阻断，新增 `Version` SemVer 实现。
- 新增 Debug APK：`C:/Users/WJZN/HeimaoAI-2.4.9-debug-universal.apk`（见第 10 节哈希）。

## 4. 用户最新确认的待实施需求

> 状态（2026-08）：以下 1-9 全部已在工作树实现并通过编译/单测/构建验证，见第 3 节“后续精简改造”；设备端验收待 `2508cb7b` 连接后执行（第 11.3/11.4 节）。

用户要求从源码完整移除，而不是只隐藏 UI：

1. 更新检查、更新提示和应用内更新下载。
2. “支持开发者”弹窗。
3. 扩展管理中的快捷消息功能。
4. 语音服务，包括 TTS 和 ASR。
5. 本地 Web 服务器。
6. 整个“关于”分组，包括：
   - 关于本 App
   - 使用文档
   - 请求日志
   - 赞助
   - 分享本 App
7. 删除新增助手中的 SillyTavern 角色卡 PNG/JSON 导入，并删除整个世界书/Lorebook 模块，包括创建、编辑、绑定、运行时注入、导入和导出。
8. 使用 `C:/Users/WJZN/Pictures/微信图片_2026-08-16_004201_862.jpg` 替换黑猫ai的 Android 桌面启动图标，并完整适配 Adaptive Icon、传统密度资源和主题单色图标。
9. 排查并优化历史总结、记忆和会话索引引起的卡顿。

范围解释：

- “语音服务”按完整删除 TTS、ASR、麦克风输入、朗读、自动播放和 AI TTS 本地工具处理。
- “Web 服务器”指 Ktor/WebUI 本地服务；用于代码、Mermaid 等内容展示的 Android `WebViewPage` 应保留。
- “分享”只移除关于分组中的“分享本 App”；聊天导出分享、文件分享和提供商配置分享应保留。
- “请求日志”应移除请求日志 UI、动态请求正文采集和 OkHttp 请求头日志；普通内部错误日志可保留。
- “SillyTavern 角色卡导入”包括新增助手中的 PNG 和 JSON 两种格式，UI、解析器、图标和独占依赖全部删除。
- “整个世界书/Lorebook 模块”包括手动创建、编辑、删除、排序、助手/会话绑定、关键词/正则扫描、运行时提示词注入、导入、导出、数据字段、资源和测试，不保留任何世界书能力。
- Mode Injection 是独立的固定提示词注入功能，继续保留其创建、编辑、助手/会话绑定、运行时注入、导入和导出。
- “桌面启动图标”只替换 `@mipmap/ic_launcher` 及其 Adaptive Icon 图层；通知栏小图标必须继续使用符合 Android 规范的单色 `R.drawable.small_icon`。

## 5. 卡顿调查的技术结论

### 5.1 不存在全库 Embedding

当前总结、记忆和历史搜索没有调用 Embedding API。`generateEmbedding()` 只有提供商能力实现，没有 App 侧调用方。

必须区分三种行为：

- Embedding：没有对全部记忆或全部会话生成向量。
- JSON 编解码：存在，尤其是读取选中的完整会话和构造记忆提示词。
- FTS 分词索引：存在，使用 SQLite FTS5 `simple` tokenizer，不是向量索引。

### 5.2 记忆链路

当助手启用记忆时，`ChatService` 每次生成都会读取以下两者之一：

- 当前助手的全部记忆；或
- 全局全部记忆。

`GenerationPrompts.buildMemoryPrompt()` 会把这些记忆整体编码为 JSON，附加到 system prompt。该过程不调用 Embedding，但会增加：

- 本地字符串和 JSON 构造成本。
- HTTP 请求体积。
- 服务端 token 化和模型首字延迟。
- 每次后续请求的重复 token 成本。

当前只改变了记忆顺序，没有限制条数或总字符数。

### 5.3 历史页链路

`HistoryVM` 使用 `ConversationRepository.getConversationsOfAssistant()`。DAO 当前执行 `SELECT *` 并一次返回当前助手的全部会话，没有分页。

消息正文已经拆分存储在 `message_node` 表中。历史列表路径明确传入空节点，所以不会反序列化全部聊天消息。此前“历史页会解码全部 nodes”的判断不准确。

仍然存在的问题：

- `SELECT *` 会读取 `summary_context`、建议、提示词和其他列表不需要的字段。
- `summary_context` 单条最多可接近 80,000 字符。
- 所有会话一次进入 Flow 和 Compose 列表，数据量大时仍有分配和排序成本。
- 总结功能新增的大文本列放大了旧有 `SELECT *` 查询的成本。

优先改为 `LightConversationEntity` 元数据投影，并为历史页使用 Paging。

### 5.4 总结来源链路

选择总结来源时，创建总结会话只验证元数据，不加载完整消息。

第一次在总结会话中发送消息时，`ChatService.ensureSummaryContext()` 会：

1. 按来源 ID 加载全部被选中的完整会话。
2. 逐会话读取 `message_node`。
3. 反序列化每个选中会话的消息 JSON。
4. 在内存中构造完整来源字符串。
5. 构造完成后才检查 2,000,000 字符硬上限。
6. 小于等于 80,000 字符时直接缓存原始来源；更大时调用总结 AI 分块归并。

因此，用户选择“全部”时，确实可能一次处理该助手全部历史。2,000,000 字符上限检查得太晚，无法限制加载和解码阶段的峰值。

缓存后的 `summary_context` 会作为隐藏 user 上下文附加到该总结会话的后续每次请求。接近 80,000 字符时，会持续增加请求体积和首字延迟。

### 5.5 “参考历史聊天记录”与记忆的关系

“参考历史聊天记录”虽然位于助手记忆设置页，但它和 `MemoryEntity` 记忆是两套独立机制：

- 记忆功能读取 `MemoryEntity`，并在启用时把记忆内容写入模型提示词。
- 历史聊天参考使用本地 SQLite FTS5 全文索引，向模型提供按需调用的工具。
- `Assistant.enableMemory` 关闭时，`Assistant.enableRecentChatsReference` 仍可单独开启。
- 开启历史聊天参考不会自动读取、编码或上传全部历史，只有模型主动调用工具时才执行查询。
- 该能力要求模型支持 Tool Call；不支持工具调用的模型无法使用。

开关位于 `AssistantMemoryPage.kt`。`ChatService` 在每次生成前检查 `assistant.enableRecentChatsReference`，开启时通过 `createConversationTools(conversationRepo, assistant.id)` 注册 `recent_chats` 和 `conversation_search`。

关闭该开关只会停止向模型暴露这两个工具。FTS 索引仍会维护，因为全局 App 消息搜索也使用同一张 `message_fts` 表。

### 5.6 两个历史聊天工具的当前逻辑

#### 5.6.1 `recent_chats`

用途：让模型先查看当前助手最近有哪些会话，再决定是否搜索正文。

参数和排序：

- 默认返回 10 个会话。
- 最少 1 个，最多 30 个。
- 只查询当前助手。
- 按置顶优先、会话更新时间倒序排列。

返回给模型的字段只有：

```json
{
  "id": "会话UUID",
  "title": "会话标题",
  "last_chat": "日期"
}
```

当前实现存在明显无效开销：`ConversationDAO.getRecentConversationsOfAssistant()` 执行 `SELECT *`，随后 `ConversationRepository.getRecentConversations()` 对每个结果调用 `loadMessageNodes()`，读取并反序列化完整 `message_node`。工具最终却只使用 ID、标题和日期。

因此，调用 `recent_chats` 时会产生与返回内容无关的完整会话读取；被选中的近期会话越长，延迟和内存分配越明显。

#### 5.6.2 `conversation_search`

用途：让模型用关键词搜索当前助手历史消息。例如：

```json
{
  "query": "耦合机 调试进度",
  "limit": 15
}
```

当前查询行为：

- 使用 SQLite FTS5 搜索消息文本。
- 通过 `jieba_query()` 处理中文查询词。
- 通过关联 `conversationentity.assistant_id` 限制为当前助手。
- 默认请求 15 条，工具参数最多允许 50 条。
- 使用 `MessageSearchSort.RELEVANCE`，按 FTS rank 后接会话更新时间倒序。
- 不读取完整会话，只返回命中片段。

返回给模型的字段：

```json
{
  "conversation_id": "会话UUID",
  "title": "会话标题",
  "snippet": "之前完成了[耦合机]的二次[调试]……",
  "date": "日期"
}
```

模型实际获得的是局部 snippet，不是完整历史消息。`MessageSearchResult` 内部还包含 `nodeId` 和 `messageId`，但当前 Tool payload 没有返回这两个字段，也没有返回永久会话编号。

全局 App 搜索继续调用无 `assistant_id` 限制的 `searchMessages()`；AI 工具调用 `searchMessagesOfAssistant()`。不得为了优化 AI 工具改变全局搜索范围。

### 5.7 FTS 索引内容和生命周期

`message_fts` 是在数据库打开时创建的 FTS5 虚拟表，使用 `simple` tokenizer，并加载本地 jieba 词典。

每条消息只索引：

- `UIMessagePart.Text` 文本。
- 每条消息最多前 10,000 个字符。
- `node_id`、`message_id`、`conversation_id`、标题和会话更新时间作为附加字段。

不会索引：

- 图片和 Base64 内容。
- 附件正文。
- 非 Text 类型的 reasoning/tool part。
- 向量或 Embedding。
- 会话标题本身；标题当前只是 UNINDEXED 返回字段，不参与 `MATCH`。

每次会话正式插入或更新时，`MessageFtsManager.indexConversation()` 会：

1. 删除该会话原有的全部 FTS 行。
2. 遍历该会话全部节点和消息。
3. 提取每条消息的文本。
4. 对每条非空消息执行一次 FTS INSERT。

流式 token 更新只修改内存状态，不会每个 token 都写索引。但生成完成、标题生成、建议生成、消息编辑和其他正式保存可能多次触发整会话索引。

当前正文保存事务与 FTS 重建相互分离，FTS 删除和逐条 INSERT 本身也没有组成明确的批量事务。如果 App 在两者之间中断，正文和索引可能暂时不一致。全局搜索页提供“重建消息索引”，会清空 `message_fts`，逐会话读取完整节点并重新建立索引。

FTS 全量重建是上游已有行为，不是当前助手范围搜索新增的。助手隔离改动只增加了基于 `conversationentity.assistant_id` 的所有权过滤。

### 5.8 历史聊天搜索的已知问题

1. `recent_chats` 为返回标题和日期读取了完整消息节点，是最直接的无效开销。
2. FTS 更新采用整会话删除和重建，长会话保存成本随消息量增加。
3. FTS 删除和逐条 INSERT 没有明确批量事务，写入效率和一致性不足。
4. SQL 固定 `LIMIT 50`，随后 Kotlin 才 `.take(limit)`；模型只请求少量结果时仍可能读取 50 行。
5. 当前工具没有传入当前会话 ID，搜索结果可能命中正在聊天的会话。
6. 同一会话多条消息命中时会返回多条结果，可能挤占其他会话的结果位置。
7. 工具只有 snippet，没有读取命中消息前后文的能力，模型可能基于残缺片段判断。
8. FTS 是关键词检索，不理解同义词、改写和语义相似性。
9. `MessageFtsManager` 通过 `Log.i` 输出原始搜索关键词，存在不必要的 Logcat 隐私暴露。
10. 索引更新失败时没有自动修复或待重试状态，只能依赖后续保存或手动重建。
11. 标题不参与搜索，用户只记得会话标题时可能无法命中。
12. Tool payload 丢弃了 node/message ID，无法安全、精确地继续读取命中位置。

### 5.9 历史聊天搜索优化方案

#### 第一阶段：低风险性能和正确性修复

- 为 `recent_chats` 新增 `LightConversationEntity` 元数据 DAO 查询，只读取 ID、标题、更新时间和永久编号，完全不加载 `message_node`。
- 将工具的实际 `limit` 作为 SQL bind 参数传入，移除固定查询 50 条后再裁剪的行为。
- 拒绝空白关键词，限制查询词长度，并移除原始关键词 Logcat。
- 将当前 `conversationId` 传入 `createConversationTools()`，搜索 SQL 排除当前会话。
- 将 FTS 删除和批量插入放进一个数据库事务，复用 prepared statement。
- Tool payload 返回永久会话编号、node ID 和 message ID。
- 每个会话限制命中数量，优先覆盖更多不同会话。
- 添加当前助手隔离、当前会话排除、SQL limit、空查询和去重测试。

#### 第二阶段：增加受控的上下文读取

新增 `conversation_read` 工具：

1. 模型先调用 `conversation_search` 获得会话、节点和消息定位信息。
2. 再按 `conversation_id`、`node_id` 或 `message_id` 读取命中位置前后少量消息。
3. 服务端再次验证该会话属于当前助手。
4. 排除 system、隐藏 reasoning、原始工具输出和附件正文。
5. 限制消息窗口、单条字符和返回总字符数。
6. 禁止使用该工具一次读取完整会话或全部历史。

该设计比直接扩大 snippet 更可靠，也能控制发送给模型的上下文和隐私范围。

#### 第三阶段：可选的语义检索

先完成本地 FTS 优化，不立即引入 Embedding。当前 FTS 具有离线、无额外 API 成本、不上传全库历史的优势。只有在确认关键词检索无法满足同义词和语义召回需求后，再单独评估本地向量或明确授权的远程 Embedding，并且不能默认对全部历史批量编码。

### 5.10 总体性能改造顺序

按优先级实施：

1. 修复 `recent_chats` 的完整会话读取。
2. 将 FTS 重建改为事务化批量写入，并进一步评估增量或延迟重建。
3. 历史页使用轻量投影和 Paging，不读取 `summary_context`。
4. 总结来源在反序列化前执行数据库长度预检。
5. 按会话和节点分批读取并立即归并，避免一次持有全部 `Conversation`。
6. 将总结字符上限改为与模型上下文相关的 token 预算。
7. 降低直接总结上下文上限，避免每次重复发送接近 80,000 字符。
8. 记忆增加“最新 N 条”和总字符上限，同时保持最新优先。
9. 添加合成大数据量的设备基准，分别测量 recent chats、FTS 保存、历史页、总结准备、首字和最终保存时间。

综合判断：历史聊天搜索值得保留，但需要优化。最高优先级是 `recent_chats` 轻量化和 FTS 事务化写入；语义搜索不应先于这些基础修复。

## 6. 源码精简边界

### 6.1 更新检查

完整移除：

- `UpdateChecker.kt`
- `UpdateCard.kt`
- `ChatVM.updateState`
- `ChatDrawer` 更新卡片和恢复计时
- Koin `UpdateChecker` 注册与注入
- 更新暂停设置和多语言资源
- `PlayStoreUtil` 与对应 Compose hook
- `VersionTest`
- baseline profile 中的失效描述符

移除后仍需保留 `INTERNET` 权限，因为模型、同步、MCP 和搜索仍依赖网络。

### 6.2 支持开发者与赞助

完整移除：

- `SettingPage` 中启动次数触发的支持开发者弹窗
- `sponsorAlertDismissedAt` 设置字段和调试页输入
- `SettingDonatePage`
- `SponsorAPI`
- `Sponsor` 数据模型
- Koin Sponsor API 注册
- 赞助和弹窗多语言资源

`launchCount` 仍被统计页使用，不应因为删除弹窗而直接删除。

### 6.3 快捷消息

该功能不只存在于扩展管理页。完整移除需要覆盖：

- `QuickMessagesPage`、`QuickMessagesVM` 和导航 Screen
- `QuickMessage` 数据模型
- `Settings.quickMessages`、`Assistant.quickMessageIds` 和 DataStore key
- `ChatInput` 快捷消息按钮
- `ExtensionSelector`、`ExtensionContent` 和助手扩展配置
- `FilesPicker` 管理入口
- Web Settings routes 中的快捷消息校验（Web 服务器整体删除后自然消失）
- V3 快捷消息迁移和导入迁移逻辑
- 多语言资源与 baseline profile 描述符

旧设置中的未知字段可以被 `JsonInstant.ignoreUnknownKeys` 忽略。不要因为删除功能而让旧助手 JSON 整体解码失败。

### 6.4 语音服务

完整移除包括：

- 整个 `:speech` Gradle 模块
- `SettingSpeechPage`、TTS/ASR provider 配置组件和导航
- `TTSManager` Koin 注册
- 根 Compose 中的 `rememberCustomTtsState()`、`rememberCustomAsrState()` 和 CompositionLocal
- `TTSController`、`TTSAutoPlay`、消息朗读操作和麦克风按钮
- `TextToSpeechTool`、对应 Tool UI 和 `LocalTools` 的 TTS 依赖
- TTS/ASR DataStore keys、Settings 字段、默认提供商和选择 helper
- ASR 开始/停止音频资源
- `RECORD_AUDIO` 权限（确认无其他录音功能后）
- 语音相关多语言资源、测试和 baseline profile 描述符

兼容性重点：旧助手的 `localTools` 可能包含多态类型 `tts`。直接删除 `LocalToolOption.Tts` 可能让旧助手配置反序列化失败。应先在 `SettingsJsonMigrator` 中过滤 `tts`，并为本地升级和备份导入添加测试。

### 6.5 Web 服务器

完整移除包括：

- 整个 `:web` Gradle 模块和 `web-ui`
- App 对 `project(":web")` 的依赖
- `WebServerManager`、`WebServerService`、`WebApiModule` 和所有 server routes
- `NsdServiceRegistrar`
- `SettingWebPage`、导航和设置入口
- Web server DataStore keys 与 Settings 字段
- App 启动时 `startWebServerIfEnabled()`
- Web server 通知渠道和 Manifest service
- 仅 Web server 使用的 Ktor server、JWT、jmDNS、SLF4J 依赖
- 确认无其他用途后移除 `CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_LOCAL_NETWORK` 和相关前台服务权限
- Web server 多语言资源、CI/build 脚本和 baseline profile 描述符

不要删除 `ui/pages/webview/WebViewPage.kt`。它用于 App 内内容展示，不属于本地 Web 服务器。

### 6.6 关于模块与请求日志

删除 `SettingPage` 中整个 `aboutSettings` CardGroup，并移除：

- `SettingAboutPage` 和导航
- 文档 URL 入口
- `LogPage` 和导航
- `SettingDonatePage` 和导航
- “分享本 App” Intent
- QQ/Discord 社区入口及仅由此使用的图标/helper
- 对应多语言资源和 baseline profile 描述符

请求日志应从执行路径移除：

- 删除 `RequestLoggingInterceptor`
- 删除 OkHttp `HttpLoggingInterceptor.Level.HEADERS`
- 删除 `LogEntry.RequestLog`、请求日志开关和请求正文缓存
- 保留仍被错误处理使用的 `Logging.log()`/`TextLog`，或改为普通 Logcat

聊天导出分享、文件分享、Android 分享导入和提供商配置分享不属于关于模块，不应删除。

### 6.7 SillyTavern 角色卡与整个世界书模块

角色卡导入删除可行性很高、风险低。整个世界书模块删除可行，但属于中等风险的跨模块改造，因为世界书同时进入 Settings、Assistant、Conversation、Room schema、提示词转换器和多个扩展 UI。

角色卡 PNG 和 JSON 两条入口共用同一解析流程：

1. `AssistantPage` 的新增助手面板显示 `AssistantImporter`。
2. `AssistantImporter.kt` 提供 PNG/JSON 两个文件选择按钮。
3. JSON 路径读取 `chara_card_v2` 或 `chara_card_v3`。
4. PNG 路径通过 `ImageUtils.getTavernCharacterMeta()` 读取 PNG `tEXt` 中的 Base64 `chara` 数据，再走相同 JSON 解析器。
5. 解析结果转换为普通 `Assistant`。

角色卡完整删除应覆盖：

- 删除 `AssistantPage` 对 `AssistantImporter` 的 import、调用和支持栏 UI。
- 删除整个 `ui/pages/assistant/detail/AssistantImporter.kt`。
- 删除 `ImageUtils.getTavernCharacterMeta()` 及 `com.drew` PNG metadata imports。
- 删除 App 的 `libs.metadata.extractor` 依赖。
- 删除 `gradle/libs.versions.toml` 中 `metadataExtractor` 版本和 `metadata-extractor` alias。
- 删除 `assistant_importer_*` 的全部语言资源。
- 删除 `assets/icons/tavern.png`。
- 删除 `AIIconMatcher` 中 `PATTERN_TAVERN` 分支和正则。

整个世界书/Lorebook 模块删除应覆盖：

- 删除 `PromptPage` 的 Lorebook 标签、`LorebookTab`、卡片、编辑页、添加、删除、排序、导入和导出 UI。
- 删除 `AssistantExtensionsPage` 的 Lorebook 标签和助手世界书绑定 UI。
- 删除 `ExtensionSelector` 的 Lorebook 标签和会话世界书绑定 UI。
- 删除 `ExtensionContent.LorebooksContent`，并将 `FilesPicker` 的扩展计数改为只统计保留功能。
- 删除 `Lorebook`、`PromptInjection.RegexInjection` 和 `RegexInjection.isTriggered()` 等世界书模型/匹配逻辑。
- 删除 `Settings.lorebooks`、`PreferencesStore.LOREBOOKS` 的读取、清洗和写入。
- 删除 `Assistant.lorebookIds` 和所有复制、校验、导入兼容代码。
- 删除 `Conversation.lorebookIds`、`ConversationEntity.lorebookIds` 和 Repository 编解码。
- 删除 `PromptInjectionTransformer` 中世界书过滤、关键词/正则扫描和注入逻辑，只保留 Mode Injection。
- 删除 `GenerationHandler`、`TransformerContext`、`ChatService` 中 `conversationLorebookIds` 参数和传递。
- 删除 Web DTO/routes 中的 `lorebookIds`；Web 服务器整体删除后这些代码也会消失。
- 删除 `LorebookSerializer`、SillyTavern fallback、`SillyTavernLorebook`、`SillyTavernEntry` 和位置映射。
- 删除全部世界书多语言资源和 baseline profile 失效描述符。
- 删除 `PromptInjectionTransformerTest` 中所有 Lorebook/RegexInjection 测试，保留并调整 Mode Injection 测试。

数据库与旧数据：

- Room 必须升级到版本 26，并新增 `Migration_25_26` 删除 `conversationentity.lorebook_ids`。
- 为兼容旧 SQLite/Android 版本，优先采用重建 `conversationentity` 表并复制保留列的迁移方式，不依赖 `ALTER TABLE DROP COLUMN`。
- 迁移必须保留会话 ID、永久编号、消息节点关联、助手 ID、总结字段、文件夹和其他非世界书字段。
- 新 schema JSON、迁移注册和 `Migration_25_26_Test` 必须同步提交。
- DataStore 中旧 `lorebooks` key 可以停止读取和写入；遗留 key 可留在设备上成为无效数据，不需要破坏性清理。
- 旧 Assistant/Settings JSON 中的 `lorebookIds`/`lorebooks` 依靠 `ignoreUnknownKeys` 忽略，并增加旧备份导入测试。
- 已有世界书及助手/会话绑定会按需求失去功能；不得连带删除普通会话或助手。

保留边界：

- 已从角色卡导入的助手已经是普通 `Assistant`，继续可用、编辑、备份和恢复。
- 已复制的角色卡 PNG 背景继续作为普通助手背景文件保留。
- 普通 PNG/JSON 附件、背景图、应用备份和文件导入不受影响。
- `PromptInjection.ModeInjection`、`Settings.modeInjections`、助手/会话 Mode Injection 绑定及其导入/导出继续保留。
- `allowConversationPromptInjection` 若仍控制会话级 Mode Injection，应保留字段并更新文案，去掉世界书描述。

性能影响：角色卡导入不是常驻开销。世界书则会在生成链路中读取 Settings、筛选绑定项并扫描最近消息做关键词/正则匹配；完整删除后可减少每次模型请求前的本地扫描，尤其是世界书较多或条目较大时。但它仍不能替代第 5 节的总结、记忆和 FTS 优化。

验收重点：源码、资源、Room schema 和 APK 中不再包含 `Lorebook`、`RegexInjection`、`lorebookIds`、`lorebooks`、`tryImportSillyTavern`、角色卡 V2/V3 解析器、`chara_card_v2`、`chara_card_v3`、`assistant_importer_*`、PNG `chara` 元数据读取和 Tavern 图标；Mode Injection 必须继续工作。

### 6.8 Android 桌面启动图标

指定源图片：`C:/Users/WJZN/Pictures/微信图片_2026-08-16_004201_862.jpg`。

图片检查结果：

- JPEG，`940 x 940`，RGB，正方形。
- 文件大小 `130921` bytes。
- 带 ICC 色彩配置，无透明通道，无 EXIF 条目和旋转信息。
- 黑猫主体居中，黑色猫、星空和白色翅膀与“黑猫ai”品牌一致。
- 猫耳、翅膀和尾部靠近画面边缘，直接覆盖资源会在圆形、水滴形和圆角方形启动器遮罩下产生裁切。
- 画面细节较多，缩小到 mdpi `48 x 48` 时星点、首饰和羽毛细节会丢失，但黑猫与白色翅膀的主轮廓仍可辨认。

当前 App 状态：

- Manifest 使用 `android:icon="@mipmap/ic_launcher"`，未单独声明 `roundIcon`。
- `minSdk = 26`，所有受支持设备都可使用 Adaptive Icon。
- `mipmap-anydpi-v26/ic_launcher.xml` 由 background、foreground、monochrome 三层组成。
- 五档 legacy 图标尺寸为 `48/72/96/144/192`。
- 五档 Adaptive Icon 图层尺寸为 `108/162/216/324/432`。
- `WorkspaceDocumentsProvider` 也使用 `R.mipmap.ic_launcher`，会自然显示新图标。
- `Export.kt` 已改用独立的 `R.drawable.chat_export_logo` 作为聊天图片 Logo，不再复用 Adaptive Icon foreground；最终真机导出 PNG 已验证猫图和“黑猫ai”页眉。
- 通知使用独立的 `R.drawable.small_icon`，不能换成彩色照片。

实施要求：

1. 将源图复制到项目内稳定、可追溯的位置，例如 `design/heimao_launcher_source.jpg`；Gradle 资源不能依赖用户 Pictures 目录。
2. 转换到 sRGB，并用无 EXIF 的 PNG 生成最终 Android 资源。
3. 为 legacy `ic_launcher.png` 制作居中方形版本，保留黑猫脸、双耳和白色翅膀的识别轮廓。
4. 为 Adaptive Icon 单独制作 background 和 foreground，不直接把同一张完整 JPG 同时放入两层。
5. 将猫脸、双耳和主要身体轮廓放在 Adaptive Icon 安全区内；外围星空和翼尖可以作为可裁切区域。
6. `ic_launcher_monochrome` 使用简化的高对比度猫/翅膀轮廓，不使用照片灰度图或高频星点阈值图，保证 Android 13+ 主题图标清晰。
7. 重新生成 mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 的 legacy/background/foreground/monochrome 全部资源，不能只替换单一密度。
8. 保持 `mipmap-anydpi-v26/ic_launcher.xml` 三层引用有效；如新增 `roundIcon`，必须指向同一套自适应资源，不能出现两种品牌图标。
9. 将聊天图片导出的 Logo 改用独立 `drawable` 资源或明确的完整图标资源，不再直接复用 Adaptive Icon foreground。
10. 保留通知栏 `small_icon.xml`、快捷方式相机图标和其他功能图标。
11. 在纳入公开分发前确认用户拥有该图片的使用和再分发权。

验证要求：

- `aapt dump badging` 仍显示正确包名、版本和 `黑猫ai` 标签。
- APK 中五档 mipmap 资源完整，Adaptive Icon XML 无缺失引用。
- 在 Android 15 设备 `2508cb7b` 安装后，分别检查桌面、应用抽屉、最近任务页和系统设置应用列表。
- 在启动器圆形/圆角方形遮罩下，猫脸和双耳不能被裁掉，白色翅膀至少保留主要轮廓。
- 开启和关闭 Android 主题图标后都应可辨认为黑猫ai；单色模式不能显示成噪点或空白。
- 检查 mdpi 缩略预览，避免只有星点、猫脸过暗或主体缩成无法辨认的小图。
- 工作区 DocumentsProvider 根目录显示新启动图标。
- 通知栏仍显示合法的单色小图标，不能出现白色方块。
- 聊天图片导出 Logo 不因 Adaptive Icon 的 108dp 留白而异常缩小或被裁切。

该改动不涉及数据库、DataStore、包名或签名兼容性。Android Launcher 可能缓存旧图标，安装验证时必要时卸载 Debug 包或清除启动器缓存；不能把缓存误判为资源替换失败。

## 7. 数据兼容策略

- 删除 UI 功能通常不需要 Room 迁移，但完整删除世界书是例外：必须用 Room 26 移除 `conversationentity.lorebook_ids`。
- DataStore 中旧的 primitive/string key 可以停止读写并留作无效数据，不要为物理清理旧 key 引入破坏性启动迁移。
- `JsonInstant` 已启用 `ignoreUnknownKeys`，删除普通 JSON 字段通常可兼容旧备份。
- 多态 sealed class/enum 的旧值不一定能靠 `ignoreUnknownKeys` 安全跳过，TTS 本地工具必须显式迁移。
- 世界书 `RegexInjection` 位于已停止读取的 `lorebooks` 数据中；旧 Assistant 的普通 `lorebookIds` 字段可以忽略，但必须用旧备份测试验证。
- 快捷消息旧字段应从 Assistant 和 Settings 导入路径中安全忽略或过滤。
- 性能优化优先使用 DAO 投影和查询调整；Room 26 由世界书字段删除承担，不要重复升级版本。
- 若性能优化还新增物化总结或长度列，应合并进同一 Room 26 schema 和迁移测试。

## 8. 推荐实施顺序

不要把所有删除项堆在一个不可审阅的大改动中。建议按以下顺序：

1. 建立总结/历史/保存性能基准和计时点。
2. 优化历史轻量查询、总结分批读取、记忆上限和 FTS 保存。
3. 移除更新检查、支持开发者弹窗和关于模块。
4. 移除快捷消息，并完成旧设置/备份兼容测试。
5. 移除角色卡 PNG/JSON 导入和整个世界书模块，完成 Room 26 迁移，同时保留 Mode Injection。
6. 移除语音服务和 `:speech`，完成多态配置迁移。
7. 移除 Web 服务器、`:web`、`web-ui` 和相关依赖/权限。
8. 生成并替换黑猫ai的完整 Android launcher/adaptive/monochrome 图标资源，同时解除聊天导出对 adaptive foreground 的复用。
9. 清理所有语言资源和两份 baseline profile 中的失效描述符。
10. 运行完整编译、单测、设备测试、APK 构建和安装冒烟测试。

每一步保持独立 diff 和验证结果。未经用户明确要求，不提交或推送。

## 9. 设备状态

当前 ADB 设备：

- serial：`2508cb7b`
- model：`PKG110`
- Android：15
- API：35
- ABI：`arm64-v8a`
- `su -c id`：`uid=0(root)`

设备上：

- 已 Root 安装 `me.rerere.rikkahub.debug` 2.4.9，versionCode 176；可与正式包并行安装。
- Debug 包曾临时授予 `POST_NOTIFICATIONS` 以完成受控 instrumentation 验证，现已恢复原始拒绝状态；`R.drawable.small_icon` 真机证据已保留。
- `SmokeWorkspace` 已从 Ubuntu 官方 URL 安装 24.04.3 arm64 Rootfs，Shell 状态为“就绪”。
- 人工终端显示 `root@localhost:/workspace#`，`pwd=/workspace`、`uid=0(root)`，并可列出 `/root`、`/etc` 与 `/workspace`，证明人工终端仍拥有完整 Rootfs。
- 为持续验收，设备 `screen_off_timeout` 已从原 `1800000` 改为 `2147483647`，并通过 `svc power stayon true` 设置 `mStayOn=true`、`mStayOnWhilePluggedInSetting=15`。需要恢复时将超时改回 `1800000` 并关闭 stay-on。

## 10. 构建环境与现有交付物

受保护 SDK 中的 NDK 头文件损坏，不得修复或覆盖。使用独立干净 NDK：

`C:/Users/WJZN/android-sdk-clean/ndk/28.2.13676358`

被 Git 忽略的 `local.properties` 当前包含：

- `sdk.dir=C\:/Users/WJZN/trime-voice/tools/android-sdk`
- `ndk.dir=C\:/Users/WJZN/android-sdk-clean/ndk/28.2.13676358`

Gradle 在 Windows 上必须串行运行：`--no-daemon --max-workers=1`。

现有最终 APK（本轮真机验收及导出/Mermaid 品牌修复后）：

- 路径：`C:/Users/WJZN/HeimaoAI-2.4.9-debug-universal.apk`
- 大小：`85844129` bytes
- SHA-256：`6bb9a8c33578143796c84a46a066dd19df56624d9961d83e93511911cb071933`
- 包名：`me.rerere.rikkahub.debug`
- 版本：2.4.9 / 176
- 标签：`黑猫ai`
- ABI：`arm64-v8a`、`x86_64`
- 签名：APK Signature Scheme v2 验证通过，Android Debug 证书

旧哈希 `52dd773da20fadd39767051e0c16436de924fdb5ca267397eecb9887bf70c385` 和中间哈希 `24267d5d57e1b51121c7ec3f1319120c9ca6631b2268814fd1a93da89ca75a37` 均已作废。最终 APK 已确认不含 web 资产、`web-ui`、`speech`，包含 adaptive/legacy/单色图标、`chat_export_logo`、离线 Markdown fallback 和 Android 15 PRoot 修复。任何生产源码变化后必须重新构建、覆盖交付路径并更新哈希。

维护 Skill：

- 活跃目录：`C:/Users/WJZN/.agents/skills/heimao-ai-maintenance`
- 包：`C:/Users/WJZN/heimao-ai-maintenance.skill`
- Skill SHA-256：`d0417003c95a3ffd40714a935c06475cb92d3e418e15e0208706a8c60651de21`（2026-09-10 重新打包：4 个文件，19590 字节，新增 `references/dependency-sources.md` 依赖来源与仓库地址）
- 作废的旧包哈希：`df5548f5fbf8c4cfa8b0599ce3070ec432d521d826b9201a98802120ac9ca8da`（2026-08-16 打包，内含旧版 SKILL.md；备份在 `C:/Users/WJZN/heimao-AI/evidence/heimao-ai-maintenance.skill.20260816.bak`）

## 11. 验证要求

### 11.1 静态检查

- `git diff --check`
- 搜索确认被删除入口、类、域名、资源 key 和 baseline profile 描述符归零。
- 检查 launcher 各密度图片尺寸、色彩模式、透明通道和 Adaptive Icon XML 引用。
- 确认项目内保存了可复现生成的图标源图，不依赖 `C:/Users/WJZN/Pictures` 的运行时路径。
- 确认没有 APK、Google 配置、SDK 路径、密钥或 `local.properties` 进入 Git。
- 确认 `C:/Users/WJZN/rikkahub` 未发生变化。

### 11.2 JVM 和编译

至少运行：

```text
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest -x :app:processDebugGoogleServices
./gradlew --no-daemon --max-workers=1 :ai:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 :workspace:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 :app:compileDebugKotlin
./gradlew --no-daemon --max-workers=1 :app:compileDebugAndroidTestKotlin -x :app:processDebugGoogleServices
./gradlew --no-daemon --max-workers=1 :workspace:compileDebugAndroidTestKotlin
```

### 11.3 已连接设备

设备可用时运行：

- `Migration_24_25_Test`
- 新增的 `Migration_25_26_Test`，验证移除 `lorebook_ids` 后其他会话字段和 `message_node` 仍完整
- `ConversationSummaryDAOTest`
- `MemoryDAOTest`
- `ProotWorkspaceOnlyInstrumentedTest`
- 新增的旧 TTS/快捷消息设置迁移测试
- 新增的总结大数据量性能测试

性能测试至少覆盖：

- 100、1000 和大量会话的历史页元数据加载。
- 选择少量来源和全选来源的总结准备。
- 大量记忆时请求体构造。
- 长对话最终保存和 FTS 更新。
- 总结会话第一次请求和缓存后第二次请求。

所有数量和耗时统计使用 Python 或设备 benchmark 结果，不凭心算估算。

### 11.4 APK 验收

1. 构建 arm64、x86_64 和 universal Debug APK。
2. 验证 ZIP 完整性、v2 签名、包名、版本号和“黑猫ai”标签。
3. 安装到 `2508cb7b`。
4. 验证桌面、应用抽屉、最近任务、系统设置和主题图标模式均显示新的黑猫翅膀图片。
5. 验证聊天、历史、总结、工作区和备份基本流程。
6. 验证设置中不再出现更新、语音、Web server 和关于分组。
7. 验证扩展和聊天输入中不再出现快捷消息。
8. 验证新增助手面板不再显示 PNG/JSON 酒馆角色卡导入栏，Prompt/助手扩展/会话扩展中不再存在任何世界书 UI。
9. 验证 APK、源码、Room schema 和备份输出中不再包含更新、赞助、Web server、TTS/ASR、请求日志、角色卡解析、`Lorebook`、`RegexInjection` 和 `lorebookIds`。
10. 验证普通助手、普通会话、背景图、PNG/JSON 附件和应用备份仍可用，旧世界书数据不会导致升级或导入崩溃。
11. 验证 Mode Injection 的创建、编辑、助手/会话绑定、运行时注入、JSON 导入和导出仍可用。
12. 验证通知栏小图标和聊天图片导出 Logo 未因 launcher 图标替换发生退化。
13. 验证 Android WebView 的代码、Mermaid 和内容预览仍可用。

## 12. 已知基线与限制

- App Lint 当前有项目既有 error；本次新增代码此前已从 Lint error 列表清零。
- locale 翻译集成测试需要外部服务凭据，没有凭据时会返回 HTTP 401。
- 部分 workspace 全量测试假设 `/bin/sh` 和 Unix 符号链接，在 Windows 上属于环境基线失败。
- WebUI 生产构建此前通过，仅有既有 bundle size 和 sourcemap 警告。
- 旧设备数据库体积不能直接证明另一台设备上新总结功能的卡顿原因。
- 模拟器系统 `/system/bin/sqlite3` 未编译 FTS5 模块，无法经 CLI 查询/写入 `message_fts`（应用自身通过 Android 框架 SQLite 正常使用 FTS5，正确性由 `MessageFtsManagerQueryTest` 验证）；FTS 运行时性能需真实设备或带 FTS5 的宿主验证。

## 13. 当前状态与剩余动作

当前工作树已实现第 4 节全部移除需求与启动图标替换（详见第 3 节），并已通过：

- `git diff --check`（无尾随空白/EOF 问题）。
- `:app:testDebugUnitTest`（全部通过，含 V4/V5 迁移、Transformer、FTS、Version 测试）。
- `:ai:testDebugUnitTest`、`:common:testDebugUnitTest`（全部通过）。
- `:workspace:testDebugUnitTest`：新增的边界测试全部通过；仅 4 个预存 `ExampleUnitTest` 环境基线失败（假定 `/bin/sh`、网络 tar 下载），与本次改动无关。
- `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:workspace:compileDebugAndroidTestKotlin`、`:app:assembleDebug`（universal/arm64/x86_64 均产出）。
- WebUI 已随 `web-ui` 整体删除，不再构建。
- APK 离线验收（11.4 第 1-2 条）：ZIP 完整；v2 签名有效（Android Debug 证书）；包 `me.rerere.rikkahub.debug`；版本 2.4.9/176；标签“黑猫ai”；native-code `arm64-v8a`/`x86_64`；权限无 `RECORD_AUDIO`/Web server 专属权限。

真实设备验收已完成，详见第 14 节。仍需外部条件或用户决定的事项：

1. Provider 支持的实际总结生成需要用户配置有效 Provider 凭据；当前已验证来源选择、独立总结 AI、总结对话创建和 Room 元数据落库。
2. 代表性大规模用户数据上的 FTS/总结端到端性能仍需真实数据与有效 Provider；现有 DAO、边界和模拟器性能测试已通过。
3. 临时数据 `SmokeWorkspace`、`SmokeExportTest` 和总结对话 `#2` 当前保留作为验收证据；删除与否由用户决定。
4. 是否提交 `master` 与推送 `origin/master` 需用户明确授权；默认不提交、不推送。

已知基线不变：Lint 既有 error、locale 401、workspace 4 个 Windows `/bin/sh`/符号链接环境基线。

## 14. 真实设备最终验收（2026-08-17）

设备 `2508cb7b`（PKG110、Android 15、arm64-v8a、KernelSU root）完成以下验证：

- Root 安装 `me.rerere.rikkahub.debug` 2.4.9/176，`RouteActivity` 启动并保持运行，最终 logcat 无 FATAL/ANR。
- Room 完整快照必须同时读取 `rikka_hub`、`rikka_hub-wal`、`rikka_hub-shm`；最终快照位于 `C:/Users/WJZN/HeimaoAI_device_screenshots/rikka_hub-final.db*`，`user_version=26`。
- 真机运行 `Migration_24_25_Test`、`Migration_25_26_Test`、`MemoryDAOTest`、`ConversationSummaryDAOTest` 共 8/8 通过。
- `ProotWorkspaceOnlyInstrumentedTest` 在 arm64 真机与 x86_64 模拟器均 `OK (1 test)`。Android 15 bionic 的 `/proc/self/exe` 执行问题通过每命令 dispatch script 和 `/bin/.rikkahub-applets` 绝对 applet 路径修复，`WORKSPACE_ONLY` 未挂载 `/proc`，仍拒绝 Rootfs 私有路径、外部 bind 和 Android 系统命令。
- 官方 Ubuntu Base `24.04.3 arm64` 从 `cdimage.ubuntu.com` 下载并安装成功，Shell 状态“就绪”。人工终端证据：`pwd=/workspace`、`uid=0(root)`，并可列出 `/root`、`/etc`、`/workspace`，因此人工终端保留完整 Rootfs。
- 主界面、抽屉、历史、设置、备份、扩展、Prompt Injection、Mode Injection、工作区、系统应用信息和 launcher 图标均完成真机截图；未见更新、赞助、About、语音、Web server、快捷消息、SillyTavern 或 Lorebook 入口。
- 离线 Markdown Preview 已修复：`mark.html` 先通过 `atob`、`Uint8Array`、`TextDecoder` 和 `textContent` 安全显示纯文本，再由可选 CDN 模块替换为富 Markdown。`webview-fixed.png` 可见 `SmokeExportTest`，不再空白。
- 聊天图片导出成品 `chat-export-final.png` 为 `1620×552` RGBA PNG，SHA-256 `25a719b25229f6f04c05d7dfc36501c888bddc7adadd893ec41c596418f314cb`，包含独立黑猫 Logo 和“黑猫ai”页眉。普通导出和 Mermaid Canvas 的旧 `rikka-ai.com` 水印均已移除；API 服务 URL/HTTP Referer 因兼容性保留。
- 新增 `NotificationIconInstrumentedTest`：由目标包发布通知，断言 packageName 和 `R.drawable.small_icon` 资源 ID，轮询等待通知进入 `activeNotifications`，默认立即清理，可用 `notificationHoldMs` 短暂保留供截图。未授予 `POST_NOTIFICATIONS` 时测试通过 JUnit Assume 跳过；真机验收由 Root 临时授权后运行，并恢复原始拒绝状态。真机 `OK (1 test)`；通知栏截图 `notification-icon.png` 和 dumpsys 均确认包 `me.rerere.rikkahub.debug`、渠道 `chat_completed`、资源图标 `0x7f070146`。
- 历史页仅列当前助手来源，真实会话显示永久编号 `#1`；可单独选择总结 AI。创建后的“对话总结”保留普通聊天输入框并显示“1 个历史对话”来源横幅。
- 总结 Room 证据：来源会话 `#1` UUID `298de1a3-836f-466c-bcbf-e70ca862d7fc`；总结对话 `#2` UUID `feb9adf1-fe03-453c-83e9-de9b9cd0a50b`；`summary_source_conversation_ids` 精确指向 `#1`；`summary_source_assistant_id` 已落库；`summary_context` 持久化长度为 0。
- 最终 universal APK：`C:/Users/WJZN/HeimaoAI-2.4.9-debug-universal.apk`，`85844129` bytes，SHA-256 `6bb9a8c33578143796c84a46a066dd19df56624d9961d83e93511911cb071933`，v2 签名通过，包/版本/标签/ABI/权限检查通过。

真机截图集中在 `C:/Users/WJZN/HeimaoAI_device_screenshots/`。本轮未提交、未推送。
