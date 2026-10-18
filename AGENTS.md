# Repository Guidelines

## 当前说明与协作边界

- 用户说明见 [README_ZH_CN.md](README_ZH_CN.md)，开发与维护入口见 [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)，专题索引见 [docs/README.md](docs/README.md)。
- 当前可写源码为 `C:/Users/jch/heimaoai/project`；旁边 deliveries/history/evidence 默认只读，不将旧源码或别的 Windows 项目当作本仓库。
- 开始和结束时检查 `git status --short`、`git branch --show-current`、`git remote -v`，保留现有未提交修改与未跟踪文件。不能根据旧父目录说明自动丢弃改动。
- 不自动 reset/restore/clean/stash，不覆盖别的会话新写内容；提交、推送、上游同步和正式发布需单独授权。
- 当前 AI Shell 按环境分流：共享 Linux 用 `WORKSPACE_ONLY`，私有 Linux 用 `WORKSPACE_PACKAGE`，人工终端用 `FULL_ROOTFS`。AI 文件工具仍限 `/workspace`；公共 apt 走计划、指纹审批和排他执行。修改时不要套用旧版“一律仅文件区”说明。
- 当前新备份为可能含凭据的明文 ZIP。密钥、口令、local.properties、私有 Google 配置、真实数据库和敏感备份不进 Git、不公开上传。
- 历史设计与交接中的旧路径、数据库版本、签名和通过记录不能自动视作当前事实。以源码和对应版本的验证为准。

## Project Overview

黑猫ai is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Build, Test, and Development Commands

```bash
git diff --check
./gradlew :app:assembleDebug     # 完整 APK 需要 app/google-services.json
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
- **workspace**: Per-workspace files, PRoot shell modes, shared/private Linux and structured apt execution. PRoot is not a kernel-level hostile-code sandbox.
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

- 构建配置、测试地图及发布步骤见维护指南；PowerShell 使用 `.\gradlew.bat`，本机推荐 JDK 21，JVM target 17。
- Room 当前版本以 `AppDatabase.kt` 为准，结构变更同步迁移注册、app/schemas、备份 schema gate 和设备迁移测试。
- 保持永久会话编号不复用、总结来源助手归属、AI 历史搜索助手范围，以及全局 App 搜索原有语义。
- 只编译或存在测试文件不等于通过端到端验证；设备测试、Provider 调用和 APK 安装没执行就如实说明。

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
