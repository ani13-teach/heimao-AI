# OpenAI 缓存键发送策略升级方案

参考对象：cc-switch（`farion1231/cc-switch`）的 `promptCacheRouting` 设计。
本文先给出可核验的现状，再给升级方案与落地清单。

## 1. 一句话结论

黑猫 AI 现在**对所有 OpenAI 兼容供应商都发 `prompt_cache_key`，而且没有任何界面能把这件事关掉**。
升级要补三件事，按优先级排：

1. 每个供应商上加三态设置（自动 / 开启 / 关闭），并把已经存在但没有界面的全局开关补上。
2. 自动模式只对已确认兼容的上游发这个字段，未知上游默认不发。
3. 真被上游用 400 拒绝且报错点名了这个字段时，自动去掉字段重试一次，并把该供应商记为不支持。

第 3 条是 cc-switch 没有、但黑猫 AI 更需要的一步。原因见 4.3。

## 2. 当前实现（源码位置）

| 位置 | 现在做什么 |
| --- | --- |
| `ai/.../providers/openai/ChatCompletionsAPI.kt:246-248` | `promptCacheEnabled && !promptCacheKey.isNullOrBlank()` 就写入 `prompt_cache_key` |
| `ai/.../providers/openai/ResponseAPI.kt:235-237` | 同一个条件，重复一遍 |
| `ai/.../provider/Provider.kt:245,247` | 参数只有全局布尔 `promptCacheEnabled` 和 `promptCacheKey` |
| `ai/.../provider/Prompt.kt:118-129` | 键 = `"chat_" + sha256("rikkahub" + conversationId)` |
| `app/.../service/ChatService.kt:854` | 每轮对话用 `PromptCacheKey.forSession(conversationId)` |
| `app/.../datastore/PreferencesStore.kt:245,524` | 全局 `promptCacheEnabled` 默认 `true` |
| `app/.../ui/pages/setting/SettingVM.kt:30` | 只做透传，没有页面绑定它 |
| `ai/.../provider/ProviderSetting.kt:54-68` | `ProviderSetting.OpenAI` 没有任何缓存相关字段 |

问题集中在两处：

- **没有界面**。全局开关在数据层存在、默认开，但全项目搜不到任何界面或字符串把它暴露给用户；供应商页也没有对应选项。用户碰到严格网关只能换供应商，改不了这个字段。
- **判定只认全局布尔**。`ProviderSetting.OpenAI` 是所有 OpenAI 兼容供应商共用的配置类型，而 `DefaultProviders.kt` 里内置了 20 多个端点（SiliconFlow、DeepSeek、Moonshot、OpenRouter、火山、智谱、x.ai……），它们现在都会收到这个字段。

## 3. cc-switch 的做法（源码位置）

| 位置 | 做法 |
| --- | --- |
| `src-tauri/src/proxy/providers/codex.rs:87-133` | `should_send_codex_chat_prompt_cache_key`：先看 `meta.promptCacheRouting`，`enabled` 直接真、`disabled` 直接假；`auto` 时解析 base_url 的 host，只有 `api.openai.com` 和 `api.kimi.com/coding` 返回真，其余返回假 |
| 同上注释 | 未知 OpenAI 兼容网关默认关掉，因为很多会因为不认识的字段直接返回 400 |
| `src-tauri/src/proxy/providers/codex.rs:136-159` | `inject_codex_chat_prompt_cache_key`：先判定；键只取显式客户端值，其次真实的客户端会话 ID；两者都没有就不发。注释写明绝不生成随机 UUID，否则每轮落到不同缓存桶，适得其反 |
| `src-tauri/src/proxy/session.rs:100-190,223-229` | 只有客户端自己带来的会话 ID 才标 `client_provided = true`；兜底 `generate_new_session_id()` 是随机 UUID 且标记为 false，所以不会被拿去当缓存键；`previous_response_id` 被明确排除，因为它是每轮变的响应游标 |
| `src-tauri/src/provider.rs:512-516` | 两个字段：`promptCacheKey`（显式覆盖）与 `promptCacheRouting`（三态） |
| `src/components/providers/forms/ProviderForm.tsx:1765-1772` | 只有非 `auto` 时才写进配置，避免把默认值写进每个人的数据 |
| `src/components/providers/forms/CodexFormFields.tsx:1070-1113` | 三态下拉 + 一句说明文案 |

cc-switch 自己也踩过两个反向的坑，值得记下来：

- issue #3193：多个会话共用一个键，互相把缓存挤掉，命中率反而变低。
- issue #5729：每个会话一个键，导致跨会话完全无法复用那段一模一样的大前缀（system + 工具定义）。

两条说明**键的粒度是要拿真实数据调的**，不是拍脑袋定的。见 4.6。

## 4. 升级方案

### 4.1 每个供应商加三态设置

```kotlin
// ai/src/main/java/me/rerere/ai/provider/PromptCacheRouting.kt
@Serializable
enum class PromptCacheRouting { AUTO, ENABLED, DISABLED }
```

