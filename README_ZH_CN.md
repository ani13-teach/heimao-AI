# 黑猫ai

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的原生 Android AI 聊天客户端。本仓库是独立维护的 fork，不是上游官方发行版。

[English](README.md) · [繁體中文](README_ZH_TW.md) · **简体中文**

## 项目说明入口

- **[开发与维护指南](docs/PROJECT_GUIDE.md)**：环境、构建、架构、源码地图、数据兼容、安全边界、测试、发布和常见问题。
- [文档目录](docs/README.md)：区分当前指南、设计方案和历史验收记录。
- [协作约定](AGENTS.md)：修改本仓库前必读。

## 主要功能

- 多供应商聊天：OpenAI、Google、Anthropic 类型，支持自定义接口、模型、请求头和请求体。
- 快速、标题、建议、翻译、OCR、压缩模型在同一个选择入口内配置主模型和有序备用链，最多 10 个备用模型，支持跨供应商，按“供应商/model id”选择；聊天助手保持单模型，不使用备用链。
- Jetpack Compose / Material 3 界面、深色模式、流式输出、消息分支及多模态附件。
- Markdown、代码高亮、数学公式、表格、Mermaid 和应用内 WebView 内容预览。
- 助手独立配置、提示词模板、Mode Injection、MCP 和联网搜索。
- 普通记忆与分层记忆；消息搜索同时匹配标题与正文，默认当前助手，可切换所有助手；AI 历史搜索限制在当前助手。
- 历史对话永久编号、多选来源总结、可另选总结助手。
- 工作区文件工具、内置有限命令执行、本地 Python 与命令输入式终端，无需安装 Linux。
- 工作区文件与独立 HOME 的 v2 完整/增量备份恢复、备份耗时记录、运行日志、聊天生成后台服务与消息队列。

**不再提供**：本地 Web 服务器/Web 多端访问、ASR/TTS、快捷消息、SillyTavern 角色卡导入、Lorebook 世界书，以及旧更新/赞助/关于入口。应用内 WebView、普通附件导入、聊天导出和 Mode Injection 仍保留。

工作区 Codemode 默认开启，可在助手的本地工具页关闭，与原 JavaScript 引擎开关独立。多步文件任务、批量读写、重复命令及结果筛选统计默认优先使用 Codemode；单次简单操作可直接调用工具。当前仅支持用 JavaScript 串行调用工作区 `read`、`write`、`edit`、`shell`，通过 `text(...)` 显式输出筛选后的文本结果；即使使用 `Promise.all` 也不会并行执行。聊天中可逐层展开执行状态、操作参数和结果，Markdown 与图片导出也保留执行详情；执行失败时，已经写入的文件不会回滚。Shell 正常结束时，非零退出码仍以原始 JSON 返回（包含 `exitCode`、`stdout`、`stderr`、`timedOut` 等字段），由 JavaScript 检查并继续处理；该操作记为失败，批次是否成功由 JavaScript 是否完成决定，超时、无效 Shell 结果或工具异常则停止执行。

自动压缩将较早且完整结束的历史合并为一份摘要，保留聊天原件、当前用户轮和最近一个完整用户轮。未达到触发长度时复用同一份摘要并追加近期历史；达到阈值后，将旧摘要与新增历史一起压缩为新摘要。在模型设置中配置触发长度和压缩目标，默认分别为估算的 100000 和 10000 token；触发长度设为 0 后停止更新摘要，已有摘要继续使用。旧版摘要块在下一次触发压缩时合并。支持工具调用的模型可通过 `context_search` 搜索摘要或已保存原文，通过 `context_read` 分页读取指定块。回查限定在当前会话和选中分支，读取原文不会撤销压缩；不读取二进制附件和隐藏思考。数据库备份包含摘要块；编辑来源、切换分支或将会话移到其他助手后，受影响的摘要块失效。

## 下载与使用

从[本 fork 的 Releases](https://github.com/ani13-teach/rikkahub/releases)查看可用产物；`nightly` 是开发预发布版，不等同于稳定版。本机确认过的交付物存放在源码旁的 `../deliveries/`。本次文档核对未联网确认远程发行状态。

上游网站、Google Play 和上游社区属于 RikkaHub，不能当成本 fork 的发布或支持入口。本 fork 保留正式包名 `me.rerere.rikkahub`，安装前须核对签名并备份；与上游签名不同时不能直接覆盖升级。

1. 安装适合设备的 APK（Android 8.0/API 26 及以上；arm64-v8a 或 x86_64）。
2. 在供应商设置中填写自己的接口地址和 API Key，添加模型并选给助手。
3. 先测试普通聊天，再按需启用记忆、搜索、MCP 或工作区工具；工具调用需要模型支持。
4. 工作区默认使用内置便携运行时，支持有限命令、管道、重定向、`&&` 和本地 Python，不是完整 Bash，也不支持 apt。终端通过输入命令、执行和取消操作，详见维护指南。

## 快速构建

推荐本机使用 JDK 21。源码当前使用 Gradle 9.5.0、AGP 9.3.1、Kotlin 2.4.10、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1。版本以构建脚本为准。内置 Python 使用 Chaquopy 17.1.0 / Python 3.13，构建机需提供同主次版本的 Python；固定依赖为 numpy 1.26.2、pandas 2.1.3、matplotlib 3.8.4、openpyxl 3.1.5。

在仓库根目录配置被 Git 忽略的 `local.properties`（只填写本机 SDK 路径）；完整 APK 构建还需要自己的 `app/google-services.json`。

```bash
# Git Bash / Linux / macOS；PowerShell 使用 .\gradlew.bat
./gradlew --version
./gradlew :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/`，Debug 包名带 `.debug`。正式版需要另配发布密钥；详细步骤与测试命令见[维护指南](docs/PROJECT_GUIDE.md)，不要把 Debug 签名当作正式签名。

## 隐私与安全

- 使用模型、搜索、MCP 时，相应内容会发送到所配置的服务；这不是完全离线应用。
- **当前新导出的备份是明文 ZIP，可能含 API Key、MCP/同步凭据、聊天和工作区数据**。仅存可信位置，不上传公共仓库或公开分享；校验哈希不等于加密。新备份/快照为 v2，工作区只含 `files.tar` 和 `home.tar`，不含 Linux 或绑定设备 ABI。可导入旧 Linux v2 完整备份及其 v1 增量备份，迁移聊天、设置、附件和工作区文件，旧 Linux 环境不恢复；拒绝归档 v1、旧加密包和无 manifest 归档。
- AI 文件工具仍限制在 `/workspace`，保留路径和助手/会话归属校验。Python 在同 UID、非导出的独立工作进程运行，每次执行后终止并确认退出；路径映射不是安全隔离，不保证阻止 Python 访问应用数据或联网。
- 发布密钥、口令、`local.properties`、Google Services 私有配置和真实用户数据库不得进入 Git。

## 贡献与许可证

保持小范围、可审阅的修改，补充对应测试和文档；不要顺手删除或覆盖别人尚未提交的工作。提交、推送、上游同步和正式发布需单独授权。

基于 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。分发修改版本或提供相关网络服务时，应遵守适用的源码提供义务。上游原作者与贡献者的版权及许可证仍保留在源码和 LICENSE 中。
