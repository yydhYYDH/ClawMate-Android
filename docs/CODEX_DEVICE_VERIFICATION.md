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

### 本轮修掉的阻塞式调用缺陷

`codexRunTask` 原本在桥接线程上 `runBlocking` 跑完整委托。委托可能持续数分钟，而平台桥接调用
的实测超时是 60 秒——**任何真实委托都必然在桥接层失败，哪怕远端已经开始执行**。

已改为项目既有的 start/poll 模式（`imagePrepareStart`/`imagePreparePoll` 用的就是这套）：
`codexRunTaskStart` 启动协程并立即返回 id，`codexRunTaskPoll` 报告 running 或最终结果，
`codexRunTaskCancel` 放弃等待但**不停止远端任务**。ArkTS 每 700 ms 轮询一次，超窗后报
`status=disconnected`，让模型去同步而不是重发。

### 桥接线程阻塞是本轮发现的一类系统性问题

平台桥接调用有**约 60 秒的硬上限**（实测 `TimeoutException: Waited 60000ms`）。任何超过它的
同步调用都必然失败。本轮找出并修复了**三处**：

| 位置 | 原来 | 现在 |
|---|---|---|
| `codexRunTask` | `runBlocking` 跑完整委托（可达 30 分钟） | `codexRunTaskStart` + `codexRunTaskPoll` |
| `codexTakePhoto` / `codexPickImage` / 导出 | 阻塞最多 300 秒等待 Activity 结果 | `…Start` + `…Result` 轮询 |
| `codexRequest` 单次超时 | 固定 70 秒，超过桥接上限 | 页面传 45 秒；transport 会把调用方请求钳制在自身上限内 |

修复前，这三条路径**在真机上都必然失败**，而单元测试全部通过——因为测试不经过桥接。

### 真机发现的跳转缺陷（已修复）

点击 Codex 空态的“连接指南”在真机上显示“无法打开连接指南”，logcat 明确报
`can't find this page pages/codex/CodexHelpPage`。两个原因叠加：

1. **页面从未带过来**：上游有 `CodexHelpPage.ets`，但适配层没复制，`main_pages.json` 也没注册。
2. **只被 `router.pushUrl` 引用的页面不可达**：import 遍历看不见它，必须显式登记并加入清单。

修复后真机验证：指南可打开、平台选择（Windows/macOS/Linux）可切换、返回正常、无崩溃。
顺手审计了全部 14 个 `pushUrl` 目标，**其余均已正确注册**。

### 真机错误路径验证

手动连接表单填入不可达地址后点击“连接服务器”：

| 场景 | 结果 |
|---|---|
| `127.0.0.1:19999`（端口关闭，Connection refused） | **6 秒内**显示“连接失败：与电脑的网络连接中断，请稍后重试”，无崩溃 |
| `127.0.0.1:19998`（监听但不响应，TLS 握手失败） | 同样快速失败并给出同一提示 |

结论：网络/TLS 失败**快速报错**，不会挂满 45 秒超时；错误文案对用户可理解；
失败后停留表单态可重试，返回导航正常。

另一个副产品：设备 loopback 完全正常（`nc` 能连 `127.0.0.1` 并得到 `Connection refused`），
所以此前的 `adb reverse` 失败是 **adb 转发链路**的问题，不是设备网络栈。

### 仍未验证：Workspace 态

`CodexScreen.Workspace`（任务列表、对话、审批卡片、交付文件）需要成功连上桥接才能进入，
当前设备上没有任何已保存连接，也没有可达的桥接。该屏幕的渲染仍无真机证据。

尝试过但不可行：在设备上运行桥接服务（需 arm64 Node 二进制，`/data/local/tmp` 在
SELinux Enforcing 下执行受限）。

## 真机端到端（已打通）

**2026-11-06 首次完成真机全链路验证。**

| 步骤 | 结果 |
|---|---|
| 粘贴配对信息（含 CA 证书） | 二次确认对话框正常 |
| 确认配对 → `/v1/pair` | **`transportFailure: false`**，TLS + CA 钉扎成功 |
| 进入 Workspace | 显示连接名 `Local Test PC`、工作区 `Fixture Project`、`● 已连接` |
| 输入任务 `hello` 并发送 | 用户消息上屏 |
| Codex 回复 | **`测试成功`**，状态 **`已完成`** |
| 桥接侧核对 | 任务标题 `hello`，状态 `completed` |

### 之前 13 轮失败的真正原因

**不是 `adb reverse` 坏了，而是自签证书没有被信任。**

诊断日志（本轮新增的 `ClawMateCodex` tag）在一秒内给出了答案：

