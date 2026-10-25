# 黑猫ai：开发与维护指南

本文依据本次本地源码核对编写，核对基线为 `cf7f346b`（`feat(workspace): add managed apt plans and schema migration`）；当时实际分支为 `master`，工作树不干净。版本、分支和文件位置会变，后续以实际源码为准。

**本次只核对说明与源码，不代表重新执行过构建、单测或设备验收。** 旧文档中的“已通过”仅指各自记录的历史版本。

## 1. 项目定位与阅读顺序

黑猫ai是 RikkaHub 的 Android fork，使用 Kotlin、Jetpack Compose / Material 3、Koin、Room、DataStore、协程与 Flow。`ai` 模块对接多种模型协议；应用维护助手、会话、记忆与工具。

- Fork：<https://github.com/ani13-teach/rikkahub>
- 上游：<https://github.com/rikkahub/rikkahub>
- 功能与使用入口：[简体中文 README](../README_ZH_CN.md)。
- 文档分类：[文档目录](README.md)。
- 修改规则：[AGENTS.md](../AGENTS.md)。

推荐顺序：README → 本指南 → 所涉模块代码与测试 → 专题设计/历史报告。计划不是完成证明，测试文件存在也不是测试通过证明。

## 2. 本机工作区与协作边界

```text
C:/Users/jch/heimaoai/
├─ project/       唯一当前源码与 Git 仓库
├─ deliveries/    已核验 APK、SHA-256、构建记录
├─ history/       旧源码和历史资料，不是实现源
├─ evidence/      本地设备日志、截图、数据库等证据
├─ tools/         环境检查与校验脚本
└─ environment/   环境说明与非敏感模板
```

其他机器可以使用不同父路径；代码、构建和测试都从 `project/` 根目录执行。交付、历史和证据目录默认只读；本次仅补充源码仓库文档。

开始和结束任务时运行：

```bash
git status --short
git branch --show-current
git remote -v
```

- 不能假定当前仍在 `release/portable-workspace-cache`；本次实查是 `master`。
- 保留已存在的修改、删除和未跟踪文件，不自动 discard、reset、restore、clean 或 stash。
- 父目录旧 README 曾要求丢弃未提交实现，属于会导致数据丢失的操作说明，不能据此自动清理当前工作。发现与当前需求冲突时先报告。
- 文档/源码近期被其他会话修改时重新读取，避免用旧内容覆盖。重型构建前检查并发写入和 Gradle 进程。
- 不自动提交、推送、改 remote 或同步上游。同步需求须先核对工作树和冲突，再 fetch 比较，不强制覆盖。

## 3. 功能现状与开发状态

当前源码包含多供应商聊天、消息分支、附件解析、MCP、网络搜索、记忆、历史参考、永久编号与总结、便携工作区、备份恢复、运行日志及后台生成。

精简后已无 `speech`、`web` 和 `web-ui` 模块；ASR/TTS、快捷消息、角色卡导入、Lorebook 与旧更新/赞助/关于入口已移除。Android WebView、Mode Injection、普通助手/附件、导出分享继续保留。后来恢复的运行日志不能与早期删除的请求日志入口混为一谈。

自动上下文压缩由 `ContextBlocks.kt` 与 `GenerationHandler.kt` 实现，在普通发送、重新生成和工具续跑请求前检查完整上下文预算。较早且完整结束的用户轮保存为独立摘要块，原聊天记录保留，当前轮和最近一个完整轮保留全文；已保存的摘要不再反复改写。全局触发长度与压缩目标在模型设置中配置，默认分别为估算的 100000 和 10000 token；触发长度为 0 时停止新增摘要块，已有块继续使用。摘要块通过来源内容、选中消息路径、会话与助手归属校验，失效块不参与发送。支持工具调用的模型可使用 `context_search` 搜索本会话当前分支的摘要或块内原文，使用 `context_read` 分页回查；原文回查每次最多 2048、每个当前用户轮最多 4096 估算 token，不读取隐藏思考或二进制附件。旧摘要仅在来源有效且不覆盖近期保护轮时导入。

摘要块保存在 Room 32 的 `context_block` 表中，与原件在同一事务提交；删除会话时级联删除。数据库迁移为 31→32，31 版工作区备份先在隔离暂存副本升级，通过 32 版结构校验后激活。普通聊天导出仍使用原始选中消息。首期不包含摘要再分层压缩；必留内容或已有摘要超过目标、模型摘要超出限额时停止该次请求并返回错误。旧的手动压缩入口和按消息条数限制上下文的设置已移除。