在 `ProviderSetting.OpenAI`（`ProviderSetting.kt:54-68`）加一个字段，默认 `AUTO`：

```kotlin
var promptCacheRouting: PromptCacheRouting = PromptCacheRouting.AUTO,
```

放供应商而不是放全局的理由：出问题的是"某一家上游"，不是"整个应用"。全局只能一刀切，切了就全丢。
全局 `promptCacheEnabled` 保留，当总闸用。

### 4.2 判定集中到一个函数

现在两个 API 类各写一遍同一个条件。收敛成一个顶层函数（`ai/.../provider/PromptCacheRouting.kt`）：

```kotlin
/** 返回 null 表示这次请求不带 prompt_cache_key。 */
fun resolvePromptCacheKey(
    routing: PromptCacheRouting,
    baseUrl: String,
    globalEnabled: Boolean,
    key: String?,
): String?
```

规则：

1. 总闸关 → 不发。
2. 键为空或全空格 → 不发。
3. 供应商设为 `DISABLED` → 不发。
4. 供应商设为 `ENABLED` → 发。
5. `AUTO` → 解析 `baseUrl` 的 host，命中已确认兼容名单才发。首发名单只放 `api.openai.com`。

只看 host，不看路径；`baseUrl` 解析失败当作不支持。
不确定的上游一律不发，符合"宁可少命中，也不能让用户当场看到 400"的取舍。

### 4.3 本地学习（比 cc-switch 多走一步）

理由：黑猫 AI 里有 20 多个内置上游，还有用户自己填的中转站。白名单永远列不全，而每猜错一个上游，代价都是用户当场收到一个 400。

实际实现的规则（看代码就三处）：

1. 三个条件同时成立才算命中：请求里确实带了这个字段、状态码是 400 或 422、响应正文里是这个字段被点名拒绝。
   第三条不能只看“正文里出现字段名”：有些网关会把整个请求体回显在报错里，那里面也有字段名。
   所以要求拒绝的措辞（unknown / unrecognized / unsupported / not supported …）和字段名挨得很近，
   否则宁可当成普通错误上报（判不准就不学，总比乱改用户的设置强）。
2. 命中后先记到 `PromptCacheKeyRejection`，再把失败当成“值得重试一次”上报。
3. 重试会重新构造请求，构造时会问一次记录，于是重试的那次不带这个字段。界面表现就是一次“正在重试”，用户看不到报错。
4. 同一个进程内这家上游不再发这个字段；同时通过回调把设置里的开关落成 `DISABLED`，重启后不会重复这一轮。
5. 其它任何 400 都不动，避免把别的原因误判成这个。

代价与边界：

- 重试走的是应用自己的自动重试开关。默认开着（间隔 3 秒），用户关掉自动重试时，这一次会直接报错，但设置已经被落成 `DISABLED`，下一次就不会再失败。
- 学习结果可撤销：用户在供应商页重新选一次（自动 / 开启）就会清掉进程内的记录，同时设置也跟着改。
- 没有做“触发次数统计”。等真有人碰到时再加，现在加就是凭空多一份没人看的数据。

### 4.4 补上全局开关的界面

`Settings.promptCacheEnabled` 目前没有界面。放到设置-通用，照 `SettingPreferencesGeneralPage.kt:271` 起那张"自动重试"卡片的样子写。
文案要说清语义：关掉表示"应用不再主动带这个字段"，不是"让上游删掉缓存"。

### 4.5 供应商页加三态选项

在 `ProviderConfigure.kt:202-291` 的 `ProviderConfigureOpenAI` 里加一个三态控件，位置放在"启用"和"Response API"附近。
文案参考 cc-switch 的意思，改成用户能看懂的话：

> 自动：只对已确认支持的接口发送这个字段。
> 开启：你的接口确认支持时选它。
> 关闭：接口会因为不认识的字段报 400 时选它。

新增字符串要覆盖 6 套语言：`values`、`values-zh`、`values-ja`、`values-ko-rKR`、`values-ru`、`values-zh-rTW`。

### 4.6 键的范围：先不动

结论：**保持会话级，这一版不改。**

依据：

- 官方文档里 `prompt_cache_key` 只是路由提示，服务端会再叠加上前缀哈希（`platform.openai.com/docs/guides/prompt-caching`）。
  所以键里不需要放前缀指纹——放了也白放，而且前缀每轮都在变长，等于每轮换一个键。
- 现在黑猫 AI 用的 `sha256(appId + conversationId)`，跟官方文档给的示例（`tenantId:sessionId` 做 SHA-256）是同一个路子。
- 真正值得考虑的另一种做法是"同一助手的会话共用一个键"，让 system + 工具定义那段大前缀跨会话复用（cc-switch #5729 的诉求）。
  但 #3193 证明键太粗会互相挤掉。所以等 4.7 的命中数据出来再决定，这一版不做。

### 4.7 验证与观察