```
SSLHandshakeException: CertPathValidatorException: Trust anchor for certification path not found
```

TLS 握手**确实在进行**，只是证书链无法验证。此前用 `nc` 探测始终无输出，是因为
`toybox nc` 不做 TLS 握手，看起来像"数据不转发"，**把人误导了 13 轮**。

手动连接表单没有 CA 输入入口，所以只有**配对文件**（携带 CA）能连上自签桥接。

### 复现命令

```bash
# 1) 本地桥接（需在 config.json 设 "testPairing": true 以挂载 /v1/pair；仅测试用）
node server.mjs serve config.json

# 2) 端口转发
adb reverse tcp:19970 tcp:19970

# 3) 生成配对串（origin 指向 127.0.0.1:19970，caCertificate 填自签 CA）
#    在手机上「创建连接 → 粘贴配对信息 → 确认配对」
```

### 交付文件（真机已验证）

发送 `deliver a report` 后：

| 项 | 真机结果 |
|---|---|
| 回答内容 | `已生成 report-d5c7855d.md` |
| 交付卡片 | 标题「交付文件」，文件名 `report-d5c7855d.md` |
| 下载确认 | **`1 KB · 已保存到此会话`** |
| 任务状态 | `已完成` |

文件确实从桥接下载到了设备沙盒。

### 已保存连接的一键重连（真机已验证）

重启 App 后 Codex 页列出「我的连接 → Local Test PC / 127.0.0.1:19970」，
轻点即回到 `● 已连接` + `Fixture Project` 工作区。
证明 `EncryptedSharedPreferences` 的保存/读取往返正确。

### 审批：协议层通过，UI 层无法自动读取

发送含 `approve` 的任务后，桥接侧任务状态为 **`waitingApproval`** ——
说明审批请求已被正确接收并反映在服务端。

但此时 `uiautomator dump` **稳定返回 `ERROR: null root node`**，无法读取界面。
复核：App 进程存活、无 FATAL、`runtimeSnapshot` 桥接调用持续正常、窗口 `mCurrentFocus` 正常。

结论：**审批流程协议层正确，卡片渲染存在但 ArkUI-X 在该状态下不暴露可访问性节点**，
无法用自动化确认卡片内容。这需要人工目视或截图审查。

### 审批（真机已验证通过）

发送含 `approve` 的任务后，真机上的 `执行命令申请` 卡片内容：

| 元素 | 内容 |
|---|---|
| 标题 | `执行命令申请` |
| 安全提示 | `确认前请检查命令、目录和申请原因；允许操作可能超出当前沙箱。` |
| 命令详情 | `rm -rf build` + 工作目录 |
| 按钮 | `拒绝`（可点）、**`仅允许本次`（可点）** |
| 状态 | `等待你的确认` |

点击「仅允许本次」后，桥接任务状态由 **`waitingApproval` → `running`**，界面无错误横幅。

### 这条路径修掉的三个真实缺陷

| 缺陷 | 后果 | 定位方式 |
|---|---|---|
| 桥接重新编码时未设 `encodeDefaults` | `canAccept` 变成 `undefined`，**手机永远无法批准**（只能拒绝） | 桥接返回 `canAccept:true`，卡片诊断显示 `ca=undefined` |
| `codexRequest` 在桥接线程 `runBlocking` | 长轮询期间触发系统 ANR（`OplusAnrThrowable`） | logcat 堆栈指向 `codexRequest` |
| `result.task !== undefined` 放过了 `null` | 审批成功后立刻弹 `Cannot read property id of null` | ArkCompiler 堆栈精确到 `applyTask` |

第一条尤其隐蔽：功能"能用"（能找到审批、能拒绝），但**最关键的那个按钮永远点不了**。

### 附件：选图与拍照均已验证

| 能力 | 真机结果 |
|---|---|
| 附件菜单 | 显示「照片 / 相机 / 文件」三项 |
| **选图（相册）** | ✅ 系统选图器打开 → 选中图片 → **附件卡片 `▧ pick-….img ×`** |
| **带附件发送** | ✅ 消息为 `analyze_this 附件：pick-….img`，任务完成，回复 `测试成功` |
| **拍照启动** | ✅ `com.oplus.camera/CapturePhotoActivity` 正常打开 |
| **拍照取消** | ✅ 返回后 `status=cancelled`，页面恢复可用，无崩溃 |
| **拍照确认** | ✅ 见下方「拍照」小节 |

> 早先误以为相机无法自动化，是因为按钮既无 `text` 也无 `content-desc`；
> 改用 `resource-id` 后即可定位。缺陷与结论均在下方记录。

### 选图路径修掉的缺陷

