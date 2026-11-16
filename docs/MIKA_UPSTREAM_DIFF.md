# Mika 上游仓库与本仓库迁移范围对照

更新于 2026-11-17。对象：https://github.com/wangzan101/mika 的 `main`（`9fd0be4`，0.1.45 / versionCode 46）
与本仓库 `third_party/mika/core`（2026-11-17 起同钉 `9fd0be4`）及 Android 宿主接线。

本页只回答「上游 Mika 有、本仓库还没有」的差别；逐条验收条件见 [Mika 功能对照](MIKA_PARITY.md)，
已完成的阶段与证据见 [迁移日志](../MIGRATION_STATUS.md)。第 11 节记录 2026-11-17 已迁移的第一档能力。

## 1. 结论摘要

- 本仓库迁移的是 Mika 的**执行内核 + 手机界面控制这一个子集**，不是 Mika 这个应用。
  内核源码与资源已作为 `:mika-core` 引入并随 APK 打包，但界面、浏览器、沙箱、记忆、技能、
  通话、MCP 等宿主一律没有接。
- 当前真正跑得通的只有三条链路：
  1. 云端聊天 → `AgentLoop` → 单应用界面读屏/点按/文本输入/滚动/返回（需 Android 14+ 与按次授权）；
  2. 系统动作：闹钟、计时器、闹钟列表、单条日程表单（走系统 Intent）；
  3. 批量日历写入（走 `CalendarContract` 并逐条回执）。
- 2026-11-17 已补上第一档：内核跟进到 `9fd0be4`，界面任务接入内置知识与技能索引、看图模型
  （窗口截图 + `describe`/`find_visually`）、无障碍坐标手势，并把文本输入放宽到任意表单控件
  （内容仍须来自用户请求）。见第 11 节。
- 其余上游能力（网页、沙箱、记忆与画像、个人技能、通话/短信登录、MCP/电脑连接、
  运行记录/回放/导出、定时与条件触发、子任务、文件库、悬浮胶囊、按住说话、微信等）仍未接入，
  其中多数在宿主里连端口实现都没有。
- 版本次差：上游 `main` 比原固定点 `eb10b34` 多 **16 个提交**，`core/src/main` 有 **18 个 Kotlin 文件**变化
  （+182/−27，资源目录无差异）。这些是模型/触发/产品修复，不是新能力类别；本仓库已跟进。

## 2. 对照基线

| 项 | 上游 | 本仓库 | 备注 |
| --- | --- | --- | --- |
| 固定点 | `9fd0be4`（0.1.45） | `9fd0be4` | 2026-11-17 跟进；`third_party/mika/README.md` 记录固定点 |
| 引入方式 | 完整 Android 应用（`app` + `core`） | 只引入 `core/src/main`（Kotlin/JVM 库 `:mika-core`） | `settings.gradle.kts` 挂 `third_party/mika/core` |
| 源码规模 | `app` 532 个 `.kt`（另含 androidTest）+ `core` | `:mika-core` 486 个 `.kt` | 本仓库无 Mika 的 `app` 代码 |
| 内核改动 | — | 与 `eb10b34` 相差 7 个文件（+43/−27） | 5 处 Kotlin 1.9 兼容 + 能力白名单（`TaskTools.kt`、`RunAssembler.kt`），README 已说明 |
| 内核漂移 | `eb10b34 → 9fd0be4` | 已跟进（2026-11-17） | `core/src/main` 18 文件、资源 0 差异 |

## 3. 内核漂移：`eb10b34 → 9fd0be4`

`core/src/main` 变化的文件：
`agent/AgentLoop.kt`、`agent/Failover.kt`、`agent/ModelCaller.kt`、`agent/StopRules.kt`、`agent/TaskState.kt`、
`model/ApiKeyText.kt`（新增）、`model/ModelCatalog.kt`、`model/ModelError.kt`、`model/ClientHeaders.kt`、
`model/AnthropicModel.kt`、`model/GeminiModel.kt`、`model/OpenAiCompatModel.kt`、`model/ResponsesModel.kt`、
`trigger/PlanEditor.kt`、`trigger/Triggers.kt`、`usage/ProviderAllowance.kt`、`skills/SkillOutcomes.kt`、`eval/Scoring.kt`。

提交类别（16 个）：

- 模型配置与错误：API Key 非法字符不再打崩配置页（#660，新增 `ApiKeyText`）、403 + `insufficient_quota` 判额度用尽（#639）、
  每日预算按服务商 ID 选备援模型（#658）。