## 4. 模块与主调用链

`settings.gradle.kts` 是实际模块清单来源：

| 模块 | 职责 |
| --- | --- |
| `app` | Android UI、ViewModel、生成服务、Repository、Room/DataStore、工具编排 |
| `ai` | OpenAI/Google/Anthropic 提供商抽象、消息/内容 part、流式协议 |
| `common` | 通用工具与扩展 |
| `document` | PDF、DOCX、PPTX、EPUB 等附件解析 |
| `highlight` | 代码语法高亮 |
| `material3` | Material 色彩相关工具 |
| `search` | 多种联网搜索提供商 |
| `workspace` | 工作区文件、独立 HOME、Kotlin 有限命令执行、迁移及本地 native 构建 |
| `app:baselineprofile` | 启动/运行性能 profile |
| `build-logic` | included build，统一模块构建约定 |

`locale-tui`、`trace-cli`、`tools/runtime-probe` 是各自带说明的辅助工具，不在上述 Android 模块列表中。

生成主链：

```text
ChatPage / ChatVM
  → ChatService（会话状态、队列、后台生成）
  → GenerationHandler（输入转换、模型请求、工具、流式处理）
  → ai Provider
  → ConversationRepository / Room（正式保存）
```

核心概念：Assistant 是助手配置；Conversation 是持久会话；MessageNode 持有可切换的备选消息；UIMessage 是含文本、图片、reasoning、tool 等 part 的消息。修改可见分支时，不能误把所有备选消息当作当前模型上下文。

### 源码定位

以下路径相对 `app/src/main/java/me/rerere/rikkahub/`：

| 功能 | 入口 |
| --- | --- |
| 生成与会话服务 | `data/ai/GenerationHandler.kt`、`service/ChatService.kt` |
| 输入/输出转换 | `data/ai/transformers/Transformer.kt` |
| AI 文件与有限命令工具 | `data/ai/tools/WorkspaceTools.kt` |
| 历史参考工具 | `data/ai/tools/ConversationTools.kt` |
| 工作区与运行协调 | `data/repository/WorkspaceRepository.kt`、`data/workspace/` |
| 本地 Python 执行与工作进程 | `data/workspace/python/AndroidWorkspacePythonExecutor.kt`、`data/workspace/python/WorkspacePythonService.kt`、`app/src/main/python/workspace_runner.py` |
| 可携带备份 | `data/workspace/WorkspaceArchiveService.kt` |
| 会话编号、归属、持久化 | `data/repository/ConversationRepository.kt`、`data/db/dao/ConversationDAO.kt` |
| 总结内容选择、分块 | `data/summary/ConversationSummary.kt` |
| 记忆查询与仓储 | `data/db/dao/MemoryDAO.kt`、`data/repository/MemoryRepository.kt` |
| FTS 查询与索引 | `data/db/fts/MessageFtsManager.kt` |
| 数据库版本与迁移 | `data/db/AppDatabase.kt`、`data/db/migrations/` |
| 历史选择与聊天 UI | `ui/pages/history/`、`ui/pages/chat/` |
| 人工终端 | `ui/pages/extensions/workspace/WorkspaceTerminalPage.kt` |

工作区核心在 `workspace/src/main/java/me/rerere/workspace/`：`WorkspaceManager.kt`、`KotlinWorkspaceShellRunner.kt`、`PortableWorkspaceRuntime.kt`、`WorkspaceFileSystem.kt`。旧 PRoot/共享 Linux/apt 实现仍有源码，但不是默认运行链路，APK 打包排除 PRoot 与 termux native 库；QuickJS 和 `libworkspace` 保留。

## 5. 工作区与安全边界（当前实现）

### 文件工具

AI 文件读写、编辑只接受 `/workspace` 或其子路径，走工作区文件区 API。路径规范化须拒绝越界、空字节、相邻前缀目录和外部目录；授权还要核对助手/会话的工作区归属。不能把描述文字或用户审批当成路径检查的替代品。

### 默认命令运行时与 Python

