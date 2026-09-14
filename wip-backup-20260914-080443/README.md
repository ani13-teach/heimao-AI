# 存档：2026-09-14 08:04 的工作树快照

这不是可运行的代码，是一次"删除前留下"的快照存档。放在 `wip/prompt-cache-routing` 分支上，
不合并回 `release/portable-workspace-cache`。

## 为什么留

2026-09-14 08:20 把"隐式提示词缓存"整个删掉了。删之前 08:04 留了一份快照，里面除了被删的
基础版，还有一套**从未提交过**的升级设计：`PromptCacheRouting`（三态路由 + 被上游 400 拒绝
后自动降级重试）。`git log --all -S PromptCacheRouting` 是空的，所以这套东西只存在于这份存档里。

## 文件

| 文件 | 说明 |
| --- | --- |
| `ai/.../provider/PromptCacheRouting.kt` | 路由枚举与实现，140 行。只在 `api.openai.com` 等确认兼容的上游发 `prompt_cache_key` |
| `ai/.../provider/PromptCacheRoutingTest.kt` | 对应测试 |
| `docs/openai-prompt-cache-routing-plan.md` | 209 行方案：现状核查、升级清单、为什么自动降级是黑猫 AI 最需要的一步 |
| `wip-backup-20260914-080443/tracked-changes.patch` | 08:04 工作树对当时的 HEAD（`22551c6a`）的完整改动，45 个文件 |

`wip-backup-20260914-080443/` 里的其余文件（`gh.patch`、`gh-cl.patch`、`clonly/`、`untracked.tgz`、
`ar-msg.txt`、`cl-msg.txt`）是同一批草稿的原始存档，原样保留：

- `clonly/`：保留全局缓存开关、只做 Claude 的改法（`PreferencesStore.kt`、`SettingVM.kt`、`strings*.xml`）
- `gh.patch` / `gh-cl.patch`：两份 GenerationHandler 的对照改法
- `ar-msg.txt` / `cl-msg.txt`：当时的提交信息草稿

这些文件入库时被 git 统一成了 LF 行尾，内容一字未改（逐文件比对过哈希，只差 `\r`）。
严格一致的原始字节在 `untracked.tgz` 和仓库外那份
`C:\Users\jch\heimaoai\_wip-backup-20260914-080443\` 里。

## 注意

- 根目录三个文件（`PromptCacheRouting.kt`、测试、方案文档）**不会编译**：`PromptCacheRouting.kt`
  引用的 `me.rerere.ai.util.PromptCacheKeyRejectedException` 在 `tracked-changes.patch` 里才加，
  本分支没有把那份 patch 应用上去，只做原样存放。
- `tracked-changes.patch` 的基准是 `22551c6a`，不是本分支的父提交。要用它得先
  `git checkout 22551c6a` 或按文件手工挑。

    git apply --check wip-backup-20260914-080443/tracked-changes.patch
