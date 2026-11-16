# ClawMate Android 迁移交接

交接基准为 2026-11-16 的实现提交 `602f34a`。继续工作前阅读 [AGENTS.md](../AGENTS.md)。[迁移日志](../MIGRATION_STATUS.md)保留阶段、提交与验收证据，[当前待办](../MIGRATION_BACKLOG.md)跟踪剩余范围，[Mika 功能对照](MIKA_PARITY.md)跟踪原版能力差异。

## 从哪里继续

- 共享实现位于 `codex/shared-frontend`。交接核对时，本地跟踪引用 `origin/codex/shared-frontend` 在 `42b65cb`，其后的 `88bb34f`（鸿蒙 UI 对照修正）、`bad08d8`（虚拟屏无障碍识别）和 `602f34a`（实时预览与进度）已在本地提交。复用当前工作区可直接继续。换工作区时先核对目标分支是否包含这三个提交和本交接页。
- 2026-11-17 跟进：内核升到 `9fd0be4`，界面任务接入随包知识与技能索引、看图模型（窗口截图 + 看图定位）、无障碍坐标手势，并把文本输入放宽到任意表单控件。构建与单测通过，真机待复核；详见[迁移日志](../MIGRATION_STATUS.md)顶部与[上游对照](MIKA_UPSTREAM_DIFF.md)第 11 节。
- 迁移目标是鸿蒙 ClawMate 的完整 Android 版本。界面与共享业务沿用 ArkTS/ArkUI-X，宿主在 `shared-app/`，源码锁、组装与适配在 `shared-frontend/`，可复用的 Android 能力在 `android-capabilities/`。`app/` 是 Compose 回归基线。鸿蒙参考源码保持只读。固定输入和构建见 [共享前端说明](../shared-frontend/README.md)。
- 手机验收沿用已配置的 `com.clawmate.shared`。原位更新前核对包名和签名，更新后核对原配置、Codex 连接与采集记录。保留现有用户授权，使用合成页面或用户明确授权的公开内容做验证。APK、SDK、凭据、设备私有数据与未经审查的截图留在版本控制之外。
- 会议和录音纪要的真实引擎与专项运行测试放在最后。保留已有会议实现和历史证据。普通聊天 ASR/TTS 和流式语音仍在当前迁移范围。

## 最新验收与剩余范围

| 范围 | 当前实现与已验收结果 | 剩余工作 |
| --- | --- | --- |
| 云端聊天控制手机 | Mika `:mika-core` 已跟进 `9fd0be4`，原版 `AgentLoop` 接 ClawMate 原有 HTTPS 模型。明确输入「打开」或「启动」加已安装应用名时在本机选定目标，用户确认应用和屏幕；其他手机操作经云端工具派发。OPPO 验收计算器读取和小红书「发现」页读取、回传摘要、取消。2026-11-17 接入内置知识索引、看图模型、坐标手势与表单输入，待真机复核 | 小红书站内热搜榜、从主屏模式发起到回传、更多应用的翻页与跨窗口操作；手势、截图看屏与看图定位的合成页验收 |
| 虚拟屏与悬浮窗 | OPPO 验收独立显示器启动、主屏焦点、主屏右侧实时预览、聊天步骤实时显示、结束或停止后回收。现用 USB 授权启动同 APK 内的助手，画面留在本机 | 应用内无线配对和恢复、浮窗触控接管、旋转与多厂商。原版的悬浮胶囊及其语音入口另待接入 |
| 系统时钟与日历 | 云端真实模型通过系统接口创建一次性和重复闹钟、显示列表、启动计时器、预填单条日程。批量写入代码已接原版工具，真机验收缺少写权限时的拒绝 | 获写权限后的日历实际保存与逐条核对。OPPO 时钟把计时器标签显示成了默认名称。时钟记录编辑和读取待接入 |
| 采集模板、个性化图与模型读屏 | 12 个内置模板经任务页调用同一按次会话底层，自定义图可编辑和运行。OPPO 已核对 12 张图、支付宝打开及确认取消、自定义计算器读页与预览。图中的 Decider 已用真实模型验收计算器单页读屏、预览和保存；微博前台整榜曾验收 1–50 名 | 12 个模板的真实结果逐项验收。微博后台整榜、小红书公开笔记真实 Decider 翻页和保存分别补测。图模型读屏在条件、循环中的接入和多页真机验收仍待完成 |
| Codex 与本地聊天 | 已有独立 Codex 页和云端聊天向电脑委托。Windows 连接器 1.1.15 的 USB 与个人热点配对、工作区、重连有真机记录。本地模型的下载、续传与管理已接入 | Codex 聊天向手机委托 Mika、真实本地推理和本地聊天调用 Mika。真实相机扫电脑屏幕、更多网络和桌面能力另验收 |
| 鸿蒙 UI、记忆与数据来源 | 首页、汇总、任务、「我的」采用 `coevomind-app@ba8e250f` 对照。OPPO 验证便签拖放交办入口、日历弹窗和云服务授权入口。系统日历、邮件、图库、云端聊天与记忆保留各自原生或共享路径 | 真正执行首页交办并回看交付、人物与陪伴记忆索引、真实图库及模型质量、邮件 OAuth 和多厂商 UI。详见[待办](../MIGRATION_BACKLOG.md) |
| 其他原版能力与发布 | Mika 的代码和资源已随内核引入。安卓宿主仍有独立适配和真实服务验收工作 | 浏览器、调度、技能/记忆、文件与沙箱、通知/来电及电脑协议等按[原版对照](MIKA_PARITY.md)逐项核查。另需生产签名、旧数据迁移回退、多厂商及 16 KB 整体验收 |

