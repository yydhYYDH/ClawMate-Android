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
| 手动连接表单用 `adb shell input text` 填不进去 | 当时的结论，后经复测推翻，见下方更正 |

### 更正：输入框其实可以自动化

后续复测推翻了上面的结论。输入失败的真实原因是**没先让输入框获得焦点**：

1. 点击输入框后必须等约 2 秒，确认 `focused="true"` 且键盘 `mInputShown=true`；
2. 密码框（token）无法被直接点击聚焦，需先聚焦地址框，再用 **TAB（keyevent 61）** 切过去；
3. 键盘弹出会**改变布局**，每次点击前都要重新 `uiautomator dump` 取坐标，不能用旧坐标。

按这三条操作后，两个字段都成功填入（地址明文可见，token 显示为 43 个圆点），
点击“连接服务器”后 logcat 中确实出现了 `codexRequest$1309`。

### 仍阻塞的一步：设备的 TCP 连不到主机

请求发出后返回 `transportFailure: true`。逐层排查：

| 尝试 | 结果 |
|---|---|
| `adb reverse tcp:8787` | 列表显示已建立，但设备 `/proc/net/tcp` 中 8787 **没有监听项** |
| 设备侧 `nc -z 127.0.0.1 8787` | 失败 |
| 改桥接监听 `0.0.0.0`，手机走局域网直连 `10.168.249.142` | `nc: connect: No route to host` |
| 添加 Windows 入站防火墙规则 | **需要管理员权限，创建失败** |

结论：本次会话环境下设备到主机的 8787 端口不通。要在真机跑通，需要以下任一条件：
- 以管理员权限放行 8787 入站，手机与电脑同网段；
- 或使用电脑端打包应用（自带配对扩展与证书，走正常局域网）；
- 或换一台 `adb reverse` 可用的设备/USB 驱动。

### 桥接安全属性（真实服务端验证）

| 属性 | 结果 |
|---|---|
| 相同 `requestId` 重发 | `200`，返回原任务，用户输入只记录一次 |
| 相同 `requestId` 但内容不同 | `409` 拒绝 |
| 中断请求 | 返回任务快照，UI 可据此显示“已确认停止” |
| 未知路由 | 类型化 HTTP 失败，不崩溃 |
| 桥接重启后任务可读 | 17 个任务全部恢复，旧任务状态仍为 `completed` |

### 真机离线行为

停掉桥接后重启 App：正常启动、六 Tab 完整、**无 FATAL**；进入 Codex 页可正常渲染连接卡片，
没有卡死或空白页。

### 尚未覆盖

- ~~交付文件下载~~：**已覆盖**。扩展替身（`tests/codex/android-fixture-app-server.mjs`）的
  “deliver” 回合会在 `outputs/` 写入文件并给出 Markdown 链接，`CodexDeliveryLiveTest` 验证
  手机落地、内容正确、落在沙盒目录内。
- ~~审批卡片~~：**已覆盖**。“approve” 回合发起 `item/commandExecution/requestApproval`，
  测试确认上报 `waitingApproval` 且**不误交付文件**。
- **模型推理质量**：替身不调用任何模型，无法覆盖。
- **真实 Codex CLI 差异**：替身按协议实现，不代表官方 App Server 的全部细节。
- **执行中真正中断**：替身的 `turn/interrupt` 直接返回，未模拟运行中打断。

复现方法见 [tests/codex/README.md](../tests/codex/README.md)。

## 复现真实联调所需


1. 一台电脑装 Node 22+ 与 `@openai/codex`，完成 `codex login`。
2. `node bridge/codex/server.mjs init <config> <项目目录>`，配置 HTTPS（配对文件可携带 CA）。
3. 手机与电脑同一网络可达；在 App 内导入配对文件或粘贴配对信息。
4. 在 Codex 页创建任务并发一条只读指令（如“概括这个项目的目录结构”）验证最小闭环。
