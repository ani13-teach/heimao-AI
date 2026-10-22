# Repository Guidelines

## 当前说明与协作边界

- 仓库级工作约定见 [AGENT.md](AGENT.md)。仅要求开发或模拟器验证时不自动发布 Release；用户要求打包、发布或交付安装包时，默认提供正式签名的 Release 版；默认不发布、不交付 Debug 安装包，正式签名不可用时不以 Debug 包替代。

- 用户说明见 [README_ZH_CN.md](README_ZH_CN.md)，开发与维护入口见 [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)，专题索引见 [docs/README.md](docs/README.md)。
- 当前可写源码为 `C:/Users/jch/heimaoai/project`；旁边 deliveries/history/evidence 默认只读，不将旧源码或别的 Windows 项目当作本仓库。
- 开始和结束时检查 `git status --short`、`git branch --show-current`、`git remote -v`，保留现有未提交修改与未跟踪文件。不能根据旧父目录说明自动丢弃改动。
- 不自动 reset/restore/clean/stash，不覆盖别的会话新写内容；提交、推送、上游同步和正式发布需单独授权。
- 应用 DI 默认 `portableRuntime=true`，使用 `KotlinWorkspaceShellRunner` 的有限命令、管道、重定向、`&&` 和本地 Python，不是完整 Bash/apt。默认 UI 无 Linux 安装/切换和 apt，终端为命令输入、执行与取消。AI 文件工具仍限 `/workspace` 并保留归属校验。Python 为同 UID、非导出的 `:workspace_python` 工作进程，每次执行后终止并确认退出；路径映射不是安全隔离。
- 当前备份/快照 v2 使用 `files.tar` + `home.tar`，不携带 Linux 或依赖源设备 ABI。备份恢复兼容旧 Linux v2 完整包及其 v1 增量包，仅迁移聊天、设置、附件和工作区 files/HOME；Linux 载荷校验后丢弃，清空旧运行环境元数据。新导出与标准验证仍要求便携格式，拒绝归档 v1、旧加密包和无 manifest 归档。新备份仍为可能含凭据的明文 ZIP。密钥、口令、local.properties、私有 Google 配置、真实数据库和敏感备份不进 Git、不公开上传。
- 历史设计与交接中的旧路径、数据库版本、签名和通过记录不能自动视作当前事实。以源码和对应版本的验证为准。

## Project Overview

黑猫ai is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Build, Test, and Development Commands

```bash
git diff --check
./gradlew :app:assembleRelease   # 仅在要求正式安装包时；需要签名配置及 app/google-services.json
./gradlew :app:testDebugUnitTest -x :app:processDebugGoogleServices
./gradlew :workspace:testDebugUnitTest
./gradlew :ai:testDebugUnitTest
./gradlew :app:lintDebug         # 失败需区分既有问题与本次回归
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **workspace**: Per-workspace files, independent HOME, Kotlin bounded commands and portable migration. PRoot/termux native libraries are excluded from packaging; QuickJS/libworkspace remain. Python path mapping is not a security sandbox.
- **app:baselineprofile**: Runtime performance profile generation.
- **build-logic**: Included build for shared Gradle conventions (not an app runtime module).

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and Mode Injection (Lorebook has been removed). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## 验证与数据兼容

- 构建配置、测试地图及发布步骤见维护指南；PowerShell 使用 `.\gradlew.bat`，本机推荐 JDK 21，JVM target 17。Chaquopy 17.1.0 / Python 3.13 要求构建机同主次版本 Python；numpy 1.26.2、pandas 2.1.3、matplotlib 3.8.4、openpyxl 3.1.5 固定版本。
- Room 当前版本以 `AppDatabase.kt` 为准，结构变更同步迁移注册、app/schemas、备份 schema gate 和设备迁移测试。
- 保持永久会话编号不复用、总结来源助手归属及 AI 历史搜索助手范围。App 消息搜索默认当前助手，可切换所有助手，同时匹配标题与正文。
- 只编译或存在测试文件不等于通过端到端验证；设备测试、Provider 调用和 APK 安装没执行就如实说明。

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