小红书的三条测试路径各自记结果。云端聊天的「发现」页摘要已通过。聊天中的站内热搜榜、任务页的「公开笔记」采集及保存仍各有待办。采集任务的 12 个模板已共用底层，当前支持后台屏的任务类型是微博、小红书公开笔记和 Mika 手机任务。

## 继续迁移的入口

| 关注点 | 读代码从这里开始 |
| --- | --- |
| 云端聊天、明确应用识别和进度 | [Index.ets](../shared-frontend/app/Index.ets) 的 `sendMessage` / `runMikaPhoneTask`，[SharedAgent.ets](../shared-frontend/adapters/platform/SharedAgent.ets)，[PlatformBridge.kt](../shared-app/src/main/java/com/clawmate/shared/PlatformBridge.kt) 的 `mikaPhoneStart` / `mikaPhonePoll`；聊天进度 UI 适配在 [prepare.mjs](../shared-frontend/prepare.mjs) |
| Mika 原版与 Android 屏幕端口 | [Mika 源码与许可](../third_party/mika/README.md)，[MikaAppRequest.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaAppRequest.kt)，[MikaPhoneEntry.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaPhoneEntry.kt)，[MikaScreenPort.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaScreenPort.kt)，[MikaSystemScreen.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaSystemScreen.kt) |
| 虚拟屏、悬浮窗与窗口授权 | [DisplayHostMain.kt](../android-capabilities/src/main/java/com/clawmate/platform/display/DisplayHostMain.kt)、[BackgroundDisplay.kt](../android-capabilities/src/main/java/com/clawmate/platform/display/BackgroundDisplay.kt)、[GuiControlService.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/GuiControlService.kt) 和 [任务类型](../android-capabilities/src/main/java/com/clawmate/platform/gui/GuiControlRequest.kt) |
| 预设图、个性化图、采集和结果 | [GuiControlEntry.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/GuiControlEntry.kt)、[工作流源码适配](../shared-frontend/collection-workflow-source.mjs)、[模型工作流迁移](WORKFLOW_VLM_MIGRATION.md) |
| Codex、其他功能与验收证据 | [Codex 上游对照](CODEX_UPSTREAM_PARITY.md)、[Codex 真机记录](CODEX_DEVICE_VERIFICATION.md)、[全量架构与工作包](MIGRATION_ARCHITECTURE_GUIDE.md) |

先复现小红书搜索页热搜榜和主屏模式，把应用是否打开、实际读到哪一页和回执内容分别记录。继续补齐共用手机执行器的页面切换、截图读屏和后台接管，再把已验收的能力接到 Codex 的手机委托。本地聊天接入依赖 Android 真实推理。每完成一段更新迁移日志、待办和相关对照；用户流程变化时同步 [中文图文手册](user-manual/README.md)。

## 构建与复测

- 本轮交接只读核对了 OPPO PKB110 的安装。包名是 `com.clawmate.shared`，APK SHA-256 为 `319c2e0755d579bdf1d7e501d47d6bc01a22299dd06a08f21c7f2ce27eba079d`，与 `602f34a` 的验收记录一致。无障碍已开启，助手进程存在，空闲时只有主屏显示器 `0`。助手状态会随进程和设备重启变化，测试前再查。
- 上一次功能验证通过了 ArkUI-X 打包与封存、共享 Debug APK、Android 能力单测（309 通过、14 跳过）、共享集成（21 项）、聊天分身检查（10 项）及用户手册一致性检查。该轮构建使用本机忽略目录里的会议 SDK 占位，会议专项照常后置。[实际命令与结果](../MIGRATION_STATUS.md#mika-实时后台预览和聊天进度2026-11-16)
- 新环境按[源码导入与工具链说明](SHARED_BOOTSTRAP.md)准备锁定输入。Windows 使用 `shared-frontend/build.ps1` 的参数或本机工具配置。它会执行会议原生库的 APK 静态门禁，完整流程需相应 SDK。已准备并封存共享运行包时，可用下列命令复跑普通回归。Gradle 环境需配置 JDK 17、Android SDK 与 `MEETING_SDK_ROOT`。

```powershell
node shared-frontend/build-state.mjs verify
.\gradlew.bat :shared-app:assembleDebug :android-capabilities:testDebugUnitTest --offline --console=plain
node shared-frontend/verify-integration.mjs
node shared-frontend/verify-chat-avatar.mjs
python docs/user-manual/build.py --check
```

- 虚拟屏真机复测时检查 `adb devices -l`、`adb shell pm path com.clawmate.shared`、`adb shell dumpsys accessibility` 和 `adb shell pidof clawmate-display-helper`。已有授权但助手退出时，可在独立终端使用当前安装的 `base.apk` 启动同包的助手。先核对路径来自 `pm path`。

```powershell
$apk = ((adb shell pm path com.clawmate.shared | Select-Object -First 1) -replace '^package:', '').Trim()
if ($apk -notmatch '^/data/app/[A-Za-z0-9_~+./=-]+/base\.apk$') { throw '未找到当前安装的 ClawMate base.apk' }
adb shell "CLASSPATH=$apk app_process /system/bin --nice-name=clawmate-display-helper com.clawmate.platform.display.DisplayHostMain com.clawmate.shared"
```

- 测试中用 `dumpsys window displays` 核对目标所在显示器、主屏焦点和退出后的回收。活跃任务期间使用系统状态与截图观察。此前 `uiautomator dump` 曾干扰无障碍调试，导致运行时误报服务关闭。