单元测试（扩展现有 `ai/src/test/.../openai/PromptCacheRequestTest.kt`）：

| 场景 | 期望 |
| --- | --- |
| `AUTO` + `api.openai.com` | 带字段 |
| `AUTO` + 自定义 host | 不带字段 |
| `ENABLED` + 自定义 host | 带字段 |
| `DISABLED` + `api.openai.com` | 不带字段 |
| 全局关闭 | 不带字段 |
| 键为空 | 不带字段 |
| 学习后（记为不支持） | 不带字段，且重试时请求体里没有这个字段 |

真实链路：读返回的缓存命中用量，区分两件不同的事——"字段发出去了"和"真的命中了"。命中只能以服务端返回的缓存读取 token 大于 0 为准。
项目已有的用量归一化（`ai/.../core/Usage.kt`）可以承接，只需在调试输出里把"是否真命中"打出来。

### 4.8 这次不做的事

- **不加手工填键**。黑猫 AI 是客户端不是代理，用户没有"我要指定某个键"的场景。
- **不引入 `prompt_cache_options` / `prompt_cache_breakpoint`**。官方已经推出显式缓存模式，字段更严格，网关兼容性更差。等隐式缓存被数据证明不够用再说。
- **不动 Claude 那条路**。`cache_control` 和 TTL 会遇到同类的"严格上游不认字段"问题，但那是独立的一题，这次不合并。

## 5. 落地顺序与改动清单

| 步骤 | 文件 | 改什么 | 验收 |
| --- | --- | --- | --- |
| 1           | `ai/.../provider/PromptCacheRouting.kt`（新增） | 枚举 + `resolvePromptCacheKey` | 新单元测试全绿 |
| 2 | `ai/.../provider/ProviderSetting.kt` | `ProviderSetting.OpenAI` 加三态字段 | 旧配置仍能读（默认 `AUTO`） |
| 3 | `ChatCompletionsAPI.kt:246`、`ResponseAPI.kt:235` | 两处条件换成一次 `resolvePromptCacheKey` | 现有请求快照测试不变 |
| 4 | `ai/src/test/.../openai/PromptCacheRequestTest.kt` | 补齐 4.7 的场景 | 全绿 |
| 5 | `app/.../ui/pages/setting/components/ProviderConfigure.kt` | 加三态控件 | 手动改能存住、重进页面还在 |
| 6 | `app/.../ui/pages/setting/SettingPreferencesGeneralPage.kt` + 6 套 strings | 补全局开关 | 关掉后抓包确认没有该字段 |
| 7 | `PromptCacheRouting.kt`、`ai/util/ErrorParser.kt`、`ai/provider/RetryPolicy.kt`、两个 API 类、`ChatService.kt`、`ProviderConfigure.kt` | 400/422 命中精确字符串 → 记下 + 去字段重试一次 → 设置落成“关闭” | 单元测试覆盖判定与可重试分类；真实严格上游未实测 |

第 1~4 步做完就有实际收益（不再对未知上游乱发字段）。
第 7 步风险比前面几步高（会碰重试路径），所以单独一轮做。

### 5.1 落地状态（2026-09-13）

七步全部完工并验证：

- `./gradlew :ai:testDebugUnitTest` → 227 项通过，0 失败。
- `./gradlew :app:testDebugUnitTest` → 386 项通过，0 失败。
- `./gradlew :app:compileDebugKotlin` → 通过（只有仓库原有的弃用警告）。

没有做真实严格网关的端到端验证（手边没有会拒绝这个字段的密钥）。
HTTP 报错分类、重试分类和字段判定都有单元测试盯着，但没有真的发过一次会被拒的请求。

## 6. 还没定的事

- **各内置上游到底认不认这个字段，没有实测**。所以首发白名单只放 `api.openai.com`，其余靠第 7 步的本地学习兜底。
- **没有真实命中率数据**。所以键的范围这一版不动，等数据（4.6）。
- 学习机制会为每个不支持的供应商多花一次请求。这是明确接受的代价，换来的是以后不用维护一张手工名单。

## 7. 来源

- cc-switch 源码：`https://github.com/farion1231/cc-switch`（`src-tauri/src/proxy/providers/codex.rs`、`src-tauri/src/proxy/session.rs`、`src-tauri/src/provider.rs`、`src/components/providers/forms/CodexFormFields.tsx`）
- OpenAI Prompt caching 官方说明：`https://platform.openai.com/docs/guides/prompt-caching`
- cc-switch issue #3193（键太粗会互相挤掉）：`https://github.com/farion1231/cc-switch/issues/3193`
- cc-switch issue #5729（键太细无法复用大前缀）：`https://github.com/farion1231/cc-switch/issues/5729`
- cc-switch issue #2206（上游因该字段报 400）：`https://github.com/farion1231/cc-switch/issues/2206`
- 本文档的现状部分对应 `origin/release/portable-workspace-cache` 上的代码，行号以该分支为准。
