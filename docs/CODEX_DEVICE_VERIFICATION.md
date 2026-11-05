# Codex 真机验证记录（PKB110 / Android 16）

日期：2026-11-06。设备 `3B159H008Y100000`，型号 PKB110，Android 16，`com.clawmate.shared`。

## 已验证（本机实际点击、截图与 UI dump 佐证）

| 项 | 方法 | 结果 |
|---|---|---|
| APK 安装 | `adb install -r shared-app-debug.apk` | Success |
| 冷启动 | `monkey -p com.clawmate.shared`，等 12s | 进程存活，`FATAL EXCEPTION` 计数 0 |
| 六 Tab | `uiautomator dump` 统计 | 首页/汇总/聊天/Codex/任务/我的 全部存在 |
| 进入 Codex | 点击底栏 Codex | 渲染连接卡片、“下载 PC 端的 Codex 连接器”、“连接指南”、“Tailscale 组网教程”、“连接凭据保存在手机安全存储中” |
| 进入配对页 | 点“创建连接” | 出现“把 Codex 带到手机”、扫描二维码 / 导入配对文件 / 粘贴配对信息 三项 |
| 粘贴输入 | 点“粘贴配对信息”，聚焦后 `input text` | 文本进入输入框并显示 `CLAWMATE-CODEX-PAIR` |
| 参数校验 | 点“确认配对” | 提示“请使用电脑端生成的完整配对信息，证书指纹不能用来配对”，无崩溃 |
| 返回键 | `input keyevent 4` | 退回 Codex 连接页，未退出应用 |
| 聊天 Tab | 点击“聊天” | 正常切换 |

## 未验证（必须说明）

以下**没有真实证据**，因为本机没有运行 PC 桥接服务，也没有有效配对：

- 配对成功（`POST /v1/pair` 换取设备令牌）
- `/v1/info` 能力协商、工作目录与模型列表
- 新建任务、下发回合、长轮询跟随
- 审批卡片与补充回答
- 中断（停止）请求与远端确认
- 交付文件下载、SHA256 校验、打开与另存为
- 聊天里 `run_codex_task` 的真实委托（需要云端模型 + 已配对电脑）

这些路径目前只有单元测试覆盖：`CodexTransportTest`(16)、`CodexBackendTest`(9)、
`CodexTypesTest`(14)、`CodexCredentialStoreTest`(14)、`CodexTrustFactoryTest`(9)，
以及 ArkTS 层的 `verify-codex-tool.mjs`(16)。

## 协议层（本机桥接，非真机）

在本机用仓库自带 `test/fake-app-server.mjs` 起了一个真实 HTTPS 桥接（自签 CA），
用 `CodexLiveBridgeTest` 以生产的 `CodexTransport` 代码路径跑通：

| 项 | 结果 |
|---|---|
| `/v1/info` 能力协商 | 通过 |
| 建任务 → 下发 → 完成，回复归属正确回合 | 通过 |
| 错误令牌 → 认证失败（非通用错误） | 通过 |
| 配对 CA 钉扎后可连接 | 通过 |
| 未钉扎客户端拒绝自签服务 | 通过 |
| 整包 5 项 | **5/5，0 跳过** |

`CodexBackendLiveTest` 在同一个桥接上跑完整委托（连上 → 建任务 → 下发 → 跟随 → 结束）：

| 项 | 结果 |
|---|---|
| 委托到达终态并带回复 | 通过 |
| 声明的能力与实际一致 | 通过 |
| 未配对时拒绝执行 | 通过 |
| 整包 3 项 | **3/3** |

### 这两个测试发现的真实缺陷（单测全部漏掉）

| 缺陷 | 后果 | 为何单测没发现 |
|---|---|---|
| 下发请求体缺少 `attachments` | **每个真实任务都会被桥接拒绝** | mock dispatcher 不校验该字段 |
| 手动设 `Accept-Encoding` 导致 OkHttp 不解压 | **≥1024 字节的响应全部解析失败** | mock 响应都很小 |
| `{"unchanged":true}` 长轮询被当成错误 | 等待超过服务端窗口就中断 | mock 从不返回 `unchanged` |

三个都已修复并补了回归测试。

**这一步发现了一个 MockWebServer 测不出的真实缺陷**：kotlinx 默认省略等于默认值的字段，
导致下发回合的请求体缺少 `attachments`，桥接返回 `Invalid input`。已修为 `encodeDefaults = true`。

## 真机联调尝试（未完成）

用 `adb reverse tcp:8787` 把手机指向本机 HTTPS 桥接，尝试在 App 内完成配对。
过程中确认与发现：

| 观察 | 结论 |
|---|---|
| 输入完整配对串 → 提示“配对信息无效或已过期” | 时效校验在真机生效（配对串确实超过 5 分钟） |
| 重新生成配对串 → 二次确认对话框出现 | 解析与确认流程正确 |
| 点确认后桥接收到 `codexRequest`，返回 `Route not found` | **裸桥接不挂载 `/v1/pair`**：该路由仅在传入 `pairing` 扩展时启用，属电脑端产品组件 |
| 对话框被外部关闭后页面永久“正在处理…” | **真实缺陷**，已修（`CodexConfirm` 超时兜底） |
| 手动连接表单用 `adb shell input text` 填不进去 | ArkUI-X 输入框与 adb 输入的兼容问题，阻塞后续 UI 自动化 |

未完成原因：真机 UI 自动化无法可靠填入输入框，且裸桥接不支持配对路由。
要在真机上跑通，需**电脑端打包应用**（自带配对扩展与本机 TLS 证书），由人手完成配对。

## 复现真实联调所需


1. 一台电脑装 Node 22+ 与 `@openai/codex`，完成 `codex login`。
2. `node bridge/codex/server.mjs init <config> <项目目录>`，配置 HTTPS（配对文件可携带 CA）。
3. 手机与电脑同一网络可达；在 App 内导入配对文件或粘贴配对信息。
4. 在 Codex 页创建任务并发一条只读指令（如“概括这个项目的目录结构”）验证最小闭环。