应用依赖注入在 `RepositoryModule.kt` 设置 `portableRuntime = true`，使用 `KotlinWorkspaceShellRunner`，不是 `WorkspaceManager` 构造器的默认值。支持有限命令、管道、重定向、`&&` 及 `python`/`python3`，不调用完整 Bash 或 apt，不提供任意 Linux 命令、交互式 PTY 或包管理器。默认工作区页面无 Linux 安装、共享/私有切换和 apt 入口；终端为命令输入、执行、取消与结果历史。

工作区保留独立文件区、HOME 和临时目录，命令路径映射为 `/workspace`、`/root`/`/home`、`/tmp`。Python 通过 Chaquopy 在同 UID、`exported=false` 的 `:workspace_python` 工作进程执行；正常完成、超时和取消后都终止整个进程并确认退出，未确认时保留终止待处理状态。该进程不是 Android isolatedProcess；路径映射与进程重建不构成安全隔离，不能声称阻止 Python 读取应用数据、调用原生能力或联网。AI 文件工具的 `/workspace` 路径边界和助手/会话归属检查不变。

Codemode 工具描述要求多步文件任务、批量读写、重复命令和结果筛选统计默认优先；单次简单操作可直接调用工具。实际仍只有 `read`、`write`、`edit`、`shell` 四种工作区调用，全部串行（包括 `Promise.all`），仅通过 `text(...)` 返回文本结果。

### 旧 Linux 工作区迁移

- 迁移到独立 HOME，复制并核对旧私有 `linux/root`；已有独立 HOME 的工作区沿用其 HOME。
- HOME 文件、目录和父目录先持久化（fsync），再写入并持久化 runtime marker，完成后才删除对应旧 `linux`；迁移失败保留源数据，不靠删除目录解决冲突。
- 全部工作区迁移成功后才删除 `shared-linux`；任一工作区失败则保留公共源目录。
- 迁移与命令执行仍受工作区锁和运行协调约束；便携运行时不再使用公共 apt 审批链。

## 6. 记忆、检索、总结与数据库兼容

- 普通记忆 DAO 查询按 `id DESC`；分层记忆按更新时间及 ID 排序，只有 active 条目进入有效索引，按层注入/读取并支持锚定修改。不要写成所有记忆都按同一种顺序。
- 历史参考与普通记忆是不同机制。FTS5 使用 `simple` tokenizer 与 jieba 查询，不是全库 Embedding。
- AI 历史搜索限当前助手、排除当前会话并控制同会话命中；App 消息搜索默认当前助手，可切换所有助手，同时匹配标题（包含匹配）与正文（FTS）。索引可覆盖备选消息，不等同于总结的可见分支选择。
- 永久会话编号由 Repository 事务分配，全局唯一且删除不回收；新增/导入不能绕过分配路径。
- 总结来源 ID 必须属于来源助手；用户可以另选总结助手。仅整理当前可见分支的 user/assistant 文本和附件名，排除 system、隐藏 reasoning、工具输出与附件正文。
- 来源作为 user 权限的附加上下文放在最新用户要求之前，不创建隐藏消息节点。**`summaryContext` 会作为会话元数据持久缓存**，不能声称完全不落库。
- 大来源需要分块归并、上限、进度与取消；先检查来源规模，不能一次加载所有历史再假装实现了内存上限。

数据库版本为 **32**，以 `APP_DATABASE_VERSION` 为准。修改 Room 结构要同步 entity、版本、迁移注册、`app/schemas/` 与迁移测试；恢复逻辑中的 schema gate 也要检查。保留编号、助手归属、消息节点和总结元数据。旧 JSON 中普通未知字段可兼容忽略，但旧多态工具类型需显式迁移测试，不做破坏性数据清理。

## 7. 备份恢复与隐私

当前备份与快照采用 v2：持久设置、可选数据库、工作区 TAR 与 manifest。每个工作区使用 `files.tar` 和 `home.tar`，不携带 Linux rootfs，不依赖源设备 ABI，也不恢复共享/私有 Linux 环境。旧“文件”选项只控制 upload/skills/fonts 等兼容项。