- 定时任务：可设最长运行时间、连续失败次数写进通知（#659）、时间选择器与多时刻编辑（#643，`PlanEditor`/`Triggers`）。
- 手机与语音：锁屏暂停不依赖广播、亮屏自动继续（#654）、按住说话麦克风误报两种原因（#632）、
  短信登录使用完整原生控件 ID（#656）。
- 通话与导出：通话结束后可编辑的微信跟进草稿（#633）、会话导出沿用勾选时的消息快照（#657）、导出脚本报错透传（#663）。
- 其他：技能结果多份时不误记（#655）、飞书连接器锁错误（#637）、个人助手回复可选中复制（#661）、
  聊天抽屉跟手（#650）、土耳其语 README、0.1.45 发版。

## 4. 本仓库实际接线（代码证据）

| 位置 | 作用 |
| --- | --- |
| [MikaPhoneEntry.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaPhoneEntry.kt) | 唯一装配点：`toolDispatcher(screen, skills = catalog, vision = vision, allowedToolNames = allowed)`，三种模式 `SCREEN` / `SYSTEM` / `CALENDAR_BATCH`；知识只给 `SCREEN` |
| [MikaScreenPort.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaScreenPort.kt) | `ScreenPort` 的无障碍实现：单窗口读树、元素点按、表单输入、元素滚动、坐标手势、窗口截图、返回、按键、单应用启动 |
| [MikaSystemScreen.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaSystemScreen.kt) | `ScreenPort` 的系统动作实现：闹钟/计时器/闹钟列表/日程表单 Intent，批量日历写入 |
| [MikaCalendarWriter.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaCalendarWriter.kt) | `CalendarContract` 写入与逐条回执 |
| [GuiControlService.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/GuiControlService.kt) | 按次授权的前台应用会话、虚拟屏、浮窗停止入口；`mikaScreenshot` 与 `mikaGesture` |
| [PlatformBridge.kt](../shared-app/src/main/java/com/clawmate/shared/PlatformBridge.kt) | ArkTS ↔ Android 的 `mikaPhoneStart` / `mikaPhoneStartWithVision` / `mikaPhonePoll` / `mikaPhoneCancel` 等 |

宿主对 Mika 的引用集中在 `AgentLoop`、`TaskState`、`toolDispatcher`、`PHONE`、`GuideCatalog`、
`VisionService`、`OpenAiCompatModel`、`Capabilities`、`uistate.*`。`Mcp`、`Sandbox`、`WebPort`、
`MemoryStore`、`PhoneCallPort`、`TriggerDefinition` 在 `android-capabilities/` 与 `shared-app/` 中仍然**零引用**。

`toolDispatcher` 的其余参数（`web`、`sandbox`、`memory`、`profile`、`utterances`、
`scheduleTrigger`、`childHost`、`runRecords`、`self`、`phoneCalls`、`mcpServers`、`deviceFiles`、`taskMemory`、
`notices`、`userScreens`、`webSearch`、`smsLogin`、`root` …）保持默认 `null`；`skills` 只给界面任务，
`vision` 只在配置了看图模型时给。

### 模式与工具白名单

| 模式 | 白名单 | 实际可用工具 |
| --- | --- | --- |
| `SCREEN` | 不限 | 手机屏幕工具 + 手机专属工具 + 少量通用工具 |
| `SYSTEM` | `phone_set_alarm`、`phone_set_timer`、`phone_show_alarms`、`phone_add_calendar_event`、`ask_user`、`end_not_done` | 见左 |
| `CALENDAR_BATCH` | `phone_calendar_bulk_insert`、`ask_user`、`end_not_done` | 见左 |

### `ScreenPort` 能力边界（代码事实）

| 方法 | 本仓库实现 |
| --- | --- |
| `snapshot` / `peek` | 读无障碍树，最多 120 个元素、360 个节点、深度 32，只读目标包名 |
| `tapRef` | 仅 `ACTION_CLICK` 且元素可点击，动作前重新读屏防止过期 |
| `typeText` / `clearText` | 任意可编辑控件（不再限定搜索框），文本必须是用户请求里出现过的子串（≤200 字，无控制字符） |
| `scroll` | 只对可滚动元素做 `ACTION_SCROLL_*` |
| `tapXY` / `doubleTapXY` / `swipe` / `gesture` | 无障碍手势执行；点在应用内容区内、无浮窗遮挡、窗口未变时才做 |
| `screenshotPng` | 配了看图模型时截目标窗口（API 34 `takeScreenshotOfWindow`），否则返回 `null` |
| `openApp` | 只允许本次确认的目标包，`cold` 一律拒绝 |
| `listApps` | 只回报本次目标一个应用 |

