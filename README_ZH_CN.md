# 黑猫ai

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的原生 Android AI 聊天客户端。本仓库是独立维护的 fork，不是上游官方发行版。

[English](README.md) · [繁體中文](README_ZH_TW.md) · **简体中文**

## 项目说明入口

- **[开发与维护指南](docs/PROJECT_GUIDE.md)**：环境、构建、架构、源码地图、数据兼容、安全边界、测试、发布和常见问题。
- [文档目录](docs/README.md)：区分当前指南、设计方案和历史验收记录。
- [协作约定](AGENTS.md)：修改本仓库前必读。

## 主要功能

- 多供应商聊天：OpenAI、Google、Anthropic 类型，支持自定义接口、模型、请求头和请求体。
- Jetpack Compose / Material 3 界面、深色模式、流式输出、消息分支及多模态附件。
- Markdown、代码高亮、数学公式、表格、Mermaid 和应用内 WebView 内容预览。
- 助手独立配置、提示词模板、Mode Injection、MCP 和联网搜索。
- 普通记忆与分层记忆；本地 FTS 全文检索；AI 历史搜索限制在当前助手。
- 历史对话永久编号、多选来源总结、可另选总结助手。
- 工作区文件工具、PRoot Linux、手动终端、共享/私有 Linux 环境及公共 apt 计划审批。
- 可携带工作区环境的备份恢复、运行日志、聊天生成后台服务与消息队列。

**不再提供**：本地 Web 服务器/Web 多端访问、ASR/TTS、快捷消息、SillyTavern 角色卡导入、Lorebook 世界书，以及旧更新/赞助/关于入口。应用内 WebView、普通附件导入、聊天导出和 Mode Injection 仍保留。

自动上下文压缩在请求前总结较早的历史，不删除聊天记录。在模型设置中配置触发长度和压缩目标，默认分别为估算的 100000 和 10000 token；触发长度设为 0 可关闭。

## 下载与使用

从[本 fork 的 Releases](https://github.com/ani13-teach/rikkahub/releases)查看可用产物；`nightly` 是开发预发布版，不等同于稳定版。本机确认过的交付物存放在源码旁的 `../deliveries/`。本次文档核对未联网确认远程发行状态。

上游网站、Google Play 和上游社区属于 RikkaHub，不能当成本 fork 的发布或支持入口。本 fork 保留正式包名 `me.rerere.rikkahub`，安装前须核对签名并备份；与上游签名不同时不能直接覆盖升级。

1. 安装适合设备的 APK（Android 8.0/API 26 及以上；arm64-v8a 或 x86_64）。
2. 在供应商设置中填写自己的接口地址和 API Key，添加模型并选给助手。
3. 先测试普通聊天，再按需启用记忆、搜索、MCP 或工作区工具；工具调用需要模型支持。
4. Linux 环境需单独安装；共享环境的 AI 普通 Shell 不提供完整 Linux/Python，完整开发工具应使用私有环境，详见维护指南。

## 快速构建

推荐本机使用 JDK 21。源码当前使用 Gradle 9.5.0、AGP 9.3.1、Kotlin 2.4.10、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1。版本以构建脚本为准。

在仓库根目录配置被 Git 忽略的 `local.properties`（只填写本机 SDK 路径）；完整 APK 构建还需要自己的 `app/google-services.json`。

```bash
# Git Bash / Linux / macOS；PowerShell 使用 .\gradlew.bat
./gradlew --version
./gradlew :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/`，Debug 包名带 `.debug`。正式版需要另配发布密钥；详细步骤与测试命令见[维护指南](docs/PROJECT_GUIDE.md)，不要把 Debug 签名当作正式签名。

## 隐私与安全

- 使用模型、搜索、MCP 时，相应内容会发送到所配置的服务；这不是完全离线应用。
- **当前新导出的备份是明文 ZIP，可能含 API Key、MCP/同步凭据、聊天和工作区数据**。仅存可信位置，不上传公共仓库或公开分享；校验哈希不等于加密。
- AI 文件工具限制在 `/workspace`；Shell 按共享、私有和人工终端分模式。PRoot 不是抵抗恶意原生程序的内核级沙箱，也不保证断网。
- 发布密钥、口令、`local.properties`、Google Services 私有配置和真实用户数据库不得进入 Git。

## 贡献与许可证

保持小范围、可审阅的修改，补充对应测试和文档；不要顺手删除或覆盖别人尚未提交的工作。提交、推送、上游同步和正式发布需单独授权。

基于 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。分发修改版本或提供相关网络服务时，应遵守适用的源码提供义务。上游原作者与贡献者的版权及许可证仍保留在源码和 LICENSE 中。