本地备份可选择一个完整基础备份，导出直接依赖该完整包的增量备份，不依赖此前增量。增量索引为 v2，运行时标识必须是 `portable-v1`；只保存变化的 ZIP 条目，工作区 TAR 有变化时保存整个 TAR。恢复时选择对应完整包，流式组合两包并校验长度、SHA-256、CRC 与累计预算；缺失或选错基础包会停止恢复。包含附件目录时按当前内容整体替换，保留删除和空目录状态。恢复专用入口兼容旧 Linux v2 完整包及依赖该完整包的旧增量 v1：按原始 manifest SHA 匹配基础包，校验全部载荷后仅迁移聊天、设置、附件及工作区 files/HOME，Linux 载荷流式校验后丢弃，清空旧运行环境元数据。新导出、增量 plan/encode 和标准验证仍要求便携格式；不支持链式增量。WebDAV/S3 继续使用完整备份。

已压缩附件采用 DEFLATE level 0，其他条目使用快速压缩；导出自检流式验证 TAR，不重复解压工作区。数据库快照遇忙重试，首次成功立即返回。备份耗时记录保留最近 20 次，记录阶段、耗时、次数、字节数、条目数与完成状态，数据库恢复后补记重启结果。父阶段包含子阶段，不能重复相加。

- **新导出不是加密包**，设置保留 API Key、URL、MCP token 和同步凭据。用户数据库与工作区也可能含敏感内容，禁止公开上传。
- 恢复入口拒绝归档格式 v1、旧加密包和无 v2 manifest 的归档；旧增量索引 v1 仅与旧 Linux v2 基础及当前清单配对时可恢复。旧加密辅助源码仍在，不代表当前入口支持旧加密包或自动加密。
- manifest 的长度与 SHA-256 校验解决完整性问题，不解决保密性或来源认证。
- 数据库备份涉及 WAL checkpoint；恢复有暂存、路径/符号链接校验、回滚记录，含数据库的恢复要求重启。
- 工作区源指纹只检测变更，不是冻结所有外部文件句柄的文件系统事务。备份时避免外部程序继续写入。
- 测试使用隔离 Debug 包或测试数据，不覆盖正式 App 用户数据库；失败不能靠删除用户工作区绕过。

模型、搜索、MCP 和同步调用会连接用户配置的远程服务；不要承诺完全离线或零数据外发。当前构建仍有 Firebase 相关依赖，是否启用以及运行行为需单独验证。日志或问题报告先脱敏。

## 8. 开发环境与首次构建

| 项目 | 当前配置 / 本机约定 |
| --- | --- |
| Gradle Wrapper | 9.5.0（`gradle/wrapper/gradle-wrapper.properties`） |
| AGP / Kotlin | 9.3.1 / 2.4.10（`gradle/libs.versions.toml`） |
| JDK | 本机推荐 21，示例 `C:/DevTools/jdk-21`；源码 JVM target 为 17，两者不是同一个概念 |
| Android SDK | compileSdk / targetSdk 37，minSdk 26；本机 `C:/Android/Sdk` |
| NDK | 28.2.13676358，当前 AGP 默认值，项目未显式指定 ndkVersion |
| CMake | 3.22.1（`workspace/build.gradle.kts`） |
| ABI | arm64-v8a、x86_64，另产 universal APK |
| App 版本 | 2.4.15 / versionCode 183，以 `app/build.gradle.kts` 为准 |
| 本地 Python | Chaquopy 17.1.0 / Python 3.13；构建机 `python` 必须同主次版本 |
| 固定 Python 依赖 | numpy 1.26.2、pandas 2.1.3、matplotlib 3.8.4、openpyxl 3.1.5 |

1. 安装 JDK、SDK 平台 37、对应构建工具、NDK 与 CMake；将 `JAVA_HOME`、`ANDROID_HOME`/`ANDROID_SDK_ROOT` 指向实际目录。
2. 在仓库根建立 `local.properties`，它已被 Git 忽略。例如：

```properties
sdk.dir=C\:\\Android\\Sdk
```

3. 完整 APK 构建需要由项目维护者提供自己的 `app/google-services.json`，不要使用随机配置或提交私有配置。

Python 依赖打包使用 `prune<Variant>PythonRequirements` Sync 任务，将 Chaquopy 安装输出复制到 `app/build/python/prunedRequirements/<variant>/`，仅排除 numpy、pandas、matplotlib、mpl_toolkits 的 `tests` 目录，再生成 `.imy` 和 `build.json`。保留原安装输出、`testing`、`_testing`、字体和运行库；依赖升级或 Chaquopy 插件升级时需复核该任务衔接，并执行 `AndroidWorkspacePythonExecutorTest` 验证计算、中文绘图和 Excel 读写。Android 语言资源只打包应用已有的英语、日语、韩语、俄语和简繁中文。
4. 在仓库根执行：

