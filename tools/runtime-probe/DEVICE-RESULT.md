# 独立探针真机结果（2026-09-21）

## 结论

本次 Android 应用身份下的探测中，Landlock ABI 查询被系统 seccomp 拦截。最小 seccomp 自测成功。因此不能在这台设备的本次配置上直接采用 Landlock + PRoot 方案；seccomp 自测通过也不能替代按目录访问控制。

## 设备与配置

- 设备型号：24129PN74C，设备代号 dada。
- Android 15 / API 35，arm64-v8a。
- 内核：6.6.30-android15-8-geae86f166c48-abogki367569362-4k。
- 探针包名：me.rerere.heimao.runtimeprobe；targetSdk 37；debuggable=true。
- 应用 SELinux 域：untrusted_app；初始 Seccomp=2、NoNewPrivs=0。

## 最终一轮

| 测试 | 结果 |
|---|---|
| Landlock ABI 查询 | 子进程 PID 5445，SIGSYS (31)，未超时 |
| 系统崩溃日志 | SYS_SECCOMP；`Cause: seccomp prevented call to disallowed arm64 system call 444` |
| seccomp 添加过滤器 | PR_SET_NO_NEW_PRIVS 返回 0；PR_SET_SECCOMP 返回 0 |
| seccomp 实效 | getppid 返回 -1 / EPERM；getpid 正常返回 5735 |
| seccomp 子进程 | exit=0，timeout=no |

原始结果位于本目录 `build/device-result.txt`，相关崩溃日志摘录位于 `build/device-crash-excerpt.txt`。这些本地构建文件默认忽略进 Git。

## 测试中修复

- ADB exec-out 在结果文件尚未出现时可能把错误文本放到 stdout，并返回 0。读取脚本现在必须同时见到报告起止标记，才保存为结果。
- 子进程管道已 EOF 但尚未回收时，POLLHUP 立即返回导致循环计数过快，误报超时。已改为 EOF 时定时等待，再非阻塞回收；重新构建安装后未再出现该误报。
- 第一轮异常结果不作为最终通过依据，以上表格来自修正后的复测。

## 边界与下一步

- 只安装、更新、运行独立探针。没有改变正式 App 代码、数据或系统 SELinux/seccomp 设置。
- SIGSYS 终止的是探针实验子进程；父进程正常生成报告。
- 本次不能判断内核自身是否编入/启用 Landlock，因为调用在到达其实现前就被拦截。
- 没有实测完整沙箱、PRoot/Python 在保护下的兼容性、公共环境写保护或不同工作区之间的强隔离。
- 探针为调试配置，不能自动代替所有正式应用配置的结论；但已足以阻止现在把 Landlock 当成已验证可用前提。
- 后续若仍要求强隔离，应先验证独立身份与受控文件访问后端，而不是继续在现有 PRoot 上简单拼接共享目录。
