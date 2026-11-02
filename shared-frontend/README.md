# ClawMate 共享前端

当前 Android 宿主是 `../shared-app/`，包名 `com.clawmate.shared`，显示名称
“ClawMate 共享前端”。`../app/` 原 Compose 产品保留；本阶段不替换其包名，不搬动其私有数据。

## 源码与职责

以鸿蒙提交 `cd69b68bf16b9ed352be5073939eb24d042de583` 为唯一参考版本。
`app/Index.ets` 负责安卓应用组装、导航和服务连接，页面与绝大部分业务代码直接从原源码生成。
`adapters/` 只提供平台接口或明确的能力限制；`prepare.mjs` 保存少量可审查的源码适配。
没有修改鸿蒙源项目，也没有用截图或 Compose 重新描绘这些页面。

SH-15 页面/业务准备清单包含144个ArkTS文件：76个与锁定上游逐字一致、37个经适配、31个为新平台代码；另有作者模板中的启动Ability。
这是准备清单的统计，不是整个鸿蒙产品的复用率。

- `upstream-lock.json` 与 `../experiments/arkui-x/ui-upstream-lock.json` 锁定全部输入，准备时逐文件检查 Git blob。
- 编译输入在 `.arkui-x-validation/reference/`；生成工程在 `.arkui-x-validation/application/project/`。
- SDK、运行库、APK、截图和测试产生的数据均忽略。原端带疑似凭据的两个默认配置文件没有导入，
  由生成的空配置替代；服务地址、模型名、密钥均由用户填写。
- Kotlin 宿主提供 Android 系统栏、焦点、键盘、剪贴板、浏览器及本地 OCR/TTS 桥接，
  OCR 和 TTS 的实际引擎与旧版共用 `:android-capabilities`，不依赖某手机厂商 SDK。
  当前调试包仅包含 arm64，最低 Android API 26；已在 OPPO PKB110 / Android 16 / OPlus V16.1.0 检查。

## 功能映射

| 功能 | 共享来源 | 当前连接与边界 |
| --- | --- | --- |
| 启动、四页引导 | FirstLaunchOnboarding / OnboardingTypes | 原始页面；完成版本实际持久化，更新与重启不重复引导 |
| 主导航、主题 | MainTabConfig / FloatingMainTabs / AppTheme / FrostedPage | 原五入口及导航样式，悬浮胶囊尺寸取锁定源码；原颜色资源跟随系统主题。Android 基础模糊近似 HDS，系统栏由宿主管理 |
| 首页、提醒、推荐、关怀 | HomePage / HomeTaskSuggestion / HomeEmotionalSupport | 原页面；真实数据管理器；建议/关怀接原提示词与响应解析，结果缓存到本机；真实模型未验收 |
| 聊天、会话、消息 | ChatPage / ChatMessageList / ChatSessionDrawer / ChatSessionStorage | 流式回复、思考参数、文件保存、会话管理；只读 query_events 工具往返已接通，最多3次查询；不执行任意模型工具 |
| 图片聊天 | ChatPage / ChatImages / 共用 AndroidChatImagePreparer | 相册/拍照、逐次确认、最多4张去元数据 JPEG、当前回合多模态 SSE、私有历史图片；真实视觉模型与相机实拍未验收 |
| 上下文预算与自动摘要 | ChatContextBudget / 原 Index 压缩编排 / ChatContext | 阈值与保留轮数生效；摘要、增量覆盖、会话重载复用及本地抽取回退已接入。完整原文保留；取消/撤回阻断迟到结果，仍超预算时明确报错；真实模型摘要质量待验收 |
| 汇总、事件详情 | CollectionPage / CollectionGroupDetailPage | 读取真实索引，支持原详情与分页；同步调用原数据管线，空数据不伪造同步时间 |
| 图库、筛选、检索、问答 | GalleryPage / GalleryStorage / GallerySearchService / GalleryAnalyzer | 原页面与存储/云协议；系统选图可打开和取消。OCR 经 URI 桥接到共用本地引擎，失败明确返回；未上传私人照片或做真实模型分析；HDC 批量读图受限 |
| 图库批次暂停/恢复 | 原GalleryScanSession / GalleryWorkQueue / GalleryScanCheckpoint | 最多9张显式选图的私有JPEG快照，原子进度、内容校验、暂停派发与继续、遗留运行态恢复为暂停；日期记为导入日，不自动上传，不枚举全相册 |
| 数据管理 | DataManagerFacade / ChatMemorySync / IndexService / MemorySyncPage | 显式近7天范围确认、保存的模型名、进度/取消、检查点去重；本地索引重建不发云请求。共享文件独立，未导入旧应用私有数据 |
| 任务与工作流 | TaskPage / WorkflowStorage / AndroidWorkflow / 共用 WorkflowRunnerImpl | 原图/JSON与文件管理；已接有界等待/条件/循环/echo/summary、单任务/批量停止、真实结果/日志；GUI和其他模型工具仍受限。任务开关控制批量候选，不自行后台执行 |
| 工作流视觉问答 | 原ToolEditor / WorkflowVlm / 共用可选模型接口 | 本机图片导入、保存的Decider视觉模型、逐次发送确认、取消/超时、步骤输出与成功摘要；GUI截图仍阻断，真实模型质量与完整schema验证待验收 |
| 每日采集提醒 | CollectionReminderPanel / 原策略 / Kotlin CollectionReminderEngine | 实际通知权限、每日近似闹钟、改时/撤销及通知跳转；前台解锁后再次确认才执行任务。原时间迁移一次；真实非空摘要保存后更新采集时间；省电送达和多厂商待验收 |
| 设置与服务 | SettingsCenterPage / SettingsCenterControls | 原分组和表单；私有偏好持久化，写失败保留可重试状态；空服务显示“未配置”；用户主动测试模型时才请求配置的服务 |
| 账号与隐私 | PrivacyConsent / PrivacyConsentDialog | 安卓授权指使用自配云服务，不代表华为账号登录；声明按实际行为改写并单独版本化；拒绝/撤回会阻断请求，小艺 A2A 明确不可用 |
| 数字分身 | HomePage / AvatarService / SeedreamAvatarProvider | 原生成/历史/参数快照界面及文生图服务连接；真实生成待验收。照片参考上传、人物前景分割未验收/未接入，不伪造成功 |
| 语音、本地模型 | 原语音控件 / 共用 Android ASR、TTS | TTS真实回调、暂停/文本重播；ASR连接系统服务并按需申请麦克风权限，没有伪造转录。真人识别质量未验收；MNN/HiAI/OM未接入 |
| HDC/GUI、Python | 平台接口与能力说明页 | 跨应用操作、外部Python服务和微信自动采集仍受限；提醒不自动启动这些能力 |
| 桌面端、诊断、日志 | Android 平台页 / 原工作流查看页 | 用户自行填写 HTTPS 桌面下载地址；原生诊断受限；运行日志只展示实际内存日志 |