```bash
./gradlew --version
./gradlew :app:assembleDebug
```

Git Bash 使用 `./gradlew`；PowerShell 使用 `.\gradlew.bat`。依赖来自 Google、Maven Central、JitPack 等，首次下载需要网络；不要因下载失败随意替换依赖来源。`mavenLocal()` 也在仓库配置中，比较构建结果时需留意本机工件影响。

产物位于 `app/build/outputs/apk/debug/`，Debug 包名 `me.rerere.rikkahub.debug`，可与正式包并存。如果机器资源不足可加 `--no-daemon --max-workers=1`，不把旧电脑的强制串行规则当作所有机器硬要求。

## 9. 验证阶梯与测试地图

所有命令从源码根目录执行；以下是操作指南，**不是本次执行结果**。

```bash
git diff --check
./gradlew :workspace:testDebugUnitTest --tests me.rerere.workspace.PortableWorkspaceTest
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.workspace.WorkspaceArchiveV2Test' -x :app:processDebugGoogleServices
python -m unittest discover -s app/src/androidTest/python -p 'test_workspace_runner.py'
./gradlew :app:testDebugUnitTest -x :app:processDebugGoogleServices
./gradlew :ai:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin -x :app:processDebugGoogleServices
./gradlew :workspace:compileDebugAndroidTestKotlin
```

`-x :app:processDebugGoogleServices` 仅用于绕开测试/编译任务的配置处理，不是完整 APK 或运行时 Firebase 配置方案。完整构建仍按第 8 节提供配置。

按需求扩大到 `:app:lintDebug`、其他模块单测与完整构建。旧报告提到 Lint、Windows `/bin/sh`、符号链接或网络测试基线失败；本次未复测，失败时依据实际日志区分环境、旧基线和新回归。

| 重点 | 测试入口（相对模块测试包） |
| --- | --- |
| 路径/工具边界 | app `data/ai/tools/WorkspaceToolsTest.kt` |
| 便携命令、HOME 迁移与故障恢复 | workspace `PortableWorkspaceTest.kt`；设备 `PortableWorkspaceInstrumentedTest.kt` |
| Python 路径、执行、进程终止与取消 | `app/src/androidTest/python/test_workspace_runner.py`；app 设备 `data/workspace/python/AndroidWorkspacePythonExecutorTest.kt` |
| FTS 隔离 | app `data/db/fts/MessageFtsManagerQueryTest.kt` |
| 分层记忆 | app `data/repository/LayeredMemory*Test.kt`、`data/ai/LayeredMemoryPromptTest.kt` |
| 总结与临时上下文 | app `data/summary/ConversationSummaryTest.kt`、`data/ai/GenerationHandlerContextTest.kt` |
| v2 备份/快照、安全、回滚、schema | app `data/workspace/WorkspaceArchiveV2Test.kt`、`data/workspace/WorkspaceArchive*Test.kt`、`data/workspace/WorkspaceSnapshotTest.kt`、`data/workspace/RoomSchema31Test.kt`、`data/workspace/SharedWorkspaceArchiveTest.kt`；设备 `data/workspace/PortableWorkspaceBackupInstrumentedTest.kt` |

上表 Kotlin JVM 测试分别位于 `app/src/test/java/me/rerere/rikkahub/` 和 `workspace/src/test/java/me/rerere/workspace/`；星号代表一组文件，不是一个文件名。

