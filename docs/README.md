# 项目文档目录

## 当前入口

| 文档 | 用途 |
| --- | --- |
| [简体中文 README](../README_ZH_CN.md) | 项目定位、功能、使用、快速构建与隐私提醒 |
| [开发与维护指南](PROJECT_GUIDE.md) | 当前源码地图、环境、Shell 边界、数据兼容、测试、发布与排障 |
| [AGENTS.md](../AGENTS.md) | 本仓库协作要求；CLAUDE.md 指向该文件 |

本次指南核对基线为 `cf7f346b`。运行结果、版本和分支应再次实查；本文索引不把旧验收套用到当前工作树。

## 专题资料

| 文档 | 阅读说明 |
| --- | --- |
| [共享 Linux 实施记录](SHARED_LINUX_IMPLEMENTATION.md) | 共享环境、旧备份故障调查及当时验收；后续 apt/Shell 边界以当前指南与源码为准 |
| [生成链路参考](references/chat-generation-pipeline.md) | 生成与转换链定位；涉及在途开发时再核对代码 |
| [缓存设计](HEIMAO_CACHE_DESIGN.md) | 缓存设计背景，不是全部当前行为的证明 |
| [可携带工作区设计](HEIMAO_PORTABLE_WORKSPACE_DESIGN.md) | 归档设计背景；实际导出格式和隐私提醒以当前指南为准 |
| [可携带工作区实施计划](HEIMAO_PORTABLE_WORKSPACE_IMPLEMENTATION_PLAN.md) | 计划，不等同于完成记录 |
| [记忆缓存抓包报告](MEMORY_PROMPT_CACHE_CAPTURE_REPORT.md) | 当时环境下的观测结论，不保证所有模型/渠道一致 |
| [提示词缓存优化计划](prompt-cache-optimization-plan.md) | 历史计划，实施状态需核对 |
| [reasoning 字段修复计划](reasoning-level-field-fix-plan.md) | 问题背景与方案 |
| [共享 Linux/Python 隔离研究](research_shared_linux_python_isolation_20260921.md) | 方案比较，不能代替实际安全边界验证 |

## 历史交接与需求计划

- [早期维护交接](HEIMAO_AI_HANDOFF.md)：保留旧电脑路径、旧 APK、旧数据库和历史验收信息；不是当前环境操作手册。
- [助手工作区绑定修复计划](plans/2026-08-27-1041-fix-assistant-workspace-binding-plan.md)。
- [聊天自动重试计划](plans/2026-09-10-0918-feat-chat-auto-retry-plan.md)。
- [分层记忆编辑计划](plans/2026-09-10-1150-feat-layered-memory-local-edit-plan.md)。

新增功能文档请在这里登记；清楚标注“计划”“实现说明”或“实测报告”。发布验收按 APK、commit、工作树与构建记录定位，不使用无限期有效的“全通过”表述。