## 5. 能力矩阵

| 上游能力 | 上游实现 | 本仓库 | 差距 |
| --- | --- | --- | --- |
| 手机界面操作 | `PHONE` 能力 + 13 个屏幕工具 + 点按/滚动/滑动/输入/返回/按键/诊断 + 截图与看图定位 | 元素点按、坐标点按/双击/滑动/多点手势、表单输入、滚动、返回、按键、诊断可用；`describe`/`find_visually` 需配置看图模型；跨应用/跨窗口状态未接 | 跨应用/跨窗口、手势与看图的真机验收 |
| 长列表/聊天记录/成批执行 | `phone_collect`、`phone_run`、`do_steps` 等 | 工具已引入，`phone_collect` 依赖滚动与跨屏去重，未真机验收整榜/多页 | 多页重叠去重与续跑 |
| 内置浏览器与网页工具 | `WEB` 能力 + `WebPort` + 20 余个 `web_*` 工具、登录态、标签页、脚本、上传下载 | 无 `WebPort` 实现，`web` 参数为 `null` → 整套网页工具不提供 | 整个网页能力 |
| Linux 沙箱 / 终端 | `SANDBOX` + `SandboxPort` + Alpine rootfs + `run_command` / 读写文件 / 技能脚本 | 无 `SandboxPort`，沙箱工具不提供 | 整个沙箱与工作区文件 |
| 技能与知识 | `GuideCatalog`、33 个 App 知识、25 个站点知识、内置技能与站点命令 | 内置索引已接（只读 `knowledge_search/read`、`skill_search/read`）；个人技能、脚本与站点命令执行未接 | 个人技能、脚本与命令 |
| 记忆 / 画像 / 任务记忆 | `MemoryStore`、`ProfileStore`、`TaskMemoryStore` | 未接线（`memory`/`profile`/`taskMemory` 为 `null`） | 全部记忆类工具与页面 |
| 视觉读屏 | `VisionService`、`describe`、`find_visually`、截图回看 | 窗口截图 + 独立视觉模型已接（`describe`/`find_visually`）；主模型图片输入未开；真机未验收 | 真机映射、过期引用与自适应附图 |
| 系统时钟与日历 | 原版系统接口 + 批量日历 | 已接，OPPO 真机验收过闹钟/计时器/列表/表单，批量写入已接 | 时钟记录编辑与读取、写权限后的逐条核对 |
| 通话与短信登录 | `PhoneCallPort`、AI 接打电话、通话摘要、微信跟进草稿、`SmsLoginPort` | 均未接 | 通话能力与短信登录自动化 |
| 微信接入 | `core/wechat` + App 侧 `wechat` | 未接 | 全部 |
| MCP / CLI / 电脑连接 | `McpClientProvider`、USB/Wi-Fi 连接、CLI 与 Skill | 未接（ClawMate 另有自己的 Codex 桥接，不等价） | 协议、鉴权、会话归属 |
| 定时与条件触发 | `trigger/`、`ScheduleAdmin`、`scheduleTrigger` | 未接（ClawMate 采集提醒是自研） | 计划编辑、执行记录、条件比较、重启恢复 |
| 子任务 / 并行 | `ChildRunHost`、`spawn_agent` | 未接 | 全部 |
| 运行记录 / 回放 / 导出 | `RunRecords`、`Replay`、`Export*` | 未接；只有聊天里的实时步骤与进度 | 记录持久化、回看、导出 |
| 读 Mika 自己（`SelfPort`、设置工具） | `mika_status`、`settings_*`、打开自身页面 | 未接 | 全部 |
| 设备文件 | `DeviceFilesPort` | 未接 | 全部 |
| root 命令 | `RootPort` | 未接 | 全部 |
| 用户屏幕（`view_user_screen`） | `UserScreenSource` | 未接 | 全部 |
| 通知感知 / 主动归纳 | `NoticeSource`、`notices_read`、`proactive` | 未接 | 全部 |
| 通用网页搜索 | `WebSearch` | 未接 | 全部 |
| 通用动作/询问 | `ask_user`、`request_confirmation`、`hand_to_user`、`handle_obstacle`、`end_not_done`、`wait` 等 | 已可用（`available` 恒真） | 行为策略差异见下 |