有已授权的测试设备/模拟器时：

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
./gradlew :workspace:connectedDebugAndroidTest
```

这会安装测试包并执行设备操作，先确认目标是可用测试设备。设备重点覆盖 `PortableWorkspaceInstrumentedTest`、`AndroidWorkspacePythonExecutorTest`、`PortableWorkspaceBackupInstrumentedTest`，以及 `MemoryDAOTest`、`ConversationSummaryDAOTest`、`Migration_30_31_Test`、旧版本迁移、FTS 和归档 schema gate。旧 PRoot/apt 测试仍用于遗留实现，不代表默认便携运行时验收；模拟器验证不等于正式 Release 发布。

通过 Kotlin 编译不等于设备验证，通过 JVM 测试也不等于 APK 与当前源码一致。没有设备或 Provider 凭据时明确记未执行，不能借用旧报告标“通过”。

## 10. 正式签名、发布与交付

仅在用户需要正式 APK 时执行；补文档不要求重打包。

`app/build.gradle.kts` 从根 `local.properties` 读取以下四项，均须配置：

```properties
storeFile=C\:/Users/jch/.android/heimao-ai-release.jks
storePassword=<仅本机填写>
keyAlias=heimao-ai
keyPassword=<仅本机填写>
```

密钥/口令只在安全本地位置和可信离线备份中保存，绝不写进说明、提交或随 APK 归档。缺字段不能认为已获得正确发布签名，也不能仅凭“构建成功”交付。

```bash
./gradlew :app:signingReport
./gradlew :app:assembleRelease
# 下列 Android SDK 工具需要在 PATH 中，或用其绝对路径
apksigner verify --print-certs app/build/outputs/apk/release/app-universal-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-universal-release.apk
sha256sum app/build/outputs/apk/release/app-universal-release.apk
```

现有正式身份（证书指纹不是秘密）：

- 包名：`me.rerere.rikkahub`，显示名称：黑猫ai。
- 别名：`heimao-ai`。
- 证书 SHA-256：`a886a7c8dcb54e44df24ba8f79f14dd1325448b0614e329986c558f855bae2c7`。

每次核对签名连续性、版本、label、ABI、SHA-256；不同签名无法覆盖升级。不要为验证随意卸载用户正式应用。

交付前核对构建前后源码状态与时间戳。只比较 APK 与 `.kt` 文件时间并不充分；构建脚本、资源、native 文件、未跟踪源码也会影响产物。构建期间有影响 APK 的变更则重新构建，不交付错配包。

用户明确要求归档时才写 `../deliveries/`，按现有命名习惯附 `.sha256` 和 `.build.txt`，记录源码路径、commit、分支、脏工作树、版本、构建命令与结果、时间、大小、包名、签名校验以及设备安装/启动结果。没做的验收写“未执行”。

CI 位于 `.github/workflows/daily-build.yml`：每天或手动触发，使用 `KEY_BASE64`、`SIGNING_CONFIG`、`GOOGLE_SERVICES_JSON` secrets，生成 `nightly` 预发布。本次未验证远程 CI；当前 workflow 写 JDK 17，而本机推荐 JDK 21，后续升级应单独核对 CI 兼容性与签名身份，不直接承诺云端通过。

## 11. 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| SDK 路径找不到 | 核对 local.properties 转义与 SDK 环境变量；不要复制整套 SDK |
| NDK 缺 source.properties / CXX1101 | 检查 28.2.13676358 是否为中断安装空壳，确认后正确重装；不要用 ndk.dir 绕版本 |
| sdkmanager 报代理 MalformedURLException | 检查 HTTP_PROXY/HTTPS_PROXY 是否为空字符串；只在该命令环境清除无效值，不改全局代理配置 |
| Google Services 任务失败 | 提供自己的正确配置；测试任务可按上文排除处理，完整 APK 不可据此跳过 |
| Windows Shell/链接相关测试失败 | 保存实际日志，在合适环境复测；不能当成本次改动自动免责 |
| 工作区 Python 无法运行 | 核对内置 Python、固定依赖、工作进程连接及终止状态；无需安装 Linux 或 apt |
| 便携迁移失败/工作区未就绪 | 检查 HOME 冲突、同步和 marker 状态，保留旧 linux/shared-linux，不删源绕过 |
| 备份恢复 schema 不一致 | 检查归档版本、迁移、schema gate 及实际错误；用隔离测试数据复现，不能删用户工作区 |
| APK 无法覆盖安装 | 比较包名、versionCode 和证书指纹；先备份，不自动卸载 |
| README/历史说明冲突 | 以当前源码、任务授权和对应版本的证据为准；旧路径/旧验收不作当前结论 |

## 12. 文档维护规则

变更功能时同步 README；变更模块、SDK、数据库、Shell/审批边界或备份格式时同步本指南及相关测试。发布结果放在对应构建记录，不把文档写成永久有效的“全通过”声明。新专题通过 [docs/README.md](README.md) 注册入口，历史报告保留并标记其适用范围。
