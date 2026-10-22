# 仓库级 Agent 工作约定

## 1. 目录与操作范围

本文件位于源码仓库根目录，与 `AGENTS.md` 配合使用。本文带 `project/` 前缀的路径及总目录表，以本地总工作区（源码仓库的父目录）为基准；该总目录不属于 Git 仓库，配套目录或脚本不存在时不自动创建或下载。

黑猫 AI 是基于 RikkaHub 的 Android 客户端，使用 Kotlin、Jetpack Compose / Material 3、Koin、Room、DataStore、协程与 Flow。

| 目录 | 用途与规则 |
| --- | --- |
| `project/` | 唯一当前源码和 Git 仓库；开发、Git、Gradle 操作均在此执行 |
| `deliveries/` | APK、校验值和构建记录；仅在明确要求交付或归档时写入 |
| `history/` | 旧源码、上游备份与历史资料；默认只读，不作为实现源 |
| `evidence/` | 设备截图、日志、数据库和测试证据；默认只读，可能含敏感数据 |
| `tools/` | 环境检查与校验脚本；运行前检查作用范围和副作用 |
| `environment/` | 环境模板；不得填入真实凭据后归档或提交 |

不要修改总目录之外的独立项目，不从历史副本覆盖当前源码。没有任务需要时，不读取或解压签名备份、数据库和私密归档。

## 2. 开始工作

1. 阅读 `project/AGENTS.md`，并检查目标目录是否有更细的工作约定。
2. 按需求阅读 `project/README_ZH_CN.md`、`project/docs/PROJECT_GUIDE.md` 和 `project/docs/README.md`，再定位相关源码与测试。
3. 在 `project/` 记录工作区、当前分支和远程配置：

   ```bash
   git status --short
   git branch --show-current
   git remote -v
   ```

4. 以实际源码和构建配置确定版本、模块、数据库结构与行为。历史设计、旧路径、旧分支和测试记录不能充当当前事实。

保留已有修改、删除和未跟踪文件；编辑前重新读取目标内容，不覆盖其他会话的更新。总目录旧 `README.md` 中“丢弃本地未提交改动”的要求不能作为清理授权。

不自动执行 `reset`、`restore`、`clean`、`stash`、分支切换或远程配置修改。提交、推送、上游同步、正式发布及破坏性操作须有明确授权，不因编写文档顺带执行。

## 3. 模块与定位

模块清单以 `project/settings.gradle.kts` 为准。

| 模块 | 职责 |
| --- | --- |
| `app` | Compose UI、ViewModel、生成服务、工具编排、Repository 与持久化 |
| `ai` | 模型供应商协议、消息抽象、流式响应与工具调用 |
| `workspace` | 工作区文件、独立 HOME、Kotlin 有限命令、便携迁移与 native 实现 |
| `search` | 多供应商搜索接口 |
| `document` | PDF、DOCX、PPTX、EPUB 等附件解析 |
| `common` | 通用工具与扩展 |
| `highlight` | 代码高亮 |
| `material3` | Material 色彩工具 |
| `app:baselineprofile` | 性能配置文件生成 |
| `build-logic` | 独立引入的 Gradle 构建约定，不是应用运行模块 |

应用业务代码位于 `project/app/src/main/java/me/rerere/rikkahub/`：

- 聊天生成：`ui/pages/chat/`、`service/ChatService.kt`、`data/ai/GenerationHandler.kt`。
- 消息转换：`data/ai/transformers/Transformer.kt`。
- AI 工具：`data/ai/tools/`；工作区工具为 `WorkspaceTools.kt`，历史参考为 `ConversationTools.kt`。
- 会话持久化：`data/repository/ConversationRepository.kt`、`data/db/dao/ConversationDAO.kt`。
- 数据库及迁移：`data/db/AppDatabase.kt`、`data/db/migrations/`。
- 总结：`data/summary/ConversationSummary.kt`；工作区业务与备份：`data/workspace/`。

模型协议代码位于 `project/ai/src/main/java/me/rerere/ai/`，工作区核心位于 `project/workspace/src/main/java/me/rerere/workspace/`。`locale-tui`、`trace-cli`、`tools/runtime-probe` 是辅助工具，按各自说明使用，不当成 Android 模块。

## 4. 修改原则

- 沿现有分层修改，保持 UI、生成编排、Provider 协议与持久化职责清晰；不顺带重构、不升级无关依赖。
- Kotlin、Compose、命名和资源使用遵循相邻代码；需要本地化时使用字符串资源与页面前缀，不为无关功能批量翻译。
- 修复缺陷时增加或更新针对性回归测试，覆盖正常、失败与边界路径。
- 流式协议修改检查分片合并、工具调用、结束、异常和取消；保留消息角色与内容 part 的语义。
- 版本号、SDK、依赖和数据库号从实际配置读取，不在业务代码或说明中复制成长期不变的常量。
- 只保留有工程价值的注释；不额外创建总结文档，不在源码、日志或交付物中加入工作过程与自我评价。

## 5. 数据与安全约束

### 会话、检索与数据库

- `MessageNode` 包含备选消息，不能把全部备选消息当作当前可见分支或模型上下文。
- 永久会话编号全局唯一，删除后不复用；新增与导入保持 Repository 的事务分配路径。
- AI 历史搜索保持当前助手范围并排除当前会话；App 消息搜索同时匹配标题与正文，默认当前助手，可切换所有助手。
- 总结来源必须属于来源助手；保留可见分支选择、取消、分块和上下文缓存语义。
- Room 结构变更同步实体、数据库版本、迁移实现与注册、`project/app/schemas/`、备份恢复 schema gate 和迁移测试。不得靠删除数据库或工作区解决兼容问题。

