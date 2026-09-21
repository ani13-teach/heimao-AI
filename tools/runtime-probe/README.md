# 独立 Android 运行时探针

包名 `me.rerere.heimao.runtimeprobe`，界面名称 **Runtime Probe**。与正式黑猫 AI 分开安装和存储；不读取正式 App 文件，不申请网络、存储权限，不更改设备安全设置。

## 第一阶段验证范围

1. 在应用派生的子进程查询 Landlock ABI：`landlock_create_ruleset(NULL, 0, VERSION)`。不创建规则，不调用 `landlock_add_rule` 或 `landlock_restrict_self`。
2. 在另一个子进程设置 `no_new_privs` 并添加 seccomp 过滤器，验证 `getppid` 被返回 `EPERM`，同时 `getpid` 正常。这只能增加该临时子进程的限制，不能放宽已有系统策略。

报告包含本进程 UID、内核版本、SELinux 上下文、Seccomp/NoNewPrivs，以及子进程的输出、退出码或信号。

每个实验子进程有 3 秒预算；超时发送 SIGKILL，最多再观察 1 秒回收情况。回收失败记录未知状态，不虚报成功。读取管道为非阻塞，子进程异常退出前的输出会保留。结果先写临时文件，再原子替换 `files/probe-result.txt`。

## 本机构建

```bash
python C:/Users/jch/heimaoai/project/tools/runtime-probe/build.py
```

使用本地 JDK 21、Android SDK 37、Build Tools 37.0.0、NDK 28.2.13676358。不运行正式项目 Gradle，不下载依赖。构建脚本可用 `--sdk`、`--jdk` 覆盖根路径。

产物：`build/Heimao-Runtime-Probe.apk`。包含 arm64-v8a 和 x86_64，minSdk 26、targetSdk 37。签名是 `build/probe-debug.jks` 中的专用测试密钥，不使用正式 App 签名。整个 `build/` 忽略进 Git。

## 连接手机后运行

手机开启 USB 调试，连接电脑并接受设备授权。确认 ADB 中只有一台已授权设备，然后运行：

```bash
python C:/Users/jch/heimaoai/project/tools/runtime-probe/run-device.py
```

脚本只安装并启动这个独立包；它会停止上次的探针进程、删除探针自己的旧结果，防止读到上次输出。不停止、覆盖或卸载正式版。

报告保存在 `build/device-result.txt`，也显示在手机屏幕，可选中复制。没有手机时不会生成实测报告。若手机禁止 USB 安装，可手动安装同一 APK，在屏幕读取结果。

## 如何判断

| 结果 | 含义 |
|---|---|
| Landlock `returned >= 1` | 本次应用子进程可查询 ABI；仍未验证创建和执行限制 |
| Landlock `errno=38` | ENOSYS，本次调用没有可用实现；不能仅据此识别具体系统层原因 |
| Landlock `errno=95` | EOPNOTSUPP，可能内核支持但没有启用 |
| `signal=31 name=SIGSYS` | 与系统调用被拦截一致；结合子进程崩溃日志里的 SYS_SECCOMP 再归因 |
| seccomp `verdict=getppid_blocked_with_EPERM_control_syscall_ok` | 此子进程添加的最小过滤器按预期生效 |
| `timeout=yes` / `exit=unknown` | 未完成，不能作为成功证据 |

**通过也不等于共享环境安全。** 后续仍需验证 PRoot、Python/pip、公共文件防改、跨工作区访问、既有 FD 和进程间访问。不要通过关闭 SELinux/seccomp、降低 target SDK 或 root 来让探针通过。

**ADB shell 不等于 App。** 此探针由正常 Android Activity 启动，再派生子进程，保留应用所受的系统限制。探针使用 `debuggable=true` 方便读取结果；调试属性可能影响部分系统行为，因此正向结果最终应在与正式版匹配的非调试配置复验。

## 已执行的验证

- Java 完整编译、D8 转 dex：通过（Java 8 source/target 有过时警告）。
- 两种 ABI 的 native 库构建：通过，`-Wall -Wextra -Werror`。
- APK v2/v3 签名验证：通过。
- 安装包包名、min/target SDK、启动 Activity、两种 ABI：已核对。
- 真机安装和运行：已在 Android 15 / arm64 设备完成。Landlock 查询被系统 seccomp 拦截；seccomp 最小自测通过。详见 [DEVICE-RESULT.md](DEVICE-RESULT.md)。

## 文件

- `AndroidManifest.xml`：独立包与 Activity。
- `src/me/rerere/heimao/runtimeprobe/ProbeActivity.java`：界面、后台调用和原子保存结果。
- `native/probe.c`：查询、自测、限时子进程和状态报告。
- `build.py`：本地构建与签名。
- `run-device.py`：单设备安装运行与结果取回。
