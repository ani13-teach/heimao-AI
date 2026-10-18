# 黑猫ai

基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 的原生 Android AI 聊天客戶端。本倉庫是獨立維護的 fork，不是上游官方發行版。

[English](README.md) · **繁體中文** · [完整简体中文说明](README_ZH_CN.md)

## 專案文件

- [開發與維護指南（簡體中文）](docs/PROJECT_GUIDE.md)：環境、架構、建置、測試、安全邊界、資料相容與簽署交付。
- [文件目錄](docs/README.md)：區分當前指南與歷史設計、驗收記錄。
- [協作規範](AGENTS.md)。

## 功能與範圍

提供多供應商聊天、串流輸出、訊息分支、多模態附件、Markdown、MCP、網路搜尋、自訂助手、Mode Injection、一般與分層記憶、助手範圍歷史搜尋、永久對話編號與歷史總結。

工作區支援 PRoot、人工終端、共享/私有 Linux、公共 apt 計畫審批與可攜式備份恢復；亦保留執行記錄、背景生成與訊息佇列。

已移除本地 Web 伺服器/Web 多端存取、ASR/TTS、快捷訊息、SillyTavern 角色卡匯入、Lorebook 世界書，以及舊更新/贊助/關於入口。應用內 WebView、一般附件匯入與聊天匯出仍保留。

自動上下文壓縮會在請求前總結較早的歷史，不刪除聊天記錄。在模型設定中配置觸發長度與壓縮目標，預設為估算的 100000 與 10000 token；觸發長度設為 0 可關閉。

## 下載與建置

從[本 fork 的 Releases](https://github.com/ani13-teach/rikkahub/releases)查看可用產物；`nightly` 為開發預發布版。本次未連網核對遠端發行狀態。上游官網與 Google Play 不是此 fork 的下載入口。

需求：Android 8.0/API 26 以上，arm64-v8a 或 x86_64。安裝後設定自己的供應商網址、API Key 與模型；Linux 環境需另行安裝。與其他發行版簽章不同時不能直接覆蓋升級，請先備份。

本機建議 JDK 21；目前建置配置為 Gradle 9.5.0、AGP 9.3.1、Kotlin 2.4.10、SDK 37、NDK 28.2.13676358、CMake 3.22.1。設定忽略追蹤的 `local.properties`，完整 APK 另需自己的 `app/google-services.json`。

```bash
./gradlew --version
./gradlew :app:assembleDebug
```

PowerShell 使用 `.\gradlew.bat`。Debug 產物在 `app/build/outputs/apk/debug/`，包名帶 `.debug`；正式簽署及測試詳見維護指南。

## 隱私與安全

- 使用模型、搜尋與 MCP 時，相關內容會傳到所設定的服務，並非完全離線。
- **新匯出的備份是明文 ZIP，可能包含 API Key、MCP/同步憑證、聊天與工作區資料**；不可公開分享，雜湊校驗不是加密。
- AI 檔案工具限 `/workspace`；共享與私有環境的 AI Shell 權限不同，人工終端保留完整 Linux。PRoot 不是抵抗任意惡意原生程式的核心級沙箱，也不保證斷網。
- 不提交簽署金鑰、密碼、Google Services 私有設定或真實使用者資料庫；保留其他人尚未提交的修改。

## 授權

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。保留上游作者與貢獻者的版權聲明，並遵守適用的原始碼提供義務。
