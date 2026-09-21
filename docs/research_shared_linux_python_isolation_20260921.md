# 黑猫 AI：共享 Linux/Python 与可靠隔离调查

**Date:** 2026-09-21
**Depth:** deep
**Confidence:** 90%（对约束与调查结论的信心，不是实现成功率）
**Sources:** 17 个联网参考页面，3 轮搜索，另有本地源码核对。

## Executive Summary

**共享一份 Linux/Python 可以省空间，但当前代码不能同时保证公共环境不可改、项目互不可见。** 本次没有找到一个已证实能直接套用的通用小改法。Android 的独立权限进程能提供真实隔离，却不能直接打开现有 Linux 文件树；Landlock 能限制文件访问，但 Android 应用调用权限和手机内核支持都是前置条件。建议先验证目标设备的应用进程能力，再决定是否开发共享后端。此次仅调查，未改执行代码、未编译、未安装探针、未执行设备命令。[S1][s1] [S2][s2] [S4][s4] [S5][s5]

这也不是说必须永远安装多套 Linux。它说明“共享存储”和“可靠隔离”是两个问题，不能用同一个 PRoot 挂载参数同时解决。现有私有 Linux 也只是减少共享文件互相污染，并不因此成为敌对代码安全沙箱。[S1][s1] [S13][s13]

## Key Findings

1. **当前 PRoot 是路径转换，不是权限隔离。** `!` 表示不跟随绑定目标的符号链接，不代表只读；`--root-id` 不产生 Android 管理员身份。[S1][s1]
2. **Landlock 值得验证，但不能直接作为产品前提。** Linux 支持其限制继承；Android 内核分支和应用系统调用名单还可能阻断它。[S2][s2] [S6][s6] [S7][s7] [S8][s8] [S11][s11] [S12][s12]
3. **Landlock 不等于完整只读。** 官方文档列出 chmod、chown、时间戳等限制缺口；早期版本甚至不能禁止文件截断。[S2][s2]
4. **独立权限进程不能直接复用现有路径后端。** AOSP 明确禁止 isolated app 自行打开应用数据文件，也限制直接创建网络套接字；传一个目录句柄不是自动放行整棵目录。[S4][s4]
5. **单独安装一个执行 APK 也不是完整答案。** 它与主 App 分开，但同一执行 APK 内的工作区仍可能共用身份；跨 APK 私有目录还受 Android 限制。[S5][s5]
6. **公共环境与项目库可以分开管理。** Linux 版 Python 的 venv 可基于一个基础 Python 创建；独立第三方库仍会重复，而且 venv 不是安全隔离机制。[S16][s16] [S5][s5]
7. **不能把设备探测放在普通聊天主进程里。** Android 禁止的系统调用可能触发 SIGSYS。测试应在专门的测试子进程中进行，不改变设备安全设置。[S15][s15]

## Detailed Analysis

### 1. 现有代码缺少什么保护？

现有流程用应用自身权限启动 PRoot。AI 命令和手动终端虽有不同挂载表，底层没有为工作区换一个 Android 身份。公共 Linux 如果仍归这个身份所有，单纯 chmod 去掉写权限，不能成为禁止拥有者恢复权限的保证。PRoot 的虚拟根目录也不能替代内核访问控制。[S1][s1] [S5][s5]

主代理核对的本地证据，路径相对 `project/`：

