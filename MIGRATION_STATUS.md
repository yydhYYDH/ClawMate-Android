# ClawMate Android 迁移状态与日志

## U04 Codex 配对与连接恢复（2026-11-12）

- 对照鸿蒙 `CodexClient` 的 HTTPS 配对、服务器 ID 校验和重试规则。Android 原生层接管重试，普通 GET 遇到临时故障最多重试一次，健康检查、长轮询和 POST 各发一次。请求和读取响应共享总时限，网络故障后清理空闲连接。
- 区分网络不可达、端口拒绝、DNS、HTTPS 校验和配对码失效。开启「记住此连接」时，已核验的设备令牌先写入本机加密存储，工作区加载失败后可沿用原授权重连。
- ArkTS、共享版 Debug APK、非会议设备测试 APK 和手册检查通过。Codex JVM 73 项通过，14 项 live 跳过，OPPO 设备协议测试 1 项通过。
- OPPO 用原 `MSI` 授权经 USB 连接真实 Windows 1.1.15 桥接。隔离测试中配对成功后 `/v1/info` 连续两次返回 502，重启应用后沿用同一条保存的连接进入工作区。详见 [真机记录](docs/CODEX_DEVICE_VERIFICATION.md#2026-11-12-连接恢复与错误诊断)。
- 当前 OPPO 与电脑的 WLAN 邻居解析失败，手机访问 8787 返回 `No route to host`。无线配对待可互访的 Wi-Fi、热点或同一 Tailscale 网络验收。
- 两端切到个人热点后可解析邻居地址。Windows 的公用网络入站规则放行后，OPPO 到真实连接器 TCP 8787 的探测从超时变为成功。随后两端切回原 Wi-Fi，热点下的完整配对仍待复测。

## P01/P04 内置 Android 后台屏底层（2026-11-12）

- 在 `:android-capabilities` 内置通用后台屏会话，应用确认的启动入口经 Binder 交给同 APK 的 Android shell 助手。辅助进程绑定 ClawMate UID，并按会话结束、应用进程退出或超时回收显示器。Android 14 及以上试验使用授权 USB 调试启动，应用内无线引导待接入。
- 现有采集按会话选定的显示器读取窗口。小红书用作业务验证，在既有确认框选择「在后台屏采集」，复用 Decider、公开搜索范围、限页、停止、预览与本机保存。主屏无障碍浮窗显示读页数和条数。聊天提示词转任务保留为下一阶段。
- OPPO PKB110 / Android 16 已验证辅助进程连接，通用夹具与小红书 `GlobalSearchActivity` 在独立虚拟屏启动，`mTopFocusedDisplayId=0`。验证了关闭会话和应用进程退出后回收显示器。以独立包验证，原共享版保留。
- 共享运行时封存校验、隔离 APK、排除会议专项后的设备测试 APK 构建通过。配套设备测试包的首次安装触发 ColorOS 确认，改用已安装隔离包运行启动和回收探针。界面采集相关 Kotlin 单测 23 项、共享工作流回归 21 项、中文手册检查通过。
- 采集闭环探针在授权检查阶段返回 `Isolation service is disconnected`。更新并强停 `com.clawmate.shared.guicollect` 隔离包后，其无障碍开关从系统启用列表移除。已按 SHA-256 恢复该隔离包的原 APK，正式共享版和其他 ClawMate 服务仍在启用列表。实际采集、翻页、浮窗显示及保存待重新授权后验收。设备剩余显示器为主屏 `0`。

## U04 Codex USB 转发恢复（2026-11-12）

- 真机拔插会清掉 ADB 会话中的端口转发。恢复转发后，原配对自动回到「已连接」。
- 新增 Windows USB 重连助手，限定到指定设备和端口。真机模拟转发丢失后约 1.9 秒自动补建，TCP 探测与手册检查通过。

## U04 Codex 实际连接器文件配对复测（2026-11-12）

- Windows 1.1.15 的原始配对文件能在 OPPO 导入并显示确认框。当前 WLAN 无法访问电脑桥接端口，确认后复现连接中断。
- 经 USB 回环导入新文件后，真实连接器新增设备，OPPO 保存连接并进入已连接工作区。详见 [真机记录](docs/CODEX_DEVICE_VERIFICATION.md#2026-11-12-已安装桌面连接器的真实文件配对)。

## U04 Codex 扫码界面与解码效率（2026-11-12）

- 对照鸿蒙 Codex 调用的 [ScanKit 默认扫码页](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V14/scan-scanbarcode-V14/)，改为暗色取景、顶部关闭、中央取景框和底部图库/手电筒；取景框与送入解码的裁剪区域一致。
- 相机使用连续对焦，解码失败后沿用同一次定位的三个角和校正点重采样，间隔尝试反色。相册保留更多截图像素并支持反色。
- Codex JVM 70 项通过、14 项 live 跳过。桌面 JVM 对同一合成高密度码分别解码 600 次，中位耗时从 3.964 ms 降到 2.557 ms。Debug APK 构建及手册检查通过。
- OPPO 真机通过相机取景、手电筒按钮开关、关闭返回，以及从相册读取桌面 1.1.15 的真实二维码并进入确认框。用相机对准实体屏幕和热点端到端连接仍待验收。

## U04 Codex 桌面 1.1.15 二维码兼容（2026-11-12）

- 用 Windows 桌面连接器 1.1.15 实际生成的二维码复现识别失败。二维码的原始网格为 113×113，标准检测器采样成了 117×117。
- 相机和相册复用同一解码器。升级 ZXing 以改善小尺寸码定位，在标准解码失败时按相邻 QR 版本重采样，仅接受 ClawMate 配对格式的后备结果。
- 68 项 Codex JVM 测试通过，14 项 live 跳过。共享版 Debug APK 和中文手册检查通过。OPPO 用真实桌面码从相册识别到确认框，相机对电脑屏幕的识码仍待实测。见 [真机记录](docs/CODEX_DEVICE_VERIFICATION.md#2026-11-12-windows-桌面-1115-二维码复测)。
- 电脑本机访问桥接 HTTPS 有响应，手机通过当前 WLAN 直连同一地址和端口返回 `No route to host`。局域网端到端配对仍需在能互访的网络验收。

## U04/U05 Codex 扫码配对与工作台对齐（2026-11-12）

- 对照 `coevomind-app@e876199` 的手机端、连接器和桥接。来源与差异见 [Codex 上游对照](docs/CODEX_UPSTREAM_PARITY.md)。
- 共享版新增本机相机与相册二维码识别。配对文件和普通附件改由 Android 文档选择器按授权读取。修复配对后同源令牌、401 分类、会话增量、拒绝审批与单个桥接请求取消。
- 复用上游交付链接与请求体预算。普通聊天的新委托选择已保存的电脑和工作目录并确认，Kotlin 落盘委托状态并为续办向原任务提交新一轮。中文手册新增操作路径。
- ArkTS、运行时校验、Android Debug APK 与非会议设备测试 APK 构建通过。Codex JVM 67 项通过、14 项 live 默认跳过，前端工具契约 18 项、源码锁检查 17 项、工作台契约、中文手册检查通过。OPPO 通过设备协议测试与相册识码、配对文件、普通附件、交付和拒绝审批，见 [真机记录](docs/CODEX_DEVICE_VERIFICATION.md#2026-11-12-扫码与工作台回归)。
- 待迁移：首页建议和提醒转交、聊天内委托卡与审批/同步/停止、图片委托、上游完整资料准备。真实电脑服务及局域网/桌面会话接力仍需验收。

## P01/P04 Mika 小红书后台屏试验（2026-11-12）

- 现有小红书前台采集以 `3ba91eb` 为基线。共享版「我的 → 数据采集 → 界面采集与规划」新增独立试验入口，通过 Mika 官方 `/api/v1` 的 USB 本机监听派发固定的「上海城市漫步」公开搜索。Kotlin 适配器限制环回连接，校验接口版本、手机助手状态和任务编号，处理取消和超时。USB 令牌按次填写，结果以文本预览，至多三条，供用户核对。Mika 按提示词执行公开范围，原前台流程仍是任务页的正式入口。
- 在 OPPO PKB110 / Android 16 安装并启动官方 Mika v0.1.39 arm64，安装前核对发布 SHA-256。真实 USB 环回接口通过认证并返回 API v1、任务恢复支持、设备解锁和手机助手未连接。实际 Mika 虚拟屏搜索和真实模型回传仍待完成 Mika 手机操作授权与模型配置后验收。
- `prepare.mjs`、hvigor `assembleHap`、运行包导出、构建状态封存及 Gradle `:shared-app:assembleDebug --offline` 通过。调试 APK 使用本地会议 SDK 占位与独立应用 ID `com.clawmate.shared.mikapilot`。会议专项未运行。
- `:android-capabilities:testDebugUnitTest --offline` 共 309 项，295 项通过、14 项跳过、零失败。新覆盖受控任务回传、取消、手机助手未就绪与笔记格式校验。Node 24 下共享通用回归 6 项、界面规划 13 项、工作流模型 21 项、共享集成 16 项通过，中文手册重新生成并通过 `build.py --check`。
- `apksigner verify` 与 `aapt2 dump packagename` 通过。独立测试 APK SHA-256 为 `a01769791c5c5c20c7ac19877d8c97dea957f40e2dd18f3b82e38b585462b0f0`。在 OPPO 上启动独立包安装时进入 ColorOS 安装确认，实际试验页尚待用户确认安装后在手机上验收。

## P01/P04 小红书提示词采集实例（2026-11-11）

- 社交任务新增「小红书 AI 采集公开笔记」。共享图节点支持编辑公开搜索关键词和提示词。Android 启动已安装客户端的指定搜索结果，校验应用和窗口。鸿蒙浏览历史模板作为独立任务保留。
- Decider 在手机提供的可用动作中选择读取、向下翻页或结束。每轮发送用户确认的提示词、动作标签和进度数字。原生端校验返回动作、当前窗口和可见结果。笔记摘录由本机读取、去重和预览，确认后写入既有采集记录，可用关键词查询。
- 公开结果最多读取三页和十五条摘录。小红书可把标题与正文开头合在一个无障碍文本节点，保留客户端提供的摘录。目标搜索窗口稳定后才开始采集。现有无障碍授权、单应用会话、锁屏与切出停止规则继续适用。更新中文手册的设置、图编辑、确认、保存位置与恢复步骤。
- OPPO PKB110 / Android 16、小红书 9.53.3。用隔离 APK 的本机受控模型端口，针对「上海城市漫步」实际选择读取、翻页、读取、结束四次操作。最终版本预览前记录未变，确认后保存两页共 6 条不重复摘录。重跑后取消预览，记录哈希不变。首次启动的路由多窗口已改为等待稳定搜索页。Instrumentation 启动曾使隔离服务断开，最终用同一原生会话入口的独立测试夹具验证。
- `prepare.mjs`、hvigor `assembleHap`、Android 运行包导出和 `build-state.mjs verify` 通过。普通共享包、隔离包和平台单测构建通过。平台单测 291 项通过，14 项外部环境测试跳过。共享集成 16 项、视觉工作流 21 项、GUI 建议 13 项、提醒 14 项、手册导航 8 项通过。手册生成校验 16 章、11 张审核截图。构建沿用本地会议 SDK 占位，非会议测试包构建排除了会议专项。
- 使用 `adb install -r` 更新原有 `com.clawmate.shared` 并启动，确认安装版包含公开笔记任务及摘录提示词。首次安装时间、原有采集记录和无障碍授权保持一致。APK SHA-256 为 `dccc0858bea9804eac4145108bffed8d154a6cdf735c9ee75ee5585f33d5ef52`。已恢复干净隔离包并移除临时测试安装。真实 Decider 服务尚未验收，写入隔离包临时模型与授权偏好的尝试被工具策略拦截。

## P01/P04 微博整榜、采集查询与原生界面（2026-11-10）

- 「读取热搜」默认逐页扫描公开主榜，仍可选择 1 至 5 条试跑。滚动时保留相邻页的重叠内容，截图前后核对榜单节点。标题和热度读无障碍节点，名次图标在本机识别，未读出的带热度行使用原色图标复识别。保存采集日期、时区、时间戳、标题、名次、热度及覆盖状态。名次缺失或冲突留空，仅在确认 1 至 50 名均存在时标为完整。完整批次仅保留有名次的主榜词条。
- 本机采集历史是界面与检索共用的数据源，兼容旧文本记录。普通采集与微博结构化榜单各可保存 200 条或批，达到上限由用户清理。云端文字聊天增加 `query_collections`，按来源、日期、名次和关键词有界读取，返回匹配内容前核对模型配置、记录状态与本次授权。现有事件索引、日历、邮箱查询继续提供各自来源。
- Codex 直聊及新委托可以按明确的问题匹配本机采集、事件索引、日历或邮件，确认摘录后发送给当前配对电脑。直聊重试沿用已确认的文本和请求 ID，委托校验目标电脑。查询层可供本机使用。共享版 Android 原生推理库仍待接入，端侧模型实际回答仍待验收。
- 微博采集确认、运行卡片、预览和历史详情使用共享版浅色/深色配色、圆角卡片与主次按钮。授权和记录写入保持逐次确认，预览及详情继续受系统截图保护。
- 在 OPPO PKB110 / Android 16 的隔离共享版使用微博 16.11.0 实测。最终版翻 6 页保存 50 条，排名连续 1 至 50，50 条均有标题和热度，日期为 2026-11-10，时区为 Asia/Shanghai。四步回执成功。打开预览时本机记录未变，确认后写入。再次运行并取消预览时记录哈希保持一致，第 4 步回执为 `cancelled`。中间版本曾遇到榜单缺一个名次，以及系统浮层遮挡时停止，分别保留部分覆盖状态和安全停止回执。
- 共享前端生成、hvigor `assembleHap`、运行包导出与 `build-state.mjs verify` 均通过。普通和隔离共享 APK 构建通过，沿用会议 SDK 占位。平台单测 289 项通过，14 项外部环境测试跳过。共享集成 16 项、提醒 14 项、视觉工作流 19 项及手册导航 8 项通过。手册构建校验 16 章与 11 张审核图。云端查询及 Codex 摘录以受控数据验证，真实模型回答待验收。
- 用 `adb install -r` 更新 OPPO 原有 `com.clawmate.shared` 并启动，确认默认图的读取范围已切换为整榜。首次安装时间、原有采集记录和无障碍授权列表一致。设备 APK 与产物 SHA-256 `6f4798b6929d9054dba1e62d1e8f6b946fc5e571c7255234e7ffa099c7dd5b73` 一致。

## P01/P04 微博热搜无障碍工作流（2026-11-10）

- Android 的内置微博模板使用「打开微博榜单 → 切换热搜标签 → 读取热搜 → 预览并保存」四个图节点。保留编辑过的旧模板。图编辑支持设定最多 1 至 5 条和插入有限等待。运行入口解析已保存的图，并在原生端校验顺序和参数。
- 单次确认绑定 `com.sina.weibo`。通过客户端榜单链接打开页面，使用无障碍节点操作公开「热搜」标签，从当前屏幕读取带数字热度值的标题，按屏幕顺序展示。榜单数字为图标，本阶段未识别官方名次、打开新闻详情或调用摘要模型。
- 运行与停止沿用共享工作流状态。取消、切出、锁屏、超时均结束本次授权。预览确认后复用本机采集记录，工作流回执保留步骤与状态，批量运行提示单次确认。更新中文手册的入口、节点参数和记录位置。
- `prepare.mjs`、hvigor `assembleHap`、运行包导出和 `build-state.mjs verify` 通过。共享集成 15 项、提醒 14 项、视觉工作流 19 项、手册导航 8 项通过。平台能力单测 286 项通过、14 项外部环境测试跳过。共享调试包沿用本机会议 SDK 占位。
- OPPO PKB110 / Android 16、微博 16.11.0 用隔离共享包测试。在图上把条数从 3 改到 1，验证配置、确认框、实际保存的条数与四步成功回执一致。首次真机读取改为按同行边界配对标题与热度。连续运行增加窗口稳定检查，避免微博旧榜单窗口被短暂显示时提前锁定。最终包连续验证确认保存与取消预览，确认前没有新增本机记录，取消后已有记录保持原值，回执第 4 步为 `cancelled`。
- `adb install -r` 更新手机上的 `com.clawmate.shared` 调试版并启动。设备 APK 与产物 SHA-256 `fed2b2f0f4ca7bc126e87f89353435ba95c6b0bca801f1f8f9ade47b7a0e9080` 一致，无障碍授权列表与测试前一致。

## P01/P04 支付宝账单单次采集入口（2026-11-09）

- 「查看支付宝」原模板依赖通用 GUI 执行器、跨应用截图和视觉摘要，Android 以前在首步返回 `blocked`。在「任务 → 手机自己控制 → 配置任务 → 购物」为内置模板提供「采集账单」，复用已验收的 Android 无障碍读取、预览和本机记录。
- 原生确认绑定 `com.eg.android.AlipayGphone`。用户确认后打开支付宝并自行进入「我的 → 账单」，再主动读取当前页或最多 3 页。采集记录仍需确认保存。未启用服务时提供系统设置入口。取消确认不创建工作流回执。编辑过的任务仍按其工作流运行，批量执行中的内置支付宝任务提示走单次确认。
- 中文手册补充入口、授权恢复、预览与本机记录位置。真实账单页面的节点可见性、自动导航、结构化摘要和汇总仍待验收。
- `prepare.mjs`、hvigor `assembleHap`、运行包导出与 `build-state.mjs verify` 均通过。普通共享包、隔离共享包与 `:android-capabilities:testDebugUnitTest` 构建成功，沿用本机会议 SDK 占位。平台单测 283 项通过，14 项外部环境测试跳过。共享集成 14 项、提醒 14 项、视觉工作流 19 项、手册导航 8 项通过。
- OPPO PKB110 上先在旧隔离包复现 `blocked`，再覆盖更新。保留旧工作流回执，任务卡换成「采集账单」，未启用服务时显示设置入口。启用后验证单次确认、取消，以及确认后启动真实支付宝并显示浮层。离开支付宝后浮层消失，未采集真实账单，未产生新工作流回执。隔离测试结束后恢复原无障碍授权。
- 使用 `adb install -r` 更新手机原有的 `com.clawmate.shared` 调试版。首次安装时间与无障碍授权列表保持一致，启动成功。设备 APK SHA-256 与构建产物一致，为 `d16cb506a7dfff674e2725eee0e28e964828dea52d1fbedcff339db456c73df2`。

## P01/P04 共享版无障碍多页文字采集（2026-11-09）

- 从「我的 → 数据采集 → 界面采集与规划」选择单个应用，用户自行打开目标页面后，可以采集当前页或自动向下采集最多 3 页。Android 14 及以上使用系统无障碍服务，自动采集只对唯一、可见且支持向下滚动的区域执行动作。
- 每次读取均校验目标窗口与系统截图许可，并释放截图像素。跳过密码、可编辑及系统标记为敏感的节点，按首次出现的顺序去重，保留页数和部分覆盖状态。应用切换、锁屏、停止、超时会丢弃未保存内容。确认保存才写入最近 10 条本机记录，兼容旧的单页记录。
- 更新中文手册与真实夹具脚本。给 ColorOS 打开目标应用的确认弹窗保留 20 秒，修复原生设置页失去焦点时点开历史记录导致的崩溃，并限制长文本预览区域高度，为保存按钮留出空间。
- `prepare.mjs`、hvigor `assembleHap`、Android 运行包导出和 `build-state.mjs verify` 通过。`verify-gui-plan.mjs` 通过 13 项。共享宿主调试包、平台能力单测和独立夹具均构建通过，沿用本机会议 SDK 占位。平台能力 283 项通过、14 项外部环境测试跳过。手册检查验证 16 章、10 张审核图及 8 项导航测试。
- OPPO PKB110 / Android 16 / ColorOS 16.1.0 使用独立包名 `com.clawmate.shared.guicollect` 安装完整共享 APK。从共享页面打开了原生选择器，再用原生界面点击复核合成应用。自动读满 3 页，确认后保存 46 条不重复文字，含初始屏幕以外的测试行，跳过可编辑内容，目标计数保持为 0。历史详情重新打开显示相同页数和测试行，可删除记录。采集中按 Home 后浮层消失，历史为空。单页预览可打开，按返回键取消后记录仍为空。验收结束关闭隔离包的无障碍授权。
- 独立 instrumentation 在该机的无障碍 UI 驱动阶段超时，设备结论来自实际界面点击、窗口状态与调试包的本机记录。最终隔离 APK SHA-256 为 `949542f7f8aaf61341666b64cfa353faf252b56ba25fe447c1acbaaba14670d8`。
- 微信、支付宝真实采集、联系人与日期边界、跨批次去重、结构化摘要和汇总同步待验收。无障碍节点没有文字的界面尚无 OCR 回退。

## 任务页与鸿蒙任务服务目录对齐（2026-11-08）

- 任务页与控制面板锁定到 `coevomind-app@0bc30157`，复核最新 `e876199f` 时两个文件的 blob 未变。核对两种控制方式、七个任务分类、11 个内置模板和图形工作流编辑入口。上游在批量采集后触发同步分析。Android 在成功保存非空摘要后提供「同步采集结果」，复用汇总页的同步确认流程。上游后来更新的微信聊天日期判断提示词留待微信自动采集迁移。
- 区分两条控制路径。「手机自己控制」可运行本机工作流和经确认的本机图片视觉问答。「电脑控制」提示 Android 设备桥接待接入。任务行标明所需服务，自动 GUI 受阻时保留 `blocked` 回执并提示所在步骤。支付宝、微信等模板的跨应用自动执行仍待接入。
- 中文手册更新控制方式、服务状态和采集结果同步流程。共享集成 13 项、视觉工作流 19 项、采集提醒 14 项、手册导航 8 项通过。用生成后的批量调度方法验证电脑模式拦截、无摘要、GUI 受阻及部分成功后的同步入口。手册生成检查验证 16 章与 10 张审核图。
- `prepare.mjs`、hvigor `assembleHap`、运行包导出与 `build-state.mjs verify` 通过。Gradle `:shared-app:assembleDebug --offline` 通过，使用现有会议 SDK 占位构建。确认 APK 中的 ArkUI 字节码与已校验的运行包一致。
- `:android-capabilities:testDebugUnitTest --rerun --offline` 重跑通过，279 项通过，14 项 Codex 外部环境用例跳过。
- 在 OPPO PKB110 覆盖安装调试包并比对 APK 哈希。真机检查任务入口、两种控制方式及其提示、配置页七类入口和任务列表的「自动 GUI · 待接入」标签，检查后恢复原控制方式。真实跨应用采集与云端同步留待各自的服务验收。

## 语音纪要标题与页面样式对齐（2026-11-08）

- 汇总来源行和详情页统一使用「语音纪要」。详情页复用日历和邮箱的 `AppHeader` 与 `FrostedPage`，使用居中标题、主题背景、圆角卡片与行式按钮。
- 按录音与导入、本地模型、纪要记录、正文、整理与导出、处理进度和导入副本分组。刷新位于纪要记录的搜索行。录音、转写和保存调用沿用既有 SDK。
- 更新中文手册、旧图说明、迁移记录和专项测试的入口选择器。当前范围是界面，完整会议运行验收仍在最终阶段。
- `prepare.mjs`、hvigor `assembleHap`、Android 运行包导出和来源校验均通过。`:shared-app:assembleDebug --offline` 使用现有会议 SDK 占位构建通过。生成的页面与两个适配源逐字节相符。源码对照核查了既有会议调用。
- OPPO PKB110 / Android 16 安装并打开新包。ADB 确认汇总顺序「日历分析 → 邮箱分析 → 语音纪要」，验证居中标题、录音/模型/记录分组、滚动到导入副本以及返回汇总。真实录音、转写、已有记录详情和纪要运行留待专项验收。
- 实机截图发现精修下拉框缺省值为空，初始化失败显示英文桥接错误。补上可见的 SenseVoice 默认值和中文服务状态。校验所有已准备文件的哈希后只更新会议页适配，重新编译、封装、安装并复查截图。首轮复测在冷启动时先于底栏出现，测试等待底栏后通过。
- `python docs/user-manual/build.py --check` 验证 16 章和 10 张审核图，`node --test docs/user-manual/navigation.test.cjs` 通过 8 项。

## 任务及汇总入口对齐、鸿蒙录音分析对照（2026-11-07）

- 任务页移除 Android 专用的「本应用截图与单步规划」入口。「我的 → 数据采集 → 界面采集与规划」承接现有截图、采集与手动控制流程。
- 汇总去掉顶部会议按钮。「本机会议」复用日历、邮箱来源行，排在两者下方，点击仍先结束聊天语音再进入现有会议页面。
- 核查鸿蒙 `coevomind-app@0bc30157` 与新增录音分析的 `84535ff3`。更新会议差异表、D30 映射与最终阶段待办。会议运行专项继续放在最后阶段。 [实现对照](docs/MEETING_INTEGRATION.md#与鸿蒙录音分析的差异2026-11-07)
- 更新中文手册入口、旧截图差异及设备测试的入口定位。

- `prepare.mjs`、hvigor `assembleHap`、Android 运行包导出和来源校验通过。`:shared-app:assembleDebug :shared-app:assembleDebugAndroidTest --offline` 通过，沿用本机会议 SDK 占位及非会议测试的临时 Gradle 过滤。
- OPPO PKB110 / Android 16 实测旧版与新版，确认任务页规划按钮及汇总顶部会议按钮已移除，来源行顺序为「日历分析 → 邮箱分析 → 本机会议」。设置页实测新入口，首次仪表测试点击「数据采集」时命中浮动导航栏。修正测试先滚动卡片后，`GuiScreenshotDeviceTest#settingsEntryCapturesPreviewAndClearDeletesItWithoutCloudAuthorization` 复测通过，覆盖设置导航、预览和清除。
- `verify-gui-plan.mjs` 通过 13 项。手册生成检查通过 16 章和 10 张审核图，导航测试通过 8 项。会议专项未运行。

## P01/P04 跨应用本机文字采集首段（2026-11-07）

- 核查 `coevomind-app@0bc30157` 的 Planner / Decider、手机 HDC UI 树与动作接口、PC `/api/workflow` 和手机微信采集。三家 GUI 模型的接口与 Key 申请入口见 [分支计划](docs/COEVOMIND_BRANCH_PLAN.md#android-跨应用采集2026-11-07)。
- 共享任务页及原生无障碍设置页接入“选择应用并开始采集”和“查看本机采集记录”。Kotlin 复用单应用、解锁、窗口遮挡及 120 秒会话边界，窗口截图由系统确认可读后即释放，目标页的无障碍文字在工作线程读取。可编辑、密码和敏感节点跳过，读取有界，达到上限时标为部分。离开目标或取消时丢弃未保存预览。
- 明确保存后写入应用私有目录的最近 10 条记录，保留来源、采集时间和文字，可删除单条。首段采用手动导航和逐页采集，独立于模型与汇总。新增 opt-in 的测试 App 用例，检查取消、保存、可编辑/屏外过滤和删除。
- 更新中文手册流程及旧任务图差异。后续先验证 Android 微信的可见节点、联系人匹配、日期边界和跨页去重，再接结构化记录与汇总索引。GUI 模型的坐标和动作协议与现有 Decider 解析分开适配。

验证结果：

- `node shared-frontend/prepare.mjs`、hvigor `assembleHap`、`package-android.py`、`build-state.mjs seal` 均通过。生成的任务页与仓库适配层一致，`node shared-frontend/verify-gui-plan.mjs` 通过 13 项受控回归。
- `gradlew.bat :android-capabilities:testDebugUnitTest :gui-control-fixture:assembleDebug --offline` 通过。最终共享能力测试报告 279 项通过、14 项联网服务测试跳过、0 失败。
- 共享宿主用本机现有的会议 SDK 占位编译。首次全量 `:shared-app:assembleDebugAndroidTest` 因会议专项引用缺少的 SDK 方法失败。通过未入库的 Gradle init 脚本仅排除 `MeetingIntegrationDeviceTest.kt` 后，`:shared-app:assembleDebug :shared-app:assembleDebugAndroidTest --offline` 通过。
- OPPO PKB110 / Android 16 安装共享调试 APK 和非会议测试 APK。 `adb shell am instrument -w -r -e class com.clawmate.shared.GuiControlDeviceTest com.clawmate.shared.test/androidx.test.runner.AndroidJUnitRunner` 返回 `OK (2 tests)`。覆盖系统服务声明、无权限时阻止开启采集以及取消说明后权限保持关闭。启用服务后的采集夹具、微信真实采集和 GUI 模型调用待验收。
- `python docs/user-manual/build.py --check` 验证 16 章与 10 张审核图，导航测试 8 项通过，`git diff --check` 通过。

## 鸿蒙参考的日历/邮箱界面与同步设置（2026-11-04）

- PersonalSourcesPage复用AppHeader/FrostedPage：居中标题、白色分组卡片、字段标题、蓝色行式操作、清除确认；无页面内互跳。
- 汇总图库下方的PersonalSourceEntry增加独立同步/取消与SVG图标，和详情页共用PersonalSourceSync。
- PersonalSourceEngine增加独立来源偏好：日历启用/来源/历史0、30、90、365天（未来30天），改变范围/来源或关闭即失效旧快照；邮箱每天20、50、100封上限进入实际只读IMAP流程。
- 设置存储与邮箱凭据分离，切换/忘记邮箱不会抹掉日历设置。聊天仍为逐次确认的query_calendar/query_mail，不自动写入画像。
- 实机修复：生命周期重复初始化，以及Builder参数按值传递导致加载结束后控件仍灰置；改为$$引用绑定。同步按钮内边距及SVG缩放已在OPPO核看。

验证与证据：

- `shared-frontend/build.ps1 ... -Offline`：完整ArkTS/Android构建通过；静态原生ELF/ZIP 16KB对齐门禁通过，不等于16KB真机稳定性验收。日志`.arkui-x-validation/sources-harmony-ui-build.log`。
- `gradlew :shared-app:assembleDebugAndroidTest :android-capabilities:testDebugUnitTest --tests com.clawmate.platform.sources.ImapTransportTest --offline --console=plain`：测试包构建及5项IMAP JVM通过；补入日历提供器夹具后再次assembleDebugAndroidTest通过。
- `am instrument ... PersonalSourcesDeviceTest,MailOAuthDeviceTest`：最终APK上5项通过，隔离目录/合成凭据及提供器，验证0/30/90/365天查询参数、撤权、设置分离与持久化、禁用清除、OAuth状态机。日志`.arkui-x-validation/sources-harmony-device-tests.log`。
- `scripts/test-shared-device.ps1 ... -EvidenceName sources-harmony-ui-regression`：native 6、chat 1、workflow 1全部通过；无会议专项。证据`.arkui-x-validation/application/sources-harmony-ui-regression/`。
- `node scripts/test-personal-source-sync.mjs`：真实IMAP解析/读取器处理合成21封邮件，20/50/100上限、正文读取次数、partial、取消（含配置读取中取消）、失败不提交、并发锁与无来源拒绝通过。
- `node shared-frontend/verify-personal-sources.mjs`：桥参数、授权/拒绝/过期/取消、日历与邮件工具回传通过；verify.mjs 6项、verify-integration.mjs 12项、verify-native.mjs 7项通过。
- OPPO实际浏览汇总和两页，验证日历来源刷新、Outlook预设更新服务器/授权字段、邮件上限20封保存重进；随后恢复原50封。已审核三张无私人内容截图，收入docs/user-manual。未读取真实私人事件、未登录邮箱或请求真实模型。

`python docs/user-manual/build.py`及`--check`通过16章/10张审核截图，`node --test docs/user-manual/navigation.test.cjs`通过8项；`git diff --check`与`build-state.mjs verify`通过。

最终安装APK SHA256：`e57cb57c00ae9734d4681404b082fab3fe09cedd6dfeafaaf9a1c5195c684a4f`。
修改集中在共享来源页面/入口/同步适配、原生来源设置及桥、隔离测试、中文手册及迁移文档；Compose基线和鸿蒙原工程未改。
剩余：Google/Entra注册、真实提供商/日历/模型质量验收，日历画像/一周安排、邮件提醒/自动更新/关注筛选、多账号/离线令牌刷新及TalkBack专项。


## 鸿蒙实机界面观察（2026-11-04）

用户允许USB调试后，HDC已正常连接。实际浏览已安装ClawMate的邮箱上下分区、汇总入口、
日历设置页；仅导航与滚动，未修改开关、保存/同步或登录邮箱。记录见
docs/HARMONY_PERSONAL_SOURCES_UI_REFERENCE.md，区分可见文案与未经验证的业务行为。
本阶段仅观察及文档，无产品代码修改，未追加构建/运行测试；前次Unauthorized阻塞已解除。

## 日历与邮箱独立页面（2026-11-04）

按用户要求删除页面内互相切换按钮，汇总两个入口继续带kind路由参数；
设置中的合并入口也改为两个独立入口，避免邮箱不可达。中文手册同步说明。
build.ps1 -Offline完整构建通过，来源查询检查、verify.mjs 6项、手册生成及--check通过。
APK SHA256：5bec478ba2489b2f8d6002e922a6e97463fe1805c29bba2812786f668cb640c8。
本次没有连接Android目标手机，未安装或运行新增真机回归；未运行会议专项。
HDC可检测到用户连接的鸿蒙手机，但状态Unauthorized，尚未进入或查看ClawMate；
已请求用户在手机端允许USB调试，不将源码参考冒充实机观察。

## 来源页面与汇总入口（2026-11-03）

参照只读鸿蒙CollectionPage的CollectionActionRow、20圆角卡片、主题背景/边框，
在“图库分析”下方增加“日历分析”“邮箱分析”两个入口，带kind路由参数打开对应分区。
来源页保留设置入口，新增顶部切换、状态卡片与分组操作，记录以独立卡片展示；
全天日程按UTC日期显示，避免全天事件因本机时区换算错日。
实际build.ps1 -Offline完整构建通过（ArkTS 1分2秒，Gradle 1分7秒）；来源桥/查询检查、
verify.mjs 6项及手册build.py --check通过。新版布局的完整真机视觉/无障碍验收仍待补齐。
手册更新主入口并明确旧图与新布局差异；真实账户/日历/模型质量及完整鸿蒙画像/关注筛选仍待验收。

## 日历/邮箱接续：提供商与OAuth（2026-11-03）

新增主流邮箱预设及自定义TLS入口；Google AuthorizationClient和Microsoft设备码流程接入
原生MailOAuth，IMAP增加XOAUTH2。令牌按账户绑定、仅留进程内存，取消隔离晚到响应，
不申请离线刷新令牌；重启/过期重新授权。注册指南见docs/MAIL_OAUTH_SETUP.md，
所有服务商的真实账户验收与发布审核仍待完成，不以预设/编译成功代替。

原生5项IMAP/XOAUTH2 JVM检查通过；手机4项隔离测试通过，覆盖Keystore/配置切换、
Microsoft等待/减速、拒绝、取消晚到响应、设备码/令牌到期和账号绑定；使用合成响应、无真实邮箱登录。
真机页面检查发现PersonalSources.call给零/单参数桥方法多传占位参数，导致读取保存设置失败；
修正实际参数数量并补按Kotlin签名检查，不能仅凭此前VM夹具认定桥接可用。
最终完整ArkTS→APK通过，普通真机8项及来源/OAuth 4项通过；页面入口、提供商选择、
空配置与OAuth无效参数提示复核通过。命令、APK哈希及范围见docs/PERSONAL_SOURCES_MIGRATION.md。
手册更新注册、授权入口、数据去向与撤销边界，生成/校验16章8图、导航8项通过；保留用户README改动，未修改鸿蒙参考源或Compose基线，
未运行会议专项。

## 日历/邮箱与聊天查询首段（2026-11-03）

按用户优先级实现显式日历读取、TLS IMAP收件箱、加密凭据、私有副本、手动分析，
并接入query_calendar/query_mail；源内容发送模型前逐次确认，提供时间/范围/截断信息。
原邮件codec/types及只读解析来自coevomind-app固定SHA，主锁和Compose基线不变。
完整构建、3项JVM、来源工具受控检查、12项集成、6项源码路由、7项桥检查通过；
手机安装与1项隔离持久化/Keystore测试通过，手册16章/7图检查通过。
实际命令、失败修正及边界见docs/PERSONAL_SOURCES_MIGRATION.md。
真实邮箱/日历/模型质量未验收；全量鸿蒙对齐仍有明确差项。用户进一步要求主流邮箱，
下一阶段补提供商及OAuth，注册步骤先文档化；会议专项未运行。

## coevomind-app规划更新（2026-11-03）

通过GitHub连接器读取分支与主要新增源码，固定目标4e86f6250c7739d0ea922e2379eeedad2d35d450。
compare显示与cd69b68分叉：ahead25/behind8，共同祖先182d2ca；不能当作线性升级。
更新架构指南并新增docs/COEVOMIND_BRANCH_PLAN.md及backlog入口，覆盖35领域和7个增量工作包。
新增Codex工作台/聊天委托、日历、邮件、来源管理，修订手机微信、增量同步及隐私差异。
Git ls-remote因网络连接失败；GitHub连接器读取成功。未改源码输入锁、应用代码或用户README。
本轮仅文档检查，不执行构建、上游测试、服务/设备测试，不提升功能完成状态。
实际检查：Python本地链接、代码块配对、D31–D35及U00–U06覆盖检查通过；git diff --check通过。
Mermaid未渲染；完整端点差异和新分支真实运行仍待后续核查。

## 全功能迁移架构规划（2026-11-02）

新增 docs/MIGRATION_ARCHITECTURE_GUIDE.md，按30个用户功能领域建立源码映射、
模块依赖、4组类关系图、拟新增接口契约、W00–W08实施包和出口验收。
对照当前ArkTS适配、PlatformBridge和通用Kotlin源码，区分现有类、共享源与规划对象；
保留GUI/PC/Python/身份替代/本地推理和真实服务待办，会议仍最后执行。
同步MIGRATION_BACKLOG规划入口；不修改应用代码、鸿蒙参考或用户已有README修改。
本轮为文档阶段，不运行Gradle/APK/会议测试，不新增功能验收结论。
实际检查：Python文档结构检查通过（30个D领域、9个W工作包、5组Mermaid代码块、
相对文档链接均存在），git diff --check通过。未运行Mermaid渲染器，图形布局尚未视觉验收。

## 图库选图 Method not implemented 修复（2026-11-02）

手机日志明确 imagePrepareStart 返回 BRIDGE_METHOD_UNIMPL。SDK BridgePlugin 的
反射调用把 InvocationTargetException 也映射到该错误；并非证明方法缺失。
已核对 API20 PhotoPlugin.getImagePath：查询 MediaStore _data 并拼 file://，
丢弃原始 content URI。该外部文件路径被现有 LocalImageAccess 私有路径门禁拒绝。
保留门禁，改由 SharedActivity 的 ACTION_OPEN_DOCUMENT 读取选中内容授权，
新增 photoPickerStart/Poll/Cancel 桥接和共享 GalleryPhotoPicker 平台适配。
保留1至9张上限、取消/失败状态；不申请全相册权限，不枚举相册，不触及私人图片。
图库、聊天与工作流共用此选图入口；旧相机入口不变。中文手册同步系统选图说明。

实际检查：新增 verify-photo-picker.mjs 通过 URI 原样传递/上限/取消/失败/版本/清理；
verify-gallery-scan.mjs 24项、verify.mjs 6项、verify-native.mjs 7项通过。
完整共享构建成功（Hvigor 10秒、Gradle 53秒），APK SHA256
a507b151e798161bab8c0e66ed9b441ab4607e37423365abcdae3388e058295f，
静态 ELF 和 ZIP 16KB 对齐门禁通过，不代表16KB实机执行。
androidTest构建33秒通过；安装主APK和测试APK均Success。
PhotoPickerDeviceTest 真机1项通过：受控合成 ActivityResult 保留content URI、
限制数量、取消后丢弃迟到结果。不是实际手动选择/OEM外观或模型分析验收。
NativeCapabilitiesDeviceTest 真机6项通过（包含实际私有图片预处理和OCR）；
最终已重新打开共享版，未清除用户配置和数据。
手册生成和--check通过15章/7图。未运行会议专项，用户README改动保留。

中间失败保留：第一次构建期间新增验证脚本触发来源过期门禁；首次指定的会议源码
路径不含SDK；一次并行Gradle测试编译出现宿主类未解析，随后串行完整重建通过。
待用户真实选图和云端视觉分析/落盘验收，不以合成ActivityResult冒充端到端通过。

## 自定义云中转配置与连通检查（2026-11-02）

沿用共享版 Planner/OpenAI Chat Completions 请求链路，无产品源码变更。
新增 scripts/configure-shared-cloud.py，从本地 JSON 读取 HTTPS 地址/模型/密钥，
停止调试共享包，只更新 Planner 三字段；保留其他偏好及隐私/云授权，提供私有备份、
原子替换与读回失败回退。真实地址和密钥不提交。新增 docs/SHARED_CLOUD_SETUP.md，
更新中文手册模型章节及 HTML。用户 README 修改保留。

桌面真实服务：模型列表可读，DeepSeek 非流式 HTTP 200 返回 CLAWMATE_OK；
DeepSeek/GLM 流式均 HTTP 200、完整正文 CLAWMATE_OK、收到 [DONE]（4/16块）。
只发送合成文字，不上传私有数据。自动审批拒绝保存本地配置/写入手机/启动应用的
组合命令，未给具体原因，未重试；不宣称配置工具真机成功或 App 联网通过。

实际检查：python -m py_compile、配置工具 --help 成功；verify.mjs 6项及
verify-integration.mjs 12项通过；手册 build.py 和 --check 通过15章/7图。
无产品源码修改，未重建 APK，未运行会议测试。待手机配置及真实聊天、保存/重启、
取消验收；视觉/工具/语音和长期稳定性仍待独立验收。

## 导师演示交付（2026-11-02）

新增 `scripts/record-mentor-demo.py`、`scripts/render-mentor-demo.py` 和
`docs/MENTOR_DEMO.md`。在 OPPO PKB110 共享版自动演示首页/聊天界面、公开本机
工作流 success/cancelled、图编辑器和截图本地预览。未调用云模型、未上传截图、
未运行会议测试；不提高迁移功能或真实服务验收等级。
scrcpy 5.0.1 录制完成，导出 91.93 秒 1920×1080 H.264 中文讲解版及竖屏原片。
实际运行两条演示任务并核对章节截图与视频关键帧；FFmpeg 全片解码零错误，
Python py_compile、git diff --check 通过。本次未重跑 Gradle/产品回归。
原生 screenrecord 写文件权限失败，改用 scrcpy；原生 stdout 探测进程未正常退出且
系统拒绝 shell 终止，后续测试机重启可清理。录像工具未修改设备权限。
原任务保留，追加两条公开演示任务；旧索引备份、录像和下载工具均未纳入 Git。
已有 shared-frontend/README.md 工作区改动未纳入本阶段。

更新：2026-10-29。迁移进度、阶段与验证统一记录于此；架构见[共享架构](docs/SHARED_ARCHITECTURE.md)，页面规范见[前端对齐](docs/FRONTEND_PARITY.md)，后续工作见[待办](MIGRATION_BACKLOG.md)。

执行范围按用户2026-10-28纠正：迁移鸿蒙ClawMate的全部功能并适配Android；会议语音的后续接入和专项测试放在最后。
聊天ASR/TTS、MiMO流式语音属于主线；自动控制、PC/Python、平台专有能力替代与本地模型/原生推理均在全量迁移清单中。
受限/占位状态继续算待适配，已接入但真实服务/质量未验收的条目继续保留验收缺口。

## 当前版本

- 主前端：ArkTS/ArkUI + ArkUI-X；Android 宿主 `shared-app`，包名 `com.clawmate.shared`，版本 `0.3-integrated`。
- 根 Gradle 同时管理共享宿主、Compose 基线 `app` 与共用 Kotlin 库 `android-capabilities`；原正式包和私有数据保留，共享版不双写或自动导入旧数据。
- SH-16已修复会议数学库的4KB ELF对齐；SH-17已在关闭兼容模式的ARM64 16KB内核中启动共享应用，通过22项数值检查及真实会议转写、页面保存和去重。完整UI回归仍有OCR/生命周期失败，稳定性未闭环，不能据此宣称已具备生产发布条件。
- SH-18在OPPO PKB110 / Android16、4KB真机使用默认时限完成16项设备检查与22项数值检查，全部通过；覆盖聊天、任务、会议、图库恢复、提醒与原生能力。该结果不代替SH-17的16KB完整验收。
- SH-19修复工作流确认超过执行上限后原始取消原因被覆盖的问题；修复版核心9项、70秒延迟取消对照及22项数值检查真机通过。16KB冷启动本轮进入recovery，聊天与完整16KB验收仍未闭环。
- SH-20按未移植清单从F12/P05开始，接通照片参考生成代码与上传确认；161项主机检查、核心9项及新增分身确认/取消1项真机检查通过。前景分割、聊天自定义分身依次待做，真实生成质量未验收。
- SH-21接通本地人物前景分割，复用原透明裁剪、PNG编码和消息缩略图；170项主机、127项共用库JVM、13项真机及22项数值检查通过。真实公开人物样本端到端分割通过，下一项为聊天自定义分身；动漫/复杂背景质量未验收。
- SH-22接通聊天自定义分身及本机选择保存，欢迎、消息、思考和任务过程共用当前分身；180项主机、14项真机功能检查及22项数值检查通过。动态图片无障碍标签仍未修复，真实生成/语音动画质量未验收；下一项F13流式语音。
- SH-24接通F13聊天MiMO流式/非流式语音、暂停/继续/取消、原WAV缓存与本机重播；192项主机、134项共用库JVM和11项非会议真机检查通过。真实MiMO音质、真人ASR和多厂商验收待做；下一项P01/P04受控GUI截图/规划。
- SH-25推进P01/P04首段：本应用实际截图、临时预览、逐次上传确认与Decider单步建议；跨应用控制、动作执行和有界循环仍待接入，建议不记作任务执行成功。
- SH-26落地按次跨应用手动控制代码和独立测试驱动；用户已允许临时权限实测，安装图片验证已由本人完成，当前需本人确认OPPO无障碍“保持开启”安全提示。真实手势尚未验收，不计P01/P04第二段完成。
- 鸿蒙只读参考：`doulujiyao12/mobiinfra-oh` / `clawmate_dev` / `cd69b68bf16b9ed352be5073939eb24d042de583`。当前页面/业务准备清单153个ArkTS文件中77个不变、39个适配、37个新增平台文件（含从原Index提取的上下文编排和原MiMO协议提取），另有作者启动Ability；不是全产品复用率。
- 统一开发分支：`codex/shared-frontend`，已合入上游 `13708b4`；fork `main` 跟随上游。用户决定先完成技术路线闭环，再与 fellow 讨论是否递交 PR；此前上游 #5 已关闭，后续仅推送 fork，不新建/重开/自动合并 PR。见[分支导航](docs/BRANCHES_AND_PRS.md)。未切换正式包名或将共享版合入 main。

## 功能覆盖

“接通”指共享版已有入口、调用和结果处理；不代表真实云服务质量或所有平台已验收。

| 范围 | 共享版实现与边界 |
| --- | --- |
| 引导、导航、主题、设置 | 原四页引导、五入口、页面/资源与私有偏好；Android适配系统栏、键盘和返回，HDS材质近似 |
| 首页、建议、陪伴 | 原提示词、来源证据、详情和缓存；`Index`连接模型协议，自由GUI/Qwen执行受限 |
| 聊天、会话、Markdown | 原会话持久化、思考/流式回复、格式渲染、复制、取消和错误；原上下文拆分/增量摘要、保存和复用已接入，本地抽取明确标记 |
| 记忆查询 F04 | `SharedAgent`→`MemoryQuery`→原索引；只读query_events，最多3次查询、有界JSON结果，不执行任意模型工具 |
| 图片聊天 F05 | 原附件面板→系统相册/相机→逐次确认→共用图片准备→多模态SSE；最多4张去元数据JPEG，仅本回合上传，历史图片保存在会话私有目录 |
| 图库、汇总、集合 F07–F09 | 原分析/检索/问答/照片引用、12类记忆、详情和分页；本地OCR共用，完整私人图库与真实视觉服务待验收 |
| 图库批次 F15 | 最多9张显式选图的持久JPEG副本，单项回执、原子检查点、暂停/继续和进程恢复；不自动上传，不枚举全图库，原始时间/位置不保留，日期为导入日 |
| 数据管理、长期记忆 F03 | `MemorySyncPage`→`DataManagerFacade`/`ChatMemorySync`→日志/索引；近7天范围确认、保存的模型名、进度/取消、检查点去重及无网络索引重建 |
| 工作流 F10 | 原图/JSON、上下文、新建/保存/复制/删除和文件查看；`AndroidWorkflow`调用共用执行器，支持等待/条件/有限循环/echo/summary、单任务/批量停止，限60秒/200步；真实结果写入run_summary.json，成功summary进入日志 |
| 视觉工具 F11 | 本机图片选择/私有保存、Decider视觉模型、逐次确认、可取消模型邮箱、answer输出与summary日志；GUI截图、完整schema校验及真实模型质量另需验收 |
| 采集提醒 F14 | 原面板/策略与任务页；Kotlin独占每日闹钟、权限/渠道、通知及防重收据，点击通知后前台解锁确认再执行有界任务；近似时间，省电送达仍待验收 |
| 语音、数字分身 | 原控件接共用系统ASR/TTS；SH-24增加原MiMO请求/解析、流式PCM播放、非流式WAV、暂停/继续/取消、原会话缓存与本机重播；SH-20照片参考生成与确认、SH-21人物分割/缩略图、SH-22聊天形象选择与图片回退已接入；真人识别、真实语音/出图质量、完整选图、动漫分割及语音动画待验收，动态图片无障碍标签仍未修复 |
| 云模型与授权 | Android兼容HTTP/SSE、超时/取消/授权撤回；端点、模型和凭据本地配置，图片与聊天记忆另作范围确认 |
| 本地模型、C++/MNN | 保留配置和明确能力边界；MNN/HiAI/OM推理及模型下载未接入 |
| HDC/GUI、Python | 共用库保留受限协议；共享执行器默认阻断GUI/HDC，Python/微信自动采集等仍需外部服务适配 |
| P01/P04首段 | 任务页独立入口，本应用ArkUI Surface截图、临时预览、逐次确认后发往Decider，复用原提示词并校验单步动作JSON；仅展示建议，不执行动作、写成功日志或开放跨应用控制；完整GUI循环仍待接入 |

## 关键阶段

| 阶段 / 提交 | 主要结果 |
| --- | --- |
| 早期Compose基线 `7f76afb` | Android外壳、Room/DataStore、流式聊天/智能体、图库/OCR、画像/分享、月度洞察、日志和受限任务/平台接口；NFC、受限无障碍、JobScheduler等旧版专项不算共享版已接入 |
| 2026-10-23，`04c7c0a`～`e21430d` | Windows构建；聊天记忆/事件查询、Markdown复制、首页建议/陪伴、图片聊天、单图云分析、引用问答和JSON编辑；修复取消、最新配置及入库/分析文案 |
| 2026-10-24～25，UI-01～12，至`cc23078` | Compose鸿蒙页面对齐，补齐图编辑、新建保存、详情及文件预览；当时1340项单元、27项设备测试通过 |
| 2026-10-25，`9ff69dd`、`86317b3` | ArkUI-X源码/协议/工具链验证；锁定OpenHarmony API20与ArkUI-X API20，修复UTF-8分块及取消 |
| 2026-10-26，SH-01～04，`ff68bde`～`0678fdf` | 共享应用、根工程整合、OCR/TTS桥接、原导航样式、主题/返回；真机修复重复键盘避让及深色手势区域 |
| SH-05～06，`7cb292a`、`53816d8` | 抽取同一份图片/ASR/工作流引擎与测试；补齐共享图片聊天、记忆工具/同步、任务运行/停止和结果查看，修复模型名、非流式取消及本地结果来源标记 |
| SH-07～08，`e89f6e7`及后续文档提交 | 统一交付记录、历史摘要、功能审计与验证结果；删除重复日志，采用项目阶段表述，应用实现不变 |

## 最近一次功能验证（2026-10-26）

| 检查 | 实际结果 |
| --- | --- |
| 完整JVM回归 | Compose 1243 + 共用库104 = **1347项通过**，零失败/错误/跳过；77项随实现迁移 |
| 主机检查 | 设置/路由6 + 桥接7 + 源契约8 + 整合12 = **33项通过** |
| 构建 | `build.ps1`完成ArkTS/HAP、匹配运行库导出及Android APK；两版测试包构建成功 |
| OPPO PKB110 / Android16 | **共享原生6项、Compose 34项通过**；实际中文OCR、TTS开始/停止、JPEG重编码、summary、GUI阻断和取消 |
| 共享界面 | 实际任务success、30秒等待停止后cancelled、运行文件列表/原文、记忆入口/空索引提示；此前已检查引导、会话/工作流持久化、主题、键盘和返回 |

受控模型/合成图片测试不等于真实服务效果；Compose设备测试不替代共享页面验收。文档整理阶段未重跑应用构建或设备测试。
精确命令、APK及18份证据哈希见[验证报告](shared-frontend/verification-reconciliation.json)；原始日志/截图/XML保存在忽略目录 `.arkui-x-validation/application/`。

修正记录：离线依赖、ArkTS类型、Windows换行和测试运行器问题均在修复后通过；曾发现任务停止不可达、运行文件名不匹配及错误AI标记并修正。真机坐标误触默认任务时执行器返回blocked、未做外部GUI操作；已恢复验证前状态，清理2个合成任务/6份运行记录及合成日志，保留原12个任务。

## 剩余边界与追溯

真实云服务/视觉/分身、真人ASR、相机实拍、多厂商Android/iOS、TalkBack及鸿蒙同状态像素/动画仍未验收。
GUI规划、HDC/Python/微信采集、提醒实际后台送达、MiMO、MNN/HiAI、照片参考分身、真实摘要质量和正式包/旧数据迁移仍有待办；动态主题下UIAutomator节点刷新边界保留。

历史全文保留在Git提交 `e89f6e7` 及其之前的版本；例如 `git show e89f6e7:docs/archive/MIGRATION_STATUS.en.md`。后续迁移日志只更新本文，其他文档维护设计、构建方法或待办。

## SH-09：本机会议 SDK 集成（2026-10-26）

分支 `codex/meeting-sdk-integration`。从 `on-device-meeting-notes` 源码引入 Android Library；
共享“汇总”新增会议入口。ArkTS 页面接入会议/音频/任务/版本、离线转写与本地纪要、模型组状态、
前台录音命令、正文编辑、回听和系统文件导入导出。Kotlin 只负责平台接口；会议后端独占会议数据，
ArkTS 在用户主动保存后独占写入 ClawMate 的记录/索引/收据。确定 export_id 文件名保证重复导入幂等，
来源版本/hash 和原交接包保留。源码依赖及复测方法见 [会议集成说明](docs/MEETING_INTEGRATION.md)。

验证记录：主机原33项及新增副本导入5项通过；Compose1243 + 共用库104项 JVM 回归通过；
`build.ps1` 实际完成 ArkTS/HAP、运行库导出及共享 APK 构建。
APK 校验确认同一包中 ArkUI-X/Python/ONNX、当前后端/网页、Kotlin桥接与服务，以及 SDK 原生库哈希一致。
公开中文音频已在 OPPO PKB110 / Android16 共享包完成真实转写。
会议专项设备测试1项通过：实际渲染页面保存副本、来源收据、重复导入去重与 Activity 销毁后 SDK
仍可使用。修复刷新后旧标题/模型/任务行复用和 SDK 复用过期 HTTP 连接；写请求不自动重试。
当前 SDK15项 JVM 测试在宿主 Kotlin1.9.24 工具链通过。
共享原生能力6项设备回归通过，包括中文OCR、JPEG处理、TTS回调、有界任务和取消。
本次共享调试 APK SHA256：`c3a43127fc5d27dc9457767bda4b94faa4047190c6c47dce1e697dd21dbdbd4e`。

本阶段只使用公开模型权重和测试音频，未复制原会议私有数据库；不改变 Compose 正式包。
长时间锁屏录音、来电/音频焦点、多厂商及16KB设备、正式签名与包体仍需专项验收。

## SH-10：共享前端维护门禁与发布边界（2026-10-26）

继续沿用共享ArkTS与Kotlin适配；本轮开发集中在构建可靠性，没有新增业务入口。
两层prepare的源码替换改为命中校验，单次替换遇到歧义即失败；重建overlay时清理旧生成文件。
新增准备输入/输出和编译运行库SHA256绑定，Android preBuild拒绝陈旧源码或运行库。
根Gradle允许Compose基线在未配置会议SDK时独立构建；共享宿主缺依赖时明确报错。
新增包体/ELF审计，包含Chaquopy嵌套Python扩展；原鸿蒙源码、正式包和私有数据未改动。

实测：原38项主机契约、9项维护检查、4项ELF审计器测试通过；两次真实prepare摘要一致，
注入的旧引导页overlay被清除。缺SDK和陈旧运行库的两次Gradle负向检查均按预期失败。
`build.ps1`实际完成ArkTS/HAP、运行库导出及共享APK构建；无会议SDK的Compose APK独立构建成功。
强制重跑JVM：Compose1243 + 共用库104 + 会议SDK15 = **1362项通过**，无失败/错误/跳过。
最终APK在OPPO PKB110 / Android16安装成功，共享原生6项与真实会议页面专项1项通过，
包括中文OCR/TTS、图片/工作流取消、公开中文音频转写、副本保存/去重及Activity销毁后SDK可用。
本次公开样例副本依测试设计保留供检查，源会议移入可恢复回收站；未启动麦克风。

当前全插件ARM64调试APK为**298.15 MiB**，SHA256：
`45d45ce522299c47a9289dbfdf08a64fd49c0c8601183573deffc8a874efdd61`。
ZIP对齐检查通过；325个ELF中324个满足静态16 KB对齐，会议SDK的
`libandroid-complex-math.so`为4 KB，发布门禁`--require-16kb`实际返回1。
107个按名称及SHA256确认的ArkUI-X原生库未发现此项失败；不能把会议依赖问题归因于ArkUI-X。
手机页大小4096，未做真实16 KB运行验证。

三次进程冷启动系统TotalTime为763/746/740 ms，文件缓存未清理；不代表首屏可交互时间。
最后一次启动约3秒后单次PSS为345229 KiB（337.14 MiB），仅作调试基线，不代表release稳态性能。
当前结论：路线适合继续开发，长期维护与正式替换仍须真实升级演练、16 KB依赖修复、包体/内存优化、
TalkBack、多厂商、真实服务及旧数据迁移验收。详见[维护评估](docs/SHARED_FRONTEND_MAINTENANCE.md)与
[精确证据](shared-frontend/verification-maintenance.json)。Android CLI skill在当前Windows环境未安装，
设备检查实际使用现有Android SDK的adb；本轮未依赖远程主机。

## SH-11：鸿蒙自动上下文摘要移植（2026-10-26）

复用原`ChatContextBudget`和锁定Index的十个摘要/编排方法，由prepare提取生成；Android适配只提供
当前保存的模型配置、可取消传输和提交门控。达到阈值后自动摘要、按设置保留最近轮次、复用/增量更新
已保存摘要，完整历史不截断；原四个`contextSummary*`字段及覆盖点继续沿用。
当前问题与图片不进入历史摘要，图片在末尾单独附加并再次检查预算。本地抽取明确标记可能省略细节；
停止、切换会话、撤回授权、模型配置变化与页面隐藏均阻止迟到摘要提交；保存失败恢复内存状态和输入。
修复首条相机消息新建会话时提前清理待处理图片及建会话失败不显示错误的问题；从本地切换云端会新建独立会话，摘要适配器拒绝本地历史。更新设置页说明并移除“自动摘要未接入”限制。
细节和边界见[上下文移植](docs/CHAT_CONTEXT_MIGRATION.md)。

验证：原51项主机检查及新增16项上下文检查通过；实际编译ArkTS/HAP、导出运行库并构建共享APK，
两次真实prepare一致且编译来源门禁通过。强制重跑JVM：Compose1243 + 共用能力104 + 会议SDK15，
共**1362项通过**，无失败/错误/跳过。OPPO PKB110 / Android16：新聊天页专项1项通过，实际输入
合成问题、在`.invalid`模型地址失败后进行本地抽取、保留原文/覆盖点，并在Activity重建后复用；
原生6项及会议页面1项设备回归也通过，共**8项设备检查通过**。

前四次设备尝试因无障碍填值/节点缓存和带图标的“发送”文字定位失败；确认实际渲染后改用键盘输入
及OCR定位，最终专项通过。该修正只改测试驱动，不能宣称TalkBack节点刷新问题已解决。
合成聊天、临时模型配置和索引均已恢复/清理；原生能力回归中的会议公共样例仍按原测试设计保留。
完整命令、失败记录和证据见[本阶段验证](shared-frontend/verification-context.json)。

本次APK SHA256：`f5c9b3234f76775c8667e567d5e483d1bfefdfc93d0b06a0cbb8321c93942094`。
APK负载与ZIP对齐检查通过；已知会议依赖`libandroid-complex-math.so`的16 KB ELF门禁仍失败，
不属于本阶段已修复范围。真实云摘要质量、实际模型token窗口、极端磁盘/进程中断与文件/索引事务、
多厂商及无障碍仍待专项验收；不以受控模型端口或本地抽取冒充真实云推理。

## SH-12：Android每日采集提醒（2026-10-27）

已将此前`fdb017a`、`58bacf0`推送到GitHub的`codex/meeting-sdk-integration`分支，
随后继续移植F14。原提醒面板、策略和任务页通过ArkTS适配调用共用Kotlin提醒引擎；
实现系统通知权限、渠道、每日近似闹钟、改时/撤销、时区/时钟/开机恢复入口和明确错误。
通知直接打开Activity，前台解锁后显示执行确认；先持久化当日领取收据再启动已开启的有界任务。
取消不启动任务，过期/旧计划/重复请求被拒绝；只有保存真实非空summary才更新最近采集时间。
Android独占提醒状态，ArkTS只缓存快照；Compose基线不增加提醒权限、不变更其数据。
当前编译输入141个：77不变、36适配、28新平台文件。详见[移植设计](docs/COLLECTION_REMINDERS.md)。

验证：**81项主机检查通过**，其中新增提醒14项；两次prepare一致、编译来源校验通过。
实际构建ArkTS/HAP、匹配运行库、共享APK及测试APK并安装；强制JVM回归
Compose1243 + 共用能力108 + 会议SDK15 = **1366项通过**，零失败/错误/跳过。
OPPO PKB110 / Android16：提醒3项、原生/会议7项、聊天上下文1项，**11项设备检查通过**。
提醒专项实际检查拒绝权限无注册、AlarmManager注册/改时/撤销、旧计划拒绝与受控时区恢复；
受控触发真实接收器后，经真实通知栏点击进入ArkTS任务确认，取消不执行，领取收据持久化防重。
测试前关闭的通知权限已通过系统设置恢复关闭（系统保留USER_SET标记），提醒偏好恢复、临时闹钟/通知清理。

早期尝试修复了ArkTS类继承类型、Kotlin集合类型推断、权限对话框自动化和OCR定位问题。
自然调度尝试等待约645秒并到达确认界面，但因OCR“已/己”定位失败；最终通过记录使用受控触发，
**不宣称自然调度或准点后台投递已验收**。真实重启、Doze、多厂商和锁屏仍需专项测试。
完整命令、失败与证据见[验证报告](shared-frontend/verification-reminders.json)。

本次APK SHA256：`d067f562f44febdc37c43690f8e7a2aa3f59f0b56a965fefb1efd896d341fe8d`。
APK负载和ZIP16 KB对齐通过；325个ELF中会议依赖`libandroid-complex-math.so`仍只有4 KB对齐，
`--require-16kb`实际返回1，107个ArkUI-X库无此静态失败。发布阻塞仍未解除。
本轮进一步验证了原界面/策略与Kotlin系统状态分离的可维护路径；长期维护结论仍需真实上游/SDK升级演练、
包体优化、无障碍、真实服务和正式数据迁移，不以此次功能通过替代这些验收。

## 评审与分支导航整理（2026-10-27）

SH-09～12已创建fork Draft PR #6，以既有共享功能PR #5分支为基底；功能检查点为`7d39bc8`。
按用户要求将共享PR #3～6统一为“共享1/4～4/4 + 具体内容”，补充依赖顺序，
上游本人递交的Compose PR #4也改为明确的Compose基线标题。
新增[分支和PR导航](docs/BRANCHES_AND_PRS.md)，区分最新已验证版、下一项开发、历史检查点与上游评审。
通过GitHub CLI实际读取/修改并复核标题、状态、head/base；文档diff检查通过。
本轮仅修改文档和PR元数据，未重跑构建/设备测试；未删除/改名/合并分支或强制推送。
下一项`vlm_qa`开发在`codex/workflow-vlm-qa`，未完成实现/验证，不纳入本次文档提交。

## SH-13：本机图片工作流视觉问答（2026-10-27）

独立分支`codex/workflow-vlm-qa`，以导航整理提交`245a3a9`为基底；没有并入PR #6或新增PR。
按锁定鸿蒙`WorkflowRunner.runTool`和原ToolEditor契约移植`vlm_qa`的answer/summary。
工具编辑器新增系统选图与去元数据JPEG私有保存；使用已保存的HTTPS Decider视觉模型，
逐次确认目标地址、模型、图片和完整问题。复用原云客户端/隐私撤回，发送及提交前核对授权、配置、
前台解锁和运行归属；网络期间失效主动取消。共用Kotlin执行器通过可选模型端口和单请求邮箱等待，
随机请求ID、防重回复、取消和总预算生效；Compose仍默认NOT_CONFIGURED，GUI/HDC不因接入模型而启用。
真实回答保存为步骤output；summary只在整个任务成功后由ArkTS写日志，标记模型来源并保留已有来源。
不把模型返回文本作为GUI命令或递归变量模板。当前142个编译输入：77不变、36适配、29平台文件。

验证：原81项及新增17项主机检查，**98项通过**；两次prepare一致，编译来源校验通过。
实际完成ArkTS/HAP、匹配运行库、共享APK与测试APK构建/安装。
强制完整JVM回归Compose1243 + 共用能力120 + 会议SDK15 = **1378项通过**，零失败/错误/跳过；
最后边界修正后重新执行共用库全部120项。OPPO PKB110 / Android16最终包**12项设备检查通过**：
视觉问答3、原生/会议7、聊天上下文1、提醒拒绝权限1。未重新申请通知授权，权限保持关闭。
模型设备专项包含实际桥接/执行器输出、防重、取消、16K转义文本往返及私有根目录拒绝，
并通过真实ArkTS任务页检查确认取消和`.invalid`模型实际传输失败，均无成功摘要写入。
受控回复不代表真实推理成功；既有提醒两个正向通知专项仍以SH-12记录为准，本轮未重跑。

初次Kotlin插值与ArkTS ArrayBuffer类型编译失败已修正；设备脚本补齐“配置任务”导航和滚动。
随后实际页面发现Android标准`file:/`引用被过严的`file://`检查拒绝，已兼容并补主机检查，
最终重构建后页面专项通过。测试恢复设置、隐私和任务索引，删除自身合成图片/配置/运行记录及私有备份；
会议公开样例仍按原回归设计保留。精确命令、失败及证据见[验证报告](shared-frontend/verification-workflow-vlm.json)。

最终APK SHA256：`59775bd88a46258d7b8a3485bb4e3b0199a6d2b7acaabd6f4d248924a5f5713f`。
负载和ZIP16 KB对齐通过；会议依赖`libandroid-complex-math.so`仍只有4 KB ELF对齐，发布门禁返回1。
完整JSON Schema验证/字段归一化、后台并行摘要、导入图片引用回收和真实视觉模型质量仍待补齐；
本项不代表默认GUI采集任务或微信自动采集已可执行。范围见[视觉问答说明](docs/WORKFLOW_VLM_MIGRATION.md)。

## SH-14：图库批次暂停与跨进程恢复（2026-10-27）

分支`codex/gallery-scan-recovery`，基底`7d34468`。Android选图进入原GalleryScanSession和分析队列；
图库先说明最多9张去元数据JPEG副本及导入日期，再保存私有快照、确认模型和上传范围。
Kotlin提供有界SHA256和窗口状态；业务图片引用、结果、进度仍由ArkTS独占。
继续前校验副本，按内容去重；显式重选可修复损坏副本。快照导入与分析共用执行门控，
在途队列未结束时禁止其他页面清空目录。复用原分析器及缓存，结果先保存再写单项回执；
检查点验证结构/日期/身份、重算累计值，经临时文件校验长度后原子替换。
暂停停止新派发，在途项允许完成；窗口失去前台、离页、撤回授权或配置变化时请求暂停。
重启后遗留running/pausing显示为paused，用户确认后仅重试queued/failed，不自动上传。
详见[图库恢复说明](docs/GALLERY_SCAN_RECOVERY.md)。当前编译输入144个：76不变、37适配、31平台文件。

实际检查：原98项与新增24项主机检查，**122项通过**；两次prepare一致，编译来源校验通过。
实际构建ArkTS/HAP、匹配运行库、共享APK及测试APK并安装；共用能力库强制执行**123项JVM测试通过**，
零失败/错误/跳过，新增3项私有文件摘要/边界/中断测试。Compose1243项与会议SDK15项为SH-13基线，
本轮未重新执行，不并入本轮JVM通过数。OPPO PKB110 / Android16最新APK**15项设备检查通过**：
图库3、视觉工作流3、原生/会议7、聊天上下文1、提醒拒绝权限1。

图库专项从合成私有图片与既有完成回执出发，真实页面确认后在失去前台时暂停；
外部force-stop后第二次仪器测试断言PID已变化，继续仅处理未完成项，既有完成文件内容hash未变。
真实`.invalid`模型失败保持可重试，未伪装扫描完成；JPEG准备摘要、私有副本校验和篡改检测也通过。
验证中先遇手机锁屏，用户解锁后继续；CREATED生命周期让承载测试线程的目标进程被OPPO冻结，
读取到`do_freezer_trap`且无ANR，检查点已暂停。改用可见但未恢复窗口的STARTED状态，再外部停止进程；
最终两个阶段分别2.759/4.674秒通过，后续最新APK再次复测。早期一次OCR点击进入清空确认，未确认删除；
后续重跑确认与完整性检查通过。该记录不能宣称TalkBack、后台连续执行或厂商冻结策略已经解决。

合成图片、缓存、日期目录、设置、隐私、检查点和原临时文件均按备份恢复/清理；通知权限保持关闭。
会议公开样例继续按原回归设计保留。当前APK SHA256：
`38ea27e5a4000e6f3a6771e28072d255c52f2ba815448b1dc337d088d42009df`。
APK负载和ZIP16 KB对齐通过；原会议依赖`libandroid-complex-math.so`静态ELF仅4 KB对齐，门禁仍返回1。
完整私人图库选择/真实模型效果、多厂商省电杀进程、掉电持久性和副本回收仍需验收；
本项不是后台全相册扫描或HDC移植完成。精确命令、失败及证据见[验证报告](shared-frontend/verification-gallery-scan.json)。

## 仓库整理：统一共享分支并接入最新上游（2026-10-27）

按用户要求，开发入口从 13 条历史功能分支收拢为 `codex/shared-frontend`；`main` 专用于同步上游。核对时 fork 远端已与上游同为 `13708b4`，本轮更新本地 main。原共享 Draft #3～7 由单一上游整合 PR 替代，完整提交号和恢复标签见[分支归档](docs/archive/BRANCHES_2026-10-27.json)。

整合采用保留历史的 merge，不强制推送。解决旧父链带来的文档、图片请求和忽略规则冲突，移除 merge 意外复活的已迁走源码/历史文档；逐项核对后，产品源码和共享编译输入仍与 `0108ca9` 一致。保留上游三份 Linux 工具链脚本及环境说明，同时保留已授权的 Windows 工作区和共享前端策略。

本轮实际运行全部122项主机检查，包括两次prepare一致性及ELF审计器单元测试；运行库绑定验证通过，摘要仍为 `b31c0fd86124b7eafe151ac710fb2f4391b7a385f7945e2977f7ef9cec89c32c`。

运行 `:app:testDebugUnitTest :android-capabilities:testDebugUnitTest :meeting-sdk:testDebugUnitTest :app:assembleDebug :shared-app:assembleDebug --offline --no-daemon --console=plain`：BUILD SUCCESSFUL，4m30s，152项任务中21项执行、131项为UP-TO-DATE。Compose 1243项测试本轮实际重跑通过；共用库123项、会议SDK15项沿用Gradle已验证缓存，未声称本轮重新执行。Compose APK重新打包，共享APK任务为UP-TO-DATE；本轮未重新编译ArkTS或重跑真机。三份新接入脚本逐一执行 `bash -n` 通过，Linux完整构建仍待该环境验证。原始日志在忽略目录 `consolidation-host.log`、`consolidation-gradle.log`。

此前 SH-14 的15项真机验证属于历史证据，不计作本轮重新运行。会议原生库的 16KB 对齐问题及真实云服务、多厂商/后台验收边界仍然保留，源码评审不等于生产发布批准。

仓库整理结果：整合提交 `d387b4d` 已推送；唯一评审入口为[上游 #5](https://github.com/yydhYYDH/ClawMate-Android/pull/5)。fork Draft #3～7 均已关闭；两个远端归档标签及其提交已核对后，通过带每条分支旧SHA保护的原子push移除13条旧远端分支，删除12条本地旧分支，本地/远端均只保留main与codex/shared-frontend。未重写历史或强制覆盖分支。

GitHub确认PR为 `mergeable=true`、`mergeable_state=clean`；尝试 `gh pr merge --auto --squash` 及明确的 `enablePullRequestAutoMerge` 请求，均被上游权限拒绝，后者返回“JKSOBBING does not have the correct permissions to execute EnablePullRequestAutoMerge”。因此无冲突已实现，Auto-merge未开启，需上游维护者处理。当前账号的上游权限为只读，未修改仓库审核或检查要求。

## SH-15：作者模板、固定资源与干净源码重建（2026-10-27）

本阶段按用户最新决定关闭上游共享整合PR #5，继续在fork的codex/shared-frontend开发，等待路线闭环后再讨论是否申请PR。未通知fellow、重开PR或尝试自动合并。

新增作者模板与bootstrap入口，移除对旧实验生成空模板的依赖。两份源锁共120个ArkTS文件，新增59个固定资源输入，逐项校验Git blob；其中补齐旧模板遗漏的11份原版默认工作流，并使用锁定的原版应用图标。引导只复制179个白名单输入，不带源端默认服务/账号配置、签名、模型、数据库或其他状态；目标存在不同参考文件时保留并报错。

Windows构建参数可指定外部SDK/构建工具/Node；本地绝对路径只写忽略配置。每次构建验证OpenHarmony 6.0.0.47 Release / API20、ArkUI-X 6.0.0.103 Release / API20、Hvigor/OHOS 6.22.7、ArkUI-X插件4.22.7与Node20.19.5。修正文档中上游工具链说明与实际锁定版本的偏差。Linux入口使用相同bootstrap/prepare/导出/seal流程，取消记录摘要后的临时配置/资源改写。

摘要覆盖作者模板、资源锁、固定资源、Linux构建入口及生成构建配置。prepare在忽略目录内清理旧生成资源、ArkTS和Hvigor目录，防止旧wrapper或未列入清单的源文件留下；主机检查注入旧overlay、孤立ArkTS及wrapper后核对再次prepare摘要不变。主平台代码与Compose产品保持现有功能边界。构建和精确验收命令见[引导说明](docs/SHARED_BOOTSTRAP.md)。

实测结果：
- 主机144项通过（原122项、新增14项输入/工具链引导及8项运行库导出检查）；两次prepare清除旧overlay、孤立ArkTS和wrapper后摘要一致。
- 新源码目录没有携带旧生成工程/参考输入/运行库/APK/Gradle项目状态；首次HAP成功用时1m42s，Android构建5m27s，90个任务全部执行。最终版本在原目录及隔离目录均完成HAP、SDK导出、seal及Android构建；最终Gradle分别32s（8执行/82缓存）和2m51s（18执行/72缓存）。
- 两处准备摘要相同：`3a901a2ad1b032c81dec2492033613c7ae86b20d7c1ba33f171eb2dabc6ebf92`，运行库文件清单同为398项；APK内11份默认工作流逐字节匹配锁定Git blob，旧图标不存在。APK不宣称位级相同。
- 测试APK最终构建成功，33s、98项任务中9项执行/89项缓存；OPPO PKB110 / Android16按独立进程完成9项检查（原生/会议7、聊天1、任务1），修正驱动后连续两轮通过。设置/索引由夹具finally还原，两份临时备份目录已确认不存在，失败截图已保存本地证据后从手机缓存清理，最终停止目标进程以丢弃内存中的夹具设置。会议公开样本沿用原回归保留策略。
- 共用库123项JVM测试任务为UP-TO-DATE；Compose1243项、会议SDK15项未在本轮重新执行。不将其计作本轮新跑测试。
- ZIP 16KB对齐检查通过；静态ELF门禁仍返回1：325个二进制中`libandroid-complex-math.so`仅4KB对齐，ArkUI-X的107个原生库未发现该问题。没有真实16KB设备运行结论。

当前APK SHA256：`38a9816a39e3f0f119055c09db719a78d10fcfe52cbe0e6d5e3da07b9961568a`。命令、24份原始证据哈希、最终产物及边界见[SH-15验证报告](shared-frontend/verification-bootstrap.json)。

校正记录：一次复制尚未完成就启动副本构建，被摘要门禁拒绝；等待复制完成后重建。真实API20 SDK没有可选的component/lib目录，修正过严布局假设后导出成功；必需引擎/API目录仍严格检查。新旧目录比对发现旧wrapper与旧图标残留，分别通过生成目录清理与临时导出树替换解决。首次将9项设备测试合批运行失败2项；隔离后聊天通过，任务仍因可见按钮的OCR整行匹配失败。截图/OCR记录定位蓝色“发送图片”被识别为“发迭图片”，仅在测试驱动添加该精确别名和同一行元素匹配，保留两次真实失败回执/无成功摘要断言，最终9项通过；没有修改产品逻辑或放宽模型/隐私确认。测试脚本同时判断JUnit文本，不能用ADB退出0冒充通过。

本轮仅验证预装工具和已有Gradle/pip/wheel缓存条件下的干净源码重建。Linux只做语法检查；真实上游/SDK升级、16KB依赖修复、真实模型、更多厂商/后台及TalkBack仍未闭环。OCR夹具适配不算产品无障碍修复；全流程离线、发布签名和生产数据切换未验收，继续不递交PR。

## SH-16：会议数学库重建与16KB静态门禁（2026-10-27）

追溯确认`libandroid-complex-math.so`来自会议SDK的Termux原生输入，原ELF仅4KB对齐，
并携带Termux RUNPATH；单独升级Chaquopy不会替换它。引入Termux 0.2固定提交
`706caf5e5ec39084ec19dc4c4fb99282fca85434`的24份原始文件（20份C源码），保留Git blob/SHA256
和完整许可。根Gradle只为可选会议SDK加载宿主适配，过滤旧预编译库，使用项目已固定的
NDK25.2/CMake3.22从源码重建，max/common page size均为16384；其他28份会议JNI输入逐字节保留。
会议依赖输出移至宿主忽略目录，SDK源码及原二进制、鸿蒙源码和Compose产品代码未修改。
共享APK包含每份源码的版权声明，Git属性保证固定输入在Windows检出时不被转换换行。

Windows和Linux共享构建入口均增加ABI/输入完整性、全APK ELF及ZIP16KB检查，失败即终止。
原生库数仍为325（含107份ArkUI-X和179份嵌套Python扩展）；**ELF门禁现在返回0，无失败库**，
build-tools36.0.0的ZIP16KB检查也通过。保留全部22个导出符号，没有新增导出；
依赖仅Android libc/libm，移除Termux搜索路径。方法见[会议原生库检查](docs/MEETING_NATIVE_16KB.md)。

本轮实际验证：
- 原144项主机检查全部通过，新增5项源码/ABI/包内容检查通过；复制源码后故意篡改的CMake负向检查
  按预期拒绝。两次prepare摘要一致，运行库398项与编译来源绑定校验通过。
- `build.ps1`实际完成HAP（17.119秒）、运行库导出/seal、APK及新增门禁；Android构建27秒，
  93项任务中7项执行、86项缓存。测试APK构建与强制JVM回归完成，1m14s、137项任务中15项执行；
  Compose1243 + 共用能力123 + 会议SDK15 = **1381项测试本轮重新执行通过**，零失败/错误/跳过。
- 未配置会议SDK的Compose独立构建通过，30秒、65项任务中8项执行；基线APK打包沿用UP-TO-DATE，
  不宣称重新编译了基线产品。Linux仅`bash -n`通过，未执行完整Linux构建。
- OPPO PKB110 / Android16安装最新共享APK；独立测试程序从实际安装库解析并数值检查全部22个导出，
  安装库SHA256与APK匹配。共享原生/会议7、聊天1、任务1，**9项设备回归通过**，包含公开中文音频
  实际转写、原页面保存副本和去重。测试恢复配置/索引并停止目标进程，数值程序已清理；
  会议公开样例按既有策略保留，源会议进入可恢复回收站，没有启动麦克风。

修正记录：最初Kotlin apply作用域不正确，曾只运行旧构建；改为明确作用于meetingProject。
随后Ninja无法进入中文依赖输出目录，迁移生成目录后通过。新增ABI门禁发现NDK自动加入未使用libdl，
添加`--as-needed`后只保留原有libc/libm；build-tools34的zipalign不支持`-P`，共享门禁改用已要求的36。
各次失败日志及29份证据哈希保留于[SH-16验证报告](shared-frontend/verification-native-16kb.json)。

最终APK SHA256：`f1847e9e656d4ab1db36e5f07236d565b9befafc74aa0350ca6e98c535585f2c`，
312,627,930字节。原会议数学库的**静态对齐阻塞已修复，16KB运行验收尚未完成**：
当前手机`PAGE_SIZE=4096`；Windows x86-64主机无模拟器/系统镜像/AVD，固件虚拟化查询返回不可用。
仍须在原生ARM64、`PAGE_SIZE=16384`且关闭兼容模式的环境复测启动、数值、实际会议转写及核心流程。
本轮不宣称16KB环境运行通过、生产发布批准或完整技术路线闭环；真实云服务、多厂商/后台、TalkBack、
发布签名和旧数据迁移仍保留原有边界，未创建或重开PR。

## SH-17：ARM64 16KB运行环境与真实会议链路（2026-10-27～28）

本阶段推进16KB运行验收。使用官方Cuttlefish镜像/配套宿主工具build 16644040，
以及同批16KB内核和模块initramfs build 16733175，在Windows的WSL中启动完整ARM64 Android17/API37系统。
`PAGE_SIZE=16384`、`uname -m=aarch64`、启动完成状态均实读确认；安装前关闭linker和PackageManager的
两项页大小兼容控制。该环境由x86-64宿主通过QEMU TCG执行ARM64系统，不能作为物理设备性能或稳定性结论。
官方输入、公开模型/音频URL与SHA256见[运行输入锁](experiments/android-16kb/runtime-lock.json)，
方法与边界见[16KB运行说明](docs/ANDROID_16KB_RUNTIME.md)。

修正此前对本机环境的判断：Windows固件虚拟化查询不可用，并不表示WSL没有可用环境。
实际有/dev/kvm与Cuttlefish工具；跨架构guest仍使用TCG。首次长Unix套接字路径、NTFS目录延迟、
缺少宿主基础配置和网络初始化失败，改为短路径、Linux ext4运行目录、关闭外网的独立guest后继续。
自定义内核在Gunyah检测处发生异常，测试环境仅排除gunyah_init、gunyah_rm_init两个initcall后启动；
首次空guest的user-0初始化进入recovery，只清空该新建空guest后正常启动。均未改产品或原手机数据。

本轮实际结果：
- 149项主机检查通过：ArkTS/引导123、运行库导出8、原源码契约8、APK审计4、会议源码/ABI/包完整性5、
  编译来源绑定1。两份PowerShell驱动AST检查通过；严格门禁在4KB手机上按预期拒绝，倍率6被参数验证拒绝。
- 共享Android构建22秒成功，93项任务中7项执行/86项缓存，APK组装为UP-TO-DATE；最终测试包构建51秒，
  101项任务中11项执行/90项缓存。本轮没有重新编译HAP或重跑1381项JVM测试，后者仍是SH-16历史结果。
- 从16KB系统实际安装的数学库解析并数值检查22个导出，全部通过，安装库SHA256与APK一致。
- 原生能力6项逐项通过，包括真实中文OCR、JPEG准备、取消、受限工作流和GUI阻断；TTS返回明确不可用，
  不计作语音播放成功。所在7项合批运行的会议用例失败，未将整组记作通过。
- 最终独立会议用例313.094秒通过：新建公开样本、实际转写出“开放时间早上9点至下午5点。”，
  原共享页面保存、不可变来源回执、二次保存去重及Activity销毁后的SDK服务可用全部通过；麦克风未启动。
- 最终聊天用例失败：OCR等待超时，ActivityScenario关闭时另有DESTROYED状态等待超时。
  早期一轮曾完成实际发送、本地摘要标记、覆盖范围持久化和原历史完整性，但Activity重建后的导航定位失败；
  不将该部分成功计作整个聊天用例通过。最终工作流用例429.758秒后失败：已经找到并点击配置、运行和取消，
  但失败回执的lastMessage不包含“取消”，断言失败；该消息实际内容未输出，根因未定，后续发送图片与
  两条失败回执/无成功摘要断言未执行。**本轮16KB设备检查为7项通过、2项失败，不是9项全过。**

仅增强测试驱动：显式选择serial，记录内核/兼容开关，并通过Require16Kb在编译/上传/安装前拒绝不符环境。
测试倍率默认1，仅显式选择1～5时扩大等待预算；只读会议查询可有界重试，所有写请求保持不重放。
TCG首次Python导入超过原90秒，两次默认时限失败；延长后服务启动，但模型加载期间一次只读请求连接重置，
有界重试后取得真实转写。初次页面保存的OCR坐标过期，未实际触发交接请求，回执断言失败；
最终用新鲜虚拟节点定位、OCR兜底并检查画面未移动，保存/去重通过。
聊天/任务稳定导航在慢速模式使用当前可见节点，条件按钮仍走画面识别；未放宽业务结果断言。

较早两次合批回归还出现独立测试辅助进程com.clawmate.shared.test的ART MarkCompact崩溃，
分别为SIGSEGV和byte类型class flags异常；保存的映射未见本应用ArkUI-X/Python/ONNX库，
但这不能证明根因，稳定性问题仍未关闭。最终聊天失败时前台白屏属于测试框架EmptyActivity，
WSL与Windows内存/交换区读数没有显示资源耗尽。当前只能确认基础与会议链路在该16KB环境中的实际结果，
**完整UI回归与稳定性验收尚未通过**，仍须其他受支持环境或原生16KB实机复核。

产品APK保持SH-16的SHA256 `f1847e9e656d4ab1db36e5f07236d565b9befafc74aa0350ca6e98c535585f2c`，
312,627,930字节；本轮改动为测试驱动、测试夹具、输入锁与验证记录。鸿蒙源码、会议SDK源码、Compose产品、
正式包名及原有私有数据未修改，未创建/重开PR。结束时确认夹具备份已还原并清理、目标进程停止、
独立数值程序已移除，再通过官方stop_cvd停止本次模拟器；公开模型与镜像缓存保留在忽略目录。
下一步先采集聊天OCR/销毁等待的线程堆栈及工作流失败回执的具体内容，区分产品、SDK、框架与TCG问题，
再在受支持的其他环境或原生16KB实机复验；不继续通过提高等待倍率消除失败。
精确结果和原始证据哈希见[SH-17验证报告](shared-frontend/verification-16kb-runtime.json)。

## SH-18：现有共享功能真机回归（2026-10-28）

按用户要求在已连接的OPPO PKB110 / Android16（API36、ARM64、PAGE_SIZE=4096）复验现有共享功能。
安装当前共享APK与最终测试APK，使用默认等待倍率1。未改产品代码、重新编译HAP或运行JVM测试；
Compose基线未参与本轮设备测试。APK仍为SH-16/17的 `f1847e9e656d4ab1db36e5f07236d565b9befafc74aa0350ca6e98c535585f2c`。

实际结果为 **16项设备检查全部通过，0失败**，另有22项数学库导出数值检查通过：
- 核心9项：原生能力6项与实际会议1项合批20.624秒通过，聊天1项7.845秒，工作流页面1项4.822秒。
  包括真实中文OCR、TTS真实开始/停止回调、JPEG准备、取消、summary与GUI阻断；公开中文音频实际转写，
  原共享页面保存不可变副本/去重及会议服务生命周期；聊天本地摘要标记、完整原历史、Activity重建后的摘要复用；
  工作流图片确认、取消及真实.invalid传输失败，失败回执持久化且不写成功摘要。
- 模型结果保真/防重放、待处理模型取消拒绝迟到结果、图片SHA256与私有副本/篡改校验，共3项通过。
- 图库2项分别2.773/5.124秒通过：真实页面确认后暂停，外部force-stop后新进程仅恢复未完成项，
  原完成项哈希未变，8项.invalid失败仍可重试，未伪装完成。仅使用合成私有图片，不扫描私人图库。
- 提醒2项4.777秒通过：实际AlarmManager注册、时间修改、时区恢复、禁用，以及实际通知点击、
  共享页面确认/取消与持久防重。通知权限原已授予，保持不变；未运行需撤销权限的拒绝分支。
  交付通过受控触发器调用真实receiver，不宣称已验证厂商省电下的定时送达。

额外现场检查了首页、汇总、主聊天页、任务、“我的”和个人隐私设置入口，保存本地截图/XML；
初次按标签定位选中了汇总里的“聊天”分类，随后明确点击底部主导航并截图确认主聊天页。
未修改设置、调用真实云服务或发送私人图片/消息；本轮不宣称首次引导、真人麦克风ASR、实际相机与分身生成已验收。
原生数值程序从实际安装库读取全部22个导出，库指纹与APK匹配，执行后已清理。

测试夹具按finally恢复设置/索引，确认聊天、工作流、图库备份均不存在，合成图库日期目录已清理；
测试输入截图和设备临时文件已移除，公开会议导入副本按既有策略保留，源会议进入可恢复回收站。
手机留在首页供用户查看，未动正式包名和旧版私有数据。精确结果、命令与28份证据哈希见
[SH-18真机报告](shared-frontend/verification-real-device.json)。

SH-17中失败的聊天与工作流用例在本机默认时限下均通过，这有助于后续对照定位，但不能证明模拟器失败的根因，
也不代替16KB原生实机或其他受支持环境验收；SH-17的7过2失败和ART辅助进程崩溃记录保留。
本阶段仅更新验证报告与阶段记录，未新建/重开PR。

## SH-19：过期确认的错误原因保留与诊断补齐（2026-10-28）

继续定位SH-17两项失败。主机回归先复现一个具体错误处理分支：用户在图片确认弹窗取消，
但原生任务已到执行上限，workflowToolReply拒收；AndroidWorkflow原先只抛出“所属任务已结束”，
覆盖了WorkflowVlm返回的用户取消原因。OPPO真机在运行后刻意等待70秒再取消，73.779秒后以同一消息失败，
证明该问题不依赖16KB或TCG。SH-17原失败未输出实际消息，因此不能直接断言其根因完全相同。

修复仅保留原始失败原因并附加任务过期说明：不延长原生60秒/共享层65秒上限，不接受迟到模型结果，
不重试写请求，也不虚构原生运行结果、成功摘要或采集记录。新增两项实际ArkTS主机回归分别验证
取消原因保留与迟到成功回答仍被拒绝、不落盘；修复前第一项按预期失败，修复后全部19项视觉工作流检查通过。

真机延迟取消对照修复后73.771秒通过，实际消息为“用户取消视觉问答，未发送图片；视觉问答所属任务已结束，
未提交迟到结果”，同时断言运行目录与采集日志未新增。正常模式核心9项全部重跑通过，
包括原生/会议7项、聊天重建复用1项、工作流正常确认/取消/不可用模型1项；22个数学导出数值检查通过。
本轮不是SH-18全部16项的再次运行。其他主机检查连同新增两项共151项通过。

产品完整重建：HAP 1分0.246秒成功，398项运行库导出与来源绑定成功；Android构建33秒，
93项任务中10项执行/83项缓存；功能测试APK构建1分1秒，101项中11项执行/90项缓存；诊断错误保护补齐后最终测试包53秒构建成功，核心9项再次通过。
源码/ABI/包完整性、325项ELF与ZIP16KB门禁通过。本轮未重新运行JVM测试或Compose设备测试。
新APK SHA256：`41b7b152682785027d79edcbfad34d506d8f03e791a8b9d276757fa43c47cbf6`，312,627,930字节。

补充诊断：工作流用例在断言前输出本用例的实际取消/不可用回执；增加有上限的测试用70秒等待入口，
默认0不改变普通回归。聊天OCR异常及Activity清理失败时保存线程状态与Java栈，
不读取偏好、消息或凭据文件，诊断异常不会替换原失败；本轮通过的真机聊天用例没有触发这些失败钩子，
不宣称已取得新的16KB聊天销毁堆栈。

16KB既有镜像本次冷启动进入recovery，读取到init_user0_failed；未执行清除数据，尝试不清数据reboot，
本轮未确认sys.boot_completed=1，之后用官方stop_cvd停止。保留启动/读取失败日志，
不宣称已确定冷启动根因，也未在该环境安装/验证新APK。聊天原失败、ART测试辅助进程崩溃与完整16KB验收继续保留。
手机测试结束确认备份夹具已恢复并清理，公开会议副本按既有策略保留；产品、测试和证据变化见
[SH-19验证报告](shared-frontend/verification-expired-confirmation.json)。鸿蒙源、Compose产品和会议SDK源码未改，未创建/重开PR。

## SH-20：F12/P05-1 照片参考数字分身（2026-10-28）

按用户新要求转向尚未移植能力，采用待办表顺序：先完成F12/P05的照片参考生成，再做前景分割和聊天自定义分身，
之后进入F13、P01/P04、P02/P03、P06。16KB问题继续记录，不作为本轮功能移植的前置阻塞。

移除共享首页对image_to_image的硬性阻断，新增AndroidAvatar适配。根据生成方式选择文生图或图生图模型，
图生图配置不再依赖文生图模型字段。复用原SeedreamAvatarProvider、AvatarService、AvatarStorage、
版本历史与来源/同意快照；没有另造Kotlin业务存储或模拟生成结果。
参考照片缓存改用共用Android图片准备通路，限制大小并转成去元数据JPEG；保存到原分身私有目录。
上传前说明参考照片的发送范围，并展示画像提示词、目标服务及模型供用户确认；取消不准备/上传照片、不改变分身版本。
准备完成后再次检查授权与配置，记录本地照片来源而非将data URL写入同意快照；
请求并发互斥、失败释放占用可重试，原当前版本保留。文生图仍不携带照片，服务端水印强制保留。
生成前校验错误与请求失败现在有可见提示。

验证：
- 新增10项主机检查，执行真实控制器、原provider请求构造、原service/version流程，图片/网络/存储端口受控。
  覆盖照片模型/JPEG请求、水印、同意与来源、拒绝不上传、错误配置、文生图无照片、授权撤回、配置变化、
  无效准备结果、失败重试、并发互斥、参考缓存字节。合计161项主机检查通过。
- HAP 13.496秒、Android 20秒成功（93任务、10执行/83缓存）；最终测试包36秒（101任务、11执行/90缓存）。
  398项运行库来源绑定、源码/ABI/包完整性、325项ELF及ZIP16KB静态门禁通过；本轮没有重跑JVM测试。
- OPPO PKB110 / Android16 / 4KB：核心9项全部通过；新增真实ArkTS分身页面检查3.721秒通过。
  夹具仅使用自建蓝色JPEG和.invalid配置，从已保存参考照片进入原首页分身弹层，实际点击“使用照片生成”，
  出现上传确认后取消，状态文件字节和版本集合均未改变。未读取私人图库或发送私人照片。
  首次纯虚拟节点定位无法找到取消；保留失败结果，改为识别实际渲染按钮后通过，业务断言未放宽。
- 新APK安装后的22项数学导出数值检查通过；夹具设置、隐私状态、分身state已恢复，自建照片和备份已清理。

本轮完成的是照片参考生成的代码接入，**不宣称真实云端出图效果或完整系统选图流程已验收**。
主机网络与生成图片结果为明确的受控测试端口；真机只验证已有参考图到上传确认/拒绝，未调用真实图片服务。
前景分割仍保留明确未接入状态，没有制造透明前景；聊天自定义分身仍是后续子项，F12/P05整体未标为完成。
新APK SHA256 `fa196eef724b3e1446e4e2d0735a40d07ec10c830736b628c84a232b1aa3498d`，312,627,930字节。
方法、结果与19份原始证据哈希见[照片参考验证报告](shared-frontend/verification-avatar-reference.json)。
鸿蒙只读参考、Compose产品与会议SDK源码未改；用户原有README编辑保留，未创建或重开PR。

## SH-21：F12/P05-2 本地人物前景分割（2026-10-28）

继续按未移植能力顺序，接通前景分割，下一项为聊天自定义分身。
`android-capabilities/avatar` 使用随包 ML Kit `segmentation-selfie:16.0.0-beta6` 执行本地人物分割，
只接受私有图片，限制文件/像素大小与512px输出；不上传图片、不依赖在线下载分割模型。
共享宿主桥接有界后台任务和RGBA结果；原ArkTS alpha裁剪、PNG编码、168×216等比透明画布及
AvatarStorage版本资产路径保持复用。生成后的原best-effort入口与启动补齐已连接，
已有前景缺少聊天缩略图时仅解码补齐，不再次推理；失败保留原分身并提示换用清晰人物图片。
串行队列避免同版本竞争，45秒前端超时/宿主关闭丢弃迟到结果；原生任务超时后保留推理输入至任务完成再释放。

验证：
- 新增9项共享服务/原裁剪及PNG/缩略图主机检查，覆盖缓存、补齐、并发、空前景、无效蒙版/协议、失败重试与超时清理；合计170项主机检查通过。
- `:android-capabilities:testDebugUnitTest` 实际执行127项，0失败/错误/跳过；新增4项验证颜色、原alpha、置信度及无人物/非法蒙版拒绝。本轮未重跑Compose基线测试。
- 最终HAP 12.502秒、Android 21秒成功（93任务，10执行/83缓存）；单元测试与测试包构建66秒（106任务，18执行/88缓存）。首轮ArkTS拒绝构造函数参数字段声明，改为显式字段后通过。
- 398项运行库来源绑定、原生完整性、全部326项ELF及ZIP16KB静态检查通过；本轮未执行16KB内核运行验收，既有稳定性问题保留。
- OPPO PKB110 / Android16 / 4KB：核心9项、前景专项3项及照片确认回归1项共13项通过；22项数学导出数值检查通过。
  前景专项1.894秒，使用测试包内NASA公开人物照片，实际经过共享首页启动→原AvatarService→原生模型→原裁剪/存储，
  输出512×499 PNG（106470个完全透明像素、138005个alpha≥200像素）及168×216消息缩略图；
  原图与版本文件字节未变。真实模型拒绝纯色无人物图，远程/外部/未授权内容路径被拦截。
  照片上传确认/取消回归3.564秒通过；全程未使用私人照片或真实图片云服务。
- 检视实际透明通道及白底/alpha诊断图，人物保留、背景透明，部分边缘仍有不确定像素。
  夹具设置/state、临时版本/图片已恢复或删除，手机上的公开样本结果副本和备份目录已清理并重启应用。

本适配为人物分割，不代替通用动物/物体分割；只验收一张公开真实人像，动漫分身、复杂背景、发丝/道具边缘及多厂商效果仍待验收。
照片真实云端生成质量与完整系统选图仍沿用SH-20边界；聊天自定义分身尚未迁移，F12/P05整体仍未标为完成。
新APK SHA256 `9605210451ed3a426d2db225848e273d805df7254428a8ed85e923145730a2e3`，312,628,469字节。
命令、结果、样本来源和27份证据哈希见[前景分割验证报告](shared-frontend/verification-avatar-foreground.json)。
鸿蒙源、Compose源与会议SDK源码未改，用户README编辑保留，未创建/重开PR或切换正式包名/数据。

## SH-22：F12/P05-3 聊天自定义分身（2026-10-28）

原DigitalAvatarView已支持个人图片，但锁定版本的聊天实例均固定猫助手；本阶段补宿主选择连接。
聊天顶栏提供“默认形象/我的分身”切换，选择保存在原SettingsStore；仅新增选择字段，图片URI为瞬态派生值。
ChatAvatar从原DataManagerPaths.avatarAssetsRoot校验当前版本资产，欢迎页使用完整前景，
普通消息/思考行使用消息缩略图，任务过程保持静态图片与原外层点击展开。
原视觉状态、点击动画、语音活跃消息映射和用户/系统角色处理保留，生成或刷新后同步当前分身缓存。
未生成分身时提示先去首页，图片读取失败回退默认猫助手；切换不改变会话输入、模型请求或上传照片，保存失败回滚选择。

验证：
- 新增10项主机检查，覆盖选择、原目录校验、缓存缺失/更新、未知值、图片失败、语音状态映射、原点击逻辑、
  原SettingsStore保存/重新加载及保存失败回滚；合计180项主机检查通过。本轮未重跑JVM/Compose测试。
- 最终HAP 21.948秒、Android 33秒（93任务，10执行/83缓存）、测试包73秒（101任务，11执行/90缓存）通过。
  398项运行库来源绑定、原生完整性、326项ELF与ZIP16KB静态门禁通过；未执行新的16KB内核运行检查。
- OPPO PKB110 / Android16 / 4KB：修复版核心9项、新增聊天分身功能1项、分割回归3项、照片确认回归1项共14项功能检查通过；22项数学导出检查通过。
  分身专项9.021秒，使用自建绿色完整图和粉色缩略缓存，实际页面测得欢迎图69837个目标像素、
  消息缩略图14780个目标像素且完整图像素为0；选择已写入偏好并在Activity重建后保留。
  任务头像实际点击后，正确过程记录的collapsed状态变为false；切回默认及新版本损坏缓存回退时，
  自定义像素消失，实际猫助手2472个特征像素出现；核看了自定义/回退截图。没有调用真实出图服务。
- 原设置、Debug设置、分身state与会话索引已恢复；自建版本/图片/会话、手机证据副本和备份已清理并重启应用。

保留失败与边界：首轮页面覆盖绕开输入法适配，已改为在既有平台适配后应用增量；测试包首次因Kotlin空格语法失败，已修正。
真机首次发现目录校验重复拼接files而拒绝合法资产，改为直接使用原facade目录并补回归。
任务过程夹具先补原Debug显示开关，再补虚拟节点/实际渲染像素定位；原过程持久化断言保持。
**无障碍检查未通过**：图片已实际回退，但未从虚拟节点取得预期的新图片标签，缓存或合并语义根因未定；
保留原失败记录，功能回退使用正向猫助手像素和截图验证，同时单独记录标签false，不把该无障碍项算作通过。
实际ASR/TTS与头像动画同步、多厂商、真实生成、完整选图及动漫/复杂背景效果仍待验收。
F12/P05三个子项已完成代码接入，完整产品验收未完成；下一项F13 MiMO流式语音。
新APK SHA256 `029d904b113411bbea0f63200a529ce610edf79b679782603daadd58fffd3ca0`，312,628,469字节。
命令、结果及20份最终/9份早期失败证据哈希见[聊天分身验证报告](shared-frontend/verification-chat-avatar.json)。
鸿蒙源、Compose源、共用原生能力及会议SDK源码未改，用户README编辑保留；未创建/重开PR或切换正式包名/数据。

## SH-23：全量迁移目标与会议后置（2026-10-28）

按用户纠正，主线是鸿蒙ClawMate全功能Android适配，会议语音后续接入和专项测试是最后独立阶段。
已将该规则写入AGENTS.md与迁移清单；移除本地模型/原生推理无限期暂缓的表述，明确受限适配不算功能完成。
后续顺序为F13聊天流式语音、P01/P04、P02/P03、P06、本地推理/剩余功能与整体验收，最后继续会议语音。
现有会议代码与历史验证记录保留；本轮没有新增会议功能或执行会议专项测试。

修正`test-shared-device.ps1`默认回归分组：普通能力6项、聊天1项、工作流1项，共8项；
会议专项仅在最终阶段通过`-IncludeMeeting`追加到最后，统计按实际选择分组计算，不再固定宣称9项。
PowerShell解析检查通过；使用既有SH-22 APK在OPPO PKB110 / Android16 / 4KB执行新默认驱动，
实际8项通过，输出`meeting included: False`。本轮没有修改产品源码，没有重跑产品构建、JVM或会议数值专项。

核对F13原始实现：锁定上游同一提交的`utils/voice/MimoVoiceClient.ets`与`pages/chat/ChatVoiceOrchestrator.ets`，
分别为blob `08c7277d9d5db9b729ead30bd5f329ff1bc4a307`、`32211f8a7c6d574ec31c3d5485df4ca439f98c28`；
原链路是聊天会话/语音状态与MiMO流式音频协议，不是会议SDK。下一实现阶段应复用该协议与编排、适配Android播放能力；
当前共享版仅有系统ASR/TTS，F13流式云语音尚未接入，不把本轮范围调整记为新增产品能力。
本轮命令和原始回执保存在忽略目录`.arkui-x-validation/sh23/`与`application/sh23-non-meeting/`。
用户README编辑保留，鸿蒙、Compose、原生能力与会议SDK源码未改；未创建或重开PR。

## UM-01：中文图文用户手册与维护规则（2026-10-28）

新增 `docs/user-manual/` 离线用户手册：15章、7张逐张核看的真实界面图、3组操作流程图。
覆盖首次使用、五入口、模型配置、聊天/图片、图库暂停恢复、汇总与记忆、首页、工作流、
提醒、会议、分身/语音、隐私清理与故障处理。基于SH-22实现和既有适配/验证记录核对；
SH-23只调整迁移优先级，不改变本手册所述产品能力。明确云端数据去向、Android受限项及未验收范围。

根README新增手册入口；AGENTS新增用户可见变更同步维护手册的规则。正文源为content.json，
Python标准库生成静态HTML，CSS/JS和截图全在本地；README记录源码对应和更新方法，
screenshots.json记录来源、SHA256和差异。7张图约3MB；主导航为SH-18，图库为更早版本，
旧工具栏及SH-22聊天分身切换差异已在图注说明。没有导入私人消息、密钥或私人照片。

实际执行与结果：
- `python docs/user-manual/build.py` 生成成功；初次校验误把未设置src的弹窗图片当成正文图片，
  已将清单检查限定为有src的图片，随后生成及只读检查通过。
- `python docs/user-manual/build.py --check`：15章、7图摘要、离线引用、锚点、HTML一致性通过。
- `node --check docs/user-manual/manual.js`、`git diff --check`通过；Git仅提示AGENTS行尾转换。
- 本地127.0.0.1静态服务 + 内置浏览器实际检查：1280宽与390宽页面无水平溢出；
  搜索“提醒”得到3章，无结果提示正常；跨结果跳转重置搜索并恢复15章；图片原图1080像素加载、
  弹窗及Esc关闭通过。保存预览于忽略目录 `.arkui-x-validation/user-manual/preview.png`。
- 点击打印按钮时CDP输入超时，随后原手册仍可读；**打印预览/PDF排版未验收**，不计为通过。
  已提供打印样式及打印前显示全章/展开FAQ逻辑。本阶段不生成PDF交付物。

本阶段只改文档和维护工具，未重建Android/HAP或运行设备/JVM回归，不复用历史结果冒充本轮通过。
首次引导、模型字段、会议和新版分身等仍需补充当前版本专用截图；手册可离线打开但尚未内嵌应用，
应用新手配置向导和易用性改进需独立实现。鸿蒙参考、Compose、应用功能和原有数据未改；
用户对shared-frontend/README.md的既有修改保留且不随本阶段提交。

## SH-24：F13 聊天MiMO语音与Android播放（2026-10-28至29）

从同一锁定上游提交提取MimoVoiceClient的六个原始请求/解析/URL方法，直接复用原VoiceTtsPresets、
PcmWav、语音元数据和会话资产命名；新增CloudSpeech共享编排承接授权、流式状态、取消、缓存与重播。
没有直接编译原Harmony网络/播放器及整个ChatVoiceOrchestrator；Android共用库只提供有界HTTPS/SSE、
AudioTrack PCM16/24kHz单声道与私有WAV MediaPlayer播放，宿主桥负责前台/解锁和生命周期。
语音设置全空时沿用系统TTS；填写云语音后使用已有服务/模型/音色/角色/流式设置，缺项明确报错。

接通首批PCM到达即播放、暂停/继续/生成中取消、完整流转原WAV缓存、非流式合成和无网络缓存重播。
仅尚未收到音频的流式失败尝试一次WAV回退；截断/奇数字节流不得保存为完整缓存。
原子临时文件、8MiB音频上限、有限队列/请求超时、HTTPS和重播目录边界生效；
授权撤回、服务配置变化、会话切换/删除、后台与过期返回停止或丢弃当前操作，避免写入另一个会话。
服务地址和密钥仍由用户本地配置，未导入上游私有默认值；新增依赖仅为测试TLS工具。

实际检查：
- 共享主机/协议/来源174项、导出器8项、APK审计4项、原生完整性5项、运行库来源1项，共192项通过；
  其中新增12项直接执行生成的原MiMO协议、原WAV编码和CloudSpeech编排，覆盖撤回/竞态/截断/缓存边界。
- `:android-capabilities:testDebugUnitTest --offline`共134项JVM测试、0失败；新增7项真实TLS/HTTP传输检查，
  覆盖原请求头、SSE、401、重定向、类型/大小限制、取消和新请求隔离。
- 最终HAP 17.701秒；产品/测试包最后联合构建90秒，120任务（16执行/104缓存）。
  398项运行库来源、326项ELF和ZIP16KB静态门禁通过；本阶段未做16KB内核运行验收。
- OPPO PKB110 / Android16 / 4KB：默认8项非会议回归通过；新增3项语音检查通过。
  真实AudioTrack播放头完成48000帧，暂停/继续、取消后拒收旧分片及实际Home退后台阻断请求通过（3.229秒）；
  拒绝远程/系统文件/content缓存来源（0.017秒）；实际ArkTS聊天页面经本机TLS服务收到PCM后、DONE前进入播放，
  DONE后原WAV负载字节一致、状态ready，MediaPlayer本机重播完成且服务请求总数仍为1（7.752秒）。
  使用合成短音和自建会话，没有调用真实MiMO服务或使用私人内容；设置、授权、索引恢复，夹具文件与备份清理并重启应用。
- 同步手册语音入口、配置/云端数据去向、缓存/取消/重试和验收边界；生成与15章/7图/本地链接只读检查通过。
  工作区另有手册阅读器编辑，本阶段只提交语音正文及对应生成HTML的增量。

保留失败记录：首次原生编译被过期前端来源门禁阻止，之后全量准备/构建；
共享宿主曾因OkHttp默认参数构造器类型可见性失败，改为显式无参构造；测试有一次Kotlin空格语法错误已修正。
Windows的localhost解析到其他域导致TLS夹具失败，改用证书SAN匹配的127.0.0.1并禁用测试代理，未关闭证书/主机校验；
首次无界takeRequest等待时，仅核实并停止本仓库的测试worker，随后改为有限等待。
早期ActivityScenario降至CREATED的生命周期夹具异常等待213.787秒，采集本应用栈后才结束，根因尚未证实；
最终采用实际Home操作的有界前后台验证通过，不据此声称旧夹具异常已修复。
前两次页面测试没捕获DONE前播放，诊断仅见已完成缓存；让测试服务按Okio段边界发出首事件并延迟DONE后原断言通过。

真实MiMO服务可用性/音质、真人ASR、多厂商/来电、语音分身动画和既有TalkBack缺口仍待验收，F13不标为完整产品验收通过。
下一项P01/P04受控GUI截图/规划循环；会议语音继续后置，本阶段没有执行会议运行或数值专项。
最终APK SHA256 `2c6a8ce71bf9ca5359cde83cc61b594af1ee3c6bf4dc9ca230c371e06befb5a5`，312,628,469字节。
命令、边界和证据哈希见[聊天语音验证报告](shared-frontend/verification-speech.json)。
鸿蒙源、Compose产品源与会议SDK源码未改；用户README和其他手册阅读器改动保留，未推送或创建PR。

## SH-25：P01/P04首段——本应用截图与单步规划（2026-10-29）

先核对同一锁定上游的MobileGuiTaskTool、MobileGuiTaskWorkflowAdapter和WorkflowRunner：
原完整链路是任务确认、Planner/Decider、截图/动作循环和最终业务结果提取，依赖HDC设备控制。
本阶段只落地Android截图/规划首段，**不把完整P01/P04标为已完成**。
原CloudDeciderPrompt三组提示词继续逐字编译；新增共享GuiPlan负责单步协议、配置/授权/生命周期检查，
不直接编译原HDC执行器，也没有将其受限返回包装为成功。

任务执行页新增“本应用截图与单步规划”入口。共用Kotlin能力通过PixelCopy读取本Activity的ArkUI Surface，
最长边1280、JPEG最多1MB、最多等待5秒；没有SurfaceView时仅回退当前Window，失败不转向其他窗口。
前台/解锁/窗口焦点、FLAG_SECURE、取消和生命周期epoch生效；不增加投屏、无障碍或其他应用控制权限。
共享页保留临时预览，用户逐次确认任务、图片、服务和模型后才向配置的HTTPS Decider发送；
授权撤回、配置变化、后台或页面关闭取消请求并丢弃迟到结果。清除/离页删除预览，意外进程终止可能遗留私有缓存。
规划JSON按原动作空间验证动作名、有限坐标、区域/输入/结束参数及长度，结果只作本页文字建议；
即使模型返回done/success，也不执行点击、滑动、输入、打开应用或写任务成功日志。
原截图历史设置改为说明临时规划预览的独立入口和边界。

实际检查与产物：
- 181项非会议共享主机/来源检查通过，其中新增12项执行实际生成的GuiPlan和页面方法，
  覆盖原提示词/图片请求、严格JSON与坐标、配置/授权/前台变化、确认拒绝、取消时迟到截图和临时文件清理。
  视觉模型端口受控，不据此宣称真实Decider服务或质量通过；会议主机专项未运行。
- `:android-capabilities:testDebugUnitTest`共134项JVM测试、0失败；产品/测试/JVM联合构建221秒、
  125任务（24执行/101缓存）。最后HAP 35.592秒、Android 47秒（93任务，10执行/83缓存），
  测试包98秒（101任务，11执行/90缓存）。398项运行库来源、既有包装完整性及326项ELF/ZIP16KB静态门禁通过。
- OPPO PKB110 / Android16 / 4KB：最终APK默认8项非会议回归、新增截图3项，共11项通过。
  真实ArkUI Surface的JPEG尺寸/体积、非空像素和本机OCR通过（2.467秒），没有导出或上传截图；
  受保护窗口、取消、过期截图无像素及实际Home退后台阻断通过（2.770秒）；
  实际任务入口→共享截图页→临时预览文件→清除通过（3.450秒），云服务授权为false，没有请求真实模型。
  设置恢复，夹具备份/预览文件清理并重启应用；保留首次边界用例失败记录。
- 手册15章/7图、生成HTML/manifest、本地引用检查通过；旧任务截图未替换，图注和来源清单标出新增入口差异。

失败与修正：页面width/height与ArkUI属性同名，改为shotWidth/shotHeight；
Bridge回调的Object注解不兼容可能为null的ResultValue，改用SDK推导类型；
中途修改主机检查输入时，来源封印门禁正确阻止旧构建，之后全量重新准备/构建通过。
Kotlin结果类型与截图引擎同名导致编译失败，结果改名WindowScreenshotResult。
首次边界用例预期裸IllegalStateException，实际onUi通过FutureTask包装为ExecutionException；
产品已正确拒绝后台截图，修正夹具检查cause类型和完整拒绝文本后通过，没有放宽产品限制。

跨应用授权与允许范围、动作执行/逐次确认、截图坐标映射、过期画面保护、规划循环、聊天/工作流GUI接入和真实视觉服务验收仍待做。
下一子阶段继续P01/P04，不能提前将其归入完成项。会议语音继续最后，本阶段未执行会议运行/数值专项或新的16KB内核运行验收。
最终APK SHA256 `027f22a05653500bbd26cdb76b226e3de7e297d364b00d97045f946ec7ad4314`，312,628,469字节。
来源映射、命令、边界和证据哈希见[截图与规划验证报告](shared-frontend/verification-gui-plan.json)。
鸿蒙参考、Compose产品源与会议SDK源码未改；用户shared-frontend/README.md编辑保留，未推送或创建PR。

## SH-26：按次跨应用手动控制代码接入，真实手势验收待续（2026-10-29）

按照用户新要求，将每阶段更新并发送待办清单写入AGENTS.md；MIGRATION_BACKLOG.md新增执行清单，
区分代码接入、受控夹具和完整产品验收。P01/P04仍按子阶段推进，不把本轮未验收功能勾为完成。

共用Kotlin增加GuiControlService、GuiControlSession、GuiControlEntry及系统可打开的原生设置页。
共享ArkTS截图/规划页接权限说明、应用选择、停止与状态入口；服务与设置Activity只在shared-app声明，
共用库仅提供代码/资源。实际处理Compose与共享宿主清单，确认该服务未进入Compose基线。
独立测试辅助模块gui-control-fixture只含SDK Java，无网络权限、主应用/Kotlin依赖和私人数据，
自测自己的进程以保留真实服务宿主；它不是第三个产品前端，也不进入正式应用。

代码范围：
- Android 14及以上窗口截图；用户明确选择一个可启动应用并确认本次会话，最长120秒、最多20次动作，
  不保存允许列表；可见控制面板始终提供停止入口，切换应用、锁屏、服务断开或到期终止后续操作。
- 截图只在本机内存中预览，不写文件或上传；预览点位按原图与FIT_CENTER留白映射。
  点按、上下滑动与返回逐次确认，确认后重新获取窗口图像并检查完整像素哈希，画面变化拒绝执行。
- 核对包名、单应用窗口、主屏、解锁、尺寸、系统栏/刘海、其他遮挡层和控制面板区域，epoch丢弃过期回调。
  没有向共享模型或任意命令开放手势接口；文字输入、横向滑动、模型循环、聊天/工作流与业务结果提取仍未接入。
- 全局手势API的窗口核验/提交存在系统切换竞态；已经提交的80/400毫秒手势可能在停止后完成。
  严格像素检查可能拒绝动态页面。这些边界已写入手册，不能据此宣称完整GUI安全/厂商兼容验收通过。

已实际执行：
- 182项非会议主机检查通过，含13项共享规划/页面方法检查；139项共用库JVM测试、0失败，
  新增5项精确应用范围、锁屏/分屏、120秒/20动作、停止及预览留白坐标测试。
- 最终APK在OPPO PKB110 / Android16 / 4KB重新完成13项JUnit真机检查：默认8项、原本应用截图3项、
  新服务绑定声明1项、服务未连接/取消权限说明不改变设置1项。独立SDK真实跨应用场景0项通过，
  不把普通回归或服务连接冒充真实跨应用手势验收。
- HAP 38.364秒，前期Android构建154秒（93任务，16执行/77缓存）；测试/JVM134秒（106任务，13执行/93缓存）。
  原生设置页与测试包联合构建224秒（124任务，31执行/93缓存）；独立驱动构建128秒（135任务，47执行/88缓存）通过。
  当前代码已编译；最终APK的398项运行库来源、326项ELF及ZIP 16KB静态门禁重新通过；不等于16KB内核运行通过。
- 手册15章/7图、HTML/manifest、本地引用检查通过；保留旧任务图并标明SH-26页面/浮层不在旧图中。

保留问题与证据：首次使用非公开ACCESSIBILITY_DETAILS常量编译失败，改用公开系统无障碍设置入口。
权限负向夹具初次在UiAutomation初始化前记录全局开关，初始化本身改变了它；改为先初始化，再记录/比较后通过。
前三次同进程实测未建立服务连接；只读服务记录显示测试前系统已绑定，但测试启动重启宿主后连接消失。
尝试在宿主就绪后刷新已授权组件仍未解决，故移除该失败夹具的实测方法，改用独立SDK场景，未将失败算作通过。
AGP改写androidTest清单中的附加instrumentation目标；改为单独的testOnly应用模块，并核对编译清单确为自测目标。
测试APK不含Kotlin运行库，因此独立场景与计数/滚动夹具使用纯SDK Java。场景以scenarioPassed和实际断言标记为准，
不生成伪JUnit通过字符串。

用户已明确授权：临时启用ClawMate无障碍服务，只操作自建Fixture，截图不上传，结束恢复权限。
前两次尝试精确恢复原设置；第三次系统移除临时组件后，另行核实本服务不在允许列表、全局开关为0。
没有修改ACCESS_RESTRICTED_SETTINGS AppOp。独立包首次安装出现已核名的“继续安装”，确认后进入OPPO
PictureVerifyActivity图片验证；用户已亲自完成，测试包安装成功，未查看/导出验证码图像或尝试绕过。
独立驱动第4～7次尝试触发120秒上限；第5～7次已观察到真实服务连接，但延迟出现的OPPO
AccessibilityGuideCloseActivity打断选择器，未产生手势通过证据。第8次普通无障碍节点点击未成功确认
“保持开启”，原配置均已精确恢复。移除驱动自动处理OEM提示的代码，改为本人在手机亲自确认；
新的临时会话等待10分钟后未收到完成回复，已按finally精确恢复原配置；另行核实本服务不在允许列表、
全局开关为0。当前不再保留临时授权或等待手机操作，不改ACCESS_RESTRICTED_SETTINGS或系统保护配置。

因此P01/P04第二段仍待验收，不标记功能完成；下一步是完成自建应用上的截图、点按、滑动、取消与Home停止场景，
最终APK普通回归、权限恢复与独立辅助包卸载已完成；继续该实测后再推进模型循环。
最终产品APK SHA-256为`0d539dd57a64c615f20e2c7dac824446d8991e204e9abeceb2230452c45a2a56`。
会议语音继续最后，未运行会议或数值专项。
最新APK和夹具摘要、阶段命令、失败记录和检查边界见[按次控制验证记录](shared-frontend/verification-gui-control.json)。
鸿蒙参考、Compose产品源、会议SDK源码未改；用户README改动保留，没有推送或创建PR。

## UM-02：手册章节导航与 App 接入基础（2026-10-29）

手册由长文锚点增强为首页目录卡片、单章节阅读、独立搜索结果与未知地址恢复页。
新增上一章/下一章、当前章高亮、浏览器前进后退、阅读位置恢复及手机目录展开/收起。
采用离线hash路由，兼容原#home等书签；刷新保持章节，内部返回栈以本次加载为界。
无JavaScript仍退回原长文阅读。所有图片和正文保持本地资源，不引入CDN或模型请求。

navigation.js提供无DOM的版本化路由解析/编码，app-manifest.json由正文源同步生成；
公开ClawMateManual接口支持已知章节ID跳转、首页、状态读取与返回，带host-api.d.ts。
返回先关图再退章，入口返回false交还宿主；未知ID不会导航。APP_INTEGRATION.md列出
可打包资源和共享前端/Android宿主接入边界；此阶段未接入App、未新建Compose帮助实现。

实际检查：
- python docs/user-manual/build.py --check通过：15章、7图、离线引用与生成一致性。
- node --test docs/user-manual/navigation.test.cjs：8项通过；覆盖全部章节往返、旧书签、
  中文/多词/Emoji搜索、无效地址、首尾翻页与目录版本契约。两个JS文件node --check通过。
- 本地HTTP + 内置浏览器：章节/正文交叉跳转、翻页、前进后退、刷新、搜索到章节再返回、
  无结果/清空、多词搜索、未知地址恢复、原图与Esc关闭通过；1280/390宽无水平溢出。
  这些页面交互在中断前已执行；恢复后重新执行主机检查和最终宿主接口检查。
- host-api.test.html实际浏览器8项通过：入口返回、未知ID拒绝、打开指定章、单章与事件、
  原图打开、返回优先关闭原图、返回入口交还宿主、打开首页。实际回执及新版页面预览保存到
  忽略目录.arkui-x-validation/user-manual-routing/。开发测试页不打包进App。
- 同时对本阶段提交快照运行生成检查和8项路由测试，均通过；git差异检查通过。

限制：内置浏览器安全策略拒绝file:协议，未绕过；直接打开本地文件仍待补验。
打印/PDF排版沿用未验收状态。Android内嵌、系统返回/进程重建及TalkBack须移植时实测；
本轮未运行Gradle、HAP或设备测试，不宣称已完成App移植。会议代码/专项未改或运行。

并行语音工作已由独立提交a523eec保存。本阶段在其已提交正文上生成阅读器HTML和目录，
语音功能代码未混入本阶段提交；仅提交手册导航、接入契约、开发检查与本条记录。
最终工作区只剩用户原有shared-frontend/README.md修改，未覆盖该修改。


## SH-26补充：范围失败不可恢复授权（2026-11-02）

GuiControlSession在目标包变化、锁屏或非单窗口核验失败时直接撤销授权，
调用方即便捕获异常，恢复原应用也不能继续消费旧授权。原服务层已有异常停止，
本次将同一约束下沉到授权对象；无新增用户入口，符合手册已有终止规则。
测试分别覆盖锁屏、0/2/3窗口、错误包以及恢复正常条件后仍拒绝执行。

实际执行：`:android-capabilities:testDebugUnitTest --tests com.clawmate.platform.gui.GuiControlSessionTest --offline --console=plain`
通过，XML记录5项、0失败/错误；手册build.py --check通过（15章/7图）；git diff --check通过。
`:shared-app:assembleDebug :gui-control-fixture:assembleDebug --offline --console=plain`
在解析正确的本地meeting SDK参数后通过（39秒，126任务）；先前两次分别因未提供参数、错误SDK根目录失败。
保留已有SDK依赖仅用于宿主构建，未开展会议功能或专项测试。
用户连接OPPO PKB110后adb已识别；APK安装与真实手势验收继续，不能将本次JVM通过当作真机通过。
android-cli技能在声明路径及本地技能搜索中未找到；本轮使用现有Gradle和adb脚本。
用户原有shared-frontend/README.md改动保留，不纳入本次提交。


## SH-26真机续验与启动衔接修正（2026-11-02）

用户完成安装/启动确认与OPPO“保持开启”后继续自建夹具验收。
发现3017296的永久撤销与原启动等待冲突：旧宿主窗口事件会提前撤销授权。
GuiControlService现在只在单一目标应用窗口出现后进入完整窗口校验，保持原5秒等待、
120秒/20动作、锁屏/切换应用停止约束；未扩展模型控制能力。
第二轮已进入真实目标应用并显示控制面板，但驱动未定位计数按钮；给夹具按钮增加
固定contentDescription，避免主题文字转换影响精确匹配。第三轮被OEM提示打断，
第四轮仍在选择器阶段停滞，均主动force-stop测试驱动，不能将相应Process crashed回执
当作已确证的产品崩溃。跨应用截图映射/点按/滑动/取消/Home停止仍未通过。

实际检查：共享APK与5项GuiControlSession JVM检查通过（65秒、98任务）；
夹具构建通过（5秒、33任务）；androidTest构建通过（23秒、101任务）。
最终APK在PKB110/4KB完成13项非会议JUnit真机回归：基础6、聊天1、工作流1、
本应用截图3、服务声明1、未授权/取消设置1。未运行会议专项或真实模型质量验收。
手册build.py --check通过15章/7图，git diff --check通过。无新增用户流程或按钮，
沿用手册既有启动与停止说明。四轮finally日志均记录精确恢复临时无障碍设置；
最终辅助夹具卸载成功，另行核对服务列表为空、全局开关0。
证据摘要和APK哈希见shared-frontend/verification-gui-control-followup.json。
保留用户README修改；下次先定位独立驱动选择器停滞，再继续真实手势验收，不提前推进模型循环。


## SH-26受控跨应用手势验收通过（2026-11-02）

本轮8次诊断/修正后，最终在OPPO PKB110 / Android16 / 4KB上通过完整独立夹具：
真实系统服务绑定、仅选自建应用、窗口截图预览、预览映射点按使计数0→1、
取消滑动保持状态、确认滑动产生非零滚动、Home离开后控制面板消失。
驱动只对宿主控件用节点点击，目标计数和滚动由真实服务dispatchGesture产生；
预览选点仍用实际触屏输入，不直接调用产品方法、不伪造业务成功。

修复GuiControlService的自有确认框关闭竞态：根节点消失时，原包名核验把自身
无障碍浮层误判为外部遮挡。新增GuiOverlayOwnership，只记住本会话已观察到本包
归属的无障碍浮层ID；未知ID仍拒绝、明确异包即撤销、窗口消失和会话结束清空。
用同一窗口快照完成校验；原单应用/解锁/遮挡/像素哈希/时间和次数限制保留。
新增2项JVM测试验证归属消失、异包、未知ID、窗口回收和会话清理。

驱动递归取子节点曾停在AccessibilityInteractionClient/Parcel调用中；记录线程栈后
改为应用侧文字查询、范围核验的节点点击，降低轮询频率，并等待启动和预览动画。
诊断捕获1076×2366窗口与1080×2374截图差异；等待动画后原严格尺寸门禁通过，
未增加比例猜测或放宽门禁。另捕获确认框关闭时type4/root-null，修复后场景通过。
所有失败与主动终止保留，不宣称单凭调用栈已解决全部OEM/Binder根因。

实际命令与结果：
- :gui-control-fixture:assembleDebug --offline --console=plain 多次构建成功（最终2秒/33任务）。
- :shared-app:assembleDebug :android-capabilities:testDebugUnitTest --tests com.clawmate.platform.gui.*
  --offline --console=plain 成功（50秒/98任务），XML共7项、0失败/错误。
- 独立am instrument带guiControlGranted=true，最终scenarioPassed=true，退出INSTRUMENTATION_CODE=-1。
- 最终APK普通真机回归13项通过（默认8、本应用截图3、声明1、未授权/取消1），未运行会议专项。
- 中文手册更新单设备受控场景验收边界，保留旧图来源；build.py生成及--check通过15章/7图，
  8项导航测试通过；首次中文stdin编辑因编码失败，改为显式UTF-8文件写入后生成检查通过。
- 临时权限finally恢复；最终列表为空、全局开关0；辅助夹具卸载成功。未修改系统保护策略。

摘要、APK哈希、诊断和最终回执哈希见shared-frontend/verification-gui-control-accepted.json。
本阶段仅单设备受控手动链路通过，多厂商、真实业务、模型循环/聊天工作流接入/结果提取仍待完成。
README用户改动保留，未触碰鸿蒙参考与Compose源，会议保持最后阶段。

## 日历双来源、首页“为你提醒”、交大 jAccount 授权与本地模型联网下载（2026-11-04）

按用户要求完成三项功能：日历来源改为“手机系统日历 + 常用节假日”并显示在首页“为你提醒”；
交大邮箱支持 jAccount 授权登录；安卓应用支持联网下载模型到本地。
参考仓库 doulujiyao12/mobiinfra-oh@coevomind-app 只读调研结论：该分支只有系统日历一种来源、
没有节假日来源、没有 jAccount/OAuth 邮箱登录；本地模型下载协议（repo/files + resolve/master + Range）
与完成标记可直接移植。节假日与 jAccount 为按用户需求新设计，设计说明见
docs/CALENDAR_MAIL_MODEL_DOWNLOAD.md。

代码接入（已完成集成，不代表真实服务验收）：
- Kotlin：新增 ChinaHolidays（2025/2026 放假与调休，未覆盖年份只显示周末并提示）、JAccountOAuth、
  JAccountCallback；PersonalSourceEngine 增加 holidayEnabled、source 标注、calendarStatus、
  holiday 只读操作、{"quick":true} 首页浏览与 Bearer 登录；ImapTransport 增加 RFC 7628 OAUTHBEARER；
  MailOAuth 增加授权码流（state 校验、密钥仅内存、令牌进程内）；PlatformBridge 接
  sourcesJAccountStart/Link/HolidayInfo 与 localModel* 端口；SharedActivity 处理 clawmate://jaccount。
- 模型下载：app/localmodel/download 新增 MiniJson、ModelScopeSpec、ModelScopeDownloader
  （清单解析、路径安全、Range 续传、大小+SHA256 校验、暂停/取消、剩余空间检查、进度与速度）；
  LocalModelManager（切换/删除，路径校验与二次确认）；LocalModelPlan（只读计划说明）。
- ArkTS：新增 CalendarSourcesPage、MailSourcesPage（拆分原合并页）、CalendarReminderCard（首页卡片）、
  LocalModelAccess；PersonalSources/SourceItem 增加 source，SourceSnapshot 来源区分；
  HomePage 在“为你提醒”下插入日历卡片；SettingsCenterPage 模型下载按钮改为“下载/暂停/继续”切换
  （原页面下载中禁用按钮，暂停后无法继续）；app/Index.ets 接入下载进度轮询、模型列表、切换、删除。

验证（实际运行）：
- `./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest --offline` 通过：
  capabilities 160 项 / 0 失败（含 ChinaHolidaysTest 5 项、JAccountOAuthTest 9 项），
  app 1267 项 / 0 失败（含 ModelScopeSpecTest 15 项、ModelScopeDownloaderTest 9 项）。
- ArkTS 编译（hvigor assembleHap）成功；package-android.py 导出运行库 129 个、build-state seal 通过。
- Node 24 运行 verify.mjs 6 项、verify-native.mjs 7 项、verify-integration.mjs 12 项、
  verify-bootstrap.mjs 14 项、verify-personal-sources.mjs 3 项全部通过。
  其中 verify.mjs 原写死“13 条路由”改为逐条校验声明路由（不降低检查强度）；verify-personal-sources.mjs
  改为先去掉 UTF-8 BOM 再剥离 import（PersonalSources.ets 曾被 Windows 编辑器写成带 BOM）。
- 用真实编译产物（android-capabilities classes + org.json）运行探针：2026-10-01..11 区间得到
  “国庆节放假×7、调休上班(10-10)、周六休息(10-11)”；jAccount 账号归一化、HTTPS 回调校验、
  授权地址参数、Bearer 载荷格式均符合预期。

未完成/待验收：
- 共享前端 Gradle 宿主（:shared-app）本机无法构建：需要 on-device-meeting-notes 的 android/meeting-sdk，
  当前机器上没有该目录（settings.gradle.kts 按设计拒绝）；本次只验证到 ArkTS 编译与运行库导出，
  未生成/安装新 APK，也**未在 OPPO 实机浏览新页面**。
- 真实 jAccount 应用注册（clientId/密钥/登记网址）与交大邮箱 OAUTHBEARER 可用性未验证。
- 真实日历授权/日程读取与首页卡片在设备上的显示未验证；节假日表每年需要随官方通知更新。
- 模型下载：未在真机完成一次真实模型下载（体积 2.7–3.5 GB）；未做省电/中断/掉网的真机长跑。
- MNN/HiAI/OM 原生推理仍未接入，下载到的模型不会产生任何输出。
## 跳过会议模块后的安卓实机验证（2026-11-05）

为在不准备 on-device-meeting-notes 的前提下产出可装 APK，在忽略目录
`.arkui-x-validation/meeting-sdk-stub/` 建了一个**临时会议 SDK 桩**：只镜像 MeetingBridge 引用的
类型与签名（`MeetingOperation.HANDOFF`、`engine.phase.name`、`MeetingHandoff.parse().toJson()`、
`stop/pause/resumeRecording` 等），行为一律明确失败（不启动 Python/ONNX、不开端口、不录音）。
产品代码未为桩做任何让步（MeetingBridge 保持原样）。该桩与构建脚本不进 Git，也不代表会议功能完成。

同时按仓库约定把模型下载代码从 `:app` 移到 `:android-capabilities`
（`com.clawmate.platform.localmodel[.download]`），因为 `:android-capabilities` 不能反向依赖 `:app`，
而共享宿主需要复用这套能力；`LocalModelManager` 改为自包含扫描，不依赖 Compose 基线的 inventory。

实机结果（OPPO PKB110 / Android 16）：
- 日历：进入“汇总 → 日历分析”，两个来源开关渲染正确（系统日历默认关、常用节假日默认开）。
  勾选 `local account` 后自动开启系统日历来源并保存（prefs 中 calendarEnabled=true、selected=[1]）。
  点“读取并同步日历记忆”：先得到“已同步 28 项（系统日历 0 项、常用节假日 28 项）”——
  设备自带日历当时没有任何日程，属真实结果而非失败；随后用 shell 插入一条测试日程
  （calendar_id=1，2026-11-06 09:00–10:00，验收后已删除），再次同步得到
  “已同步 29 项（系统日历 1 项、常用节假日 28 项）”，快照 JSON 中 items 带 source=system/holiday、
  sources 含 -1:常用节假日:holiday 与 5 个系统日历。
- 首页“为你提醒”：卡片同时显示
  “明天 11月6日 · 09:00–10:00｜系统日历｜ClawMateTestEvent”与
  “后天 11月7日 · 全天｜常用节假日｜周末休息”，下方“日历记忆同步于 …”与刷新/去设置入口。
- 实机发现并修复两个真实缺陷：
  1) 全天日期用 `toISOString()` 格式化，在东八区把 10-31 显示成 10-30（与后文日期矛盾）——
     改为按本机时区拼 YYYY-MM-DD；日历页与邮箱页同步修正。
  2) 两个来源开关放在 `@Builder SwitchRow(…, on: boolean, …)` 里，ArkUI 按值参数冻结导致保存后
     开关仍显示旧状态——把开关内联进页面，直接读 `this.options.*`；删除不再使用的 builder。
- 邮箱：进入“汇总 → 邮箱分析”，点“使用jAccount”切到 jAccount 模式，页面正确渲染
  `jAccount账号 / IMAP加密收信服务器(mail.sjtu.edu.cn:993) / jAccount客户端ID / jAccount回调网址 /
  jAccount密钥` 与“需先申请 jAccount 接口”的说明。空表单点“启动jAccount授权”得到明确失败：
  “授权未完成…请填写 jAccount 接口申请时分配的客户端ID”，没有伪造授权成功。
- 模型下载：设置 → 模型与智能体 → 本地模型 → 本地模型下载，默认仓库
  `fengerhu1/mobi_2B_offline_npu2_kirin9030_online_v2` 真实下载完成：29 个文件、
  3,279,889,376 字节（界面显示 3.1 GB、约 26 MB/s），`vision/` 子目录按规则跳过，
  `.download_complete` 与 `.download_manifest.json` 已写入；列表显示
  “下载完成 · 3.1 GB”并可“切换/删除”，切换后选择写入 `files/models/selected.json`；
  页面仍明确显示“安卓本地推理尚未接入”。
- Deep Link：`am start -a android.intent.action.VIEW -d clawmate://jaccount?...` 能解析到
  `com.clawmate.shared/.SharedActivity` 并拉起应用（Manifest intent-filter 生效）。

仍未验收：
- 未用真实 jAccount 应用（clientId/密钥/登记网址）完成授权码→令牌→OAUTHBEARER 登录的端到端；
  设备上只验证到“页面渲染 + 参数校验 + Deep Link 能被系统分发”，回调后的 state 校验与令牌兑换
  只有 JVM 单元测试覆盖。
- 未验证交大邮箱服务器是否开放 OAUTHBEARER。
- 会议模块仍是桩：会议页会显示“当前构建未包含会议模块”，会议语音留待最后阶段。
- 模型下载的长跑（掉网/省电/中断续传）未专项测试；本次为一次连续 3 GB 下载。

本轮检查：`:android-capabilities:testDebugUnitTest :app:testDebugUnitTest` 全绿
（184 项 + 1243 项，0 失败）；ArkTS hvigor 编译、package-android、build-state seal 通过；
APK 构建并安装到 PKB110，上述页面均在实机浏览。
## 日历来源整合、页首月视图、交大邮箱账号密码登录（2026-11-05）

按用户要求做三项调整，并在 OPPO PKB110 上逐项验证。

日历来源整合与中文命名（Kotlin `CalendarSourceLabels`）：
- 原来直接列出 6 个系统日历（`local account`、`birthday@localhost`、`oplus_new_birthday_default_calendar`、
  `oplus_anniversary_default_calendar`、`oplus_countdown_default_calendar` 等包名），现在按规则归成
  「本机日历 / 生日 / 纪念日 / 倒计时和订阅 / 常用节假日」，界面只显示中文名，具体日历放在“包含：…”小字里。
- 每类一个开关；`disabledGroups` 持久化停用类别，`effectiveIds()` 只返回“已勾选且类别启用”的日历，
  同步与首页快速浏览都走这一层，避免关掉的类别仍被读取。
- 实机：四个分类开关全部渲染；关闭“生日”后 prefs 变为 `disabledGroups=birthday`、`selected` 去掉 2/3；
  再打开恢复为 `disabledGroups` 为空、`selected=[1,4,5,2,3]`，列表消失/恢复的问题已修复。

页首月视图（页面内建，避免 @Builder/@Prop 按值参数冻结）：
- 月视图可翻月、回合今天；日期格子用蓝/橙小圆点标记“有日程/有节假日”；
  点日期在卡片内展开当天条目（来源、时间、标题），“收起/展开”可折叠。
- 实机：11 月网格中 11-07/08、11-14/15、11-21/22 等日期带节假日标记（共 11 个日期）；
  点 11-08 展开得到“常用节假日 · 全天 · 周末休息”。底部“日历记忆”改为只列所选日期。

交大邮箱改为 jAccount 账号 + 密码（默认）：
- 预设「上海交大邮箱」= jAccount 账号 + 密码，IMAP `mail.sjtu.edu.cn:993` 走标准 LOGIN；
  账号允许只填 `abc`，保存前经 `sourcesJAccountUser` 归一化成 `abc@sjtu.edu.cn`。
- OAuth 授权码流程保留为「上海交大邮箱（jAccount授权·高级）」，需要先申请 jAccount 接口。
- 实机：切换预设后字段标题正确变为“jAccount账号 / jAccount密码”（修复了切换预设后标题不刷新的问题）；
  保存后本机记录 `user=clawmtest@sjtu.edu.cn`、密码为 Keystore 加密串；读取时服务器返回
  `* OK IMAP4rev1 proxy server ready`，`LOGIN` 得到 `A1 NO LOGIN failed`（测试账号不存在），
  证明账号密码链路可用；测试凭据已清除。

本轮检查：`:android-capabilities:testDebugUnitTest` 195 项 / 0 失败（新增 CalendarSourceLabelsTest 5 项、
CalendarMonthViewTest 6 项）；ArkTS 编译、package-android、seal 通过；APK 构建并安装到 PKB110 逐页验证。

仍未验收：真实 jAccount 账号的收信、真实日程（本机日历为空）与跨月日程显示、会议模块（仍是临时桩）。
## 邮件读取回归修复、周末条目移除与日历黄历边界（2026-11-05 下午）

用户用真实 jAccount 账号验收时报告“读取失败”，并指出日历里读不到节气/记者节/寒衣节、
周末条目多余。逐项定位与修复如下。

**1. 邮箱读取失败（真实原因：Java 正则语法错误）**
`ImapTransport.readFrame` 里字面量标记的正则写成 `"\\{(\\d+)\\+?}\\r\\n$"`，右花括号未转义，
Java 抛 `PatternSyntaxException`：**任何一次 IMAP 读取都会失败**，与账号密码无关。
修复为 companion 中的预编译 `literalPattern`（`\\{(\\d+)\\+?\\}\\r\\n$`），
并补回归测试 `ImapTransportTest.literalPatternIsValidAndMatchesBothForms`。
同时把来源读取的通用错误“读取失败或已取消…”改为回传真实原因（取消时仍显示“读取已取消”），
否则这类缺陷永远看不到。

**2. 邮件中文乱码（两个独立缺陷）**
- `mailHeader` 的 RFC 2047 正则把 `GB2312?B` 整个当成字符集名，解码必然失败；
  修复为 `charset.split('*')[0]`，并按“头部块”解析（折叠行并入前一个头部）。
- **ArkUI-X 的 `TextDecoder('gb18030')` 不报错，而是按 Latin-1 逐字节解码**（设备实测：
  对同一串字节 `gb18030` 与 `utf-8` 两种结果都只是乱码）。改为应用自带码表：
  `shared-frontend/adapters/utils/mail/Gb18030Table.ets`（按 WHATWG index-gb18030.txt 生成，
  23940 项，二分查找），并在无字符集声明时按“UTF-8 是否解出合理中文”探测。
- 实机结果：标题「关于组织参加中国电科“网信杯”人工智能创新大赛的通知」/「人工智能学院2026年度
  研究生学业奖学金评定工作通知」，发件人「王鑫」「艾茹洁」，正文中文全部正常；

**3. 交大邮箱真实收信验收**
用用户真实 jAccount 账号保存后读取：**成功读取近三天 2 封邮件**，正文完整，未下载附件。
密码用 Keystore 加密保存在本机；排障时用过的临时密码环境变量已清除。

**4. 移除周末条目**
`ChinaHolidays.entries()` 不再为常规周末生成“周末休息/周六休息”条目，只保留法定节假日与
调休上班日。实机同步结果从 28 项降为 **12 项（全部为真实节假日/调休）**，
首页“为你提醒”不再出现周末。单测更新为 `weekendsAreNotRecordedAsHolidayEntries`。

**5. 节气/记者节/寒衣节读不到（能力边界，已核实）**
设备实测：`CalendarContract` 与 `content://com.coloros.calendar/events` 均 0 行记录；
OPPO 日历里的节气、记者节、寒衣节只存在于 `com.coloros.calendar` 自己的黄历面板与私有数据中，
不写入 CalendarProvider，第三方应用没有读取入口。应用不伪造这类内容，已在文档与用户手册中写明。

本轮检查：`:android-capabilities:testDebugUnitTest` **196 项 / 0 失败**；ArkTS 编译、package-android、
seal 通过；APK 安装到 PKB110 并在真实账号下验证收信、标题/正文中文、周末条目移除。
## 内置节日与二十四节气来源（2026-11-05 晚）

用户要求补上节日与节气表并配开关。新增两个内置来源，均可在日历分析页独立开关。

- **节日**（`Festivals.kt`，id = -2）：中国传统节日 10 项（春节、元宵、龙抬头、七夕、中元、中秋、
  重阳、腊八、小年、除夕）、西方节日 8 项、国际日与纪念日 20 项。浮动节日按“第几个星期几”算，
  农历节日按农历换算。删除了“第几个周末”这类不稳定日期，避免与放假安排冲突。
- **二十四节气**（`SolarTerms.kt`，id = -3）：VSOP87 截断版太阳黄经算法，求 15° 整数倍时刻后取本地日期，
  覆盖 1900—2099 年。已用权威日期校验：2026 立春 2-4、春分 3-20、清明 4-5、夏至 6-21、立秋 8-7、
  秋分 9-23、立冬 11-7；2026 冬至落在北京时间 12-22 04:49，因此记为 12-22（不是想当然的 21 日）。
- **农历**（`LunarCalendar.kt`）：编码表由权威实现逐年生成并回环校验，1900—2099 年“正月初一”零差异；
  除夕按该农历年腊月实际天数取最后一天（2025 农历年腊月 29 天 → 除夕 2026-02-16）。

开关与持久化：`festivalEnabled`、`solarTermEnabled`、`disabledFestivalCategories`
（chinese/western/international，至少保留一类）。同步、首页快速浏览、来源列表都按开关过滤。

实机（OPPO PKB110）验证：
- 日历分析页 11 个开关全部渲染：系统日历 / 常用节假日 / 节日 / 二十四节气 + 三个节日类别 + 四个系统日历类别。
- 同步结果 `已同步 33 项（常用节假日 12 项、节日 13 项、二十四节气 8 项）`。
- 关“二十四节气”→ 25 项；再关“西方节日”→ 23 项（节日 11 项）；恢复后回到 33 项，prefs 与界面一致。
- 月视图标记正确：10-31 万圣节前夜、11-07 立冬、11-22 小雪、11-26 感恩节，与 OPPO 黄历的立冬日期一致。
- 首页“为你提醒”显示“后天 11月7日 · 全天 · 立冬”。

本轮踩到并修复的三个真实缺陷：
1. **自递归**：批量替换 `this.options.festivalEnabled` 时把辅助函数自身的实现也改成了递归调用
   （`return this.festivalEnabled() !== false`），导致 `RangeError` 与 `libc SIGSEGV`——页面空白、进程崩溃。
   教训：整文件字符串替换后必须回读被替换的定义本身。
2. **ArkUI builder 不支持 `if / else if` 链**：来源列表加分支后整页渲染失败，改为嵌套 if。
3. **`@Builder` 值参数冻结**（本仓库第四次遇到）：月视图单元格的 cell 快照不随数据更新，
   改为只传日期字符串、在 builder 内用 `this.cellFor(date)` 现算。

检查：`:android-capabilities` **213 项 / 0 失败**（新增 SolarTermsTest 5、LunarCalendarTest 5、
FestivalsTest 7）；`:app` 1243 项 / 0 失败；ArkTS 编译、package-android、seal 通过；APK 已装到 PKB110。
---

## Codex 移植到 Android（2026-11-06 收尾）

### 范围

C1（`CodexBackend` 与传输层）、C0-b（六 Tab）、C2（Codex Tab UI + 选图/拍照/FileProvider）、
C3（聊天委托 `run_codex_task`）。扫码按 Android 平台事实降级，不是遗漏。

### 真机验收（PKB110）

主链路全部走通：配对（含 CA 钉扎）→ Workspace → 下发任务 → 回复 →
交付文件下载 → 审批（拒绝与批准）→ 选图附件 → 拍照附件 → 已保存连接重连 →
聊天委托（模型自行调用 `run_codex_task`，含离线降级）。

逐项证据见 [docs/CODEX_DEVICE_VERIFICATION.md](docs/CODEX_DEVICE_VERIFICATION.md)。

### 本轮修掉的真实缺陷（10 个，单测全部漏掉）

1. 下发请求体缺 `attachments` —— 每个真实任务都会被拒。
2. 手动设 `Accept-Encoding` 绕过 OkHttp 解压 —— 大于等于 1024 字节的响应全部解析失败。
3. `{"unchanged":true}` 被当成错误 —— 长轮询超过服务端窗口即中断。
4. `codexRunTask` 在桥接线程 `runBlocking` —— 委托必然触发 60 秒上限。
5. 选择器阻塞 300 秒 —— 用户在相机多停留片刻即超时。
6. 多处超时超过桥接上限。
7. 对话框被外部关闭后 `busy` 不复位 —— 整页失效。
8. `CodexHelpPage` 未注册 —— “连接指南”必然失败（只被 `router.pushUrl` 引用的页面不在 import 遍历内）。
9. 桥接重新序列化未设 `encodeDefaults` —— `canAccept` 变 `undefined`，**手机永远无法批准审批**。
10. 相机未 `createNewFile()` —— `CaptureProvider` 要求文件已存在，**拍照静默失败**。

### 一个需要记下的教训

此前多轮认定“`adb reverse` 数据通道不通”，**结论是错的**。真实原因是自签证书缺少信任锚。
`toybox nc` 不做 TLS 握手，看起来像“数据不转发”，把排查引向了网络层。
后来在传输层加一条诊断日志（记录 `IOException` 的类名），一秒就给出了答案。
**过时的结论比没有结论更危险**，它会让后来的人跳过正确的路径。

### 已知限制（如实保留）

- Android **无扫码**：系统无扫码 intent，内置扫码器需额外相机权限；配对走导入文件/粘贴。
- 真实 Codex CLI 的推理质量、执行中真正中断、官方 App Server 与替身的细节差异未验证。
- 上游 17 个漂移文件仍钉在 `cd69b68`。

### 检查

`:android-capabilities` **293 项 / 0 失败**（含 14 项 live 测试，未提供桥接时自动跳过）；
`shared-frontend/verify-codex-tool.mjs` 18 项通过；APK 已装到 PKB110，六 Tab 正常、无 FATAL。

## Codex 官方 App Server 真机复测（2026-11-07）

- 用 USB 在 PKB110 复测已安装的共享版 APK。官方 App Server 跑通真实文本回复、图片识别、执行中断、文件下载和系统导出。保存的连接在重启后可用设备令牌重连。
- 正式配对在签发设备令牌后收到 401。隔离测试桥接临时接受同一测试码后继续验证，产品配对缺陷仍待修复。
- 同时复现文档选择后读取失败（配对 JSON、普通文件附件）、任务增量未刷新终态、审批「拒绝」无回调。审批「仅允许本次」用受控替身验证请求返回 200。
- `node shared-frontend/verify-codex-tool.mjs` 18 项通过。另用主机探针复现增量合并丢失和拒绝回调缺失。
  本轮沿用设备现有 APK，细节及未覆盖范围见 [Codex 真机复测](docs/CODEX_DEVICE_VERIFICATION.md#2026-11-07-usb-真机复测)。