把选取的文件复制到 `cacheDir` 再交给 ArkTS 重新打开，是不可靠的：
缓存目录不保证在两个运行时交接期间保留，文件消失后表现为"复制成功"却读取失败，
用户只看到不明确的错误。已改为写入 `files/`，保留最近 6 个，并把复制失败上报为
`failed`（而非 `cancelled`），让页面能说清发生了什么。

## 聊天委托（C3，真机已验证）

在聊天页对云端模型说「please_use_codex」，模型自行决定调用 `run_codex_task`：

| 观测点 | 结果 |
|---|---|
| `codexRunTaskStart` 桥接调用 | ✅ 已调用 |
| 桥接侧任务 | ✅ 新增 1 个任务，状态 `completed` |
| 委托内容 | 模型自动构造：`输出一句话：hello` + 交付要求 |
| Codex 回复 | `测试成功` |
| 模型对用户的转述 | 「这次连上了，Codex 返回'测试成功'（run_id: f4419c03…）」 |

完整链路：**聊天页 → 云端模型决策 → `run_codex_task` → `CodexBackend` → 桥接 → 结果回传 → 模型描述**。

### 降级行为也已验证

桥接未启动时再次委托，模型回答：

> 「如果你想用 Codex 做实际的事情…**等电脑重新连上后告诉我**，我再重新委托。」

即 `unavailable` 状态被正确传给模型，模型没有谎称成功，也没有自动重发。

### 拍照（真机已完成验证）

| 能力 | 结果 |
|---|---|
| 相机启动 | ✅ `com.oplus.camera/CapturePhotoActivity` |
| 快门 → 确认 | ✅ 经 `resource-id=done_button` 定位 |
| 附件卡片 | ✅ `▧ 586156f4-….jpg ×` |
| 带附件发送 | ✅ `describe_photo 附件：586156f4-….jpg` |
| 任务完成 | ✅ `测试成功`，桥接任务数 +1 |

#### 修掉的缺陷

`CaptureProvider` 只提供**已存在**的文件（`require(it.isFile)`），而 `takePhotoStart`
只构造了路径未创建文件。相机拿到一个打不开的 URI，写入静默失败，App 只能看到"没有文件"
而误判为用户取消。已改为在启动相机前 `createNewFile()`，失败时上报 `failed` 而非 `cancelled`。

> 相机确认按钮没有 `text` 也没有 `content-desc`，是按 `resource-id` 定位的。
> 这解释了上一轮为何判定"无法自动化"——用错了定位维度，不是真的不可测。

## 复现真实联调所需


1. 一台电脑装 Node 22+ 与 `@openai/codex`，完成 `codex login`。
2. `node bridge/codex/server.mjs init <config> <项目目录>`，配置 HTTPS（配对文件可携带 CA）。
3. 手机与电脑同一网络可达；在 App 内导入配对文件或粘贴配对信息。
4. 在 Codex 页创建任务并发一条只读指令（如“概括这个项目的目录结构”）验证最小闭环。

## 2026-11-07 USB 真机复测

OPPO PKB110 / Android 16，已安装 `com.clawmate.shared` `0.3-integrated`。ADB 读取的 APK
SHA-256 与本地构建产物一致。本轮沿用已安装包，在独立测试目录通过 `adb reverse` 连到上游 HTTPS 桥接。
模型链路使用已登录的官方 Codex App Server（本机 CLI `0.167.0-alpha.3.2`）；审批单独使用可控替身。

| 路径 | 真机结果 |
| --- | --- |
| 连接指南 | 可以打开 Windows 指南。 |
| 正式配对 | 粘贴配对信息并确认后，`POST /v1/pair` 返回 200，紧接着 `GET /v1/info` 返回 401。Android 对同地址复用传输层，仍发送一次性配对码。 |
| 导入配对文件和普通文件附件 | 系统选择器可以选中下载目录中的测试 JSON/TXT，回到应用均显示 `No such file or directory`。两处仍直接用 ArkTS `fileIo.open` 读取文档选择器结果。照片选取经 Kotlin 读取，可正常上传。 |
| 真实回复与停止 | 手机收到 `CLAWMATE_USB_REAL_OK`。第二轮实际启动了本地等待命令，手机点停止后桥接 `/interrupt` 返回 200，桥接状态 `interrupted`，手机显示「已中断」。 |
| 文件交付和导出 | 官方 App Server 生成 `usb-proof.md`。重开会话后手机下载并显示「已保存到此会话」，「另存为」导出到下载目录。电脑原件、手机私有副本与导出文件 SHA-256 一致。这台设备对 Markdown 返回无可用预览的提示。 |
| 选图和相机 | 选取人工生成的蓝色 PNG，上传返回 201，官方模型识别为 `Blue`。本轮相机验证到打开和取消后恢复对话。 |
| 自动同步 | 文件交付轮和图片识别轮在电脑端均已完成，手机仍显示「执行中」。从「手机会话」重新打开才刷新终态和结果。主机探针复现了带 `task: null` 的响应提前退出 `mergeCodexTask`、丢弃 `taskDelta`。 |
| 审批（替身） | 审批卡显示后，「仅允许本次」提交成功，`/approvals` 返回 200，待审批项消失。第二条任务点「拒绝」未发请求，状态保持 `waitingApproval`。主机探针确认 `adaptCodexApprovalCard` 覆盖了拒绝按钮原有的 `onClick`。 |
| 重启与重连 | 重启 Android 应用后可点击已保存连接，使用桥接签发的设备令牌进入工作区。验证前已撤销测试桥接对旧配对码的临时接受。 |