| 位置 | 已确认事实 |
|---|---|
| `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt:43–52` | files/linux/tmp 按工作区分别解析 |
| `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt:63–89` | 启动前 patch rootfs，再通过 ProcessBuilder 执行 |
| 同文件 `:142–259` | 非 files-only 模式根目录来自 linuxDir；没有内核只读设置 |
| `workspace/src/main/cpp/termux_pty.cpp:88–120` | fork、设置终端、execve；没有换 UID 或安装安全规则 |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt:8–25` | 有可替换的执行接口，但参数依赖普通文件路径 |
| 同文件 `:46–72` | 取消和超时调用 destroyForcibly；后代清理还依赖 PRoot 行为 |
| `app/src/main/AndroidManifest.xml:136–159` | 存在 DocumentsProvider 和前台服务；不是“整个应用没有 IPC” |
| `app/build.gradle.kts:20–24,103` | compile/target SDK 37、min SDK 26，原生库使用 legacy packaging |
| `workspace/src/main/java/me/rerere/workspace/RootfsRuntimeInspector.kt:83–97` | capabilityChecks 主要是文件、目录、架构检查，并非安全能力探测 |
| `docs/HEIMAO_PORTABLE_WORKSPACE_DESIGN.md:120–127` | 原设计明确区分防误操作与敌对代码隔离 |

这与问题 2、3 的关系是：新保护必须覆盖 AI、终端及它们的所有子进程，不能只替换一个 UI 入口。也不能把现有文件存在性检查改个名字，就当成设备支持安全隔离。

### 2. Landlock 配合 PRoot 是否可行？

**原理上有价值，但当前不能承诺在普通 Android 手机上可用。** Landlock 是 Linux 内核提供的访问限制：程序可以主动收紧自己和后代的权限，不能再自行撤销。若保护在专用启动子进程内、启动 PRoot 之前生效，追踪器和被追踪程序可以一起受到限制。这是待验证设计，不是本项目已有功能。[S2][s2]

需要同时满足三个条件：

1. 内核编入 Landlock，并在启动时启用。
2. Android 应用的系统调用过滤规则允许调用相关接口。
3. 实际规则能覆盖运行环境、文件句柄、进程间访问和 PRoot 所需行为。

**第二条有具体阻碍。** 本次读取的 AOSP bionic `main` 中，`SYSCALLS.TXT`、`SECCOMP_ALLOWLIST_COMMON.TXT`、`SECCOMP_ALLOWLIST_APP.TXT` 都没有 Landlock 调用。`Android.bp` 的 `seccomp_syscalls_sources_app` 使用这些输入；生成器通过基础列表、允许列表和禁止列表组成策略。因此，这份上游配置不能作为“普通应用已允许 Landlock”的依据，反而表明存在调用被过滤的阻碍。没有读取用户手机的实际过滤器，不能外推为每台手机都同样表现。[S6][s6] [S7][s7] [S8][s8] [S9][s9] [S10][s10] [S15][s15]

内核配置也有差异：本次读取的 `android-mainline` arm64 GKI 配置包含 `CONFIG_SECURITY_LANDLOCK=y`；`android15-6.6` 同路径配置没有显式配置此项。前者不是所有已出厂设备的配置，后者也不能替代厂商最终配置。不能只看到 Linux 版本较新，就宣布支持。[S11][s11] [S12][s12]

即便可以调用，还有必须补齐的保护：

- Landlock ABI 低于 3 时，不能阻止文件截断；仅禁止 WRITE_FILE 不够。
- 官方文档仍列出 chmod/chown/utime/xattr 等缺口。修改公共文件权限，仍可能让其他工作区无法使用工具。
- 启用规则前已经打开的文件句柄不能当作自动撤销；要清理继承句柄。
- Landlock 有 ptrace 关系限制；较新版本还能限制信号、抽象 Unix socket。不能笼统说它完全不管这些，也不能假定旧手机全部支持。
- 应约束 PRoot 追踪器与后代，而不是只限制 guest shell。
- 网络、应用内部代理和回传接口还需独立审查，不能仅靠文件规则解决。[S2][s2]

seccomp 可以补充禁止某类系统操作，但传统过滤器不能读取路径字符串。因此，“只禁止公共目录 chmod，保留项目目录 chmod”不是一个简单路径判断。全部禁止又可能影响 pip、构建和权限设置。PRoot 自身使用 ptrace/系统调用加速，组合后的行为也需要测试。[S14][s14]

**判断：保留为条件性技术路线，但先测设备能力，不能先迁移用户数据。** 这直接决定问题 4 的先后顺序。

### 3. 换成独立 Android 权限进程能否解决？

Android 的独立应用身份提供内核级隔离，这比把程序移进一个普通同 UID service 更强。但 `isolatedProcess` 不是拥有原应用全部文件权限的服务。AOSP 的 `isolated_app_all.te` 明确允许使用经 IPC 传入的已打开数据文件，同时 `neverallow ... app_data_file_type:file open` 禁止自行打开这类文件。[S3][s3] [S4][s4] [S5][s5]

现有 Linux 运行依赖大量按路径打开文件：解释器、动态库、Python 模块、配置、项目文件。传入一个 rootfs 目录句柄，并不等于子目录文件的 `openat` 已获得 SELinux 放行。仅修改 Manifest 和 `WorkspaceShellRunner`，不能保证 bash、Python、pip 就能工作。AOSP 还限制 isolated app 创建 TCP/UDP 套接字；网络也需要受控代理。AOSP 允许 `self:process ptrace`，所以不能武断宣称 ptrace 必然失败，但完整 PRoot 启动仍未验证。[S4][s4]

不同 APK 的执行服务可以保护主 App 的部分数据，却又遇到两层问题：公共环境所在 APK 的私有目录不能靠 world-readable 权限普遍开放；一个执行 APK 中所有工作区若同 UID，工作区间的隔离仍未解决。Android 官方推荐受控文件分享，而非公开应用数据目录。签名相同或 signature 级绑定权限不等于获得对方目录访问权。[S5][s5]

因此，独立身份路线可能需要一个新的文件访问代理和执行后端。它必须只给当前工作区发文件权限，公共包只给读权限，并支持动态加载、子进程、取消、终端与网络。这里的“可能”是研究方向，不是已完成的最小改造。代理如果只是替任意路径照单打开文件，还会把隔离重新破坏。

### 4. 哪条路线值得下一步验证？

**先做应用身份下的能力探测，再做最小运行样例；不要先重写整个工作区。** 前面问题 2 的系统调用阻碍可以很早排除不适用设备。问题 3 的目录打开限制则说明，若设备不支持内核限制，不能无成本退回 isolatedProcess。[S2][s2] [S4][s4] [S15][s15]

建议后续验证顺序如下。本次没有执行这些步骤。

| 阶段 | 验证什么 | 停止条件 |
|---|---|---|
| A：设备能力 | 在 App 派生的专门测试进程中查询 Landlock；记录返回值、退出信号、系统版本和内核信息 | 被过滤、未启用或功能版本不足：停止把该路线当通用默认 |
| B：最小功能 | PRoot 启动、Python import、原生扩展、venv、项目内 pip 安装、终端 | 要求开放整个 App 数据目录才能运行：不接受 |
| C：公共环境保护 | 创建、覆盖、截断、删除、重命名、链接、改权限、已有句柄、子进程写入 | 任一未授权改动成功：不得宣称公共环境受保护 |
| D：项目间保护 | 两个测试工作区互读互写、进程访问、信号、socket、应用代理接口 | 越界成功：不得启用严格共享模式 |
| E：迁移与恢复 | 旧私有环境保留、公共升级失败回退、导出离线恢复、删除不影响他人 | 无法回退或恢复不完整：不默认开放 |

ADB shell 的结果不能替代应用进程结果，因为 Android 应用继承的过滤策略是关键变量。探测也不应通过关闭 SELinux/seccomp、root 或降低 target SDK 来“做成功”。若需要编译安装测试包，单独批准这一验证步骤；不要触碰正在使用的正式版数据。[S15][s15] [S5][s5]

Python 部分可继续采用“一份基础 Python + 按需项目库”。venv 默认不继承公共第三方库；`--system-site-packages` 可以继承，但会受公共库变动影响。项目库和构建结果仍可能重复，所以不能承诺零重复。[S16][s16]

注意 Python 官方文档中的“venv 不支持 Android”说的是原生 Android Python 平台，不宜直接套到 PRoot 内 Linux 发行版 Python。若改成原生 Android 或其他执行方式，必须重新核实模块、子进程、原生扩展与 pip 能力。venv 的迁移还受绝对路径、基础解释器和架构影响；稳定 guest 路径有帮助，但不保证任意设备可搬迁。[S16][s16] [S1][s1]

## Comparison

| 路线 | 省重复安装 | 公共环境可靠保护 | 项目强隔离 | 本次判断 |
|---|---|---|---|---|
| 直接共用可写 Linux | 是 | 否 | 否 | 只适合明确接受互相影响的信任模式 |
| chmod + PRoot 挂载/黑名单 | 是 | 没有保证 | 没有保证 | 不作为严格模式 |
| Landlock + 补充限制 + PRoot | 有望 | 有条件，未实测 | 需处理 FD/IPC 等 | 先做应用身份下能力探测 |
| isolatedProcess 直接套现有后端 | 理论想共享 | 有独立身份，但程序可能跑不通 | 身份边界真实 | 不能直接套用现有文件路径后端 |
| 独立身份 + 受控文件/网络代理 | 有望 | 取决于新后端 | 有望 | 改动大，需独立原型证明 |
| 私有 Linux + 公共下载缓存 | 只省下载，不省安装树 | 减少共享已装包污染 | 仍不是强沙箱 | 可做保守优化，不冒充最终目标 |

表中最重要的差别不是目录数量，而是谁有权修改公共内容。共享可写目录最省事，却会扩大一次误操作的影响。系统级限制方向有真实依据，但 Android 的应用限制让桌面 Linux 的经验不能直接照搬。[S1][s1] [S2][s2] [S5][s5]

独立身份不是“做不到”，而是会改变文件访问方式。若要保留任意 Linux 工具、联网和交互终端，文件与网络代理的工作量必须算进去。相反，只共享下载缓存容易保留现状，但没有解决用户最在意的已安装环境重复。[S4][s4] [S13][s13]

## Contradictions & Debates

### “PRoot 可以 confine，所以它就是安全沙箱”

PRoot 官方介绍的是路径视图和请求转换；Termux PRoot-Distro 明确说明没有真正的 PID/network/IPC 隔离。两者不矛盾：看不到正常路径，不代表获得了独立权限。仓库自身的安全分级也与此一致。[S1][s1] [S13][s13]

### “Android 新内核已启用 Landlock，所以应用能用”

`android-mainline` 的配置支持前半句的一种情形；bionic 应用调用名单是另一道限制。不同分支、不同配置层的证据不能互相替代。结论应是“需要应用身份下探测”，不是普遍支持或普遍不支持。[S6][s6] [S7][s7] [S8][s8] [S10][s10] [S11][s11] [S12][s12]

### “Landlock 完全不限制 ptrace、信号和 socket”

这是 scout 半成品中的错误泛化，本报告不采用。官方文档明确说明 ptrace 的域关系限制，以及后续版本的 IPC scope。与此同时，旧版本缺少部分能力，元数据操作也仍有缺口。[S2][s2]

### “没有 AIDL 就没有 IPC”

这也是不准确概括。主代理核对 Manifest 发现 FileProvider、WorkspaceDocumentsProvider；当前只是没有证实存在专用的隔离 Shell 执行服务。已有 provider 需要纳入未来边界审查，不能忽略。

## Uncertainties & Gaps

- 没有读取用户手机的型号、内核配置、真实 SELinux 策略及应用过滤器；没有声称手机未连接。
- 未运行任何写保护或跨工作区攻击测试。当前结论是设计约束，不是安全认证。
- 没有 PRoot 二进制的可复现构建记录。二进制内版本字符串不证明精确补丁版本。
- 新版 Landlock 文档描述的能力不能自动视为旧手机能力。
- 独立身份文件代理没有原型；动态库、fork/exec、原生 Python 扩展、速度和内存都未知。
- 没有评估所有 OEM 定制内核，也没有证明所有 Android 无 root 路线绝对不可能。
- 不把应用同 UID 下潜在的 ptrace、信号或 FD 攻击写成已成功复现；Android 仍有其他限制。
- 本次引用的 main 分支与在线文档可能变化，判断范围是查询时读取的内容。

## Recommendations

### Primary Recommendation

**下一步做专门的设备能力验证，不先迁移环境。** 先查应用进程能不能启用所需内核保护；通过后再测 PRoot/Python 功能和越界访问。严格模式应在保护不满足时拒绝启动，而不是静默降成无保护共享。[S2][s2] [S15][s15]

公共 Linux 的管理权限应留给明确的维护入口。升级时不让工作区任务并发修改同一系统，公共环境也不保存个人 HOME、凭据、登录脚本或项目结果。这是基于共享风险的建议，不是已经实施的架构。

### Alternative

若目标设备不具备必要能力，而用户仍要求任意代码可靠隔离，应将“独立身份 + 受控文件/网络访问”作为单独原型课题，先证明最小 Python 样例可运行。若用户只要求自己的受信任项目互不误操作，则可以另行选择较简单的共享模式，但界面和文档必须如实说明保护等级。[S4][s4] [S5][s5]

### Not Recommended

不建议通过公开应用数据目录、共享 UID、关闭系统安全机制、单纯命令黑名单或宣称 PRoot bind 为只读来实现严格保护。也不建议先把所有旧环境合并，再补隔离和回退。[S1][s1] [S5][s5] [S14][s14]

## Methodology

- 用户指定 deep；分成 Android 独立身份、PRoot/系统限制两路 scout，只读并行。
- 主代理执行 3 轮联网研究，共 10 条搜索查询，优先 Tavily；读取官方文档和 AOSP 原文，而非只引用搜索摘要。
- 对 AOSP 大文件用只读 HTTP 获取、base64 解码和定向行提取；未下载执行任何远程代码。
- 查询过一个旧的生成过滤器路径，返回 HTTP 404；随后检查现行 Android.bp 与生成器，未把不存在的文件当证据。
- 最终 research_checkpoint：PROCEED，四项信心分别为 95%、90%、90%、85%，工具返回总体 90%。这里评估约束结论，不衡量设备实施成功率。
- 多跳证据链：PRoot 挂载 → 内核限制 → Landlock 缺口 → Android bionic 应用过滤器 → 实机探测条件；isolatedProcess → SELinux 文件打开规则 → 文件代理成本。
- 两路 scout 都遇到 `steered (wrapped up at the turn limit — output may be partial)`。类型均为 scout，目录均为 `C:/Users/jch/heimaoai/project`，isolation 均为 off；均不按完成的全面调查计数。
- 主代理补核了执行链、Manifest、SDK、文件能力检查、设备测试模式及设计文档；未采用半成品里未经核实的绝对说法、准确二进制版本断言和“零 IPC”断言。
- 关键事实由 Linux、Android、PRoot/Termux 三方资料交叉检查。Android 特定系统规则以 AOSP 原文为准，多份 AOSP 文件不算独立组织背书。
- 未运行测试、构建、ADB、设备安装或数据迁移。此次唯一新增文件是这份调查报告。

## Sources

所有来源读取日期为 2026-09-21；没有可靠发布日期的页面不猜日期。Tier 1 表示该技术的官方文档/源代码，不表示已证明适用于目标设备。

| # | Title | URL | Date / 范围 | Credibility |
|---|---|---|---|---|
| S1 | PRoot 官方说明 | [链接][s1] | 无明确发布日期 | Tier 1 |
| S2 | Linux Landlock userspace API | [链接][s2] | 页面标注 June 2026 | Tier 1 |
| S3 | Android service 元素 | [链接][s3] | 页面标注 2026-07-01 | Tier 1 |
| S4 | isolated_app_all.te | [链接][s4] | AOSP main，读取时版本 | Tier 1 |
| S5 | Android Application Sandbox | [链接][s5] | 页面标注 2026-07-13 | Tier 1 |
| S6 | SECCOMP_ALLOWLIST_APP.TXT | [链接][s6] | AOSP main，读取时版本 | Tier 1 |
| S7 | SECCOMP_ALLOWLIST_COMMON.TXT | [链接][s7] | AOSP main，读取时版本 | Tier 1 |
| S8 | Bionic SYSCALLS.TXT | [链接][s8] | AOSP main，读取时版本 | Tier 1 |
| S9 | genseccomp.py | [链接][s9] | AOSP main，读取时版本 | Tier 1 |
| S10 | Bionic Android.bp | [链接][s10] | AOSP main，重点 2403–2554 行 | Tier 1 |
| S11 | arm64 GKI config | [链接][s11] | android15-6.6 分支 | Tier 1 |
| S12 | arm64 GKI config | [链接][s12] | android-mainline 分支 | Tier 1 |
| S13 | Termux PRoot-Distro README | [链接][s13] | master，重点 Limitations | Tier 1（项目自述） |
| S14 | Linux Seccomp BPF | [链接][s14] | 在线版本；涉及 ptrace 还需按实际内核核实 | Tier 1 |
| S15 | Seccomp filter in Android O | [链接][s15] | 2017-07-20；机制说明与现行源码合读 | Tier 1 |
| S16 | Python venv 文档 | [链接][s16] | 读取时在线 3.x 文档 | Tier 1 |
| S17 | PRoot-Distro Security policy | [链接][s17] | 仅指向 Termux 安全政策；不作为隔离保证 | Tier 1（项目自述） |

[s1]: https://proot-me.github.io
[s2]: https://docs.kernel.org/userspace-api/landlock.html
[s3]: https://developer.android.com/guide/topics/manifest/service-element
[s4]: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app_all.te
[s5]: https://source.android.com/docs/security/app-sandbox
[s6]: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_APP.TXT
[s7]: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_COMMON.TXT
[s8]: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SYSCALLS.TXT
[s9]: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/tools/genseccomp.py
[s10]: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/Android.bp
[s11]: https://android.googlesource.com/kernel/common/+/refs/heads/android15-6.6/arch/arm64/configs/gki_defconfig
[s12]: https://android.googlesource.com/kernel/common/+/refs/heads/android-mainline/arch/arm64/configs/gki_defconfig
[s13]: https://github.com/termux/proot-distro
[s14]: https://docs.kernel.org/userspace-api/seccomp_filter.html
[s15]: https://android-developers.googleblog.com/2017/07/seccomp-filter-in-android-o.html
[s16]: https://docs.python.org/3/library/venv.html
[s17]: https://github.com/termux/proot-distro/blob/master/SECURITY.md