### 应用内便携工作区

- AI 文件工具只接受 `/workspace` 及其子路径，保留路径规范化、越界与符号链接检查，以及助手/会话归属校验。这里的 `/workspace` 是应用运行时路径，不是本地源码目录。
- 应用 DI 默认 `portableRuntime=true`，`KotlinWorkspaceShellRunner` 仅支持有限命令、管道、重定向、`&&` 与本地 Python，不是完整 Bash/apt。默认 UI 无 Linux 安装/环境切换/apt，终端为命令输入、执行与取消。
- Python 通过同 UID、非导出的 `:workspace_python` 工作进程运行，每次执行后终止并确认退出。路径映射不是安全隔离，不承诺阻止 Python 访问应用数据或联网；AI 文件工具边界不变。
- 独立 HOME 迁移先校验并 fsync 文件、目录及 marker，再删除旧 linux；失败保留源，全部工作区成功后才删 shared-linux，不靠删目录绕过错误。
- PRoot 与 termux native 库已从打包排除，QuickJS/libworkspace 保留；旧 Linux/apt 源码不代表默认运行能力。

### 凭据与用户数据

- `local.properties`、私有 `google-services.json`、签名文件、口令、API Key、MCP token、真实数据库与敏感备份不进 Git，不复制进公开交付物或日志。
- 当前备份/快照 v2 使用 `files.tar` + `home.tar`，无 Linux 或源设备 ABI 依赖。备份恢复兼容带完整清单的旧 Linux v2 完整包及其 v1 增量包：保留聊天、设置、附件和工作区 files/HOME，Linux 载荷校验后丢弃并清空旧运行环境元数据；新导出与标准验证仍要求便携格式。拒绝归档 v1、旧加密包和无 manifest 归档。新备份仍为可能含凭据的明文 ZIP，SHA-256 不代表保密或来源认证。
- 测试使用隔离 Debug 包与测试数据；不得覆盖正式应用数据或为验证覆盖安装而擅自卸载正式应用。

## 6. 构建与验证

- 每次重新打包都必须升级版本号，无论源码是否变化、是否正式发布。打包前在 `project/app/build.gradle.kts` 中递增 `versionCode`，并按现有版本格式升级 `versionName`；不得沿用上一次打包的版本号。

先在总目录使用 PowerShell 执行 `./tools/check-environment.ps1`。脚本与 `environment/local.properties.template` 含旧机器的路径候选值，应核对本机环境，不直接照搬。维护指南推荐本机 JDK 21；应用 JVM target 为 17，两者不同。

构建要求以这些文件为准：`project/gradle/wrapper/gradle-wrapper.properties`、`project/gradle/libs.versions.toml`、`project/app/build.gradle.kts`、`project/workspace/build.gradle.kts`。Chaquopy 17.1.0 / Python 3.13 要求构建机 `python` 同主次版本；固定 numpy 1.26.2、pandas 2.1.3、matplotlib 3.8.4、openpyxl 3.1.5。使用仓库 Gradle Wrapper，不用全局 Gradle 替代；不要因依赖下载失败擅自更换仓库来源。

以下命令均从 `project/` 执行，按修改范围选择，不要求每次全部运行。Git Bash/Linux 使用 `./gradlew`；PowerShell 改用 `.\gradlew.bat`。

```bash
git diff --check
./gradlew :ai:testDebugUnitTest
./gradlew :workspace:testDebugUnitTest
./gradlew :app:testDebugUnitTest -x :app:processDebugGoogleServices
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

- 完整 APK 构建需要维护者提供正确的 `project/app/google-services.json`；禁止生成随机私有配置来掩盖缺失。
- `-x :app:processDebugGoogleServices` 仅用于相关测试/编译任务，不作为完整 APK 或运行时 Firebase 配置方案。
- 仅开发或模拟器验证不自动构建/发布 Release；用户要求交付安装包时默认使用正式签名的 Release 版，输出位于 `project/app/build/outputs/apk/release/`，包名为 `me.rerere.rikkahub`。
- Debug 构建仅用于开发验证，默认不发布、不交付 Debug 安装包；仅在用户明确要求时交付。
- 设备测试入口为 `:app:connectedDebugAndroidTest`、`:workspace:connectedDebugAndroidTest`；只对已授权的测试设备/模拟器执行，事先确认目标设备。
- 工作区权限、数据库迁移、备份恢复和流式协议变更，分别检查对应模块的 `src/test/`、`src/androidTest/` 与协议测试夹具；详细测试地图见维护指南。
- 区分环境阻塞、既有失败与本次回归；没有对应基线证据，不把失败直接归为历史问题。

## 7. 完成与交付

- 结束前在 `project/` 复查 Git 状态与差异，确认没有覆盖已有工作，也没有混入凭据、缓存或无关产物。
- 按功能变化同步现有说明；模块、构建、数据库或安全边界变化同步维护指南，不复制旧验收结论。
- 用户要求打包、发布或交付安装包时，默认提供使用正式签名的 Release 版，不发布 Debug 安装包；正式签名不可用时报告阻塞，不以 Debug 包替代。交付前核对源码状态、包名、版本、ABI、签名连续性及 SHA-256，按现有归档规范记录。
- 使用简体中文简洁汇报实际修改、验证结果和剩余阻塞。区分通过、失败、跳过和未执行；编译通过不等于设备验证，历史记录不等于当前版本验收。
