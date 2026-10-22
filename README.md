# 黑猫ai / Heimao AI

An independently maintained Android fork of [RikkaHub](https://github.com/rikkahub/rikkahub), built with Kotlin, Jetpack Compose and Material 3. This repository is **not** an official upstream distribution.

**[完整简体中文说明](README_ZH_CN.md)** · [繁體中文](README_ZH_TW.md)

## Documentation

- [Development and maintenance guide (Chinese)](docs/PROJECT_GUIDE.md): architecture, setup, builds, tests, database compatibility, workspace security and signed releases.
- [Documentation index](docs/README.md): current guidance versus historical plans and verification reports.
- [Repository guidelines](AGENTS.md): read before editing.

## Features

- OpenAI-, Google- and Anthropic-type providers; configurable models, endpoints, headers and request bodies.
- Streaming chat, message branches, multimodal attachments and rich Markdown rendering.
- Configurable assistants, prompt templates, Mode Injection, MCP and web search.
- Memory and layered memory, assistant-scoped history tools, permanent conversation numbers and multi-conversation summaries.
- PRoot workspaces, manual terminals, shared/private Linux environments and approved public apt plans.
- Portable workspace backup/restore, runtime logs, background generation and message queues.

Removed features include the local Web server/Web UI, ASR/TTS, quick messages, SillyTavern character-card import, Lorebook, and old update/sponsor/about entries. In-app WebView previews and ordinary file imports/exports remain available.

Automatic context compression summarizes older completed user turns into separate, persistent blocks without deleting chat records or rewriting existing summaries. The current turn and the most recent completed turn stay in full. The trigger and target lengths are configured in model settings; defaults are 100,000 and 10,000 estimated tokens. Set the trigger to 0 to stop creating new blocks; existing blocks still apply. Models with tool support can search summaries or saved originals using `context_search` and read individual blocks in pages using `context_read`. These tools stay within the current conversation and selected branch; reading originals does not undo compression. Binary attachments and hidden reasoning are excluded. Context blocks are included in database backups; editing sources, switching branches, or moving a conversation to another assistant invalidates affected blocks.

## Download and setup

Check [this fork's Releases](https://github.com/ani13-teach/rikkahub/releases) for available artifacts. `nightly` is a development prerelease; remote release availability was not checked in this documentation-only update. Upstream website/Play Store downloads are not this fork's releases.

Android 8.0/API 26 or later is required; supported ABIs are arm64-v8a and x86_64. Configure your own provider endpoint, API key and model after installation. Tool use requires a compatible model. Linux environments require separate installation.

The release package ID remains `me.rerere.rikkahub`; different signing certificates prevent in-place upgrades from other distributions. Back up first.

## Build

Current source configuration: Gradle 9.5.0, AGP 9.3.1, Kotlin 2.4.10, Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1. Local development recommends JDK 21; the source JVM target is 17.

Set your SDK path in ignored `local.properties` and provide your own `app/google-services.json` for a complete APK build.

```bash
./gradlew --version
./gradlew :app:assembleDebug
```

Use `.\gradlew.bat` in PowerShell. Debug APKs are written to `app/build/outputs/apk/debug/` with the `.debug` package suffix. Release signing, test commands and troubleshooting are documented in the maintenance guide.

## Privacy and safety

Configured model/search/MCP services may receive the data used by those requests. This is not a fully offline application.

**New backups are plaintext ZIP archives and may contain API keys, MCP/sync credentials, conversations and workspace data.** Keep them private. Hash verification is not encryption. Legacy encrypted-archive restore support does not make new exports encrypted.

AI file tools are restricted to `/workspace`. AI shells have different shared/private modes; manual terminals retain full Linux access. PRoot is not a kernel-level security boundary against arbitrary malicious native programs and does not guarantee network isolation.

Never commit keys, passwords, private Google Services configuration, `local.properties` or real user databases. Preserve existing uncommitted work and keep changes reviewable. Commits, pushes, upstream synchronization and releases require separate authorization.

## License and attribution

Licensed under [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). Upstream copyright and license notices remain in the source. Follow applicable source-availability obligations when distributing modifications or providing relevant network services.