## 构建

使用锁定 API20 工具链和本目录 `template/` 的作者模板；不再依赖旧实验生成工程。
初次输入导入、四个工具路径参数和干净源码重建方法见[环境引导](../docs/SHARED_BOOTSTRAP.md)。
`bootstrap.mjs` 只复制清单内120个源文件和59个资源，逐文件验证固定提交的Git blob；
工具版本不匹配、参考输入漂移或目标已有不同文件会报错。它不下载SDK或复制源端配置/私有数据。

从仓库根目录运行，路径只作为本机参数，不写入应用配置：

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK 17>' -AndroidSdk '<Android SDK>' -MeetingSdkRoot '<on-device-meeting-notes>' -Offline
```

流程：验证源文件哈希 → 生成共享 ArkTS 工程 → Hvigor assembleHap → 导出匹配 SDK 运行库
→ Gradle Android assembleDebug。任一步失败立即停止，不使用上一次 APK 冒充新构建。

统一构建的产物：`shared-app/build/outputs/apk/debug/shared-app-debug.apk`。
SH-01 独立工程的历史文件名为 `ClawMateShared-debug.apk`，构建时应使用根工程的最新产物。
调试包使用全量 SDK 插件定位兼容性，尚未进行发布签名、依赖裁剪、大小/性能或发布 SDK 等级验收。

## 检查与实际证据

使用 Node 24 从仓库根运行：

```powershell
node shared-frontend/verify.mjs
node shared-frontend/verify-native.mjs
node shared-frontend/verify-integration.mjs
node shared-frontend/verify-reminders.mjs
node shared-frontend/verify-workflow-vlm.mjs
node shared-frontend/verify-gallery-scan.mjs
node shared-frontend/verify-bootstrap.mjs
python -B shared-frontend/verify-runtime-export.py
node experiments/arkui-x/verify-contracts.mjs --source-root .arkui-x-validation/reference
./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest
```

第一条检查输入锁、十个路由、设置读写/重启语义、失败后的保存重试和上下文预算边界；
第二条检查跨运行时协议、取消及源导航样式；第三条检查记忆/工具/图片/工作流整合及非流式取消；第四条重跑七个原始模块的八项主机契约。
Gradle 检查共用能力和 Compose 基线。主机检查不代替 ArkTS 编译或 Android 真机检查。

设备检查包括：四页引导与完成状态、五个主入口、图库路由及授权拦截、系统选图打开/取消、
授权同意/撤回、设置写入后进程重启恢复、工作流图新建/保存/复制/删除/文件预览、
未保存编辑的返回确认、文件数量实时刷新、会话新建/置顶/重命名/重启恢复/删除。
只操作独立共享包；测试任务、会话和 `.invalid` 测试下载地址已清理，授权恢复关闭。

迁移进度、验证结果和修正统一见 [迁移日志](../MIGRATION_STATUS.md)。
精确证据保留在本目录的 `verification-*.json`；原始日志、截图和XML在忽略目录。

两版职责与数据归属见 `../docs/SHARED_ARCHITECTURE.md`，页面对齐边界见 `../docs/FRONTEND_PARITY.md`。

## 继续跟随上游

准备脚本现在会在文本适配失效时停止，并清理上一轮生成的旧 overlay。
构建会记录输入与运行库摘要；直接运行 Android Gradle 也会检查是否需要重新编译共享前端。
维护检查、16 KB/包体审计和生产验收条件见[长期维护评估](../docs/SHARED_FRONTEND_MAINTENANCE.md)。

保持上游只读。新版本需更新锁定提交与文件哈希，重新审查平台适配、确认完整依赖闭包，
然后执行 prepare、主机检查、构建和真机回归。不要通过关闭编译检查或直接改参考输入解决漂移。
Android 厂商差异只应进入平台接口；其他系统应实现自己的接口并单独构建/验收。

共享版已包含应用组装和主要本地交互；真实云服务/图片服务、摘要质量、原生集成与旧数据迁移
仍有明确边界。当前只有一台 Android 手机的证据，不宣称小米等多品牌或 iOS 已验收。

功能覆盖和实际验收范围统一见 [迁移日志](../MIGRATION_STATUS.md)，避免在多份说明中重复维护测试数量和阶段状态。
