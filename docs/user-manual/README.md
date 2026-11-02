# ClawMate 图文用户手册

用户入口：[打开图文手册](index.html)。下载或分发时保留本目录的全部文件，可直接用浏览器打开 `index.html`，不需要服务器、账号或网络。支持首页目录卡片、独立章节、上一章/下一章、浏览器前进后退、搜索结果页和原图查看。旧的 `#home` 等书签继续有效，刷新保留当前章节。无 JavaScript 时退回完整长文，正文、目录及图片链接仍可阅读。

后续移植 App 的资源清单、路由约定和返回接口见[App 接入说明](APP_INTEGRATION.md)。`navigation.js` 不依赖 DOM；`app-manifest.json` 与 `index.html` 一起从内容源生成，`manual.js` 只负责浏览器视图和历史适配。当前尚未接入应用运行时。

本版对应 Android 共享前端 `0.3-integrated`，正文在 SH-22 基础上随 SH-24 更新了 MiMO 语音说明；章节导航与接入契约于 2026-10-29 完成 UM-02。不是旧 Compose 版或鸿蒙版的完整能力说明。手册尚未内嵌应用；首次引导、模型字段、会议与分身新流程暂以文字/流程图说明，未伪造截图。

## 更新方法

1. 修改 `content.json` 中对应章节。每章写清入口、前置条件、操作、完成标志、失败恢复与数据去向。用用户能找到的按钮名称；区分本机、云端、尚未接入和待验收。
2. 截图只用当前应用的空数据或公开测试数据页面。逐张打开核看，不导入通知、凭据、私人消息、照片或设备标识。保留原图，不用生成图冒充实际界面。截图日期、来源、SHA256 和差异记入 `screenshots.json`，并同步图注。
3. 更新日期；执行 `python docs/user-manual/build.py` 生成 `index.html` 和 `app-manifest.json`，不要单独编辑生成文件。章节 ID 是 App 接入的稳定标识，不随标题改名。
4. 执行 `python docs/user-manual/build.py --check`、`node --check docs/user-manual/manual.js` 和 `node --test docs/user-manual/navigation.test.cjs`。浏览器检查宽屏/窄屏、目录/翻页/正文跳转、前进后退、刷新/直接地址、搜索与返回结果、未知路由恢复、图片放大/Esc 和打印预览；确认无水平溢出、无缺图。打印样式会显示全部章节，实际 PDF 排版仍待验收。
5. 在 `MIGRATION_STATUS.md` 记录实际检查、缺图/平台限制并提交本阶段。功能或按钮变化的提交应一并修改相应章节；若未能取得新截图，明确标注旧图差异，不能静默沿用。

生成和文件校验仅依赖 Python 标准库，路由测试使用 Node 内置测试库；阅读资源全部本地加载，不使用 CDN、统计或 localStorage。URL/浏览器历史保存当前章节、搜索词和滚动位置，刷新会重置手册内部返回栈；不要把凭据填入搜索框。内容中的 HTML 是仓库维护者编写的静态源，不能直接填入未经审查的模型输出或导入数据。

## 内容与实现对应

| 章节 | 主要核对来源（均为本仓库或本地只读输入） |
| --- | --- |
| 首次使用、导航、聊天、图片、首页 | `shared-frontend/app/Index.ets`、`adapters/platform/ChatImages.ets`；锁定页面 `pages/chat`、`pages/home`、`pages/onboarding` |
| 模型、设置、隐私 | 锁定并适配后的 `pages/settings/SettingsCenterPage.ets`、`SettingsCenterTypes.ets`；`adapters/utils/AiServiceAccess.ets` |
| 图库、汇总、记忆 | `gallery-source.mjs`、`adapters/platform/MemoryQuery.ets`；`docs/GALLERY_SCAN_RECOVERY.md`；适配后的 `MemorySyncPage.ets` |
| 工作流、提醒 | `adapters/platform/AndroidWorkflow.ets`、`WorkflowVlm.ets`；适配后的 `pages/task/TaskPage.ets`；`docs/COLLECTION_REMINDERS.md` |
| 会议 | `docs/MEETING_INTEGRATION.md`、`adapters/platform/MeetingApi.ets` |
| 分身、语音、能力边界 | `chat-avatar-source.mjs`、`adapters/platform/ChatAvatar.ets`、`AndroidAvatar.ets`；`MIGRATION_STATUS.md` SH-20～SH-22 与各验证报告 |

锁定页面的审阅输入位于忽略目录 `.arkui-x-validation/application/project/entry/src/main/ets/`；此目录只用于核对，不是手册构建依赖。维护时通过既有源码准备流程更新，不改鸿蒙参考项目。界面改进需求（应用内帮助入口、首次配置向导、字段通俗解释）仍需独立实现与验收，本阶段交付手册，不宣称已解决应用本身的易用性问题。
