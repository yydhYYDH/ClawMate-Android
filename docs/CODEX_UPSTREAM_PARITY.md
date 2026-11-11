# Codex 上游对照

对照上游 `coevomind-app` 的 [e876199](https://github.com/doulujiyao12/mobiinfra-oh/tree/e876199f382f32530af850dff20cfd6178a55554)，以及 Android 原先锁定的 `4e86f62`。核查范围包括手机工作台、普通聊天、首页建议和提醒、PC 连接器、HTTPS 桥接与存储。

| 功能 | 上游实现 | Android 对照结果 |
| --- | --- | --- |
| 配对、记住电脑、重连 | `CodexConnectionViews`、`CodexPage`、`CodexClient`；PC `PairingManager` | 相机和相册识码、导入配对文件、粘贴、HTTPS 手动连接、加密存储已接入。修复同源令牌缓存、配对请求误带 bearer 以及 401 回传。 |
| 配置与 PC 管理 | `CodexHelpPage`、`CodexHelpContent`、`pc-companion/host` | Android 有安装与连接指南。设置工作目录、创建和撤销配对仍由电脑端负责。 |
| 会话与同步 | `CodexPage`、`CodexChatViews`、桥接 `history.mjs`、`task-sync.mjs` | 工作目录、模型、推理强度、手机会话、电脑会话列表、历史分页、分支和继续会话已有入口。修复显式空 `task` 丢弃增量，及桥接取消请求串扰。真实桌面会话接力仍待验收。 |
| 附件、审批和交付 | `CodexApprovalCard`、`CodexArtifactStore`、`CodexMarkdownLinks`、桥接 `artifacts.mjs` | 图片、拍照、普通文件、审批回答、仅允许本次、拒绝及下载、打开、另存为已接入。回复链接按上游规则匹配会话内交付，普通网页链接交给系统浏览器。 |
| JSON 体积预算 | `CodexRequestBudget`、桥接 `bodyJson` | 已在 Android 工作台复用上游 UTF-8 请求限制，发送前拒绝超限请求。 |
| 普通聊天调用 Codex | `CodexTaskTool`、`CodexChatTargetPicker`、`CodexChatService`、`CodexChatRunCard` | `SharedAgent` 新委托接入上游的电脑、目录和确认选择器。Kotlin 修复委托状态落盘和同会话续办，续办会向原任务提交新一轮。聊天内审批/停止/同步卡、交付文件可视化和图片委托仍有差距。 |
| 委托前资料准备 | `CodexTaskPreparation`、`CodexMemoryPlan`、`CodexMemorySelection`、`CodexProfileLookup`、`CodexReferenceContext` | Android 已有经用户确认的本机摘录。上游的多步资料检查、精确事件与原文补齐及范围/字节预算仍需与 Android 数据层核对。 |
| 首页交给 Codex | `HomePage`、`HomeTaskExecutionSheet`、`HomeCodexHandoff`、`HomeCodexContext`、`HomeCodexAttachments` | 上游会从首页灵感和适用提醒转交选中的证据、截图及目标，创建独立手机聊天。Android 原先没有该入口。 |

上游 PC 连接器版本为 `1.1.15`。Android 工作台保留现有 App ID 与本机凭据，桥接与连接器继续复用上游。各路径的设备与服务验收见 [Codex 真机记录](CODEX_DEVICE_VERIFICATION.md)。