> 表格 5 中「未接」的判断依据：宿主未给端口，且 `available(ctx)` 以参数非空为门控（如
> `MemoryTools.kt`、`LookupTools.kt`、`ScheduleTask.kt`、`SpawnAgent.kt`、`HistoryRead.kt`、
> `TaskMemoryTools.kt`、`WebSearchTool.kt`、`NoticesRead.kt`、`SelfTools.kt`）。工具**声明**仍在
> `knownTools()` 里，改名/旧名解析与技能文档校验不受影响。

## 6. 工具层差异（按本仓库配置推导）

模型中实际能看到的工具：

- 屏幕工具：`phone_observe`、`phone_list_elements`、`phone_tap`、`phone_scroll`、`phone_swipe`、
  `phone_type_text`、`phone_clear_text`、`phone_back`、`phone_press_key`、`phone_diagnose`；
  配置了看图模型时再加 `phone_describe`、`phone_find_visually`；`phone_screenshot` 仍隐藏（主模型不能收图）。
- 手机专属：`phone_query`、`phone_find`、`phone_extract`、`phone_collect`、`phone_wait`、`phone_event`、
  `phone_dialog`、`phone_gesture`、`phone_run`、`phone_command`、`list_apps`、`launch_app`、`app_after_task`、
  `share_text`、`set_wheel`、`phone_set_alarm`、`phone_set_timer`、`phone_show_alarms`、
  `phone_add_calendar_event`、`phone_calendar_bulk_insert`。`phone_sms_login`（无端口）与 `phone_open_link`
  （`deepLinks` 默认关闭）不可用。
- 通用：`wait`、`view_past_screens`、`ask_user`、`request_confirmation`、`hand_to_user`、`handle_obstacle`、
  `end_not_done`，以及界面任务里的 `knowledge_search`、`knowledge_read`、`skill_search`、`skill_read`。
- 隐藏：`skill_save`、`memory_search/save/edit`、`profile_edit`、`history_read`、`notices_read`、`web_search`、
  `schedule_task`、`spawn_agent`、`task_memory_*`、`settings_list/set`、`self_*`、`view_user_screen`。

## 7. 应用层（上游 `app`）差异

上游 `app` 里与 Mika 产品直接相关、本仓库**没有**对应实现的部分：

- 悬浮胶囊：显示进度/待处理/结果，长按说话，双击转发当前屏，全屏自动隐藏，快捷设置磁贴（`capsule`、`proactive`、`voice`）。
- 后台屏任务页：多个任务同时使用后台屏、等待同一个 App、实时预览与放大、双指缩放（`task`、`ui`）。
- 内置浏览器页与登录态（`browser`、`web`）、Mika 自己的页面（`self`、`settings`、`memory`）。
- 文件库与工作区、终端、导出与分享（`device`、`export`、`sandbox`、`terminal`）。
- 定时任务页、执行记录、复制为新计划（`task`、`trigger`）。
- 运行记录/回放/诊断（`record`、`replay`、`selfcheck`）。
- 模型设置：多服务商、账号登录（ChatGPT/Claude/Gemini/xAI）、额度与费用、代理、语音转文字选择（`settings`、`i18n`）。
- AI 接打电话、通话摘要与微信跟进草稿（`calls`）。
- MCP/CLI 电脑连接与配对向导（`mcp`、`setup`、`shell`）。
- 记忆页、画像、主动通知归纳、教学/接管模式（`memory`、`personal`、`proactive`、`teach`、`takeover`）。
- 多语言（11 种）与《关于》、更新检查（`about`、`i18n`）。

本仓库的聊天、任务、采集、Codex、语音 ASR/TTS 等界面属于 ClawMate 既有产品，**不计入** Mika 迁移完成度。

## 8. 行为层差异（已接线部分）

- 询问/自主：本仓库固定为逐次确认（`confirmTimeoutMs = 12s`），未暴露上游的「自主」策略。
- 模型：只有 OpenAI 兼容协议；上游还有 Anthropic、Gemini、Responses API、账号登录与本地服务。
- 运行时装配：`RunSpec` 用默认值（`legacy`），上游按来源/在场/能力集裁剪工具。
- 进度：本仓库从 `TaskState.Running` 抓「第 N 步 · 动作」，上游有完整的步骤记录与回看。
- 会话归属：每次手机任务独立 `AgentLoop`，不进入 Mika 的对话/运行记录体系。