为继续测配对之后的流程，临时让隔离的官方桥接接受本次一次性测试码；审批替身用固定的测试凭据完成配对。
这两项兼容设置仅用于本轮测试。测试完成后移除了手机测试连接、共享目录中的测试输入/导出文件和两条 USB 转发，并停止测试桥接。
聊天页委托、局域网和电脑端已有会话接力未在本轮复测。官方模型质量仅验证固定文本与单张合成图。

## 2026-11-12 扫码与工作台回归

在 OPPO PKB110 安装本轮共享 Debug APK。USB 转发连接上游 `e876199` HTTPS 桥接及 `PairingManager`，使用隔离工作目录和受控 App Server 替身。两次配对各生成独立一次性凭证。

| 路径 | 结果 |
| --- | --- |
| 相册识码 | 从系统文件选择器选测试二维码，出现电脑名和 HTTPS 地址的确认框。确认后 `POST /v1/pair`、`GET /v1/info`、`GET /v1/tasks` 均为 200，工作区显示「已连接」。 |
| 相机取景与取消 | 系统提供本次相机授权，授予后 CameraService 确认 ClawMate 打开后置相机。点击「取消」回到配对页。待将手机对准真实电脑屏幕，验收取景识码到配对。 |
| 配对文件 | 从 OPPO 文件选择器「文件 → 全部文件 → Download」选择测试 JSON，确认后同源再次配对，`/v1/pair` 和 `/v1/info` 均为 200。 |
| 普通附件与交付 | 选取 TXT 后 `POST /v1/uploads` 返回 201，保留文件名。下发带附件的受控任务后，原会话自动显示「已完成」和「已保存到此会话」。点回复中的 Markdown 链接触发原生打开，本机对该 Markdown 格式提示使用「另存为」。 |
| 拒绝审批 | 在替身产生的审批卡点「拒绝」，`POST /v1/tasks/{id}/approvals` 返回 200。 |
| 设备协议回归 | `CodexPairingDeviceTest` 通过，覆盖配对无 bearer、同源更新设备令牌、空任务字段与有效增量、401 状态回传。 |

构建通过 ArkTS、运行时签封校验及 Android Debug APK 打包。设备测试 APK 使用仓库既有的非会议筛选，会议 SDK 使用本机替身。普通聊天的新选择器未做真实云服务端到端验收，续办回归在 JVM 模拟 HTTPS 协议数据上覆盖持久化、复用原任务与提交第二轮。

## 2026-11-12 Windows 桌面 1.1.15 二维码复测

- Windows 已安装连接器实际输出 479×479 像素的配对 PNG。旧 ZXing 读取失败，桌面探针发现检测器把 113×113 模块估算成 117×117。修正采样网格后解码结果与原配对数据完全一致。ZXing 3.5.4 配合重采样也能读取按桌面显示尺寸缩放的受控样本。
- Android Debug APK 已安装到 OPPO PKB110 并核对 APK 哈希。从相册选择这份真实桌面二维码，进入电脑名称和 HTTPS 地址的确认框。诊断图片的配对码已被替换，测试在确认前取消，未新增设备授权。实际相机对准电脑屏幕仍待验收。
- 用 PC 配对 CA 从 Windows 本机连接桥接，TLS 校验成功，匿名健康请求收到预期 401。同一台手机从 WLAN 测试同一 TCP 地址和端口，返回 `No route to host`，因此没有完成真实局域网配对。
- `:android-capabilities:testDebugUnitTest --tests 'com.clawmate.platform.codex.*'` 通过 68 项，14 项 live 跳过。新增回归覆盖相机灰度帧与相册 RGB 的非整数像素渲染。共享版 Debug APK 构建及中文手册检查通过。