## 9. 建议的下一步（按收益/成本）

1. ~~**P0 内核跟进**：升到 `9fd0be4`~~ —— 2026-11-17 完成，见第 11 节。
2. **P1 手机能力补齐**：视觉、通用手势、非搜索框输入已经接入（同样是 2026-11-17），剩余的是
   跨窗口/跨应用状态判断与真机验收；这一档要真机核对后 `phone_describe`、`phone_find_visually`、
   `phone_gesture` 才算名副其实。
3. **P2 网页能力**：`WebPort` 需要 WebView 宿主、Cookie 持久化、上传下载与页面脚本，工作量最大的一类。
4. **P3 沙箱**：Alpine rootfs、arm64 原生库、`run_command` 与工作区文件，涉及打包体积与安全边界。
5. **P4 记忆/记录/定时**：知识索引已接；记忆、个人技能、运行记录与定时仍需要持久化、索引与页面，
   且与 ClawMate 自己的数据管理有重叠，先定边界再接。
6. **P5 其余**：通话、短信登录、MCP/电脑连接、微信、通知感知、主动任务、胶囊、语音入口。

## 10. 本页的验证与限制

- 内核差异由 `git diff --no-index` 对比 `eb10b34` 与 `9fd0be4` 的导出树得到，资源目录无差异；
  提交列表来自 GitHub compare API（16 个提交）。升级后 `:mika-core:compileKotlin` 通过。
- 宿主接线由源码阅读确认（唯一 `toolDispatcher` 调用点、`available(ctx)` 门控）。
- 2026-11-17 起本页同时依据构建与单测结果（见第 11 节）；真机部分仍未验收。
- 上游仍在演进；本页以 `9fd0be4` 为准，后续更新时请同时刷新 [Mika 功能对照](MIKA_PARITY.md)。

## 11. 第一档已迁移（2026-11-17）

| 能力 | 接入点 | 边界 |
| --- | --- | --- |
| 内置知识与技能索引 | [MikaPhoneEntry.kt](../android-capabilities/src/main/java/com/clawmate/platform/gui/MikaPhoneEntry.kt) 用 `GuideCatalog.loadBundled()` 读随包索引，传给 `toolDispatcher(skills=…)` 与 `AgentLoop(skills=…)` | 只给界面任务；闹钟/日历任务不注入。索引 33 App + 25 站点 + 6 技能，只读 |
| 看图模型 | [MikaVisionConfig.kt](../android-capabilities/src/main/java/com/clawmate/platform/mika/MikaVisionConfig.kt)、[AndroidImageOps.kt](../android-capabilities/src/main/java/com/clawmate/platform/mika/AndroidImageOps.kt)，配置从页面「云模型」的 Decider 视觉模型经 `mikaPhoneStartWithVision` 传入 | 未配置时 `describe`/`find_visually` 缺席、不截图；主模型仍未开图片输入 |
| 窗口截图 | `GuiControlService.mikaScreenshot` 用 `takeScreenshotOfWindow`（API 34），尺寸与窗口边界不一致或窗口变化时返回 null | 受保护页面由系统拒绝，不由应用伪造 |
| 坐标手势 | `mikaGesture` 用 `dispatchGesture`；点按/双击/滑动/多点手势都走它 | 点必须在应用内容区内，浮窗遮挡或窗口变化时拒绝；一次手势算一次会话动作 |
| 表单输入 | `MikaInput.allowed`：任意可编辑控件（不再限定搜索框），文本必须是用户请求原文、≤200 字、无控制字符 | 计算出的日期/数字仍不能自动填入；清空输入框不受限 |

验证（2026-11-17）：

- `:android-capabilities:testDebugUnitTest`：334 项、0 失败、14 跳过；新增
  `MikaKnowledgeTest`（断言知识工具只在接入目录时出现）、`MikaEndpointsTest`、
  `MikaVisionConfigTest`、`MikaInputTest`。
- `shared-frontend/build.ps1`：ArkUI-X 准备、hvigor `assembleHap`、运行包导出、`build-state.mjs verify`
  与 `:shared-app:assembleDebug` 通过；APK 内确认 `knowledge/index.json` 及 App/站点知识。
  脚本末尾的会议原生库校验因本机会议 SDK 占位缺 `.so` 失败，属会议专项限制。
- 真机未验收：手势、截图看屏、看图定位与表单输入需要在 OPPO 上用合成页或授权页面复核。
